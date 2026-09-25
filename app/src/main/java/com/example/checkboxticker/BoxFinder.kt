package com.example.checkboxticker

import android.graphics.Rect
import kotlin.math.abs

/**
 * Finds empty checkboxes in a screenshot without knowing anything about the app that drew
 * them: an empty box is a small, roughly square outline with nothing inside it.
 *
 * Works on a grey-level copy of the screen. A ticked box is filled in, so it fails the
 * "nothing inside" test and is left alone.
 */
object BoxFinder {

    private const val EDGE = 26          // grey levels between a line and its background
    private const val MAX_BOXES = 60

    fun find(lum: IntArray, w: Int, h: Int, minPx: Int, maxPx: Int): List<Rect> {
        if (w < 4 || h < 4 || minPx < 2) return emptyList()
        val size = w * h

        // 1. outline pixels: where the picture changes sharply
        val edge = BooleanArray(size)
        for (y in 0 until h - 1) {
            val row = y * w
            for (x in 0 until w - 1) {
                val k = row + x
                val v = lum[k]
                if (abs(v - lum[k + 1]) > EDGE || abs(v - lum[k + w]) > EDGE) edge[k] = true
            }
        }

        // 2. join them into shapes and keep the ones shaped like an empty box
        val seen = BooleanArray(size)
        val stack = IntArray(size)
        val members = IntArray(size)
        val found = ArrayList<Rect>()

        for (start in 0 until size) {
            if (!edge[start] || seen[start]) continue

            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var count = 0
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                members[count++] = idx
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x + 1 < w && edge[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; stack[sp++] = idx + 1 }
                if (x > 0 && edge[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; stack[sp++] = idx - 1 }
                if (y + 1 < h && edge[idx + w] && !seen[idx + w]) { seen[idx + w] = true; stack[sp++] = idx + w }
                if (y > 0 && edge[idx - w] && !seen[idx - w]) { seen[idx - w] = true; stack[sp++] = idx - w }
                // Diagonal neighbours too: a box's rounded corner often has its outline
                // pixels touching only corner to corner. Joining side to side alone could
                // split a box's outline into several pieces, none looking like a full box -
                // which box, if any, this happened to depended on the exact screen size.
                if (x + 1 < w && y + 1 < h && edge[idx + w + 1] && !seen[idx + w + 1]) { seen[idx + w + 1] = true; stack[sp++] = idx + w + 1 }
                if (x > 0 && y + 1 < h && edge[idx + w - 1] && !seen[idx + w - 1]) { seen[idx + w - 1] = true; stack[sp++] = idx + w - 1 }
                if (x + 1 < w && y > 0 && edge[idx - w + 1] && !seen[idx - w + 1]) { seen[idx - w + 1] = true; stack[sp++] = idx - w + 1 }
                if (x > 0 && y > 0 && edge[idx - w - 1] && !seen[idx - w - 1]) { seen[idx - w - 1] = true; stack[sp++] = idx - w - 1 }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minPx || bh < minPx || bw > maxPx || bh > maxPx) continue

            val aspect = bw.toFloat() / bh
            if (aspect < 0.75f || aspect > 1.33f) continue
            if (count.toFloat() / (bw * bh) > 0.55f) continue          // solid shape, not an outline
            if (!ringLike(members, count, w, minX, minY, bw, bh)) continue
            if (!hollow(lum, edge, w, minX, minY, bw, bh)) continue

            found.add(Rect(minX, minY, maxX + 1, maxY + 1))
            if (found.size >= MAX_BOXES * 2) break
        }

        return dedupe(found)
    }

    /** True when the shape runs along all four sides of its own bounding box. */
    private fun ringLike(
        members: IntArray, count: Int, w: Int,
        minX: Int, minY: Int, bw: Int, bh: Int
    ): Boolean {
        val top = BooleanArray(bw)
        val bottom = BooleanArray(bw)
        val left = BooleanArray(bh)
        val right = BooleanArray(bh)
        val tol = maxOf(2, minOf(bw, bh) / 6)

        for (i in 0 until count) {
            val idx = members[i]
            val x = idx % w - minX
            val y = idx / w - minY
            if (x < 0 || y < 0 || x >= bw || y >= bh) continue
            if (y <= tol) top[x] = true
            if (y >= bh - 1 - tol) bottom[x] = true
            if (x <= tol) left[y] = true
            if (x >= bw - 1 - tol) right[y] = true
        }

        return covered(top) && covered(bottom) && covered(left) && covered(right)
    }

    private fun covered(side: BooleanArray): Boolean {
        var n = 0
        for (flag in side) if (flag) n++
        return n.toFloat() / side.size > 0.5f
    }

    /** True when the middle of the box is empty and flat - no tick, no label, no picture. */
    private fun hollow(
        lum: IntArray, edge: BooleanArray, w: Int,
        minX: Int, minY: Int, bw: Int, bh: Int
    ): Boolean {
        val insetX = maxOf(2, bw / 4)
        val insetY = maxOf(2, bh / 4)
        val x0 = minX + insetX
        val x1 = minX + bw - insetX
        val y0 = minY + insetY
        val y1 = minY + bh - insetY
        if (x1 <= x0 || y1 <= y0) return false

        var edges = 0
        var total = 0
        var lo = 255
        var hi = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val k = row + x
                total++
                if (edge[k]) edges++
                val v = lum[k]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        return total > 0 && edges * 100 / total < 12 && hi - lo < 48
    }

    /**
     * The biggest patch of one colour below [minY] - the coloured button in a pop-up.
     * Returns null when no patch is big enough to be worth tapping.
     */
    fun findColour(rgb: IntArray, w: Int, h: Int, target: Int, tol: Int, minY: Int): Rect? {
        val size = w * h
        if (size == 0) return null

        val tr = (target shr 16) and 0xff
        val tg = (target shr 8) and 0xff
        val tb = target and 0xff

        val match = BooleanArray(size)
        val from = (minY.coerceIn(0, h)) * w
        for (k in from until size) {
            val c = rgb[k]
            if (abs(((c shr 16) and 0xff) - tr) <= tol &&
                abs(((c shr 8) and 0xff) - tg) <= tol &&
                abs((c and 0xff) - tb) <= tol
            ) match[k] = true
        }

        val seen = BooleanArray(size)
        val stack = IntArray(size)
        var best: Rect? = null
        var bestCount = 0

        for (start in from until size) {
            if (!match[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var count = 0
            var minX = w
            var maxX = 0
            var top = h
            var bottom = 0

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x + 1 < w && match[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; stack[sp++] = idx + 1 }
                if (x > 0 && match[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; stack[sp++] = idx - 1 }
                if (idx + w < size && match[idx + w] && !seen[idx + w]) { seen[idx + w] = true; stack[sp++] = idx + w }
                if (idx - w >= from && match[idx - w] && !seen[idx - w]) { seen[idx - w] = true; stack[sp++] = idx - w }
            }

            if (count > bestCount && count >= 25) {
                bestCount = count
                best = Rect(minX, top, maxX + 1, bottom + 1)
            }
        }
        return best
    }

    private fun dedupe(list: List<Rect>): List<Rect> {
        val out = ArrayList<Rect>()
        for (rect in list.sortedBy { it.top }) {
            if (out.none { Rect.intersects(it, rect) }) out.add(rect)
            if (out.size >= MAX_BOXES) break
        }
        return out
    }
}
