package com.termx.app.terminal

import android.util.Log
import java.io.*
import java.nio.charset.StandardCharsets

/**
 * Manages a shell process session.
 *
 * This class now serves as a compatibility wrapper around PtySession,
 * which provides true PTY-based terminal support. If the native PTY
 * library is available, it uses forkpty() for proper terminal semantics
 * (job control, signal handling, TIOCSWINSZ). Otherwise, it falls back
 * to Runtime.exec() with limited functionality.
 *
 * The public API remains backward-compatible with the original TerminalSession.
 */
class TerminalSession(
    private val shellPath: String = "/system/bin/sh",
    private val cwd: String = System.getProperty("user.home", "/"),
    private val env: Map<String, String> = defaultEnv()
) {
    companion object {
        private const val TAG = "TerminalSession"

        fun defaultEnv(): Map<String, String> = PtySession.defaultEnv()
    }

    // Internal PTY session — handles native PTY or fallback
    private val ptySession = PtySession(shellPath, cwd, env)

    var process: Process? = null  // Kept for backward compatibility (null with native PTY)
        private set

    var isRunning: Boolean = false
        private set

    // Callbacks
    var onOutput: ((ByteArray) -> Unit)?
        get() = ptySession.onOutput
        set(value) { ptySession.onOutput = value }

    var onError: ((ByteArray) -> Unit)?
        get() = ptySession.onError
        set(value) { ptySession.onError = value }

    var onExit: ((Int) -> Unit)?
        get() = ptySession.onExit
        set(value) { ptySession.onExit = value }

    var onTitleChanged: ((String) -> Unit)?
        get() = ptySession.onTitleChanged
        set(value) { ptySession.onTitleChanged = value }

    // Terminal size for PTY
    var columns: Int
        get() = ptySession.columns
        set(value) { ptySession.columns = value }

    var rows: Int
        get() = ptySession.rows
        set(value) { ptySession.rows = value }

    fun start() {
        if (isRunning) return

        try {
            ptySession.start()
            isRunning = true

            // Monitor for exit
            Thread({
                while (ptySession.running) {
                    Thread.sleep(200)
                }
                isRunning = false
            }, "TermSession-Monitor").apply {
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell session", e)
            isRunning = false
            onOutput?.invoke("Error: Failed to start shell: ${e.message}\r\n".toByteArray())
            onExit?.invoke(-1)
        }
    }

    fun write(data: ByteArray) {
        ptySession.write(data)
    }

    fun write(text: String) {
        ptySession.write(text)
    }

    fun sendInput(text: String) {
        ptySession.sendInput(text)
    }

    fun sendCtrl(c: Char) {
        ptySession.sendCtrl(c)
    }

    fun sendEscapeSequence(seq: String) {
        ptySession.sendEscapeSequence(seq)
    }

    fun sendArrowKey(direction: ArrowDirection) {
        ptySession.sendArrowKey(
            when (direction) {
                ArrowDirection.UP -> PtySession.ArrowDirection.UP
                ArrowDirection.DOWN -> PtySession.ArrowDirection.DOWN
                ArrowDirection.RIGHT -> PtySession.ArrowDirection.RIGHT
                ArrowDirection.LEFT -> PtySession.ArrowDirection.LEFT
            }
        )
    }

    fun sendFunctionKey(key: Int) {
        ptySession.sendFunctionKey(key)
    }

    fun sendTab() { ptySession.sendTab() }
    fun sendEnter() { ptySession.sendEnter() }
    fun sendBackspace() { ptySession.sendBackspace() }
    fun sendDelete() { ptySession.sendDelete() }
    fun sendHome() { ptySession.sendHome() }
    fun sendEnd() { ptySession.sendEnd() }
    fun sendPageUp() { ptySession.sendPageUp() }
    fun sendPageDown() { ptySession.sendPageDown() }

    /**
     * Resize the terminal.
     * With native PTY, this sends TIOCSWINSZ which generates SIGWINCH.
     */
    fun resize(cols: Int, rows: Int) {
        ptySession.resize(cols, rows)
    }

    fun sendSignal(signal: Int) {
        ptySession.sendSignal(signal)
    }

    fun close() {
        isRunning = false
        ptySession.close()
    }

    /**
     * Get the child process PID.
     */
    fun getChildPid(): Int = ptySession.getChildPid()

    enum class ArrowDirection {
        UP, DOWN, LEFT, RIGHT
    }
}
