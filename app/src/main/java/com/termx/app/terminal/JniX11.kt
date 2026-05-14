package com.termx.app.terminal

import android.util.Log

/**
 * JNI bridge for the TermX X11 Virtual Display Server.
 *
 * Provides access to the native X11 server implemented in termx_x11.c.
 * This server creates a virtual framebuffer that GUI applications can
 * render to, enabling headless graphical operations like Cloudflare
 * CAPTCHA solving.
 *
 * Features:
 *   - Multiple display sessions (:0, :1, :2, etc.)
 *   - X11 core protocol (enough for Chromium, Firefox, etc.)
 *   - Virtual framebuffer with dirty region tracking
 *   - Keyboard and mouse input injection
 *   - Screenshot capture
 *   - Dynamic resolution changes
 *
 * Usage flow:
 *   1. nativeStartServer(displayNum, width, height) -> handle
 *   2. nativeIsRunning(handle) -> true
 *   3. Set DISPLAY=:displayNum in terminal session
 *   4. GUI apps connect to port 6000+displayNum
 *   5. nativeReadFramebuffer() to render in Android view
 *   6. nativeSendKeyEvent/nativeSendPointerEvent for input
 *   7. nativeStopServer(handle) to shutdown
 */
object JniX11 {

    private const val TAG = "JniX11"

    /**
     * Whether the native X11 library is available.
     * Checked by loading the library and verifying symbol presence.
     */
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("termx-x11")
            Log.i(TAG, "Native X11 library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native X11 library not available: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load X11 library: ${e.message}")
            false
        }
    }

    // ---- Server Lifecycle ----

    /**
     * Start an X11 virtual display server.
     *
     * @param displayNum Display number (0 for :0, 1 for :1, etc.)
     * @param width      Framebuffer width in pixels
     * @param height     Framebuffer height in pixels
     * @return Server handle (0 = failure)
     */
    fun nativeStartServer(displayNum: Int, width: Int, height: Int): Long {
        if (!isAvailable) return 0L
        return nativeStartServerInternal(displayNum, width, height)
    }

    /**
     * Stop the X11 server and release all resources.
     */
    fun nativeStopServer(handle: Long) {
        if (!isAvailable || handle == 0L) return
        nativeStopServerInternal(handle)
    }

    /**
     * Check if the server is running.
     */
    fun nativeIsRunning(handle: Long): Boolean {
        if (!isAvailable || handle == 0L) return false
        return nativeIsRunningInternal(handle)
    }

    // ---- Framebuffer Access ----

    /**
     * Get the raw framebuffer memory address.
     * Can be used for zero-copy rendering in advanced scenarios.
     */
    fun nativeGetFramebufferHandle(handle: Long): Long {
        if (!isAvailable || handle == 0L) return 0L
        return nativeGetFramebufferHandleInternal(handle)
    }

    /**
     * Read framebuffer data into a byte array.
     * The data is in RGBA format (4 bytes per pixel).
     *
     * @param handle Server handle
     * @param outArray Byte array to write into (must be width*height*4 bytes)
     * @param offset Offset into outArray
     * @param length Number of bytes to read
     * @return true if successful
     */
    fun nativeReadFramebuffer(handle: Long, outArray: ByteArray, offset: Int, length: Int): Boolean {
        if (!isAvailable || handle == 0L) return false
        return nativeReadFramebufferInternal(handle, outArray, offset, length)
    }

    /**
     * Take a screenshot and save to a file.
     * Saves as PPM format (can be converted to PNG by the Kotlin side).
     *
     * @param handle Server handle
     * @param path File path to save the screenshot
     * @return true if successful
     */
    fun nativeTakeScreenshot(handle: Long, path: String): Boolean {
        if (!isAvailable || handle == 0L) return false
        return nativeTakeScreenshotInternal(handle, path)
    }

    // ---- Input Injection ----

    /**
     * Send a keyboard event to the X11 server.
     *
     * @param handle Server handle
     * @param keysym X11 keysym value
     * @param down true for key press, false for key release
     */
    fun nativeSendKeyEvent(handle: Long, keysym: Int, down: Boolean) {
        if (!isAvailable || handle == 0L) return
        nativeSendKeyEventInternal(handle, keysym, down)
    }

    /**
     * Send a mouse/pointer event to the X11 server.
     *
     * @param handle Server handle
     * @param x X coordinate
     * @param y Y coordinate
     * @param buttonMask Button mask (bit 0=left, bit 1=middle, bit 2=right)
     */
    fun nativeSendPointerEvent(handle: Long, x: Int, y: Int, buttonMask: Int) {
        if (!isAvailable || handle == 0L) return
        nativeSendPointerEventInternal(handle, x, y, buttonMask)
    }

    // ---- Display Management ----

    /**
     * Resize the virtual display.
     * Sends ConfigureNotify events to all connected X11 clients.
     *
     * @param handle Server handle
     * @param width New width
     * @param height New height
     */
    fun nativeResize(handle: Long, width: Int, height: Int) {
        if (!isAvailable || handle == 0L) return
        nativeResizeInternal(handle, width, height)
    }

    /**
     * Get the number of connected X11 clients.
     */
    fun nativeGetClientCount(handle: Long): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeGetClientCountInternal(handle)
    }

    /**
     * Get the display width.
     */
    fun nativeGetWidth(handle: Long): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeGetWidthInternal(handle)
    }

    /**
     * Get the display height.
     */
    fun nativeGetHeight(handle: Long): Int {
        if (!isAvailable || handle == 0L) return 0
        return nativeGetHeightInternal(handle)
    }

    /**
     * Get the display number.
     */
    fun nativeGetDisplayNum(handle: Long): Int {
        if (!isAvailable || handle == 0L) return -1
        return nativeGetDisplayNumInternal(handle)
    }

    // ---- Native method declarations ----

    private external fun nativeStartServerInternal(displayNum: Int, width: Int, height: Int): Long
    private external fun nativeStopServerInternal(handle: Long)
    private external fun nativeIsRunningInternal(handle: Long): Boolean
    private external fun nativeGetFramebufferHandleInternal(handle: Long): Long
    private external fun nativeReadFramebufferInternal(handle: Long, outArray: ByteArray, offset: Int, length: Int): Boolean
    private external fun nativeTakeScreenshotInternal(handle: Long, path: String): Boolean
    private external fun nativeSendKeyEventInternal(handle: Long, keysym: Int, down: Boolean)
    private external fun nativeSendPointerEventInternal(handle: Long, x: Int, y: Int, buttonMask: Int)
    private external fun nativeResizeInternal(handle: Long, width: Int, height: Int)
    private external fun nativeGetClientCountInternal(handle: Long): Int
    private external fun nativeGetWidthInternal(handle: Long): Int
    private external fun nativeGetHeightInternal(handle: Long): Int
    private external fun nativeGetDisplayNumInternal(handle: Long): Int
}
