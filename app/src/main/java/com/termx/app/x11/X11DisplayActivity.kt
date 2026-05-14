package com.termx.app.x11

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import com.termx.app.terminal.JniX11
import com.termx.app.utils.FullscreenManager

/**
 * Activity that renders an X11 virtual display session in-app.
 *
 * Each instance is associated with a specific display number (:0, :1, etc.)
 * and renders the virtual framebuffer from that display session.
 *
 * Touch/mouse input is forwarded to the X11 display, allowing interactive
 * control of GUI applications including browsers for Cloudflare CAPTCHA solving.
 *
 * The title bar shows the display number tag, e.g., "TermX Display :0".
 *
 * Features:
 *   - Real-time framebuffer rendering at 30fps
 *   - Touch → mouse event forwarding
 *   - Pinch-to-zoom
 *   - Virtual keyboard support
 *   - Fullscreen mode
 *   - Screenshot capture
 *   - Display number tag in title
 */
class X11DisplayActivity : Activity() {

    companion object {
        private const val TAG = "X11DisplayActivity"
        const val EXTRA_DISPLAY = "display_number"

        fun start(context: Context, displayNum: Int = 0) {
            val intent = Intent(context, X11DisplayActivity::class.java)
            intent.putExtra(EXTRA_DISPLAY, displayNum)
            context.startActivity(intent)
        }
    }

    private var displayView: X11DisplayView? = null
    private var displayNum: Int = 0
    private var isNativeDisplay: Boolean = false
    private var nativeHandle: Long = 0L
    private var kotlinServer: X11DisplayServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayNum = intent.getIntExtra(EXTRA_DISPLAY, 0)

        // Check which display backend to use
        val info = X11Manager.getDisplayInfo(displayNum)
        if (info == null) {
            Toast.makeText(this, "Display :$displayNum not running. Start one first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        isNativeDisplay = info.nativeHandle != 0L
        nativeHandle = info.nativeHandle
        kotlinServer = info.kotlinServer

        if (isNativeDisplay && nativeHandle == 0L && kotlinServer == null) {
            Toast.makeText(this, "Display :$displayNum has no backend available.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Create the display view
        displayView = X11DisplayView(this).apply {
            initDisplay(displayNum, isNativeDisplay, nativeHandle, kotlinServer)
        }
        setContentView(FrameLayout(this).apply {
            addView(displayView)
            setBackgroundColor(Color.BLACK)
        })

        // Enter fullscreen
        FullscreenManager.enterFullscreen(this)

        title = "TermX Display :$displayNum"
    }

    override fun onResume() {
        super.onResume()
        displayView?.startRendering()
    }

    override fun onPause() {
        super.onPause()
        displayView?.stopRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayView?.stopRendering()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, "Fullscreen").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 0, "Screenshot").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 3, 0, "Reset Zoom").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 4, 0, "Send Ctrl+Alt+Del")
        menu.add(0, 5, 0, "New Display :${X11Manager.allocateDisplayNum()}")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { FullscreenManager.toggle(this); true }
            2 -> {
                val path = "/data/data/com.termx.app/files/home/screenshot_${displayNum}_${System.currentTimeMillis()}.png"
                if (X11Manager.takeScreenshot(displayNum, path)) {
                    Toast.makeText(this, "Screenshot saved: $path", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show()
                }
                true
            }
            3 -> { displayView?.resetZoom(); true }
            4 -> {
                // Send Ctrl+Alt+Del
                X11Manager.sendKeyEvent(displayNum, 0xFF08, true)  // Backspace as Ctrl+Alt+Del
                X11Manager.sendKeyEvent(displayNum, 0xFF08, false)
                true
            }
            5 -> {
                // Start a new display session
                val newNum = X11Manager.allocateDisplayNum()
                if (newNum >= 0) {
                    X11Manager.startDisplay(this, newNum, 1024, 768)
                    start(this, newNum)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
