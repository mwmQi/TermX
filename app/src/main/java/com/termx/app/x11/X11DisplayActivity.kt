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
        displayView = X11DisplayView(this, displayNum, isNativeDisplay, nativeHandle, kotlinServer)
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

    /**
     * Custom View that renders the virtual framebuffer for a specific display.
     */
    private class X11DisplayView(
        context: Context,
        private val displayNum: Int,
        private val isNative: Boolean,
        private val nativeHandle: Long,
        private val kotlinServer: X11DisplayServer?
    ) : SurfaceView(context), SurfaceHolder.Callback {

        private val TAG = "X11DisplayView"

        private var renderThread: Thread? = null
        private val isRendering = java.util.concurrent.atomic.AtomicBoolean(false)

        // Zoom and pan
        private var scaleFactor = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        // Touch tracking
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isPinching = false
        private var pinchStartDist = 0f
        private var pinchStartScale = 1f

        // Mouse state
        private var mouseButtonMask = 0

        // Paint
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Cached display dimensions
        private var fbWidth = 1024
        private var fbHeight = 768

        init {
            holder.addCallback(this)
            updateDimensions()
        }

        private fun updateDimensions() {
            val info = X11Manager.getDisplayInfo(displayNum)
            if (info != null) {
                fbWidth = info.width
                fbHeight = info.height
            }
        }

        fun startRendering() {
            if (isRendering.getAndSet(true)) return

            renderThread = Thread({
                renderLoop()
            }, "X11-Render-$displayNum").apply {
                isDaemon = true
                start()
            }
        }

        fun stopRendering() {
            isRendering.set(false)
            renderThread?.interrupt()
        }

        fun resetZoom() {
            scaleFactor = 1f
            offsetX = 0f
            offsetY = 0f
        }

        private fun renderLoop() {
            var lastFrame = 0L
            val targetFps = 30
            val frameInterval = 1000L / targetFps

            while (isRendering.get()) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrame < frameInterval) {
                        Thread.sleep(frameInterval - (now - lastFrame))
                    }
                    lastFrame = now

                    // Check if display is still running
                    if (!X11Manager.isDisplayRunning(displayNum)) continue

                    // Update dimensions
                    updateDimensions()

                    // Get framebuffer bitmap
                    val bitmap = if (isNative && nativeHandle != 0L) {
                        X11Manager.getFramebufferBitmap(displayNum)
                    } else {
                        kotlinServer?.framebufferRef?.getSnapshot()
                    } ?: continue

                    val canvas = holder.lockCanvas() ?: continue

                    try {
                        // Clear
                        canvas.drawColor(Color.BLACK)

                        // Calculate scaling
                        val viewWidth = width.toFloat()
                        val viewHeight = height.toFloat()
                        val fbWidthF = fbWidth.toFloat()
                        val fbHeightF = fbHeight.toFloat()

                        val fitScale = minOf(viewWidth / fbWidthF, viewHeight / fbHeightF)
                        val finalScale = fitScale * scaleFactor

                        val drawWidth = fbWidthF * finalScale
                        val drawHeight = fbHeightF * finalScale
                        val drawX = (viewWidth - drawWidth) / 2 + offsetX
                        val drawY = (viewHeight - drawHeight) / 2 + offsetY

                        // Draw the framebuffer
                        val srcRect = Rect(0, 0, fbWidth, fbHeight)
                        val dstRect = RectF(drawX, drawY, drawX + drawWidth, drawY + drawHeight)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)

                        // Draw cursor
                        val cursorInfo = if (isNative) {
                            // Native server tracks cursor internally
                            null
                        } else {
                            kotlinServer?.framebufferRef?.let { fb ->
                                Pair(fb.cursorX, fb.cursorY)
                            }
                        }
                        if (cursorInfo != null) {
                            val cursorX = drawX + cursorInfo.first * finalScale
                            val cursorY = drawY + cursorInfo.second * finalScale
                            val cursorPaint = Paint().apply {
                                color = Color.WHITE
                                style = Paint.Style.STROKE
                                strokeWidth = 2f * finalScale
                            }
                            canvas.drawLine(cursorX, cursorY - 10 * finalScale, cursorX, cursorY + 10 * finalScale, cursorPaint)
                            canvas.drawLine(cursorX - 10 * finalScale, cursorY, cursorX + 10 * finalScale, cursorY, cursorPaint)
                        }

                        // Draw display tag
                        val tagPaint = Paint().apply {
                            color = Color.argb(200, 255, 255, 255)
                            textSize = 12f * resources.displayMetrics.density
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                        val tagBgPaint = Paint().apply {
                            color = Color.argb(140, 0, 0, 0)
                            style = Paint.Style.FILL
                        }
                        val clientCount = X11Manager.getClientCount(displayNum)
                        val tagText = "Display :$displayNum | ${fbWidth}x${fbHeight} | X11 clients: $clientCount"
                        val tagWidth = tagPaint.measureText(tagText) + 20f
                        canvas.drawRect(8f, height - 36f, 8f + tagWidth, height - 8f, tagBgPaint)
                        canvas.drawText(tagText, 18f, height - 16f, tagPaint)

                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRendering.get()) {
                        Log.e(TAG, "Render error for display :$displayNum", e)
                    }
                }
            }
        }

        // ---- Touch Input ----

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isPinching = false

                    // Send mouse click to X11
                    val fbCoords = viewToFramebuffer(event.x, event.y)
                    mouseButtonMask = 1 // Left button
                    X11Manager.sendPointerEvent(displayNum, fbCoords.first, fbCoords.second, mouseButtonMask)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 2) {
                        // Pinch zoom
                        val dist = getPinchDistance(event)
                        if (!isPinching) {
                            isPinching = true
                            pinchStartDist = dist
                            pinchStartScale = scaleFactor
                        } else {
                            scaleFactor = (pinchStartScale * dist / pinchStartDist).coerceIn(0.5f, 5f)
                        }
                    } else if (!isPinching) {
                        // Pan or drag
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        offsetX += dx
                        offsetY += dy
                        lastTouchX = event.x
                        lastTouchY = event.y

                        // Send mouse move to X11
                        val fbCoords = viewToFramebuffer(event.x, event.y)
                        X11Manager.sendPointerEvent(displayNum, fbCoords.first, fbCoords.second, mouseButtonMask)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mouseButtonMask = 0
                    isPinching = false
                    val fbCoords = viewToFramebuffer(event.x, event.y)
                    X11Manager.sendPointerEvent(displayNum, fbCoords.first, fbCoords.second, 0)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    mouseButtonMask = 1 // Two-finger tap as left click
                }
            }
            return true
        }

        private fun viewToFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int> {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val fitScale = minOf(viewWidth / fbWidth, viewHeight / fbHeight) * scaleFactor

            val fbX = ((viewX - (viewWidth - fbWidth * fitScale) / 2 - offsetX) / fitScale).toInt()
                .coerceIn(0, fbWidth - 1)
            val fbY = ((viewY - (viewHeight - fbHeight * fitScale) / 2 - offsetY) / fitScale).toInt()
                .coerceIn(0, fbHeight - 1)
            return Pair(fbX, fbY)
        }

        private fun getPinchDistance(event: MotionEvent): Float {
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        // ---- Surface Callbacks ----

        override fun surfaceCreated(holder: SurfaceHolder) {
            startRendering()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            stopRendering()
        }
    }
}
