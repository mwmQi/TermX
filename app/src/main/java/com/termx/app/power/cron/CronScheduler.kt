package com.termx.app.power.cron

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.termx.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Cron-like task scheduler for TermX.
 *
 * Schedules terminal commands to run at specified times, similar to
 * the standard Unix cron daemon. Jobs persist across app restarts and
 * device reboots.
 *
 * Crontab format:
 *   ┌──────── minute (0-59)
 *   │ ┌────── hour (0-23)
 *   │ │ ┌──── day of month (1-31)
 *   │ │ │ ┌── month (1-12)
 *   │ │ │ │ ┌ day of week (0-6, 0=Sunday)
 *   │ │ │ │ │
 *   * * * * * command
 *
 * Special strings:
 *   @hourly    Run once per hour  (0 * * * *)
 *   @daily     Run once per day   (0 0 * * *)
 *   @weekly    Run once per week  (0 0 * * 0)
 *   @monthly   Run once per month (0 0 1 * *)
 *   @reboot    Run on device/app boot
 *   @every_Nm  Run every N minutes
 *
 * Shell usage:
 *   termx-cron list                     List all cron jobs
 *   termx-cron add "*/5 * * * * cmd"    Add a cron job
 *   termx-cron remove <id>              Remove a cron job
 *   termx-cron enable <id>              Enable a cron job
 *   termx-cron disable <id>             Disable a cron job
 *   termx-cron run <id>                 Run a job immediately
 *   termx-cron logs [id]                View execution logs
 *   termx-cron clear-logs               Clear execution logs
 *   termx-cron start                    Start the cron daemon
 *   termx-cron stop                     Stop the cron daemon
 *   termx-cron status                   Show daemon status
 */
class CronScheduler(private val context: Context) {

    // ── In-memory job store ─────────────────────────────────────────

    /** Thread-safe map of job ID → CronJob. */
    private val jobs = ConcurrentHashMap<String, CronJob>()

    /** Whether the scheduler daemon is currently running. */
    @Volatile
    var isRunning = false
        private set

    // ── Persistence ─────────────────────────────────────────────────

    /** Directory for cron data storage. */
    private val cronDir: File by lazy {
        File(context.filesDir, "cron").also { it.mkdirs() }
    }

    /** File holding all cron job definitions. */
    private val jobsFile: File by lazy {
        File(cronDir, "jobs.json")
    }

    // ── AlarmManager ────────────────────────────────────────────────

    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    // ── Wake lock for execution ─────────────────────────────────────

    private var wakeLock: PowerManager.WakeLock? = null

    // ════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════

    /**
     * Start the cron scheduler daemon.
     *
     * Loads persisted jobs, calculates next run times, and schedules
     * all enabled jobs with the [AlarmManager]. Also starts a
     * foreground service for reliable background execution.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "Cron scheduler already running")
            return
        }

        Log.i(TAG, "Starting cron scheduler...")

        // Load persisted jobs
        loadJobs()

        // Schedule all enabled jobs
        val enabledJobs = jobs.values.filter { it.enabled }
        for (job in enabledJobs) {
            scheduleJob(job.id)
        }

        // Start foreground service for reliability
        startForegroundService()

        isRunning = true
        Log.i(TAG, "Cron scheduler started with ${enabledJobs.size} enabled job(s)")
    }

    /**
     * Stop the cron scheduler daemon.
     *
     * Cancels all pending alarms and stops the foreground service.
     * Jobs remain persisted and will be restored on next start.
     */
    fun stop() {
        if (!isRunning) {
            Log.d(TAG, "Cron scheduler not running")
            return
        }

        Log.i(TAG, "Stopping cron scheduler...")

        // Cancel all pending alarms
        for (job in jobs.values) {
            cancelAlarm(job.id)
            job.nextRunAt = 0L
        }
        persistJobs()

        // Stop foreground service
        stopForegroundService()

        // Release wake lock
        releaseWakeLock()

        isRunning = false
        Log.i(TAG, "Cron scheduler stopped")
    }

    /**
     * Add a new cron job.
     *
     * @param expression  Cron expression string (e.g. "*/5 * * * *")
     * @param command     Shell command to execute
     * @param label       Optional human-readable label
     * @param logOutput   Whether to capture command output (default true)
     * @param timeoutSec  Execution timeout in seconds (0 = no limit)
     * @param workDir     Working directory for command execution
     * @param envVars     Additional environment variables ("KEY=VALUE")
     * @return The created [CronJob], or null if the expression is invalid.
     */
    fun addJob(
        expression: String,
        command: String,
        label: String = "",
        logOutput: Boolean = true,
        timeoutSec: Int = 0,
        workDir: String = "",
        envVars: List<String> = emptyList()
    ): CronJob? {
        // Validate expression
        val parsed = CronExpression.parse(expression)
        if (parsed == null) {
            Log.w(TAG, "Invalid cron expression: $expression")
            return null
        }

        val validationError = parsed.validate()
        if (validationError != null) {
            Log.w(TAG, "Cron expression validation failed: $validationError")
            return null
        }

        // Create job
        val job = CronJob(
            id               = CronJob.generateId(),
            expression       = expression,
            command          = command,
            label            = label,
            logOutput        = logOutput,
            timeoutSeconds   = timeoutSec,
            workingDirectory = workDir,
            environmentVars  = envVars,
            createdAt        = System.currentTimeMillis()
        )

        // Store
        jobs[job.id] = job
        persistJobs()

        // Schedule if enabled and daemon is running
        if (job.enabled && isRunning) {
            scheduleJob(job.id)
        }

        Log.i(TAG, "Added cron job ${job.id}: $expression → ${command.take(60)}")
        return job
    }

    /**
     * Add a one-time scheduled command.
     *
     * @param executeAtMillis  Epoch millis when the command should run
     * @param command          Shell command to execute
     * @param label            Optional label
     * @return The created [CronJob], or null on error.
     */
    fun addOneTimeJob(
        executeAtMillis: Long,
        command: String,
        label: String = ""
    ): CronJob? {
        if (executeAtMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "One-time job time is in the past")
            return null
        }

        // Create a cron expression that won't naturally match again
        // We use the exact minute/hour/day/month for scheduling, then
        // disable the job after its first execution.
        val cal = Calendar.getInstance().apply { timeInMillis = executeAtMillis }
        val minute = cal.get(Calendar.MINUTE)
        val hour   = cal.get(Calendar.HOUR_OF_DAY)
        val day    = cal.get(Calendar.DAY_OF_MONTH)
        val month  = cal.get(Calendar.MONTH) + 1
        val dow    = cal.get(Calendar.DAY_OF_WEEK) - 1

        // Expression that matches the specific minute only
        val expression = "$minute $hour $day $month $dow"

        val job = CronJob(
            id               = CronJob.generateId(),
            expression       = expression,
            command          = command,
            label            = label.ifEmpty { "one-time" },
            createdAt        = System.currentTimeMillis(),
            nextRunAt        = executeAtMillis
        )

        jobs[job.id] = job
        persistJobs()

        if (isRunning) {
            scheduleAlarm(job.id, executeAtMillis)
        }

        Log.i(TAG, "Added one-time job ${job.id} for ${CronJob.formatTime(executeAtMillis)}")
        return job
    }

    /**
     * Remove a cron job by ID.
     *
     * @return true if the job was found and removed.
     */
    fun removeJob(jobId: String): Boolean {
        val removed = jobs.remove(jobId)
        if (removed != null) {
            cancelAlarm(jobId)
            persistJobs()
            Log.i(TAG, "Removed cron job: $jobId")
            return true
        }
        Log.w(TAG, "Job not found for removal: $jobId")
        return false
    }

    /**
     * Enable a cron job.
     */
    fun enableJob(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.enabled) return true
        job.enabled = true
        persistJobs()
        if (isRunning) {
            scheduleJob(jobId)
        }
        Log.i(TAG, "Enabled cron job: $jobId")
        return true
    }

    /**
     * Disable a cron job. Cancels its pending alarm.
     */
    fun disableJob(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        if (!job.enabled) return true
        job.enabled = false
        job.nextRunAt = 0L
        cancelAlarm(jobId)
        persistJobs()
        Log.i(TAG, "Disabled cron job: $jobId")
        return true
    }

    /**
     * Run a job immediately, regardless of its schedule.
     * Does not affect the existing schedule.
     */
    fun runJobNow(jobId: String): Boolean {
        val job = jobs[jobId] ?: return false
        Log.i(TAG, "Running job immediately: $jobId")

        val intent = Intent(context, CronReceiver::class.java).apply {
            action = CronReceiver.ACTION_CRON_RUN_NOW
            putExtra(CronReceiver.EXTRA_JOB_ID, jobId)
        }
        context.sendBroadcast(intent)
        return true
    }

    /**
     * Get a job by ID, or null if not found.
     */
    fun getJob(jobId: String): CronJob? = jobs[jobId]

    /**
     * Update an existing job's data (called after execution to persist
     * metadata changes like lastRunAt, executionCount, etc.).
     */
    fun updateJob(job: CronJob) {
        jobs[job.id] = job
        persistJobs()
    }

    /**
     * List all cron jobs.
     */
    fun listJobs(): List<CronJob> = jobs.values.toList()

    /**
     * List only enabled cron jobs.
     */
    fun listEnabledJobs(): List<CronJob> = jobs.values.filter { it.enabled }

    /**
     * Get the number of registered jobs.
     */
    fun jobCount(): Int = jobs.size

    /**
     * Get the number of enabled jobs.
     */
    fun enabledJobCount(): Int = jobs.values.count { it.enabled }

    /**
     * Get a formatted status string for the scheduler.
     */
    fun getStatus(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  TermX Cron Scheduler Status")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("Running:    $isRunning")
        sb.appendLine("Total jobs: ${jobs.size}")
        sb.appendLine("Enabled:    ${enabledJobCount()}")
        sb.appendLine("Disabled:   ${jobs.size - enabledJobCount()}")

        val now = System.currentTimeMillis()
        val nextJob = jobs.values
            .filter { it.enabled && it.nextRunAt > now }
            .minByOrNull { it.nextRunAt }

        if (nextJob != null) {
            sb.appendLine("Next run:   ${CronJob.formatTime(nextJob.nextRunAt)} (${nextJob.id})")
        } else {
            sb.appendLine("Next run:   none scheduled")
        }

        sb.appendLine("Data dir:   ${cronDir.absolutePath}")
        sb.appendLine("═══════════════════════════════════════")

        // Job listing
        if (jobs.isNotEmpty()) {
            sb.appendLine()
            for (job in jobs.values.sortedBy { it.createdAt }) {
                val status = job.statusString()
                val next = if (job.nextRunAt > 0) CronJob.formatTime(job.nextRunAt) else "-"
                val last = if (job.lastRunAt > 0) CronJob.formatTime(job.lastRunAt) else "never"
                sb.appendLine("  ${job.id.take(20).padEnd(20)} ${status.padEnd(12)} next=$next last=$last")
                sb.appendLine("  ${" ".repeat(20)} ${job.expression} → ${job.command.take(50)}")
            }
        }

        return sb.toString()
    }

    /**
     * Get a formatted listing of all jobs for terminal display.
     */
    fun listJobsFormatted(): String {
        if (jobs.isEmpty()) return "No cron jobs configured."

        val sb = StringBuilder()
        sb.appendLine("ID                   Status       Schedule            Command")
        sb.appendLine("─────────────────── ──────────── ────────────────── ──────────────────")

        for (job in jobs.values.sortedBy { it.createdAt }) {
            val id = job.id.take(20).padEnd(20)
            val status = job.statusString().padEnd(12)
            val schedule = job.expression.padEnd(18)
            val cmd = job.command.take(50)
            sb.appendLine("$id $status $schedule $cmd")
        }

        sb.appendLine()
        sb.appendLine("Total: ${jobs.size} job(s), ${enabledJobCount()} enabled")
        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    // Scheduling internals
    // ════════════════════════════════════════════════════════════════

    /**
     * Schedule a single job with the AlarmManager.
     * Calculates the next execution time and sets an exact alarm.
     */
    fun scheduleJob(jobId: String) {
        val job = jobs[jobId] ?: return
        if (!job.enabled) {
            cancelAlarm(jobId)
            return
        }

        val parsed = job.parsedExpression
        if (parsed == null) {
            Log.w(TAG, "Cannot schedule job $jobId – invalid expression: ${job.expression}")
            return
        }

        // @reboot jobs are handled separately in the boot receiver
        if (parsed.isReboot) {
            job.nextRunAt = 0L
            return
        }

        // Interval-based jobs
        if (parsed.intervalMinutes > 0) {
            val baseTime = if (job.intervalBaseTime > 0) job.intervalBaseTime else System.currentTimeMillis()
            val nextTime = baseTime + parsed.intervalMinutes * 60_000L
            job.nextRunAt = if (nextTime > System.currentTimeMillis()) nextTime else {
                System.currentTimeMillis() + parsed.intervalMinutes * 60_000L
            }
            scheduleAlarm(jobId, job.nextRunAt)
            persistJobs()
            return
        }

        // Standard cron expression – calculate next fire time
        val now = System.currentTimeMillis()
        val nextTime = parsed.nextExecutionTime(now)
        if (nextTime <= 0L) {
            Log.w(TAG, "No valid next execution time for job $jobId")
            job.nextRunAt = 0L
            return
        }

        job.nextRunAt = nextTime
        scheduleAlarm(jobId, nextTime)
        persistJobs()

        Log.d(TAG, "Scheduled job $jobId for ${CronJob.formatTime(nextTime)}")
    }

    /**
     * Set an exact alarm with the [AlarmManager] for the given job.
     */
    private fun scheduleAlarm(jobId: String, triggerAtMillis: Long) {
        val intent = Intent(context, CronReceiver::class.java).apply {
            action = CronReceiver.ACTION_CRON_FIRE
            putExtra(CronReceiver.EXTRA_JOB_ID, jobId)
        }

        val requestCode = jobId.hashCode() and 0x7FFFFFFF
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use setExactAndAllowWhileIdle for Doze compatibility
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Alarm set for job $jobId at ${CronJob.formatTime(triggerAtMillis)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot set exact alarm for job $jobId – missing permission?", e)
            // Fallback to inexact alarm
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm for job $jobId", e)
        }
    }

    /**
     * Cancel a pending alarm for the given job.
     */
    fun cancelAlarm(jobId: String) {
        val intent = Intent(context, CronReceiver::class.java).apply {
            action = CronReceiver.ACTION_CRON_FIRE
            putExtra(CronReceiver.EXTRA_JOB_ID, jobId)
        }

        val requestCode = jobId.hashCode() and 0x7FFFFFFF
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Cancelled alarm for job $jobId")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Persistence
    // ════════════════════════════════════════════════════════════════

    /**
     * Load jobs from the JSON persistence file.
     * Clears the in-memory store and replaces with persisted data.
     */
    fun loadJobs() {
        jobs.clear()

        if (!jobsFile.exists()) {
            Log.d(TAG, "No persisted jobs file found")
            return
        }

        try {
            val text = jobsFile.readText()
            if (text.isBlank()) return

            val jsonArray = JSONArray(text)
            for (i in 0 until jsonArray.length()) {
                try {
                    val job = CronJob.fromJSON(jsonArray.getJSONObject(i))
                    if (job != null) {
                        jobs[job.id] = job
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse job at index $i", e)
                }
            }
            Log.i(TAG, "Loaded ${jobs.size} job(s) from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load jobs from storage", e)
        }
    }

    /**
     * Persist all jobs to the JSON file.
     */
    fun persistJobs() {
        try {
            val jsonArray = JSONArray()
            for (job in jobs.values) {
                jsonArray.put(job.toJSON())
            }
            jobsFile.writeText(jsonArray.toString(2))
            Log.d(TAG, "Persisted ${jobs.size} job(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist jobs", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Foreground service
    // ════════════════════════════════════════════════════════════════

    private fun startForegroundService() {
        val intent = Intent(context, CronSchedulerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(context, CronSchedulerService::class.java)
        context.stopService(intent)
    }

    // ════════════════════════════════════════════════════════════════
    // Wake lock management
    // ════════════════════════════════════════════════════════════════

    /**
     * Acquire a partial wake lock during job execution.
     */
    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TermX::CronScheduler"
            ).apply {
                acquire(10 * 60 * 1000L) // 10-minute max
            }
            Log.d(TAG, "Wake lock acquired for cron execution")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Release the wake lock.
     */
    fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Shell command interface
    // ════════════════════════════════════════════════════════════════

    /**
     * Process a shell command from the `termx-cron` wrapper script.
     *
     * Supported commands:
     *   list                          List all cron jobs
     *   add "*/5 * * * * command"     Add a cron job
     *   remove <id>                   Remove a cron job
     *   enable <id>                   Enable a cron job
     *   disable <id>                  Disable a cron job
     *   run <id>                      Run a job immediately
     *   logs [id]                     View execution logs
     *   clear-logs                    Clear all execution logs
     *   start                         Start the cron daemon
     *   stop                          Stop the cron daemon
     *   status                        Show daemon status
     *   show <id>                     Show detailed job info
     *
     * @param args Command-line arguments (first element is the subcommand).
     * @return Output string for the terminal.
     */
    fun processCommand(args: List<String>): String {
        if (args.isEmpty()) {
            return getUsageText()
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "list", "ls", "l" -> listJobsFormatted()

            "add", "a" -> {
                if (args.size < 2) return "Usage: termx-cron add \"<expression> <command>\""
                val fullArg = args.drop(1).joinToString(" ")
                // Try to split expression from command
                val addResult = parseAndAddJob(fullArg)
                addResult
            }

            "remove", "rm", "r", "delete", "del" -> {
                if (args.size < 2) return "Usage: termx-cron remove <id>"
                val jobId = args[1]
                if (removeJob(jobId)) "Removed job: $jobId" else "Job not found: $jobId"
            }

            "enable", "en" -> {
                if (args.size < 2) return "Usage: termx-cron enable <id>"
                if (enableJob(args[1])) "Enabled job: ${args[1]}" else "Job not found: ${args[1]}"
            }

            "disable", "dis" -> {
                if (args.size < 2) return "Usage: termx-cron disable <id>"
                if (disableJob(args[1])) "Disabled job: ${args[1]}" else "Job not found: ${args[1]}"
            }

            "run" -> {
                if (args.size < 2) return "Usage: termx-cron run <id>"
                if (runJobNow(args[1])) "Triggered job: ${args[1]}" else "Job not found: ${args[1]}"
            }

            "logs" -> {
                val jobId = args.getOrElse(1) { null }
                CronReceiver.readLogs(context, jobId)
            }

            "clear-logs" -> {
                CronReceiver.clearLogs(context)
                "All execution logs cleared."
            }

            "start" -> {
                start()
                "Cron scheduler started."
            }

            "stop" -> {
                stop()
                "Cron scheduler stopped."
            }

            "status" -> getStatus()

            "show" -> {
                if (args.size < 2) return "Usage: termx-cron show <id>"
                val job = getJob(args[1])
                job?.detailedDescription() ?: "Job not found: ${args[1]}"
            }

            "help", "-h", "--help" -> getUsageText()

            else -> "Unknown command: ${args[0]}\n${getUsageText()}"
        }
    }

    /**
     * Parse a full "expression command" string and add it as a job.
     * Handles both special shorthand (e.g. "@hourly cmd") and standard
     * 5-field expressions (e.g. "*/5 * * * * cmd").
     */
    private fun parseAndAddJob(input: String): String {
        val trimmed = input.trim()

        // Check for special shorthand strings first
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("@") && !lower.startsWith("@every_")) {
            // @hourly, @daily, @weekly, @monthly, @reboot
            val parts = trimmed.split(Regex("\\s+"), 2)
            if (parts.size < 2) {
                return "Error: No command specified after ${parts[0]}"
            }
            val expression = parts[0]
            val command = parts[1]
            val job = addJob(expression, command)
            return if (job != null) {
                "Added job: ${job.id}\n  Schedule: $expression\n  Command:  $command"
            } else {
                "Error: Invalid expression '$expression'"
            }
        }

        if (lower.startsWith("@every_")) {
            // @every_5m command
            val match = Regex("^(@every_\\d+m?)\\s+(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(trimmed)
            if (match != null) {
                val expression = match.groupValues[1]
                val command = match.groupValues[2]
                val job = addJob(expression, command)
                return if (job != null) {
                    "Added job: ${job.id}\n  Schedule: $expression\n  Command:  $command"
                } else {
                    "Error: Invalid interval expression '$expression'"
                }
            }
            return "Error: Invalid @every_Nm syntax. Usage: @every_5m <command>"
        }

        // Standard 5-field cron expression
        // Split into 5 fields + remaining command
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 6) {
            return "Error: Expected 5 cron fields + command. Got ${parts.size} part(s).\n" +
                   "Format: minute hour day month weekday command"
        }

        val expression = parts.subList(0, 5).joinToString(" ")
        val command = parts.subList(5, parts.size).joinToString(" ")

        val job = addJob(expression, command)
        return if (job != null) {
            val desc = job.parsedExpression?.describe() ?: ""
            "Added job: ${job.id}\n  Schedule: $expression\n  Command:  $command\n  Human:   $desc"
        } else {
            "Error: Invalid cron expression '$expression'"
        }
    }

    /**
     * Get usage/help text for the termx-cron command.
     */
    private fun getUsageText(): String {
        return """
Usage: termx-cron <command> [arguments]

Commands:
  list                          List all cron jobs
  add "<expr> <cmd>"            Add a cron job
  remove <id>                   Remove a cron job
  enable <id>                   Enable a cron job
  disable <id>                  Disable a cron job
  run <id>                      Run a job immediately
  show <id>                     Show detailed job info
  logs [id]                     View execution logs
  clear-logs                    Clear all execution logs
  start                         Start the cron daemon
  stop                          Stop the cron daemon
  status                        Show daemon status

Cron Expression Format:
  ┌──────── minute (0-59)
  │ ┌────── hour (0-23)
  │ │ ┌──── day of month (1-31)
  │ │ │ ┌── month (1-12)
  │ │ │ │ ┌ day of week (0-6, 0=Sunday)
  │ │ │ │ │
  * * * * * command

  Field syntax: * | literal | range(1-5) | step(*/5) | list(1,3,5) | range-step(1-10/2)

Special strings:
  @hourly       Run once per hour
  @daily        Run once per day
  @weekly       Run once per week
  @monthly      Run once per month
  @reboot       Run on device boot
  @every_Nm     Run every N minutes

Examples:
  termx-cron add "*/5 * * * * echo hello"
  termx-cron add "@hourly /path/to/backup.sh"
  termx-cron add "@every_10m ping -c 1 8.8.8.8"
  termx-cron add "0 2 * * * /path/to/cleanup.sh"
  termx-cron list
  termx-cron run cron_abc123
  termx-cron logs
  termx-cron status
""".trimIndent()
    }

    // ════════════════════════════════════════════════════════════════
    // Singleton
    // ════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "CronScheduler"

        @Volatile
        private var instance: CronScheduler? = null

        /**
         * Get the singleton [CronScheduler] instance.
         * Creates it on first access.
         */
        fun getInstance(context: Context): CronScheduler {
            return instance ?: synchronized(this) {
                instance ?: CronScheduler(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Process a shell command using the singleton instance.
         * Convenience method for use from shell wrappers.
         */
        fun processShellCommand(context: Context, args: List<String>): String {
            return getInstance(context).processCommand(args)
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// Foreground service for the cron scheduler
// ════════════════════════════════════════════════════════════════════

/**
 * Foreground service that keeps the cron scheduler alive.
 * Displays a persistent notification with job count and next run time.
 */
class CronSchedulerService : Service() {

    companion object {
        private const val TAG = "CronSchedulerService"
        const val CHANNEL_ID = "termx_cron_channel"
        const val NOTIFICATION_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Cron scheduler foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Update notification with current status
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Cron scheduler foreground service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cron Scheduler",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the cron scheduler running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val scheduler = CronScheduler.getInstance(this)
        val jobCount = scheduler.enabledJobCount()

        val now = System.currentTimeMillis()
        val nextJob = scheduler.listEnabledJobs()
            .filter { it.nextRunAt > now }
            .minByOrNull { it.nextRunAt }
        val nextRunText = if (nextJob != null) {
            "Next: ${CronJob.formatTime(nextJob.nextRunAt)}"
        } else {
            "No upcoming runs"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TermX Cron")
            .setContentText("$jobCount job(s) active · $nextRunText")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
