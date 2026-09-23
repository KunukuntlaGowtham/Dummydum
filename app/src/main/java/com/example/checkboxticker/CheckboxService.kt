package com.example.checkboxticker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Finds every checkbox on the screen of whatever app is in front and ticks it.
 *
 * Must be switched on in Settings > Accessibility > Checkbox Ticker. The floating
 * button it draws is an accessibility overlay, so no "draw over other apps"
 * permission is needed.
 */
class CheckboxService : AccessibilityService() {

    companion object {
        const val PREFS = "cfg"
        const val REPORT_FILE = "scan_report.txt"
        const val FAILED_FILE = "failed.txt"
        const val DEFAULT_COLOUR = 0x663398        // the purple button in the pop-up
        @Volatile
        var instance: CheckboxService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var running = false
    private var looping = false
    private var ticked = 0
    private var lastBox: Rect? = null
    private var panel: TextView? = null
    private var lastStatus = ""

    private var attempts = 0                      // boxes numbered so far in the run
    private val failed = ArrayList<Int>()         // the numbers of boxes that did not tick
    private var autoMode = false
    private var silentRun = false
    private var lastAutoRun = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        applySettings()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        looping = false
        hidePanel()
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !autoMode || running) return
        if (event.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoRun < 1500L) return
        lastAutoRun = now
        main.postDelayed({ if (autoMode && !running) tickAll(fromAuto = true) }, 400L)
    }

    // ---------------------------------------------------------------- settings

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Re-reads the saved options. Called by the settings screen after saving. */
    fun applySettings() {
        val p = prefs()
        autoMode = p.getBoolean("auto", false)
        if (p.getBoolean("bubble", true)) showBubble() else hideBubble()
        updateBubble()
    }

    fun isAutoOn() = autoMode

    // ---------------------------------------------------------------- the work

    /** Ticks every checkbox currently on screen, one after another. */
    fun tickAll(fromAuto: Boolean) {
        if (running) return
        val roots = roots()
        if (roots.isEmpty()) {
            if (!fromAuto) toast("Nothing to read - switch to the app you want ticked")
            return
        }

        val p = prefs()
        val rules = Rules(
            onlyUnchecked = p.getBoolean("onlyUnchecked", true),
            switches = p.getBoolean("switches", true),
            radios = p.getBoolean("radios", false),
            loose = p.getBoolean("loose", true)
        )
        val gap = p.getInt("gapMs", 250).coerceIn(0, 5000)
        val max = p.getInt("maxTicks", 50).coerceIn(1, 500)

        val targets = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) collect(root, targets, rules, max)

        if (targets.isEmpty()) {
            // Nothing in the tree: look at the screen itself instead. Only on a run you
            // asked for - guessing from pixels on every screen change would tap wildly.
            if (!fromAuto && p.getBoolean("pixels", true) && ScreenService.instance != null) {
                tickByPixels(fromAuto)
                return
            }
            if (!fromAuto) {
                saveReport()
                toast(
                    if (roots.isEmpty()) "This app shows nothing to read - turn on screen reading in the app"
                    else "No checkbox found - scan report saved, open the app to read it"
                )
            }
            return
        }

        running = true
        silentRun = fromAuto
        updateBubble()
        tickNext(targets, 0, gap, 0)
    }

    // ---------------------------------------------------------------- start / stop

    fun isLooping() = looping

    fun toggleLoop() {
        if (looping) stopLoop("Stopped") else startLoop()
    }

    /**
     * Works down the page a snap at a time until STOP:
     *
     * 1. Snap the screen and find every empty checkbox on it; number them top to bottom,
     *    carrying on from the last screen.
     * 2. Tick each one and clear its pop-up - as many times as there are boxes.
     * 3. Snap again: a box still empty where it was did not tick - its number is shown.
     * 4. Scroll so the next boxes come up, and do it all again.
     */
    fun startLoop() {
        if (looping) return
        if (ScreenService.instance == null) {
            toast("Turn on screen reading in the app first")
            return
        }
        looping = true
        running = true
        silentRun = false
        ticked = 0
        lastBox = null
        attempts = 0
        failed.clear()
        onScreen.clear()
        oldLooks.clear()
        emptySnaps = 0
        scrolledOnce = false
        moveBubbleAside()
        updateBubble()
        toast("Running - press STOP to finish")
        status("Started")
        snap()
    }

    fun stopLoop(why: String) {
        if (!looping) return
        looping = false
        running = false
        lastBox = null
        updateBubble()
        status("$why - numbered $attempts, ticked ${attempts - failed.size}")
        toast("$why after $attempts boxes")
    }

    // ---------------------------------------------------------------- a snap at a time

    /** A checkbox of the current snap: its number, where it is, and what it looks like. */
    private class Numbered(val number: Int, val box: Rect, val look: BoxLook.Look?)

    private val onScreen = ArrayList<Numbered>()        // this snap's boxes, top to bottom
    private val oldLooks = ArrayList<BoxLook.Look>()    // boxes that did not tick, to know again
    private var emptySnaps = 0                          // snaps in a row with nothing new
    private var scrolledOnce = false

    /** Step 1: snap, find the boxes, number the new ones. */
    private fun snap() {
        if (!looping) return
        val screen = ScreenService.instance
        if (screen == null) {
            stopLoop("Screen reading stopped")
            return
        }
        status("snap")
        screen.findBoxesWithSketch(dp(14), dp(48), ownWindows()) { boxes, sketch ->
            if (!looping) return@findBoxesWithSketch
            val found = boxes.filter { !hitsBubble(it) }.sortedBy { it.top }
            onScreen.clear()
            for (box in found) {
                val look = sketch?.let { lookOf(it, box) }
                // A box that did not tick stays empty, and may still be on screen after the
                // scroll: it is known by what is written beside it, and not numbered again.
                if (look != null && oldLooks.any { BoxLook.same(it, look) }) continue
                attempts++
                onScreen.add(Numbered(attempts, Rect(box), look))
            }
            if (onScreen.isEmpty()) {
                nothingNew()
                return@findBoxesWithSketch
            }
            emptySnaps = 0
            status("boxes ${onScreen.first().number} to ${onScreen.last().number} on this screen")
            tickNext(0)
        }
    }

    /** Step 2: tick each box of the snap and clear its pop-up, one after another. */
    private fun tickNext(i: Int) {
        if (!looping) return
        if (i >= onScreen.size) {
            checkSnap()
            return
        }
        val item = onScreen[i]
        val ok = gestureTap(item.box.exactCenterX(), item.box.exactCenterY())
        if (ok) ticked++
        status("box ${item.number}: tapped" + (if (ok) "" else " - refused"))
        val p = prefs()
        main.postDelayed({
            if (looping) afterTick { tickNext(i + 1) }
        }, waitMs(p, "tickWaitMs", 300))
    }

    /** Step 3: snap again - a box still empty where it was did not tick. */
    private fun checkSnap() {
        val screen = ScreenService.instance
        if (screen == null) {
            stopLoop("Screen reading stopped")
            return
        }
        status("checking")
        screen.findBoxes(dp(14), dp(48)) { boxes ->
            if (!looping) return@findBoxes
            for (item in onScreen) {
                if (boxes.any { Rect.intersects(it, item.box) }) {
                    noteNotTicked(item.number)
                    item.look?.let {
                        oldLooks.add(it)
                        if (oldLooks.size > 30) oldLooks.removeAt(0)
                    }
                }
            }
            scrollOn()
        }
    }

    /** Writes down a box that did not tick and shows its number. */
    private fun noteNotTicked(number: Int) {
        failed.add(number)
        try {
            openFileOutput(FAILED_FILE, Context.MODE_APPEND).use {
                it.write("box $number not ticked\n".toByteArray())
            }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "could not write the failure", e)
        }
        updatePanel()
    }

    /** Step 4: bring the boxes below this snap's last one up to the top of the screen. */
    private fun scrollOn() {
        if (!looping) return
        val lowest = onScreen.maxOf { it.box.bottom }
        val h = resources.displayMetrics.heightPixels
        val distance = (lowest + dp(16) - dp(72)).coerceIn(dp(48), h * 7 / 10)
        scrollBy(distance)
    }

    /** No new box on this snap. Three in a row after scrolling means the end of the page. */
    private fun nothingNew() {
        emptySnaps++
        if (scrolledOnce && emptySnaps >= 3) {
            stopLoop("Reached the end of the page")
            return
        }
        status("nothing new here - scrolling on")
        scrollBy(resources.displayMetrics.heightPixels / 2)
    }

    private fun scrollBy(distance: Int) {
        if (!looping) return
        status("scrolling")
        swipeUp(distance)
        scrolledOnce = true
        lastBox = null
        main.postDelayed({ snap() }, waitMs(prefs(), "scrollWaitMs", 300) + 400L)
    }

    /**
     * Moves the page up by [distance] pixels: a steady drag, then the finger held still for a
     * moment before it lifts, so the page does not fling on past boxes nobody has seen.
     */
    private fun swipeUp(distance: Int) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val from = metrics.heightPixels * 0.85f
        val to = (from - distance).coerceAtLeast(metrics.heightPixels * 0.1f)
        try {
            val path = Path().apply { moveTo(x, from); lineTo(x, to) }
            val drag = GestureDescription.StrokeDescription(path, 0L, 500L, true)
            dispatchGesture(
                GestureDescription.Builder().addStroke(drag).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        try {
                            val hold = Path().apply { moveTo(x, to) }
                            val still = drag.continueStroke(hold, 0L, 200L, false)
                            dispatchGesture(
                                GestureDescription.Builder().addStroke(still).build(), null, null
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("CheckboxTicker", "hold failed", e)
                        }
                    }
                },
                null
            )
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "scroll failed", e)
        }
    }

    /**
     * A box together with what is written beside it: from just left of the box, 300 dp across
     * and 140 dp down - on a form, the name, date of birth, age and number.
     */
    private fun lookOf(sketch: BoxLook.Sketch, box: Rect): BoxLook.Look =
        BoxLook.cut(sketch, box.left - dp(4), box.top - dp(4), dp(300), dp(140))

    /** Where our own windows are, left out of the snap's copy of the screen. */
    private fun ownWindows(): List<Rect> = listOfNotNull(boundsOfView(bubble), boundsOfView(panel))

    private fun boundsOfView(candidate: View?): Rect? {
        val view = candidate ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val where = IntArray(2)
        view.getLocationOnScreen(where)
        val pad = dp(8)
        return Rect(where[0] - pad, where[1] - pad, where[0] + view.width + pad, where[1] + view.height + pad)
    }

    /**
     * Checkboxes sit down the left of a form, and a box under the START/STOP button is never
     * tapped - so for a run the button moves to the right-hand edge.
     */
    private fun moveBubbleAside() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val w = resources.displayMetrics.widthPixels
        val width = if (view.width > 0) view.width else dp(90)
        if (params.x + width / 2 >= w / 2) return
        params.x = w - width - dp(4)
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            // gone already
        }
    }

    private fun waitMs(p: SharedPreferences, key: String, fallback: Int) =
        p.getInt(key, fallback).coerceIn(0, 10000).toLong().coerceAtLeast(50L)

    /**
     * A see-through line at the bottom of the screen saying what the run is doing, so a run
     * that achieves nothing says which step it got stuck on.
     */
    private fun status(text: String) {
        android.util.Log.i("CheckboxTicker", text)
        lastStatus = text
        updatePanel()
    }

    private fun updatePanel() {
        if (!prefs().getBoolean("showStatus", true)) {
            hidePanel()
            return
        }
        val view = ensurePanel() ?: return
        val tally = if (failed.isEmpty()) {
            "all ticked so far"
        } else {
            "not ticked: " + failed.joinToString(", ")
        }
        view.text = "$lastStatus\n$tally"
    }

    private fun ensurePanel(): TextView? {
        panel?.let { return it }
        val manager = windowManager ?: return null

        val view = TextView(this)
        view.setTextColor(Color.WHITE)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        view.setPadding(dp(10), dp(6), dp(10), dp(6))
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(130, 0, 0, 0))
        }

        val params = WindowManager.LayoutParams(
            dp(250),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = dp(12)
        params.y = dp(80)

        return try {
            manager.addView(view, params)
            panel = view
            view
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "status line failed", e)
            null
        }
    }

    private fun hidePanel() {
        val view = panel ?: return
        try { windowManager?.removeView(view) } catch (e: Exception) { }
        panel = null
    }

    /** Our own windows are on screen during a scan, so nothing under them is a checkbox. */
    private fun hitsBubble(box: Rect): Boolean = covers(bubble, box) || covers(panel, box)

    private fun covers(candidate: View?, box: Rect): Boolean {
        val view = candidate ?: return false
        val where = IntArray(2)
        view.getLocationOnScreen(where)
        val pad = dp(8)
        val mine = Rect(
            where[0] - pad, where[1] - pad,
            where[0] + view.width + pad, where[1] + view.height + pad
        )
        return Rect.intersects(mine, box)
    }

    /**
     * The fallback that needs no accessibility tree at all: take a picture of the screen,
     * find the empty boxes on it, and tap where they are.
     */
    private fun tickByPixels(fromAuto: Boolean) {
        val screen = ScreenService.instance
        if (screen == null) {
            if (!fromAuto) toast("Turn on screen reading in the app first")
            return
        }

        running = true
        silentRun = fromAuto
        updateBubble()

        // A pop-up after every tick can move the rest of the page, so in that mode the
        // screen is looked at again before each box instead of trusting the first picture.
        if (popupExpected()) oneAtATime(0, null) else allAtOnce(fromAuto)
    }

    private fun popupExpected() =
        prefs().getBoolean("tapColour", true) && ScreenService.instance != null

    private fun allAtOnce(fromAuto: Boolean) {
        val screen = ScreenService.instance ?: return finishRun(0)
        bubble?.visibility = View.INVISIBLE   // keep our own button out of the picture
        main.postDelayed({
            screen.findBoxes(dp(14), dp(48)) { boxes ->
                bubble?.visibility = View.VISIBLE
                if (boxes.isEmpty()) {
                    running = false
                    updateBubble()
                    if (!fromAuto) toast("No empty box found on the screen")
                } else {
                    showMarkers(boxes)
                    val gap = prefs().getInt("gapMs", 250).coerceIn(0, 5000)
                    tapNext(boxes, 0, gap, 0)
                }
            }
        }, 150L)
    }

    private fun tapNext(boxes: List<Rect>, i: Int, gap: Int, done: Int) {
        if (i >= boxes.size) {
            finishRun(done)
            return
        }
        val box = boxes[i]
        val ok = gestureTap(box.exactCenterX(), box.exactCenterY())
        main.postDelayed(
            { afterTick { tapNext(boxes, i + 1, gap, done + if (ok) 1 else 0) } },
            gap.toLong().coerceAtLeast(60L)
        )
    }

    /**
     * Tick one box, deal with its pop-up, look at the screen again, tick the next. A ticked
     * box stops looking empty, so it drops out of the next look by itself; [last] only
     * guards against a box that refuses to be ticked holding the run up for ever.
     */
    private fun oneAtATime(done: Int, last: Rect?) {
        val screen = ScreenService.instance ?: return finishRun(done)
        val p = prefs()
        val max = p.getInt("maxTicks", 50).coerceIn(1, 500)
        if (done >= max) {
            finishRun(done)
            return
        }

        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            screen.findBoxes(dp(14), dp(48)) { boxes ->
                bubble?.visibility = View.VISIBLE
                val box = boxes.firstOrNull { it != last }
                if (box == null) {
                    finishRun(done)
                } else {
                    showMarkers(listOf(box))
                    val ok = gestureTap(box.exactCenterX(), box.exactCenterY())
                    val gap = p.getInt("gapMs", 250).coerceIn(0, 5000).toLong().coerceAtLeast(60L)
                    main.postDelayed(
                        { afterTick { oneAtATime(done + if (ok) 1 else 0, box) } },
                        gap
                    )
                }
            }
        }, 150L)
    }

    private fun finishRun(done: Int) {
        running = false
        lastAutoRun = SystemClock.uptimeMillis()
        updateBubble()
        if (!silentRun || done > 0) {
            toast(if (done == 0) "No empty box found on the screen" else "Ticked $done on screen")
        }
    }

    /**
     * Some apps answer a tick with a pop-up that has to be dealt with before the next box
     * can be ticked. This waits for it, taps the coloured button in it, and only then lets
     * the run carry on. The top slice of the screen is left alone throughout, so a coloured
     * status bar or header is never mistaken for the button.
     */
    private fun afterTick(next: () -> Unit) {
        val p = prefs()
        val screen = ScreenService.instance
        if (!p.getBoolean("tapColour", true) || screen == null) {
            next()
            return
        }

        val colour = p.getInt("colour", DEFAULT_COLOUR)
        val tolerance = p.getInt("colourTol", 60).coerceIn(0, 200)
        val skipTop = p.getInt("skipTopPct", 20).coerceIn(0, 90)

        // The pop-up has already had the after-a-tick wait to appear.
        screen.findColour(colour, tolerance, skipTop) { box ->
            if (box == null) {
                status("pop-up: no ${String.format("#%06X", colour)} below the top $skipTop%")
                next()
            } else {
                showMarkers(listOf(box))
                gestureTap(box.exactCenterX(), box.exactCenterY())
                status("pop-up: tapped ${box.centerX()},${box.centerY()}")
                main.postDelayed({ next() }, waitMs(p, "clearWaitMs", 300))
            }
        }
    }

    /** Flashes a ring round everything the screen scan found, so it is clear what was tapped. */
    private fun showMarkers(boxes: List<Rect>) {
        // A ring is a square outline with a flat middle, which is exactly what the scanner
        // looks for, so during a run it would photograph its own markers and tap those.
        if (looping) return
        val manager = windowManager ?: return
        val view = MarkerView(this, boxes)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            manager.addView(view, params)
        } catch (e: Exception) {
            return
        }
        main.postDelayed({
            try { manager.removeView(view) } catch (e: Exception) { }
        }, 1200L)
    }

    private class MarkerView(ctx: Context, private val boxes: List<Rect>) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.argb(235, 0, 200, 90)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (box in boxes) canvas.drawRect(box, paint)
        }
    }

    /**
     * Every window worth reading, not only the focused one - an in-app browser, a bottom
     * sheet or a dialog often lives in a window of its own.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in getWindows()) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() == packageName) continue
                out.add(root)
            }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "window list failed", e)
        }
        if (out.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() != packageName) out.add(root)
        }
        return out
    }

    private data class Rules(
        val onlyUnchecked: Boolean,
        val switches: Boolean,
        val radios: Boolean,
        val loose: Boolean
    )

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        rules: Rules,
        max: Int
    ) {
        if (node == null || out.size >= max) return
        if (isTarget(node, rules)) {
            out.add(node)
            return  // a checkable row and its checkbox are the same tick, so stop here
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, rules, max)
        }
    }

    private fun isTarget(node: AccessibilityNodeInfo, rules: Rules): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val cls = (node.className ?: "").toString()
        if (!rules.radios && cls.endsWith("RadioButton")) return false
        if (!rules.switches && (cls.endsWith("Switch") || cls.endsWith("SwitchCompat") ||
                    cls.endsWith("SwitchMaterial") || cls.endsWith("ToggleButton"))) return false

        val declared = node.isCheckable || cls.endsWith("CheckBox") || cls.endsWith("CheckedTextView")
        val guessed = rules.loose && !declared && node.isClickable && looksLikeCheckbox(node, bounds)
        if (!declared && !guessed) return false

        return !(rules.onlyUnchecked && looksChecked(node))
    }

    /**
     * For a control that never says it is checkable - a styled div in a web page, a custom
     * view - guess from its name, or from its shape when it sits inside a WebView.
     */
    private fun looksLikeCheckbox(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        val id = (node.viewIdResourceName ?: "").lowercase()
        if (id.contains("checkbox") || id.contains("check_box") || id.contains("tickbox")) return true

        val words = words(node)
        if (words.contains("checkbox") || words.contains("check box") || words.contains("tick box")) return true

        // A small empty square you can tap inside a web page is nearly always a checkbox.
        if (!isInWebView(node)) return false
        if (node.childCount > 0 || !node.text.isNullOrEmpty()) return false
        val w = bounds.width()
        val h = bounds.height()
        val square = abs(w - h) <= maxOf(w, h) / 4
        return square && maxOf(w, h) <= dp(56) && minOf(w, h) >= dp(12)
    }

    private fun isInWebView(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 12) {
            if ((parent.className ?: "").contains("WebView")) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /** Best guess at whether a control is already ticked, for controls that do not report it. */
    private fun looksChecked(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return node.isChecked
        val words = words(node)
        if (words.contains("unchecked") || words.contains("not checked") ||
            words.contains("unticked") || words.contains("not selected")) return false
        if (node.isChecked || node.isSelected) return true
        return words.contains("checked") || words.contains("ticked") || words.contains("selected")
    }

    private fun words(node: AccessibilityNodeInfo): String {
        val text = node.text ?: ""
        val desc = node.contentDescription ?: ""
        val state = if (Build.VERSION.SDK_INT >= 30) node.stateDescription ?: "" else ""
        return "$text $desc $state".lowercase()
    }

    private fun tickNext(targets: List<AccessibilityNodeInfo>, i: Int, gap: Int, done: Int) {
        if (i >= targets.size) {
            running = false
            lastAutoRun = SystemClock.uptimeMillis()
            updateBubble()
            if (!silentRun || done > 0) {
                toast(if (done == 0) "Nothing could be ticked" else "Ticked $done")
            }
            return
        }

        val node = targets[i]
        val before = if (node.isCheckable) node.isChecked else null
        var ok = clickNode(node)
        if (!ok) ok = gestureTap(node)

        main.postDelayed({
            var worked = ok
            // A web page can swallow the click, so make sure the box really changed.
            if (ok && before != null && !stateChanged(node, before)) worked = gestureTap(node)
            afterTick { tickNext(targets, i + 1, gap, done + if (worked) 1 else 0) }
        }, gap.toLong().coerceAtLeast(60L))
    }

    private fun stateChanged(node: AccessibilityNodeInfo, before: Boolean): Boolean = try {
        node.refresh()
        node.isChecked != before
    } catch (e: Exception) {
        true
    }

    /** Clicks the node itself, or the nearest clickable parent. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if ((node.isClickable || node.isCheckable) &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /** Taps the middle of a node on screen - works even when nothing is clickable. */
    private fun gestureTap(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return gestureTap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float): Boolean = try {
        val path = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, null, null)
    } catch (e: Exception) {
        android.util.Log.e("CheckboxTicker", "tap failed", e)
        false
    }

    // ---------------------------------------------------------------- scan report

    private class Stats {
        var nodes = 0
        var checkable = 0
        var clickable = 0
        var webNodes = 0
        var webViews = 0
        val samples = ArrayList<String>()
    }

    /**
     * Writes down everything the service can see on the screen in front, so a screen where
     * nothing gets ticked can be looked at instead of guessed about.
     */
    fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("Checkbox Ticker scan report\n")
        sb.append("android ").append(Build.VERSION.SDK_INT)
            .append(" / ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")

        val list = try { getWindows() } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        sb.append("windows: ").append(list.size).append("\n")

        val roots = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        for (window in list) {
            val root = window.root
            val label = "window type=${window.type} active=${window.isActive} pkg=${root?.packageName ?: "?"}"
            if (root == null) {
                sb.append("\n").append(label).append("\n  no root - this window does not publish its content\n")
            } else {
                roots.add(label to root)
            }
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add("active window pkg=${it.packageName}" to it) }
        }

        for ((label, root) in roots) {
            val stats = Stats()
            walk(root, stats, false)
            sb.append("\n").append(label).append("\n")
            sb.append("  nodes=").append(stats.nodes)
                .append(" checkable=").append(stats.checkable)
                .append(" clickable=").append(stats.clickable)
                .append(" webviews=").append(stats.webViews)
                .append(" nodesInWeb=").append(stats.webNodes).append("\n")
            if (stats.samples.isEmpty()) {
                sb.append("  no checkable or tappable web node found\n")
            } else {
                for (line in stats.samples) sb.append(line).append("\n")
            }
        }
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, stats: Stats, inWeb: Boolean) {
        if (node == null || stats.nodes >= 4000) return
        stats.nodes++

        val cls = (node.className ?: "").toString()
        val isWebView = cls.contains("WebView")
        if (isWebView) stats.webViews++
        val web = inWeb || isWebView
        if (web) stats.webNodes++
        if (node.isCheckable) stats.checkable++
        if (node.isClickable) stats.clickable++

        if ((node.isCheckable || (web && node.isClickable)) && stats.samples.size < 40) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            stats.samples.add(
                "  " + cls.substringAfterLast('.') +
                        " chk=" + node.isCheckable + "/" + node.isChecked +
                        " clk=" + node.isClickable +
                        " web=" + web +
                        " " + bounds.width() + "x" + bounds.height() +
                        " id=" + (node.viewIdResourceName ?: "-") +
                        " txt=" + (node.text ?: "").toString().take(24) +
                        " desc=" + (node.contentDescription ?: "").toString().take(24)
            )
        }

        for (i in 0 until node.childCount) walk(node.getChild(i), stats, web)
    }

    private fun saveReport() {
        val text = buildReport()
        android.util.Log.i("CheckboxTicker", text)
        try {
            openFileOutput(REPORT_FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "could not save report", e)
        }
    }

    // ---------------------------------------------------------------- bubble

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun showBubble() {
        if (bubble != null) return
        val manager = windowManager ?: return

        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(230, 33, 118, 255))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(220)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        var downAt = 0L

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    downAt = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            manager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // view already gone
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        if (SystemClock.uptimeMillis() - downAt > 500L) {
                            saveReport()
                            toast("Scan report saved - open the app to read it")
                        } else {
                            toggleLoop()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            manager.addView(view, params)
            bubble = view
            bubbleParams = params
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "bubble failed", e)
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // already removed
        }
        bubble = null
        bubbleParams = null
    }

    private fun updateBubble() {
        val view = bubble ?: return
        view.text = if (looping) "STOP" else "START"
        (view.background as? GradientDrawable)?.setColor(
            if (looping) Color.argb(235, 205, 45, 45) else Color.argb(230, 33, 118, 255)
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
