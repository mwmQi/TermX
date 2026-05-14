package com.termx.app.terminal

import android.content.ClipData
import android.content.Context
import android.graphics.*
import android.content.ClipboardManager
import android.text.InputType
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import kotlin.math.max
import kotlin.math.min

/**
 * Custom View that renders a terminal screen.
 * Handles drawing, touch input, scrolling, and text selection.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Terminal state
    var buffer: TerminalBuffer = TerminalBuffer()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var session: TerminalSession? = null
    var emulator: TerminalEmulator? = null

    // Appearance
    var colors: TerminalColors = TerminalColors.catppuccinMocha()
        set(value) {
            field = value
            bgPaint.color = value.background
            invalidate()
        }

    private var fontSize: Float = 14f // in SP
    private var fontType: String = "monospace"

    // Paints
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Measurements
    private var charWidth: Float = 0f
    private var charHeight: Float = 0f
    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var paddingTopPx: Float = 0f
    private var paddingBottomPx: Float = 0f
    private var paddingLeftPx: Float = 0f
    private var paddingRightPx: Float = 0f

    // Scrolling
    private var scrollOffset: Int = 0 // Number of scrollback lines visible
    private val scroller = Scroller(context)
    private var isScrolling = false

    // Cursor blink
    private var cursorBlinkOn = true
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            postDelayed(this, 500)
        }
    }

    // Touch tracking
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var isDraggingSelection = false
    private var longPressDetected = false

    // Callbacks
    var onKeyUp: ((Int) -> Unit)? = null
    var onKeyDown: ((Int) -> Unit)? = null

    // Clipboard
    private val clipboard by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0)

        bgPaint.color = colors.background
        selectionPaint.color = Color.argb(80, 100, 150, 255)
        selectionPaint.style = Paint.Style.FILL

        updateFont()
        setupGestureDetector()
    }

    private fun updateFont() {
        val density = resources.displayMetrics.density
        val pxSize = fontSize * density

        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = pxSize
        textPaint.color = colors.foreground

        // Measure character dimensions
        val metrics = textPaint.fontMetrics
        charHeight = metrics.descent - metrics.ascent
        charWidth = textPaint.measureText("M")
        cellWidth = charWidth
        cellHeight = charHeight * 1.2f // Add line spacing

        cursorPaint.color = colors.cursor
        cursorPaint.style = Paint.Style.FILL

        // Calculate terminal size
        recalculateSize()
    }

    private fun recalculateSize() {
        val availWidth = (width - paddingLeftPx - paddingRightPx).coerceAtLeast(1f)
        val availHeight = (height - paddingTopPx - paddingBottomPx).coerceAtLeast(1f)

        val newCols = (availWidth / cellWidth).toInt().coerceAtLeast(1)
        val newRows = (availHeight / cellHeight).toInt().coerceAtLeast(1)

        if (newCols != buffer.columns || newRows != buffer.rows) {
            buffer.resize(newCols, newRows)
            session?.columns = newCols
            session?.rows = newRows
            // Resize native PTY — sends TIOCSWINSZ which generates SIGWINCH
            // in the child's foreground process group
            session?.resize(newCols, newRows)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalculateSize()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        recalculateSize()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val totalLines = buffer.rows + buffer.scrollbackSize
        val startRow = buffer.scrollbackSize - scrollOffset
        val endRow = startRow + buffer.rows

        // Draw cells
        for (screenRow in 0 until buffer.rows) {
            val scrollbackRow = startRow + screenRow
            val y = paddingTopPx + screenRow * cellHeight

            // Get the line data
            val cells: Array<TerminalCell> = if (scrollbackRow < 0) {
                // Regular screen buffer
                buffer.getCell(screenRow, 0).let { _ ->
                    Array(buffer.columns) { col -> buffer.getCell(screenRow, col) }
                }
            } else if (scrollbackRow < buffer.scrollbackSize) {
                // Scrollback line
                buffer.getScrollbackLine(scrollbackRow) ?: continue
            } else {
                continue
            }

            // Draw backgrounds first (for colored backgrounds)
            var bgRunStart = -1
            var currentBgColor = colors.background
            for (col in 0 until cells.size) {
                val cell = if (col < cells.size) cells[col] else TerminalCell()
                val cellBg = if (cell.inverse) cell.fgColor else cell.bgColor
                if (cellBg != currentBgColor || col == cells.size - 1) {
                    if (bgRunStart >= 0 && currentBgColor != colors.background) {
                        val x1 = paddingLeftPx + bgRunStart * cellWidth
                        val x2 = paddingLeftPx + (col + 1) * cellWidth
                        bgPaint.color = currentBgColor
                        canvas.drawRect(x1, y, x2, y + cellHeight, bgPaint)
                    }
                    currentBgColor = cellBg
                    bgRunStart = col
                }
                if (bgRunStart < 0) bgRunStart = col
            }
            bgPaint.color = colors.background

            // Draw selection
            drawSelection(canvas, screenRow, y)

            // Draw characters
            for (col in 0 until cells.size) {
                val cell = cells[col]
                if (cell.char != ' ' && cell.char != '\u0000') {
                    val x = paddingLeftPx + col * cellWidth

                    // Determine color
                    val fg = if (cell.inverse) cell.bgColor else cell.fgColor
                    textPaint.color = fg
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    textPaint.isStrikeThruText = cell.strikethrough
                    textPaint.textSkewX = if (cell.italic) -0.2f else 0f

                    val textY = y + charHeight - textPaint.fontMetrics.descent
                    canvas.drawText(cell.char.toString(), x, textY, textPaint)
                }
            }
        }

        // Draw cursor
        if (buffer.cursorVisible && cursorBlinkOn && scrollOffset == 0) {
            val cursorX = paddingLeftPx + buffer.cursorCol * cellWidth
            val cursorY = paddingTopPx + buffer.cursorRow * cellHeight

            // Cursor style: block
            cursorPaint.alpha = 180
            canvas.drawRect(
                cursorX, cursorY,
                cursorX + cellWidth, cursorY + cellHeight,
                cursorPaint
            )

            // Draw character under cursor with inverted color
            val cell = buffer.getCell(buffer.cursorRow, buffer.cursorCol)
            if (cell.char != ' ' && cell.char != '\u0000') {
                textPaint.color = colors.background
                val textY = cursorY + charHeight - textPaint.fontMetrics.descent
                canvas.drawText(cell.char.toString(), cursorX, textY, textPaint)
            }
        }
    }

    private fun drawSelection(canvas: Canvas, screenRow: Int, y: Float) {
        val selStart = buffer.selectionStart
        val selEnd = buffer.selectionEnd
        if (selStart == null || selEnd == null) return

        val (startRow, startCol) = selStart
        val (endRow, endCol) = selEnd

        if (screenRow in startRow..endRow) {
            val selStartCol = if (screenRow == startRow) startCol else 0
            val selEndCol = if (screenRow == endRow) endCol else buffer.columns

            val x1 = paddingLeftPx + selStartCol * cellWidth
            val x2 = paddingLeftPx + selEndCol * cellWidth
            canvas.drawRect(x1, y, x2, y + cellHeight, selectionPaint)
        }
    }

    // ---- Input Handling ----

    private lateinit var gestureDetector: GestureDetector

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressDetected = true
                // Start selection at touch position
                val col = ((e.x - paddingLeftPx) / cellWidth).toInt().coerceIn(0, buffer.columns - 1)
                val row = ((e.y - paddingTopPx) / cellHeight).toInt().coerceIn(0, buffer.rows - 1)

                // Select the word at position
                selectWordAt(row, col)
                invalidate()
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                if (!isDraggingSelection) {
                    // Terminal scroll
                    val scrollLines = (distanceY / cellHeight).toInt()
                    scrollOffset = (scrollOffset + scrollLines).coerceIn(
                        0, buffer.scrollbackSize
                    )
                    invalidate()
                }
                return true
            }
        })
    }

    private fun selectWordAt(row: Int, col: Int) {
        val line = buffer.getLineText(row)
        if (line.isBlank()) return

        var startCol = col
        var endCol = col

        // Find word boundaries
        while (startCol > 0 && line[startCol - 1].isLetterOrDigit()) startCol--
        while (endCol < line.length && line[endCol].isLetterOrDigit()) endCol++

        buffer.selectionStart = row to startCol
        buffer.selectionEnd = row to endCol
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isDraggingSelection = false
                longPressDetected = false

                requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressDetected) {
                    isDraggingSelection = true
                    val col = ((event.x - paddingLeftPx) / cellWidth).toInt()
                        .coerceIn(0, buffer.columns - 1)
                    val row = ((event.y - paddingTopPx) / cellHeight).toInt()
                        .coerceIn(0, buffer.rows - 1)
                    buffer.selectionEnd = row to col
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!longPressDetected) {
                    buffer.selectionStart = null
                    buffer.selectionEnd = null
                    invalidate()
                }
                isDraggingSelection = false
                longPressDetected = false
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                session?.sendEnter()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                session?.sendBackspace()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                session?.sendDelete()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                session?.sendTab()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.isShiftPressed) {
                    scrollUp()
                } else {
                    session?.sendArrowKey(TerminalSession.ArrowDirection.UP)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.isShiftPressed) {
                    scrollDown()
                } else {
                    session?.sendArrowKey(TerminalSession.ArrowDirection.DOWN)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                session?.sendArrowKey(TerminalSession.ArrowDirection.LEFT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                session?.sendArrowKey(TerminalSession.ArrowDirection.RIGHT)
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                session?.sendHome()
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                session?.sendEnd()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                session?.sendPageUp()
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                session?.sendPageDown()
                return true
            }
            else -> {
                val ch = event.unicodeChar.toChar()
                if (ch != '\u0000') {
                    if (event.isCtrlPressed) {
                        session?.sendCtrl(ch)
                    } else {
                        session?.write(ch.toString())
                    }
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    // ---- Scrolling ----

    fun scrollUp() {
        scrollOffset = min(scrollOffset + 1, buffer.scrollbackSize)
        invalidate()
    }

    fun scrollDown() {
        scrollOffset = max(scrollOffset - 1, 0)
        invalidate()
    }

    fun scrollToBottom() {
        scrollOffset = 0
        invalidate()
    }

    // ---- Clipboard ----

    fun copySelection() {
        val start = buffer.selectionStart ?: return
        val end = buffer.selectionEnd ?: return
        val (startRow, startCol) = start
        val (endRow, endCol) = end

        val sb = StringBuilder()
        for (row in startRow..endRow) {
            val line = buffer.getLineText(row)
            val from = if (row == startRow) startCol else 0
            val to = if (row == endRow) endCol else line.length
            if (from < line.length) {
                sb.append(line.substring(from, min(to, line.length)))
            }
            if (row != endRow) sb.append("\n")
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("TermX", sb.toString().trimEnd()))

        // Clear selection
        buffer.selectionStart = null
        buffer.selectionEnd = null
        invalidate()
    }

    fun paste() {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0)?.text?.toString() ?: return
            session?.write(text)
        }
    }

    // ---- Lifecycle ----

    fun resume() {
        postDelayed(cursorBlinkRunnable, 500)
    }

    fun pause() {
        removeCallbacks(cursorBlinkRunnable)
    }

    fun setFontSize(size: Float) {
        fontSize = size
        updateFont()
        invalidate()
    }

    /**
     * InputConnection for soft keyboard integration.
     */
    private class TerminalInputConnection(
        private val terminalView: TerminalView
    ) : BaseInputConnection(terminalView, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            terminalView.session?.write(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                repeat(beforeLength) { terminalView.session?.sendBackspace() }
            }
            if (afterLength > 0) {
                repeat(afterLength) { terminalView.session?.sendDelete() }
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                terminalView.onKeyDown(event.keyCode, event)
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            when (actionCode) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_NEXT -> {
                    terminalView.session?.sendEnter()
                    return true
                }
            }
            return false
        }
    }
}
