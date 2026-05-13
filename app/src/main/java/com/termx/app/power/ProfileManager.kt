package com.termx.app.power

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.*

/**
 * Profile System for TermX — manage multiple shell profiles/environments.
 *
 * Each profile has its own:
 *   - Shell (bash, zsh, sh)
 *   - Home directory
 *   - PATH and environment variables
 *   - Startup scripts
 *   - Color theme
 *   - Font settings
 *   - Aliases
 *
 * Profiles are stored as JSON files in the app's private directory.
 * A "default" profile is created automatically on first use.
 *
 * Shell usage:
 *   termx-profile list                        List all profiles
 *   termx-profile create <name>               Create a new profile
 *   termx-profile delete <name>               Delete a profile
 *   termx-profile switch <name>               Switch to a profile
 *   termx-profile current                     Show current profile
 *   termx-profile show <name>                 Show profile settings
 *   termx-profile env <name>                  Show profile environment
 *   termx-profile set <name> <key> <value>    Set profile variable
 *   termx-profile get <name> <key>            Get profile variable
 *   termx-profile export <name>               Export profile config
 *   termx-profile import <path>               Import profile config
 *   termx-profile clone <src> <dst>           Clone a profile
 *   termx-profile rename <old> <new>          Rename a profile
 *   termx-profile reset <name>                Reset to defaults
 */
object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val PROFILES_DIR = "profiles"
    private const val CURRENT_PROFILE_FILE = "current_profile"
    private const val PROFILE_EXTENSION = ".json"
    private const val DEFAULT_PROFILE = "default"

    // Profile setting keys
    private const val KEY_NAME = "name"
    private const val KEY_SHELL = "shell"
    private const val KEY_HOME = "home"
    private const val KEY_PATH = "path"
    private const val KEY_ENV = "environment"
    private const val KEY_ALIASES = "aliases"
    private const val KEY_STARTUP_SCRIPT = "startup_script"
    private const val KEY_THEME = "theme"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_FONT_NAME = "font_name"
    private const val KEY_CURSOR_STYLE = "cursor_style"
    private const val KEY_BELL = "bell_enabled"
    private const val KEY_SCROLLBACK = "scrollback_lines"
    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_IMPORTED_AT = "imported_at"

    // Default color themes
    private val THEMES = mapOf(
        "dark" to mapOf(
            "foreground" to "#CCCCCC",
            "background" to "#1E1E1E",
            "cursor" to "#FFFFFF"
        ),
        "light" to mapOf(
            "foreground" to "#333333",
            "background" to "#FFFFFF",
            "cursor" to "#000000"
        ),
        "solarized-dark" to mapOf(
            "foreground" to "#839496",
            "background" to "#002B36",
            "cursor" to "#93A1A1"
        ),
        "solarized-light" to mapOf(
            "foreground" to "#657B83",
            "background" to "#FDF6E3",
            "cursor" to "#586E75"
        ),
        "monokai" to mapOf(
            "foreground" to "#F8F8F2",
            "background" to "#272822",
            "cursor" to "#F8F8F0"
        ),
        "dracula" to mapOf(
            "foreground" to "#F8F8F2",
            "background" to "#282A36",
            "cursor" to "#F8F8F2"
        ),
        "nord" to mapOf(
            "foreground" to "#D8DEE9",
            "background" to "#2E3440",
            "cursor" to "#D8DEE9"
        ),
        "gruvbox" to mapOf(
            "foreground" to "#EBDBB2",
            "background" to "#282828",
            "cursor" to "#EBDBB2"
        )
    )

    // =========================================================================
    // Main command dispatcher
    // =========================================================================

    /**
     * Execute a termx-profile command.
     *
     * @param context Android context
     * @param args    Command arguments (first element is subcommand)
     * @return Result string to be printed to terminal
     */
    fun execute(context: Context, args: List<String>): String {
        if (args.isEmpty()) return getHelpText()

        return try {
            when (args[0]) {
                "list" -> listProfiles(context)
                "create" -> createProfile(context, args)
                "delete" -> deleteProfile(context, args)
                "switch" -> switchProfile(context, args)
                "current" -> currentProfile(context)
                "show" -> showProfile(context, args)
                "env" -> showEnvironment(context, args)
                "set" -> setVariable(context, args)
                "get" -> getVariable(context, args)
                "export" -> exportProfile(context, args)
                "import" -> importProfile(context, args)
                "clone" -> cloneProfile(context, args)
                "rename" -> renameProfile(context, args)
                "reset" -> resetProfile(context, args)
                "add-alias" -> addAlias(context, args)
                "remove-alias" -> removeAlias(context, args)
                "list-aliases" -> listAliases(context, args)
                "add-path" -> addPath(context, args)
                "remove-path" -> removePath(context, args)
                "set-theme" -> setTheme(context, args)
                "list-themes" -> listThemes()
                "help" -> getHelpText()
                else -> "Unknown command: ${args[0]}\n${getHelpText()}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${args[0]}", e)
            "Error: ${e.message}"
        }
    }

    // =========================================================================
    // Profile CRUD Operations
    // =========================================================================

    /**
     * List all available profiles.
     */
    private fun listProfiles(context: Context): String {
        val profilesDir = getProfilesDir(context)
        val currentName = getCurrentProfileName(context)

        if (!profilesDir.exists() || profilesDir.listFiles().isNullOrEmpty()) {
            // Ensure default profile exists
            ensureDefaultProfile(context)
        }

        val sb = StringBuilder()
        sb.appendLine("TermX Profiles")
        sb.appendLine("═".repeat(50))

        val profileFiles = profilesDir.listFiles()
            ?.filter { it.name.endsWith(PROFILE_EXTENSION) }
            ?.sortedBy { it.name }

        if (profileFiles.isNullOrEmpty()) {
            sb.appendLine("  (no profiles found)")
            return sb.toString()
        }

        profileFiles.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val name = json.optString(KEY_NAME, file.nameWithoutExtension)
                val shell = json.optString(KEY_SHELL, "bash")
                val isActive = name == currentName
                val marker = if (isActive) " * " else "   "
                val createdAt = json.optString(KEY_CREATED_AT, "unknown")

                sb.appendLine("${marker}$name")
                sb.appendLine("     Shell: $shell | Created: $createdAt")
            } catch (e: Exception) {
                sb.appendLine("   ${file.nameWithoutExtension} (error reading profile)")
            }
        }

        sb.appendLine()
        sb.appendLine("  * = active profile")

        return sb.toString()
    }

    /**
     * Create a new profile.
     *
     * Usage: termx-profile create <name>
     */
    private fun createProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile create <name>"

        val name = args[1].sanitizeName()

        if (!isValidName(name)) {
            return "Error: Invalid profile name '$name'. Use alphanumeric characters, hyphens, and underscores only."
        }

        val profileFile = getProfileFile(context, name)
        if (profileFile.exists()) {
            return "Error: Profile '$name' already exists"
        }

        try {
            val profile = createDefaultProfileJson(name)
            saveProfile(context, name, profile)

            // Create profile home directory
            val homeDir = File(getProfilesDir(context), "$name/home")
            homeDir.mkdirs()

            // Create startup script file
            val rcFile = File(getProfilesDir(context), "$name/.bashrc")
            rcFile.writeText("# ~/.bashrc for TermX profile: $name\n# Add your custom commands here\n\n")

            return "Profile '$name' created successfully"
        } catch (e: Exception) {
            return "Failed to create profile: ${e.message}"
        }
    }

    /**
     * Delete a profile. Cannot delete the currently active profile or 'default'.
     *
     * Usage: termx-profile delete <name>
     */
    private fun deleteProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile delete <name>"

        val name = args[1].sanitizeName()
        val currentName = getCurrentProfileName(context)

        if (name == DEFAULT_PROFILE) {
            return "Error: Cannot delete the default profile"
        }

        if (name == currentName) {
            return "Error: Cannot delete the currently active profile. Switch to another profile first."
        }

        val profileFile = getProfileFile(context, name)
        if (!profileFile.exists()) {
            return "Error: Profile '$name' not found"
        }

        try {
            // Delete profile config
            profileFile.delete()

            // Delete profile home directory
            val profileDir = File(getProfilesDir(context), name)
            if (profileDir.exists()) {
                profileDir.deleteRecursively()
            }

            return "Profile '$name' deleted"
        } catch (e: Exception) {
            return "Failed to delete profile: ${e.message}"
        }
    }

    /**
     * Switch to a different profile.
     *
     * Usage: termx-profile switch <name>
     */
    private fun switchProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile switch <name>"

        val name = args[1].sanitizeName()

        val profileFile = getProfileFile(context, name)
        if (!profileFile.exists()) {
            return "Error: Profile '$name' not found. Use 'termx-profile list' to see available profiles."
        }

        try {
            // Save current profile name
            val currentFile = File(context.filesDir, CURRENT_PROFILE_FILE)
            currentFile.writeText(name)

            // Update the profile's updated_at timestamp
            val profile = loadProfile(context, name)
            if (profile != null) {
                profile.put(KEY_UPDATED_AT, currentDate())
                saveProfile(context, name, profile)
            }

            return buildString {
                appendLine("Switched to profile '$name'")
                appendLine("Restart terminal sessions to apply changes")
            }
        } catch (e: Exception) {
            return "Failed to switch profile: ${e.message}"
        }
    }

    /**
     * Show the current profile name.
     */
    private fun currentProfile(context: Context): String {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name)

        return if (profile != null) {
            buildString {
                appendLine("Current profile: $name")
                appendLine("  Shell:  ${profile.optString(KEY_SHELL, "bash")}")
                appendLine("  Theme:  ${profile.optString(KEY_THEME, "dark")}")
                appendLine("  Font:   ${profile.optString(KEY_FONT_NAME, "Monospace")} ${profile.optInt(KEY_FONT_SIZE, 14)}pt")
            }
        } else {
            "Current profile: $name"
        }
    }

    /**
     * Show detailed profile settings.
     *
     * Usage: termx-profile show <name>
     */
    private fun showProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile show <name>"

        val name = args[1].sanitizeName()
        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        val currentName = getCurrentProfileName(context)
        val isActive = name == currentName

        val sb = StringBuilder()
        sb.appendLine("Profile: $name ${if (isActive) "(active)" else ""}")
        sb.appendLine("═".repeat(50))

        sb.appendLine("  Shell:           ${profile.optString(KEY_SHELL, "bash")}")
        sb.appendLine("  Home Directory:  ${profile.optString(KEY_HOME, "~/")}")
        sb.appendLine("  Theme:           ${profile.optString(KEY_THEME, "dark")}")
        sb.appendLine("  Font:            ${profile.optString(KEY_FONT_NAME, "Monospace")}")
        sb.appendLine("  Font Size:       ${profile.optInt(KEY_FONT_SIZE, 14)}")
        sb.appendLine("  Cursor Style:    ${profile.optString(KEY_CURSOR_STYLE, "block")}")
        sb.appendLine("  Bell:            ${if (profile.optBoolean(KEY_BELL, true)) "enabled" else "disabled"}")
        sb.appendLine("  Scrollback:      ${profile.optInt(KEY_SCROLLBACK, 10000)} lines")
        sb.appendLine("  Startup Script:  ${profile.optString(KEY_STARTUP_SCRIPT, "~/.bashrc")}")
        sb.appendLine("  Created:         ${profile.optString(KEY_CREATED_AT, "unknown")}")
        sb.appendLine("  Updated:         ${profile.optString(KEY_UPDATED_AT, "unknown")}")

        // PATH
        sb.appendLine()
        sb.appendLine("  PATH:")
        val pathArray = profile.optJSONArray(KEY_PATH)
        if (pathArray != null && pathArray.length() > 0) {
            for (i in 0 until pathArray.length()) {
                sb.appendLine("    ${pathArray.getString(i)}")
            }
        } else {
            sb.appendLine("    (using system default)")
        }

        // Environment variables
        sb.appendLine()
        sb.appendLine("  Environment Variables:")
        val envObj = profile.optJSONObject(KEY_ENV)
        if (envObj != null && envObj.length() > 0) {
<<<<<<< HEAD
            val keys = envObj.keys().asSequence().toList().sorted()
            keys.forEach { key: String ->
=======
            val keys = Iterable { envObj.keys() }.toList().sorted()
            keys.forEach { key ->
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                sb.appendLine("    $key=${envObj.getString(key)}")
            }
        } else {
            sb.appendLine("    (none)")
        }

        // Aliases
        sb.appendLine()
        sb.appendLine("  Aliases:")
        val aliasObj = profile.optJSONObject(KEY_ALIASES)
        if (aliasObj != null && aliasObj.length() > 0) {
<<<<<<< HEAD
            val keys = aliasObj.keys().asSequence().toList().sorted()
            keys.forEach { key: String ->
=======
            val keys = Iterable { aliasObj.keys() }.toList().sorted()
            keys.forEach { key ->
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                sb.appendLine("    alias $key='${aliasObj.getString(key)}'")
            }
        } else {
            sb.appendLine("    (none)")
        }

        return sb.toString()
    }

    // =========================================================================
    // Environment Variables
    // =========================================================================

    /**
     * Show environment variables for a profile.
     *
     * Usage: termx-profile env <name>
     */
    private fun showEnvironment(context: Context, args: List<String>): String {
        val name = if (args.size >= 2) args[1].sanitizeName() else getCurrentProfileName(context)
        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        val sb = StringBuilder()
        sb.appendLine("Environment for profile '$name':")
        sb.appendLine("─".repeat(40))

        // Core variables
        sb.appendLine("  SHELL=${profile.optString(KEY_SHELL, "bash")}")
        sb.appendLine("  HOME=${profile.optString(KEY_HOME, "~/")}")

        // PATH
        val pathArray = profile.optJSONArray(KEY_PATH)
        if (pathArray != null && pathArray.length() > 0) {
            val pathStr = (0 until pathArray.length()).joinToString(":") { pathArray.getString(it) }
            sb.appendLine("  PATH=$pathStr")
        }

        // Custom environment
        val envObj = profile.optJSONObject(KEY_ENV)
        if (envObj != null) {
<<<<<<< HEAD
            envObj.keys().asSequence().toList().sorted().forEach { key: String ->
=======
            Iterable { envObj.keys() }.toList().sorted().forEach { key ->
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                sb.appendLine("  $key=${envObj.getString(key)}")
            }
        }

        return sb.toString()
    }

    /**
     * Set a profile variable.
     *
     * Usage: termx-profile set <name> <key> <value>
     */
    private fun setVariable(context: Context, args: List<String>): String {
        if (args.size < 4) return "Usage: termx-profile set <name> <key> <value>"

        val name = args[1].sanitizeName()
        val key = args[2]
        val value = args[3]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            when (key.lowercase()) {
                "shell" -> profile.put(KEY_SHELL, value)
                "home" -> profile.put(KEY_HOME, value)
                "theme" -> {
                    if (!THEMES.containsKey(value.lowercase())) {
                        return "Unknown theme: $value\nAvailable themes: ${THEMES.keys.joinToString(", ")}"
                    }
                    profile.put(KEY_THEME, value.lowercase())
                }
                "font-name", "font" -> profile.put(KEY_FONT_NAME, value)
                "font-size" -> {
                    val size = value.toIntOrNull()
                    if (size == null || size < 6 || size > 32) {
                        return "Invalid font size: $value (must be 6-32)"
                    }
                    profile.put(KEY_FONT_SIZE, size)
                }
                "cursor-style", "cursor" -> {
                    val validCursors = listOf("block", "underline", "bar")
                    if (value.lowercase() !in validCursors) {
                        return "Invalid cursor style: $value (valid: ${validCursors.joinToString(", ")})"
                    }
                    profile.put(KEY_CURSOR_STYLE, value.lowercase())
                }
                "bell" -> profile.put(KEY_BELL, value.lowercase() in listOf("true", "1", "yes", "on"))
                "scrollback" -> {
                    val lines = value.toIntOrNull()
                    if (lines == null || lines < 100) {
                        return "Invalid scrollback: $value (must be >= 100)"
                    }
                    profile.put(KEY_SCROLLBACK, lines)
                }
                "startup-script" -> profile.put(KEY_STARTUP_SCRIPT, value)
                else -> {
                    // Store as custom environment variable
                    val envObj = profile.optJSONObject(KEY_ENV) ?: JSONObject()
                    envObj.put(key, value)
                    profile.put(KEY_ENV, envObj)
                }
            }

            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            return "Set $key=$value for profile '$name'"
        } catch (e: Exception) {
            return "Failed to set variable: ${e.message}"
        }
    }

    /**
     * Get a profile variable.
     *
     * Usage: termx-profile get <name> <key>
     */
    private fun getVariable(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile get <name> <key>"

        val name = args[1].sanitizeName()
        val key = args[2]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        val value = when (key.lowercase()) {
            "shell" -> profile.optString(KEY_SHELL, "bash")
            "home" -> profile.optString(KEY_HOME, "~/")
            "theme" -> profile.optString(KEY_THEME, "dark")
            "font-name", "font" -> profile.optString(KEY_FONT_NAME, "Monospace")
            "font-size" -> profile.optInt(KEY_FONT_SIZE, 14).toString()
            "cursor-style", "cursor" -> profile.optString(KEY_CURSOR_STYLE, "block")
            "bell" -> profile.optBoolean(KEY_BELL, true).toString()
            "scrollback" -> profile.optInt(KEY_SCROLLBACK, 10000).toString()
            "startup-script" -> profile.optString(KEY_STARTUP_SCRIPT, "~/.bashrc")
            else -> {
                // Check custom environment variables
                val envObj = profile.optJSONObject(KEY_ENV)
                envObj?.optString(key) ?: "(not set)"
            }
        }

        return value
    }

    // =========================================================================
    // Alias Management
    // =========================================================================

    /**
     * Add an alias to a profile.
     *
     * Usage: termx-profile add-alias <name> <alias> <command>
     */
    private fun addAlias(context: Context, args: List<String>): String {
        if (args.size < 4) return "Usage: termx-profile add-alias <name> <alias> <command>"

        val name = args[1].sanitizeName()
        val alias = args[2]
        val command = args[3]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            val aliasObj = profile.optJSONObject(KEY_ALIASES) ?: JSONObject()
            aliasObj.put(alias, command)
            profile.put(KEY_ALIASES, aliasObj)
            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            // Also update the .bashrc file
            appendAliasToBashrc(context, name, alias, command)

            return "Alias added: $alias='$command' for profile '$name'"
        } catch (e: Exception) {
            return "Failed to add alias: ${e.message}"
        }
    }

    /**
     * Remove an alias from a profile.
     *
     * Usage: termx-profile remove-alias <name> <alias>
     */
    private fun removeAlias(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile remove-alias <name> <alias>"

        val name = args[1].sanitizeName()
        val alias = args[2]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            val aliasObj = profile.optJSONObject(KEY_ALIASES)
            if (aliasObj == null || !aliasObj.has(alias)) {
                return "Error: Alias '$alias' not found in profile '$name'"
            }

            aliasObj.remove(alias)
            profile.put(KEY_ALIASES, aliasObj)
            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            return "Alias '$alias' removed from profile '$name'"
        } catch (e: Exception) {
            return "Failed to remove alias: ${e.message}"
        }
    }

    /**
     * List aliases for a profile.
     *
     * Usage: termx-profile list-aliases <name>
     */
    private fun listAliases(context: Context, args: List<String>): String {
        val name = if (args.size >= 2) args[1].sanitizeName() else getCurrentProfileName(context)
        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        val aliasObj = profile.optJSONObject(KEY_ALIASES)
        if (aliasObj == null || aliasObj.length() == 0) {
            return "No aliases defined for profile '$name'"
        }

        val sb = StringBuilder()
        sb.appendLine("Aliases for profile '$name':")
        sb.appendLine("─".repeat(40))

<<<<<<< HEAD
        aliasObj.keys().asSequence().toList().sorted().forEach { alias: String ->
=======
        Iterable { aliasObj.keys() }.toList().sorted().forEach { alias ->
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
            sb.appendLine("  alias $alias='${aliasObj.getString(alias)}'")
        }

        return sb.toString()
    }

    // =========================================================================
    // PATH Management
    // =========================================================================

    /**
     * Add a directory to the profile's PATH.
     *
     * Usage: termx-profile add-path <name> <directory>
     */
    private fun addPath(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile add-path <name> <directory>"

        val name = args[1].sanitizeName()
        val directory = args[2]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            val pathArray = profile.optJSONArray(KEY_PATH) ?: org.json.JSONArray()

            // Check for duplicate
            for (i in 0 until pathArray.length()) {
                if (pathArray.getString(i) == directory) {
                    return "Path '$directory' already exists in profile '$name'"
                }
            }

            pathArray.put(directory)
            profile.put(KEY_PATH, pathArray)
            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            return "Added '$directory' to PATH for profile '$name'"
        } catch (e: Exception) {
            return "Failed to add path: ${e.message}"
        }
    }

    /**
     * Remove a directory from the profile's PATH.
     *
     * Usage: termx-profile remove-path <name> <directory>
     */
    private fun removePath(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile remove-path <name> <directory>"

        val name = args[1].sanitizeName()
        val directory = args[2]

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            val pathArray = profile.optJSONArray(KEY_PATH)
            if (pathArray == null) return "PATH is empty for profile '$name'"

            val newPathArray = org.json.JSONArray()
            var found = false

            for (i in 0 until pathArray.length()) {
                val entry = pathArray.getString(i)
                if (entry != directory) {
                    newPathArray.put(entry)
                } else {
                    found = true
                }
            }

            if (!found) return "Path '$directory' not found in profile '$name'"

            profile.put(KEY_PATH, newPathArray)
            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            return "Removed '$directory' from PATH for profile '$name'"
        } catch (e: Exception) {
            return "Failed to remove path: ${e.message}"
        }
    }

    // =========================================================================
    // Theme Management
    // =========================================================================

    /**
     * Set the color theme for a profile.
     *
     * Usage: termx-profile set-theme <name> <theme>
     */
    private fun setTheme(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile set-theme <name> <theme>\nAvailable themes: ${THEMES.keys.joinToString(", ")}"

        val name = args[1].sanitizeName()
        val theme = args[2].lowercase()

        if (!THEMES.containsKey(theme)) {
            return "Unknown theme: $theme\nAvailable themes: ${THEMES.keys.joinToString(", ")}"
        }

        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            profile.put(KEY_THEME, theme)
            profile.put(KEY_UPDATED_AT, currentDate())
            saveProfile(context, name, profile)

            return "Theme set to '$theme' for profile '$name'"
        } catch (e: Exception) {
            return "Failed to set theme: ${e.message}"
        }
    }

    /**
     * List available themes.
     */
    private fun listThemes(): String {
        val sb = StringBuilder()
        sb.appendLine("Available Themes")
        sb.appendLine("═".repeat(50))

        THEMES.forEach { (name, colors) ->
            sb.appendLine("  $name")
            colors.forEach { (key, value) ->
                sb.appendLine("    $key: $value")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    // =========================================================================
    // Import / Export
    // =========================================================================

    /**
     * Export a profile configuration to a JSON file.
     *
     * Usage: termx-profile export <name>
     */
    private fun exportProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile export <name>"

        val name = args[1].sanitizeName()
        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            val exportPath = if (args.size >= 3) {
                args[2]
            } else {
                "/sdcard/termx-profile-$name.json"
            }

            val exportFile = File(exportPath)
            exportFile.parentFile?.mkdirs()

            // Add export metadata
            val exportObj = JSONObject(profile.toString())
            exportObj.put("exported_at", currentDate())
            exportObj.put("exported_by", "TermX")
            exportObj.put("version", 1)

            exportFile.writeText(exportObj.toString(2))

            return "Profile '$name' exported to $exportPath"
        } catch (e: Exception) {
            return "Export failed: ${e.message}"
        }
    }

    /**
     * Import a profile configuration from a JSON file.
     *
     * Usage: termx-profile import <path>
     */
    private fun importProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile import <path>"

        val importPath = args[1]
        val importFile = File(importPath)

        if (!importFile.exists()) {
            return "Error: Import file not found: $importPath"
        }

        try {
            val jsonStr = importFile.readText()
            val profile = JSONObject(jsonStr)

            // Validate minimum required fields
            val name = profile.optString(KEY_NAME, "").sanitizeName()
            if (name.isEmpty()) {
                return "Error: Invalid profile file — missing profile name"
            }

            if (getProfileFile(context, name).exists()) {
                return "Error: Profile '$name' already exists. Rename the existing profile first."
            }

            // Update timestamps
            profile.put(KEY_IMPORTED_AT, currentDate())
            profile.put(KEY_UPDATED_AT, currentDate())

            saveProfile(context, name, profile)

            // Create profile directory structure
            val homeDir = File(getProfilesDir(context), "$name/home")
            homeDir.mkdirs()

            return "Profile '$name' imported from $importPath"
        } catch (e: Exception) {
            return "Import failed: ${e.message}"
        }
    }

    // =========================================================================
    // Clone / Rename / Reset
    // =========================================================================

    /**
     * Clone a profile to a new name.
     *
     * Usage: termx-profile clone <src> <dst>
     */
    private fun cloneProfile(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile clone <src> <dst>"

        val srcName = args[1].sanitizeName()
        val dstName = args[2].sanitizeName()

        val srcProfile = loadProfile(context, srcName)
            ?: return "Error: Source profile '$srcName' not found"

        if (getProfileFile(context, dstName).exists()) {
            return "Error: Destination profile '$dstName' already exists"
        }

        try {
            // Clone the profile with a new name
            val clonedProfile = JSONObject(srcProfile.toString())
            clonedProfile.put(KEY_NAME, dstName)
            clonedProfile.put(KEY_CREATED_AT, currentDate())
            clonedProfile.put(KEY_UPDATED_AT, currentDate())

            // Remove import/export metadata from clone
            clonedProfile.remove("exported_at")
            clonedProfile.remove("exported_by")
            clonedProfile.remove("imported_at")

            saveProfile(context, dstName, clonedProfile)

            // Clone profile directory structure
            val srcDir = File(getProfilesDir(context), srcName)
            val dstDir = File(getProfilesDir(context), dstName)
            if (srcDir.exists()) {
                srcDir.copyRecursively(dstDir, overwrite = false)
            }

            return "Profile '$srcName' cloned to '$dstName'"
        } catch (e: Exception) {
            return "Clone failed: ${e.message}"
        }
    }

    /**
     * Rename a profile.
     *
     * Usage: termx-profile rename <old> <new>
     */
    private fun renameProfile(context: Context, args: List<String>): String {
        if (args.size < 3) return "Usage: termx-profile rename <old> <new>"

        val oldName = args[1].sanitizeName()
        val newName = args[2].sanitizeName()

        if (!isValidName(newName)) {
            return "Error: Invalid profile name '$newName'"
        }

        val oldProfile = loadProfile(context, oldName)
            ?: return "Error: Profile '$oldName' not found"

        if (getProfileFile(context, newName).exists()) {
            return "Error: Profile '$newName' already exists"
        }

        try {
            // Update profile with new name
            oldProfile.put(KEY_NAME, newName)
            oldProfile.put(KEY_UPDATED_AT, currentDate())

            // Save with new name
            saveProfile(context, newName, oldProfile)

            // Rename profile directory
            val oldDir = File(getProfilesDir(context), oldName)
            val newDir = File(getProfilesDir(context), newName)
            if (oldDir.exists()) {
                oldDir.renameTo(newDir)
            }

            // Delete old profile file
            getProfileFile(context, oldName).delete()

            // Update current profile reference if renaming the active profile
            val currentName = getCurrentProfileName(context)
            if (currentName == oldName) {
                File(context.filesDir, CURRENT_PROFILE_FILE).writeText(newName)
            }

            return "Profile '$oldName' renamed to '$newName'"
        } catch (e: Exception) {
            return "Rename failed: ${e.message}"
        }
    }

    /**
     * Reset a profile to default settings.
     *
     * Usage: termx-profile reset <name>
     */
    private fun resetProfile(context: Context, args: List<String>): String {
        if (args.size < 2) return "Usage: termx-profile reset <name>"

        val name = args[1].sanitizeName()
        val profile = loadProfile(context, name)
            ?: return "Error: Profile '$name' not found"

        try {
            // Keep the name and creation date, reset everything else
            val createdAt = profile.optString(KEY_CREATED_AT, currentDate())
            val resetProfile = createDefaultProfileJson(name)
            resetProfile.put(KEY_CREATED_AT, createdAt)
            resetProfile.put(KEY_UPDATED_AT, currentDate())

            saveProfile(context, name, resetProfile)

            return "Profile '$name' reset to defaults"
        } catch (e: Exception) {
            return "Reset failed: ${e.message}"
        }
    }

    // =========================================================================
    // Profile Resolution for Terminal Sessions
    // =========================================================================

    /**
     * Get the full shell environment for the current profile.
     * This is used when spawning new terminal sessions.
     *
     * @param context Android context
     * @return Map of environment variable names to values
     */
    fun getShellEnvironment(context: Context): Map<String, String> {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name) ?: return emptyMap()

        val env = mutableMapOf<String, String>()

        // Core shell variables
        env["SHELL"] = profile.optString(KEY_SHELL, "bash")
        env["HOME"] = profile.optString(KEY_HOME, System.getenv("HOME") ?: "/data/data/com.termx/files/home")
        env["TERM"] = "xterm-256color"
        env["TERMUX"] = "1"
        env["TERMUX_APP__PACKAGE_NAME"] = "com.termx"

        // PATH
        val pathArray = profile.optJSONArray(KEY_PATH)
        val pathEntries = mutableListOf<String>()
        if (pathArray != null) {
            for (i in 0 until pathArray.length()) {
                pathEntries.add(pathArray.getString(i))
            }
        }
        if (pathEntries.isNotEmpty()) {
            env["PATH"] = pathEntries.joinToString(":") + ":${System.getenv("PATH")}"
        }

        // Custom environment variables
        val envObj = profile.optJSONObject(KEY_ENV)
        if (envObj != null) {
<<<<<<< HEAD
            envObj.keys().asSequence().forEach { key: String ->
=======
            Iterable { envObj.keys() }.forEach { key ->
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                env[key] = envObj.getString(key)
            }
        }

        // Profile name
        env["TERMUX_PROFILE"] = name

        return env
    }

    /**
     * Get the shell command for the current profile.
     *
     * @param context Android context
     * @return Shell command string (e.g., "/bin/bash")
     */
    fun getShellCommand(context: Context): String {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name)

        val shell = profile?.optString(KEY_SHELL, "bash") ?: "bash"
        return when (shell) {
            "zsh" -> "/bin/zsh"
            "sh" -> "/bin/sh"
            "bash" -> "/bin/bash"
            else -> "/bin/$shell"
        }
    }

    /**
     * Get the startup script content for the current profile.
     *
     * @param context Android context
     * @return Startup script content or empty string
     */
    fun getStartupScript(context: Context): String {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name) ?: return ""

        val scriptPath = profile.optString(KEY_STARTUP_SCRIPT, "~/.bashrc")
        val expandedPath = scriptPath.replaceFirst(
            "^~".toRegex(),
            profile.optString(KEY_HOME, System.getenv("HOME") ?: "/data/data/com.termx/files/home")
        )

        val scriptFile = File(expandedPath)
        return if (scriptFile.exists()) scriptFile.readText() else ""
    }

    /**
     * Get the theme colors for the current profile.
     *
     * @param context Android context
     * @return Map of color names to hex color strings
     */
    fun getThemeColors(context: Context): Map<String, String> {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name)

        val themeName = profile?.optString(KEY_THEME, "dark") ?: "dark"
        return THEMES[themeName] ?: THEMES["dark"]!!
    }

    /**
     * Get the font configuration for the current profile.
     *
     * @param context Android context
     * @return Pair of (font name, font size)
     */
    fun getFontConfig(context: Context): Pair<String, Int> {
        ensureDefaultProfile(context)
        val name = getCurrentProfileName(context)
        val profile = loadProfile(context, name)

        val fontName = profile?.optString(KEY_FONT_NAME, "Monospace") ?: "Monospace"
        val fontSize = profile?.optInt(KEY_FONT_SIZE, 14) ?: 14
        return Pair(fontName, fontSize)
    }

    // =========================================================================
    // Internal Utilities
    // =========================================================================

    /**
     * Get the profiles directory, creating it if necessary.
     */
    private fun getProfilesDir(context: Context): File {
        val dir = File(context.filesDir, PROFILES_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Get the file for a specific profile.
     */
    private fun getProfileFile(context: Context, name: String): File {
        return File(getProfilesDir(context), "$name$PROFILE_EXTENSION")
    }

    /**
     * Load a profile's JSON configuration.
     */
    private fun loadProfile(context: Context, name: String): JSONObject? {
        val file = getProfileFile(context, name)
        if (!file.exists()) return null

        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile: $name", e)
            null
        }
    }

    /**
     * Save a profile's JSON configuration.
     */
    private fun saveProfile(context: Context, name: String, profile: JSONObject) {
        val file = getProfileFile(context, name)
        file.parentFile?.mkdirs()
        file.writeText(profile.toString(2))
    }

    /**
     * Get the name of the currently active profile.
     */
    private fun getCurrentProfileName(context: Context): String {
        val file = File(context.filesDir, CURRENT_PROFILE_FILE)
        return if (file.exists()) {
            file.readText().trim().ifEmpty { DEFAULT_PROFILE }
        } else {
            DEFAULT_PROFILE
        }
    }

    /**
     * Create a default profile JSON object.
     */
    private fun createDefaultProfileJson(name: String): JSONObject {
        return JSONObject().apply {
            put(KEY_NAME, name)
            put(KEY_SHELL, "bash")
            put(KEY_HOME, "~/")
            put(KEY_PATH, org.json.JSONArray().apply {
                put("/usr/bin")
                put("/usr/local/bin")
                put("/data/data/com.termx/files/usr/bin")
            })
            put(KEY_ENV, JSONObject())
            put(KEY_ALIASES, JSONObject().apply {
                put("ll", "ls -la")
                put("la", "ls -a")
                put("l", "ls -CF")
                put("..", "cd ..")
                put("cls", "clear")
            })
            put(KEY_STARTUP_SCRIPT, "~/.bashrc")
            put(KEY_THEME, "dark")
            put(KEY_FONT_SIZE, 14)
            put(KEY_FONT_NAME, "Monospace")
            put(KEY_CURSOR_STYLE, "block")
            put(KEY_BELL, true)
            put(KEY_SCROLLBACK, 10000)
            put(KEY_CREATED_AT, currentDate())
            put(KEY_UPDATED_AT, currentDate())
        }
    }

    /**
     * Ensure the default profile exists, creating it if necessary.
     */
    private fun ensureDefaultProfile(context: Context) {
        val defaultFile = getProfileFile(context, DEFAULT_PROFILE)
        if (!defaultFile.exists()) {
            try {
                val profile = createDefaultProfileJson(DEFAULT_PROFILE)
                saveProfile(context, DEFAULT_PROFILE, profile)

                // Create directory structure
                val homeDir = File(getProfilesDir(context), "$DEFAULT_PROFILE/home")
                homeDir.mkdirs()

                // Create default .bashrc
                val rcFile = File(getProfilesDir(context), "$DEFAULT_PROFILE/.bashrc")
                if (!rcFile.exists()) {
                    rcFile.writeText(buildDefaultBashrc())
                }

                // Set as current profile
                File(context.filesDir, CURRENT_PROFILE_FILE).writeText(DEFAULT_PROFILE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create default profile", e)
            }
        }
    }

    /**
     * Generate default .bashrc content.
     */
    private fun buildDefaultBashrc(): String {
        return """
# ~/.bashrc for TermX profile: default
# This file is sourced on each new terminal session

# If not running interactively, don't do anything
case $- in
    *i*) ;;
      *) return;;
esac

# History settings
HISTCONTROL=ignoreboth
HISTSIZE=1000
HISTFILESIZE=2000
shopt -s histappend

# Check window size after each command
shopt -s checkwinsize

# Prompt
PS1='\[\033[01;32m\]\u@\h\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '

# Aliases
alias ll='ls -la'
alias la='ls -a'
alias l='ls -CF'
alias cls='clear'
alias ..='cd ..'
alias ...='cd ../..'

# TermX specific
export TERMUX=1
export TERM=xterm-256color

echo "Welcome to TermX!"
""".trimIndent()
    }

    /**
     * Append an alias to the profile's .bashrc file.
     */
    private fun appendAliasToBashrc(context: Context, profileName: String, alias: String, command: String) {
        val rcFile = File(getProfilesDir(context), "$profileName/.bashrc")
        if (rcFile.exists()) {
            rcFile.appendText("\nalias $alias='$command'\n")
        }
    }

    /**
     * Sanitize a profile name — remove unsafe characters.
     */
    private fun String.sanitizeName(): String {
        return this.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    }

    /**
     * Check if a profile name is valid.
     */
    private fun isValidName(name: String): Boolean {
        return name.isNotEmpty() && name.matches(Regex("^[a-zA-Z0-9_\\-]+$"))
    }

    /**
     * Get the current date as an ISO-formatted string.
     */
    private fun currentDate(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
    }

    // =========================================================================
    // Help Text
    // =========================================================================

    /**
     * Get help text for termx-profile commands.
     */
    fun getHelpText(): String {
        return """
TermX Profile Manager — termx-profile
======================================

Profile Management:
  list                                  List all profiles
  create <name>                         Create a new profile
  delete <name>                         Delete a profile
  switch <name>                         Switch to a profile
  current                               Show current profile
  show <name>                           Show profile settings
  clone <src> <dst>                     Clone a profile
  rename <old> <new>                    Rename a profile
  reset <name>                          Reset profile to defaults

Environment:
  env [name]                            Show profile environment
  set <name> <key> <value>              Set profile variable
  get <name> <key>                      Get profile variable
  add-path <name> <directory>           Add to PATH
  remove-path <name> <directory>        Remove from PATH

Aliases:
  add-alias <name> <alias> <command>    Add an alias
  remove-alias <name> <alias>           Remove an alias
  list-aliases [name]                   List aliases

Appearance:
  set-theme <name> <theme>              Set color theme
  list-themes                           List available themes

Import/Export:
  export <name> [path]                  Export profile to JSON
  import <path>                         Import profile from JSON

Valid keys for 'set': shell, home, theme, font-name, font-size,
  cursor-style, bell, scrollback, startup-script, or any custom key

Examples:
  termx-profile create dev
  termx-profile set dev shell zsh
  termx-profile set dev theme monokai
  termx-profile add-alias dev gs 'git status'
  termx-profile add-path dev /usr/local/go/bin
  termx-profile switch dev
""".trimIndent()
    }
}
