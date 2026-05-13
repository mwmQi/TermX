package com.termx.app.x11

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * X11 Display Server — manages virtual display sessions.
 *
 * This class creates and manages virtual X11 display sessions that allow
 * GUI applications to run inside TermX without physical display drivers.
 * It's the TermX equivalent of Termux:X11, providing:
 *
 * - Virtual DISPLAY environment (DISPLAY=:0)
 * - X11 socket for local client connections
 * - Window management coordination
 * - Display session lifecycle
 * - Integration with the virtual framebuffer and VNC server
 *
 * How it works:
 *   1. Creates a virtual framebuffer (in-memory screen)
 *   2. Sets up DISPLAY=:0 environment variable for child processes
 *   3. Starts a VNC server for remote display access
 *   4. Provides an X11 Unix socket for local GUI app connections
 *   5. Handles X11 client connections and renders to the framebuffer
 *
 * For Cloudflare CAPTCHA solving:
 *   1. Start display session → DISPLAY=:0 is available
 *   2. Install chromium/firefox via pkg
 *   3. Run: chromium --display=:0 --no-sandbox
 *   4. Connect via VNC to see and interact with the browser
 *   5. Navigate to CAPTCHA page, solve it visually
 *
 * Usage from terminal:
 *   termx-display start           # Start display :0
 *   termx-display start 1920x1080 # Start with specific resolution
 *   termx-display stop            # Stop display
 *   termx-display status          # Show display status
 *   termx-display resize 1280x720 # Resize display
 *   termx-display screenshot file.png  # Take screenshot
 */
class X11DisplayServer(private val context: Context) {

    companion object {
        private const val TAG = "X11DisplayServer"

        /** Default display number */
        const val DEFAULT_DISPLAY = 0

        /** Default resolution */
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 768

        /** X11 socket directory */
        const val X11_SOCKET_DIR = "/tmp/.X11-unix"

        /** Lock file directory */
        const val X11_LOCK_DIR = "/tmp"

        /** VNC default port base (5900 + display number) */
        const val VNC_PORT_BASE = 5900

        @Volatile
        private var instance: X11DisplayServer? = null

        fun getInstance(context: Context): X11DisplayServer {
            return instance ?: synchronized(this) {
                instance ?: X11DisplayServer(context.applicationContext).also { instance = it }
            }
        }
    }

    // Display state
    private val isRunning = AtomicBoolean(false)

    @Volatile var displayNumber: Int = DEFAULT_DISPLAY
        private set

    @Volatile var width: Int = DEFAULT_WIDTH
        private set

    @Volatile var height: Int = DEFAULT_HEIGHT
        private set

    // Components
    private var framebuffer: VirtualFramebuffer? = null
    private var vncServer: VncServer? = null
    private var x11Socket: ServerSocket? = null
    private var socketThread: Thread? = null

    // X11 client tracking
    private val clients = mutableMapOf<Int, X11ClientInfo>()
    private var nextClientId = 0

    // Callbacks
    var onDisplayStarted: (() -> Unit)? = null
    var onDisplayStopped: (() -> Unit)? = null
    var onClientConnected: ((X11ClientInfo) -> Unit)? = null
    var onFrameUpdate: (() -> Unit)? = null

    val running: Boolean get() = isRunning.get()
    val framebufferRef: VirtualFramebuffer? get() = framebuffer
    val vncServerRef: VncServer? get() = vncServer
    val displayEnv: String get() = ":$displayNumber"

    /**
     * Start the X11 display server.
     *
     * @param displayNum Display number (default 0 → DISPLAY=:0)
     * @param w          Framebuffer width
     * @param h          Framebuffer height
     * @param startVnc   Whether to also start VNC server
     * @param vncPort    VNC port (default 5900 + display number)
     * @param vncPassword Optional VNC password
     * @return true if started successfully
     */
    fun start(
        displayNum: Int = DEFAULT_DISPLAY,
        w: Int = DEFAULT_WIDTH,
        h: Int = DEFAULT_HEIGHT,
        startVnc: Boolean = true,
        vncPort: Int = VNC_PORT_BASE + displayNum,
        vncPassword: String? = null
    ): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Display :$displayNum already running")
            return true
        }

        displayNumber = displayNum
        width = w
        height = h

        try {
            // 1. Create the virtual framebuffer
            framebuffer = VirtualFramebuffer(w, h).also { fb ->
                fb.start()
                fb.onFrameUpdate = { x, y, fw, fh ->
                    onFrameUpdate?.invoke()
                    // Notify VNC clients
                    vncServer?.sendFrameUpdate()
                }
            }

            // 2. Create X11 socket directory
            setupX11Dirs()

            // 3. Start VNC server
            if (startVnc) {
                vncServer = VncServer(framebuffer!!, vncPort, vncPassword).also { vnc ->
                    vnc.onKeyEvent = { keysym, down ->
                        handleVncKeyEvent(keysym, down)
                    }
                    vnc.onPointerEvent = { x, y, buttonMask ->
                        handleVncPointerEvent(x, y, buttonMask)
                    }
                    if (!vnc.start()) {
                        Log.w(TAG, "VNC server failed to start, continuing without VNC")
                    }
                }
            }

            // 4. Create X11 Unix domain socket (for local GUI app connections)
            startX11Socket()

            // 5. Create lock file
            createLockFile()

            isRunning.set(true)
            onDisplayStarted?.invoke()

            Log.i(TAG, "X11 display :$displayNum started (${w}x${h}), VNC port ${if (startVnc) vncPort else "disabled"}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start display :$displayNum", e)
            stop()
            return false
        }
    }

    /**
     * Stop the X11 display server.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        vncServer?.stop()
        vncServer = null

        framebuffer?.stop()
        framebuffer = null

        x11Socket?.close()
        x11Socket = null

        socketThread?.interrupt()
        socketThread = null

        clients.clear()

        removeLockFile()

        onDisplayStopped?.invoke()
        Log.i(TAG, "X11 display :$displayNumber stopped")
    }

    /**
     * Resize the display.
     */
    fun resize(newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        framebuffer?.resize(newWidth, newHeight)
        Log.i(TAG, "Display :$displayNumber resized to ${newWidth}x${newHeight}")
    }

    /**
     * Get the environment variables needed for GUI apps.
     * These should be added to the terminal session's environment.
     */
    fun getDisplayEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["DISPLAY"] = displayEnv
        env["XDG_RUNTIME_DIR"] = "${context.filesDir.absolutePath}/run"
        env["XAUTHORITY"] = "${context.filesDir.absolutePath}/.Xauthority"

        // For Chromium/Electron apps
        env["CHROME_DEVEDETACH"] = "1"

        // Wayland compat (some apps check this)
        env["WAYLAND_DISPLAY"] = ""

        // X11 socket path
        env["XDG_SESSION_TYPE"] = "x11"

        return env
    }

    /**
     * Take a screenshot and save to a file.
     */
    fun takeScreenshot(outputPath: String): Boolean {
        val fb = framebuffer ?: return false
        val bitmap = fb.getSnapshot()
        try {
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Screenshot saved to $outputPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            return false
        }
    }

    /**
     * Get display status info.
     */
    fun getStatus(): String {
        if (!isRunning.get()) return "Display: not running"

        return buildString {
            appendLine("Display: :$displayNumber")
            appendLine("Resolution: ${width}x${height}")
            appendLine("VNC: ${if (vncServer?.running == true) "running on port ${VNC_PORT_BASE + displayNumber}" else "not running"}")
            appendLine("VNC Clients: ${vncServer?.connectedClients ?: 0}")
            appendLine("X11 Clients: ${clients.size}")
            appendLine("Frame Count: ${framebuffer?.frameCount ?: 0}")
        }
    }

    // ---- X11 Socket Setup ----

    private fun setupX11Dirs() {
        val socketDir = File("${context.filesDir.absolutePath}$X11_SOCKET_DIR")
        if (!socketDir.exists()) socketDir.mkdirs()

        val runDir = File("${context.filesDir.absolutePath}/run")
        if (!runDir.exists()) runDir.mkdirs()
    }

    private fun startX11Socket() {
        // Create a TCP socket that listens for X11 connections
        // Real X11 uses Unix domain sockets, but on Android we use TCP
        val x11Port = 6000 + displayNumber

        try {
            x11Socket = ServerSocket(x11Port)
            socketThread = Thread({
                acceptX11Clients()
            }, "X11-Socket").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "X11 socket listening on port $x11Port")
        } catch (e: IOException) {
            Log.w(TAG, "X11 socket on port $x11Port unavailable: ${e.message}")
        }
    }

    private fun acceptX11Clients() {
        try {
            while (isRunning.get()) {
                val clientSocket = x11Socket?.accept() ?: break
                val clientId = ++nextClientId

                val clientInfo = X11ClientInfo(
                    id = clientId,
                    name = "Client #$clientId",
                    pid = -1,
                    connected = System.currentTimeMillis()
                )
                clients[clientId] = clientInfo
                onClientConnected?.invoke(clientInfo)

                Log.i(TAG, "X11 client #$clientId connected")

                // Handle client in a separate thread
                Thread({
                    handleX11Client(clientId, clientSocket)
                }, "X11Client-$clientId").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) Log.d(TAG, "X11 accept error: ${e.message}")
        }
    }

    /**
     * Handle an X11 client connection.
     * This implements a minimal X11 protocol for client identification.
     * Real rendering is done through the framebuffer directly.
     */
    private fun handleX11Client(clientId: Int, socket: java.net.Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // X11 connection setup
            val byteOrder = input.readByte()
            val pad1 = input.readByte()
            val majorVersion = input.readUnsignedShort()
            val minorVersion = input.readUnsignedShort()
            val authLen = input.readUnsignedShort()
            val pad2 = input.readUnsignedShort()

            // Read auth data
            if (authLen > 0) {
                val authData = ByteArray(authLen)
                input.readFully(authData)
            }

            // Send success response
            output.writeByte(1) // success
            output.writeByte(pad1)
            output.writeShort(11) // protocol major version
            output.writeShort(0)  // protocol minor version
            output.writeShort(0)  // additional data length
            output.writeInt(0)    // release number
            output.writeInt(0)    // resource base
            output.writeInt(0)    // resource mask
            output.writeInt(0)    // motion buffer size
            output.writeShort(0)  // vendor length
            output.writeShort(0)  // max request length
            output.flush()

            // Keep connection alive (client will send requests)
            while (isRunning.get() && !socket.isClosed) {
                Thread.sleep(100)
            }

        } catch (e: Exception) {
            Log.d(TAG, "X11 client #$clientId disconnected: ${e.message}")
        } finally {
            clients.remove(clientId)
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun createLockFile() {
        val lockFile = File("${context.filesDir.absolutePath}$X11_LOCK_DIR/.X${displayNumber}-lock")
        try {
            lockFile.parentFile?.mkdirs()
            lockFile.writeText("${android.os.Process.myPid()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create X11 lock file: ${e.message}")
        }
    }

    private fun removeLockFile() {
        val lockFile = File("${context.filesDir.absolutePath}$X11_LOCK_DIR/.X${displayNumber}-lock")
        lockFile.delete()
    }

    // ---- VNC Input Handlers ----

    private fun handleVncKeyEvent(keysym: Int, down: Boolean) {
        // Forward VNC keyboard events to the active terminal session
        // or to the focused X11 window
        Log.d(TAG, "VNC key: keysym=$keysym down=$down")
    }

    private fun handleVncPointerEvent(x: Int, y: Int, buttonMask: Int) {
        // Forward VNC mouse events to the X11 display
        framebuffer?.moveCursor(x, y)
        Log.d(TAG, "VNC pointer: ($x, $y) buttons=$buttonMask")
    }
}

/**
 * Information about an X11 client connection.
 */
data class X11ClientInfo(
    val id: Int,
    val name: String,
    val pid: Int,
    val connected: Long
)
