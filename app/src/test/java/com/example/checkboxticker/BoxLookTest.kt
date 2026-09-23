package com.example.checkboxticker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLookTest {

    private val cols = 60
    private val card = 250          // rows per card
    private val screenRows = 700

    /** Text-like noise: mostly white, with dark strokes. */
    private fun ink(seed: Int, n: Int): IntArray {
        var x = seed
        return IntArray(n) {
            x = x * 1103515245 + 12345
            val v = (x ushr 16) and 0xff
            if (v < 90) v / 2 else 250
        }
    }

    /**
     * A list of cards like the form's: each has an empty box at the top left, some words
     * every card shares ("DOB:", "yrs") and a name and numbers of its own.
     */
    private fun page(cards: Int): IntArray {
        val p = IntArray(cards * card * cols) { 250 }
        val shared = ink(99, card * cols)
        for (k in 0 until cards) {
            val own = ink(1000 + k, card * cols)
            val top = k * card
            for (r in 0 until card) for (c in 0 until cols) {
                val v = when {
                    r in 60..75 && c in 2..40 -> own[r * cols + c]      // the name
                    r in 85..100 && c in 2..8 -> shared[r * cols + c]   // "DOB:"
                    r in 85..100 && c in 9..20 -> own[r * cols + c]     // the date
                    r in 110..125 && c in 2..12 -> shared[r * cols + c] // "yrs"
                    r in 10..40 && c in 30..36 -> own[r * cols + c]     // its number
                    else -> 250
                }
                p[(top + r) * cols + c] = v
            }
            box(p, top + 10, 2, filled = false)
        }
        return p
    }

    /** A box outline 20 rows x 6 cells, empty or ticked. */
    private fun box(p: IntArray, top: Int, left: Int, filled: Boolean) {
        for (r in 0 until 20) for (c in 0 until 6) {
            val edge = r == 0 || r == 19 || c == 0 || c == 5
            p[(top + r) * cols + left + c] = if (edge) 150 else if (filled) 40 else 255
        }
    }

    private fun screen(p: IntArray, scrolled: Int): BoxLook.Sketch =
        BoxLook.Sketch(p.copyOfRange(scrolled * cols, (scrolled + screenRows) * cols), cols, screenRows)

    /** The look of the box whose top is [boxTop] rows down the screen. */
    private fun look(s: BoxLook.Sketch, boxTop: Int) = BoxLook.cut(s, 0, boxTop - 2, 50, 140)

    @Test
    fun `the same box after the page moved is recognised`() {
        val p = page(6)
        val first = screen(p, 300)          // card 2's box is at 2 * 250 + 10 - 300 = 210
        val later = screen(p, 373)          // now at 137
        assertTrue(BoxLook.same(look(first, 210), look(later, 137)))
    }

    @Test
    fun `the box found a row or two off is still recognised`() {
        val p = page(6)
        assertTrue(BoxLook.same(look(screen(p, 300), 210), look(screen(p, 373), 139)))
        assertTrue(BoxLook.same(look(screen(p, 300), 210), look(screen(p, 373), 134)))
    }

    @Test
    fun `found a little bigger and a cell to the side, still the same box`() {
        val p = page(6)
        val first = BoxLook.cut(screen(p, 300), 0, 208, 50, 140)
        val later = BoxLook.cut(screen(p, 373), 1, 136, 51, 142)
        assertTrue(BoxLook.same(first, later))
        assertTrue(BoxLook.same(later, first))
    }

    @Test
    fun `neighbouring boxes are different boxes`() {
        val p = page(6)
        val s = screen(p, 300)
        assertFalse(BoxLook.same(look(s, 210), look(s, 460)))   // card 2 and card 3
        val later = screen(p, 373)
        assertFalse(BoxLook.same(look(s, 210), look(later, 387)))
    }

    @Test
    fun `our own button over part of it does not matter`() {
        val p = page(6)
        val first = screen(p, 300)
        val later = screen(p, 373)
        for (r in 130 until 170) for (c in 0 until 14) later.cells[r * cols + c] = BoxLook.SKIP
        assertTrue(BoxLook.same(look(first, 210), look(later, 137)))
    }

    @Test
    fun `a blank patch is never taken for a tried box`() {
        val blank = BoxLook.Sketch(IntArray(screenRows * cols) { 250 }, cols, screenRows)
        assertFalse(BoxLook.same(look(blank, 100), look(blank, 300)))
    }
}
