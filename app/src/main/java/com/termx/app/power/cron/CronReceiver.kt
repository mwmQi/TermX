package com.termx.app.power.cron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.termx.app.utils.ShellUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BroadcastReceiver that handles cron alarm triggers.
 *
 * When the [AlarmManager] fires a pending intent for a scheduled cron job,
 * this receiver is invoked. It:
 *
 *  1. Reads the job ID from the incoming intent
 *  2. Loads the job from persistent storage
 *  3. Verifies the job is still enabled and valid
 *  4. Executes the shell command (with optional timeout)
 *  5. Captures stdout/stderr and logs the result
 *  6. Updates job metadata (last run time, execution count)
 *  7. Schedules the next occurrence via [CronScheduler]
 *
 * This receiver is also used to handle:
 *  - Boot completed events (to restore @reboot and scheduled jobs)
 *  - Explicit run-now commands
 *  - Daemon start/stop actions
 *
 * Intent actions:
 *   ACTION_CRON_FIRE    – Alarm fired for a specific job
 *   ACTION_CRON_BOOT    – Device just booted, restore all jobs
 *   ACTION_CRON_RUN_NOW – Run a specific job immediately
 */
class CronReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CronReceiver"

        /** Alarm fired for a scheduled cron job. */
        const val ACTION_CRON_FIRE = "com.termx.app.cron.FIRE"

        /** Device boot completed – restore cron jobs. */
        const val ACTION_CRON_BOOT = "com.termx.app.cron.BOOT"

        /** Explicit request to run a job immediately. */
        const val ACTION_CRON_RUN_NOW = "com.termx.app.cron.RUN_NOW"

        /** Extra key for the job ID in the intent. */
        const val EXTRA_JOB_ID = "job_id"

        /** Maximum log entries to keep per job before rotation. */
        private const val MAX_LOG_ENTRIES = 100

        /** Thread pool for concurrent job execution. */
        private val executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        )

        /** Guard to prevent re-entrant boot restoration. */
        private val bootRestoring = AtomicBoolean(false)

        /**
         * Read execution logs for a specific job or all jobs.
         * Returns a formatted string suitable for terminal display.
         */
        @JvmStatic
        fun readLogs(context: Context, jobId: String?): String {
            val logDir = File(context.filesDir, "cron/logs")
            if (!logDir.exists()) return "No logs found."

            val sb = StringBuilder()

            if (jobId != null) {
                // Read logs for a specific job
                val logFile = File(logDir, "${jobId}.json")
                if (!logFile.exists()) return "No logs found for job: $jobId"

                try {
                    val entries = org.json.JSONArray(logFile.readText())
                    for (i in 0 until entries.length()) {
                        val entry = CronJob.ExecutionLog.fromJSON(entries.getJSONObject(i))
                        if (entry != null) {
                            sb.appendLine(entry.formattedOutput())
                        }
                    }
                } catch (e: Exception) {
                    return "Error reading logs: ${e.message}"
                }
            } else {
                // Read logs for all jobs
                val logFiles = logDir.listFiles { f -> f.name.endsWith(".json") }
                    ?: return "No logs found."

                for (logFile in logFiles.sortedByDescending { it.lastModified() }) {
                    try {
                        val entries = org.json.JSONArray(logFile.readText())
                        for (i in 0 until entries.length()) {
                            val entry = CronJob.ExecutionLog.fromJSON(entries.getJSONObject(i))
                            if (entry != null) {
                                sb.appendLine(entry.formattedOutput())
                            }
                        }
                    } catch (e: Exception) {
                        sb.appendLine("Error reading ${logFile.name}: ${e.message}")
                    }
                }
            }

            return sb.toString().ifBlank { "No log entries found." }
        }

        /**
         * Clear execution logs, optionally only for a specific job.
         */
        @JvmStatic
        fun clearLogs(context: Context, jobId: String? = null) {
            val logDir = File(context.filesDir, "cron/logs")
            if (!logDir.exists()) return

            if (jobId != null) {
                val logFile = File(logDir, "${jobId}.json")
                if (logFile.exists()) logFile.delete()
            } else {
                logDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CRON_FIRE -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId.isNullOrBlank()) {
                    Log.w(TAG, "Cron fire received with no job ID")
                    return
                }
                Log.d(TAG, "Cron alarm fired for job: $jobId")
                handleCronFire(context, jobId)
            }

            ACTION_CRON_BOOT -> {
                Log.i(TAG, "Cron boot event received, restoring jobs...")
                handleBootRestore(context)
            }

            ACTION_CRON_RUN_NOW -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId.isNullOrBlank()) {
                    Log.w(TAG, "Run-now received with no job ID")
                    return
                }
                Log.d(TAG, "Run-now requested for job: $jobId")
                handleCronFire(context, jobId)
            }

            else -> {
                Log.w(TAG, "Unknown cron action: ${intent.action}")
            }
        }
    }

    // ── Alarm fire handler ──────────────────────────────────────────

    /**
     * Handle a cron alarm firing for the given [jobId].
     * Executes the job's command and reschedules.
     */
    private fun handleCronFire(context: Context, jobId: String) {
        // We must acquire a wake lock to ensure the device stays awake
        // while we execute the command. The scheduler will release it.
        val scheduler = CronScheduler.getInstance(context)

        val job = scheduler.getJob(jobId)
        if (job == null) {
            Log.w(TAG, "Job not found for alarm: $jobId – may have been removed")
            scheduler.cancelAlarm(jobId)
            return
        }

        if (!job.enabled) {
            Log.d(TAG, "Job $jobId is disabled, skipping execution")
            return
        }

        // Execute on a background thread (goAsync has a 10s limit)
        val pendingResult = goAsync()
        executor.execute {
            try {
                executeJob(context, scheduler, job)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing job $jobId", e)
            } finally {
                // Schedule the next occurrence
                try {
                    scheduler.scheduleJob(jobId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error rescheduling job $jobId", e)
                }
                pendingResult.finish()
            }
        }
    }

    // ── Job execution ───────────────────────────────────────────────

    /**
     * Execute a cron job's command, capture output, and log results.
     */
    private fun executeJob(
        context: Context,
        scheduler: CronScheduler,
        job: CronJob
    ) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Executing job ${job.id}: ${job.command.take(80)}")

        var exitCode = -1
        var stdout = ""
        var stderr = ""

        try {
            val result = if (job.timeoutSeconds > 0) {
                executeWithTimeout(job)
            } else {
                // Execute without timeout using ShellUtils
                val cwd = if (job.workingDirectory.isNotBlank()) job.workingDirectory else "/"
                ShellUtils.execute(job.command, cwd)
            }

            exitCode = result.exitCode
            stdout = result.stdout
            stderr = result.stderr
        } catch (e: Exception) {
            stderr = "Execution error: ${e.message}"
            Log.e(TAG, "Job ${job.id} execution failed", e)
        }

        val durationMs = System.currentTimeMillis() - startTime
        Log.i(TAG, "Job ${job.id} completed: exit=$exitCode, took=${durationMs}ms")

        // Update job metadata
        job.lastRunAt = startTime
        job.executionCount++

        // For @reboot jobs, mark as having run this boot
        if (job.isRebootJob) {
            job.hasRunThisBoot = true
        }

        // For interval jobs, update the base time
        if (job.isIntervalJob) {
            job.intervalBaseTime = startTime
        }

        // Persist updated job
        scheduler.updateJob(job)

        // Log execution output if enabled
        if (job.logOutput) {
            val logEntry = CronJob.ExecutionLog(
                jobId     = job.id,
                timestamp = startTime,
                command   = job.command,
                exitCode  = exitCode,
                stdout    = truncateOutput(stdout),
                stderr    = truncateOutput(stderr),
                durationMs = durationMs
            )
            appendLogEntry(context, job.id, logEntry)
        }

        // Log significant failures
        if (exitCode != 0) {
            Log.w(TAG, "Job ${job.id} exited with code $exitCode")
        }
    }

    /**
     * Execute a command with a timeout.
     * Uses a separate thread and Process.waitFor(timeout).
     */
    private fun executeWithTimeout(job: CronJob): ShellUtils.ShellResult {
        val cwd = if (job.workingDirectory.isNotBlank()) job.workingDirectory else "/"

        val process = Runtime.getRuntime().exec(
            arrayOf("/system/bin/sh", "-c", job.command),
            job.environmentVars.toTypedArray(),
            File(cwd)
        )

        var completed = false
        val future = executor.submit {
            try {
                process.waitFor()
                completed = true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            future.get(job.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            process.destroyForcibly()
            future.cancel(true)
            return ShellUtils.ShellResult(
                "", "Command timed out after ${job.timeoutSeconds}s", -9
            )
        } catch (e: Exception) {
            process.destroyForcibly()
            return ShellUtils.ShellResult(
                "", "Execution error: ${e.message}", -1
            )
        }

        if (!completed) {
            process.destroyForcibly()
            return ShellUtils.ShellResult("", "Execution interrupted", -1)
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }

        return ShellUtils.ShellResult(stdout, stderr, exitCode)
    }

    // ── Logging ─────────────────────────────────────────────────────

    /**
     * Append an execution log entry to the job's log file.
     * Keeps at most [MAX_LOG_ENTRIES] entries per job, rotating oldest first.
     */
    private fun appendLogEntry(
        context: Context,
        jobId: String,
        entry: CronJob.ExecutionLog
    ) {
        try {
            val logDir = File(context.filesDir, "cron/logs")
            if (!logDir.exists()) logDir.mkdirs()

            val logFile = File(logDir, "${jobId}.json")
            val entries = mutableListOf<JSONObject>()

            // Read existing log entries
            if (logFile.exists()) {
                try {
                    val existing = logFile.readText()
                    val jsonArray = org.json.JSONArray(existing)
                    for (i in 0 until jsonArray.length()) {
                        entries.add(jsonArray.getJSONObject(i))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read log file for $jobId, starting fresh", e)
                }
            }

            // Append new entry
            entries.add(entry.toJSON())

            // Rotate: keep only the most recent entries
            while (entries.size > MAX_LOG_ENTRIES) {
                entries.removeAt(0)
            }

            // Write back
            val outputArray = org.json.JSONArray()
            for (jsonObj in entries) {
                outputArray.put(jsonObj)
            }
            logFile.writeText(outputArray.toString(2))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log entry for job $jobId", e)
        }
    }

    // ── Boot restoration ────────────────────────────────────────────

    /**
     * Restore all cron jobs after a device reboot.
     * Re-schedules enabled jobs and runs @reboot jobs.
     */
    private fun handleBootRestore(context: Context) {
        if (!bootRestoring.compareAndSet(false, true)) {
            Log.d(TAG, "Boot restore already in progress, skipping")
            return
        }

        try {
            val scheduler = CronScheduler.getInstance(context)

            // Start the scheduler (this also restores jobs from storage)
            scheduler.start()

            // Reset hasRunThisBoot for all @reboot jobs
            val rebootJobs = scheduler.listJobs().filter { it.isRebootJob && it.enabled }
            for (job in rebootJobs) {
                job.hasRunThisBoot = false
                scheduler.updateJob(job)
            }

            // Run @reboot jobs
            for (job in rebootJobs) {
                Log.i(TAG, "Running @reboot job: ${job.id}")
                executor.execute {
                    try {
                        executeJob(context, scheduler, job)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to run @reboot job ${job.id}", e)
                    }
                }
            }

            Log.i(TAG, "Boot restore complete. ${rebootJobs.size} @reboot job(s) executed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during boot restore", e)
        } finally {
            bootRestoring.set(false)
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    /**
     * Truncate output to a reasonable size for logging.
     * Keeps the first 4KB and notes if truncated.
     */
    private fun truncateOutput(output: String): String {
        val maxLen = 4096
        return if (output.length <= maxLen) {
            output
        } else {
            output.substring(0, maxLen) + "\n... [truncated, ${output.length - maxLen} more chars]"
        }
    }

}
