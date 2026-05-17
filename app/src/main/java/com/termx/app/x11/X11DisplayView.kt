package com.termx.app.x11

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Custom View that renders the virtual framebuffer for a specific X11 display.
 * Renders the X11 framebuffer scaled to fit the view with pinch-to-zoom support.
 */
class X11DisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val TAG = "X11DisplayView"

    private var displayNum: Int = 0
    private var isNative: Boolean = false
    private var nativeHandle: Long = 0L
    private var kotlinServer: X11DisplayServer? = null

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

    // Cached display dimensions — updated from X11Manager info
    @Volatile private var fbWidth: Int = 1024
    @Volatile private var fbHeight: Int = 768

    // Track if we've already resized to avoid repeated resize calls
    private var hasResizedToView = false

    init {
        holder.addCallback(this)
        setWillNotDraw(false)
    }

    fun initDisplay(displayNum: Int, isNative: Boolean, nativeHandle: Long, kotlinServer: X11DisplayServer?) {
        this.displayNum = displayNum
        this.isNative = isNative
        this.nativeHandle = nativeHandle
        this.kotlinServer = kotlinServer
        hasResizedToView = false
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !hasResizedToView) {
            // Only auto-resize the X11 display once when the view is first laid out.
            // This avoids expensive framebuffer reallocations on every rotation/keyboard event.
            // Use 90% of view size to leave some margin.
            val targetW = (w * 0.9f).toInt().coerceIn(640, 2560)
            val targetH = (h * 0.9f).toInt().coerceIn(480, 1440)
            val alignedW = (targetW / 8) * 8
            val alignedH = (targetH / 8) * 8

            // Only resize if dimensions differ significantly from current
            if (alignedW != fbWidth || alignedH != fbHeight) {
                X11Manager.resizeDisplay(displayNum, alignedW, alignedH)
                updateDimensions()
            }
            hasResizedToView = true
        }
    }

    private fun renderLoop() {
        var lastFrame = 0L
        val targetFps = 30
        val frameInterval = 1000L / targetFps

        while (isRendering.get()) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrame < frameInterval) {
                    Thread.sleep(kotlin.math.max(1, frameInterval - (now - lastFrame)))
                }
                lastFrame = now

                // Check if display is still running
                if (!X11Manager.isDisplayRunning(displayNum)) {
                    Thread.sleep(500)
                    continue
                }

                // Update dimensions from manager (may have changed externally)
                updateDimensions()

                if (fbWidth <= 0 || fbHeight <= 0) {
                    Thread.sleep(100)
                    continue
                }

                // Get framebuffer bitmap
                val bitmap = try {
                    if (isNative && nativeHandle != 0L) {
                        X11Manager.getFramebufferBitmap(displayNum)
                    } else {
                        kotlinServer?.framebufferRef?.getSnapshot()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get bitmap for display :$displayNum", e)
                    null
                } ?: continue

                val canvas = try {
                    holder.lockCanvas()
                } catch (e: Exception) {
                    null
                } ?: continue

                try {
                    // Clear
                    canvas.drawColor(Color.BLACK)

                    // Calculate scaling to fit view
                    val viewWidth = width.toFloat()
                    val viewHeight = height.toFloat()
                    val fbWidthF = fbWidth.toFloat()
                    val fbHeightF = fbHeight.toFloat()

                    if (fbWidthF <= 0 || fbHeightF <= 0) continue

                    val fitScale = kotlin.math.min(viewWidth / fbWidthF, viewHeight / fbHeightF)
                    val finalScale = fitScale * scaleFactor

                    val drawWidth = fbWidthF * finalScale
                    val drawHeight = fbHeightF * finalScale
                    val drawX = (viewWidth - drawWidth) / 2 + offsetX
                    val drawY = (viewHeight - drawHeight) / 2 + offsetY

                    // Draw the framebuffer
                    val srcRect = Rect(0, 0, fbWidth, fbHeight)
                    val dstRect = RectF(drawX, drawY, drawX + drawWidth, drawY + drawHeight)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)

                    // Draw cursor for Kotlin server
                    val cursorInfo = if (!isNative) {
                        kotlinServer?.framebufferRef?.let { fb ->
                            Pair(fb.cursorX, fb.cursorY)
                        }
                    } else null

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

                    // Draw display tag overlay
                    val tagPaint = Paint().apply {
                        color = Color.argb(200, 255, 255, 255)
                        textSize = 12f * resources.displayMetrics.density
                        typeface = Typeface.MONOSPACE
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isPinching = false

                val fbCoords = viewToFramebuffer(event.x, event.y)
                mouseButtonMask = 1
                X11Manager.sendPointerEvent(displayNum, fbCoords.first, fbCoords.second, mouseButtonMask)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2) {
                    val dist = getPinchDistance(event)
                    if (!isPinching) {
                        isPinching = true
                        pinchStartDist = dist
                        pinchStartScale = scaleFactor
                    } else {
                        scaleFactor = (pinchStartScale * dist / pinchStartDist).coerceIn(0.25f, 5f)
                    }
                } else if (!isPinching) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    offsetX += dx
                    offsetY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y

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
                // Don't change mouse state on multi-touch
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Check if we still have 2 fingers
                if (event.pointerCount < 2) {
                    isPinching = false
                }
            }
        }
        return true
    }

    private fun viewToFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int> {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val fbW = fbWidth.toFloat()
        val fbH = fbHeight.toFloat()

        if (fbW <= 0 || fbH <= 0) return Pair(0, 0)

        val fitScale = kotlin.math.min(viewWidth / fbW, viewHeight / fbH) * scaleFactor

        if (fitScale <= 0f) return Pair(0, 0)

        val fbX = ((viewX - (viewWidth - fbW * fitScale) / 2 - offsetX) / fitScale).toInt()
            .coerceIn(0, fbWidth - 1)
        val fbY = ((viewY - (viewHeight - fbH * fitScale) / 2 - offsetY) / fitScale).toInt()
            .coerceIn(0, fbHeight - 1)
        return Pair(fbX, fbY)
    }

    private fun getPinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startRendering()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }
}
