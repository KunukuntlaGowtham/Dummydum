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

    /** Cells marked with this are covered by our own windows and are left out. */
    const val SKIP = -1

    /** Average difference, in brightness levels, below which two pictures count as the same. */
    const val STILL = 1.0

    /**
     * A small grey copy of the screen: [rows] rows of [cols] cells, each the brightness of a
     * short run of pixels. Cells under the floating button or the status panel hold [SKIP].
     */
    class Sketch(val cells: IntArray, val cols: Int, val rows: Int)

    /**
     * Compares the sketches from just before and just after the scroll, and returns how many
     * rows the page moved up, or null when the two cannot be compared.
     *
     * Content that moved up by d rows makes after[y] look like before[y + d]. First the
     * simple case: if the screen is unchanged, the page did not move - which is what happens
     * at the bottom, however much the rows look alike. Otherwise the shift that matches best
     * wins, and among near-equal matches the one closest to [expected] (the swipe's length),
     * which settles a list of identical rows where one step looks the same as the next.
     */
    fun measure(before: Sketch, after: Sketch, expected: Int, maxShift: Int): Int? {
        if (before.cols != after.cols || before.rows != after.rows) return null
        val cols = before.cols
        val rows = before.rows
        if (cols < 1 || rows < 8) return null
        val limit = maxShift.coerceIn(0, rows / 2)
        val b = before.cells
        val a = after.cells
        val fewest = cols * 4        // a shift is only judged on a fair amount of overlap

        val errors = DoubleArray(limit + 1) { Double.MAX_VALUE }
        for (d in 0..limit) {
            var sum = 0L
            var count = 0
            for (y in 0 until rows - d) {
                val ra = y * cols
                val rb = (y + d) * cols
                for (x in 0 until cols) {
                    val va = a[ra + x]
                    val vb = b[rb + x]
                    if (va == SKIP || vb == SKIP) continue
                    sum += abs(va - vb)
                    count++
                }
            }
            if (count >= fewest) errors[d] = sum.toDouble() / count
        }

        if (errors[0] <= STILL) return 0

        val best = errors.minOrNull() ?: return null
        if (best == Double.MAX_VALUE) return null
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
