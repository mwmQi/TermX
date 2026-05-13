package com.termx.app.power

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen Recorder API for TermX — record screen from terminal.
 *
 * Provides screen recording capabilities using Android's MediaProjection API.
 * Supports configurable resolution, bitrate, frame rate, and output format.
 * Recording requires user permission via the MediaProjection permission dialog.
 *
 * Shell usage:
 *   termx-record start [duration] [output]  Start recording
 *   termx-record stop                        Stop recording
 *   termx-record status                      Recording status
 *   termx-record info                        Recording info
 *
 * Note: Screen recording requires a MediaProjection token obtained through
 * the system permission dialog. The API must be initialized with a valid
 * result code and intent data from MediaProjectionManager.createScreenCaptureIntent().
 *
 * Requires: FOREGROUND_SERVICE, and on API 34+ FOREGROUND_SERVICE_MEDIA_PLAYBACK.
 */
object ScreenRecorderApi {

    private const val TAG = "ScreenRecorderApi"

    // Default recording parameters
    private const val DEFAULT_BITRATE = 8_000_000   // 8 Mbps
    private const val DEFAULT_FRAME_RATE = 30
    private const val DEFAULT_DURATION_MS = 0L       // 0 = unlimited
    private const val VIDEO_WIDTH = 720
    private const val VIDEO_HEIGHT = 1280
    private const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

    // Recording state
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    // Recording info tracking
    private val recordingStartTime = AtomicLong(0)
    private val totalRecordedSize = AtomicLong(0)
    private val currentOutputPath = AtomicReference<String>("")
    private var frameCount = AtomicLong(0)

    // MediaProjection components
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var projectionManager: MediaProjectionManager? = null

    // Background thread for recording
    private var recorderThread: HandlerThread? = null
    private var recorderHandler: Handler? = null
    private var mainHandler: Handler? = null

    // Duration timer
    private var durationRunnable: Runnable? = null

    // Permission data (set externally before starting)
    private var permissionResultCode: Int = 0
    private var permissionData: Intent? = null

    /**
     * Configuration for screen recording.
     */
    data class RecordingConfig(
        val width: Int = VIDEO_WIDTH,
        val height: Int = VIDEO_HEIGHT,
        val bitrate: Int = DEFAULT_BITRATE,
        val frameRate: Int = DEFAULT_FRAME_RATE,
        val durationMs: Long = DEFAULT_DURATION_MS,
        val outputPath: String = "",
        val audioEnabled: Boolean = false
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("  Resolution: ${width}x${height}")
            appendLine("  Bitrate:    ${bitrate / 1_000_000} Mbps")
            appendLine("  Frame Rate: ${frameRate} fps")
            appendLine("  Duration:   ${if (durationMs > 0) "${durationMs / 1000}s" else "Unlimited"}")
            appendLine("  Audio:      ${if (audioEnabled) "Enabled" else "Disabled"}")
            appendLine("  Output:     ${outputPath.ifBlank { "Auto-generated" }}")
        }
    }

    /**
     * Recording status information.
     */
    data class RecordingStatus(
        val isRecording: Boolean,
        val isPaused: Boolean,
        val outputPath: String,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val config: RecordingConfig?
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("=== Screen Recording Status ===")
            appendLine("  Recording:  $isRecording")
            appendLine("  Paused:     $isPaused")
            if (isRecording) {
                appendLine("  Output:     $outputPath")
                appendLine("  Duration:   ${formatDurationMs(durationMs)}")
                appendLine("  File Size:  ${formatFileSize(fileSizeBytes)}")
                config?.let { append(it.toFormattedString()) }
            }
        }
    }

    // ---- Public API Methods ----

    /**
     * Initialize the Screen Recorder API with the application context.
     * Must be called before any recording operations.
     */
    fun initialize(context: Context): String {
        return try {
            projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mainHandler = Handler(Looper.getMainLooper())
            isInitialized.set(true)
            Log.i(TAG, "ScreenRecorderApi initialized")
            "Screen Recorder API initialized. Use setPermission() before starting recording."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ScreenRecorderApi", e)
            "Error initializing: ${e.message}"
        }
    }

    /**
     * Set the MediaProjection permission data.
     * This must be called with the result from the screen capture permission dialog
     * before starting a recording.
     *
     * @param resultCode The result code from onActivityResult
     * @param data The intent data from onActivityResult
     */
    fun setPermission(resultCode: Int, data: Intent) {
        permissionResultCode = resultCode
        permissionData = data
        Log.d(TAG, "MediaProjection permission data set (resultCode=$resultCode)")
    }

    /**
     * Start screen recording.
     *
     * @param context Application context
     * @param config Recording configuration (resolution, bitrate, etc.)
     * @return Status message
     */
    fun startRecording(context: Context, config: RecordingConfig = RecordingConfig()): String {
        return try {
            if (isRecording.get()) {
                return "Error: Already recording. Stop current recording first."
            }

            if (!isInitialized.get()) {
                initialize(context)
            }

            val projData = permissionData
                ?: return "Error: MediaProjection permission not granted. Call setPermission() first."

            // Get MediaProjection
            mediaProjection = projectionManager?.getMediaProjection(permissionResultCode, projData)
                ?: return "Error: Failed to acquire MediaProjection. Permission may have been denied."

            // Setup output path
            val outputPath = if (config.outputPath.isNotBlank()) {
                config.outputPath
            } else {
                generateOutputPath(context)
            }

            // Ensure output directory exists
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            // Determine recording dimensions
            val metrics = getDisplayMetrics(context)
            val width = if (config.width > 0) config.width else minOf(metrics.widthPixels, VIDEO_WIDTH)
            val height = if (config.height > 0) config.height else minOf(metrics.heightPixels, VIDEO_HEIGHT)

            // Setup recorder thread
            recorderThread = HandlerThread("ScreenRecorder", Thread.NORM_PRIORITY)
            recorderThread?.start()
            recorderHandler = Handler(recorderThread!!.looper)

            // Create and configure MediaRecorder
            mediaRecorder = createMediaRecorder(context, config.copy(
                width = width,
                height = height,
                outputPath = outputPath
            ))

            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TermX ScreenRecorder",
                width,
                height,
                metrics.densityDpi,
                VIRTUAL_DISPLAY_FLAGS,
                null,
                null,
                recorderHandler
            )

            // Start recording
            mediaRecorder?.start()
            isRecording.set(true)
            isPaused.set(false)
            recordingStartTime.set(System.currentTimeMillis())
            currentOutputPath.set(outputPath)
            frameCount.set(0)

            // Setup duration limit
            if (config.durationMs > 0) {
                setupDurationLimit(config.durationMs)
            }

            // Register callback for projection stop
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                    stopRecordingInternal()
                }
            }, recorderHandler)

            Log.i(TAG, "Recording started: ${width}x${height} -> $outputPath")
            buildString {
                appendLine("Recording started")
                appendLine("  Output: ${width}x${height} @ ${config.bitrate / 1_000_000}Mbps")
                appendLine("  File:   $outputPath")
                if (config.durationMs > 0) {
                    appendLine("  Limit:  ${config.durationMs / 1000}s")
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start recording (state error)", e)
            cleanupRecording()
            "Error starting recording: ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanupRecording()
            "Error starting recording: ${e.message}"
        }
    }

    /**
     * Stop the current recording.
     */
    fun stopRecording(): String {
        if (!isRecording.get()) {
            return "Not currently recording"
        }

        val startTime = recordingStartTime.get()
        val duration = System.currentTimeMillis() - startTime
        val path = currentOutputPath.get()

        stopRecordingInternal()

        val fileSize = File(path).let { if (it.exists()) it.length() else 0L }
        Log.i(TAG, "Recording stopped. Duration: ${formatDurationMs(duration)}, Size: ${formatFileSize(fileSize)}")

        return buildString {
            appendLine("Recording stopped")
            appendLine("  Duration: ${formatDurationMs(duration)}")
            appendLine("  File:     $path")
            appendLine("  Size:     ${formatFileSize(fileSize)}")
        }
    }

    /**
     * Get the current recording status.
     */
    fun getStatus(): String {
        val duration = if (isRecording.get()) {
            System.currentTimeMillis() - recordingStartTime.get()
        } else 0L

        val fileSize = currentOutputPath.get().let { path ->
            if (path.isNotBlank()) {
                val file = File(path)
                if (file.exists()) file.length() else 0L
            } else 0L
        }

        val status = RecordingStatus(
            isRecording = isRecording.get(),
            isPaused = isPaused.get(),
            outputPath = currentOutputPath.get(),
            durationMs = duration,
            fileSizeBytes = fileSize,
            config = null
        )

        return status.toFormattedString()
    }

    /**
     * Get detailed recording info including configuration.
     */
    fun getInfo(context: Context): String {
        return buildString {
            appendLine("=== Screen Recorder Info ===")
            appendLine("  Initialized:    ${isInitialized.get()}")
            appendLine("  Has Permission: ${permissionData != null}")
            appendLine("  Recording:      ${isRecording.get()}")
            appendLine("  Paused:         ${isPaused.get()}")

            if (isRecording.get()) {
                val duration = System.currentTimeMillis() - recordingStartTime.get()
                appendLine("  Duration:       ${formatDurationMs(duration)}")
                appendLine("  Output Path:    ${currentOutputPath.get()}")

                val file = File(currentOutputPath.get())
                if (file.exists()) {
                    appendLine("  File Size:      ${formatFileSize(file.length())}")
                }
            }

            appendLine()
            appendLine("=== Device Display ===")
            val metrics = getDisplayMetrics(context)
            appendLine("  Resolution:     ${metrics.widthPixels}x${metrics.heightPixels}")
            appendLine("  Density:        ${metrics.densityDpi}dpi")
            appendLine("  Density Scale:  ${metrics.density}")

            appendLine()
            appendLine("=== Default Configuration ===")
            val defaultConfig = RecordingConfig()
            append(defaultConfig.toFormattedString())
        }
    }

    /**
     * Pause the current recording.
     * Requires API 24+ (Android 7.0 Nougat).
     */
    fun pauseRecording(): String {
        return try {
            if (!isRecording.get()) {
                return "Not currently recording"
            }

            if (isPaused.get()) {
                return "Recording is already paused"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused.set(true)
                Log.i(TAG, "Recording paused")
                "Recording paused"
            } else {
                "Error: Pause requires Android 7.0+ (API 24)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
            "Error pausing recording: ${e.message}"
        }
    }

    /**
     * Resume a paused recording.
     * Requires API 24+ (Android 7.0 Nougat).
     */
    fun resumeRecording(): String {
        return try {
            if (!isRecording.get()) {
                return "Not currently recording"
            }

            if (!isPaused.get()) {
                return "Recording is not paused"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                isPaused.set(false)
                Log.i(TAG, "Recording resumed")
                "Recording resumed"
            } else {
                "Error: Resume requires Android 7.0+ (API 24)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
            "Error resuming recording: ${e.message}"
        }
    }

    /**
     * List all recordings in the output directory.
     */
    fun listRecordings(context: Context): String {
        val outputDir = getOutputDirectory(context)
        val files = outputDir.listFiles { file ->
            file.extension.equals("mp4", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            return "No recordings found"
        }

        return buildString {
            appendLine("=== Screen Recordings (${files.size}) ===")
            files.forEach { file ->
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(file.lastModified()))
                appendLine("  ${file.name}  ${formatFileSize(file.length())}  $date")
            }
        }
    }

    /**
     * Release all resources held by the Screen Recorder API.
     */
    fun release(): String {
        stopRecordingInternal()
        projectionManager = null
        permissionData = null
        isInitialized.set(false)
        Log.i(TAG, "ScreenRecorderApi released")
        return "Screen Recorder API released"
    }

    /**
     * Check if currently recording.
     */
    fun isCurrentlyRecording(): Boolean = isRecording.get()

    /**
     * Check if recording is paused.
     */
    fun isCurrentlyPaused(): Boolean = isPaused.get()

    /**
     * Get the current recording output path.
     */
    fun getCurrentOutputPath(): String = currentOutputPath.get()

    // ---- Internal Helpers ----

    /**
     * Create and configure a MediaRecorder instance.
     */
    private fun createMediaRecorder(context: Context, config: RecordingConfig): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(config.outputPath)
        recorder.setVideoEncodingBitRate(config.bitrate)
        recorder.setVideoFrameRate(config.frameRate)
        recorder.setVideoSize(config.width, config.height)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (config.audioEnabled) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44100)
        }

        recorder.prepare()
        return recorder
    }

    /**
     * Internal method to stop recording and clean up resources.
     */
    private fun stopRecordingInternal() {
        isRecording.set(false)
        isPaused.set(false)

        // Cancel duration timer
        durationRunnable?.let { mainHandler?.removeCallbacks(it) }
        durationRunnable = null

        try {
            mediaRecorder?.apply {
                try { stop() } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder stop() failed (may be expected)", e)
                }
                try { release() } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder release() failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaRecorder", e)
        }
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay", e)
        }
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        try {
            recorderThread?.quitSafely()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder thread", e)
        }
        recorderThread = null
        recorderHandler = null
    }

    /**
     * Clean up recording resources on error.
     */
    private fun cleanupRecording() {
        isRecording.set(false)
        isPaused.set(false)

        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { recorderThread?.quitSafely() } catch (_: Exception) {}
        recorderThread = null
        recorderHandler = null
    }

    /**
     * Setup a duration-based auto-stop timer.
     */
    private fun setupDurationLimit(durationMs: Long) {
        durationRunnable = Runnable {
            if (isRecording.get()) {
                Log.i(TAG, "Duration limit reached (${durationMs}ms), stopping recording")
                stopRecordingInternal()
            }
        }
        mainHandler?.postDelayed(durationRunnable!!, durationMs)
    }

    /**
     * Generate an automatic output file path for the recording.
     */
    private fun generateOutputPath(context: Context): String {
        val outputDir = getOutputDirectory(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(outputDir, "TermX_record_$timestamp.mp4").absolutePath
    }

    /**
     * Get the output directory for recordings.
     */
    private fun getOutputDirectory(context: Context): File {
        val moviesDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "TermX")
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }
        return moviesDir
    }

    /**
     * Get display metrics for the default display.
     */
    private fun getDisplayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics
    }

    /**
     * Format milliseconds to a human-readable duration string.
     */
    private fun formatDurationMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60

        return when {
            hrs > 0 -> String.format("%02d:%02d:%02d", hrs, mins, secs)
            mins > 0 -> String.format("%02d:%02d", mins, secs)
            else -> "${secs}s"
        }
    }

    /**
     * Format file size in bytes to a human-readable string.
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return "${"%.1f".format(size)} ${units[unitIndex]}"
    }
}
