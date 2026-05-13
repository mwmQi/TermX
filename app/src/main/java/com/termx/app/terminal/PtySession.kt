package com.termx.app.terminal

import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTY-based terminal session using native forkpty() for true terminal emulation.
 *
 * Unlike the legacy Runtime.exec()-based TerminalSession, PtySession creates a real
 * Unix pseudo-terminal (PTY) with proper TTY semantics. This enables:
 *
 * - **Job control**: Ctrl+Z suspends, fg/bg resume, multiple background processes
 * - **Signal handling**: SIGINT (Ctrl+C), SIGTSTP (Ctrl+Z), SIGQUIT (Ctrl+\) all work
 * - **Terminal resize**: TIOCSWINSZ ioctl generates SIGWINCH, programs like vim/top adapt
 * - **Line discipline**: Proper TTY input processing (canonical mode, echo, etc.)
 * - **Process groups**: Signal delivery to the foreground process group, not just one PID
 *
 * If the native PTY library is unavailable (e.g., on very old Android versions or
 * restricted environments), it falls back to Runtime.exec() with a compatibility shim.
 */
class PtySession(
    private val shellPath: String = "/system/bin/sh",
    private val cwd: String = "/data/data/com.termx.app/files",
    private val env: Map<String, String> = defaultEnv()
) {
    companion object {
        private const val TAG = "PtySession"
        private const val READ_BUFFER_SIZE = 32768
        private const val READ_POLL_INTERVAL_MS = 50L

        /** Unix signal constants */
        const val SIGHUP = 1
        const val SIGINT = 2
        const val SIGQUIT = 3
        const val SIGKILL = 9
        const val SIGTERM = 15
        const val SIGTSTP = 20
        const val SIGCONT = 18
        const val SIGWINCH = 28

        fun defaultEnv(): Map<String, String> {
            val env = mutableMapOf<String, String>()
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            env["LANG"] = "en_US.UTF-8"
            env["PATH"] = buildPath()
            env["HOME"] = "/data/data/com.termx.app/files"
            env["SHELL"] = "/system/bin/sh"
            env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
            env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
            env["HOSTNAME"] = "android"
            env["TMPDIR"] = "/data/data/com.termx.app/files/usr/tmp"
            env["PREFIX"] = "/data/data/com.termx.app/files/usr"
            env["LD_LIBRARY_PATH"] = "/data/data/com.termx.app/files/usr/lib"

            // X11 display env — inject DISPLAY for running display sessions
            // This allows GUI apps (chromium, firefox, etc.) to render to the virtual display
            try {
                val x11Manager = Class.forName("com.termx.app.x11.X11Manager")
                val isAnyRunning = x11Manager.getMethod("isAnyDisplayRunning").invoke(null) as? Boolean
                if (isAnyRunning == true) {
                    // Get the first running display number
                    val getFirstNum = x11Manager.getMethod("getFirstDisplayNum")
                    val firstNum = getFirstNum.invoke(null) as? Int ?: 0
                    env["DISPLAY"] = ":$firstNum"
                    env["XDG_RUNTIME_DIR"] = "/data/data/com.termx.app/files/run"
                    env["XAUTHORITY"] = "/data/data/com.termx.app/files/.Xauthority"
                    env["XDG_SESSION_TYPE"] = "x11"
                    env["WAYLAND_DISPLAY"] = ""

                    // Add all running display numbers as a reference
                    val listDisplays = x11Manager.getMethod("listDisplays")
                    @Suppress("UNCHECKED_CAST")
                    val displays = listDisplays.invoke(null) as? List<Any>
                    if (displays != null && displays.size > 1) {
                        // If multiple displays are running, list them
                        val displayNums = displays.mapNotNull { d ->
                            val field = d.javaClass.getDeclaredField("displayNum")
                            field.isAccessible = true
                            field.getInt(d)
                        }
                        env["TERMX_DISPLAYS"] = displayNums.joinToString(",") { ":$it" }
                    }
                }
            } catch (_: Exception) {
                // X11 not available or not running — skip
            }
            // Copy existing system env
            System.getenv()?.forEach { (k, v) ->
                if (k !in env) env[k] = v
            }
            return env
        }

        /**
         * Build the PATH with TermX's bin directory first, then system paths.
         * This ensures shell wrapper commands (termx-*) are found before system ones.
         */
        private fun buildPath(): String {
            val termxBin = "/data/data/com.termx.app/files/bin"
            val termxUsrBin = "/data/data/com.termx.app/files/usr/bin"
            val systemPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
            return "$termxBin:$termxUsrBin:$systemPath"
        }
    }

    // Native PTY handle (0 = not initialized)
    private var nativeHandle: Long = 0L

    // Fallback mode: Runtime.exec() when native PTY is unavailable
    private var fallbackProcess: Process? = null
    private var fallbackOutput: OutputStream? = null
    private var fallbackInput: InputStream? = null
    private var fallbackError: InputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val useNativePty: Boolean

    // Reading threads
    private var readerThread: Thread? = null
    private var errorReaderThread: Thread? = null
    private var exitWatchThread: Thread? = null

    // Terminal dimensions
    @Volatile var columns: Int = 80
    @Volatile var rows: Int = 24

    // Callbacks
    var onOutput: ((ByteArray) -> Unit)? = null
    var onError: ((ByteArray) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null

    val running: Boolean get() = isRunning.get()

    init {
        useNativePty = JniPty.isAvailable
        Log.i(TAG, "PtySession initialized, native PTY: $useNativePty")
    }

    /**
     * Start the terminal session.
     * Creates a native PTY or falls back to Runtime.exec().
     */
    fun start() {
        if (isRunning.get()) return

        try {
            if (useNativePty) {
                startNativePty()
            } else {
                startFallback()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            isRunning.set(false)
            onOutput?.invoke(
                "Error: Failed to start terminal: ${e.message}\r\n".toByteArray()
            )
            onExit?.invoke(-1)
        }
    }

    /**
     * Start a native PTY session using forkpty().
     */
    private fun startNativePty() {
        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()

        nativeHandle = JniPty.nativeCreatePty(
            shellPath, cwd, envArray, rows, columns
        )

        if (nativeHandle == 0L) {
            throw RuntimeException("nativeCreatePty returned null handle")
        }

        isRunning.set(true)

        // Start reader thread — polls the PTY master fd for output
        readerThread = Thread({
            readNativePtyOutput()
        }, "PtySession-Reader").apply {
            isDaemon = true
            start()
        }

        // Start exit watchdog
        exitWatchThread = Thread({
            monitorNativeExit()
        }, "PtySession-ExitWatch").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "Native PTY session started (pid=${JniPty.nativeGetChildPid(nativeHandle)})")
    }

    /**
     * Continuously read output from the native PTY master fd.
     * Uses a polling loop with a small sleep to avoid busy-waiting.
     */
    private fun readNativePtyOutput() {
        try {
            while (isRunning.get() && nativeHandle != 0L) {
                val data = JniPty.nativeRead(nativeHandle, READ_BUFFER_SIZE)
                if (data != null && data.isNotEmpty()) {
                    onOutput?.invoke(data)
                } else {
                    // No data available — sleep briefly before retrying
                    Thread.sleep(READ_POLL_INTERVAL_MS)
                }

                // Check if child is still alive
                if (!JniPty.nativeIsChildAlive(nativeHandle)) {
                    val exitCode = JniPty.nativeWaitForExit(nativeHandle)
                    if (exitCode >= 0) {
                        isRunning.set(false)
                        onExit?.invoke(exitCode)
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Normal — thread interrupted on close
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "PTY read error", e)
            }
        }
    }

    /**
     * Monitor the native child process for exit.
     */
    private fun monitorNativeExit() {
        try {
            while (isRunning.get() && nativeHandle != 0L) {
                val exitCode = JniPty.nativeWaitForExit(nativeHandle)
                if (exitCode >= 0) {
                    isRunning.set(false)
                    onExit?.invoke(exitCode)
                    break
                }
                Thread.sleep(200)
            }
        } catch (e: InterruptedException) {
            // Normal
        }
    }

    /**
     * Start a fallback session using Runtime.exec().
     * Used when native PTY is unavailable.
     */
    private fun startFallback() {
        Log.w(TAG, "Using Runtime.exec() fallback — no PTY available")

        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val file = File(cwd)

        fallbackProcess = Runtime.getRuntime().exec(
            shellPath,
            envArray,
            if (file.exists()) file else File("/")
        )

        fallbackOutput = fallbackProcess?.outputStream
        fallbackInput = fallbackProcess?.inputStream
        fallbackError = fallbackProcess?.errorStream

        isRunning.set(true)

        // Read stdout
        readerThread = Thread({
            readStream(fallbackInput!!, onOutput)
        }, "PtySession-Stdout").apply {
            isDaemon = true
            start()
        }

        // Read stderr
        errorReaderThread = Thread({
            readStream(fallbackError!!, onError)
        }, "PtySession-Stderr").apply {
            isDaemon = true
            start()
        }

        // Monitor exit
        exitWatchThread = Thread({
            val exitCode = fallbackProcess?.waitFor() ?: -1
            isRunning.set(false)
            onExit?.invoke(exitCode)
        }, "PtySession-ExitWatch").apply {
            isDaemon = true
            start()
        }
    }

    private fun readStream(stream: InputStream, callback: ((ByteArray) -> Unit)?) {
        try {
            val buffer = ByteArray(4096)
            var len: Int
            while (stream.read(buffer).also { len = it } != -1) {
                if (len > 0 && callback != null) {
                    callback.invoke(buffer.copyOf(len))
                }
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                Log.d(TAG, "Stream read error: ${e.message}")
            }
        }
    }

    // ---- Write Operations ----

    /**
     * Write raw bytes to the terminal (input to the shell).
     */
    fun write(data: ByteArray) {
        if (!isRunning.get()) return

        try {
            if (useNativePty && nativeHandle != 0L) {
                var offset = 0
                while (offset < data.size) {
                    val written = JniPty.nativeWrite(nativeHandle, data, offset, data.size - offset)
                    if (written < 0) {
                        Log.e(TAG, "PTY write failed")
                        break
                    }
                    offset += written
                }
            } else {
                fallbackOutput?.apply {
                    write(data)
                    flush()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
        }
    }

    /**
     * Write a UTF-8 string to the terminal.
     */
    fun write(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Send a line of input (appends \r for PTY or \n for fallback).
     */
    fun sendInput(text: String) {
        write(text + "\r")
    }

    /**
     * Send a control character (Ctrl+A through Ctrl+Z).
     * This sends the raw byte (1-26) which the PTY's line discipline
     * interprets as a signal-generating character.
     */
    fun sendCtrl(c: Char) {
        val code = c.uppercaseChar().code - 'A'.code + 1
        if (code in 1..26) {
            write(byteArrayOf(code.toByte()))
        }
    }

    /**
     * Send an escape sequence.
     */
    fun sendEscapeSequence(seq: String) {
        write("\u001B$seq")
    }

    /**
     * Send an arrow key via ANSI escape sequence.
     */
    fun sendArrowKey(direction: ArrowDirection) {
        val seq = when (direction) {
            ArrowDirection.UP -> "\u001B[A"
            ArrowDirection.DOWN -> "\u001B[B"
            ArrowDirection.RIGHT -> "\u001B[C"
            ArrowDirection.LEFT -> "\u001B[D"
        }
        write(seq)
    }

    /**
     * Send a function key (F1-F12).
     */
    fun sendFunctionKey(key: Int) {
        val seq = when (key) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return
        }
        write(seq)
    }

    fun sendTab() { write("\t") }
    fun sendEnter() { write("\r") }
    fun sendBackspace() { write(byteArrayOf(0x7F)) }
    fun sendDelete() { write("\u001B[3~") }
    fun sendHome() { write("\u001B[H") }
    fun sendEnd() { write("\u001B[F") }
    fun sendPageUp() { write("\u001B[5~") }
    fun sendPageDown() { write("\u001B[6~") }

    /**
     * Send a Unix signal to the child's foreground process group.
     * With native PTY, this uses kill(-pid, signal) to reach all processes
     * in the terminal's session, not just the immediate child.
     */
    fun sendSignal(signal: Int) {
        if (useNativePty && nativeHandle != 0L) {
            JniPty.nativeSendSignal(nativeHandle, signal)
        } else {
            // Fallback: use Android's Process.sendSignal
            try {
                val pid = fallbackProcess?.hashCode() ?: return
                android.os.Process.sendSignal(pid, signal)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback signal failed", e)
            }
        }
    }

    /**
     * Resize the terminal.
     * With native PTY, this calls TIOCSWINSZ which generates SIGWINCH,
     * allowing programs like vim, top, htop, etc. to adapt.
     */
    fun resize(newCols: Int, newRows: Int) {
        columns = newCols
        rows = newRows

        if (useNativePty && nativeHandle != 0L) {
            JniPty.nativeResize(nativeHandle, newRows, newCols)
        }
        // Fallback mode has no resize capability
    }

    /**
     * Get the child process PID.
     */
    fun getChildPid(): Int {
        return if (useNativePty && nativeHandle != 0L) {
            JniPty.nativeGetChildPid(nativeHandle)
        } else {
            fallbackProcess?.hashCode() ?: -1
        }
    }

    /**
     * Close the terminal session.
     * Closes the PTY master fd (which sends SIGHUP to the child's session)
     * and cleans up all resources.
     */
    fun close() {
        isRunning.set(false)

        if (useNativePty && nativeHandle != 0L) {
            JniPty.nativeClose(nativeHandle)
            nativeHandle = 0L
        } else {
            try { fallbackOutput?.close() } catch (_: IOException) {}
            try { fallbackInput?.close() } catch (_: IOException) {}
            try { fallbackError?.close() } catch (_: IOException) {}
            fallbackProcess?.destroy()
        }

        readerThread?.interrupt()
        errorReaderThread?.interrupt()
        exitWatchThread?.interrupt()
    }

    enum class ArrowDirection {
        UP, DOWN, LEFT, RIGHT
    }
}
