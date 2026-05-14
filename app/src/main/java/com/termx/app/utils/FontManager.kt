package com.termx.app.utils

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Manages custom font loading for the terminal.
 * Supports loading .ttf and .otf fonts from:
 *   1. ~/.termux/font.ttf (Termux compatibility)
 *   2. ~/.termx/font.ttf
 *   3. Built-in fonts
 */
object FontManager {

    private const val TAG = "FontManager"

    // Built-in font names
    val BUILT_IN_FONTS = listOf(
        "Monospace (Default)",
        "Noto Sans Mono",
        "Source Code Pro",
        "Fira Code",
        "JetBrains Mono",
        "Cascadia Code",
        "Ubuntu Mono",
        "Inconsolata"
    )

    private var cachedTypeface: Typeface? = null
    private var cachedFontName: String? = null

    /**
     * Get the current terminal typeface.
     * Priority: custom font file > saved font preference > default monospace
     */
    fun getTypeface(context: Context): Typeface {
        val prefs = PreferenceManager.getInstance(context)

        // Check for custom font files (Termux-style)
        val customFont = loadCustomFontFile(context)
        if (customFont != null) {
            Log.d(TAG, "Using custom font file")
            return customFont
        }

        // Check saved font preference
        val fontName = prefs.fontType
        if (fontName != cachedFontName || cachedTypeface == null) {
            cachedTypeface = loadBuiltInFont(context, fontName)
            cachedFontName = fontName
        }

        return cachedTypeface ?: Typeface.MONOSPACE
    }

    /**
     * Load a custom font from ~/.termux/font.ttf or ~/.termx/font.ttf
     */
    private fun loadCustomFontFile(context: Context): Typeface? {
        // Check Termux-style location first
        val termuxFont = File(context.filesDir, ".termux/font.ttf")
        if (termuxFont.exists()) {
            try {
                return Typeface.createFromFile(termuxFont)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Termux font", e)
            }
        }

        // Check TermX location
        val termxFont = File(context.filesDir, ".termx/font.ttf")
        if (termxFont.exists()) {
            try {
                return Typeface.createFromFile(termxFont)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TermX font", e)
            }
        }

        // Also check .otf
        for (dir in listOf(File(context.filesDir, ".termux"), File(context.filesDir, ".termx"))) {
            for (ext in listOf(".ttf", ".otf")) {
                val fontFile = File(dir, "font$ext")
                if (fontFile.exists()) {
                    try {
                        return Typeface.createFromFile(fontFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load font: ${fontFile.path}", e)
                    }
                }
            }
        }

        return null
    }

    /**
     * Load a built-in font by name.
     */
    private fun loadBuiltInFont(context: Context, fontName: String): Typeface {
        return when (fontName) {
            "Monospace (Default)" -> Typeface.MONOSPACE
            "Noto Sans Mono" -> loadAssetFont(context, "fonts/NotoSansMono.ttf")
            "Source Code Pro" -> loadAssetFont(context, "fonts/SourceCodePro.ttf")
            "Fira Code" -> loadAssetFont(context, "fonts/FiraCode.ttf")
            "JetBrains Mono" -> loadAssetFont(context, "fonts/JetBrainsMono.ttf")
            "Cascadia Code" -> loadAssetFont(context, "fonts/CascadiaCode.ttf")
            "Ubuntu Mono" -> loadAssetFont(context, "fonts/UbuntuMono.ttf")
            "Inconsolata" -> loadAssetFont(context, "fonts/Inconsolata.ttf")
            else -> Typeface.MONOSPACE
        }
    }

    private fun loadAssetFont(context: Context, path: String): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, path)
        } catch (e: Exception) {
            Log.w(TAG, "Font not found in assets: $path, using MONOSPACE")
            Typeface.MONOSPACE
        }
    }

    /**
     * Install a custom font from a file path.
     * Copies it to ~/.termx/font.ttf
     */
    fun installCustomFont(context: Context, sourcePath: String): Boolean {
        return try {
            val termxDir = File(context.filesDir, ".termx")
            if (!termxDir.exists()) termxDir.mkdirs()

            val destFile = File(termxDir, "font.ttf")
            File(sourcePath).copyTo(destFile, overwrite = true)

            // Clear cache
            cachedTypeface = null
            cachedFontName = null

            Log.i(TAG, "Custom font installed: $sourcePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install custom font", e)
            false
        }
    }

    /**
     * Remove custom font, revert to default.
     */
    fun removeCustomFont(context: Context) {
        for (dir in listOf(File(context.filesDir, ".termux"), File(context.filesDir, ".termx"))) {
            for (ext in listOf(".ttf", ".otf")) {
                val fontFile = File(dir, "font$ext")
                if (fontFile.exists()) fontFile.delete()
            }
        }
        cachedTypeface = null
        cachedFontName = null
    }

    /**
     * Check if a custom font is installed.
     */
    fun hasCustomFont(context: Context): Boolean {
        return loadCustomFontFile(context) != null
    }

    /**
     * Get the custom font file path if one exists.
     */
    fun getCustomFontPath(context: Context): String? {
        for (dir in listOf(File(context.filesDir, ".termux"), File(context.filesDir, ".termx"))) {
            for (ext in listOf(".ttf", ".otf")) {
                val fontFile = File(dir, "font$ext")
                if (fontFile.exists()) return fontFile.absolutePath
            }
        }
        return null
    }
}
