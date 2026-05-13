package com.termx.app.power

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraManager.AvailabilityCallback
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flashlight/LED control API for TermX.
 *
 * Provides comprehensive flashlight control including on/off, toggle, blink patterns,
 * SOS morse code, and strobe effects. Uses the Camera2 API for reliable flashlight
 * control across all Android 6.0+ devices.
 *
 * Shell usage:
 *   termx-flash on                    Turn flashlight on
 *   termx-flash off                   Turn flashlight off
 *   termx-flash toggle               Toggle flashlight
 *   termx-flash blink [on_ms] [off_ms] [count]  Blink flashlight
 *   termx-flash status               Check flashlight status
 *   termx-flash sos                  SOS morse code pattern
 *   termx-flash strobe [freq_hz]     Strobe effect
 *
 * Requires: CAMERA permission for flashlight control on some devices.
 */
@SuppressLint("MissingPermission")
object FlashlightApi {

    private const val TAG = "FlashlightApi"

    // Default timing constants
    private const val DEFAULT_BLINK_ON_MS = 200L
    private const val DEFAULT_BLINK_OFF_MS = 200L
    private const val DEFAULT_BLINK_COUNT = 3
    private const val DEFAULT_STROBE_FREQ_HZ = 10.0
    private const val STROBE_DURATION_MS = 5000L

    // SOS pattern: ... --- ... (morse code)
    // Dot = 1 unit, Dash = 3 units, Gap between elements = 1 unit,
    // Gap between letters = 3 units, Gap between words = 7 units
    private val SOS_PATTERN = longArrayOf(
        // S: ...
        150, 100, 150, 100, 150,
        // Gap between letters
        300,
        // O: ---
        450, 100, 450, 100, 450,
        // Gap between letters
        300,
        // S: ...
        150, 100, 150, 100, 150
    )

    private const val SOS_REPEAT = 3

    // State tracking
    private val isOn = AtomicBoolean(false)
    private val isBlinking = AtomicBoolean(false)
    private var cameraManager: CameraManager? = null
    private var flashlightCameraId: String? = null
    private var blinkHandler: Handler? = null
    private var strobeRunnable: Runnable? = null

    // Availability callback for camera monitoring
    private val availabilityCallback = object : AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            Log.d(TAG, "Camera $cameraId unavailable")
        }

        override fun onCameraAvailable(cameraId: String) {
            Log.d(TAG, "Camera $cameraId available")
        }
    }

    /**
     * Data class representing flashlight device info.
     */
    data class FlashlightInfo(
        val hasFlashlight: Boolean,
        val cameraId: String?,
        val isOn: Boolean,
        val isBlinking: Boolean
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("=== Flashlight Status ===")
            appendLine("Available:  $hasFlashlight")
            appendLine("Camera ID:  ${cameraId ?: "N/A"}")
            appendLine("State:      ${if (isOn) "ON" else "OFF"}")
            appendLine("Blinking:   $isBlinking")
        }
    }

    /**
     * Initialize the flashlight API. Must be called before any other methods.
     * Discovers the rear camera with flashlight capability.
     */
    fun initialize(context: Context): String {
        return try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager?.registerAvailabilityCallback(availabilityCallback, null)
            flashlightCameraId = findFlashlightCameraId()

            if (flashlightCameraId != null) {
                Log.i(TAG, "Flashlight initialized on camera: $flashlightCameraId")
                "Flashlight initialized successfully (camera: $flashlightCameraId)"
            } else {
                Log.w(TAG, "No flashlight-capable camera found")
                "No flashlight found on this device"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize flashlight", e)
            "Error initializing flashlight: ${e.message}"
        }
    }

    /**
     * Turn the flashlight on.
     */
    fun turnOn(context: Context): String {
        return try {
            ensureInitialized(context)
            val cameraId = flashlightCameraId
                ?: return "Error: No flashlight available on this device"

            if (isOn.get()) {
                return "Flashlight is already ON"
            }

            stopBlinking()
            cameraManager?.setTorchMode(cameraId, true)
            isOn.set(true)
            Log.i(TAG, "Flashlight turned ON")
            "Flashlight ON"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn flashlight on", e)
            "Error turning flashlight on: ${e.message}"
        }
    }

    /**
     * Turn the flashlight off.
     */
    fun turnOff(context: Context): String {
        return try {
            ensureInitialized(context)
            val cameraId = flashlightCameraId
                ?: return "Error: No flashlight available on this device"

            stopBlinking()

            if (!isOn.get()) {
                return "Flashlight is already OFF"
            }

            cameraManager?.setTorchMode(cameraId, false)
            isOn.set(false)
            Log.i(TAG, "Flashlight turned OFF")
            "Flashlight OFF"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn flashlight off", e)
            "Error turning flashlight off: ${e.message}"
        }
    }

    /**
     * Toggle the flashlight state.
     */
    fun toggle(context: Context): String {
        return if (isOn.get()) {
            turnOff(context)
        } else {
            turnOn(context)
        }
    }

    /**
     * Get current flashlight status.
     */
    fun getStatus(context: Context): String {
        return try {
            ensureInitialized(context)
            val info = FlashlightInfo(
                hasFlashlight = flashlightCameraId != null,
                cameraId = flashlightCameraId,
                isOn = isOn.get(),
                isBlinking = isBlinking.get()
            )
            info.toFormattedString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get flashlight status", e)
            "Error getting status: ${e.message}"
        }
    }

    /**
     * Blink the flashlight with configurable timing and count.
     *
     * @param onMs Duration in milliseconds the flashlight stays on per blink
     * @param offMs Duration in milliseconds the flashlight stays off per blink
     * @param count Number of blinks (0 = infinite until stopped)
     */
    fun blink(context: Context, onMs: Long = DEFAULT_BLINK_ON_MS,
              offMs: Long = DEFAULT_BLINK_OFF_MS, count: Int = DEFAULT_BLINK_COUNT): String {
        return try {
            ensureInitialized(context)
            val cameraId = flashlightCameraId
                ?: return "Error: No flashlight available on this device"

            stopBlinking()

            val effectiveCount = if (count <= 0) Int.MAX_VALUE else count
            isBlinking.set(true)
            blinkHandler = Handler(Looper.getMainLooper())

            var currentBlink = 0
            val blinkRunnable = object : Runnable {
                override fun run() {
                    if (!isBlinking.get() || currentBlink >= effectiveCount) {
                        // Final state: ensure flashlight is off
                        try {
                            cameraManager?.setTorchMode(cameraId, false)
                            isOn.set(false)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set final torch state", e)
                        }
                        isBlinking.set(false)
                        Log.d(TAG, "Blinking complete after $currentBlink blinks")
                        return
                    }

                    try {
                        // Turn on
                        cameraManager?.setTorchMode(cameraId, true)
                        isOn.set(true)
                        blinkHandler?.postDelayed({
                            try {
                                // Turn off
                                cameraManager?.setTorchMode(cameraId, false)
                                isOn.set(false)
                                currentBlink++
                                blinkHandler?.postDelayed(this, offMs)
                            } catch (e: Exception) {
                                Log.e(TAG, "Blink off failed", e)
                                isBlinking.set(false)
                            }
                        }, onMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Blink on failed", e)
                        isBlinking.set(false)
                    }
                }
            }

            blinkRunnable.run()
            val countStr = if (count <= 0) "infinite" else count.toString()
            Log.i(TAG, "Blinking started: on=${onMs}ms off=${offMs}ms count=$countStr")
            "Blinking: on=${onMs}ms off=${offMs}ms count=$countStr"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start blinking", e)
            isBlinking.set(false)
            "Error starting blink: ${e.message}"
        }
    }

    /**
     * Play SOS morse code pattern using the flashlight.
     * Pattern: ... --- ... repeated 3 times with pauses.
     */
    fun sos(context: Context): String {
        return try {
            ensureInitialized(context)
            val cameraId = flashlightCameraId
                ?: return "Error: No flashlight available on this device"

            stopBlinking()
            isBlinking.set(true)
            blinkHandler = Handler(Looper.getMainLooper())

            // Build the full SOS sequence as timed on/off pairs
            val onOffPairs = mutableListOf<Pair<Long, Boolean>>()
            for (i in SOS_PATTERN.indices) {
                val duration = SOS_PATTERN[i]
                val isOnPhase = i % 2 == 0
                onOffPairs.add(Pair(duration, isOnPhase))
            }

            // Add inter-word pause between SOS repetitions
            val fullSequence = mutableListOf<Pair<Long, Boolean>>()
            for (rep in 0 until SOS_REPEAT) {
                fullSequence.addAll(onOffPairs)
                if (rep < SOS_REPEAT - 1) {
                    fullSequence.add(Pair(700L, false)) // 7-unit gap between words
                }
            }

            var index = 0
            val sosRunnable = object : Runnable {
                override fun run() {
                    if (!isBlinking.get() || index >= fullSequence.size) {
                        try {
                            cameraManager?.setTorchMode(cameraId, false)
                            isOn.set(false)
                        } catch (e: Exception) {
                            Log.e(TAG, "SOS final off failed", e)
                        }
                        isBlinking.set(false)
                        Log.d(TAG, "SOS pattern complete")
                        return
                    }

                    val (duration, shouldTurnOn) = fullSequence[index]
                    try {
                        cameraManager?.setTorchMode(cameraId, shouldTurnOn)
                        isOn.set(shouldTurnOn)
                        index++
                        blinkHandler?.postDelayed(this, duration)
                    } catch (e: Exception) {
                        Log.e(TAG, "SOS pattern step failed", e)
                        isBlinking.set(false)
                    }
                }
            }

            sosRunnable.run()
            Log.i(TAG, "SOS pattern started")
            "SOS pattern playing ($SOS_REPEAT repetitions)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SOS", e)
            isBlinking.set(false)
            "Error starting SOS: ${e.message}"
        }
    }

    /**
     * Strobe effect at a given frequency.
     *
     * @param freqHz Frequency in Hertz (cycles per second). Default 10Hz.
     * @param durationMs Total duration of the strobe effect in milliseconds. Default 5000ms.
     */
    fun strobe(context: Context, freqHz: Double = DEFAULT_STROBE_FREQ_HZ,
               durationMs: Long = STROBE_DURATION_MS): String {
        return try {
            ensureInitialized(context)
            val cameraId = flashlightCameraId
                ?: return "Error: No flashlight available on this device"

            if (freqHz <= 0 || freqHz > 50) {
                return "Error: Frequency must be between 0.1 and 50 Hz"
            }

            stopBlinking()
            isBlinking.set(true)
            blinkHandler = Handler(Looper.getMainLooper())

            val halfPeriodMs = (1000.0 / freqHz / 2.0).toLong()
            val startTime = System.currentTimeMillis()

            strobeRunnable = object : Runnable {
                override fun run() {
                    if (!isBlinking.get()) return

                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= durationMs) {
                        try {
                            cameraManager?.setTorchMode(cameraId, false)
                            isOn.set(false)
                        } catch (e: Exception) {
                            Log.e(TAG, "Strobe final off failed", e)
                        }
                        isBlinking.set(false)
                        Log.d(TAG, "Strobe effect complete")
                        return
                    }

                    try {
                        val newState = !isOn.get()
                        cameraManager?.setTorchMode(cameraId, newState)
                        isOn.set(newState)
                        blinkHandler?.postDelayed(this, halfPeriodMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Strobe step failed", e)
                        isBlinking.set(false)
                    }
                }
            }

            blinkHandler?.post(strobeRunnable!!)
            Log.i(TAG, "Strobe started: ${freqHz}Hz for ${durationMs}ms")
            "Strobe: ${freqHz}Hz for ${durationMs}ms (half-period: ${halfPeriodMs}ms)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start strobe", e)
            isBlinking.set(false)
            "Error starting strobe: ${e.message}"
        }
    }

    /**
     * Stop any active blinking or strobe effect.
     */
    fun stopBlinking(): String {
        val wasActive = isBlinking.get() || strobeRunnable != null
        isBlinking.set(false)
        strobeRunnable = null
        blinkHandler?.removeCallbacksAndMessages(null)
        blinkHandler = null

        // Ensure flashlight is off after stopping
        try {
            flashlightCameraId?.let { camId ->
                cameraManager?.setTorchMode(camId, false)
            }
            isOn.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off flashlight after stopping blink", e)
        }

        return if (wasActive) "Stopped blinking/strobe" else "No active blink/strobe to stop"
    }

    /**
     * Release all resources. Call when the API is no longer needed.
     */
    fun release(context: Context): String {
        stopBlinking()
        try {
            cameraManager?.unregisterAvailabilityCallback(availabilityCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister availability callback", e)
        }
        cameraManager = null
        flashlightCameraId = null
        Log.i(TAG, "FlashlightApi released")
        return "FlashlightApi resources released"
    }

    /**
     * Check if the device has a flashlight.
     */
    fun hasFlashlight(context: Context): Boolean {
        ensureInitialized(context)
        return flashlightCameraId != null
    }

    /**
     * Check if the flashlight is currently on.
     */
    fun isFlashlightOn(): Boolean = isOn.get()

    /**
     * Check if the flashlight is currently blinking.
     */
    fun isCurrentlyBlinking(): Boolean = isBlinking.get()

    // ---- Internal helpers ----

    /**
     * Find the camera ID that has a flashlight (torch) capability.
     * Prefers the rear-facing camera.
     */
    private fun findFlashlightCameraId(): String? {
        val manager = cameraManager ?: return null

        // First try to find a rear-facing camera with flashlight
        for (cameraId in manager.cameraIdList) {
            try {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                    return cameraId
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking camera $cameraId", e)
            }
        }

        // Fallback: any camera with flashlight
        for (cameraId in manager.cameraIdList) {
            try {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (hasFlash) {
                    Log.w(TAG, "No rear flashlight found, using camera $cameraId")
                    return cameraId
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking camera $cameraId", e)
            }
        }

        return null
    }

    /**
     * Ensure the API is initialized. Auto-initializes if needed.
     */
    private fun ensureInitialized(context: Context) {
        if (cameraManager == null) {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager?.registerAvailabilityCallback(availabilityCallback, null)
        }
        if (flashlightCameraId == null) {
            flashlightCameraId = findFlashlightCameraId()
        }
    }
}
