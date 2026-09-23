package com.example.checkboxticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the Sequencer against a pretend page the way the service does: look, pick one box,
 * tap it, record the result, scroll, look again. The page knows which box is which; the
 * Sequencer does not - it only ever sees identical rectangles on the current screenful.
 */
class SequencerTest {

    private class Page(
        val count: Int,
        val spacing: Int,
        val firstY: Int = 300,
        val boxSize: Int = 60,
        val screenHeight: Int = 2400,
        val pageHeight: Int = firstY + spacing * count + 600,
        val slop: Int = 0                    // how much of each swipe the page swallows
    ) {
        var offset = 0
        val checked = BooleanArray(count)

        private fun pageY(i: Int) = firstY + i * spacing

        /** The unchecked boxes on screen, as identical rectangles. */
        fun visible(): List<Box> = (0 until count)
            .filter { !checked[it] }
            .map { pageY(it) - offset }
            .filter { it >= 0 && it + boxSize <= screenHeight }
            .map { Box(100, it, 100 + boxSize, it + boxSize) }

        /** Which box that rectangle is - only the test may ask. */
        fun physicalOf(box: Box): Int =
            (0 until count).first { pageY(it) - offset == box.top } + 1

        fun scroll(px: Int): Int {
            val before = offset
            offset = min(max(0, pageHeight - screenHeight), offset + max(0, px - slop))
            return offset - before
        }
    }

    private class Outcome(val order: List<Int>, val shown: List<Int>, val physical: List<Int>)

    private fun run(page: Page, failing: Set<Int>, scrollPx: Int = 315): Outcome {
        val seq = Sequencer()
        seq.begin()
        val order = ArrayList<Int>()
        var steps = 0

        while (steps++ < 1000) {
            val box = seq.pick(page.visible())
            if (box == null) {
                val moved = page.scroll(scrollPx)
                if (moved == 0) break                  // end of page, nothing below the line
                seq.scrolled(moved)
                continue
            }

            seq.start(box)
            val physical = page.physicalOf(box)
            order.add(physical)
            assertEquals("the run's count must be the box's place on the page",
                physical, seq.attempt)

            if (physical in failing) {
                seq.failed()                           // stays unchecked on the page
            } else {
                page.checked[physical - 1] = true
                seq.succeeded()
            }
            seq.scrolling()
            seq.scrolled(page.scroll(scrollPx))
        }
        assertTrue("the run must end by itself", steps < 1000)
        return Outcome(order, seq.failures.map { it.shown }, seq.failures.map { it.physical })
    }

    @Test
    fun `failures at 3, 6 and 9 of 10 read as 3, 5 and 7`() {
        val result = run(Page(count = 10, spacing = 700), setOf(3, 6, 9))
        assertEquals((1..10).toList(), result.order)
        assertEquals(listOf(3, 6, 9), result.physical)
        assertEquals(listOf(3, 5, 7), result.shown)
    }

    @Test
    fun `a failed box is never tapped twice`() {
        val result = run(Page(count = 15, spacing = 700), setOf(1, 2, 3, 8, 15))
        assertEquals(result.order.size, result.order.toSet().size)
        assertEquals((1..15).toList(), result.order)
    }

    @Test
    fun `boxes exactly one scroll apart are all reached`() {
        // Each scroll brings the next box to where the last one was.
        val result = run(Page(count = 12, spacing = 315), setOf(4, 5, 11))
        assertEquals((1..12).toList(), result.order)
        assertEquals(listOf(4, 4, 9), result.shown)
    }

    @Test
    fun `a page that stops scrolling does not trap the run on its last failure`() {
        // Short page: it runs out of scroll while failed boxes are still on screen.
        val result = run(Page(count = 5, spacing = 500, pageHeight = 2600), setOf(4, 5))
        assertEquals((1..5).toList(), result.order)
        assertEquals(listOf(4, 4), result.shown)
    }

    @Test
    fun `a page that swallows part of each swipe still moves on`() {
        val page = Page(count = 10, spacing = 700, slop = 24)
        val result = run(page, setOf(3, 6, 9))
        assertEquals((1..10).toList(), result.order)
        assertEquals(listOf(3, 5, 7), result.shown)
    }

    @Test
    fun `every box failing is still a finite run`() {
        val result = run(Page(count = 10, spacing = 700), (1..10).toSet())
        assertEquals((1..10).toList(), result.order)
        assertEquals((1..10).toList(), result.physical)
        assertEquals(List(10) { 1 }, result.shown)
    }

    // ------------------------------------------------------------ measuring the scroll

    private fun profile(length: Int, seed: Int): IntArray {
        var x = seed
        return IntArray(length) {
            x = x * 1103515245 + 12345
            (x ushr 16) and 0xff
        }
    }

    @Test
    fun `the page's movement is read from the pictures`() {
        val page = profile(1400, 7)
        val before = page.copyOfRange(0, 1200)
        val after = page.copyOfRange(143, 1343)            // moved up by 143 rows
        assertEquals(143, Sequencer.measureShift(before, after, expected = 157, maxShift = 400))
    }

    @Test
    fun `a page that did not move reads as no movement`() {
        val before = profile(1200, 3)
        assertEquals(0, Sequencer.measureShift(before, before.copyOf(), expected = 157, maxShift = 400))
    }

    @Test
    fun `a repeating list resolves to the swipe rather than to no movement`() {
        val period = IntArray(150) { if (it in 20..40) 200 else 30 }
        val page = IntArray(1500) { period[it % 150] }
        val before = page.copyOfRange(0, 1200)
        val after = page.copyOfRange(150, 1350)            // moved by exactly one period
        assertEquals(150, Sequencer.measureShift(before, after, expected = 157, maxShift = 400))
    }
}
