package com.example.checkboxticker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Settings screen: pick what counts as a checkbox, then let the service tick them. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var onlyUnchecked: CheckBox
    private lateinit var switches: CheckBox
    private lateinit var radios: CheckBox
    private lateinit var loose: CheckBox
    private lateinit var bubble: CheckBox
    private lateinit var auto: CheckBox
    private lateinit var pixels: CheckBox
    private lateinit var tapColour: CheckBox
    private lateinit var notes: CheckBox
    private lateinit var colourInput: EditText
    private lateinit var tolInput: EditText
    private lateinit var fast: CheckBox
    private lateinit var giveUpInput: EditText
    private lateinit var tickWaitInput: EditText
    private lateinit var clearWaitInput: EditText
    private lateinit var scrollWaitInput: EditText
    private lateinit var scrollMmInput: EditText
    private lateinit var skipTopInput: EditText
    private lateinit var gapInput: EditText
    private lateinit var maxInput: EditText

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(title("Checkbox Ticker"))
        root.addView(note("Ticks every checkbox on the screen of whatever app is in front."))

        status = TextView(this).apply {
            setPadding(0, dp(10), 0, dp(14))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        root.addView(status)

        root.addView(button("1. Turn the service on in Accessibility") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Find \"Checkbox Ticker\" and switch it on")
            } catch (e: Exception) {
                toast("Open Settings > Accessibility yourself")
            }
        })

        root.addView(heading("What to tick"))
        onlyUnchecked = check("Only boxes that are empty", p.getBoolean("onlyUnchecked", true))
        switches = check("Also flip switches and toggles", p.getBoolean("switches", true))
        radios = check("Also tick radio buttons", p.getBoolean("radios", false))
        loose = check("Also web page and custom boxes", p.getBoolean("loose", true))
        root.addView(onlyUnchecked)
        root.addView(switches)
        root.addView(radios)
        root.addView(loose)
        root.addView(note("Web pages and custom views often do not say they are checkboxes. " +
                "With this on they are spotted by their name, or by being a small empty " +
                "square inside a web page. Turn it off if it taps the wrong thing."))

        root.addView(heading("How it runs"))
        bubble = check("Show the floating TICK button", p.getBoolean("bubble", true))
        auto = check("Auto-tick whenever the screen changes", p.getBoolean("auto", false))
        pixels = check("Read the screen when an app hides its checkboxes", p.getBoolean("pixels", true))
        root.addView(bubble)
        root.addView(auto)
        root.addView(pixels)
        root.addView(note("The floating button is START and STOP. START keeps going by itself - " +
                "tick a box, clear the pop-up, scroll on a little, tick the next - until you " +
                "press STOP. Hold the button instead to save a scan report of what is on screen."))

        root.addView(label("Gap between taps (ms)"))
        gapInput = number(p.getInt("gapMs", 250))
        root.addView(gapInput)

        root.addView(label("Most boxes in one run"))
        maxInput = number(p.getInt("maxTicks", 50))
        root.addView(maxInput)

        root.addView(heading("After each tick"))
        tapColour = check("A pop-up appears - tap its colour", p.getBoolean("tapColour", true))
        root.addView(tapColour)
        root.addView(note("Needs screen reading. After every box is ticked the pop-up is waited " +
                "for, the biggest patch of this colour is tapped, and only then does the next " +
                "box get ticked."))

        notes = check("Note down the words under each box", p.getBoolean("notes", true))
        root.addView(notes)
        root.addView(note("The label under a box is read before it is ticked - from the app's " +
                "own text when it publishes any, otherwise by reading the picture of the " +
                "screen. Both happen on the phone."))

        root.addView(label("Colour of the pop-up button"))
        colourInput = EditText(this).apply {
            setText(String.format("#%06X", p.getInt("colour", CheckboxService.DEFAULT_COLOUR)))
            layoutParams = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(colourInput)

        root.addView(label("Colour tolerance"))
        tolInput = number(p.getInt("colourTol", 60))
        root.addView(tolInput)

        root.addView(label("Ignore the top % of the screen"))
        skipTopInput = number(p.getInt("skipTopPct", 20))
        root.addView(skipTopInput)

        root.addView(heading("Speed"))
        fast = check("Full speed (wait for the screen, not the clock)", p.getBoolean("fast", true))
        root.addView(fast)
        root.addView(note("On: every step carries on the instant the screen is ready - the " +
                "pop-up is tapped as it appears, the next box the moment it has gone. The app " +
                "runs as fast as the phone allows and the three waits below are ignored. " +
                "Needs screen reading."))

        root.addView(label("Give up waiting after (ms)"))
        giveUpInput = number(p.getInt("giveUpMs", 2500))
        root.addView(giveUpInput)

        root.addView(note("Used only when full speed is off. Milliseconds: 1000 is one second."))

        root.addView(label("After ticking a box (waits for the pop-up)"))
        tickWaitInput = number(p.getInt("tickWaitMs", 300))
        root.addView(tickWaitInput)

        root.addView(label("After clearing the pop-up"))
        clearWaitInput = number(p.getInt("clearWaitMs", 300))
        root.addView(clearWaitInput)

        root.addView(label("After scrolling"))
        scrollWaitInput = number(p.getInt("scrollWaitMs", 300))
        root.addView(scrollWaitInput)

        root.addView(label("Scroll each time (mm)"))
        scrollMmInput = number(p.getInt("scrollMm", 20))
        root.addView(scrollMmInput)

        root.addView(button("2. Save settings") {
            save()
            toast("Saved")
        })

        root.addView(button("Allow screen reading (works in any app)") {
            save()
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            }
            val projection = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(projection.createScreenCaptureIntent(), 1)
        })

        root.addView(note("With screen reading on, a screen that publishes nothing is still " +
                "handled: the empty boxes are spotted by how they look and tapped. Nothing " +
                "leaves the phone - a frame is measured and dropped."))

        root.addView(button("Show the notes") { showNotes() })

        root.addView(button("Show last scan report") { showReport() })

        root.addView(button("3. Start in 5 seconds (open your app now)") {
            save()
            if (CheckboxService.instance == null) {
                toast("Do step 1 first")
            } else {
                toast("Open the app with the checkboxes")
                moveTaskToBack(true)
                main.postDelayed({ CheckboxService.instance?.startLoop() }, 5000L)
            }
        })

        root.addView(button("Stop now") {
            CheckboxService.instance?.stopLoop("Stopped")
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val service = CheckboxService.instance
        status.text = "Service: ${if (service != null) "on" else "off"}\n" +
                "Auto ticking: ${if (service?.isAutoOn() == true) "on" else "off"}\n" +
                "Screen reading: ${if (ScreenService.instance != null) "on" else "off"}\n" +
                "Now: ${if (service?.isLooping() == true) "running" else "stopped"}"
    }

    private fun save() {
        getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("onlyUnchecked", onlyUnchecked.isChecked)
            .putBoolean("switches", switches.isChecked)
            .putBoolean("radios", radios.isChecked)
            .putBoolean("loose", loose.isChecked)
            .putBoolean("bubble", bubble.isChecked)
            .putBoolean("auto", auto.isChecked)
            .putBoolean("pixels", pixels.isChecked)
            .putInt("gapMs", gapInput.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: 250)
            .putInt("maxTicks", maxInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 50)
            .putBoolean("tapColour", tapColour.isChecked)
            .putBoolean("notes", notes.isChecked)
            .putInt("colour", parseColour(colourInput.text.toString()))
            .putInt("colourTol", tolInput.text.toString().toIntOrNull()?.coerceIn(0, 200) ?: 60)
            .putBoolean("fast", fast.isChecked)
            .putInt("giveUpMs", giveUpInput.text.toString().toIntOrNull()?.coerceIn(200, 20000) ?: 2500)
            .putInt("tickWaitMs", tickWaitInput.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 300)
            .putInt("clearWaitMs", clearWaitInput.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 300)
            .putInt("scrollWaitMs", scrollWaitInput.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 300)
            .putInt("scrollMm", scrollMmInput.text.toString().toIntOrNull()?.coerceIn(1, 200) ?: 20)
            .putInt("skipTopPct", skipTopInput.text.toString().toIntOrNull()?.coerceIn(0, 90) ?: 20)
            .apply()
        CheckboxService.instance?.applySettings()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Screen reading was not allowed")
            return
        }
        startForegroundService(
            Intent(this, ScreenService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
        )
        toast("Screen reading is on")
    }

    /** Shows the labels written down as boxes were ticked. */
    private fun showNotes() {
        val text = try {
            openFileInput(CheckboxService.NOTES_FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (text.isBlank()) {
            toast("Nothing noted down yet")
            return
        }

        val body = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Notes")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Checkbox Ticker notes")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "Send notes"))
            }
            .setNeutralButton("Clear") { _, _ ->
                deleteFile(CheckboxService.NOTES_FILE)
                toast("Notes cleared")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Shows what the service could see the last time a screen was scanned. */
    private fun showReport() {
        val report = try {
            openFileInput(CheckboxService.REPORT_FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (report.isBlank()) {
            toast("Hold the floating button on the screen that is not working first")
            return
        }

        val body = TextView(this).apply {
            this.text = report
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("What the service sees")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Checkbox Ticker scan report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(send, "Send scan report"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun parseColour(text: String): Int {
        val cleaned = text.trim().removePrefix("#").removePrefix("0x")
        val value = cleaned.toIntOrNull(16) ?: return CheckboxService.DEFAULT_COLOUR
        return value and 0xFFFFFF
    }

    // ---------------------------------------------------------------- widgets

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        gravity = Gravity.START
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, 0)
    }

    private fun check(text: String, checked: Boolean) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
    }

    private fun number(value: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener(View.OnClickListener { onClick() })
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
