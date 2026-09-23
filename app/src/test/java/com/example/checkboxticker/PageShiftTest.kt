package com.example.checkboxticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageShiftTest {

    private val cols = 40
    private val rows = 600          // the screen, in sketch rows
    private val swipe = 160         // how far a swipe asks the page to go, in sketch rows

    /** A made-up page, [length] rows long: random text-like content on white. */
    private fun page(length: Int, seed: Int): IntArray {
        var x = seed
        return IntArray(length * cols) {
            x = x * 1103515245 + 12345
            val v = (x ushr 16) and 0xff
            if (v < 60) v else 255          // mostly white, like a form
        }
    }

    /**
     * A page where every row group is the same checkbox line, [every] rows apart - the worst
     * case, where one step down the list looks just like the next.
     */
    private fun repeating(length: Int, every: Int): IntArray {
        val line = page(every, 7)
        return IntArray(length * cols) { line[it % (every * cols)] }
    }

    /** What the screen shows with the page scrolled [top] rows down. */
    private fun screen(page: IntArray, top: Int): PageShift.Sketch {
        val cells = page.copyOfRange(top * cols, (top + rows) * cols)
        // Our see-through status panel, bottom left: its text changes between pictures,
        // and it is marked to be left out.
        for (y in 450 until 560) for (x in 0 until 18) {
            cells[y * cols + x] = PageShift.SKIP
        }
        // The top rows are the status bar, also left out.
        for (k in 0 until 24 * cols) cells[k] = PageShift.SKIP
        return PageShift.Sketch(cells, cols, rows)
    }

    private fun measure(before: PageShift.Sketch, after: PageShift.Sketch) =
        PageShift.measure(before, after, swipe, swipe * 3 / 2 + 20)

    @Test
    fun `at the bottom 13, 14 and 15 are where they were - the page did not move`() {
        val p = page(2000, 1)
        assertEquals(0, measure(screen(p, 1400), screen(p, 1400)))
    }

    @Test
    fun `at the bottom of a list of identical rows - still not moved`() {
        // One step of the list looks exactly like the next, and the swipe's length is one
        // step: only the "screen unchanged" check can tell, and it says 0.
        val p = repeating(2000, swipe)
        assertEquals(0, measure(screen(p, 1400), screen(p, 1400)))
    }

    @Test
    fun `mid-page the full swipe`() {
        val p = page(3000, 2)
        assertEquals(swipe, measure(screen(p, 500), screen(p, 500 + swipe)))
    }

    @Test
    fun `mid-page a list of identical rows closer together than the swipe`() {
        val p = repeating(3000, 60)
        assertEquals(swipe, measure(screen(p, 500), screen(p, 500 + swipe)))
    }

    @Test
    fun `near the bottom the page only went part of the way`() {
        val p = page(3000, 3)
        assertEquals(64, measure(screen(p, 800), screen(p, 864)))
    }

    @Test
    fun `the page went a little further than asked`() {
        val p = page(3000, 4)
        assertEquals(190, measure(screen(p, 800), screen(p, 990)))
    }

    @Test
    fun `content only on the left half still measures`() {
        // The right-hand side of the form is blank - which fooled the old measurement.
        val p = page(3000, 5)
        for (y in 0 until 3000) for (x in 20 until cols) p[y * cols + x] = 255
        assertEquals(0, measure(screen(p, 900), screen(p, 900)))
        assertEquals(swipe, measure(screen(p, 900), screen(p, 900 + swipe)))
    }

    @Test
    fun `a slightly different picture of a still page still counts as not moved`() {
        val p = page(2000, 6)
        val before = screen(p, 1400)
        val after = screen(p, 1400)
        for (k in 30 * cols until 40 * cols) {          // a few cells changed, e.g. a glow
            if (after.cells[k] != PageShift.SKIP) after.cells[k] = 0
        }
        assertEquals(0, measure(before, after))
    }

    @Test
    fun `look-alike cards on a still page with a message popping up - not moved`() {
        // Cards the same shape, about 1.5 swipes apart, only a little text different on each;
        // between the two pictures a message appeared at the bottom of the screen.
        val card = repeating(3000, 249)
        val names = page(3000, 9)
        for (y in 0 until 3000) if (y % 249 in 60..75) {
            for (x in 5 until 20) card[y * cols + x] = names[y * cols + x]
        }
        for (from in listOf(380, 200)) {            // low on the screen, and higher up
            val before = screen(card, 1400)
            val after = screen(card, 1400)
            for (y in from until from + 50) for (x in 0 until cols) {
                if (after.cells[y * cols + x] != PageShift.SKIP) after.cells[y * cols + x] = 40
            }
            assertEquals("message at row $from", 0, measure(before, after))
        }
    }

    @Test
    fun `pictures of different sizes cannot be compared`() {
        val p = page(2000, 8)
        val other = PageShift.Sketch(IntArray(cols * 10), cols, 10)
        assertNull(measure(screen(p, 0), other))
    }
}
