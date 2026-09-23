package com.example.checkboxticker

import android.graphics.Rect
import kotlin.math.abs

/** Where a box has got to. */
enum class BoxState { CANDIDATE, TAPPED, VERIFYING, COMPLETED, FAILED }

/**
 * One checkbox, known only by where it is.
 *
 * Identical boxes cannot be told apart by name, id, number or colour, so a box's identity
 * here is its rectangle: matched with a tolerance, and carried along when the page scrolls.
 */
class Candidate(rect: Rect) {
    val rect = Rect(rect)
    var state = BoxState.CANDIDATE
    var taps = 0
    var number = 0                  // its place in the run, given when it is first tapped

    /** Still worth a go: never tried, or tried and allowed another. */
    fun actionable(maxRetries: Int) =
        state == BoxState.CANDIDATE || (state == BoxState.TAPPED && taps <= maxRetries)
}

/**
 * The boxes on the page as they stand, and the ones already finished with.
 *
 * This is what stops a run rescanning after every tap: one look fills the list, and the run
 * works down it, looking again only when the list is used up or the page has moved. A box
 * that will not tick is marked failed and never offered again, so it cannot hold the run up.
 */
class Candidates {

    private val list = ArrayList<Candidate>()
    private val failed = ArrayList<Rect>()
    private val completed = ArrayList<Rect>()

    val size get() = list.size

    fun waiting(maxRetries: Int) = list.count { it.actionable(maxRetries) }

    /** The rectangles worth matching after a scroll: failures stay visible, so they anchor it. */
    fun anchors(): List<Rect> = failed.map { Rect(it) }

    fun clear() {
        list.clear()
        failed.clear()
        completed.clear()
    }

    /** Fills the list from a fresh look, leaving out whatever is finished or given up on. */
    fun refill(found: List<Rect>, tolerance: Int) {
        list.clear()
        for (rect in found) {
            if (failed.any { near(it, rect, tolerance) }) continue
            if (completed.any { near(it, rect, tolerance) }) continue
            if (list.any { near(it.rect, rect, tolerance) }) continue    // seen twice in one look
            list.add(Candidate(rect))
        }
    }

    fun next(maxRetries: Int): Candidate? = list.firstOrNull { it.actionable(maxRetries) }

    fun markCompleted(candidate: Candidate) {
        candidate.state = BoxState.COMPLETED
        completed.add(Rect(candidate.rect))
        trim(completed)
    }

    fun markFailed(candidate: Candidate) {
        candidate.state = BoxState.FAILED
        failed.add(Rect(candidate.rect))
        trim(failed)
    }

    /** Everything known moves with the page; what has gone off the top is forgotten. */
    fun shift(dy: Int) {
        if (dy == 0) return
        for (candidate in list) candidate.rect.offset(0, dy)
        for (rect in failed) rect.offset(0, dy)
        for (rect in completed) rect.offset(0, dy)
        list.removeAll { it.rect.bottom <= 0 }
        failed.removeAll { it.bottom <= 0 }
        completed.removeAll { it.bottom <= 0 }
    }

    private fun trim(rects: ArrayList<Rect>) {
        while (rects.size > 300) rects.removeAt(0)
    }

    companion object {

        /** The same box, allowing for the small movement between one look and the next. */
        fun near(a: Rect, b: Rect, tolerance: Int): Boolean =
            abs(a.centerX() - b.centerX()) <= tolerance &&
                    abs(a.centerY() - b.centerY()) <= tolerance

        /**
         * How far the page really moved, read from the boxes themselves rather than assumed
         * from the swipe: the shift that most of [before] agree on when matched against
         * [after]. A fling, a bounce or a refused scroll all move by their own amount, so
         * [expected] is only the fallback when nothing matches.
         */
        fun measureShift(
            before: List<Rect>,
            after: List<Rect>,
            expected: Int,
            tolerance: Int
        ): Int {
            if (before.isEmpty() || after.isEmpty()) return expected

            val offsets = ArrayList<Int>()
            for (old in before) {
                for (fresh in after) {
                    if (abs(old.width() - fresh.width()) > tolerance) continue
                    if (abs(old.height() - fresh.height()) > tolerance) continue
                    if (abs(old.centerX() - fresh.centerX()) > tolerance) continue
                    offsets.add(fresh.centerY() - old.centerY())
                }
            }
            if (offsets.isEmpty()) return expected

            var best = expected
            var bestVotes = 0
            for (offset in offsets) {
                val votes = offsets.count { abs(it - offset) <= tolerance }
                if (votes > bestVotes) {
                    bestVotes = votes
                    best = offset
                }
            }
            return best
        }
    }
}
