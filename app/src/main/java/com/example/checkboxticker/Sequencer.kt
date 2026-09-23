package com.example.checkboxticker

import kotlin.math.abs

/**
 * A checkbox as found by one scan, in screen pixels. Plain numbers rather than
 * android.graphics.Rect, so the run's logic can be tested away from a phone.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val height get() = bottom - top
}

/**
 * Where a run is. Every box goes through the same seven states, in this order, and no
 * other order is accepted:
 *
 *   FIND_TARGET -> TAPPING -> VERIFYING -> RECORDING_RESULT -> CLEARING_POPUP
 *               -> SCROLLING -> WAITING_FOR_SCROLL -> FIND_TARGET
 *
 * Two extra moves exist. VERIFYING -> VERIFYING is the one extra look at the same box
 * when the screen may have been slow (a look, never a tap). FIND_TARGET -> SCROLLING is
 * scrolling on when a scan finds nothing to do. Anything may go to IDLE, which is stopping.
 */
enum class TickState {
    IDLE,
    FIND_TARGET,
    TAPPING,
    VERIFYING,
    RECORDING_RESULT,
    CLEARING_POPUP,
    SCROLLING,
    WAITING_FOR_SCROLL
}

/** A box that would not tick, by its place on the page and by its number in the report. */
data class Failure(val physical: Int, val shown: Int)

/**
 * One run: the single box being dealt with, the counts, the order of the steps, and the
 * rule that picks the next box.
 *
 * The rule is what stops a failed box coming back. All boxes look the same and a failed one
 * stays empty, so "the first empty box on screen" can be the same failed box for ever.
 * Instead the run reads the page top to bottom, like a finger moving down it: there is a
 * line across the screen, and every box above it has been dealt with. When a box's result
 * is recorded, successful or not, the line drops to just below it. When the page scrolls,
 * the line moves up with the page by however far the page actually moved. The next target
 * is always the first empty box below the line in a fresh scan - so the failed box, which
 * is above the line, is never chosen again. Nothing about the failed box itself has to be
 * remembered or recognised; the line is only "how far down the page the run has got".
 */
class Sequencer {

    var state = TickState.IDLE
        private set

    /** The physical number of the box in hand, counting from 1 down the page. */
    var attempt = 0
        private set
    var successes = 0
        private set
    val failures = ArrayList<Failure>()

    /** Failures back to back, for the optional "stop after n failures in a row". */
    var failStreak = 0
        private set

    /** Everything above this screen line has been dealt with. Null until the first box. */
    var cursor: Int? = null
        private set

    /** The one target of the run. Only ever set in TAPPING, VERIFYING and RECORDING_RESULT. */
    var current: Box? = null
        private set

    val currentAttempt get() = attempt
    val failedCount get() = failures.size

    /** Whether [to] may follow the state the run is in now. */
    fun canMove(to: TickState): Boolean = to == TickState.IDLE || to in NEXT.getValue(state)

    /** Moves to [to] if that is a legal next step. Refuses, and changes nothing, if not. */
    fun moveTo(to: TickState): Boolean {
        if (!canMove(to)) return false
        state = to
        return true
    }

    fun begin() {
        attempt = 0
        successes = 0
        failures.clear()
        failStreak = 0
        cursor = null
        current = null
        state = TickState.FIND_TARGET
    }

    fun stop() {
        current = null
        state = TickState.IDLE
    }

    /** FIND_TARGET: of one fresh scan, the first empty box below the line. One, never more. */
    fun pick(found: List<Box>): Box? {
        if (state != TickState.FIND_TARGET) return null
        return found.filter { box -> cursor.let { it == null || box.centerY > it } }
            .minByOrNull { it.top }
    }

    /** FIND_TARGET -> TAPPING: [box] becomes the target, and the only one. */
    fun start(box: Box): Boolean {
        if (!moveTo(TickState.TAPPING)) return false
        attempt++
        current = box
        return true
    }

    /** VERIFYING -> RECORDING_RESULT, success. */
    fun succeeded(): Boolean {
        if (state != TickState.VERIFYING || !moveTo(TickState.RECORDING_RESULT)) return false
        successes++
        failStreak = 0
        settleCurrent()
        return true
    }

    /**
     * VERIFYING -> RECORDING_RESULT, failure. Returns the number the box is shown as in the
     * report - its place once the boxes that failed before it are taken out - or null if the
     * run was not verifying. Physical 3, 6 and 9 failing are shown as 3, 5 and 7.
     */
    fun failed(): Int? {
        if (state != TickState.VERIFYING || !moveTo(TickState.RECORDING_RESULT)) return null
        val displayedNumber = currentAttempt - failedCount
        failures.add(Failure(attempt, displayedNumber))
        failStreak++
        settleCurrent()
        return displayedNumber
    }

    /** The target is finished with: the line drops below it, and it stops being the target. */
    private fun settleCurrent() {
        val box = current ?: return
        cursor = box.centerY + margin(box)
        current = null
    }

    /** WAITING_FOR_SCROLL -> FIND_TARGET: the page moved up by [movedPx], and the line too. */
    fun scrolled(movedPx: Int): Boolean {
        if (!moveTo(TickState.FIND_TARGET)) return false
        cursor = cursor?.minus(movedPx)
        return true
    }

    companion object {

        /** The legal next steps from each state. IDLE is always allowed on top of these. */
        private val NEXT: Map<TickState, Set<TickState>> = mapOf(
            TickState.IDLE to setOf(TickState.FIND_TARGET),
            TickState.FIND_TARGET to setOf(TickState.TAPPING, TickState.SCROLLING),
            TickState.TAPPING to setOf(TickState.VERIFYING),
            TickState.VERIFYING to setOf(TickState.VERIFYING, TickState.RECORDING_RESULT),
            TickState.RECORDING_RESULT to setOf(TickState.CLEARING_POPUP),
            TickState.CLEARING_POPUP to setOf(TickState.SCROLLING),
            TickState.SCROLLING to setOf(TickState.WAITING_FOR_SCROLL),
            TickState.WAITING_FOR_SCROLL to setOf(TickState.FIND_TARGET)
        )

        /**
         * How far below a finished box's middle the line sits. More than half a box, so the
         * box stays above the line even if the page's movement is misread by a few pixels;
         * far less than the gap to the next box, so that one is never skipped.
         */
        fun margin(box: Box) = (box.height * 3) / 5 + 4

        /**
         * How far the page moved between two pictures, from their row profiles - the average
         * brightness of each row. Content that moved up by d rows makes after[y] look like
         * before[y + d]. The shift that matches best wins; among near-equal matches the one
         * closest to [expected] (the swipe) wins, which settles repeating lists where one
         * period of movement looks the same as none. Returns rows of the profile.
         */
        fun measureShift(before: IntArray, after: IntArray, expected: Int, maxShift: Int): Int {
            val n = minOf(before.size, after.size)
            if (n < 8) return expected
            val limit = maxShift.coerceIn(0, n / 2)

            val errors = DoubleArray(limit + 1)
            for (d in 0..limit) {
                var sum = 0L
                val overlap = n - d
                for (y in 0 until overlap) sum += abs(after[y] - before[y + d])
                errors[d] = sum.toDouble() / overlap
            }

            val best = errors.minOrNull() ?: return expected
            val band = best * 1.1 + 0.5
            var choice = 0
            var choiceDistance = Int.MAX_VALUE
            for (d in 0..limit) {
                if (errors[d] > band) continue
                val distance = abs(d - expected)
                if (distance < choiceDistance) {
                    choiceDistance = distance
                    choice = d
                }
            }
            return choice
        }
    }
}
