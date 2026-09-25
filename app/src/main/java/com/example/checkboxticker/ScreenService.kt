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
import android.util.Log
import android.view.WindowManager

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
        /**
         * Picture pixels per dp that finding and recognising boxes are tuned for - what a
         * full-HD phone read at half size gives. Every phone is read at the size that gives
         * this, so a checkbox and the words beside it come out the same size on any phone.
         */
        const val PX_PER_DP = 1.3125f

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

    /**
     * How much smaller than the screen the pictures are: whatever brings them to
     * [PX_PER_DP], never larger than the screen itself. Tested on real pictures of the form,
     * reading at full size on a full-HD phone found fewer boxes, not more - the finder wants
     * the same detail on every phone, not the most.
     */
    @Volatile
    private var scale = 2f
    private var density = 2.625f

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
        density = resources.displayMetrics.density
        scale = maxOf(1f, density / PX_PER_DP)

        val w = Math.round(screenW / scale)
        val h = Math.round(screenH / scale)
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
            "ticker", w, h, maxOf(1, Math.round(resources.displayMetrics.densityDpi / scale)),
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
                detect((minScreenPx / scale).toInt(), (maxScreenPx / scale).toInt())
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
                    boxes = detectIn(frame, (minScreenPx / scale).toInt(), (maxScreenPx / scale).toInt())
                    sketch = sketchOf(frame, skip)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "screen scan failed", t)
            }
            main.post { done(boxes, sketch) }
        }
    }

    /**
     * A grey copy of the screen in cells of about 3 x 0.75 dp, whatever the phone, each the
     * average brightness of the picture pixels it covers. Our own windows - whose text changes
     * from one snap to the next - are marked [BoxLook.SKIP].
     */
    private fun sketchOf(frame: Frame, skip: List<Rect>): BoxLook.Sketch {
        val sw = if (screenW > 0) screenW else Math.round(frame.w * scale)
        val sh = if (screenH > 0) screenH else Math.round(frame.h * scale)
        val framePxPerDp = density * frame.w / sw
        val cw = maxOf(1, Math.round(3.05f * framePxPerDp))
        val ch = maxOf(1, Math.round(0.76f * framePxPerDp))
        val cols = frame.w / cw
        val rows = frame.h / ch
        val cells = IntArray(cols * rows)
        for (y in 0 until rows) {
            for (cx in 0 until cols) {
                var sum = 0
                for (yy in y * ch until y * ch + ch) {
                    val row = yy * frame.w
                    for (x in cx * cw until cx * cw + cw) {
                        val c = frame.rgb[row + x]
                        sum += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 +
                                (c and 0xff) * 114) / 1000
                    }
                }
                cells[y * cols + cx] = sum / (cw * ch)
            }
        }
        val pxPerCol = sw.toFloat() / cols
        val pxPerRow = sh.toFloat() / rows
        for (r in skip) {
            val left = (r.left / pxPerCol).toInt().coerceIn(0, cols)
            val right = (r.right / pxPerCol + 1).toInt().coerceIn(0, cols)
            val top = (r.top / pxPerRow).toInt().coerceIn(0, rows)
            val bottom = (r.bottom / pxPerRow + 1).toInt().coerceIn(0, rows)
            for (y in top until bottom) {
                for (x in left until right) cells[y * cols + x] = BoxLook.SKIP
            }
        }
        return BoxLook.Sketch(cells, cols, rows, pxPerCol, pxPerRow)
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
                        ?.let { toScreen(it, frame) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "colour scan failed", t)
                null
            }
            main.post { done(box) }
        }
    }

    private class Frame(val rgb: IntArray, val w: Int, val h: Int)

    /**
     * A place on the picture, in screen pixels - by the real ratio of the two sizes, not an
     * assumed x2, so it lands exactly on any screen.
     */
    private fun toScreen(r: Rect, frame: Frame): Rect {
        val sw = if (screenW > 0) screenW else Math.round(frame.w * scale)
        val sh = if (screenH > 0) screenH else Math.round(frame.h * scale)
        return Rect(
            (r.left.toLong() * sw / frame.w).toInt(),
            (r.top.toLong() * sh / frame.h).toInt(),
            (r.right.toLong() * sw / frame.w).toInt(),
            (r.bottom.toLong() * sh / frame.h).toInt()
        )
    }

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

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height

            val rgb = IntArray(w * h)
            for (y in 0 until h) {
                var i = y * rowStride
                val row = y * w
                for (x in 0 until w) {
                    if (i + 2 >= bytes.size) break
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
        return BoxFinder.find(lum, frame.w, frame.h, minPx, maxPx).map { toScreen(it, frame) }
    }
}
