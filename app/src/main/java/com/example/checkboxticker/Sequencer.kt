package com.example.checkboxticker

import kotlin.math.abs

/**
 * A checkbox as found on one screenful, in screen pixels. Plain numbers rather than
 * android.graphics.Rect, so the run's logic can be tested away from a phone.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val height get() = bottom - top
}

/** Where the run is. Exactly one box is ever between TAP_CURRENT and RECORD. */
enum class Phase { IDLE, FIND_CURRENT, TAP_CURRENT, VERIFY_CURRENT, CLEAR_POPUP, SCROLL }

/** What a run has done so far, for the report and the status line. */
data class Failure(val physical: Int, val shown: Int)

/**
 * The run's bookkeeping, and the rule that picks the next box.
 *
 * The rule is the whole fix. Boxes are identical and stay unchecked when a tap fails, so
 * "the first unchecked box on screen" can be the same failed box for ever. Instead the run
 * keeps a cursor: a line on the screen above which every box has been dealt with. A box is
 * dealt with the moment its result is recorded, and the line then sits just below it. Each
 * scroll moves the line up by however far the page actually moved. The next box is the first
 * unchecked one below the line - so a failed box, which is above it, can never come back,
 * without the run having to recognise it again after a scroll.
 */
class Sequencer {

    var phase = Phase.IDLE
        private set

    /** The physical number of the box being dealt with, counting from 1 across the run. */
    var attempt = 0
        private set
    var successes = 0
        private set
    val failures = ArrayList<Failure>()

    /** Failures back to back - a run that only fails is stopped rather than left going. */
    var failStreak = 0
        private set

    /** Everything above this screen line has been dealt with. Null until the first box. */
    var cursor: Int? = null
        private set

    var current: Box? = null
        private set

    fun begin() {
        phase = Phase.FIND_CURRENT
        attempt = 0
        successes = 0
        failures.clear()
        failStreak = 0
        cursor = null
        current = null
    }

    fun stop() {
        phase = Phase.IDLE
        current = null
    }

    /** FIND_CURRENT: the first unchecked box below the line, top to bottom. */
    fun pick(found: List<Box>): Box? =
        found.filter { box -> cursor.let { it == null || box.centerY > it } }
            .minByOrNull { it.top }

    /** TAP_CURRENT: [box] is now the one box being dealt with. */
    fun start(box: Box) {
        attempt++
        current = box
        phase = Phase.TAP_CURRENT
    }

    fun verifying() {
        phase = Phase.VERIFY_CURRENT
    }

    /** RECORD, success. */
    fun succeeded() {
        successes++
        failStreak = 0
        finishCurrent()
    }

    /**
     * RECORD, failure. Returns the number the box is shown as: its place once the boxes that
     * failed before it are taken out, so physical 3, 6 and 9 failing read as 3, 5 and 7.
     */
    fun failed(): Int {
        val shown = attempt - failures.size
        failures.add(Failure(attempt, shown))
        failStreak++
        finishCurrent()
        return shown
    }

    /** The current box is settled: the line drops to just below it, and it is let go. */
    private fun finishCurrent() {
        val box = current ?: return
        cursor = box.centerY + margin(box)
        current = null
        phase = Phase.CLEAR_POPUP
    }

    fun scrolling() {
        phase = Phase.SCROLL
    }

    /** SCROLL done: the page moved up by [movedPx], and the line with it. */
    fun scrolled(movedPx: Int) {
        cursor = cursor?.minus(movedPx)
        phase = Phase.FIND_CURRENT
    }

    companion object {

        /**
         * How far below a settled box's middle the line sits. More than half a box, so the
         * box itself is always above it even if the page's movement is misjudged by a few
         * pixels; far less than the gap to the next box, so that one is never skipped.
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
