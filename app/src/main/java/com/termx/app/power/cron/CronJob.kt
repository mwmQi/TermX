package com.termx.app.power.cron

import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Data model representing a single cron job in the TermX scheduler.
 *
 * Each job has:
 *  - A unique ID for identification and alarm management
 *  - A cron expression that defines its schedule
 *  - A shell command to execute
 *  - An enabled/disabled flag
 *  - Tracking metadata (creation time, last run, next run, execution count)
 *  - Configurable output logging and timeout
 *  - JSON serialization for persistent storage
 *
 * Jobs are persisted to a JSON file and restored on app start or device reboot.
 */
data class CronJob(
    /** Unique identifier for this job (used as AlarmManager request code). */
    val id: String,

    /** The raw cron expression string (e.g. "*/5 * * * *" or "@hourly"). */
    val expression: String,

    /** The shell command to execute when the job fires. */
    val command: String,

    /** Whether this job is active and will be scheduled. */
    var enabled: Boolean = true,

    /** Epoch millis when this job was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Epoch millis when this job last executed, or 0 if never. */
    var lastRunAt: Long = 0L,

    /** Epoch millis of the next scheduled execution, or 0 if unscheduled. */
    var nextRunAt: Long = 0L,

    /** How many times this job has executed. */
    var executionCount: Long = 0L,

    /** Whether to capture and log command output. */
    var logOutput: Boolean = true,

    /** Maximum execution time in seconds before the process is killed (0 = no limit). */
    var timeoutSeconds: Int = 0,

    /** Optional human-readable label for this job. */
    var label: String = "",

    /** The working directory for command execution. */
    var workingDirectory: String = "",

    /** Environment variables as "KEY=VALUE" pairs. */
    var environmentVars: List<String> = emptyList(),

    /**
     * For @reboot jobs: tracks whether the job has already run
     * in the current boot session.
     */
    var hasRunThisBoot: Boolean = false,

    /**
     * For @every_Nm interval jobs: tracks the base time from which
     * intervals are measured.
     */
    var intervalBaseTime: Long = 0L
) {

    /** Parsed cron expression, lazily evaluated. Null if expression is invalid. */
    val parsedExpression: CronExpression? by lazy {
        CronExpression.parse(expression)
    }

    /** Whether this is a @reboot job. */
    val isRebootJob: Boolean
        get() = expression.trim().lowercase(Locale.ROOT) == "@reboot"

    /** Whether this is an interval-based job (@every_Nm). */
    val isIntervalJob: Boolean
        get() = parsedExpression?.intervalMinutes?.let { it > 0 } == true

    /** The interval in minutes for @every_Nm jobs, or 0. */
    val intervalMinutes: Int
        get() = parsedExpression?.intervalMinutes ?: 0

    // ── Status helpers ──────────────────────────────────────────────

    /** Returns a human-readable status string. */
    fun statusString(): String {
        return when {
            !enabled -> "DISABLED"
            isRebootJob && hasRunThisBoot -> "RAN (boot)"
            nextRunAt <= 0L -> "PENDING"
            else -> "SCHEDULED"
        }
    }

    /** Returns a short description suitable for display. */
    fun shortDescription(): String {
        val lbl = if (label.isNotBlank()) "[$label] " else ""
        return "$lbl$expression → ${command.take(50)}"
    }

    /** Returns a detailed multi-line description. */
    fun detailedDescription(): String {
        val sb = StringBuilder()
        sb.appendLine("Job ID:    $id")
        if (label.isNotBlank()) sb.appendLine("Label:     $label")
        sb.appendLine("Schedule:  $expression")
        sb.appendLine("Command:   $command")
        sb.appendLine("Status:    ${statusString()}")
        sb.appendLine("Enabled:   $enabled")
        sb.appendLine("Log output:$logOutput")
        if (timeoutSeconds > 0) sb.appendLine("Timeout:   ${timeoutSeconds}s")
        if (workingDirectory.isNotBlank()) sb.appendLine("Work dir:  $workingDirectory")
        if (environmentVars.isNotEmpty()) sb.appendLine("Env vars:  ${environmentVars.joinToString(" ")}")
        sb.appendLine("Created:   ${formatTime(createdAt)}")
        sb.appendLine("Last run:  ${if (lastRunAt > 0) formatTime(lastRunAt) else "never"}")
        sb.appendLine("Next run:  ${if (nextRunAt > 0) formatTime(nextRunAt) else "unscheduled"}")
        sb.appendLine("Executions: $executionCount")

        // Human-readable schedule description
        parsedExpression?.let { expr ->
            sb.appendLine("Human:     ${expr.describe()}")
        }

        return sb.trimEnd()
    }

    // ── JSON serialization ──────────────────────────────────────────

    /**
     * Serialize this job to a JSONObject for persistent storage.
     */
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_EXPRESSION, expression)
            put(KEY_COMMAND, command)
            put(KEY_ENABLED, enabled)
            put(KEY_CREATED_AT, createdAt)
            put(KEY_LAST_RUN_AT, lastRunAt)
            put(KEY_NEXT_RUN_AT, nextRunAt)
            put(KEY_EXECUTION_COUNT, executionCount)
            put(KEY_LOG_OUTPUT, logOutput)
            put(KEY_TIMEOUT_SECONDS, timeoutSeconds)
            put(KEY_LABEL, label)
            put(KEY_WORKING_DIRECTORY, workingDirectory)
            put(KEY_HAS_RUN_THIS_BOOT, hasRunThisBoot)
            put(KEY_INTERVAL_BASE_TIME, intervalBaseTime)

            // Environment variables as JSON array
            val envArray = JSONArray()
            for (env in environmentVars) {
                envArray.put(env)
            }
            put(KEY_ENVIRONMENT_VARS, envArray)
        }
    }

    // ── Companion ───────────────────────────────────────────────────

    companion object {
        // JSON keys
        const val KEY_ID                = "id"
        const val KEY_EXPRESSION        = "expression"
        const val KEY_COMMAND           = "command"
        const val KEY_ENABLED           = "enabled"
        const val KEY_CREATED_AT        = "created_at"
        const val KEY_LAST_RUN_AT       = "last_run_at"
        const val KEY_NEXT_RUN_AT       = "next_run_at"
        const val KEY_EXECUTION_COUNT   = "execution_count"
        const val KEY_LOG_OUTPUT        = "log_output"
        const val KEY_TIMEOUT_SECONDS   = "timeout_seconds"
        const val KEY_LABEL             = "label"
        const val KEY_WORKING_DIRECTORY = "working_directory"
        const val KEY_ENVIRONMENT_VARS  = "environment_vars"
        const val KEY_HAS_RUN_THIS_BOOT = "has_run_this_boot"
        const val KEY_INTERVAL_BASE_TIME = "interval_base_time"

        /**
         * Deserialize a cron job from a JSONObject.
         * Returns null if the JSON is missing required fields.
         */
        fun fromJSON(json: JSONObject): CronJob? {
            return try {
                val id = json.optString(KEY_ID, "")
                val expression = json.optString(KEY_EXPRESSION, "")
                val command = json.optString(KEY_COMMAND, "")
                if (id.isEmpty() || expression.isEmpty() || command.isEmpty()) {
                    return null
                }

                val envList = mutableListOf<String>()
                val envArray = json.optJSONArray(KEY_ENVIRONMENT_VARS)
                if (envArray != null) {
                    for (i in 0 until envArray.length()) {
                        envList.add(envArray.getString(i))
                    }
                }

                CronJob(
                    id               = id,
                    expression       = expression,
                    command          = command,
                    enabled          = json.optBoolean(KEY_ENABLED, true),
                    createdAt        = json.optLong(KEY_CREATED_AT, System.currentTimeMillis()),
                    lastRunAt        = json.optLong(KEY_LAST_RUN_AT, 0L),
                    nextRunAt        = json.optLong(KEY_NEXT_RUN_AT, 0L),
                    executionCount   = json.optLong(KEY_EXECUTION_COUNT, 0L),
                    logOutput        = json.optBoolean(KEY_LOG_OUTPUT, true),
                    timeoutSeconds   = json.optInt(KEY_TIMEOUT_SECONDS, 0),
                    label            = json.optString(KEY_LABEL, ""),
                    workingDirectory = json.optString(KEY_WORKING_DIRECTORY, ""),
                    environmentVars  = envList,
                    hasRunThisBoot   = json.optBoolean(KEY_HAS_RUN_THIS_BOOT, false),
                    intervalBaseTime = json.optLong(KEY_INTERVAL_BASE_TIME, 0L)
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Generate a unique job ID based on the current timestamp and a random suffix.
         */
        fun generateId(): String {
            val ts = System.currentTimeMillis().toString(36)
            val rand = (0..9999).random().toString(36)
            return "cron_${ts}_$rand"
        }

        /**
         * Format an epoch-millis timestamp for display.
         */
        fun formatTime(epochMillis: Long): String {
            if (epochMillis <= 0) return "N/A"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(epochMillis))
        }
    }

    // ── Execution log entry ─────────────────────────────────────────

    /**
     * Represents a single execution log entry for a cron job.
     */
    data class ExecutionLog(
        val jobId: String,
        val timestamp: Long,
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long
    ) {
        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put("job_id", jobId)
                put("timestamp", timestamp)
                put("command", command)
                put("exit_code", exitCode)
                put("stdout", stdout)
                put("stderr", stderr)
                put("duration_ms", durationMs)
            }
        }

        fun formattedOutput(): String {
            val sb = StringBuilder()
            sb.appendLine("═══════════════════════════════════════")
            sb.appendLine("Job:    $jobId")
            sb.appendLine("Time:   ${formatTime(timestamp)}")
            sb.appendLine("Cmd:    $command")
            sb.appendLine("Exit:   $exitCode")
            sb.appendLine("Took:   ${durationMs}ms")
            if (stdout.isNotBlank()) {
                sb.appendLine("── stdout ──")
                sb.append(stdout.trimEnd())
                sb.appendLine()
            }
            if (stderr.isNotBlank()) {
                sb.appendLine("── stderr ──")
                sb.append(stderr.trimEnd())
                sb.appendLine()
            }
            sb.appendLine("═══════════════════════════════════════")
            return sb.toString()
        }

        companion object {
            fun fromJSON(json: JSONObject): ExecutionLog? {
                return try {
                    ExecutionLog(
                        jobId      = json.optString("job_id", ""),
                        timestamp  = json.optLong("timestamp", 0L),
                        command    = json.optString("command", ""),
                        exitCode   = json.optInt("exit_code", -1),
                        stdout     = json.optString("stdout", ""),
                        stderr     = json.optString("stderr", ""),
                        durationMs = json.optLong("duration_ms", 0L)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
