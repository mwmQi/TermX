package com.termx.app.session

import android.content.Context
import com.termx.app.terminal.TerminalBuffer
import com.termx.app.terminal.TerminalColors
import com.termx.app.terminal.TerminalEmulator
import com.termx.app.terminal.TerminalSession
import com.termx.app.x11.DisplayInfo
import com.termx.app.x11.X11Manager
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple terminal sessions and display sessions.
 *
 * Session types:
 *   - Terminal sessions: Shell processes with VT100/VT220 emulation
 *   - Display sessions: X11 virtual displays (:0, :1, :2, etc.)
 *
 * Display sessions appear as tabs in the main UI alongside terminal sessions,
 * each tagged with its display number (e.g., "Display :0", "Display :1").
 *
 * When a display session tab is selected, the app opens X11DisplayActivity
 * to render the virtual framebuffer. When a terminal tab is selected,
 * the terminal view is shown as usual.
 *
 * The DISPLAY environment variable is automatically injected into new
 * terminal sessions when a display is running.
 */
class SessionManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Base session info — can be a terminal or a display.
     */
    sealed class SessionType {
        data class Terminal(
            val id: String,
            val number: Int,
            val session: TerminalSession,
            val buffer: TerminalBuffer,
            val emulator: TerminalEmulator,
            var title: String = "Terminal $number",
            var createdAt: Long = System.currentTimeMillis(),
            var onUpdate: (() -> Unit)? = null
        ) : SessionType()

        data class Display(
            val displayNum: Int,
            var title: String = "Display :0",
            var createdAt: Long = System.currentTimeMillis()
        ) : SessionType()
    }

    // Terminal sessions
    private val terminalSessions = ConcurrentHashMap<String, SessionType.Terminal>()
    private var activeTerminalId: String? = null

    // Display sessions
    private val displaySessions = ConcurrentHashMap<Int, SessionType.Display>()

    // Global ordering — all sessions (terminal + display) in creation order
    private val sessionOrder = mutableListOf<Any>()  // String (terminal ID) or Int (display num)

    // Active session tracking
    private var activeSessionType: SessionKind = SessionKind.TERMINAL
    private var activeDisplayNum: Int? = null

    enum class SessionKind { TERMINAL, DISPLAY }

    // Current terminal session (for backward compatibility)
    val activeSession: SessionType.Terminal?
        get() = activeTerminalId?.let { terminalSessions[it] }

    val activeDisplaySession: SessionType.Display?
        get() = activeDisplayNum?.let { displaySessions[it] }

    val activeSessionCount: Int
        get() = terminalSessions.size + displaySessions.size

    /** Get all sessions in order (terminals first, then displays) */
    val allSessions: List<SessionType>
        get() {
            val terminals = terminalSessions.values.toList().sortedBy { it.createdAt }
            val displays = displaySessions.values.toList().sortedBy { it.displayNum }
            return terminals + displays
        }

    /** Get all terminal sessions */
    val allTerminalSessions: List<SessionType.Terminal>
        get() = terminalSessions.values.toList().sortedBy { it.createdAt }

    /** Get all display sessions */
    val allDisplaySessions: List<SessionType.Display>
        get() = displaySessions.values.toList().sortedBy { it.displayNum }

    /** Current active session kind */
    val currentSessionKind: SessionKind get() = activeSessionType

    /** Current active display number (if display is active) */
    val currentDisplayNum: Int? get() = activeDisplayNum

    var onSessionChanged: ((SessionType?) -> Unit)? = null
    var onSessionListChanged: (() -> Unit)? = null

    private fun getNextSessionNumber(): Int {
        val used = terminalSessions.values.map { it.number }.toSet()
        var n = 1
        while (n in used) n++
        return n
    }

    fun createSession(
        shellPath: String = "/system/bin/sh",
        cwd: String = "/data/data/com.termx.app/files/home",
        colors: TerminalColors = TerminalColors.catppuccinMocha()
    ): SessionType.Terminal {
        val num = getNextSessionNumber()
        val id = "session_${num}_${System.currentTimeMillis()}"
        val buffer = TerminalBuffer(columns = 80, rows = 24)
        val session = TerminalSession(shellPath = shellPath, cwd = cwd)
        val emulator = TerminalEmulator(buffer, colors)

        val info = SessionType.Terminal(
            id = id,
            number = num,
            session = session,
            buffer = buffer,
            emulator = emulator,
            title = "Terminal $num"
        )

        // Wire up session output to emulator
        session.onOutput = { data ->
            emulator.process(data)
            info.onUpdate?.invoke()
        }

        // Title change from OSC escape sequences
        session.onTitleChanged = { title ->
            val terminalInfo = terminalSessions[id]
            if (terminalInfo != null) {
                terminalInfo.title = title
                onSessionListChanged?.invoke()
            }
        }

        // Also wire emulator title callback
        emulator.onTitleChanged = { title ->
            val terminalInfo = terminalSessions[id]
            if (terminalInfo != null) {
                terminalInfo.title = title
                onSessionListChanged?.invoke()
            }
        }

        session.onExit = { exitCode ->
            removeSession(id)
        }

        terminalSessions[id] = info
        sessionOrder.add(id)
        activeTerminalId = id
        activeSessionType = SessionKind.TERMINAL
        activeDisplayNum = null

        // Start the shell (uses native PTY if available)
        session.start()

        onSessionChanged?.invoke(info)
        onSessionListChanged?.invoke()

        return info
    }

    /**
     * Create a display session.
     * This starts a virtual X11 display and adds it as a session tab.
     *
     * @param displayNum Display number (0 for :0, etc.)
     * @param width Display width (0 = auto-detect from screen)
     * @param height Display height (0 = auto-detect from screen)
     * @return DisplayInfo or null if failed
     */
    fun createDisplaySession(
        displayNum: Int = X11Manager.allocateDisplayNum(),
        width: Int = 0,
        height: Int = 0
    ): DisplayInfo? {
        if (displayNum < 0) return null

        val success = X11Manager.startDisplay(context, displayNum, width, height)
        if (!success) return null

        val displayInfo = X11Manager.getDisplayInfo(displayNum) ?: return null

        val session = SessionType.Display(
            displayNum = displayNum,
            title = "Display :$displayNum",
            createdAt = System.currentTimeMillis()
        )

        displaySessions[displayNum] = session
        sessionOrder.add(displayNum)
        activeSessionType = SessionKind.DISPLAY
        activeDisplayNum = displayNum

        onSessionChanged?.invoke(session)
        onSessionListChanged?.invoke()

        return displayInfo
    }

    /**
     * Switch to a terminal session.
     */
    fun switchToSession(id: String) {
        if (terminalSessions.containsKey(id)) {
            activeTerminalId = id
            activeSessionType = SessionKind.TERMINAL
            activeDisplayNum = null
            onSessionChanged?.invoke(terminalSessions[id])
        }
    }

    /**
     * Switch to a display session.
     */
    fun switchToDisplay(displayNum: Int) {
        if (displaySessions.containsKey(displayNum)) {
            activeSessionType = SessionKind.DISPLAY
            activeDisplayNum = displayNum
            onSessionChanged?.invoke(displaySessions[displayNum])
        }
    }

    /**
     * Switch to session by position index.
     */
    fun switchToPosition(position: Int) {
        val sessions = allSessions
        if (position !in sessions.indices) return

        when (val session = sessions[position]) {
            is SessionType.Terminal -> switchToSession(session.id)
            is SessionType.Display -> switchToDisplay(session.displayNum)
        }
    }

    /**
     * Remove a terminal session.
     */
    fun removeSession(id: String) {
        val info = terminalSessions.remove(id)
        info?.session?.close()
        sessionOrder.remove(id)

        if (activeTerminalId == id) {
            activeTerminalId = terminalSessions.keys.firstOrNull()
            if (activeTerminalId != null) {
                activeSessionType = SessionKind.TERMINAL
                activeDisplayNum = null
                onSessionChanged?.invoke(terminalSessions[activeTerminalId])
            } else if (displaySessions.isNotEmpty()) {
                activeSessionType = SessionKind.DISPLAY
                activeDisplayNum = displaySessions.keys.first()
                onSessionChanged?.invoke(displaySessions[activeDisplayNum])
            } else {
                onSessionChanged?.invoke(null)
            }
        }

        onSessionListChanged?.invoke()
    }

    /**
     * Remove a display session.
     */
    fun removeDisplaySession(displayNum: Int) {
        displaySessions.remove(displayNum)
        sessionOrder.remove(displayNum)
        X11Manager.stopDisplay(displayNum)

        if (activeDisplayNum == displayNum) {
            activeDisplayNum = null
            if (terminalSessions.isNotEmpty()) {
                activeTerminalId = terminalSessions.keys.first()
                activeSessionType = SessionKind.TERMINAL
                onSessionChanged?.invoke(terminalSessions[activeTerminalId])
            } else if (displaySessions.isNotEmpty()) {
                activeSessionType = SessionKind.DISPLAY
                activeDisplayNum = displaySessions.keys.first()
                onSessionChanged?.invoke(displaySessions[activeDisplayNum])
            } else {
                activeSessionType = SessionKind.TERMINAL
                onSessionChanged?.invoke(null)
            }
        }

        onSessionListChanged?.invoke()
    }

    /**
     * Remove a session by position index.
     */
    fun removeAtPosition(position: Int) {
        val sessions = allSessions
        if (position !in sessions.indices) return

        when (val session = sessions[position]) {
            is SessionType.Terminal -> removeSession(session.id)
            is SessionType.Display -> removeDisplaySession(session.displayNum)
        }
    }

    fun closeAllSessions() {
        terminalSessions.values.forEach { it.session.close() }
        terminalSessions.clear()
        displaySessions.clear()
        sessionOrder.clear()
        X11Manager.stopAllDisplays()
        activeTerminalId = null
        activeDisplayNum = null
        activeSessionType = SessionKind.TERMINAL
        onSessionChanged?.invoke(null)
        onSessionListChanged?.invoke()
    }

    fun getSession(id: String): SessionType.Terminal? = terminalSessions[id]

    fun getDisplaySession(displayNum: Int): SessionType.Display? = displaySessions[displayNum]

    fun renameSession(id: String, newTitle: String) {
        terminalSessions[id]?.title = newTitle
        onSessionListChanged?.invoke()
    }

    /**
     * Resize the active session's PTY.
     */
    fun resizeActiveSession(cols: Int, rows: Int) {
        activeSession?.session?.resize(cols, rows)
    }

    // ---- Backward compatibility helpers ----

    /**
     * Get the number of terminal sessions only.
     */
    val terminalCount: Int get() = terminalSessions.size

    /**
     * Get the number of display sessions only.
     */
    val displayCount: Int get() = displaySessions.size
}
