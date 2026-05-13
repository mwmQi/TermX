package com.termx.app.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.termx.app.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service for the TermX Screen Recorder.
 *
 * Keeps the screen recording running in the background with a persistent
 * notification. The notification displays:
 * - Recording status and duration
 * - Output file path and size
 * - Quick actions: stop recording, pause/resume
 *
 * This service is required for MediaProjection to continue recording
 * when the app is in the background. It also manages the recording
 * lifecycle and provides reliable cleanup.
 *
 * Requires: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION
 */
class ScreenRecorderService : Service() {

    companion object {
        private const val TAG = "ScreenRecorderService"
        const val CHANNEL_ID = "termx_screen_record_channel"
        const val NOTIFICATION_ID = 2003

        var isRunning = false
            private set

        // Intent actions from notification
        const val ACTION_START_RECORDING = "com.termx.app.action.RECORD_START"
        const val ACTION_STOP_RECORDING = "com.termx.app.action.RECORD_STOP"
        const val ACTION_PAUSE_RECORDING = "com.termx.app.action.RECORD_PAUSE"
        const val ACTION_RESUME_RECORDING = "com.termx.app.action.RECORD_RESUME"

        // Intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_OUTPUT = "output_path"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FRAME_RATE = "frame_rate"
        const val EXTRA_AUDIO = "audio_enabled"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationUpdateRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Screen recorder service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT) ?: ""
                val width = intent.getIntExtra(EXTRA_WIDTH, 720)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
                val frameRate = intent.getIntExtra(EXTRA_FRAME_RATE, 30)
                val audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)

                startRecording(resultCode, resultData, duration, outputPath,
                    width, height, bitrate, frameRate, audioEnabled)
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
                stopSelf()
            }
            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }
            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }
            else -> {
                // Default: show current status
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopNotificationUpdates()
        if (ScreenRecorderApi.isCurrentlyRecording()) {
            ScreenRecorderApi.stopRecording()
        }
        isRunning = false
        Log.i(TAG, "Screen recorder service destroyed")
    }

    /**
     * Start screen recording with the given MediaProjection permission.
     */
    private fun startRecording(
        resultCode: Int,
        resultData: Intent?,
        durationMs: Long,
        outputPath: String,
        width: Int,
        height: Int,
        bitrate: Int,
        frameRate: Int,
        audioEnabled: Boolean
    ) {
        try {
            if (!ScreenRecorderApi.isCurrentlyRecording()) {
                // Initialize the API if needed
                ScreenRecorderApi.initialize(this)

                // Set MediaProjection permission
                if (resultData != null) {
                    ScreenRecorderApi.setPermission(resultCode, resultData)
                } else {
                    Log.e(TAG, "MediaProjection result data is null")
                    stopSelf()
                    return
                }

                // Build recording config
                val config = ScreenRecorderApi.RecordingConfig(
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    frameRate = frameRate,
                    durationMs = durationMs,
                    outputPath = outputPath,
                    audioEnabled = audioEnabled
                )

                val result = ScreenRecorderApi.startRecording(this, config)
                Log.i(TAG, "Recording start result: $result")
            }

            // Start periodic notification updates
            startNotificationUpdates()

            WakeLockManager.acquire(this, "TermX-Record")
            updateNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            stopSelf()
        }
    }

    /**
     * Stop the current recording.
     */
    private fun stopRecording() {
        try {
            stopNotificationUpdates()
            val result = ScreenRecorderApi.stopRecording()
            Log.i(TAG, "Recording stop result: $result")
            WakeLockManager.release()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    /**
     * Pause the current recording.
     */
    private fun pauseRecording() {
        try {
            val result = ScreenRecorderApi.pauseRecording()
            Log.i(TAG, "Recording pause result: $result")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing recording", e)
        }
    }

    /**
     * Resume the current recording.
     */
    private fun resumeRecording() {
        try {
            val result = ScreenRecorderApi.resumeRecording()
            Log.i(TAG, "Recording resume result: $result")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming recording", e)
        }
    }

    /**
     * Start periodic notification updates to show recording duration.
     */
    private fun startNotificationUpdates() {
        stopNotificationUpdates()
        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                if (ScreenRecorderApi.isCurrentlyRecording()) {
                    updateNotification()
                    mainHandler.postDelayed(this, 1000) // Update every second
                }
            }
        }
        mainHandler.post(notificationUpdateRunnable!!)
    }

    /**
     * Stop periodic notification updates.
     */
    private fun stopNotificationUpdates() {
        notificationUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        notificationUpdateRunnable = null
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows screen recording status and controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Update the foreground notification with current recording state.
     */
    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    /**
     * Build the foreground notification.
     */
    private fun buildNotification(): Notification {
        val isRecording = ScreenRecorderApi.isCurrentlyRecording()
        val isPaused = ScreenRecorderApi.isCurrentlyPaused()
        val outputPath = ScreenRecorderApi.getCurrentOutputPath()

        val title = when {
            isPaused -> "Recording Paused"
            isRecording -> "Recording..."
            else -> "Screen Recorder"
        }

        val content = when {
            isPaused -> "Tap to resume | $outputPath"
            isRecording -> {
                val status = ScreenRecorderApi.getStatus()
                // Extract duration from status
                val durationLine = status.substringAfter("Duration:").substringBefore("\n").trim()
                val sizeLine = status.substringAfter("File Size:").substringBefore("\n").trim()
                "$durationLine | $sizeLine"
            }
            else -> "Ready"
        }

        // Content intent — open app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(isRecording || isPaused)

        if (isRecording) {
            // Pause action
            val pauseIntent = PendingIntent.getService(
                this, 1,
                Intent(this, ScreenRecorderService::class.java).setAction(ACTION_PAUSE_RECORDING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pauseIntent
            )
        } else if (isPaused) {
            // Resume action
            val resumeIntent = PendingIntent.getService(
                this, 1,
                Intent(this, ScreenRecorderService::class.java).setAction(ACTION_RESUME_RECORDING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                resumeIntent
            )
        }

        // Stop action (always available when service is running)
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenRecorderService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            stopIntent
        )

        return builder.build()
    }
}
