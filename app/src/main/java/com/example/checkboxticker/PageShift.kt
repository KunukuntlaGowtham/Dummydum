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
