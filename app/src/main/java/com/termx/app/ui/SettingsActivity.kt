package com.termx.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.termx.app.terminal.TerminalColors
import com.termx.app.utils.PreferenceManager

/**
 * Settings activity for TermX terminal emulator.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getInstance(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(com.termx.app.R.string.settings_title)

        setupSettingsUI()
    }

    private fun setupSettingsUI() {
        val scrollView = android.widget.ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // Font Size
        addSectionHeader(layout, "Font Size")
        val fontSizeText = android.widget.TextView(this).apply {
            text = "Font size: ${prefs.fontSize.toInt()}sp"
            textSize = 16f
        }
        layout.addView(fontSizeText)

        val fontSeekBar = SeekBar(this).apply {
            max = 24
            progress = prefs.fontSize.toInt() - 8
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = progress + 8
                    fontSizeText.text = "Font size: ${size}sp"
                    prefs.fontSize = size.toFloat()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(fontSeekBar)

        addSpacer(layout)

        // Theme
        addSectionHeader(layout, "Color Theme")
        val themes = TerminalColors.getAllThemes().map { it.first }
        val themeSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                themes
            )
            setSelection(themes.indexOf(prefs.themeName).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    prefs.themeName = themes[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(themeSpinner)

        addSpacer(layout)

        // Shell
        addSectionHeader(layout, "Shell")
        val shells = com.termx.app.utils.ShellUtils.getAvailableShells()
        val shellSpinner = android.widget.Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                shells
            )
            setSelection(shells.indexOf(prefs.shellPath).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    prefs.shellPath = shells[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(shellSpinner)

        addSpacer(layout)

        // Toggles
        addSectionHeader(layout, "Preferences")

        addToggle(layout, "Show Extra Keys", prefs.showExtraKeys) { prefs.showExtraKeys = it }
        addToggle(layout, "Cursor Blink", prefs.cursorBlink) { prefs.cursorBlink = it }
        addToggle(layout, "Bell Vibration", prefs.bellVibrate) { prefs.bellVibrate = it }
        addToggle(layout, "Keep Screen On", prefs.keepScreenOn) { prefs.keepScreenOn = it }
        addToggle(layout, "Close Sessions on Exit", prefs.closeOnExit) { prefs.closeOnExit = it }

        addSpacer(layout)

        // Scrollback size
        addSectionHeader(layout, "Scrollback Buffer")
        val scrollbackText = android.widget.TextView(this).apply {
            text = "Lines: ${prefs.scrollbackSize}"
            textSize = 16f
        }
        layout.addView(scrollbackText)

        val scrollbackSeekBar = SeekBar(this).apply {
            max = 100
            progress = prefs.scrollbackSize / 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val size = progress * 100
                    scrollbackText.text = "Lines: $size"
                    prefs.scrollbackSize = size
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(scrollbackSeekBar)

        // Restart note
        addSpacer(layout)
        val noteText = android.widget.TextView(this).apply {
            text = "Note: Some changes require restarting the terminal session to take effect."
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
        }
        layout.addView(noteText)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun addSectionHeader(layout: android.widget.LinearLayout, title: String) {
        val textView = android.widget.TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(textView)
    }

    private fun addSpacer(layout: android.widget.LinearLayout) {
        val spacer = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                2
            )
            setBackgroundColor(android.graphics.Color.parseColor("#333344"))
        }
        layout.addView(spacer)
    }

    private fun addToggle(
        layout: android.widget.LinearLayout,
        label: String,
        initialValue: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val textView = android.widget.TextView(this).apply {
            text = label
            textSize = 16f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val switch = android.widget.Switch(this).apply {
            isChecked = initialValue
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }

        row.addView(textView)
        row.addView(switch)
        layout.addView(row)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
