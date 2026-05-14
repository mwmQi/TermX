package com.termx.app.terminal

import android.util.Log

/**
 * JNI bridge to the native PTY implementation.
 *
 * This class provides the Kotlin interface to the C-based pseudo-terminal
 * functions defined in termx_pty.c. Each method maps 1:1 to a native function
 * that manages a real Unix PTY (master/slave pair) with proper terminal
 * semantics including:
 *
 * - True TTY line discipline (ICANON off, ISIG on for Ctrl+C/Z handling)
 * - Window size updates via TIOCSWINSZ ioctl (generates SIGWINCH)
 * - Process group-based signal delivery (kill(-pid, sig) for the foreground group)
 * - Non-blocking I/O with poll/select readiness
 * - Session leader assignment via setsid()
 *
 * The native handle (jlong) is a pointer to a PtyProcess C struct that holds
 * the master fd, child PID, and session state. It must be explicitly freed
 * via close() to avoid memory leaks.
 */
object JniPty {

    private const val TAG = "JniPty"

    init {
        try {
            System.loadLibrary("termx-pty")
            Log.i(TAG, "Native PTY library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native PTY library", e)
        }
    }

    /**
     * Check if the native library was loaded successfully.
     */
    val isAvailable: Boolean by lazy {
        try {
            // Try calling a simple native method to verify
            val testHandle = nativeCreatePty(
                "/system/bin/sh", "/",
                arrayOf<String>(), 1, 1
            )
            if (testHandle != 0L) {
                nativeClose(testHandle)
                true
            } else false
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native PTY library not available: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Native PTY test failed: ${e.message}")
            false
        }
    }

    /**
     * Create a new PTY and fork a shell process.
     *
     * @param shellPath Absolute path to the shell binary (e.g., "/system/bin/sh")
     * @param cwd       Working directory for the shell
     * @param envArray  Array of "KEY=VALUE" environment variable strings
     * @param rows      Initial terminal rows
     * @param cols      Initial terminal columns
     * @return          Native handle (pointer to PtyProcess struct), or 0 on failure
     */
    @JvmStatic
    external fun nativeCreatePty(
        shellPath: String,
        cwd: String,
        envArray: Array<String>,
        rows: Int,
        cols: Int
    ): Long

    /**
     * Read available data from the PTY master (output from the shell).
     *
     * @param handle   Native PtyProcess handle
     * @param bufSize  Maximum bytes to read (recommend 4096-65536)
     * @return         ByteArray of data read, or null if nothing available / EOF
     */
    @JvmStatic
    external fun nativeRead(handle: Long, bufSize: Int): ByteArray?

    /**
     * Write data to the PTY master (input to the shell).
     *
     * @param handle   Native PtyProcess handle
     * @param data     Byte array of data to write
     * @param offset   Offset into the data array
     * @param length   Number of bytes to write
     * @return         Number of bytes actually written, or -1 on error
     */
    @JvmStatic
    external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    /**
     * Update the PTY window size.
     * This sends a TIOCSWINSZ ioctl which generates SIGWINCH in the child's
     * foreground process group, allowing interactive programs (vim, top, etc.)
     * to react to terminal resize events.
     *
     * @param handle  Native PtyProcess handle
     * @param rows    New row count
     * @param cols    New column count
     */
    @JvmStatic
    external fun nativeResize(handle: Long, rows: Int, cols: Int)

    /**
     * Get the PID of the child shell process.
     *
     * @param handle Native PtyProcess handle
     * @return       Child PID, or -1 on error
     */
    @JvmStatic
    external fun nativeGetChildPid(handle: Long): Int

    /**
     * Check if the child process is still running.
     *
     * @param handle Native PtyProcess handle
     * @return       true if the child is alive
     */
    @JvmStatic
    external fun nativeIsChildAlive(handle: Long): Boolean

    /**
     * Send a signal to the child's foreground process group.
     * Common signals:
     *   - SIGINT  (2)  — Ctrl+C
     *   - SIGQUIT (3)  — Ctrl+\
     *   - SIGTSTP (20) — Ctrl+Z
     *   - SIGTERM (15) — Graceful termination
     *   - SIGKILL (9)  — Force kill
     *   - SIGHUP  (1)  — Hangup (terminal closed)
     *
     * @param handle  Native PtyProcess handle
     * @param signal  Unix signal number
     */
    @JvmStatic
    external fun nativeSendSignal(handle: Long, signal: Int)

    /**
     * Non-blocking check for child process exit status.
     *
     * @param handle Native PtyProcess handle
     * @return       Exit code if exited, -1 if still running, -2 on error
     */
    @JvmStatic
    external fun nativeWaitForExit(handle: Long): Int

    /**
     * Close the PTY and terminate the child process.
     * Sends SIGHUP → SIGTERM → (brief wait) → SIGKILL.
     *
     * @param handle Native PtyProcess handle
     */
    @JvmStatic
    external fun nativeClose(handle: Long)

    /**
     * Get the master file descriptor for poll/select operations.
     *
     * @param handle Native PtyProcess handle
     * @return       Master fd number, or -1 on error
     */
    @JvmStatic
    external fun nativeGetMasterFd(handle: Long): Int
}
