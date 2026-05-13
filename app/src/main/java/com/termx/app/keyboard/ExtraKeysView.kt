package com.termx.app.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.termx.app.terminal.TerminalSession
import com.termx.app.terminal.TerminalView

/**
 * Extra keys bar for terminal - provides special keys like ESC, Ctrl, Alt, Tab, arrows, etc.
 * Similar to Termux's extra keys row.
 */
class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container: LinearLayout
    private var terminalView: TerminalView? = null

    // Modifier key states (sticky toggles)
    private var ctrlActive = false
    private var altActive = false
    private var fnActive = false

    // Pending character for Ctrl/Alt combo
    private var pendingModifier: Modifier = Modifier.NONE

    private enum class Modifier { NONE, CTRL, ALT, FN }

    // Key definitions
    private data class ExtraKey(
        val label: String,
        val modifier: Modifier = Modifier.NONE,
        val isSticky: Boolean = false,
        val action: (() -> Unit)? = null
    )

    private val keys: List<ExtraKey> = listOf(
        ExtraKey("ESC", action = { sendEscape() }),
        ExtraKey("CTRL", modifier = Modifier.CTRL, isSticky = true),
        ExtraKey("ALT", modifier = Modifier.ALT, isSticky = true),
        ExtraKey("FN", modifier = Modifier.FN, isSticky = true),
        ExtraKey("TAB", action = { sendTab() }),
        ExtraKey("HOME", action = { sendHome() }),
        ExtraKey("END", action = { sendEnd() }),
        ExtraKey("PGUP", action = { sendPageUp() }),
        ExtraKey("PGDN", action = { sendPageDown() }),
        ExtraKey("←", action = { sendArrow(TerminalSession.ArrowDirection.LEFT) }),
        ExtraKey("↑", action = { sendArrow(TerminalSession.ArrowDirection.UP) }),
        ExtraKey("↓", action = { sendArrow(TerminalSession.ArrowDirection.DOWN) }),
        ExtraKey("→", action = { sendArrow(TerminalSession.ArrowDirection.RIGHT) }),
        ExtraKey("DEL", action = { sendDelete() }),
        ExtraKey("INS", action = { sendInsert() }),
        ExtraKey("F1", action = { sendFKey(1) }),
        ExtraKey("F2", action = { sendFKey(2) }),
        ExtraKey("F3", action = { sendFKey(3) }),
        ExtraKey("F4", action = { sendFKey(4) }),
        ExtraKey("F5", action = { sendFKey(5) }),
        ExtraKey("F6", action = { sendFKey(6) }),
        ExtraKey("F7", action = { sendFKey(7) }),
        ExtraKey("F8", action = { sendFKey(8) }),
        ExtraKey("F9", action = { sendFKey(9) }),
        ExtraKey("F10", action = { sendFKey(10) }),
        ExtraKey("F11", action = { sendFKey(11) }),
        ExtraKey("F12", action = { sendFKey(12) }),
        ExtraKey("~", action = { sendChar('~') }),
        ExtraKey("|", action = { sendChar('|') }),
        ExtraKey("/", action = { sendChar('/') }),
        ExtraKey("\\", action = { sendChar('\\') }),
        ExtraKey("-", action = { sendChar('-') }),
        ExtraKey("_", action = { sendChar('_') }),
        ExtraKey(":", action = { sendChar(':') }),
        ExtraKey(";", action = { sendChar(';') }),
        ExtraKey("{", action = { sendChar('{') }),
        ExtraKey("}", action = { sendChar('}') }),
        ExtraKey("[", action = { sendChar('[') }),
        ExtraKey("]", action = { sendChar(']') }),
        ExtraKey("(", action = { sendChar('(') }),
        ExtraKey(")", action = { sendChar(')') }),
        ExtraKey("<", action = { sendChar('<') }),
        ExtraKey(">", action = { sendChar('>') }),
        ExtraKey("$", action = { sendChar('$') }),
        ExtraKey("&", action = { sendChar('&') }),
        ExtraKey("*", action = { sendChar('*') }),
        ExtraKey("=", action = { sendChar('=') }),
        ExtraKey("+", action = { sendChar('+') }),
        ExtraKey("\"", action = { sendChar('"') }),
        ExtraKey("'", action = { sendChar('\'') }),
        ExtraKey("`", action = { sendChar('`') }),
        ExtraKey("!", action = { sendChar('!') }),
        ExtraKey("#", action = { sendChar('#') }),
        ExtraKey("@", action = { sendChar('@') }),
        ExtraKey("^", action = { sendChar('^') }),
        ExtraKey("%", action = { sendChar('%') }),
        ExtraKey("?", action = { sendChar('?') }),
        ExtraKey(",", action = { sendChar(',') }),
        ExtraKey(".", action = { sendChar('.') }),
    )

    private val keyViews: MutableList<TextView> = mutableListOf()

    init {
        container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
            setPadding(4, 2, 4, 2)
        }
        addView(container)
        isHorizontalScrollBarEnabled = false

        buildKeys()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildKeys() {
        container.removeAllViews()
        keyViews.clear()

        val density = resources.displayMetrics.density
        val keyHeight = (36 * density).toInt()
        val keyPaddingH = (6 * density).toInt()
        val keyPaddingV = (2 * density).toInt()

        for ((index, key) in keys.withIndex()) {
            val keyView = TextView(context).apply {
                text = key.label
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#D4BE98"))
                setPadding(keyPaddingH, keyPaddingV, keyPaddingH, keyPaddingV)

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2A2B3C"))
                    cornerRadius = 4 * density
                    setStroke(1, Color.parseColor("#3E4452"))
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    keyHeight
                ).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }

                // Touch handling
                setOnTouchListener { v, event ->
                    val tv = v as TextView
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            (v as? android.widget.TextView)?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            v.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#4A4B6C"))
                                cornerRadius = 4 * density
                                setStroke(1, Color.parseColor("#6C7086"))
                            }
<<<<<<< HEAD
                            (v as? android.widget.TextView)?.setTextColor(Color.WHITE)
=======
                            tv.setTextColor(Color.WHITE)
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.background = GradientDrawable().apply {
                                val bgColor = if (key.isSticky && isModifierActive(key.modifier)) {
                                    Color.parseColor("#4A6A8C")
                                } else {
                                    Color.parseColor("#2A2B3C")
                                }
                                setColor(bgColor)
                                cornerRadius = 4 * density
                                setStroke(1, Color.parseColor("#3E4452"))
                            }
<<<<<<< HEAD
                            (v as? android.widget.TextView)?.setTextColor(Color.parseColor("#D4BE98"))
=======
                            tv.setTextColor(Color.parseColor("#D4BE98"))
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)

                            handleKeyPress(key)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.background = GradientDrawable().apply {
                                setColor(Color.parseColor("#2A2B3C"))
                                cornerRadius = 4 * density
                                setStroke(1, Color.parseColor("#3E4452"))
                            }
<<<<<<< HEAD
                            (v as? android.widget.TextView)?.setTextColor(Color.parseColor("#D4BE98"))
=======
                            tv.setTextColor(Color.parseColor("#D4BE98"))
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                            true
                        }
                        else -> false
                    }
                }
            }

            keyViews.add(keyView)
            container.addView(keyView)
        }
    }

    private fun handleKeyPress(key: ExtraKey) {
        if (key.isSticky) {
            toggleModifier(key.modifier)
            return
        }

        // Apply pending modifier
        when (pendingModifier) {
            Modifier.CTRL -> {
                if (key.action != null) {
                    key.action!!()
                }
                // Reset sticky modifiers after use
                clearModifiers()
            }
            Modifier.ALT -> {
                if (key.action != null) {
                    key.action!!()
                }
                clearModifiers()
            }
            Modifier.FN -> {
                handleFnKey(key)
                clearModifiers()
            }
            Modifier.NONE -> {
                key.action?.invoke()
            }
        }
    }

    private fun toggleModifier(modifier: Modifier) {
        when (modifier) {
            Modifier.CTRL -> {
                ctrlActive = !ctrlActive
                pendingModifier = if (ctrlActive) Modifier.CTRL else Modifier.NONE
            }
            Modifier.ALT -> {
                altActive = !altActive
                pendingModifier = if (altActive) Modifier.ALT else Modifier.NONE
            }
            Modifier.FN -> {
                fnActive = !fnActive
                pendingModifier = if (fnActive) Modifier.FN else Modifier.NONE
            }
            Modifier.NONE -> {}
        }
        updateKeyVisuals()
    }

    private fun isModifierActive(modifier: Modifier): Boolean {
        return when (modifier) {
            Modifier.CTRL -> ctrlActive
            Modifier.ALT -> altActive
            Modifier.FN -> fnActive
            Modifier.NONE -> false
        }
    }

    private fun clearModifiers() {
        ctrlActive = false
        altActive = false
        fnActive = false
        pendingModifier = Modifier.NONE
        updateKeyVisuals()
    }

    private fun updateKeyVisuals() {
        val density = resources.displayMetrics.density
        for ((index, key) in keys.withIndex()) {
            if (key.isSticky && index < keyViews.size) {
                val isActive = isModifierActive(key.modifier)
                keyViews[index].background = GradientDrawable().apply {
                    setColor(if (isActive) Color.parseColor("#4A6A8C") else Color.parseColor("#2A2B3C"))
                    cornerRadius = 4 * density
                    setStroke(1, Color.parseColor("#3E4452"))
                }
                keyViews[index].setTextColor(
                    if (isActive) Color.WHITE else Color.parseColor("#D4BE98")
                )
            }
        }
    }

    private fun handleFnKey(key: ExtraKey) {
        // FN combos - e.g., FN + number = F-key
        // Add custom FN mappings here
    }

    // ---- Send Functions ----

    private fun sendEscape() {
        terminalView?.session?.write("\u001B")
    }

    private fun sendTab() {
        terminalView?.session?.sendTab()
    }

    private fun sendHome() {
        terminalView?.session?.sendHome()
    }

    private fun sendEnd() {
        terminalView?.session?.sendEnd()
    }

    private fun sendPageUp() {
        terminalView?.session?.sendPageUp()
    }

    private fun sendPageDown() {
        terminalView?.session?.sendPageDown()
    }

    private fun sendArrow(direction: TerminalSession.ArrowDirection) {
        terminalView?.session?.sendArrowKey(direction)
    }

    private fun sendDelete() {
        terminalView?.session?.sendDelete()
    }

    private fun sendInsert() {
        terminalView?.session?.write("\u001B[2~")
    }

    private fun sendFKey(key: Int) {
        terminalView?.session?.sendFunctionKey(key)
    }

    private fun sendChar(c: Char) {
        if (ctrlActive) {
            terminalView?.session?.sendCtrl(c)
            clearModifiers()
        } else if (altActive) {
            terminalView?.session?.write("\u001B$c")
            clearModifiers()
        } else {
            terminalView?.session?.write(c.toString())
        }
    }

    // ---- Public API ----

    fun setTerminalView(view: TerminalView) {
        terminalView = view
    }
}
