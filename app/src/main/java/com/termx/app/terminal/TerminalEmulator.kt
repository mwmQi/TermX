package com.termx.app.terminal

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * VT100/VT220 terminal emulator with XTerm extensions.
 * Parses escape sequences and updates the terminal buffer.
 */
class TerminalEmulator(
    private val buffer: TerminalBuffer,
    private val colors: TerminalColors
) {
    companion object {
        private const val TAG = "TerminalEmulator"
    }

    // Parser states
    private enum class State {
        NORMAL, ESC, CSI, OSC, CHARSET
    }

    private var state = State.NORMAL
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var charsetIndex = 0

    // Title callback
    var onTitleChanged: ((String) -> Unit)? = null

    // Bell callback
    var onBell: (() -> Unit)? = null

    fun process(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = data[i++].toInt() and 0xFF
            processByte(b)
        }
    }

    fun processString(str: String) {
        process(str.toByteArray(Charsets.UTF_8))
    }

    private fun processByte(b: Int) {
        when (state) {
            State.NORMAL -> processNormal(b)
            State.ESC -> processEsc(b)
            State.CSI -> processCsi(b)
            State.OSC -> processOsc(b)
            State.CHARSET -> processCharset(b)
        }
    }

    private fun processNormal(b: Int) {
        when (b) {
            0x07 -> onBell?.invoke() // BEL
            0x08 -> { // BS - Backspace
                buffer.cursorCol = (buffer.cursorCol - 1).coerceAtLeast(0)
            }
            0x09 -> { // HT - Tab
                buffer.cursorCol = ((buffer.cursorCol / 8) + 1) * 8
                if (buffer.cursorCol >= buffer.columns) {
                    buffer.cursorCol = buffer.columns - 1
                }
            }
            0x0A -> { // LF - Line Feed
                buffer.cursorRow++
                if (buffer.cursorRow > buffer.scrollBottom) {
                    buffer.scrollUp()
                    buffer.cursorRow = buffer.scrollBottom
                }
            }
            0x0D -> buffer.cursorCol = 0 // CR - Carriage Return
            0x1B -> state = State.ESC // ESC
            in 0x20..0x7E -> buffer.putChar(b.toChar()) // Printable ASCII
            in 0x80..0xFF -> {
                // UTF-8 handling - for now treat as printable
                buffer.putChar(b.toChar())
            }
        }
    }

    private fun processEsc(b: Int) {
        when (b) {
            0x5B -> { // [ - CSI
                state = State.CSI
                csiBuffer.clear()
            }
            0x5D -> { // ] - OSC
                state = State.OSC
                oscBuffer.clear()
            }
            0x37 -> { // ESC 7 - Save cursor
                buffer.saveCursor()
                state = State.NORMAL
            }
            0x38 -> { // ESC 8 - Restore cursor
                buffer.restoreCursor()
                state = State.NORMAL
            }
            0x44 -> { // ESC D - Index (move down)
                buffer.cursorRow++
                if (buffer.cursorRow > buffer.scrollBottom) {
                    buffer.scrollUp()
                    buffer.cursorRow = buffer.scrollBottom
                }
                state = State.NORMAL
            }
            0x4D -> { // ESC M - Reverse Index (move up)
                buffer.cursorRow--
                if (buffer.cursorRow < buffer.scrollTop) {
                    buffer.scrollDown()
                    buffer.cursorRow = buffer.scrollTop
                }
                state = State.NORMAL
            }
            0x45 -> { // ESC E - Next Line
                buffer.cursorCol = 0
                buffer.cursorRow++
                if (buffer.cursorRow > buffer.scrollBottom) {
                    buffer.scrollUp()
                    buffer.cursorRow = buffer.scrollBottom
                }
                state = State.NORMAL
            }
            0x63 -> { // ESC c - Reset
                buffer.reset()
                state = State.NORMAL
            }
            0x28, 0x29 -> { // ESC ( / ESC ) - Character set
                charsetIndex = if (b == 0x28) 0 else 1
                state = State.CHARSET
            }
            else -> {
                Log.w(TAG, "Unhandled ESC sequence: 0x${b.toString(16)}")
                state = State.NORMAL
            }
        }
    }

    private fun processCsi(b: Int) {
        if (b in 0x30..0x3F) {
            // Parameter or intermediate byte
            csiBuffer.append(b.toChar())
        } else if (b in 0x40..0x7E) {
            // Final byte - execute CSI sequence
            executeCsi(b.toChar(), csiBuffer.toString())
            state = State.NORMAL
        } else {
            Log.w(TAG, "Unexpected byte in CSI: 0x${b.toString(16)}")
            state = State.NORMAL
        }
    }

    private fun executeCsi(finalChar: Char, params: String) {
        val paramList = parseParams(params)
        val p = if (paramList.isNotEmpty()) paramList[0] else 0
        val q = if (paramList.size > 1) paramList[1] else 0

        when (finalChar) {
            'A' -> { // CUU - Cursor Up
                buffer.cursorRow = (buffer.cursorRow - p).coerceAtLeast(buffer.scrollTop)
            }
            'B' -> { // CUD - Cursor Down
                buffer.cursorRow = (buffer.cursorRow + p).coerceAtMost(buffer.scrollBottom)
            }
            'C' -> { // CUF - Cursor Forward
                buffer.cursorCol = (buffer.cursorCol + p).coerceAtMost(buffer.columns - 1)
            }
            'D' -> { // CUB - Cursor Back
                buffer.cursorCol = (buffer.cursorCol - p).coerceAtLeast(0)
            }
            'E' -> { // CNL - Cursor Next Line
                buffer.cursorRow = (buffer.cursorRow + p).coerceAtMost(buffer.rows - 1)
                buffer.cursorCol = 0
            }
            'F' -> { // CPL - Cursor Previous Line
                buffer.cursorRow = (buffer.cursorRow - p).coerceAtLeast(0)
                buffer.cursorCol = 0
            }
            'G' -> { // CHA - Cursor Horizontal Absolute
                buffer.cursorCol = (p - 1).coerceIn(0, buffer.columns - 1)
            }
            'H', 'f' -> { // CUP - Cursor Position
                buffer.cursorRow = (p - 1).coerceIn(0, buffer.rows - 1)
                buffer.cursorCol = (q - 1).coerceIn(0, buffer.columns - 1)
            }
            'J' -> { // ED - Erase Display
                when (p) {
                    0 -> buffer.clearFromCursorToEnd()
                    1 -> buffer.clearFromCursorToStart()
                    2 -> buffer.clearScreen()
                }
            }
            'K' -> { // EL - Erase Line
                when (p) {
                    0 -> buffer.clearLineFromCursorToEnd()
                    1 -> buffer.clearLineFromCursorToStart()
                    2 -> buffer.clearLine(buffer.cursorRow)
                }
            }
            'L' -> { // IL - Insert Lines
                repeat(p) { buffer.scrollDown() }
            }
            'M' -> { // DL - Delete Lines
                repeat(p) { buffer.scrollUp() }
            }
            'P' -> { // DCH - Delete Characters
                val row = buffer.cursorRow
                val col = buffer.cursorCol
                if (row in 0 until buffer.rows) {
                    for (c in col until (buffer.columns - p)) {
                        if (c + p < buffer.columns) {
                            buffer.setCell(row, c, buffer.getCell(row, c + p))
                        }
                    }
                    for (c in (buffer.columns - p) until buffer.columns) {
                        buffer.setCell(row, c, TerminalCell())
                    }
                }
            }
            'S' -> { // SU - Scroll Up
                repeat(p) { buffer.scrollUp() }
            }
            'T' -> { // SD - Scroll Down
                repeat(p) { buffer.scrollDown() }
            }
            'X' -> { // ECH - Erase Characters
                val row = buffer.cursorRow
                val col = buffer.cursorCol
                for (c in col until minOf(col + p, buffer.columns)) {
                    buffer.setCell(row, c, TerminalCell())
                }
            }
            'd' -> { // VPA - Vertical Position Absolute
                buffer.cursorRow = (p - 1).coerceIn(0, buffer.rows - 1)
            }
            'm' -> { // SGR - Select Graphic Rendition
                handleSgr(paramList)
            }
            'h' -> { // SM - Set Mode
                handleSetMode(params, paramList)
            }
            'l' -> { // RM - Reset Mode
                handleResetMode(params, paramList)
            }
            'r' -> { // DECSTBM - Set Scrolling Region
                buffer.scrollTop = if (p > 0) (p - 1) else 0
                buffer.scrollBottom = if (q > 0) (q - 1) else (buffer.rows - 1)
                buffer.cursorRow = 0
                buffer.cursorCol = 0
            }
            's' -> buffer.saveCursor() // Save cursor
            'u' -> buffer.restoreCursor() // Restore cursor
            else -> {
                Log.d(TAG, "Unhandled CSI: ${finalChar} params=$params")
            }
        }
    }

    private fun handleSgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }

        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> resetAttributes()
                1 -> buffer.currentBold = true
                3 -> buffer.currentItalic = true
                4 -> buffer.currentUnderline = true
                5 -> buffer.currentBlink = true
                7 -> buffer.currentInverse = true
                9 -> buffer.currentStrikethrough = true
                22 -> buffer.currentBold = false
                23 -> buffer.currentItalic = false
                24 -> buffer.currentUnderline = false
                25 -> buffer.currentBlink = false
                27 -> buffer.currentInverse = false
                29 -> buffer.currentStrikethrough = false
                in 30..37 -> buffer.currentFg = colors.getAnsiColor(params[i] - 30)
                38 -> {
                    // Extended foreground color
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> { // 256-color
                                if (i + 2 < params.size) {
                                    buffer.currentFg = colors.getAnsiColor(params[i + 2])
                                    i += 2
                                }
                            }
                            2 -> { // True color
                                if (i + 4 < params.size) {
                                    val r = params[i + 2]
                                    val g = params[i + 3]
                                    val b = params[i + 4]
                                    buffer.currentFg = (0xFF000000.toInt() or
                                            (r shl 16) or (g shl 8) or b)
                                    i += 4
                                }
                            }
                        }
                    }
                }
                39 -> buffer.currentFg = colors.foreground
                in 40..47 -> buffer.currentBg = colors.getAnsiColor(params[i] - 40)
                48 -> {
                    // Extended background color
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> {
                                if (i + 2 < params.size) {
                                    buffer.currentBg = colors.getAnsiColor(params[i + 2])
                                    i += 2
                                }
                            }
                            2 -> {
                                if (i + 4 < params.size) {
                                    val r = params[i + 2]
                                    val g = params[i + 3]
                                    val b = params[i + 4]
                                    buffer.currentBg = (0xFF000000.toInt() or
                                            (r shl 16) or (g shl 8) or b)
                                    i += 4
                                }
                            }
                        }
                    }
                }
                49 -> buffer.currentBg = colors.background
                in 90..97 -> buffer.currentFg = colors.getAnsiColor(params[i] - 90 + 8)
                in 100..107 -> buffer.currentBg = colors.getAnsiColor(params[i] - 100 + 8)
            }
            i++
        }
    }

    private fun handleSetMode(params: String, paramList: List<Int>) {
        if (params.startsWith("?")) {
            for (p in paramList) {
                when (p) {
                    25 -> buffer.cursorVisible = true
                    // Add more DEC private modes as needed
                }
            }
        }
    }

    private fun handleResetMode(params: String, paramList: List<Int>) {
        if (params.startsWith("?")) {
            for (p in paramList) {
                when (p) {
                    25 -> buffer.cursorVisible = false
                }
            }
        }
    }

    private fun processOsc(b: Int) {
        when (b) {
            0x07 -> { // BEL terminates OSC
                executeOsc(oscBuffer.toString())
                state = State.NORMAL
            }
            0x1B -> { // ESC can also terminate via ST
                state = State.NORMAL
            }
            else -> oscBuffer.append(b.toChar())
        }
    }

    private fun executeOsc(data: String) {
        val parts = data.split(";", limit = 2)
        if (parts.size == 2) {
            when (parts[0]) {
                "0", "2" -> onTitleChanged?.invoke(parts[1]) // Window title
            }
        }
    }

    private fun processCharset(b: Int) {
        // Just ignore charset designations for now
        state = State.NORMAL
    }

    private fun resetAttributes() {
        buffer.currentFg = colors.foreground
        buffer.currentBg = colors.background
        buffer.currentBold = false
        buffer.currentItalic = false
        buffer.currentUnderline = false
        buffer.currentStrikethrough = false
        buffer.currentBlink = false
        buffer.currentInverse = false
    }

    private fun parseParams(params: String): List<Int> {
        if (params.isEmpty()) return listOf(0)
        val cleaned = params.trimStart('?')
        return cleaned.split(";").mapNotNull {
            it.toIntOrNull()?.let { v -> if (v == 0 && it.isNotEmpty() && it != "0") null else v }
        }.ifEmpty { listOf(0) }
    }
}
