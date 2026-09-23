package com.example.checkboxticker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
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

    private var looping = false
    private var runId = 0                         // stamps every callback with its run
    private var autoRun = false
    private var screenAtStart = false
    private var reportStarted = false
    private var currentNode: AccessibilityNodeInfo? = null   // the target, when the app names it
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
        runId++
        currentNode = null
        seq.stop()
        hidePanel()
        hideBubble()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !autoMode || looping) return
        if (event.packageName?.toString() == packageName) return
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoRun < 1500L) return
        lastAutoRun = now
        // The same one-at-a-time engine as START - there is no other way to tap.
        main.postDelayed({ if (autoMode && !looping) startLoop(auto = true) }, 400L)
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

    // ---------------------------------------------------------------- start / stop

    fun isLooping() = looping

    fun toggleLoop() {
        if (looping) stopLoop("Stopped") else startLoop()
    }

    /**
     * Starts the one engine that taps. START, the floating button, "Start in 5 seconds" and
     * automatic mode all come in here, so two runs can never tap side by side.
     *
     * [auto] marks a run started by automatic mode. If its first scan finds nothing to tick
     * it ends there, quietly - automatic mode never scrolls a page that has no checkboxes.
     */
    fun startLoop(auto: Boolean = false) {
        if (looping) return
        looping = true
        runId++
        autoRun = auto
        reportStarted = false
        currentNode = null
        screenAtStart = ScreenService.instance != null
        seq.begin()
        profileBefore = null
        swipePx = 0
        lastMoved = 0
        scrollPending = false
        scrolledOnce = false
        emptyScrolls = 0
        updateBubble()
        if (!auto) toast("Running - press STOP to finish")
        status(if (auto) "Automatic: looking for a checkbox" else "Started")
        findTarget(runId)
    }

    fun stopLoop(why: String) {
        if (!looping) return
        looping = false
        runId++                   // whatever is still scheduled for the old run now does nothing
        currentNode = null
        lastAutoRun = SystemClock.uptimeMillis()
        seq.stop()
        updateBubble()
        status("$why - ${seq.attempt} tried, ${seq.successes} ticked, ${seq.failures.size} failed")
        if (!autoRun || seq.attempt > 0) {
            toast("$why: ${seq.successes} ticked, ${seq.failures.size} failed")
        }
    }

    /** Whether a callback scheduled for run [run] may still act. */
    private fun alive(run: Int) = looping && run == runId

    /** Moves the run to [to]. A step out of order stops the run instead of tapping on. */
    private fun enter(to: TickState): Boolean {
        if (seq.moveTo(to)) return true
        orderStop(to)
        return false
    }

    private fun orderStop(to: TickState) {
        stopLoop("Stopped: ${seq.state} cannot be followed by $to")
    }

    // ---------------------------------------------------------------- the run

    /*
     * The only code that taps. One target at a time, every step in this order:
     *
     *   FIND_TARGET -> TAPPING -> VERIFYING -> RECORDING_RESULT -> CLEARING_POPUP
     *        ^                                                          |
     *        +------- WAITING_FOR_SCROLL <---------- SCROLLING <--------+
     *
     * The Sequencer holds the state and refuses any step out of order, so a second tap
     * before the first box is settled, or a fresh search straight after a failure, cannot
     * happen. Every callback carries the number of the run it belongs to and does nothing
     * once that run has stopped, so a quick STOP then START cannot leave two chains going.
     *
     * The next target always comes from a fresh scan and is the first empty box below the
     * Sequencer's line. Once a box's result is recorded it is above that line, so a failed
     * box - which still looks empty - is never chosen again. Nothing is kept from one scan to
     * the next except that line: no rectangles, no list, no queue.
     */

    /** FIND_TARGET: one fresh scan, at most one target. */
    private fun findTarget(run: Int) {
        if (!alive(run)) return
        val p = prefs()

        val limit = p.getInt("maxTicks", 50).coerceIn(1, 500)
        if (seq.attempt >= limit) {
            stopLoop("Reached the limit of $limit")
            return
        }
        val streakLimit = p.getInt("maxFailStreak", 5)
        if (streakLimit > 0 && seq.failStreak >= streakLimit) {
            stopLoop("${seq.failStreak} failed in a row")
            return
        }

        val screen = ScreenService.instance
        if (screen == null && screenAtStart && p.getBoolean("pixels", true)) {
            stopLoop("Screen reading stopped")
            return
        }
        if (screen == null) {
            if (!settleScroll(null, null)) return
            choose(run, p, emptyList(), null)
            return
        }
        screen.findBoxesWithProfile(dp(14), dp(48)) { found, profile ->
            if (!alive(run)) return@findBoxesWithProfile
            if (!settleScroll(screen, profile)) return@findBoxesWithProfile
            choose(run, p, found.filter { !hitsBubble(it) }.map { it.toBox() }, screen)
        }
    }

    /**
     * WAITING_FOR_SCROLL -> FIND_TARGET. Moves the line up by how far the page actually
     * moved, measured from the pictures either side of the scroll; without screen reading
     * only the swipe's length is known.
     */
    private fun settleScroll(screen: ScreenService?, after: IntArray?): Boolean {
        if (!scrollPending) return true
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
        if (seq.scrolled(lastMoved)) return true
        orderStop(TickState.FIND_TARGET)
        return false
    }

    /**
     * Picks the target: the first empty box below the line, named by the app when it names
     * its boxes, seen in the scan otherwise. One of the two, never both.
     */
    private fun choose(run: Int, p: SharedPreferences, seen: List<Box>, screen: ScreenService?) {
        val fromTree = treeBoxes(p, screen != null)
        val treePick = seq.pick(fromTree.map { it.first })
        if (treePick != null) {
            val node = fromTree.first { it.first == treePick }.second
            emptyScrolls = 0
            tapNode(run, node, treePick, p)
            return
        }

        val pick = if (screen != null && p.getBoolean("pixels", true)) seq.pick(seen) else null
        if (pick != null && screen != null) {
            emptyScrolls = 0
            tapBox(run, pick, p, screen)
            return
        }

        // Nothing below the line in this scan.
        if (autoRun && seq.attempt == 0) {
            stopLoop("Automatic: nothing to tick here")
            return
        }
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
        scrollOn(run, p)
    }

    /** TAPPING, for a box found in the scan: one gesture at its middle. */
    private fun tapBox(run: Int, box: Box, p: SharedPreferences, screen: ScreenService) {
        if (!seq.start(box)) {
            orderStop(TickState.TAPPING)
            return
        }
        currentNode = null
        val ok = gestureTap(box.centerX.toFloat(), box.centerY.toFloat())
        status("box ${seq.attempt}: tapped ${box.centerX},${box.centerY}" +
                (if (ok) "" else " - gesture refused"))
        main.postDelayed({
            if (alive(run)) verifyBox(run, box, maxRetries(p), p, screen)
        }, waitMs(p, "tickWaitMs", 300))
    }

    /**
     * VERIFYING, by sight: only this box, only its patch of the picture. A sent gesture says
     * nothing about the box, so the box itself is looked at.
     *
     * Still an empty box there: not ticked. It gets [retriesLeft] more looks, a moment apart,
     * in case the screen was slow - looks, never taps: a second tap on a box that was only
     * slow to show its tick would untick it.
     *
     * No empty box there: ticked - unless the pop-up is up, which may be lying over the box.
     * Then the pop-up is tapped away and the box looked at once more before the result is
     * recorded.
     */
    private fun verifyBox(
        run: Int,
        box: Box,
        retriesLeft: Int,
        p: SharedPreferences,
        screen: ScreenService
    ) {
        if (!enter(TickState.VERIFYING)) return
        screen.stillEmpty(box.toRect(), dp(14), dp(48)) { empty ->
            if (!alive(run)) return@stillEmpty

            if (empty) {
                if (retriesLeft > 0) {
                    status("box ${seq.attempt}: not ticked yet, looking again")
                    main.postDelayed({
                        if (alive(run)) verifyBox(run, box, retriesLeft - 1, p, screen)
                    }, waitMs(p, "tickWaitMs", 300))
                } else if (record(false)) {
                    clearThenScroll(run, p)
                }
                return@stillEmpty
            }

            popupAt(p, screen) { popup ->
                if (!alive(run)) return@popupAt
                if (popup == null) {
                    if (record(true)) clearThenScroll(run, p, popupGone = true)
                    return@popupAt
                }
                gestureTap(popup.exactCenterX(), popup.exactCenterY())
                status("box ${seq.attempt}: pop-up over the box - cleared it, looking again")
                main.postDelayed({
                    if (!alive(run)) return@postDelayed
                    screen.stillEmpty(box.toRect(), dp(14), dp(48)) recheck@{ emptyAfter ->
                        if (!alive(run)) return@recheck
                        if (record(!emptyAfter)) clearThenScroll(run, p)
                    }
                }, waitMs(p, "clearWaitMs", 300))
            }
        }
    }

    /**
     * TAPPING, for a box the app names: its own click, with a tap at its middle only if the
     * click is refused.
     */
    private fun tapNode(run: Int, node: AccessibilityNodeInfo, box: Box, p: SharedPreferences) {
        if (!seq.start(box)) {
            orderStop(TickState.TAPPING)
            return
        }
        currentNode = node
        val before = if (node.isCheckable) node.isChecked else null
        var ok = clickNode(node)
        if (!ok) ok = gestureTap(node)
        status("box ${seq.attempt}: clicked " +
                (node.className ?: "a node").toString().substringAfterLast('.') +
                (if (ok) "" else " - refused"))
        main.postDelayed({
            if (alive(run)) verifyNode(run, node, box, before, maxRetries(p), p)
        }, waitMs(p, "tickWaitMs", 300))
    }

    /** VERIFYING, for a box the app names: the same node is asked whether it changed. */
    private fun verifyNode(
        run: Int,
        node: AccessibilityNodeInfo,
        box: Box,
        before: Boolean?,
        retriesLeft: Int,
        p: SharedPreferences
    ) {
        // A node that never said it was a checkbox has no state to ask; it is looked at.
        // (Such nodes are only chosen when screen reading is on - see treeBoxes.)
        if (before == null) {
            val screen = ScreenService.instance
            if (screen != null) {
                verifyBox(run, box, retriesLeft, p, screen)
            } else {
                stopLoop("Screen reading stopped")
            }
            return
        }

        if (!enter(TickState.VERIFYING)) return
        if (stateChanged(node, before)) {
            if (record(true)) clearThenScroll(run, p)
        } else if (retriesLeft > 0) {
            status("box ${seq.attempt}: not ticked yet, looking again")
            main.postDelayed({
                if (alive(run)) verifyNode(run, node, box, before, retriesLeft - 1, p)
            }, waitMs(p, "tickWaitMs", 300))
        } else if (record(false)) {
            clearThenScroll(run, p)
        }
    }

    /**
     * RECORDING_RESULT. From here the box is settled - successful or not, it is behind the
     * line and is never chosen again, whatever it looks like after the scroll.
     */
    private fun record(success: Boolean): Boolean {
        val physical = seq.attempt
        if (success) {
            if (!seq.succeeded()) {
                orderStop(TickState.RECORDING_RESULT)
                return false
            }
            status("box $physical: ticked")
        } else {
            val shown = seq.failed()
            if (shown == null) {
                orderStop(TickState.RECORDING_RESULT)
                return false
            }
            recordFailure(physical, shown)
            status("box $physical: FAILED - listed as $shown")
        }
        currentNode = null
        return true
    }

    /**
     * Writes a failure down with both its numbers. The report reads by the shown number - the
     * box's place once earlier failures are taken out - with the physical one alongside.
     */
    private fun recordFailure(physical: Int, shown: Int) {
        if (!reportStarted) {
            startFailedReport()
            reportStarted = true
        }
        val line = if (physical == shown) "$shown\n" else "$shown   (physical box $physical)\n"
        try {
            openFileOutput(FAILED_FILE, Context.MODE_APPEND).use { it.write(line.toByteArray()) }
        } catch (e: Exception) {
            android.util.Log.e("CheckboxTicker", "could not write the failure", e)
        }
        updatePanel()
    }

    /** Each run with a failure gets its own heading in the failed-box report. */
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

    /** CLEARING_POPUP, then SCROLLING - after every box, failures included. */
    private fun clearThenScroll(run: Int, p: SharedPreferences, popupGone: Boolean = false) {
        if (!enter(TickState.CLEARING_POPUP)) return
        if (popupGone) {
            scrollOn(run, p)                      // just looked: there is no pop-up
        } else {
            clearPopup(run, p, 3) { scrollOn(run, p) }
        }
    }

    /**
     * Taps the pop-up's button and waits until it has gone. If it is still there after
     * [triesLeft] taps the run stops: the next box must never be tapped under a pop-up.
     */
    private fun clearPopup(run: Int, p: SharedPreferences, triesLeft: Int, then: () -> Unit) {
        val screen = ScreenService.instance
        if (!p.getBoolean("tapColour", true) || screen == null) {
            then()
            return
        }
        popupAt(p, screen) { popup ->
            if (!alive(run)) return@popupAt
            if (popup == null) {
                then()
                return@popupAt
            }
            if (triesLeft <= 0) {
                stopLoop("The pop-up would not clear")
                return@popupAt
            }
            gestureTap(popup.exactCenterX(), popup.exactCenterY())
            status("pop-up: tapped ${popup.centerX()},${popup.centerY()}")
            main.postDelayed({
                if (alive(run)) clearPopup(run, p, triesLeft - 1, then)
            }, waitMs(p, "clearWaitMs", 300))
        }
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
     * SCROLLING, then WAITING_FOR_SCROLL, then a fresh FIND_TARGET. A picture is taken just
     * before the swipe, so the next scan can tell how far the page really moved.
     */
    private fun scrollOn(run: Int, p: SharedPreferences) {
        if (!enter(TickState.SCROLLING)) return
        currentNode = null
        val mm = p.getInt("scrollMm", 20)
        val screen = ScreenService.instance
        val go = { before: IntArray? ->
            if (alive(run)) {
                profileBefore = before
                status("scrolling $mm mm")
                swipePx = scrollScreen(mm)
                scrollPending = true
                scrolledOnce = true
                if (enter(TickState.WAITING_FOR_SCROLL)) {
                    main.postDelayed({ findTarget(run) }, waitMs(p, "scrollWaitMs", 300))
                }
            }
        }
        if (screen == null) go(null) else screen.rowProfile { before -> go(before) }
    }

    private fun maxRetries(p: SharedPreferences) = p.getInt("maxRetries", 1).coerceIn(0, 5)

    /**
     * The boxes the tree names, each with its place on screen. With screen reading off, only
     * nodes that report a checked state are offered: anything else could not be verified.
     */
    private fun treeBoxes(
        p: SharedPreferences,
        screenOn: Boolean
    ): List<Pair<Box, AccessibilityNodeInfo>> {
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
            if (!screenOn && !node.isCheckable) continue
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
