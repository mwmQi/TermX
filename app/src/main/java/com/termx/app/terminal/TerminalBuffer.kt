package com.termx.app.terminal

/**
 * Terminal cell representing a single character on screen.
 * Stores character, foreground color, background color, and style flags.
 */
data class TerminalCell(
    val char: Char = ' ',
    val fgColor: Int = 0,
    val bgColor: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false
)

/**
 * Represents the terminal screen buffer.
 * Manages rows/columns of TerminalCells and cursor position.
 */
class TerminalBuffer(
    var columns: Int = 80,
    var rows: Int = 24,
    var scrollbackLines: Int = 5000
) {
    // Main screen buffer
    private val screen: Array<Array<TerminalCell>> = Array(rows) {
        Array(columns) { TerminalCell() }
    }

    // Scrollback history (lines that have scrolled off the top)
    private val scrollback: MutableList<Array<TerminalCell>> = mutableListOf()

    // Cursor position
    var cursorCol: Int = 0
    var cursorRow: Int = 0
    var cursorVisible: Boolean = true

    // Saved cursor state (for CSI s/u)
    private var savedCursorCol: Int = 0
    private var savedCursorRow: Int = 0

    // Scrolling region
    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    // Current attributes for new characters
    var currentFg: Int = 0
    var currentBg: Int = 0
    var currentBold: Boolean = false
    var currentItalic: Boolean = false
    var currentUnderline: Boolean = false
    var currentStrikethrough: Boolean = false
    var currentBlink: Boolean = false
    var currentInverse: Boolean = false

    // Selection
    var selectionStart: Pair<Int, Int>? = null
    var selectionEnd: Pair<Int, Int>? = null

    fun getCell(row: Int, col: Int): TerminalCell {
        if (row < 0 || row >= rows || col < 0 || col >= columns) {
            return TerminalCell()
        }
        return screen[row][col]
    }

    fun setCell(row: Int, col: Int, cell: TerminalCell) {
        if (row in 0 until rows && col in 0 until columns) {
            screen[row][col] = cell
        }
    }

    fun putChar(c: Char) {
        if (cursorCol >= columns) {
            cursorCol = 0
            cursorRow++
            if (cursorRow > scrollBottom) {
                scrollUp()
                cursorRow = scrollBottom
            }
        }
        if (cursorRow in 0 until rows && cursorCol in 0 until columns) {
            screen[cursorRow][cursorCol] = TerminalCell(
                char = c,
                fgColor = currentFg,
                bgColor = currentBg,
                bold = currentBold,
                italic = currentItalic,
                underline = currentUnderline,
                strikethrough = currentStrikethrough,
                blink = currentBlink,
                inverse = currentInverse
            )
        }
        cursorCol++
    }

    fun scrollUp() {
        // Move the top line of scroll region to scrollback
        if (scrollTop == 0) {
            scrollback.add(screen[0].copyOf())
            if (scrollback.size > scrollbackLines) {
                scrollback.removeAt(0)
            }
        }
        // Shift lines up within scroll region
        for (row in scrollTop until scrollBottom) {
            for (col in 0 until columns) {
                screen[row][col] = screen[row + 1][col]
            }
        }
        // Clear the bottom line of scroll region
        clearLine(scrollBottom)
    }

    fun scrollDown() {
        for (row in scrollBottom downTo (scrollTop + 1)) {
            for (col in 0 until columns) {
                screen[row][col] = screen[row - 1][col]
            }
        }
        clearLine(scrollTop)
    }

    fun clearLine(row: Int) {
        if (row in 0 until rows) {
            for (col in 0 until columns) {
                screen[row][col] = TerminalCell(fgColor = currentFg, bgColor = currentBg)
            }
        }
    }

    fun clearScreen() {
        for (row in 0 until rows) {
            clearLine(row)
        }
    }

    fun clearFromCursorToEnd() {
        // Clear from cursor to end of line
        for (col in cursorCol until columns) {
            if (cursorRow in 0 until rows) {
                screen[cursorRow][col] = TerminalCell(fgColor = currentFg, bgColor = currentBg)
            }
        }
        // Clear all lines below
        for (row in (cursorRow + 1) until rows) {
            clearLine(row)
        }
    }

    fun clearFromCursorToStart() {
        // Clear from start of line to cursor
        for (col in 0..cursorCol) {
            if (cursorRow in 0 until rows) {
                screen[cursorRow][col] = TerminalCell(fgColor = currentFg, bgColor = currentBg)
            }
        }
        // Clear all lines above
        for (row in 0 until cursorRow) {
            clearLine(row)
        }
    }

    fun clearLineFromCursorToEnd() {
        if (cursorRow in 0 until rows) {
            for (col in cursorCol until columns) {
                screen[cursorRow][col] = TerminalCell(fgColor = currentFg, bgColor = currentBg)
            }
        }
    }

    fun clearLineFromCursorToStart() {
        if (cursorRow in 0 until rows) {
            for (col in 0..cursorCol) {
                screen[cursorRow][col] = TerminalCell(fgColor = currentFg, bgColor = currentBg)
            }
        }
    }

    fun saveCursor() {
        savedCursorCol = cursorCol
        savedCursorRow = cursorRow
    }

    fun restoreCursor() {
        cursorCol = savedCursorCol
        cursorRow = savedCursorRow
    }

    fun getLineText(row: Int): String {
        if (row < 0 || row >= rows) return ""
        return screen[row].map { it.char }.joinToString("")
    }

    fun getScrollbackLine(index: Int): Array<TerminalCell>? {
        return if (index in scrollback.indices) scrollback[index] else null
    }

    val scrollbackSize: Int get() = scrollback.size

    fun resize(newCols: Int, newRows: Int) {
        // Create new buffer and copy what fits
        val newScreen = Array(newRows) {
            Array(newCols) { TerminalCell() }
        }
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(columns, newCols)
        for (row in 0 until copyRows) {
            for (col in 0 until copyCols) {
                newScreen[row][col] = screen[row][col]
            }
        }
        columns = newCols
        rows = newRows
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
    }

    fun reset() {
        clearScreen()
        cursorCol = 0
        cursorRow = 0
        scrollTop = 0
        scrollBottom = rows - 1
        scrollback.clear()
        currentFg = 0
        currentBg = 0
        currentBold = false
        currentItalic = false
        currentUnderline = false
        currentStrikethrough = false
        currentBlink = false
        currentInverse = false
    }
}
