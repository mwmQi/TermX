package com.termx.app.ui.floatwin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.termx.app.terminal.TerminalColors
import com.termx.app.terminal.TerminalView

/**
 * Floating terminal window overlay.
 * Similar to Termux:Float - shows a small terminal in a floating window.
 *
 * Requires SYSTEM_ALERT_WINDOW permission (for overlays).
 */
class FloatingTerminal(private val context: Context) {

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: View? = null
    private var terminalView: TerminalView? = null
    private var isShowing = false

    // Position and size
    private var posX = 100
    private var posY = 100
    private var width = 600
    private var height = 400

    // Touch tracking for drag
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    /**
     * Show the floating terminal window.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        val wmParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            width = this@FloatingTerminal.width
            height = this@FloatingTerminal.height
        }

        // Build the floating view
        floatView = buildFloatView(wmParams)

        windowManager.addView(floatView, wmParams)
        isShowing = true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFloatView(wmParams: WindowManager.LayoutParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E2E.toInt())
        }

        // Title bar (drag handle + close button)
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF181825.toInt())
            setPadding(8, 4, 8, 4)
        }

        val titleText = android.widget.TextView(context).apply {
            text = "  λ TermX Float"
            setTextColor(0xFF89B4FA.toInt())
            textSize = 12f
            this.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val resizeBtn = android.widget.TextView(context).apply {
            text = "⟷"
            setTextColor(0xFFA6ADC8.toInt())
            textSize = 14f
            setPadding(8, 0, 8, 0)
        }

        val closeBtn = android.widget.TextView(context).apply {
            text = "✕"
            setTextColor(0xFFF38BA8.toInt())
            textSize = 14f
            setPadding(8, 0, 8, 0)
        }

        titleBar.addView(titleText)
        titleBar.addView(resizeBtn)
        titleBar.addView(closeBtn)

        // Terminal view
        terminalView = TerminalView(context).apply {
            colors = TerminalColors.catppuccinMocha()
            setFontSize(10f)
        }

        val termContainer = FrameLayout(context).apply {
            this.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            addView(terminalView)
        }

        container.addView(titleBar)
        container.addView(termContainer)

        // Drag handling on title bar
        titleBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = wmParams.x
                    initialY = wmParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    wmParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    wmParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatView, wmParams)
                    true
                }
                else -> false
            }
        }

        // Close button
        closeBtn.setOnClickListener { dismiss() }

        // Resize (toggle between small/large)
        var isExpanded = false
        resizeBtn.setOnClickListener {
            if (isExpanded) {
                wmParams.width = 600
                wmParams.height = 400
            } else {
                wmParams.width = WindowManager.LayoutParams.MATCH_PARENT
                wmParams.height = WindowManager.LayoutParams.MATCH_PARENT
            }
            windowManager.updateViewLayout(floatView, wmParams)
            isExpanded = !isExpanded
        }

        return container
    }

    /**
     * Dismiss the floating window.
     */
    fun dismiss() {
        if (!isShowing) return

        try {
            floatView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}

        floatView = null
        terminalView = null
        isShowing = false
    }

    fun isShowing(): Boolean = isShowing

    fun getTerminalView(): TerminalView? = terminalView
}
