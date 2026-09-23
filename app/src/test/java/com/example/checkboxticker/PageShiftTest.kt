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

    // ------------------------------------------------------------ from the checkboxes

    private fun spots(vararg ys: Int) = ys.map { PageShift.Spot(100, it) }

    @Test
    fun `boxes 13, 14 and 15 at the bottom, 13 failed - the page did not move`() {
        // Before the swipe: 13 (just pressed, still unchecked), 14 and 15 below it.
        // After: the page could not scroll, so all three are exactly where they were.
        val before = spots(600, 1300, 2000)
        val after = spots(600, 1300, 2000)
        assertEquals(0, PageShift.fromBoxes(before, after, swipe = 315, tolerance = 36))
    }

    @Test
    fun `only the failed box on screen at the bottom - the page did not move`() {
        assertEquals(0, PageShift.fromBoxes(spots(900), spots(900), swipe = 315, tolerance = 36))
    }

    @Test
    fun `mid-page, the untouched boxes show the real movement`() {
        // 700 apart; the top one was ticked and is gone; the rest moved up 315 and a new one
        // came into view. Pairing neighbours suggests 385 downwards - impossible for a swipe
        // up, so it is not counted.
        val before = spots(300, 1000, 1700)
        val after = spots(685, 1385, 2085)
        assertEquals(315, PageShift.fromBoxes(before, after, swipe = 315, tolerance = 36))
    }

    @Test
    fun `a page that could only move part of the way shows that`() {
        val before = spots(500, 1200, 1900)
        val after = spots(380, 1080, 1780)
        assertEquals(120, PageShift.fromBoxes(before, after, swipe = 315, tolerance = 36))
    }

    @Test
    fun `a stretch that has not quite settled still reads as no movement`() {
        // Android's end-of-list stretch can leave things a few pixels off for a moment.
        val before = spots(600, 1300, 2000)
        val after = spots(606, 1304, 2009)
        assertEquals(0, PageShift.fromBoxes(before, after, swipe = 315, tolerance = 36))
    }

    @Test
    fun `boxes in another column are not paired`() {
        val before = listOf(PageShift.Spot(100, 600))
        val after = listOf(PageShift.Spot(700, 600))
        assertEquals(null, PageShift.fromBoxes(before, after, swipe = 315, tolerance = 36))
    }

    // ------------------------------------------------------------ from the pictures

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
