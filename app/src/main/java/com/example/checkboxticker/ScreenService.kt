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
        const val SCALE = 2          // work on a half-size copy of the screen

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

    /** The last picture taken. With nothing moving on screen Android sends no new one. */
    @Volatile
    private var lastFrame: Frame? = null

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
    /**
     * The average brightness of each row of the screen. One taken before a scroll and one
     * after say how far the page really moved - which at the bottom of a page is nothing.
     */
    fun rowProfile(done: (IntArray?) -> Unit) {
        worker.post {
            val profile = try {
                grab()?.let { profileOf(it) }
            } catch (t: Throwable) {
                Log.e(TAG, "profile failed", t)
                null
            }
            main.post { done(profile) }
        }
    }

    /** One picture, two answers: the boxes on it, and its row profile. */
    fun findBoxesWithProfile(
        minScreenPx: Int,
        maxScreenPx: Int,
        done: (List<Rect>, IntArray?) -> Unit
    ) {
        worker.post {
            var boxes: List<Rect> = emptyList()
            var profile: IntArray? = null
            try {
                val frame = grab()
                if (frame != null) {
                    boxes = detectIn(frame, minScreenPx / SCALE, maxScreenPx / SCALE)
                    profile = profileOf(frame)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "screen scan failed", t)
            }
            main.post { done(boxes, profile) }
        }
    }

    /**
     * Rows are averaged over the right-hand part of the screen only: the floating button and
     * the status panel sit on the left and never move, and would pull every comparison
     * towards "the page did not move".
     */
    private fun profileOf(frame: Frame): IntArray {
        val from = (frame.w * 55) / 100
        val to = (frame.w * 95) / 100
        val span = (to - from).coerceAtLeast(1)
        val out = IntArray(frame.h)
        for (y in 0 until frame.h) {
            val row = y * frame.w
            var sum = 0
            for (x in from until to) {
                val c = frame.rgb[row + x]
                sum += (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 +
                        (c and 0xff) * 114) / 1000
            }
            out[y] = sum / span
        }
        return out
    }

    /** Profile rows to screen pixels, and back. */
    fun rowsToScreen(rows: Int): Int {
        val h = lastFrame?.h ?: return rows * SCALE
        return if (h > 0) (rows.toLong() * screenH / h).toInt() else rows * SCALE
    }

    fun screenToRows(px: Int): Int {
        val h = lastFrame?.h ?: return px / SCALE
        return if (screenH > 0) (px.toLong() * h / screenH).toInt() else px / SCALE
    }

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
        // No new picture means nothing on screen changed - at the bottom of a page, a scroll
        // that goes nowhere - so the last picture is still the screen.
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
        return BoxFinder.find(lum, frame.w, frame.h, minPx, maxPx).map {
            Rect(it.left * SCALE, it.top * SCALE, it.right * SCALE, it.bottom * SCALE)
        }
    }
}
