package com.example.checkboxticker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
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
        @Volatile
        var instance: CheckboxService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var windows: WindowManager? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var running = false
    private var autoMode = false
    private var silentRun = false
    private var lastAutoRun = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        instance = this
        windows = getSystemService(WindowManager::class.java)
        applySettings()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
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
            if (!fromAuto) {
                saveReport()
                toast("No checkbox found - scan report saved, open the app to read it")
            }
            return
        }

        running = true
        silentRun = fromAuto
        updateBubble()
        tickNext(targets, 0, gap, 0)
    }

    /**
     * Every window worth reading, not only the focused one - an in-app browser, a bottom
     * sheet or a dialog often lives in a window of its own.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in windows) {
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
            tickNext(targets, i + 1, gap, done + if (worked) 1 else 0)
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

        val list = try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
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
        val manager = windows ?: return

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
                            tickAll(fromAuto = false)
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
            windows?.removeView(view)
        } catch (e: Exception) {
            // already removed
        }
        bubble = null
        bubbleParams = null
    }

    private fun updateBubble() {
        bubble?.text = when {
            running -> "TICKING…"
            autoMode -> "AUTO ☑"
            else -> "TICK ☑"
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
