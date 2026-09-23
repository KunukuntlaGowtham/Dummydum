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

    /** Cells marked with this are covered by our own windows and are left out. */
    const val SKIP = -1

    /**
     * A small grey copy of the screen: [rows] rows of [cols] cells, each the brightness of a
     * short run of pixels. [pxPerCol] and [pxPerRow] are how many screen pixels a cell covers.
     */
    class Sketch(
        val cells: IntArray,
        val cols: Int,
        val rows: Int,
        val pxPerCol: Float = 1f,
        val pxPerRow: Float = 1f
    )

    class Look(val cells: IntArray, val cols: Int, val rows: Int)

    /** Darker than this is ink: text, lines, the box outline. The page itself is lighter. */
    private const val INK = 200

    /** Two cells this far apart in brightness differ. */
    private const val DIFFERENT = 40

    /** Up to this share of inked cells may differ and the two are still the same box. */
    private const val SAME = 0.10

    /** How many rows up or down to try when lining the two up (and one cell either side). */
    private const val SLACK = 5

    /** Fewer inked cells than this is too little to recognise anything by. */
    private const val FEWEST = 40

    /**
     * Cuts out the area [left], [top], [width] x [height] (screen pixels) of [sketch]. Parts
     * off the screen, and cells under our own windows, stay [SKIP] and are left out
     * of every comparison.
     */
    fun cut(sketch: Sketch, left: Int, top: Int, width: Int, height: Int): Look {
        val c0 = floor(left / sketch.pxPerCol).toInt()
        val r0 = floor(top / sketch.pxPerRow).toInt()
        val cols = (width / sketch.pxPerCol).toInt().coerceAtLeast(1)
        val rows = (height / sketch.pxPerRow).toInt().coerceAtLeast(1)
        val raw = IntArray(cols * rows) { SKIP }
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
        val out = IntArray(raw.size) { SKIP }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val k = r * cols + c
                if (raw[k] == SKIP) continue
                var sum = 0
                var n = 0
                for (rr in maxOf(0, r - 1)..minOf(rows - 1, r + 1)) {
                    val v = raw[rr * cols + c]
                    if (v != SKIP) {
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
        // The box is found a pixel or two differently each time, so the two may be a row or
        // a cell apart in size and in place: compare what they share, at every small offset.
        val cols = minOf(a.cols, b.cols)
        val rows = minOf(a.rows, b.rows)
        for (dy in -SLACK..SLACK) {
            for (dx in -1..1) {
                var inked = 0
                var differ = 0
                for (r in maxOf(0, -dy) until minOf(rows, rows - dy)) {
                    val ra = r * a.cols
                    val rb = (r + dy) * b.cols
                    for (c in maxOf(0, -dx) until minOf(cols, cols - dx)) {
                        val va = a.cells[ra + c]
                        val vb = b.cells[rb + c + dx]
                        if (va == SKIP || vb == SKIP) continue
                        if (va >= INK && vb >= INK) continue
                        inked++
                        if (abs(va - vb) > DIFFERENT) differ++
                    }
                }
                if (inked >= FEWEST && differ <= inked * SAME) return true
            }
        }
        return false
    }
}
