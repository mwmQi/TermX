package com.termx.app.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * High-performance terminal renderer based on Termux's approach.
 * Draws text runs instead of individual characters to improve performance
 * and fix rendering glitches.
 */
class TerminalRenderer(private val textSize: Float, val typeface: Typeface) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = textSize
    }

    val fontWidth: Float = textPaint.measureText("M")
    val fontLineSpacing: Int = Math.ceil(textPaint.fontSpacing.toDouble()).toInt()
    private val fontAscent: Int = Math.ceil(textPaint.ascent().toDouble()).toInt()
    val fontLineSpacingAndAscent: Int = fontLineSpacing + fontAscent

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

        // Draw background is handled by the View
        
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
                        // We need the whole row. For performance, we should ideally
                        // have this in TerminalBuffer, but let's do this for now.
                        Array(columns) { col -> buffer.getCell(actualRow, col) }
                    } else null
                }
            } ?: continue

            drawTextRun(canvas, cells, colors, heightOffset, paddingLeft)
        }
    }

    private fun drawTextRun(
        canvas: Canvas,
        cells: Array<TerminalCell>,
        colors: TerminalColors,
        y: Float,
        paddingLeft: Float
    ) {
        var currentStyleCell: TerminalCell? = null
        var runStart = 0

        for (col in cells.indices) {
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
            drawRun(canvas, cells, runStart, cells.size, currentStyleCell, colors, y, paddingLeft)
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
        val runWidth = (end - start) * fontWidth
        val left = paddingLeft + start * fontWidth

        // Background
        var bg = if (style.inverse) style.fgColor else style.bgColor
        if (bg == 0) bg = if (style.inverse) colors.foreground else colors.background
        
        if (bg != colors.background) {
            textPaint.color = bg
            canvas.drawRect(left, y - fontLineSpacingAndAscent + fontAscent, left + runWidth, y, textPaint)
        }

        // Foreground
        var fg = if (style.inverse) style.bgColor else style.fgColor
        if (fg == 0) fg = if (style.inverse) colors.background else colors.foreground

        textPaint.color = fg
        textPaint.isFakeBoldText = style.bold
        textPaint.isUnderlineText = style.underline
        textPaint.isStrikeThruText = style.strikethrough
        textPaint.textSkewX = if (style.italic) -0.25f else 0f

        val text = StringBuilder()
        for (i in start until end) {
            val c = cells[i].char
            text.append(if (c == '\u0000' || c == ' ') ' ' else c)
        }
        
        // Only draw if not just spaces or has background
        if (text.any { it != ' ' }) {
            canvas.drawText(text.toString(), left, y - fontLineSpacingAndAscent, textPaint)
        }
    }
}
