package com.termx.app.power

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Macro System for TermX — record, replay, and manage command macros.
 *
 * Provides a complete macro recording and playback system for terminal commands.
 * Macros are stored as JSON files and support variable substitution, looping,
 * conditional execution, and export as standalone shell scripts.
 *
 * Shell usage:
 *   termx-macro record <name>         Start recording a macro
 *   termx-macro stop                  Stop recording
 *   termx-macro play <name>           Play a macro
 *   termx-macro list                  List all macros
 *   termx-macro show <name>           Show macro contents
 *   termx-macro delete <name>         Delete a macro
 *   termx-macro edit <name>           Edit macro (add/remove commands)
 *   termx-macro export <name>         Export macro as script
 *   termx-macro import <path>         Import macro from file
 *   termx-macro loop <name> <count>   Play macro N times
 *   termx-macro schedule <name> <cron> Schedule macro with cron
 *
 * No special permissions required. Macros are stored in the app's internal storage.
 */
object MacroSystem {

    private const val TAG = "MacroSystem"
    private const val MACRO_DIR = "macros"
    private const val MACRO_EXTENSION = ".json"
    private const val DEFAULT_DELAY_MS = 500L
    private const val MAX_MACRO_SIZE = 10000   // Maximum commands per macro
    private const val MAX_NAME_LENGTH = 64

    // Recording state
    private var isRecording = false
    private var currentRecordingName: String? = null
    private val currentCommands = mutableListOf<MacroCommand>()
    private var recordingStartTime: Long = 0

    // Playback state
    private var isPlaying = false
    private var playbackInterrupted = false

    /**
     * Represents a single command in a macro with optional delay after execution.
     */
    data class MacroCommand(
        val command: String,
        val delayAfterMs: Long = DEFAULT_DELAY_MS,
        val description: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val ignoreError: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("command", command)
            put("delayAfterMs", delayAfterMs)
            put("description", description)
            put("timestamp", timestamp)
            put("ignoreError", ignoreError)
        }

        fun toFormattedString(index: Int): String = buildString {
            append("  [$index] ")
            if (description.isNotBlank()) {
                append("# $description  ")
            }
            append(command)
            if (delayAfterMs != DEFAULT_DELAY_MS) {
                append(" (delay: ${delayAfterMs}ms)")
            }
            if (ignoreError) {
                append(" [ignore-error]")
            }
        }
    }

    /**
     * Represents a complete macro with metadata.
     */
    data class Macro(
        val name: String,
        val commands: List<MacroCommand>,
        val createdAt: Long,
        val modifiedAt: Long,
        val description: String = "",
        val variables: Map<String, String> = emptyMap(),
        val playCount: Int = 0,
        val lastPlayedAt: Long = 0
    ) {
        val commandCount: Int get() = commands.size

        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("createdAt", createdAt)
            put("modifiedAt", modifiedAt)
            put("playCount", playCount)
            put("lastPlayedAt", lastPlayedAt)

            val cmdArray = JSONArray()
            commands.forEach { cmdArray.put(it.toJson()) }
            put("commands", cmdArray)

            val varObj = JSONObject()
            variables.forEach { (k, v) -> varObj.put(k, v) }
            put("variables", varObj)
        }

        fun toFormattedString(): String = buildString {
            appendLine("=== Macro: $name ===")
            if (description.isNotBlank()) {
                appendLine("Description:  $description")
            }
            appendLine("Commands:     $commandCount")
            appendLine("Created:      ${formatTimestamp(createdAt)}")
            appendLine("Modified:     ${formatTimestamp(modifiedAt)}")
            appendLine("Play Count:   $playCount")
            if (lastPlayedAt > 0) {
                appendLine("Last Played:  ${formatTimestamp(lastPlayedAt)}")
            }
            if (variables.isNotEmpty()) {
                appendLine("Variables:")
                variables.forEach { (k, v) -> appendLine("  \$$k = $v") }
            }
            appendLine("Commands:")
            commands.forEachIndexed { i, cmd -> appendLine(cmd.toFormattedString(i)) }
        }
    }

    // ---- Recording API ----

    /**
     * Start recording a new macro.
     *
     * @param context Application context
     * @param name Name for the macro (alphanumeric, dashes, underscores)
     * @param description Optional description
     */
    fun record(context: Context, name: String, description: String = ""): String {
        if (isRecording) {
            return "Error: Already recording macro '$currentRecordingName'. Stop it first."
        }

        val sanitizedName = sanitizeName(name)
        if (!isValidName(sanitizedName)) {
            return "Error: Invalid macro name '$name'. Use alphanumeric characters, dashes, and underscores only."
        }

        if (macroExists(context, sanitizedName)) {
            return "Error: Macro '$sanitizedName' already exists. Delete it first or use a different name."
        }

        isRecording = true
        currentRecordingName = sanitizedName
        currentCommands.clear()
        recordingStartTime = System.currentTimeMillis()

        Log.i(TAG, "Recording started: $sanitizedName")
        return buildString {
            appendLine("Recording macro: $sanitizedName")
            appendLine("Commands will be captured. Use termx-macro stop to finish recording.")
            if (description.isNotBlank()) {
                appendLine("Description: $description")
            }
        }
    }

    /**
     * Record a command into the current macro recording.
     *
     * @param command The shell command to record
     * @param delayAfterMs Delay after this command in milliseconds
     * @param description Optional description for the command
     * @param ignoreError Whether to continue on error
     */
    fun recordCommand(command: String, delayAfterMs: Long = DEFAULT_DELAY_MS,
                      description: String = "", ignoreError: Boolean = false): String {
        if (!isRecording) {
            return "Error: Not currently recording a macro"
        }

        if (currentCommands.size >= MAX_MACRO_SIZE) {
            return "Error: Macro has reached maximum size ($MAX_MACRO_SIZE commands)"
        }

        val macroCommand = MacroCommand(
            command = command,
            delayAfterMs = delayAfterMs,
            description = description,
            timestamp = System.currentTimeMillis(),
            ignoreError = ignoreError
        )
        currentCommands.add(macroCommand)

        Log.d(TAG, "Recorded command #${currentCommands.size}: $command")
        return "Recorded [${currentCommands.size}]: $command"
    }

    /**
     * Stop recording and save the macro.
     *
     * @param context Application context
     * @param description Optional description to set/override
     */
    fun stopRecording(context: Context, description: String = ""): String {
        if (!isRecording) {
            return "Error: Not currently recording a macro"
        }

        val name = currentRecordingName ?: return "Error: No recording in progress"
        val commandCount = currentCommands.size

        if (commandCount == 0) {
            isRecording = false
            currentRecordingName = null
            currentCommands.clear()
            return "Recording cancelled: No commands recorded for macro '$name'"
        }

        val macro = Macro(
            name = name,
            commands = currentCommands.toList(),
            createdAt = recordingStartTime,
            modifiedAt = System.currentTimeMillis(),
            description = description
        )

        val saved = saveMacro(context, macro)
        isRecording = false
        currentRecordingName = null
        currentCommands.clear()

        return if (saved) {
            Log.i(TAG, "Macro '$name' saved with $commandCount commands")
            "Macro '$name' saved with $commandCount commands"
        } else {
            "Error: Failed to save macro '$name'"
        }
    }

    /**
     * Check if a macro is currently being recorded.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Get the name of the macro currently being recorded.
     */
    fun getCurrentRecordingName(): String? = currentRecordingName

    /**
     * Get the number of commands recorded so far.
     */
    fun getRecordedCommandCount(): Int = currentCommands.size

    // ---- Playback API ----

    /**
     * Play a macro by executing all its commands sequentially.
     *
     * @param context Application context
     * @param name Name of the macro to play
     * @param variables Optional variable overrides for substitution
     * @param commandExecutor Function that executes a command and returns its output
     * @return Formatted result of the playback
     */
    fun play(context: Context, name: String, variables: Map<String, String> = emptyMap(),
             commandExecutor: (String) -> String = { cmd ->
                 // Default executor: just log the command
                 Log.d(TAG, "Executing: $cmd")
                 "executed: $cmd"
             }): String {
        if (isPlaying) {
            return "Error: A macro is already playing. Wait for it to finish or interrupt it."
        }

        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        if (macro.commands.isEmpty()) {
            return "Error: Macro '$name' has no commands"
        }

        isPlaying = true
        playbackInterrupted = false

        val mergedVars = macro.variables + variables
        val results = mutableListOf<String>()
        var successCount = 0
        var errorCount = 0

        try {
            macro.commands.forEachIndexed { index, cmd ->
                if (playbackInterrupted) {
                    results.add("  [INTERRUPTED] Playback stopped at command $index")
                    return@forEachIndexed
                }

                // Apply variable substitution
                val resolvedCommand = substituteVariables(cmd.command, mergedVars)

                try {
                    val output = commandExecutor(resolvedCommand)
                    results.add("  [OK] [$index] $resolvedCommand")
                    successCount++
                } catch (e: Exception) {
                    if (cmd.ignoreError) {
                        results.add("  [SKIP] [$index] $resolvedCommand (error ignored: ${e.message})")
                        successCount++
                    } else {
                        results.add("  [FAIL] [$index] $resolvedCommand (${e.message})")
                        errorCount++
                        throw e // Stop playback on error
                    }
                }

                // Delay between commands
                if (cmd.delayAfterMs > 0 && index < macro.commands.size - 1) {
                    try {
                        Thread.sleep(cmd.delayAfterMs)
                    } catch (e: InterruptedException) {
                        playbackInterrupted = true
                        results.add("  [INTERRUPTED] Delay interrupted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Macro playback stopped due to error at command", e)
        } finally {
            isPlaying = false
        }

        // Update play count
        updatePlayStats(context, name)

        return buildString {
            appendLine("=== Macro Playback: $name ===")
            appendLine("Commands: ${successCount} succeeded, ${errorCount} failed")
            results.forEach { appendLine(it) }
            if (playbackInterrupted) {
                appendLine("Playback was interrupted")
            }
        }
    }

    /**
     * Play a macro N times in a loop.
     *
     * @param context Application context
     * @param name Macro name
     * @param count Number of times to loop
     * @param commandExecutor Function to execute each command
     */
    fun loop(context: Context, name: String, count: Int,
             commandExecutor: (String) -> String = { "executed: $it" }): String {
        if (count <= 0) return "Error: Loop count must be positive"
        if (count > 100) return "Error: Maximum loop count is 100"

        val results = mutableListOf<String>()
        for (i in 1..count) {
            if (playbackInterrupted) break
            results.add("--- Loop iteration $i/$count ---")
            val result = play(context, name, commandExecutor = commandExecutor)
            results.add(result)
        }

        return buildString {
            appendLine("=== Macro Loop: $name x$count ===")
            results.forEach { appendLine(it) }
        }
    }

    /**
     * Interrupt the current macro playback.
     */
    fun interruptPlayback(): String {
        if (!isPlaying) {
            return "No macro is currently playing"
        }
        playbackInterrupted = true
        Log.i(TAG, "Macro playback interrupted by user")
        return "Playback interrupted. Will stop after current command."
    }

    // ---- Management API ----

    /**
     * List all saved macros.
     */
    fun list(context: Context): String {
        val macros = getAllMacros(context)
        if (macros.isEmpty()) {
            return "No macros saved"
        }

        return buildString {
            appendLine("=== Macros (${macros.size}) ===")
            macros.sortedBy { it.name.lowercase() }.forEach { macro ->
                val desc = if (macro.description.isNotBlank()) " — ${macro.description}" else ""
                appendLine("  ${macro.name}  (${macro.commandCount} cmds, played ${macro.playCount}x)$desc")
            }
        }
    }

    /**
     * Show the contents of a specific macro.
     */
    fun show(context: Context, name: String): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"
        return macro.toFormattedString()
    }

    /**
     * Delete a macro.
     */
    fun delete(context: Context, name: String): String {
        val file = getMacroFile(context, name)
        if (!file.exists()) {
            return "Error: Macro '$name' not found"
        }

        return if (file.delete()) {
            Log.i(TAG, "Macro '$name' deleted")
            "Macro '$name' deleted"
        } else {
            "Error: Failed to delete macro '$name'"
        }
    }

    /**
     * Add a command to an existing macro.
     */
    fun addCommand(context: Context, name: String, command: String,
                   delayAfterMs: Long = DEFAULT_DELAY_MS, description: String = "",
                   ignoreError: Boolean = false): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        val newCommand = MacroCommand(
            command = command,
            delayAfterMs = delayAfterMs,
            description = description,
            timestamp = System.currentTimeMillis(),
            ignoreError = ignoreError
        )

        val updatedMacro = macro.copy(
            commands = macro.commands + newCommand,
            modifiedAt = System.currentTimeMillis()
        )

        return if (saveMacro(context, updatedMacro)) {
            "Command added to macro '$name' [${updatedMacro.commandCount} total]"
        } else {
            "Error: Failed to update macro '$name'"
        }
    }

    /**
     * Remove a command from a macro by index.
     */
    fun removeCommand(context: Context, name: String, index: Int): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        if (index < 0 || index >= macro.commands.size) {
            return "Error: Invalid command index $index (macro has ${macro.commands.size} commands)"
        }

        val removed = macro.commands[index]
        val updatedCommands = macro.commands.toMutableList()
        updatedCommands.removeAt(index)

        val updatedMacro = macro.copy(
            commands = updatedCommands,
            modifiedAt = System.currentTimeMillis()
        )

        return if (saveMacro(context, updatedMacro)) {
            "Removed command [$index] '${removed.command}' from macro '$name'"
        } else {
            "Error: Failed to update macro '$name'"
        }
    }

    /**
     * Set/update the description of a macro.
     */
    fun setDescription(context: Context, name: String, description: String): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        val updatedMacro = macro.copy(
            description = description,
            modifiedAt = System.currentTimeMillis()
        )

        return if (saveMacro(context, updatedMacro)) {
            "Description updated for macro '$name'"
        } else {
            "Error: Failed to update macro '$name'"
        }
    }

    /**
     * Set a variable on a macro for substitution during playback.
     */
    fun setVariable(context: Context, name: String, key: String, value: String): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        val updatedVars = macro.variables.toMutableMap()
        updatedVars[key] = value

        val updatedMacro = macro.copy(
            variables = updatedVars,
            modifiedAt = System.currentTimeMillis()
        )

        return if (saveMacro(context, updatedMacro)) {
            "Variable '$key' set to '$value' in macro '$name'"
        } else {
            "Error: Failed to update macro '$name'"
        }
    }

    /**
     * Export a macro as a standalone shell script.
     */
    fun export(context: Context, name: String): String {
        val macro = loadMacro(context, name)
            ?: return "Error: Macro '$name' not found"

        val scriptContent = buildString {
            appendLine("#!/bin/sh")
            appendLine("# TermX Macro: $name")
            if (macro.description.isNotBlank()) {
                appendLine("# Description: ${macro.description}")
            }
            appendLine("# Exported: ${formatTimestamp(System.currentTimeMillis())}")
            appendLine("# Commands: ${macro.commandCount}")
            appendLine()

            // Variable declarations
            if (macro.variables.isNotEmpty()) {
                appendLine("# Variables")
                macro.variables.forEach { (k, v) ->
                    appendLine("${k.toUpperCase()}=\"$v\"")
                }
                appendLine()
            }

            // Commands
            macro.commands.forEachIndexed { i, cmd ->
                if (cmd.description.isNotBlank()) {
                    appendLine("# ${cmd.description}")
                }
                val resolvedCmd = if (macro.variables.isNotEmpty()) {
                    substituteVariables(cmd.command, macro.variables)
                } else {
                    cmd.command
                }
                appendLine(resolvedCmd)
                if (cmd.ignoreError) {
                    appendLine("|| true  # ignore error")
                }
                if (cmd.delayAfterMs > 0) {
                    appendLine("sleep ${cmd.delayAfterMs / 1000.0}")
                }
            }
        }

        // Write script file
        val exportDir = File(getMacroDir(context), "exports")
        exportDir.mkdirs()
        val exportFile = File(exportDir, "${name}.sh")
        try {
            FileWriter(exportFile).use { it.write(scriptContent) }
            // Make executable
            exportFile.setExecutable(true)
            Log.i(TAG, "Macro '$name' exported to ${exportFile.absolutePath}")
            return "Macro exported: ${exportFile.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export macro '$name'", e)
            return "Error exporting macro: ${e.message}"
        }
    }

    /**
     * Import a macro from a JSON file.
     */
    fun import(context: Context, path: String): String {
        return try {
            val importFile = File(path)
            if (!importFile.exists()) {
                return "Error: File not found: $path"
            }
            if (!importFile.canRead()) {
                return "Error: Cannot read file: $path"
            }

            val jsonContent = BufferedReader(FileReader(importFile)).use { reader ->
                reader.readText()
            }

            val jsonObject = JSONObject(jsonContent)
            val macro = parseMacroFromJson(jsonObject)

            if (macroExists(context, macro.name)) {
                return "Error: Macro '${macro.name}' already exists. Delete it first."
            }

            if (saveMacro(context, macro)) {
                Log.i(TAG, "Macro '${macro.name}' imported from $path")
                "Macro '${macro.name}' imported (${macro.commandCount} commands)"
            } else {
                "Error: Failed to save imported macro"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import macro from $path", e)
            "Error importing macro: ${e.message}"
        }
    }

    /**
     * Rename a macro.
     */
    fun rename(context: Context, oldName: String, newName: String): String {
        val macro = loadMacro(context, oldName)
            ?: return "Error: Macro '$oldName' not found"

        val sanitizedNewName = sanitizeName(newName)
        if (!isValidName(sanitizedNewName)) {
            return "Error: Invalid macro name '$newName'"
        }

        if (macroExists(context, sanitizedNewName)) {
            return "Error: Macro '$sanitizedNewName' already exists"
        }

        val renamedMacro = macro.copy(
            name = sanitizedNewName,
            modifiedAt = System.currentTimeMillis()
        )

        return if (saveMacro(context, renamedMacro)) {
            delete(context, oldName)
            "Macro renamed: $oldName -> $sanitizedNewName"
        } else {
            "Error: Failed to rename macro"
        }
    }

    // ---- Internal Helpers ----

    /**
     * Get the macro storage directory.
     */
    private fun getMacroDir(context: Context): File {
        val dir = File(context.filesDir, MACRO_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Get the file for a specific macro.
     */
    private fun getMacroFile(context: Context, name: String): File {
        return File(getMacroDir(context), "$name$MACRO_EXTENSION")
    }

    /**
     * Check if a macro with the given name exists.
     */
    private fun macroExists(context: Context, name: String): Boolean {
        return getMacroFile(context, name).exists()
    }

    /**
     * Save a macro to its JSON file.
     */
    private fun saveMacro(context: Context, macro: Macro): Boolean {
        return try {
            val file = getMacroFile(context, macro.name)
            FileWriter(file).use { writer ->
                writer.write(macro.toJson().toString(2))
            }
            Log.d(TAG, "Macro '${macro.name}' saved")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save macro '${macro.name}'", e)
            false
        }
    }

    /**
     * Load a macro from its JSON file.
     */
    private fun loadMacro(context: Context, name: String): Macro? {
        return try {
            val file = getMacroFile(context, name)
            if (!file.exists()) return null

            val jsonContent = BufferedReader(FileReader(file)).use { it.readText() }
            val jsonObject = JSONObject(jsonContent)
            parseMacroFromJson(jsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load macro '$name'", e)
            null
        }
    }

    /**
     * Parse a macro from its JSON representation.
     */
    private fun parseMacroFromJson(json: JSONObject): Macro {
        val name = json.getString("name")
        val description = json.optString("description", "")
        val createdAt = json.optLong("createdAt", System.currentTimeMillis())
        val modifiedAt = json.optLong("modifiedAt", System.currentTimeMillis())
        val playCount = json.optInt("playCount", 0)
        val lastPlayedAt = json.optLong("lastPlayedAt", 0)

        val commandsArray = json.getJSONArray("commands")
        val commands = (0 until commandsArray.length()).map { i ->
            val cmdObj = commandsArray.getJSONObject(i)
            MacroCommand(
                command = cmdObj.getString("command"),
                delayAfterMs = cmdObj.optLong("delayAfterMs", DEFAULT_DELAY_MS),
                description = cmdObj.optString("description", ""),
                timestamp = cmdObj.optLong("timestamp", 0),
                ignoreError = cmdObj.optBoolean("ignoreError", false)
            )
        }

        val variablesObj = json.optJSONObject("variables")
        val variables = if (variablesObj != null) {
            val keys = variablesObj.keys()
            val map = mutableMapOf<String, String>()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = variablesObj.getString(key)
            }
            map
        } else emptyMap()

        return Macro(
            name = name,
            commands = commands,
            createdAt = createdAt,
            modifiedAt = modifiedAt,
            description = description,
            variables = variables,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt
        )
    }

    /**
     * Get all saved macros.
     */
    private fun getAllMacros(context: Context): List<Macro> {
        val dir = getMacroDir(context)
        val macros = mutableListOf<Macro>()

        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val jsonContent = BufferedReader(FileReader(file)).use { it.readText() }
                val jsonObject = JSONObject(jsonContent)
                macros.add(parseMacroFromJson(jsonObject))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse macro file: ${file.name}", e)
            }
        }

        return macros
    }

    /**
     * Update play statistics for a macro.
     */
    private fun updatePlayStats(context: Context, name: String) {
        val macro = loadMacro(context, name) ?: return
        val updatedMacro = macro.copy(
            playCount = macro.playCount + 1,
            lastPlayedAt = System.currentTimeMillis()
        )
        saveMacro(context, updatedMacro)
    }

    /**
     * Substitute variables in a command string.
     * Variables are in the format $NAME or ${NAME}.
     */
    private fun substituteVariables(command: String, variables: Map<String, String>): String {
        var result = command
        variables.forEach { (key, value) ->
            result = result.replace("\$$key", value)
            result = result.replace("\${$key}", value)
        }
        return result
    }

    /**
     * Sanitize a macro name: lowercase, replace spaces with dashes, remove special chars.
     */
    private fun sanitizeName(name: String): String {
        return name.trim()
            .lowercase()
            .replace("\\s+".toRegex(), "-")
            .replace("[^a-z0-9\\-_]".toRegex(), "")
    }

    /**
     * Validate a macro name.
     */
    private fun isValidName(name: String): Boolean {
        return name.isNotBlank() &&
                name.length <= MAX_NAME_LENGTH &&
                name.matches("^[a-z0-9][a-z0-9\\-_]*$".toRegex())
    }

    /**
     * Format a timestamp for display.
     */
    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}
