package com.termx.app.x11

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.termx.app.terminal.JniX11
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for the X11 display system — multi-display support.
 *
 * Manages multiple virtual display sessions, each identified by a
 * display number tag (:0, :1, :2, etc.). This is the core of TermX's
 * graphical capabilities, enabling headless GUI rendering for applications
 * like browsers (Cloudflare CAPTCHA solving).
 *
 * Display sessions appear as tabs in the main UI alongside terminal sessions,
 * with each display tagged by its number (e.g., "Display :0", "Display :1").
 *
 * Usage:
 *   // Start display :0 at auto-detected screen resolution
 *   X11Manager.startDisplay(context, 0)
 *
 *   // Start display :1 at custom resolution
 *   X11Manager.startDisplay(context, 1, 1920, 1080)
 *
 *   // Get env vars for terminal sessions
 *   X11Manager.getDisplayEnv()  // -> {DISPLAY=:0, ...}
 *
 *   // List all running displays
 *   X11Manager.listDisplays()  // -> [DisplayInfo(:0, 1024x768), DisplayInfo(:1, 1920x1080)]
 */
object X11Manager {

    private const val TAG = "X11Manager"
    private const val MAX_DISPLAYS = 8

    /** Active display servers, keyed by display number — thread-safe */
    private val displays = ConcurrentHashMap<Int, DisplayInfo>()

    /** Native X11 server handles, keyed by display number — thread-safe */
    private val nativeHandles = ConcurrentHashMap<Int, Long>()

    /** Kotlin-level display servers (fallback if native not available) — thread-safe */
    private val kotlinServers = ConcurrentHashMap<Int, X11DisplayServer>()

    /** Use native X11 server if available */
    private val useNativeX11: Boolean by lazy { JniX11.isAvailable }

    /** Callbacks for display session changes */
    var onDisplayListChanged: (() -> Unit)? = null

    /**
     * Calculate the optimal display resolution based on the device screen size.
     * Returns a resolution that fits within the screen while maintaining a standard aspect ratio.
     */
    fun calculateAutoResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Use 90% of screen size, capped at reasonable limits
        var width = (screenWidth * 0.9f).toInt().coerceIn(640, 2560)
        var height = (screenHeight * 0.9f).toInt().coerceIn(480, 1440)

        // Align to 8-pixel boundary (required by some X11 operations)
        width = (width / 8) * 8
        height = (height / 8) * 8

        return width to height
    }

    /**
     * Start a virtual display session.
     *
     * @param context    Android context
     * @param displayNum Display number (0 for :0, 1 for :1, etc.)
     * @param width      Display width in pixels (0 = auto-detect from screen)
     * @param height     Display height in pixels (0 = auto-detect from screen)
     * @param startVnc   Whether to start the VNC server
     * @param vncPort    VNC port (default 5900 + display number)
     * @param password   Optional VNC password
     * @return true if started successfully
     */
    fun startDisplay(
        context: Context,
        displayNum: Int = 0,
        width: Int = 0,
        height: Int = 0,
        startVnc: Boolean = true,
        vncPort: Int = 5900 + displayNum,
        password: String? = null
    ): Boolean {
        if (displayNum < 0 || displayNum >= MAX_DISPLAYS) {
            Log.w(TAG, "Invalid display number: $displayNum (max ${MAX_DISPLAYS - 1})")
            return false
        }

        // Auto-detect resolution if not specified
        val (resolvedWidth, resolvedHeight) = if (width <= 0 || height <= 0) {
            val (w, h) = calculateAutoResolution(context)
            Log.i(TAG, "Auto-resolving display :$displayNum to ${w}x${h}")
            w to h
        } else {
            width to height
        }

        if (displays.containsKey(displayNum)) {
            Log.w(TAG, "Display :$displayNum already running")
            return true
        }

        val startTime = System.currentTimeMillis()

        if (useNativeX11) {
            // Use native C X11 server
            val handle = JniX11.nativeStartServer(displayNum, resolvedWidth, resolvedHeight)
            if (handle == 0L) {
                Log.e(TAG, "Failed to start native X11 server for display :$displayNum")
                return false
            }

            nativeHandles[displayNum] = handle
            displays[displayNum] = DisplayInfo(
                displayNum = displayNum,
                width = resolvedWidth,
                height = resolvedHeight,
                vncPort = if (startVnc) vncPort else -1,
                startTime = startTime,
                nativeHandle = handle
            )

            Log.i(TAG, "Native X11 display :$displayNum started (${resolvedWidth}x${resolvedHeight})")

        } else {
            // Fallback to Kotlin X11 server
            try {
                val server = X11DisplayServer.getInstance(context)
                val success = server.start(
                    displayNum = displayNum,
                    w = resolvedWidth,
                    h = resolvedHeight,
                    startVnc = startVnc,
                    vncPort = vncPort,
                    vncPassword = password
                )

                if (!success) {
                    Log.e(TAG, "Failed to start Kotlin X11 server for display :$displayNum")
                    return false
                }

                kotlinServers[displayNum] = server
                displays[displayNum] = DisplayInfo(
                    displayNum = displayNum,
                    width = resolvedWidth,
                    height = resolvedHeight,
                    vncPort = if (startVnc) vncPort else -1,
                    startTime = startTime,
                    kotlinServer = server
                )

                Log.i(TAG, "Kotlin X11 display :$displayNum started (${resolvedWidth}x${resolvedHeight})")
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting Kotlin X11 display :$displayNum", e)
                return false
            }
        }

        onDisplayListChanged?.invoke()
        return true
    }

    /**
     * Stop a specific display session. Thread-safe — handles concurrent calls.
     */
    fun stopDisplay(displayNum: Int = 0) {
        val info = displays.remove(displayNum) ?: return

        // Atomically remove and stop native handle
        val handle = nativeHandles.remove(displayNum)
        if (handle != null && handle != 0L) {
            try {
                JniX11.nativeStopServer(handle)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping native X11 server for :$displayNum", e)
            }
        }

        // Atomically remove and stop Kotlin server
        val server = kotlinServers.remove(displayNum)
        if (server != null) {
            try {
                server.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping Kotlin X11 server for :$displayNum", e)
            }
        }

        Log.i(TAG, "Display :$displayNum stopped")
        onDisplayListChanged?.invoke()
    }

    /**
     * Stop all display sessions.
     */
    fun stopAllDisplays() {
        val nums = displays.keys.toList()
        for (num in nums) {
            stopDisplay(num)
        }
    }

    /**
     * Check if a specific display is running.
     */
    fun isDisplayRunning(displayNum: Int = 0): Boolean {
        return displays.containsKey(displayNum)
    }

    /**
     * Check if any display is running.
     */
    fun isAnyDisplayRunning(): Boolean = displays.isNotEmpty()

    /**
     * Get info about a specific display.
     */
    fun getDisplayInfo(displayNum: Int = 0): DisplayInfo? = displays[displayNum]

    /**
     * Get the first running display number (for default DISPLAY env).
     */
    fun getFirstDisplayNum(): Int? = displays.keys.minOrNull()

    /**
     * List all running displays.
     */
    fun listDisplays(): List<DisplayInfo> = displays.values.toList().sortedBy { it.displayNum }

    /**
     * Get the environment variables for the display session.
     * These should be injected into terminal session environments.
     * Uses the first available display if no specific one is requested.
     */
    fun getDisplayEnv(context: Context, displayNum: Int? = null): Map<String, String> {
        val num = displayNum ?: getFirstDisplayNum() ?: return emptyMap()
        val info = displays[num] ?: return emptyMap()

        return buildMap {
            put("DISPLAY", ":$num")
            put("XDG_RUNTIME_DIR", "${context.filesDir.absolutePath}/run")
            put("XAUTHORITY", "${context.filesDir.absolutePath}/.Xauthority")
            put("XDG_SESSION_TYPE", "x11")
            put("WAYLAND_DISPLAY", "")

            // For Chromium/Electron apps
            put("CHROME_DEVEDETACH", "1")

            // X11 socket path hints
            put("X11_SOCKET_DIR", "${context.filesDir.absolutePath}/tmp/.X11-unix")
        }
    }

    /**
     * Get the native X11 server handle for a display.
     */
    fun getNativeHandle(displayNum: Int = 0): Long? = nativeHandles[displayNum]

    /**
     * Get the Kotlin display server for a display.
     */
    fun getKotlinServer(displayNum: Int = 0): X11DisplayServer? = kotlinServers[displayNum]

    /**
     * Read framebuffer data from a native X11 display.
     * Returns RGBA byte array or null if not available.
     */
    fun readFramebuffer(displayNum: Int = 0): ByteArray? {
        val handle = nativeHandles[displayNum] ?: return null
        val info = displays[displayNum] ?: return null
        val size = info.width * info.height * 4
        if (size <= 0) return null
        val buf = ByteArray(size)
        if (JniX11.nativeReadFramebuffer(handle, buf, 0, size)) {
            return buf
        }
        return null
    }

    /**
     * Get a framebuffer bitmap from a native display.
     */
    fun getFramebufferBitmap(displayNum: Int = 0): Bitmap? {
        val info = displays[displayNum] ?: return null
        val data = readFramebuffer(displayNum) ?: return null

        if (info.width <= 0 || info.height <= 0) return null

        return try {
            val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(info.width * info.height)

            for (i in 0 until pixels.size) {
                val base = i * 4
                val r = data[base].toInt() and 0xFF
                val g = data[base + 1].toInt() and 0xFF
                val b = data[base + 2].toInt() and 0xFF
                val a = data[base + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            bitmap.setPixels(pixels, 0, info.width, 0, 0, info.width, info.height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create framebuffer bitmap for :$displayNum", e)
            null
        }
    }

    /**
     * Resize a display.
     */
    fun resizeDisplay(displayNum: Int = 0, width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid resize dimensions: ${width}x${height}")
            return
        }

        val handle = nativeHandles[displayNum]
        if (handle != null && handle != 0L) {
            try {
                JniX11.nativeResize(handle, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resize native display :$displayNum", e)
            }
        }

        kotlinServers[displayNum]?.resize(width, height)

        displays[displayNum]?.let { info ->
            displays[displayNum] = info.copy(width = width, height = height)
        }

        onDisplayListChanged?.invoke()
        Log.i(TAG, "Display :$displayNum resized to ${width}x${height}")
    }

    /**
     * Take a screenshot of a display.
     */
    fun takeScreenshot(displayNum: Int = 0, path: String): Boolean {
        val handle = nativeHandles[displayNum]
        if (handle != null && handle != 0L) {
            val bitmap = getFramebufferBitmap(displayNum) ?: return false
            try {
                FileOutputStream(path).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                return false
            }
        }

        return kotlinServers[displayNum]?.takeScreenshot(path) ?: false
    }

    /**
     * Send a key event to a display.
     */
    fun sendKeyEvent(displayNum: Int = 0, keysym: Int, down: Boolean) {
        val handle = nativeHandles[displayNum]
        if (handle != null && handle != 0L) {
            JniX11.nativeSendKeyEvent(handle, keysym, down)
            return
        }
        // Kotlin server doesn't have direct key injection yet
        Log.d(TAG, "Key event on display :$displayNum: keysym=$keysym down=$down")
    }

    /**
     * Send a pointer event to a display.
     */
    fun sendPointerEvent(displayNum: Int = 0, x: Int, y: Int, buttonMask: Int) {
        val handle = nativeHandles[displayNum]
        if (handle != null && handle != 0L) {
            JniX11.nativeSendPointerEvent(handle, x, y, buttonMask)
            return
        }
        kotlinServers[displayNum]?.framebufferRef?.moveCursor(x, y)
    }

    /**
     * Get the number of connected X11 clients for a display.
     */
    fun getClientCount(displayNum: Int = 0): Int {
        val handle = nativeHandles[displayNum]
        if (handle != null && handle != 0L) {
            return JniX11.nativeGetClientCount(handle)
        }
        return kotlinServers[displayNum]?.let { server ->
            // Count X11 clients from the Kotlin server
            0  // Simplified
        } ?: 0
    }

    /**
     * Get display status as a string.
     */
    fun getStatus(): String {
        if (displays.isEmpty()) return "Display: not running"

        return buildString {
            for ((num, info) in displays.toSortedMap()) {
                val clientCount = getClientCount(num)
                val vnc = if (info.vncPort > 0) "VNC port ${info.vncPort}" else "VNC disabled"
                val uptime = (System.currentTimeMillis() - info.startTime) / 1000
                appendLine("Display :$num")
                appendLine("  Resolution: ${info.width}x${info.height}")
                appendLine("  X11 Clients: $clientCount")
                appendLine("  $vnc")
                appendLine("  Uptime: ${uptime}s")
                appendLine("  Engine: ${if (info.nativeHandle != 0L) "Native" else "Kotlin"}")
            }
        }
    }

    /**
     * Allocate the next available display number.
     */
    fun allocateDisplayNum(): Int {
        for (i in 0 until MAX_DISPLAYS) {
            if (!displays.containsKey(i)) return i
        }
        return -1  // All slots used
    }

    // ---- Shell Command Handling ----

    /**
     * Handle the `termx-display` shell command.
     */
    fun handleDisplayCommand(context: Context, args: List<String>): String {
        if (args.isEmpty()) return "Usage: termx-display <start|stop|status|list|resize|screenshot> [display_num] [options]"

        return when (args[0]) {
            "start" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val resolution = args.getOrElse(2) { "auto" }
                val w: Int
                val h: Int
                if (resolution.equals("auto", ignoreCase = true)) {
                    val (autoW, autoH) = calculateAutoResolution(context)
                    w = autoW
                    h = autoH
                } else if (resolution.contains("x")) {
                    val parts = resolution.split("x")
                    w = parts.getOrElse(0) { "1024" }.toIntOrNull() ?: 1024
                    h = parts.getOrElse(1) { "768" }.toIntOrNull() ?: 768
                } else {
                    w = 1024
                    h = 768
                }
                val vncPort = args.getOrElse(3) { (5900 + displayNum).toString() }.toIntOrNull() ?: (5900 + displayNum)

                if (startDisplay(context, displayNum, w, h, vncPort = vncPort)) {
                    "Display :$displayNum started: ${w}x${h}\n" +
                    "DISPLAY=:$displayNum\n" +
                    "X11 port: ${6000 + displayNum}\n" +
                    "VNC port: $vncPort\n" +
                    "Connect: adb forward tcp:$vncPort tcp:$vncPort"
                } else {
                    "Failed to start display :$displayNum"
                }
            }
            "stop" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                stopDisplay(displayNum)
                "Display :$displayNum stopped"
            }
            "list" -> {
                val displayList = listDisplays()
                if (displayList.isEmpty()) {
                    "No displays running"
                } else {
                    displayList.joinToString("\n") { info ->
                        ":${info.displayNum}  ${info.width}x${info.height}  clients=${getClientCount(info.displayNum)}  ${if (info.vncPort > 0) "vnc=${info.vncPort}" else ""}"
                    }
                }
            }
            "status" -> getStatus()
            "resize" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val w = args.getOrElse(2) { "1024" }.toIntOrNull() ?: 1024
                val h = args.getOrElse(3) { "768" }.toIntOrNull() ?: 768
                resizeDisplay(displayNum, w, h)
                "Display :$displayNum resized to ${w}x${h}"
            }
            "screenshot" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val path = args.getOrElse(2) {
                    "/data/data/com.termx.app/files/home/screenshot_${displayNum}_${System.currentTimeMillis()}.png"
                }
                if (takeScreenshot(displayNum, path)) "Screenshot saved: $path"
                else "Failed to take screenshot"
            }
            else -> "Unknown command: ${args[0]}\nUsage: termx-display <start|stop|status|list|resize|screenshot> [display_num] [options]"
        }
    }

    /**
     * Handle the `termx-vnc` shell command.
     */
    fun handleVncCommand(context: Context, args: List<String>): String {
        if (args.isEmpty()) return "Usage: termx-vnc <start|stop|status|clients> [display_num]"

        return when (args[0]) {
            "start" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val port = args.getOrElse(2) { (5900 + displayNum).toString() }.toIntOrNull() ?: (5900 + displayNum)
                val server = kotlinServers[displayNum]
                if (server != null && server.running) {
                    val fbRef = server.framebufferRef
                    if (fbRef != null) {
                        val vnc = VncServer(fbRef, port)
                        if (vnc.start()) {
                            "VNC server started on port $port for display :$displayNum"
                        } else {
                            "Failed to start VNC server"
                        }
                    } else {
                        "Framebuffer not available for display :$displayNum"
                    }
                } else {
                    "Display :$displayNum not running. Start it first: termx-display start $displayNum"
                }
            }
            "stop" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                kotlinServers[displayNum]?.vncServerRef?.stop() ?: run {
                    "No VNC server for display :$displayNum"
                }
                "VNC server stopped for display :$displayNum"
            }
            "status" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val vnc = kotlinServers[displayNum]?.vncServerRef
                if (vnc?.running == true) {
                    "VNC running on port, ${vnc.connectedClients} client(s)"
                } else {
                    "VNC not running for display :$displayNum"
                }
            }
            "clients" -> {
                val displayNum = args.getOrElse(1) { "0" }.toIntOrNull() ?: 0
                val vnc = kotlinServers[displayNum]?.vncServerRef
                "VNC clients: ${vnc?.connectedClients ?: 0}"
            }
            else -> "Unknown command: ${args[0]}"
        }
    }
}

/**
 * Information about a running display session.
 */
data class DisplayInfo(
    val displayNum: Int,
    val width: Int,
    val height: Int,
    val vncPort: Int = -1,
    val startTime: Long = System.currentTimeMillis(),
    val nativeHandle: Long = 0L,
    val kotlinServer: X11DisplayServer? = null
) {
    /** Display tag like ":0", ":1" etc. */
    val tag: String get() = ":$displayNum"

    /** Human-readable title for session tabs */
    val title: String get() = "Display $tag"

    /** X11 port */
    val x11Port: Int get() = 6000 + displayNum
}
