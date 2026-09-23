package com.example.checkboxticker

import org.junit.Assert.assertEquals
import org.junit.Test

class PageShiftTest {

    /** A made-up page: every row a different brightness, like real content. */
    private fun page(length: Int, seed: Int): IntArray {
        var x = seed
        return IntArray(length) {
            x = x * 1103515245 + 12345
            (x ushr 16) and 0xff
        }
    }

    @Test
    fun `a page at its bottom that did not move reads as no movement`() {
        // The 13-14-15 case: the swipe asked for 157 rows, the page went nowhere.
        val screen = page(1200, 3)
        assertEquals(0, PageShift.measure(screen, screen.copyOf(), expected = 157, maxShift = 400))
    }

    @Test
    fun `a page that moved the full swipe reads as the full swipe`() {
        val content = page(1500, 7)
        val before = content.copyOfRange(0, 1200)
        val after = content.copyOfRange(157, 1357)
        assertEquals(157, PageShift.measure(before, after, expected = 157, maxShift = 400))
    }

    @Test
    fun `a page that moved less than the swipe reads as what it really moved`() {
        // Near the bottom a page can only go part of the way.
        val content = page(1500, 11)
        val before = content.copyOfRange(0, 1200)
        val after = content.copyOfRange(64, 1264)
        assertEquals(64, PageShift.measure(before, after, expected = 157, maxShift = 400))
    }

    @Test
    fun `a repeating list resolves to the swipe rather than to no movement`() {
        val step = IntArray(150) { if (it in 20..40) 200 else 30 }
        val content = IntArray(1500) { step[it % 150] }
        val before = content.copyOfRange(0, 1200)
        val after = content.copyOfRange(150, 1350)
        assertEquals(150, PageShift.measure(before, after, expected = 157, maxShift = 400))
    }
}
