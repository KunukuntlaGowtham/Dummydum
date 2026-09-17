package com.example.checkboxticker

import android.app.Activity
import android.content.Context
import android.content.Intent
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
    private lateinit var bubble: CheckBox
    private lateinit var auto: CheckBox
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
        root.addView(onlyUnchecked)
        root.addView(switches)
        root.addView(radios)

        root.addView(heading("How it runs"))
        bubble = check("Show the floating TICK button", p.getBoolean("bubble", true))
        auto = check("Auto-tick whenever the screen changes", p.getBoolean("auto", false))
        root.addView(bubble)
        root.addView(auto)
        root.addView(note("Tap the floating button to tick now, hold it to switch auto on or off."))

        root.addView(label("Gap between taps (ms)"))
        gapInput = number(p.getInt("gapMs", 250))
        root.addView(gapInput)

        root.addView(label("Most boxes in one run"))
        maxInput = number(p.getInt("maxTicks", 50))
        root.addView(maxInput)

        root.addView(button("2. Save settings") {
            save()
            toast("Saved")
        })

        root.addView(button("3. Tick in 5 seconds (open your app now)") {
            save()
            if (CheckboxService.instance == null) {
                toast("Do step 1 first")
            } else {
                toast("Open the app with the checkboxes")
                moveTaskToBack(true)
                main.postDelayed({ CheckboxService.instance?.tickAll(false) }, 5000L)
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val service = CheckboxService.instance
        status.text = "Service: ${if (service != null) "on" else "off"}\n" +
                "Auto ticking: ${if (service?.isAutoOn() == true) "on" else "off"}"
    }

    private fun save() {
        getSharedPreferences(CheckboxService.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("onlyUnchecked", onlyUnchecked.isChecked)
            .putBoolean("switches", switches.isChecked)
            .putBoolean("radios", radios.isChecked)
            .putBoolean("bubble", bubble.isChecked)
            .putBoolean("auto", auto.isChecked)
            .putInt("gapMs", gapInput.text.toString().toIntOrNull()?.coerceIn(0, 5000) ?: 250)
            .putInt("maxTicks", maxInput.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 50)
            .apply()
        CheckboxService.instance?.applySettings()
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
