package com.termx.app.x11

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Virtual Framebuffer — the core of the TermX X11 display system.
 *
 * This class implements an in-memory screen buffer that acts as a virtual display,
 * similar to Xvfb (X Virtual Framebuffer) on Linux. It provides:
 *
 * - A pixel buffer (ARGB_8888) that GUI applications can draw into
 * - Dirty region tracking for efficient VNC/remote updates
 * - Thread-safe read/write access with read-write locks
 * - Bitmap snapshots for rendering in Android views
 * - Binary frame data export for VNC RFB protocol
 * - Resolution change support with buffer reallocation
 *
 * The framebuffer is the bridge between X11 client applications (which write pixels)
 * and display consumers (the X11Activity, VNC clients, etc.) which read pixels.
 *
 * Architecture:
 *   X11 Client → writes pixels → VirtualFramebuffer ← reads pixels ← VNC/Activity
 *                                      ↕
 *                               Dirty region tracker
 *                                      ↕
 *                               Change notification
 */
class VirtualFramebuffer(
    var width: Int = 1024,
    var height: Int = 768,
    var bpp: Int = 32  // Bits per pixel (ARGB_8888)
) {

    companion object {
        private const val TAG = "VirtualFramebuffer"

        /** RFB pixel format constants for VNC protocol */
        const val RFB_BITS_PER_PIXEL = 32
        const val RFB_DEPTH = 24
        const val RFB_BIG_ENDIAN = false
        const val RFB_TRUE_COLOR = true
        const val RFB_RED_MAX = 255
        const val RFB_GREEN_MAX = 255
        const val RFB_BLUE_MAX = 255
        const val RFB_RED_SHIFT = 0
        const val RFB_GREEN_SHIFT = 8
        const val RFB_BLUE_SHIFT = 16
    }

    // The main pixel buffer — stored as a Bitmap for Android rendering
    @Volatile
    private var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    // Raw pixel data buffer for VNC output (BGRA format)
    @Volatile
    private var rawBuffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    // Thread safety
    private val lock = ReentrantReadWriteLock()
    private val isRunning = AtomicBoolean(false)

    // Dirty region tracking — tracks which parts of the screen changed
    @Volatile var dirtyX: Int = 0
    @Volatile var dirtyY: Int = 0
    @Volatile var dirtyWidth: Int = width
    @Volatile var dirtyHeight: Int = height
    @Volatile var hasDirtyRegion: Boolean = true  // Initially full screen is dirty

    // Frame counter for change detection
    @Volatile var frameCount: Long = 0
        private set

    // Callback when the framebuffer content changes
    var onFrameUpdate: ((x: Int, y: Int, w: Int, h: Int) -> Unit)? = null

    // Cursor position (for VNC cursor rendering)
    @Volatile var cursorX: Int = width / 2
    @Volatile var cursorY: Int = height / 2
    @Volatile var cursorVisible: Boolean = true

    /**
     * Start the framebuffer.
     */
    fun start() {
        if (isRunning.getAndSet(true)) return
        // Fill with a default background
        lock.write {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#1E1E2E")) // Catppuccin Mocha bg
            markDirty(0, 0, width, height)
        }
        Log.i(TAG, "VirtualFramebuffer started: ${width}x${height}")
    }

    /**
     * Stop the framebuffer and release resources.
     */
    fun stop() {
        isRunning.set(false)
        Log.i(TAG, "VirtualFramebuffer stopped")
    }

    val running: Boolean get() = isRunning.get()

    // ---- Pixel Write Operations ----

    /**
     * Write a single pixel at (x, y).
     * Thread-safe with dirty region tracking.
     */
    fun putPixel(x: Int, y: Int, color: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        lock.write {
            bitmap.setPixel(x, y, color)
            markDirty(x, y, 1, 1)
        }
    }

    /**
     * Write a rectangular region of pixels from a byte array.
     * Input format: ARGB or BGRA depending on format parameter.
     * This is the primary method for X11 client rendering.
     *
     * @param x      Destination X offset
     * @param y      Destination Y offset
     * @param w      Width of the source rectangle
     * @param h      Height of the source rectangle
     * @param data   Raw pixel data
     * @param offset Offset into data array
     * @param format Pixel format: "argb", "bgra", "rgba"
     */
    fun putRect(x: Int, y: Int, w: Int, h: Int, data: ByteArray, offset: Int = 0, format: String = "bgra") {
        if (x >= width || y >= height) return
        if (x + w > width || y + h > height) {
            // Clip to framebuffer bounds
            val clipW = minOf(w, width - x)
            val clipH = minOf(h, height - y)
            putRect(x, y, clipW, clipH, data, offset, format)
            return
        }

        lock.write {
            val pixels = IntArray(w * h)
            val bytesPerPixel = 4

            for (py in 0 until h) {
                for (px in 0 until w) {
                    val srcIdx = offset + (py * w + px) * bytesPerPixel
                    if (srcIdx + 3 >= data.size) break

                    val pixel = when (format) {
                        "bgra" -> {
                            val b = data[srcIdx].toInt() and 0xFF
                            val g = data[srcIdx + 1].toInt() and 0xFF
                            val r = data[srcIdx + 2].toInt() and 0xFF
                            val a = data[srcIdx + 3].toInt() and 0xFF
                            Color.argb(a, r, g, b)
                        }
                        "rgba" -> {
                            val r = data[srcIdx].toInt() and 0xFF
                            val g = data[srcIdx + 1].toInt() and 0xFF
                            val b = data[srcIdx + 2].toInt() and 0xFF
                            val a = data[srcIdx + 3].toInt() and 0xFF
                            Color.argb(a, r, g, b)
                        }
                        "argb" -> {
                            val a = data[srcIdx].toInt() and 0xFF
                            val r = data[srcIdx + 1].toInt() and 0xFF
                            val g = data[srcIdx + 2].toInt() and 0xFF
                            val b = data[srcIdx + 3].toInt() and 0xFF
                            Color.argb(a, r, g, b)
                        }
                        else -> Color.BLACK
                    }
                    pixels[py * w + px] = pixel
                }
            }

            bitmap.setPixels(pixels, 0, w, x, y, w, h)
            markDirty(x, y, w, h)
        }
    }

    /**
     * Write an Android Bitmap to the framebuffer at the given position.
     */
    fun putBitmap(x: Int, y: Int, src: Bitmap) {
        lock.write {
            val canvas = Canvas(bitmap)
            canvas.drawBitmap(src, x.toFloat(), y.toFloat(), null)
            markDirty(x, y, src.width, src.height)
        }
    }

    /**
     * Fill a rectangular area with a solid color.
     */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        lock.write {
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint().apply { this.color = color }
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), paint)
            markDirty(x, y, w, h)
        }
    }

    /**
     * Clear the entire framebuffer with a color.
     */
    fun clear(color: Int = Color.parseColor("#1E1E2E")) {
        lock.write {
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            markDirty(0, 0, width, height)
        }
    }

    // ---- Pixel Read Operations ----

    /**
     * Get a snapshot of the entire framebuffer as a Bitmap.
     * Returns a copy — modifications to the returned bitmap don't affect the framebuffer.
     */
    fun getSnapshot(): Bitmap {
        return lock.read {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /**
     * Get raw pixel data in BGRA format for VNC output.
     * Returns a ByteArray of size (w * h * 4).
     */
    fun getRawRect(x: Int, y: Int, w: Int, h: Int): ByteArray {
        return lock.read {
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, x, y, w, h)

            val raw = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val base = i * 4
                raw[base] = (pixel and 0xFF).toByte()          // B
                raw[base + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
                raw[base + 2] = ((pixel shr 16) and 0xFF).toByte() // R
                raw[base + 3] = ((pixel shr 24) and 0xFF).toByte() // A
            }
            raw
        }
    }

    /**
     * Get the full framebuffer as raw BGRA bytes.
     * Used by VNC server for full screen updates.
     */
    fun getRawFrame(): ByteArray {
        return getRawRect(0, 0, width, height)
    }

    /**
     * Get a single pixel color.
     */
    fun getPixel(x: Int, y: Int): Int {
        return lock.read {
            if (x in 0 until width && y in 0 until height) bitmap.getPixel(x, y)
            else 0
        }
    }

    // ---- Dirty Region Management ----

    /**
     * Mark a region as dirty (modified).
     * Merges with existing dirty region for efficient batch updates.
     */
    private fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        if (!hasDirtyRegion) {
            dirtyX = x
            dirtyY = y
            dirtyWidth = w
            dirtyHeight = h
            hasDirtyRegion = true
        } else {
            // Merge regions
            val newX = minOf(dirtyX, x)
            val newY = minOf(dirtyY, y)
            val newRight = maxOf(dirtyX + dirtyWidth, x + w)
            val newBottom = maxOf(dirtyY + dirtyHeight, y + h)
            dirtyX = newX
            dirtyY = newY
            dirtyWidth = newRight - newX
            dirtyHeight = newBottom - newY
        }

        frameCount++
        onFrameUpdate?.invoke(x, y, w, h)
    }

    /**
     * Get the dirty region and clear it.
     * Returns null if no dirty region.
     */
    fun consumeDirtyRegion(): DirtyRect? {
        if (!hasDirtyRegion) return null
        hasDirtyRegion = false
        return DirtyRect(dirtyX, dirtyY, dirtyWidth, dirtyHeight)
    }

    /**
     * Force the entire screen to be marked dirty.
     */
    fun markFullDirty() {
        markDirty(0, 0, width, height)
    }

    // ---- Resize ----

    /**
     * Resize the framebuffer.
     * Creates a new buffer and copies what fits from the old one.
     */
    fun resize(newWidth: Int, newHeight: Int) {
        lock.write {
            val oldBitmap = bitmap
            bitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#1E1E2E"))
            canvas.drawBitmap(oldBitmap, 0f, 0f, null)

            rawBuffer = ByteBuffer.allocateDirect(newWidth * newHeight * 4)
                .order(ByteOrder.LITTLE_ENDIAN)

            width = newWidth
            height = newHeight
            markDirty(0, 0, newWidth, newHeight)
        }
        Log.i(TAG, "Framebuffer resized to ${newWidth}x${newHeight}")
    }

    // ---- Cursor ----

    /**
     * Update the cursor position.
     */
    fun moveCursor(x: Int, y: Int) {
        val oldX = cursorX
        val oldY = cursorY
        cursorX = x.coerceIn(0, width - 1)
        cursorY = y.coerceIn(0, height - 1)
        // Mark old and new cursor positions as dirty
        markDirty(oldX - 16, oldY - 16, 32, 32)
        markDirty(cursorX - 16, cursorY - 16, 32, 32)
    }

    // ---- Data Classes ----

    data class DirtyRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )
}
