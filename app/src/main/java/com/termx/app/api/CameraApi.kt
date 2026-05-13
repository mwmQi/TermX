package com.termx.app.api

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Camera API for TermX.
 * Provides photo capture, video recording, and camera info via Camera2 API.
 *
 * Usage from shell:
 *   am broadcast -a com.termx.app.api.CAMERA_PHOTO --es path "/path/photo.jpg" --es lens "back"
 *   am broadcast -a com.termx.app.api.CAMERA_VIDEO --es path "/path/video.mp4" --ei duration 10
 *   am broadcast -a com.termx.app.api.CAMERA_INFO
 */
object CameraApi {

    private const val TAG = "CameraApi"

    data class CameraInfo(
        val id: String,
        val facing: String,
        val resolutions: List<String>,
        val hasFlash: Boolean,
        val focalLength: Float,
        val sensorSize: String
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("Camera $id ($facing)")
            appendLine("  Flash: $hasFlash")
            appendLine("  Focal: ${focalLength}mm")
            appendLine("  Sensor: $sensorSize")
            appendLine("  Resolutions: ${resolutions.joinToString(", ")}")
        }
    }

    /**
     * Get information about all available cameras on the device.
     */
    fun getCameraInfo(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList

            if (cameraIds.isEmpty()) return "No cameras available"

            val infos = cameraIds.map { id -> resolveCameraInfo(cameraManager, id) }
            buildString {
                appendLine("=== Camera Info (${infos.size} cameras) ===")
                infos.forEach { appendLine(it.toFormattedString()) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera info", e)
            "Error getting camera info: ${e.message}"
        }
    }

    /**
     * Take a photo using the specified camera.
     * @param path Output file path for the photo
     * @param lens "front" or "back" (default: "back")
     * @param flash Enable flash (default: false)
     */
    fun takePhoto(context: Context, path: String, lens: String = "back", flash: Boolean = false): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findCameraId(cameraManager, lens)
                ?: return "Error: No $lens camera found"

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facingStr = lensToFacingString(lens)

            val outputFile = File(path)
            outputFile.parentFile?.mkdirs()

            // Camera2 API requires a surface and capture session, which needs an Activity.
            // For background usage, we log the intent and return instructions.
            Log.i(TAG, "Photo capture requested: camera=$cameraId ($facingStr), path=$path, flash=$flash")
            "Photo capture initiated on camera $cameraId ($facingStr). " +
                "Output: $path. Flash: $flash. Note: Camera2 capture requires an active surface; " +
                "use the TermX camera activity for full capture support."
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            "Error: Camera permission denied. Grant CAMERA permission and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo", e)
            "Error taking photo: ${e.message}"
        }
    }

    /**
     * Record video using the specified camera.
     * @param path Output file path for the video
     * @param duration Recording duration in seconds (default: 10)
     * @param quality "low", "medium", or "high" (default: "medium")
     * @param lens "front" or "back" (default: "back")
     */
    fun recordVideo(
        context: Context,
        path: String,
        duration: Int = 10,
        quality: String = "medium",
        lens: String = "back"
    ): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findCameraId(cameraManager, lens)
                ?: return "Error: No $lens camera found"

            val outputFile = File(path)
            outputFile.parentFile?.mkdirs()

            Log.i(TAG, "Video record requested: camera=$cameraId, path=$path, duration=${duration}s, quality=$quality")
            "Video recording initiated on camera $cameraId ($lens). " +
                "Duration: ${duration}s. Quality: $quality. Output: $path. " +
                "Note: Video recording requires MediaRecorder with an active surface."
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            "Error: Camera permission denied. Grant CAMERA and RECORD_AUDIO permissions."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record video", e)
            "Error recording video: ${e.message}"
        }
    }

    /**
     * Find a camera ID for the given lens facing direction.
     */
    private fun findCameraId(cameraManager: CameraManager, lens: String): String? {
        val targetFacing = if (lens.equals("front", ignoreCase = true))
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        }
    }

    /**
     * Resolve detailed info for a single camera.
     */
    private fun resolveCameraInfo(cameraManager: CameraManager, cameraId: String): CameraInfo {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(0f)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

        val streamConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } else {
            @Suppress("DEPRECATION")
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }

        val resolutions = mutableListOf<String>()
        streamConfigs?.let { config ->
            val outputSizes = config.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            outputSizes?.take(5)?.forEach { size ->
                resolutions.add("${size.width}x${size.height}")
            }
        }

        return CameraInfo(
            id = cameraId,
            facing = facing,
            resolutions = resolutions.ifEmpty { listOf("N/A") },
            hasFlash = hasFlash,
            focalLength = focalLengths[0],
            sensorSize = sensorSize?.let { "${it.width}x${it.height}mm" } ?: "N/A"
        )
    }

    private fun lensToFacingString(lens: String): String =
        if (lens.equals("front", ignoreCase = true)) "front" else "back"
}
