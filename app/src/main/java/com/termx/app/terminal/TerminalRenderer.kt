package com.termx.app.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * High-performance terminal renderer based on Termux's approach.
 * Draws text runs using drawTextRun() for proper baseline positioning
 * and character width measurement.
 */
class TerminalRenderer(private val textSize: Float, val typeface: Typeface) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSize
    }

    val fontWidth: Float = textPaint.measureText("X")
    val fontLineSpacing: Int = Math.ceil(textPaint.fontSpacing.toDouble()).toInt()
    private val fontAscent: Int = Math.ceil(textPaint.ascent().toDouble()).toInt()
    val fontLineSpacingAndAscent: Int = fontLineSpacing + fontAscent

    // Per-character width cache for ASCII (like Termux)
    private val asciiMeasures = FloatArray(127)

    init {
        val sb = StringBuilder(" ")
        for (i in 0 until 127) {
            sb[0] = i.toChar()
            asciiMeasures[i] = textPaint.measureText(sb, 0, 1)
        }
    }

    fun render(
        buffer: TerminalBuffer,
        canvas: Canvas,
        colors: TerminalColors,
        scrollOffset: Int,
        paddingLeft: Float,
        paddingTop: Float
    ) {
        val rows = buffer.rows
        val columns = buffer.columns
        val startRow = buffer.scrollbackSize - scrollOffset

        var heightOffset = paddingTop + fontLineSpacingAndAscent

        for (screenRow in 0 until rows) {
            heightOffset += fontLineSpacing

            val cells = when {
                startRow + screenRow < 0 -> null
                startRow + screenRow < buffer.scrollbackSize -> {
                    buffer.getScrollbackLine(startRow + screenRow)
                }
                else -> {
                    val actualRow = startRow + screenRow - buffer.scrollbackSize
                    if (actualRow in 0 until buffer.rows) {
                        Array(columns) { col -> buffer.getCell(actualRow, col) }
                    } else null
                }
            } ?: continue

            drawTextRun(canvas, cells, columns, colors, heightOffset, paddingLeft)
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        cells: Array<TerminalCell>,
        columns: Int,
        colors: TerminalColors,
        y: Float,
        paddingLeft: Float
    ) {
        var currentStyleCell: TerminalCell? = null
        var runStart = 0

        for (col in 0 until columns) {
            val cell = cells[col]
            val styleMatches = currentStyleCell?.let {
                it.fgColor == cell.fgColor &&
                it.bgColor == cell.bgColor &&
                it.bold == cell.bold &&
                it.italic == cell.italic &&
                it.underline == cell.underline &&
                it.strikethrough == cell.strikethrough &&
                it.inverse == cell.inverse
            } ?: false

            if (!styleMatches) {
                if (currentStyleCell != null) {
                    drawRun(canvas, cells, runStart, col, currentStyleCell, colors, y, paddingLeft)
                }
                currentStyleCell = cell
                runStart = col
            }
        }

        if (currentStyleCell != null) {
            drawRun(canvas, cells, runStart, columns, currentStyleCell, colors, y, paddingLeft)
        }
    }

    private fun drawRun(
        canvas: Canvas,
        cells: Array<TerminalCell>,
        start: Int,
        end: Int,
        style: TerminalCell,
        colors: TerminalColors,
        y: Float,
        paddingLeft: Float
    ) {
        val left = paddingLeft + start * fontWidth
        val right = paddingLeft + end * fontWidth

        // Resolve background color
        var bg = if (style.inverse) style.fgColor else style.bgColor
        if (bg == 0) bg = if (style.inverse) colors.foreground else colors.background

        if (bg != colors.background) {
            textPaint.color = bg
            canvas.drawRect(
                left,
                y - fontLineSpacingAndAscent + fontAscent,
                right,
                y,
                textPaint
            )
        }

        // Resolve foreground color
        var fg = if (style.inverse) style.bgColor else style.fgColor
        if (fg == 0) fg = if (style.inverse) colors.background else colors.foreground

        // Apply text style
        textPaint.color = fg
        textPaint.isFakeBoldText = style.bold
        textPaint.isUnderlineText = style.underline
        textPaint.isStrikeThruText = style.strikethrough
        textPaint.textSkewX = if (style.italic) -0.35f else 0f

        // Build text and measure width
        val text = CharArray(end - start)
        var textLen = 0
        var measuredWidth = 0f

        for (i in start until end) {
            val c = cells[i].char
            val ch = if (c == '\u0000') ' ' else c
            text[textLen++] = ch

            // Measure using asciiMeasures cache if applicable
            val code = ch.code
            measuredWidth += if (code in 0 until 127) {
                asciiMeasures[code]
            } else {
                textPaint.measureText(ch.toString())
            }
        }

        if (textLen == 0) return

        // Check if measured width matches expected width (for scaling if needed)
        val expectedWidth = (end - start) * fontWidth
        val needsScaling = kotlin.math.abs(measuredWidth - expectedWidth) > 0.01f * expectedWidth

        if (needsScaling) {
            canvas.save()
            canvas.scale(expectedWidth / measuredWidth, 1f)
            val scaledLeft = left * measuredWidth / expectedWidth
            canvas.drawTextRun(
                text, 0, textLen,
                0, textLen,
                scaledLeft, y - fontLineSpacingAndAscent,
                false, textPaint
            )
            canvas.restore()
        } else {
            canvas.drawTextRun(
                text, 0, textLen,
                0, textLen,
                left, y - fontLineSpacingAndAscent,
                false, textPaint
            )
        }
    }
}
