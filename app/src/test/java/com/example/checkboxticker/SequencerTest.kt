package com.example.checkboxticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the Sequencer against a pretend page exactly the way the service does - one fresh
 * scan, one target, tap, verify, record, clear the pop-up, scroll, wait, fresh scan - and
 * checks what the service must guarantee. The page knows which box is which; the Sequencer
 * does not, it only ever sees identical rectangles from the current scan.
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
        val taps = IntArray(count)

        private fun pageY(i: Int) = firstY + i * spacing

        /** A fresh scan: the unchecked boxes on screen, as identical rectangles. */
        fun scan(): List<Box> = (0 until count)
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

    private class Outcome(
        val order: List<Int>,
        val shown: List<Int>,
        val physical: List<Int>,
        val traces: List<List<TickState>>
    )

    private fun run(page: Page, failing: Set<Int>, scrollPx: Int = 315): Outcome {
        val seq = Sequencer()
        seq.begin()
        val order = ArrayList<Int>()
        val traces = ArrayList<List<TickState>>()
        var steps = 0

        while (steps++ < 1000) {
            assertEquals(TickState.FIND_TARGET, seq.state)
            val scan = page.scan()                       // one fresh scan ...
            val box = seq.pick(scan)                      // ... gives one target at most

            if (box == null) {
                assertTrue(seq.moveTo(TickState.SCROLLING))
                val moved = page.scroll(scrollPx)
                assertTrue(seq.moveTo(TickState.WAITING_FOR_SCROLL))
                if (moved == 0) break                     // end of page, nothing below the line
                assertTrue(seq.scrolled(moved))
                continue
            }

            val trace = arrayListOf(seq.state)
            assertTrue(seq.start(box)); trace.add(seq.state)
            val physical = page.physicalOf(box)
            page.taps[physical - 1]++
            order.add(physical)
            assertEquals("the run's count must be the box's place on the page",
                physical, seq.currentAttempt)

            // Nothing may pick a second target while this one is in hand.
            assertNull(seq.pick(scan))

            assertTrue(seq.moveTo(TickState.VERIFYING)); trace.add(seq.state)
            if (physical in failing) {
                assertTrue(seq.failed() != null)          // stays unchecked on the page
            } else {
                page.checked[physical - 1] = true
                assertTrue(seq.succeeded())
            }
            trace.add(seq.state)
            assertNull("the target is let go once recorded", seq.current)

            assertTrue(seq.moveTo(TickState.CLEARING_POPUP)); trace.add(seq.state)
            assertTrue(seq.moveTo(TickState.SCROLLING)); trace.add(seq.state)
            val moved = page.scroll(scrollPx)
            assertTrue(seq.moveTo(TickState.WAITING_FOR_SCROLL)); trace.add(seq.state)
            assertTrue(seq.scrolled(moved))
            traces.add(trace)
        }
        assertTrue("the run must end by itself", steps < 1000)
        for (i in 0 until page.count) {
            assertTrue("box ${i + 1} was tapped ${page.taps[i]} times", page.taps[i] <= 1)
        }
        return Outcome(order, seq.failures.map { it.shown }, seq.failures.map { it.physical }, traces)
    }

    private val cycle = listOf(
        TickState.FIND_TARGET,
        TickState.TAPPING,
        TickState.VERIFYING,
        TickState.RECORDING_RESULT,
        TickState.CLEARING_POPUP,
        TickState.SCROLLING,
        TickState.WAITING_FOR_SCROLL
    )

    // ------------------------------------------------------------ the run

    @Test
    fun `failures at 3, 6 and 9 of 10 read as 3, 5 and 7`() {
        val result = run(Page(count = 10, spacing = 700), setOf(3, 6, 9))
        assertEquals((1..10).toList(), result.order)
        assertEquals(listOf(3, 6, 9), result.physical)
        assertEquals(listOf(3, 5, 7), result.shown)
    }

    @Test
    fun `every box goes through all seven steps in order, failures included`() {
        val result = run(Page(count = 10, spacing = 700), setOf(3, 6, 9))
        assertEquals(10, result.traces.size)
        for (trace in result.traces) assertEquals(cycle, trace)
    }

    @Test
    fun `a failed box is never tapped twice`() {
        val result = run(Page(count = 15, spacing = 700), setOf(1, 2, 3, 8, 15))
        assertEquals((1..15).toList(), result.order)
    }

    @Test
    fun `box 13 failing is tapped once and the run moves on`() {
        val page = Page(count = 15, spacing = 700)
        val result = run(page, setOf(13))
        assertEquals(1, page.taps[12])
        assertEquals(listOf(13, 14, 15), result.order.takeLast(3))
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
        val result = run(Page(count = 10, spacing = 700, slop = 24), setOf(3, 6, 9))
        assertEquals((1..10).toList(), result.order)
        assertEquals(listOf(3, 5, 7), result.shown)
    }

    @Test
    fun `every box failing is still a finite run`() {
        val result = run(Page(count = 10, spacing = 700), (1..10).toSet())
        assertEquals((1..10).toList(), result.order)
        assertEquals(List(10) { 1 }, result.shown)
    }

    // ------------------------------------------------------------ the order of the steps

    private fun inState(target: TickState): Sequencer {
        val seq = Sequencer()
        seq.begin()
        val path = cycle.drop(1)
        for (next in path) {
            if (seq.state == target) break
            when (next) {
                TickState.TAPPING -> assertTrue(seq.start(Box(0, 100, 60, 160)))
                TickState.RECORDING_RESULT -> assertTrue(seq.succeeded())
                else -> assertTrue(seq.moveTo(next))
            }
        }
        assertEquals(target, seq.state)
        return seq
    }

    @Test
    fun `a second tap while one target is in hand is refused`() {
        val seq = inState(TickState.TAPPING)
        assertFalse(seq.moveTo(TickState.TAPPING))
        assertFalse(seq.start(Box(0, 900, 60, 960)))
        assertEquals(1, seq.currentAttempt)
    }

    @Test
    fun `a tapped box cannot be abandoned for a new search`() {
        assertFalse(inState(TickState.TAPPING).moveTo(TickState.FIND_TARGET))
        assertFalse(inState(TickState.VERIFYING).moveTo(TickState.FIND_TARGET))
    }

    @Test
    fun `a result cannot skip the pop-up or the scroll`() {
        val recorded = inState(TickState.RECORDING_RESULT)
        assertFalse(recorded.moveTo(TickState.FIND_TARGET))
        assertFalse(recorded.moveTo(TickState.SCROLLING))
        assertFalse(recorded.moveTo(TickState.TAPPING))
        assertFalse(inState(TickState.CLEARING_POPUP).moveTo(TickState.FIND_TARGET))
        assertFalse(inState(TickState.SCROLLING).moveTo(TickState.FIND_TARGET))
    }

    @Test
    fun `a result can only be recorded while verifying`() {
        assertFalse(inState(TickState.TAPPING).succeeded())
        assertNull(inState(TickState.TAPPING).failed())
        assertNull(inState(TickState.FIND_TARGET).failed())
    }

    @Test
    fun `stopping is allowed from anywhere`() {
        for (state in cycle) {
            val seq = inState(state)
            assertTrue(seq.moveTo(TickState.IDLE))
        }
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
