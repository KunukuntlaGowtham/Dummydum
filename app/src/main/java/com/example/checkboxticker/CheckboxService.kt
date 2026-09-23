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

    private val seq = Sequencer()                 // the one box in hand, the counts, the line
    private var profileBefore: IntArray? = null   // the screen's rows just before a scroll
    private var swipePx = 0                       // how far the last swipe asked to move
    private var lastMoved = 0                     // how far the page actually moved
    private var scrollPending = false
    private var scrolledOnce = false
    private var emptyScrolls = 0
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

    /** Tick a box, clear its pop-up, scroll on a little, and keep going until STOP. */
    fun startLoop() {
        if (looping) return
        looping = true
        running = true
        silentRun = false
        ticked = 0
        lastBox = null
        updateBubble()
        seq.begin()
        profileBefore = null
        swipePx = 0
        lastMoved = 0
        scrollPending = false
        scrolledOnce = false
        emptyScrolls = 0
        startFailedReport()
        toast("Running - press STOP to finish")
        status("Started")
        findCurrent()
    }

    fun stopLoop(why: String) {
        if (!looping) return
        looping = false
        running = false
        lastBox = null
        seq.stop()
        updateBubble()
        status("$why - ${seq.attempt} tried, ${seq.successes} ticked, ${seq.failures.size} failed")
        toast("$why: ${seq.successes} ticked, ${seq.failures.size} failed")
    }

    // ---------------------------------------------------------------- the run

    /*
     * One box at a time, strictly in this order:
     *
     *   FIND_CURRENT -> TAP_CURRENT -> VERIFY_CURRENT -> RECORD -> CLEAR_POPUP -> SCROLL
     *        ^                                                                      |
     *        +----------------------------------------------------------------------+
     *
     * Which box is "next" is decided by the Sequencer's line, never by "the first unchecked
     * box on screen": a box whose result is recorded is above the line from then on, and
     * the line moves up with the page by however far the page really moved. A failed box
     * is therefore never picked again, although it still looks exactly like an empty box.
     */

    /** FIND_CURRENT: one look, one box. The same look settles how far the last scroll went. */
    private fun findCurrent() {
        if (!looping) return
        val p = prefs()

        if (seq.attempt >= p.getInt("maxTicks", 50).coerceIn(1, 500)) {
            stopLoop("Reached the limit of ${p.getInt("maxTicks", 50)}")
            return
        }
        if (seq.failStreak >= p.getInt("maxFailStreak", 5).coerceIn(1, 100)) {
            stopLoop("${seq.failStreak} failed in a row")
            return
        }

        val screen = ScreenService.instance
        if (screen == null) {
            settleScroll(null, null)
            choose(p, emptyList(), null)
            return
        }
        screen.findBoxesWithProfile(dp(14), dp(48)) { found, profile ->
            if (!looping) return@findBoxesWithProfile
            settleScroll(screen, profile)
            choose(p, found.filter { !hitsBubble(it) }.map { it.toBox() }, screen)
        }
    }

    /**
     * Moves the line by how far the page actually moved, measured from the pictures taken
     * either side of the scroll. Without screen reading only the swipe's length is known.
     */
    private fun settleScroll(screen: ScreenService?, after: IntArray?) {
        if (!scrollPending) return
        scrollPending = false

        val before = profileBefore
        profileBefore = null
        lastMoved = if (screen != null && before != null && after != null) {
            val expected = screen.screenToRows(swipePx)
            val rows = Sequencer.measureShift(before, after, expected, expected * 2 + 20)
            screen.rowsToScreen(rows)
        } else {
            swipePx
        }
        seq.scrolled(lastMoved)
    }

    /** The first unchecked box below the line: from the tree when it names them, else by sight. */
    private fun choose(p: SharedPreferences, seen: List<Box>, screen: ScreenService?) {
        val fromTree = treeBoxes(p)
        val treePick = seq.pick(fromTree.map { it.first })
        if (treePick != null) {
            val node = fromTree.first { it.first == treePick }.second
            emptyScrolls = 0
            tapNode(node, treePick, p)
            return
        }

        val pick = if (screen != null && p.getBoolean("pixels", true)) seq.pick(seen) else null
        if (pick != null) {
            emptyScrolls = 0
            tapBox(pick, p, screen!!)
            return
        }

        // Nothing below the line. A page that did not move last time has nothing further.
        if (scrolledOnce && lastMoved <= 0) {
            stopLoop("Reached the end of the page")
            return
        }
        emptyScrolls++
        if (emptyScrolls > p.getInt("emptyScrolls", 6).coerceIn(1, 50)) {
            stopLoop("Nothing left to tick")
            return
        }
        status("nothing below the last box (${seen.size} seen) - scrolling on")
        scrollOn(p)
    }

    /** TAP_CURRENT, for a box found by sight. */
    private fun tapBox(box: Box, p: SharedPreferences, screen: ScreenService) {
        seq.start(box)
        val ok = gestureTap(box.centerX.toFloat(), box.centerY.toFloat())
        status("box ${seq.attempt}: tapped ${box.centerX},${box.centerY}" +
                (if (ok) "" else " - refused"))
        main.postDelayed({
            if (looping) verifyBox(box, maxRetries(p), p, screen)
        }, waitMs(p, "tickWaitMs", 300))
    }

    /**
     * VERIFY_CURRENT, by sight. Only this one box is looked at, and only its patch of the
     * picture.
     *
     * Still an empty box there: not ticked. It gets [retriesLeft] more looks after a short
     * wait in case the screen was slow, and is never tapped again - a second tap on a box
     * that was only slow to show its tick would untick it.
     *
     * No empty box there: ticked - unless the pop-up is up, which may be sitting on top of
     * the box. Then the pop-up is cleared first and the box looked at again. Either way the
     * result is recorded before anything scrolls.
     */
    private fun verifyBox(box: Box, retriesLeft: Int, p: SharedPreferences, screen: ScreenService) {
        seq.verifying()
        screen.stillEmpty(box.toRect(), dp(14), dp(48)) { empty ->
            if (!looping) return@stillEmpty

            if (empty) {
                if (retriesLeft > 0) {
                    status("box ${seq.attempt}: not ticked yet, looking again")
                    main.postDelayed({
                        if (looping) verifyBox(box, retriesLeft - 1, p, screen)
                    }, waitMs(p, "tickWaitMs", 300))
                } else {
                    record(false)
                    clearPopupThenScroll(p)
                }
                return@stillEmpty
            }

            popupAt(p, screen) { popup ->
                if (!looping) return@popupAt
                if (popup == null) {
                    record(true)
                    scrollOn(p)
                    return@popupAt
                }
                // The pop-up may be hiding the box: clear it, then look once more.
                gestureTap(popup.exactCenterX(), popup.exactCenterY())
                status("box ${seq.attempt}: pop-up cleared, checking the box")
                main.postDelayed({
                    if (!looping) return@postDelayed
                    screen.stillEmpty(box.toRect(), dp(14), dp(48)) recheck@{ emptyAfter ->
                        if (!looping) return@recheck
                        record(!emptyAfter)
                        scrollOn(p)
                    }
                }, waitMs(p, "clearWaitMs", 300))
            }
        }
    }

    /** TAP_CURRENT and VERIFY_CURRENT where the app names its boxes: the node says itself. */
    private fun tapNode(node: AccessibilityNodeInfo, box: Box, p: SharedPreferences) {
        seq.start(box)
        val before = if (node.isCheckable) node.isChecked else null
        var ok = clickNode(node)
        if (!ok) ok = gestureTap(node)
        status("box ${seq.attempt}: clicked " +
                (node.className ?: "a node").toString().substringAfterLast('.') +
                (if (ok) "" else " - refused"))

        main.postDelayed({
            if (looping) verifyNode(node, box, before, maxRetries(p), p)
        }, waitMs(p, "tickWaitMs", 300))
    }

    private fun verifyNode(
        node: AccessibilityNodeInfo,
        box: Box,
        before: Boolean?,
        retriesLeft: Int,
        p: SharedPreferences
    ) {
        seq.verifying()
        val screen = ScreenService.instance

        // A node that never said it was a checkbox has no state to read; look at it instead.
        if (before == null) {
            if (screen != null) {
                verifyBox(box, retriesLeft, p, screen)
            } else {
                record(true)
                clearPopupThenScroll(p)
            }
            return
        }

        if (stateChanged(node, before)) {
            record(true)
            clearPopupThenScroll(p)
        } else if (retriesLeft > 0) {
            status("box ${seq.attempt}: not ticked yet, looking again")
            main.postDelayed({
                if (looping) verifyNode(node, box, before, retriesLeft - 1, p)
            }, waitMs(p, "tickWaitMs", 300))
        } else {
            record(false)
            clearPopupThenScroll(p)
        }
    }

    /**
     * RECORD. From here the box is settled: the line drops below it and it is never chosen
     * again, whatever it looks like after the scroll.
     */
    private fun record(success: Boolean) {
        val physical = seq.attempt
        if (success) {
            seq.succeeded()
            status("box $physical: ticked")
            return
        }
        val shown = seq.failed()
        recordFailure(physical, shown)
        status("box $physical: FAILED - listed as $shown")
    }

    /**
     * Writes a failure down with both its numbers. The list reads by the shown number - the
     * box's place once earlier failures are taken out - and keeps the physical one alongside.
     */
    private fun recordFailure(physical: Int, shown: Int) {
        val line = if (physical == shown) "$shown\n" else "$shown   (physical box $physical)\n"
        try {
            openFileOutput(FAILED_FILE, Context.MODE_APPEND).use { it.write(line.toByteArray()) }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "could not write the failure", e)
        }
        updatePanel()
    }

    /** Each run gets its own heading in the failed-box report. */
    private fun startFailedReport() {
        val stamp = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
            .format(java.util.Date())
        val heading = "--- run of $stamp: failed boxes, numbered with earlier failures " +
                "taken out ---\n"
        try {
            openFileOutput(FAILED_FILE, Context.MODE_APPEND).use { it.write(heading.toByteArray()) }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "could not start the report", e)
        }
    }

    /** CLEAR_POPUP, then SCROLL. */
    private fun clearPopupThenScroll(p: SharedPreferences) {
        seq.scrolling()
        afterTick { if (looping) scrollOn(p) }
    }

    /** Finds the pop-up's button, if the pop-up is up. */
    private fun popupAt(p: SharedPreferences, screen: ScreenService, done: (Rect?) -> Unit) {
        if (!p.getBoolean("tapColour", true)) {
            done(null)
            return
        }
        screen.findColour(
            p.getInt("colour", DEFAULT_COLOUR),
            p.getInt("colourTol", 60).coerceIn(0, 200),
            p.getInt("skipTopPct", 20).coerceIn(0, 90),
            done
        )
    }

    /**
     * SCROLL. A picture is taken just before, so the next look can tell how far the page
     * really moved - a swipe's length is not the page's movement.
     */
    private fun scrollOn(p: SharedPreferences) {
        seq.scrolling()
        val mm = p.getInt("scrollMm", 20)
        val screen = ScreenService.instance
        val go = { before: IntArray? ->
            profileBefore = before
            status("scrolling $mm mm")
            swipePx = scrollScreen(mm)
            scrollPending = true
            scrolledOnce = true
            main.postDelayed({ findCurrent() }, waitMs(p, "scrollWaitMs", 300))
        }
        if (screen == null) go(null) else screen.rowProfile { before -> if (looping) go(before) }
    }

    private fun maxRetries(p: SharedPreferences) = p.getInt("maxRetries", 1).coerceIn(0, 5)

    /** The boxes the tree names, each with its place on screen. Off entirely if asked. */
    private fun treeBoxes(p: SharedPreferences): List<Pair<Box, AccessibilityNodeInfo>> {
        if (!p.getBoolean("useTree", true)) return emptyList()
        val rules = Rules(
            onlyUnchecked = p.getBoolean("onlyUnchecked", true),
            switches = p.getBoolean("switches", true),
            radios = p.getBoolean("radios", false),
            loose = p.getBoolean("loose", true)
        )
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (root in roots()) collect(root, nodes, rules, 20)

        val out = ArrayList<Pair<Box, AccessibilityNodeInfo>>()
        for (node in nodes) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // With no place on screen a node cannot be put in order against the line.
            if (bounds.isEmpty) continue
            out.add(bounds.toBox() to node)
        }
        return out
    }

    private fun Rect.toBox() = Box(left, top, right, bottom)

    private fun Box.toRect() = Rect(left, top, right, bottom)

    /** A short, controlled swipe up, so the page moves on by about [mm] millimetres. */
    private fun scrollScreen(mm: Int): Int {
        val metrics = resources.displayMetrics
        val distance = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_MM, mm.coerceIn(1, 200).toFloat(), metrics
        )
        val x = metrics.widthPixels / 2f
        val from = metrics.heightPixels * 0.7f
        val to = (from - distance).coerceAtLeast(metrics.heightPixels * 0.1f)
        try {
            val path = Path().apply { moveTo(x, from); lineTo(x, to) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "scroll failed", e)
        }
        return (from - to).toInt()
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
        val tally = if (seq.failures.isEmpty()) {
            "none failed"
        } else {
            "failed ${seq.failures.size}: " + seq.failures.joinToString(", ") { it.shown.toString() }
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
