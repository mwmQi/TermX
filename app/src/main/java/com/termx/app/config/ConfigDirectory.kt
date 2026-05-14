package com.termx.app.config

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the ~/.termx/ directory structure with all config files.
 * Also supports ~/.termux/ for Termux compatibility.
 */
object ConfigDirectory {

    private const val TAG = "ConfigDirectory"
    private const val TERMX_DIR = ".termx"
    private const val TERMUX_DIR = ".termux"

    /**
     * Initialize the config directory structure.
     */
    fun init(context: Context) {
        // Create TermX directory
        val termxDir = File(context.filesDir, TERMX_DIR)
        val dirs = listOf("", "boot", "shortcut", "colors")
        for (dir in dirs) {
            val d = File(termxDir, dir)
            if (!d.exists()) d.mkdirs()
        }

        // Create Termux compat directory
        val termuxDir = File(context.filesDir, TERMUX_DIR)
        for (dir in listOf("", "boot", "shortcut")) {
            val d = File(termuxDir, dir)
            if (!d.exists()) d.mkdirs()
        }

        // Generate default properties if needed
        val props = TermXProperties(context)
        props.ensureDefaultConfig()

        // Create sample boot script
        val bootScript = File(termxDir, "boot/README")
        if (!bootScript.exists()) {
            bootScript.writeText("""
# TermX Boot Scripts
# Place executable scripts here to run on device boot.
# Scripts must be executable: chmod +x scriptname
#
# Example:
# #!/system/bin/sh
# echo "Boot script ran at $(date)" >> ~/boot.log
""".trimIndent())
        }

        // Create sample shortcut
        val shortcutReadme = File(termxDir, "shortcut/README")
        if (!shortcutReadme.exists()) {
            shortcutReadme.writeText("""
# TermX Shortcuts
# Place executable scripts here. They will appear in the widget.
# Scripts must be executable: chmod +x scriptname
#
# The script name (without extension) will be shown in the widget.
#
# Example: ~/.termx/shortcut/hello
# #!/system/bin/sh
# echo "Hello from TermX!"
""".trimIndent())
        }

        // Create custom colors README
        val colorsReadme = File(termxDir, "colors/README")
        if (!colorsReadme.exists()) {
            colorsReadme.writeText("""
# TermX Custom Colors
# Place .properties files here to add custom color themes.
# File format (same as Termux colors.properties):
#
# background=#1E1E2E
# foreground=#CDD6F4
# cursor=#F5E0DC
# color0=#45475A
# color1=#F38BA8
# color2=#A6E3A1
# color3=#F9E2AF
# color4=#89B4FA
# color5=#F5C2E7
# color6=#94E2D5
# color7=#BAC2DE
# color8=#585B70
# color9=#F38BA8
# color10=#A6E3A1
# color11=#F9E2AF
# color12=#89B4FA
# color13=#F5C2E7
# color14=#94E2D5
# color15=#A6ADC8
""".trimIndent())
        }

        Log.i(TAG, "Config directories initialized")
    }

    /**
     * Get list of boot scripts.
     */
    fun getBootScripts(context: Context): List<File> {
        val scripts = mutableListOf<File>()
        for (dirName in listOf(TERMX_DIR, TERMUX_DIR)) {
            val bootDir = File(context.filesDir, "$dirName/boot")
            if (bootDir.exists()) {
                bootDir.listFiles()?.filter { it.isFile && it.canExecute() }?.let { scripts.addAll(it) }
            }
        }
        return scripts.sortedBy { it.name }
    }

    /**
     * Get list of shortcut scripts (for widget).
     */
    fun getShortcutScripts(context: Context): List<File> {
        val scripts = mutableListOf<File>()
        for (dirName in listOf(TERMX_DIR, TERMUX_DIR)) {
            val shortcutDir = File(context.filesDir, "$dirName/shortcut")
            if (shortcutDir.exists()) {
                shortcutDir.listFiles()?.filter { it.isFile && it.canExecute() }?.let { scripts.addAll(it) }
            }
        }
        return scripts.sortedBy { it.name }
    }

    /**
     * Load custom color themes from ~/.termx/colors/ directory.
     */
    fun loadCustomColors(context: Context): Map<String, Map<String, Int>> {
        val themes = mutableMapOf<String, Map<String, Int>>()
        val colorsDir = File(context.filesDir, "$TERMX_DIR/colors")
        if (!colorsDir.exists()) return themes

        colorsDir.listFiles()?.filter { it.extension == "properties" }?.forEach { file ->
            try {
                val colors = mutableMapOf<String, Int>()
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val key = trimmed.substring(0, eq).trim()
                        val value = trimmed.substring(eq + 1).trim()
                        if (value.startsWith("#")) {
                            try {
                                colors[key] = android.graphics.Color.parseColor(value)
                            } catch (_: Exception) {}
                        }
                    }
                }
                if (colors.isNotEmpty()) {
                    themes[file.nameWithoutExtension] = colors
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load color theme: ${file.name}", e)
            }
        }

        return themes
    }
}
