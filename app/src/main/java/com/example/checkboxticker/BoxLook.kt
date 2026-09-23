package com.example.checkboxticker

import kotlin.math.abs
import kotlin.math.floor

/**
 * What a checkbox looks like together with what is written beside it - on a form, the
 * person's name, date of birth, number. Two of these say whether a box on screen now is one
 * already tried, wherever the page has scrolled to, without measuring the scroll: box 13 is
 * the box next to "Seelam Padmavathi", not the box at a certain height.
 *
 * Checked on real pictures of such a form: the same card seen at three scroll positions
 * differed in at most 2% of its inked cells, neighbouring cards in at least 28%.
 */
object BoxLook {

    class Look(val cells: IntArray, val cols: Int, val rows: Int)

    /** Darker than this is ink: text, lines, the box outline. The page itself is lighter. */
    private const val INK = 200

    /** Two cells this far apart in brightness differ. */
    private const val DIFFERENT = 40

    /** Up to this share of inked cells may differ and the two are still the same box. */
    private const val SAME = 0.10

    /** How many rows up or down to try when lining the two up. */
    private const val SLACK = 5

    /** Fewer inked cells than this is too little to recognise anything by. */
    private const val FEWEST = 40

    /**
     * Cuts out the area [left], [top], [width] x [height] (screen pixels) of [sketch]. Parts
     * off the screen, and cells under our own windows, stay [PageShift.SKIP] and are left out
     * of every comparison.
     */
    fun cut(sketch: PageShift.Sketch, left: Int, top: Int, width: Int, height: Int): Look {
        val c0 = floor(left / sketch.pxPerCol).toInt()
        val r0 = floor(top / sketch.pxPerRow).toInt()
        val cols = (width / sketch.pxPerCol).toInt().coerceAtLeast(1)
        val rows = (height / sketch.pxPerRow).toInt().coerceAtLeast(1)
        val raw = IntArray(cols * rows) { PageShift.SKIP }
        for (r in 0 until rows) {
            val y = r0 + r
            if (y < 0 || y >= sketch.rows) continue
            for (c in 0 until cols) {
                val x = c0 + c
                if (x < 0 || x >= sketch.cols) continue
                raw[r * cols + c] = sketch.cells[y * sketch.cols + x]
            }
        }
        return Look(soften(raw, cols, rows), cols, rows)
    }

    /**
     * Averages each cell with the ones just above and below it. A scroll rarely moves the page
     * by a whole number of rows, and this keeps the edges of letters from counting as a
     * difference.
     */
    private fun soften(raw: IntArray, cols: Int, rows: Int): IntArray {
        val out = IntArray(raw.size) { PageShift.SKIP }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val k = r * cols + c
                if (raw[k] == PageShift.SKIP) continue
                var sum = 0
                var n = 0
                for (rr in maxOf(0, r - 1)..minOf(rows - 1, r + 1)) {
                    val v = raw[rr * cols + c]
                    if (v != PageShift.SKIP) {
                        sum += v
                        n++
                    }
                }
                out[k] = sum / n
            }
        }
        return out
    }

    /**
     * True when [a] and [b] are the same box: the same words beside it. (Only empty boxes are
     * ever found on screen, so a box that did tick is never compared at all.)
     */
    fun same(a: Look, b: Look): Boolean {
        if (a.cols != b.cols || a.rows != b.rows) return false
        val cols = a.cols
        val rows = a.rows
        for (dy in -SLACK..SLACK) {
            var inked = 0
            var differ = 0
            for (r in maxOf(0, -dy) until minOf(rows, rows - dy)) {
                val ra = r * cols
                val rb = (r + dy) * cols
                for (c in 0 until cols) {
                    val va = a.cells[ra + c]
                    val vb = b.cells[rb + c]
                    if (va == PageShift.SKIP || vb == PageShift.SKIP) continue
                    if (va >= INK && vb >= INK) continue
                    inked++
                    if (abs(va - vb) > DIFFERENT) differ++
                }
            }
            if (inked >= FEWEST && differ <= inked * SAME) return true
        }
        return false
    }
}
