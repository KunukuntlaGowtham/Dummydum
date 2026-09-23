package com.example.checkboxticker

import kotlin.math.abs

/**
 * How far the page really moved during a scroll, read from two pictures of it.
 *
 * A swipe asks the page to move a set distance, but a page does not always go that far - and
 * at the bottom it does not move at all. Anything that assumes the swipe's length (such as
 * the list of boxes already tried) then ends up in the wrong place.
 */
object PageShift {

    /** Where a checkbox's middle was on screen, in screen pixels. */
    data class Spot(val x: Int, val y: Int)

    /**
     * How far the page moved, read from the checkboxes seen just before and just after the
     * scroll. This is the reliable measure: boxes that were not touched move exactly as the
     * page does, and at the bottom of a page - where the page does not move - the box that
     * would not tick and the ones below it are all still in exactly the same place.
     *
     * Every pairing of a box before with a box after in the same column suggests a movement.
     * Only movements a swipe up can cause are counted: the page's content goes up, by nothing
     * up to half as far again as the swipe. The movement most pairs agree on wins; on a tie,
     * the smaller one. Returns null when no box can be paired, so the caller can fall back.
     */
    fun fromBoxes(before: List<Spot>, after: List<Spot>, swipe: Int, tolerance: Int): Int? {
        val most = swipe * 3 / 2 + tolerance
        val moves = ArrayList<Int>()
        for (a in before) {
            for (b in after) {
                if (abs(a.x - b.x) > tolerance) continue
                val moved = a.y - b.y                  // content goes up: its y gets smaller
                if (moved < -tolerance || moved > most) continue
                moves.add(moved)
            }
        }
        if (moves.isEmpty()) return null

        var best = 0
        var bestVotes = 0
        for (m in moves) {
            val agreeing = moves.filter { abs(it - m) <= tolerance }
            val votes = agreeing.size
            val centre = agreeing.sum() / votes
            if (votes > bestVotes || (votes == bestVotes && abs(centre) < abs(best))) {
                bestVotes = votes
                best = centre
            }
        }
        return best.coerceAtLeast(0)
    }

    /**
     * Compares the row profiles - the average brightness of each row - from before and after
     * the scroll. Content that moved up by d rows makes after[y] look like before[y + d]; the
     * shift that matches best wins. Among near-equal matches the one closest to [expected]
     * (the swipe) wins, which settles a repeating list where one step of movement looks the
     * same as none. Returns the movement in profile rows; 0 means the page did not move.
     */
    fun measure(before: IntArray, after: IntArray, expected: Int, maxShift: Int): Int {
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
