package com.termx.app.config

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Termux-compatible properties configuration system.
 * Reads from ~/.termux/termux.properties and ~/.termx/termx.properties
 *
 * Supported properties:
 *   bell-character=vibrate|visible|ignore
 *   shortcut.create-session=ctrl+alt+c
 *   shortcut.next-session=ctrl+alt+n
 *   shortcut.previous-session=ctrl+alt+p
 *   extra-keys-style=dark|light|default
 *   extra-keys=[[ESC,...]]
 *   fullscreen=true|false
 *   use-black-ui=true|false
 *   font-size=14
 *   cursor-blink=true|false
 *   cursor-style=block|underline|bar
 *   back-key=escape|back
 *   terminal-margin-horizontal=3
 *   terminal-margin-vertical=0
 *   terminal-transcript-rows=5000
 *   create-session-on-launch=true
 *   default-working-directory=/home
 *   allow-external-apps=true
 *   shell=/system/bin/sh
 */
class TermXProperties(private val context: Context) {

    companion object {
        private const val TAG = "TermXProperties"
        private const val TERMUX_PROPS = "termux.properties"
        private const val TERMXX_PROPS = "termx.properties"
    }

    private val props = Properties()

    // Cached parsed values
    var bellCharacter: BellMode = BellMode.VIBRATE
        private set
    var cursorBlink: Boolean = true
        private set
    var cursorStyle: CursorStyle = CursorStyle.BLOCK
        private set
    var fullscreen: Boolean = false
        private set
    var useBlackUI: Boolean = true
        private set
    var backKeyMode: BackKeyMode = BackKeyMode.BACK
        private set
    var terminalMarginH: Int = 3
        private set
    var terminalMarginV: Int = 0
        private set
    var transcriptRows: Int = 5000
        private set
    var createSessionOnLaunch: Boolean = true
        private set
    var defaultWorkingDir: String = "/"
        private set
    var allowExternalApps: Boolean = false
        private set
    var shellPath: String = "/system/bin/sh"
        private set
    var fontSize: Int = 14
        private set
    var extraKeysStyle: String = "dark"
        private set
    var extraKeysLayout: String = "default"
        private set

    // Keyboard shortcuts
    var shortcutNewSession: String = "ctrl+alt+c"
        private set
    var shortcutNextSession: String = "ctrl+alt+n"
        private set
    var shortcutPrevSession: String = "ctrl+alt+p"
        private set
    var shortcutRenameSession: String = "ctrl+alt+r"
        private set

    enum class BellMode { VIBRATE, VISIBLE, IGNORE }
    enum class CursorStyle { BLOCK, UNDERLINE, BAR }
    enum class BackKeyMode { ESCAPE, BACK }

    /**
     * Load properties from files.
     */
    fun load() {
        props.clear()

        // Load from Termux location
        loadFromFile(File(context.filesDir, ".termux/$TERMUX_PROPS"))
        // Load from TermX location (overrides Termux)
        loadFromFile(File(context.filesDir, ".termx/$TERMXX_PROPS"))

        // Parse all properties
        parseProperties()
    }

    private fun loadFromFile(file: File) {
        if (!file.exists()) return

        try {
            file.inputStream().use { stream ->
                // Read line by line to handle comments
                stream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!.trim()
                        // Skip comments and empty lines
                        if (currentLine.isEmpty() || currentLine.startsWith("#")) continue
                        // Parse key=value
                        val eqIndex = currentLine.indexOf('=')
                        if (eqIndex > 0) {
                            val key = currentLine.substring(0, eqIndex).trim()
                            val value = currentLine.substring(eqIndex + 1).trim()
                            props[key] = value
                        }
                    }
                }
            }
            Log.d(TAG, "Loaded properties from: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load properties from: ${file.absolutePath}", e)
        }
    }

    private fun parseProperties() {
        bellCharacter = when (props.getProperty("bell-character", "vibrate").lowercase()) {
            "vibrate" -> BellMode.VIBRATE
            "visible" -> BellMode.VISIBLE
            "ignore" -> BellMode.IGNORE
            else -> BellMode.VIBRATE
        }

        cursorBlink = props.getProperty("cursor-blink", "true").toBooleanStrictOrNull() ?: true
        cursorStyle = when (props.getProperty("cursor-style", "block").lowercase()) {
            "block" -> CursorStyle.BLOCK
            "underline" -> CursorStyle.UNDERLINE
            "bar" -> CursorStyle.BAR
            else -> CursorStyle.BLOCK
        }

        fullscreen = props.getProperty("fullscreen", "false").toBooleanStrictOrNull() ?: false
        useBlackUI = props.getProperty("use-black-ui", "true").toBooleanStrictOrNull() ?: true
        terminalMarginH = props.getProperty("terminal-margin-horizontal", "3").toIntOrNull() ?: 3
        terminalMarginV = props.getProperty("terminal-margin-vertical", "0").toIntOrNull() ?: 0
        transcriptRows = props.getProperty("terminal-transcript-rows", "5000").toIntOrNull() ?: 5000
        createSessionOnLaunch = props.getProperty("create-session-on-launch", "true").toBooleanStrictOrNull() ?: true
        defaultWorkingDir = props.getProperty("default-working-directory", "/")
        allowExternalApps = props.getProperty("allow-external-apps", "false").toBooleanStrictOrNull() ?: false
        shellPath = props.getProperty("shell", "/system/bin/sh")
        fontSize = props.getProperty("font-size", "14").toIntOrNull() ?: 14
        extraKeysStyle = props.getProperty("extra-keys-style", "dark")
        extraKeysLayout = props.getProperty("extra-keys-layout", "default")

        backKeyMode = when (props.getProperty("back-key", "back").lowercase()) {
            "escape" -> BackKeyMode.ESCAPE
            "back" -> BackKeyMode.BACK
            else -> BackKeyMode.BACK
        }

        // Shortcuts
        shortcutNewSession = props.getProperty("shortcut.create-session", "ctrl+alt+c")
        shortcutNextSession = props.getProperty("shortcut.next-session", "ctrl+alt+n")
        shortcutPrevSession = props.getProperty("shortcut.previous-session", "ctrl+alt+p")
        shortcutRenameSession = props.getProperty("shortcut.rename-session", "ctrl+alt+r")

        Log.i(TAG, "Properties parsed: bell=$bellCharacter, cursor=$cursorStyle, fullscreen=$fullscreen")
    }

    /**
     * Generate a default termx.properties file.
     */
    fun generateDefaultConfig(): String {
        return """
# TermX Properties
# Place in ~/.termx/termx.properties
# Also reads ~/.termux/termux.properties for compatibility

# Bell behavior: vibrate, visible, ignore
bell-character=vibrate

# Cursor style: block, underline, bar
cursor-style=block
cursor-blink=true

# Font size (in sp)
font-size=14

# Fullscreen on startup
fullscreen=false

# Dark UI
use-black-ui=true

# Terminal margins (in dp)
terminal-margin-horizontal=3
terminal-margin-vertical=0

# Scrollback buffer size
terminal-transcript-rows=5000

# Create session on app launch
create-session-on-launch=true

# Default working directory
default-working-directory=/

# Shell path
shell=/system/bin/sh

# Back key behavior: escape or back
back-key=back

# Allow external apps to run commands
allow-external-apps=false

# Extra keys style: dark, light, default
extra-keys-style=dark

# Keyboard shortcuts
shortcut.create-session=ctrl+alt+c
shortcut.next-session=ctrl+alt+n
shortcut.previous-session=ctrl+alt+p
shortcut.rename-session=ctrl+alt+r
""".trimIndent()
    }

    /**
     * Write the default config file if it doesn't exist.
     */
    fun ensureDefaultConfig() {
        val termxDir = File(context.filesDir, ".termx")
        if (!termxDir.exists()) termxDir.mkdirs()

        val configFile = File(termxDir, TERMXX_PROPS)
        if (!configFile.exists()) {
            configFile.writeText(generateDefaultConfig())
            Log.i(TAG, "Created default config at: ${configFile.absolutePath}")
        }
    }
}
