package com.termx.app.terminal

/**
 * Terminal color scheme definition.
 * Supports 256-color and true-color (24-bit) terminal output.
 */
data class TerminalColors(
    val foreground: Int = 0xFFCCCCCC.toInt(),
    val background: Int = 0xFF1E1E2E.toInt(),
    val cursor: Int = 0xFFF5E0DC.toInt(),

    // Standard 16 ANSI colors
    val black: Int = 0xFF45475A.toInt(),
    val red: Int = 0xFFF38BA8.toInt(),
    val green: Int = 0xFFA6E3A1.toInt(),
    val yellow: Int = 0xFFF9E2AF.toInt(),
    val blue: Int = 0xFF89B4FA.toInt(),
    val magenta: Int = 0xFFF5C2E7.toInt(),
    val cyan: Int = 0xFF94E2D5.toInt(),
    val white: Int = 0xFFBAC2DE.toInt(),

    // Bright variants
    val brightBlack: Int = 0xFF585B70.toInt(),
    val brightRed: Int = 0xFFF38BA8.toInt(),
    val brightGreen: Int = 0xFFA6E3A1.toInt(),
    val brightYellow: Int = 0xFFF9E2AF.toInt(),
    val brightBlue: Int = 0xFF89B4FA.toInt(),
    val brightMagenta: Int = 0xFFF5C2E7.toInt(),
    val brightCyan: Int = 0xFF94E2D5.toInt(),
    val brightWhite: Int = 0xFFA6ADC8.toInt()
) {
    fun getAnsiColor(index: Int): Int {
        return when (index) {
            0 -> black
            1 -> red
            2 -> green
            3 -> yellow
            4 -> blue
            5 -> magenta
            6 -> cyan
            7 -> white
            8 -> brightBlack
            9 -> brightRed
            10 -> brightGreen
            11 -> brightYellow
            12 -> brightBlue
            13 -> brightMagenta
            14 -> brightCyan
            15 -> brightWhite
            else -> foreground
        }
    }

    companion object {
        /** Catppuccin Mocha theme (default) */
        fun catppuccinMocha() = TerminalColors()

        /** Dracula theme */
        fun dracula() = TerminalColors(
            foreground = 0xFFF8F8F2.toInt(),
            background = 0xFF282A36.toInt(),
            cursor = 0xFFF8F8F2.toInt(),
            black = 0xFF21222C.toInt(),
            red = 0xFFFF5555.toInt(),
            green = 0xFF50FA7B.toInt(),
            yellow = 0xFFF1FA8C.toInt(),
            blue = 0xFFBD93F9.toInt(),
            magenta = 0xFFFF79C6.toInt(),
            cyan = 0xFF8BE9FD.toInt(),
            white = 0xFFF8F8F2.toInt(),
            brightBlack = 0xFF6272A4.toInt(),
            brightRed = 0xFFFF6E6E.toInt(),
            brightGreen = 0xFF69FF94.toInt(),
            brightYellow = 0xFFFFA647.toInt(),
            brightBlue = 0xFFD6ACFF.toInt(),
            brightMagenta = 0xFFFF92DF.toInt(),
            brightCyan = 0xFFA4FFFF.toInt(),
            brightWhite = 0xFFFFFFFF.toInt()
        )

        /** Monokai theme */
        fun monokai() = TerminalColors(
            foreground = 0xFFF8F8F2.toInt(),
            background = 0xFF272822.toInt(),
            cursor = 0xFFF8F8F0.toInt(),
            black = 0xFF272822.toInt(),
            red = 0xFFF92672.toInt(),
            green = 0xFFA6E22E.toInt(),
            yellow = 0xFFF4BF75.toInt(),
            blue = 0xFF66D9EF.toInt(),
            magenta = 0xFFAE81FF.toInt(),
            cyan = 0xFFA1EFE4.toInt(),
            white = 0xFFF8F8F2.toInt(),
            brightBlack = 0xFF75715E.toInt(),
            brightRed = 0xFFFD971F.toInt(),
            brightGreen = 0xFFA6E22E.toInt(),
            brightYellow = 0xFFE6DB74.toInt(),
            brightBlue = 0xFF66D9EF.toInt(),
            brightMagenta = 0xFFAE81FF.toInt(),
            brightCyan = 0xFFA1EFE4.toInt(),
            brightWhite = 0xFFF9F8F5.toInt()
        )

        /** Solarized Dark theme */
        fun solarizedDark() = TerminalColors(
            foreground = 0xFF839496.toInt(),
            background = 0xFF002B36.toInt(),
            cursor = 0xFF93A1A1.toInt(),
            black = 0xFF073642.toInt(),
            red = 0xFFDC322F.toInt(),
            green = 0xFF859900.toInt(),
            yellow = 0xFFB58900.toInt(),
            blue = 0xFF268BD2.toInt(),
            magenta = 0xFFD33682.toInt(),
            cyan = 0xFF2AA198.toInt(),
            white = 0xFFEEE8D5.toInt(),
            brightBlack = 0xFF002B36.toInt(),
            brightRed = 0xFFCB4B16.toInt(),
            brightGreen = 0xFF586E75.toInt(),
            brightYellow = 0xFF657B83.toInt(),
            brightBlue = 0xFF839496.toInt(),
            brightMagenta = 0xFF6C71C4.toInt(),
            brightCyan = 0xFF93A1A1.toInt(),
            brightWhite = 0xFFFDF6E3.toInt()
        )

        /** Nord theme */
        fun nord() = TerminalColors(
            foreground = 0xFFD8DEE9.toInt(),
            background = 0xFF2E3440.toInt(),
            cursor = 0xFFD8DEE9.toInt(),
            black = 0xFF3B4252.toInt(),
            red = 0xFFBF616A.toInt(),
            green = 0xFFA3BE8C.toInt(),
            yellow = 0xFFEBCB8B.toInt(),
            blue = 0xFF81A1C1.toInt(),
            magenta = 0xFFB48EAD.toInt(),
            cyan = 0xFF88C0D0.toInt(),
            white = 0xFFE5E9F0.toInt(),
            brightBlack = 0xFF4C566A.toInt(),
            brightRed = 0xFFBF616A.toInt(),
            brightGreen = 0xFFA3BE8C.toInt(),
            brightYellow = 0xFFEBCB8B.toInt(),
            brightBlue = 0xFF81A1C1.toInt(),
            brightMagenta = 0xFFB48EAD.toInt(),
            brightCyan = 0xFF8FBCBB.toInt(),
            brightWhite = 0xFFECEFF4.toInt()
        )

        fun getAllThemes(): List<Pair<String, TerminalColors>> = listOf(
            "Catppuccin Mocha" to catppuccinMocha(),
            "Dracula" to dracula(),
            "Monokai" to monokai(),
            "Solarized Dark" to solarizedDark(),
            "Nord" to nord()
        )
    }
}
