package com.example.checkboxticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import kotlin.math.abs

/**
 * Reads the screen so checkboxes can be found by how they look, for apps that publish no
 * accessibility tree at all - a page drawn on a canvas, a game, a locked-down WebView.
 *
 * Nothing leaves the phone: a frame is grabbed, measured, and dropped.
 */
class ScreenService : Service() {

    companion object {
        const val TAG = "CheckboxTicker"
        const val CHANNEL = "ticker"
        const val SCALE = 2          // work on a half-size copy of the screen
        const val POLL_MS = 30L          // how often to look at the newest picture
        const val QUIET_MS = 100L        // no new picture this long: nothing is moving
        const val POPUP_SAMPLES = 150    // this many more sampled points of colour: a pop-up
        const val CELL = 4           // pixels per cell across, in a sketch

        @Volatile
        var instance: ScreenService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (!::thread.isInitialized) {
            thread = HandlerThread("screen").apply { start() }
            worker = Handler(thread.looper)
        }

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        }

        if (data != null) {
            release()
            try {
                start(code, data)
                instance = this
            } catch (t: Throwable) {
                Log.e(TAG, "screen reading failed to start", t)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        release()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Checkbox Ticker", NotificationManager.IMPORTANCE_LOW)
        )
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Checkbox Ticker can read the screen")
            .setContentText("Used only to spot checkboxes when an app hides them")
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, note)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "foreground failed", t)
        }
    }

    private fun start(code: Int, data: Intent) {
        val windows = getSystemService(WindowManager::class.java)
        val size = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            size.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION") windows.defaultDisplay.getRealSize(size)
        }
        screenW = size.x
        screenH = size.y

        val w = screenW / SCALE
        val h = screenH / SCALE
        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = manager.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "screen reading stopped by the system")
                instance = null
                release()
            }
        }, worker)

        val imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = proj.createVirtualDisplay(
            "ticker", w, h, maxOf(1, resources.displayMetrics.densityDpi / SCALE),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, worker
        )
    }

    private fun release() {
        try { display?.release() } catch (t: Throwable) { }
        try { reader?.close() } catch (t: Throwable) { }
        val proj = projection
        projection = null
        display = null
        reader = null
        try { proj?.stop() } catch (t: Throwable) { }
    }

    /**
     * Grabs a frame, finds the empty boxes on it and hands them back on the main thread,
     * in screen coordinates.
     */
    fun findBoxes(minScreenPx: Int, maxScreenPx: Int, done: (List<Rect>) -> Unit) {
        worker.post {
            val boxes = try {
                detect(minScreenPx / SCALE, maxScreenPx / SCALE)
            } catch (t: Throwable) {
                Log.e(TAG, "screen scan failed", t)
                emptyList()
            }
            main.post { done(boxes) }
        }
    }

    /**
     * One snap, two answers: the empty boxes on it (in screen coordinates), and a small grey
     * copy of the screen to recognise them by later, with our own windows ([skip], in screen
     * pixels) left out.
     */
    fun findBoxesWithSketch(
        minScreenPx: Int,
        maxScreenPx: Int,
        skip: List<Rect>,
        done: (List<Rect>, BoxLook.Sketch?) -> Unit
    ) {
        worker.post {
            var boxes: List<Rect> = emptyList()
            var sketch: BoxLook.Sketch? = null
            try {
                val frame = grab()
                if (frame != null) {
                    boxes = detectIn(frame, minScreenPx / SCALE, maxScreenPx / SCALE)
                    sketch = sketchOf(frame, skip)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "screen scan failed", t)
            }
            main.post { done(boxes, sketch) }
        }
    }

    /**
     * Every row of the screen, each cell the average brightness of [CELL] pixels side by
     * side. Our own windows - whose text changes from one snap to the next - are marked
     * [BoxLook.SKIP].
     */
    private fun sketchOf(frame: Frame, skip: List<Rect>): BoxLook.Sketch {
        val cols = frame.w / CELL
        val rows = frame.h
        val cells = IntArray(cols * rows)
        for (y in 0 until rows) {
            val row = y * frame.w
            for (cx in 0 until cols) {
                var sum = 0
                val x0 = cx * CELL
                for (x in x0 until x0 + CELL) {
                    val c = frame.rgb[row + x]
                    sum += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 +
                            (c and 0xff) * 114) / 1000
                }
                cells[y * cols + cx] = sum / CELL
            }
        }
        val sw = if (screenW > 0) screenW else frame.w * SCALE
        val sh = if (screenH > 0) screenH else frame.h * SCALE
        for (r in skip) {
            val left = (r.left.toLong() * frame.w / sw / CELL).toInt().coerceIn(0, cols)
            val right = ((r.right.toLong() * frame.w / sw + CELL - 1) / CELL).toInt().coerceIn(0, cols)
            val top = (r.top.toLong() * rows / sh).toInt().coerceIn(0, rows)
            val bottom = (r.bottom.toLong() * rows / sh + 1).toInt().coerceIn(0, rows)
            for (y in top until bottom) {
                for (x in left until right) cells[y * cols + x] = BoxLook.SKIP
            }
        }
        return BoxLook.Sketch(
            cells, cols, rows,
            pxPerCol = sw.toFloat() * CELL / frame.w,
            pxPerRow = sh.toFloat() / rows
        )
    }

    /** Finds the biggest patch of one colour, ignoring the top [skipTopPct] % of the screen. */
    fun findColour(target: Int, tolerance: Int, skipTopPct: Int, done: (Rect?) -> Unit) {
        worker.post {
            val box = try {
                val frame = grab()
                if (frame == null) {
                    null
                } else {
                    val minY = frame.h * skipTopPct.coerceIn(0, 90) / 100
                    BoxFinder.findColour(frame.rgb, frame.w, frame.h, target, tolerance, minY)
                        ?.let { Rect(it.left * SCALE, it.top * SCALE, it.right * SCALE, it.bottom * SCALE) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "colour scan failed", t)
                null
            }
            main.post { done(box) }
        }
    }

    private class Frame(val rgb: IntArray, val w: Int, val h: Int)

    /** The last picture taken - still the screen, until Android sends a new one. */
    @Volatile
    private var lastFrame: Frame? = null

    /** One frame of the screen as plain colours. */
    private fun grab(): Frame? {
        val imageReader = reader ?: return null

        var image = imageReader.acquireLatestImage()
        var tries = 0
        val known = lastFrame
        val limit = if (known == null) 12 else 3
        val pause = if (known == null) 40L else 15L
        while (image == null && tries < limit) {
            Thread.sleep(pause)
            image = imageReader.acquireLatestImage()
            tries++
        }
        // Android sends a new picture only when something on screen changes, so no new one
        // means the last picture is still what is on screen.
        if (image == null) return known
        return decode(image)
    }

    // Pictures are decoded into the same few arrays over and over: on a low-end phone making
    // new ones for every picture keeps the phone busy clearing up after them.
    private var bytes = ByteArray(0)
    private val rgbArrays = arrayOfNulls<IntArray>(2)
    private var nextArray = 0

    /** Turns one picture from Android into plain colours, and keeps it as the latest. */
    private fun decode(image: android.media.Image): Frame {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val size = buffer.remaining()
            if (bytes.size != size) bytes = ByteArray(size)
            buffer.get(bytes)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height

            var rgb = rgbArrays[nextArray]
            if (rgb == null || rgb.size != w * h) {
                rgb = IntArray(w * h)
                rgbArrays[nextArray] = rgb
            }
            nextArray = 1 - nextArray
            for (y in 0 until h) {
                var i = y * rowStride
                val row = y * w
                for (x in 0 until w) {
                    if (i + 2 >= size) break
                    val r = bytes[i].toInt() and 0xff
                    val g = bytes[i + 1].toInt() and 0xff
                    val b = bytes[i + 2].toInt() and 0xff
                    rgb[row + x] = (r shl 16) or (g shl 8) or b
                    i += pixelStride
                }
            }
            val frame = Frame(rgb, w, h)
            lastFrame = frame
            return frame
        } finally {
            image.close()
        }
    }

    /** The newest picture if Android has sent one, else the last one. Never waits. */
    private fun newest(): Frame? {
        val image = reader?.acquireLatestImage() ?: return lastFrame
        return decode(image)
    }

    // ---------------------------------------------------------------- watching, not waiting

    /**
     * Rather than waiting a fixed time and hoping the screen is ready, these watch each new
     * picture as it arrives and answer the moment it is: the pop-up is up, the pop-up is gone,
     * the page has stopped. A fast phone and a fast website go as fast as they can; a slow
     * phone takes just as long as it needs. [maxMs] only stops a wait going on for ever.
     */

    /** How much of the pop-up's colour is on screen, as a count of sampled points. */
    fun colourCount(target: Int, tolerance: Int, skipTopPct: Int, done: (Int) -> Unit) {
        worker.post {
            val n = try {
                newest()?.let { samples(it, target, tolerance, skipTopPct) } ?: 0
            } catch (t: Throwable) {
                Log.e(TAG, "colour count failed", t)
                0
            }
            main.post { done(n) }
        }
    }

    /**
     * Waits for the pop-up: more of [target] than the [before] count taken just before the
     * tap. Hands back its button, in screen pixels - or null if none shows within [maxMs].
     */
    fun awaitPopup(
        target: Int, tolerance: Int, skipTopPct: Int, before: Int, maxMs: Long,
        done: (Rect?) -> Unit
    ) {
        worker.post {
            var button: Rect? = null
            try {
                val until = SystemClock.uptimeMillis() + maxMs
                while (true) {
                    val frame = newest()
                    if (frame != null &&
                        samples(frame, target, tolerance, skipTopPct) >= before + POPUP_SAMPLES
                    ) {
                        val minY = frame.h * skipTopPct.coerceIn(0, 90) / 100
                        button = BoxFinder.findColour(frame.rgb, frame.w, frame.h, target, tolerance, minY)
                            ?.let { Rect(it.left * SCALE, it.top * SCALE, it.right * SCALE, it.bottom * SCALE) }
                        if (button != null) break
                    }
                    if (SystemClock.uptimeMillis() >= until) break
                    Thread.sleep(POLL_MS)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pop-up watch failed", t)
            }
            main.post { done(button) }
        }
    }

    /** Waits for the pop-up to go: back to about the [before] count. False if not by [maxMs]. */
    fun awaitPopupGone(
        target: Int, tolerance: Int, skipTopPct: Int, before: Int, maxMs: Long,
        done: (Boolean) -> Unit
    ) {
        worker.post {
            var gone = false
            try {
                val until = SystemClock.uptimeMillis() + maxMs
                while (true) {
                    val frame = newest()
                    if (frame == null ||
                        samples(frame, target, tolerance, skipTopPct) < before + POPUP_SAMPLES / 2
                    ) {
                        gone = true
                        break
                    }
                    if (SystemClock.uptimeMillis() >= until) break
                    Thread.sleep(POLL_MS)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pop-up watch failed", t)
                gone = true
            }
            main.post { done(gone) }
        }
    }

    /**
     * Waits until the screen stops changing: Android sends no new picture for [QUIET_MS], or
     * two pictures in a row are the same. At most [maxMs].
     */
    fun awaitStill(maxMs: Long, done: () -> Unit) {
        worker.post {
            try {
                val until = SystemClock.uptimeMillis() + maxMs
                var quietSince = SystemClock.uptimeMillis()
                var previous: IntArray? = null
                while (SystemClock.uptimeMillis() < until) {
                    val image = reader?.acquireLatestImage()
                    if (image == null) {
                        if (SystemClock.uptimeMillis() - quietSince >= QUIET_MS) break
                    } else {
                        val now = look(decode(image))
                        if (previous != null && alike(previous, now)) break
                        previous = now
                        quietSince = SystemClock.uptimeMillis()
                    }
                    Thread.sleep(POLL_MS)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "stillness watch failed", t)
            }
            main.post { done() }
        }
    }

    /** Points of [target] colour, sampling every 3rd pixel each way below the top slice. */
    private fun samples(frame: Frame, target: Int, tolerance: Int, skipTopPct: Int): Int {
        val tr = (target shr 16) and 0xff
        val tg = (target shr 8) and 0xff
        val tb = target and 0xff
        var n = 0
        var y = frame.h * skipTopPct.coerceIn(0, 90) / 100
        while (y < frame.h) {
            val row = y * frame.w
            var x = 0
            while (x < frame.w) {
                val c = frame.rgb[row + x]
                if (abs(((c shr 16) and 0xff) - tr) <= tolerance &&
                    abs(((c shr 8) and 0xff) - tg) <= tolerance &&
                    abs((c and 0xff) - tb) <= tolerance
                ) n++
                x += 3
            }
            y += 3
        }
        return n
    }

    /** A rough copy of a picture - every 6th pixel's green - to tell whether it changed. */
    private fun look(frame: Frame): IntArray {
        val cols = frame.w / 6
        val rows = frame.h / 6
        val out = IntArray(cols * rows)
        for (r in 0 until rows) {
            val row = r * 6 * frame.w
            for (c in 0 until cols) out[r * cols + c] = (frame.rgb[row + c * 6] shr 8) and 0xff
        }
        return out
    }

    private fun alike(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0L
        for (i in a.indices) diff += abs(a[i] - b[i])
        return diff <= a.size.toLong()      // on average under one level apart
    }

    private fun detect(minPx: Int, maxPx: Int): List<Rect> {
        val frame = grab() ?: return emptyList()
        return detectIn(frame, minPx, maxPx)
    }

    private fun detectIn(frame: Frame, minPx: Int, maxPx: Int): List<Rect> {
        val lum = IntArray(frame.rgb.size)
        for (k in frame.rgb.indices) {
            val c = frame.rgb[k]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            lum[k] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return BoxFinder.find(lum, frame.w, frame.h, minPx, maxPx).map {
            Rect(it.left * SCALE, it.top * SCALE, it.right * SCALE, it.bottom * SCALE)
        }
    }
}
