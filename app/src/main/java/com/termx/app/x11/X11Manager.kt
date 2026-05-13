package com.termx.app.x11

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.termx.app.terminal.JniX11
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

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
 *   // Start display :0 at 1024x768
 *   X11Manager.startDisplay(context, 0, 1024, 768)
 *
 *   // Start display :1 at 1920x1080
 *   X11Manager.startDisplay(context, 1, 1920, 1080)
 *
 *   // Get env vars for terminal sessions
 *   X11Manager.getDisplayEnv()  // -> {DISPLAY=:0, ...}
 *
 *   // List all running displays
 *   X11Manager.listDisplays()  // -> [DisplayInfo(:0, 1024x768), DisplayInfo(:1, 1920x1080)]
 *
 * Shell usage:
 *   termx-display start           # Start display :0
 *   termx-display start 1        # Start display :1
 *   termx-display start 2 1920x1080  # Display :2 at 1920x1080
 *   termx-display stop            # Stop display :0
 *   termx-display stop 1          # Stop display :1
 *   termx-display list            # List all displays
 *   termx-display status          # Status of :0
 *   termx-display screenshot      # Screenshot of :0
 */
object X11Manager {

    private const val TAG = "X11Manager"
    private const val MAX_DISPLAYS = 8

    /** Active display servers, keyed by display number */
    private val displays = mutableMapOf<Int, DisplayInfo>()

    /** Native X11 server handles, keyed by display number */
    private val nativeHandles = mutableMapOf<Int, Long>()

    /** Kotlin-level display servers (fallback if native not available) */
    private val kotlinServers = mutableMapOf<Int, X11DisplayServer>()

    /** Use native X11 server if available */
    private val useNativeX11: Boolean by lazy { JniX11.isAvailable }

    /** Callbacks for display session changes */
    var onDisplayListChanged: (() -> Unit)? = null

    /**
     * Start a virtual display session.
     *
     * @param context    Android context
     * @param displayNum Display number (0 for :0, 1 for :1, etc.)
     * @param width      Display width in pixels
     * @param height     Display height in pixels
     * @param startVnc   Whether to start the VNC server
     * @param vncPort    VNC port (default 5900 + display number)
     * @param password   Optional VNC password
     * @return true if started successfully
     */
    fun startDisplay(
        context: Context,
        displayNum: Int = 0,
        width: Int = 1024,
        height: Int = 768,
        startVnc: Boolean = true,
        vncPort: Int = 5900 + displayNum,
        password: String? = null
    ): Boolean {
        if (displayNum < 0 || displayNum >= MAX_DISPLAYS) {
            Log.w(TAG, "Invalid display number: $displayNum (max ${MAX_DISPLAYS - 1})")
            return false
        }

        if (displays.containsKey(displayNum)) {
            Log.w(TAG, "Display :$displayNum already running")
            return true
        }

        val startTime = System.currentTimeMillis()

        if (useNativeX11) {
            // Use native C X11 server
            val handle = JniX11.nativeStartServer(displayNum, width, height)
            if (handle == 0L) {
                Log.e(TAG, "Failed to start native X11 server for display :$displayNum")
                return false
            }

            nativeHandles[displayNum] = handle
            displays[displayNum] = DisplayInfo(
                displayNum = displayNum,
                width = width,
                height = height,
                vncPort = if (startVnc) vncPort else -1,
                startTime = startTime,
                nativeHandle = handle
            )

            Log.i(TAG, "Native X11 display :$displayNum started (${width}x${height})")

        } else {
            // Fallback to Kotlin X11 server
            val server = X11DisplayServer.getInstance(context)
            val success = server.start(
                displayNum = displayNum,
                w = width,
                h = height,
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
                width = width,
                height = height,
                vncPort = if (startVnc) vncPort else -1,
                startTime = startTime,
                kotlinServer = server
            )

            Log.i(TAG, "Kotlin X11 display :$displayNum started (${width}x${height})")
        }

        onDisplayListChanged?.invoke()
        return true
    }

    /**
     * Stop a specific display session.
     */
    fun stopDisplay(displayNum: Int = 0) {
        val info = displays.remove(displayNum) ?: return

        nativeHandles[displayNum]?.let { handle ->
            JniX11.nativeStopServer(handle)
            nativeHandles.remove(displayNum)
        }

        kotlinServers[displayNum]?.let { server ->
            server.stop()
            kotlinServers.remove(displayNum)
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
        val data = readFramebuffer(displayNum) ?: return null
        val info = displays[displayNum] ?: return null

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
        return bitmap
    }

    /**
     * Resize a display.
     */
    fun resizeDisplay(displayNum: Int = 0, width: Int, height: Int) {
        val handle = nativeHandles[displayNum]
        if (handle != null) {
            JniX11.nativeResize(handle, width, height)
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
        if (handle != null) {
            // Native server can save PPM directly, but we prefer PNG
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
        if (handle != null) {
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
        if (handle != null) {
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
        if (handle != null) {
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
                val resolution = args.getOrElse(2) { "1024x768" }
                val (w, h) = if (resolution.contains("x")) {
                    resolution.split("x").let { it[0].toIntOrNull() ?: 1024 to it[1].toIntOrNull() ?: 768 }
                } else {
                    1024 to 768
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
                    val vnc = VncServer(server.framebufferRef!!, port)
                    if (vnc.start()) {
                        "VNC server started on port $port for display :$displayNum"
                    } else {
                        "Failed to start VNC server"
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
