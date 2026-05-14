package com.termx.app.api

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Fingerprint/Biometric API for TermX.
 * Provides biometric authentication, hardware check, and enrollment status.
 *
 * Supports:
 *   - API 23-27: android.hardware.fingerprint.FingerprintManager
 *   - API 28+:  android.hardware.biometrics.BiometricPrompt (via reflection)
 *
 * Usage from shell:
 *   am broadcast -a com.termx.app.api.FINGERPRINT_AUTH
 *   am broadcast -a com.termx.app.api.FINGERPRINT_CHECK
 *   am broadcast -a com.termx.app.api.FINGERPRINT_ENROLLED
 *
 * Requires: USE_BIOMETRIC or USE_FINGERPRINT permission
 */
object FingerprintApi {

    private const val TAG = "FingerprintApi"

    /** Authentication result states */
    enum class AuthResult {
        SUCCESS, FAILED, ERROR_NO_HARDWARE, ERROR_NONE_ENROLLED, ERROR_UNAVAILABLE, ERROR_CANCELED
    }

    data class BiometricStatus(
        val hardwareAvailable: Boolean,
        val fingerprintsEnrolled: Boolean,
        val apiLevel: String
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("=== Biometric Status ===")
            appendLine("Hardware:    $hardwareAvailable")
            appendLine("Enrolled:    $fingerprintsEnrolled")
            appendLine("API Level:   $apiLevel")
        }
    }

    /**
     * Check if biometric hardware is available on this device.
     */
    fun isHardwareAvailable(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+: Use BiometricManager via reflection to avoid direct dependency
                    val bmClass = Class.forName("android.hardware.biometrics.BiometricManager")
                    val bm = context.getSystemService(BiometricManager_className())
                    if (bm != null) {
                        val canAuth = bmClass.getMethod("canAuthenticate").invoke(bm) as Int
                        canAuth != BIOMETRIC_ERROR_NO_HARDWARE()
                    } else {
                        fallbackFingerprintHardwareCheck(context)
                    }
                } else {
                    fallbackFingerprintHardwareCheck(context)
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Biometric hardware check failed", e)
            fallbackFingerprintHardwareCheck(context)
        }
    }

    /**
     * Check if biometric/fingerprint is enrolled on this device.
     */
    fun isEnrolled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val bmClass = Class.forName("android.hardware.biometrics.BiometricManager")
                val bm = context.getSystemService(BiometricManager_className())
                if (bm != null) {
                    val canAuth = bmClass.getMethod("canAuthenticate").invoke(bm) as Int
                    canAuth == BIOMETRIC_SUCCESS()
                } else {
                    fallbackFingerprintEnrolledCheck(context)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                fallbackFingerprintEnrolledCheck(context)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Biometric enrollment check failed", e)
            fallbackFingerprintEnrolledCheck(context)
        }
    }

    /**
     * Get full biometric status.
     */
    fun getStatus(context: Context): String {
        return try {
            val hw = isHardwareAvailable(context)
            val enrolled = isEnrolled(context)
            val apiDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "API 28+ (BiometricPrompt)"
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "API 23-27 (FingerprintManager)"
                else "Unsupported (API < 23)"

            BiometricStatus(
                hardwareAvailable = hw,
                fingerprintsEnrolled = enrolled,
                apiLevel = apiDesc
            ).toFormattedString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get biometric status", e)
            "Error getting biometric status: ${e.message}"
        }
    }

    /**
     * Initiate biometric authentication.
     * Note: Full BiometricPrompt requires an Activity and CryptoObject.
     * This provides status and prepares authentication; actual prompt needs UI.
     */
    fun authenticate(context: Context): String {
        return try {
            if (!isHardwareAvailable(context)) {
                return "Error: No biometric hardware available on this device"
            }
            if (!isEnrolled(context)) {
                return "Error: No fingerprints enrolled. Go to Settings > Security to enroll."
            }

            // BiometricPrompt requires a FragmentActivity and Executor.
            // For terminal API, we report readiness and require an activity for full auth.
            Log.i(TAG, "Biometric authentication requested")
            "Biometric authentication ready. Hardware available, fingerprints enrolled. " +
                "Full authentication prompt requires an Activity context. " +
                "Use TermX biometric activity for interactive authentication."
        } catch (e: SecurityException) {
            Log.e(TAG, "Biometric permission denied", e)
            "Error: USE_BIOMETRIC permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Biometric authentication failed", e)
            "Error during authentication: ${e.message}"
        }
    }

    // ---- Fallback for API 23-27 using FingerprintManager ----

    @Suppress("DEPRECATION")
    private fun fallbackFingerprintHardwareCheck(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fm = context.getSystemService(Context.FINGERPRINT_SERVICE)
                    as? android.hardware.fingerprint.FingerprintManager
                fm?.isHardwareDetected == true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "FingerprintManager hardware check failed", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun fallbackFingerprintEnrolledCheck(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val fm = context.getSystemService(Context.FINGERPRINT_SERVICE)
                    as? android.hardware.fingerprint.FingerprintManager
                fm?.hasEnrolledFingerprints() == true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "FingerprintManager enrollment check failed", e)
            false
        }
    }

    // ---- Constants via reflection for API 28+ BiometricManager ----

    private fun BiometricManager_className(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "biometric"
        else "fingerprint"

    private fun BIOMETRIC_SUCCESS(): Int = 0
    private fun BIOMETRIC_ERROR_NO_HARDWARE(): Int = 12
}
