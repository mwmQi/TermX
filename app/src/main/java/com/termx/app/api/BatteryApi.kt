package com.termx.app.api

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Detailed Battery API for TermX.
 * Provides comprehensive battery information including health, voltage,
 * temperature, technology, power save mode, and capacity estimate.
 *
 * Usage from shell:
 *   am broadcast -a com.termx.app.api.BATTERY_DETAIL
 *   am broadcast -a com.termx.app.api.BATTERY_POWER_SAVE
 *   am broadcast -a com.termx.app.api.BATTERY_CAPACITY
 *
 * No special permissions required for most fields.
 */
object BatteryApi {

    private const val TAG = "BatteryApi"

    data class BatteryDetail(
        val level: Int,
        val scale: Int,
        val percentage: Int,
        val status: String,
        val charging: Boolean,
        val pluggedType: String,
        val health: String,
        val voltage: Int,
        val voltageVolts: Float,
        val temperature: Int,
        val temperatureCelsius: Float,
        val technology: String,
        val present: Boolean
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("=== Battery Detail ===")
            appendLine("Level:       $percentage% ($level/$scale)")
            appendLine("Status:      $status")
            appendLine("Charging:    $charging")
            appendLine("Plugged:     $pluggedType")
            appendLine("Health:      $health")
            appendLine("Voltage:     ${"%.3f".format(voltageVolts)}V ($voltage mV)")
            appendLine("Temperature: ${"%.1f".format(temperatureCelsius)}°C ($temperature)")
            appendLine("Technology:  $technology")
            appendLine("Present:     $present")
        }
    }

    /**
     * Get comprehensive battery information.
     */
    fun getBatteryDetail(context: Context): String {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return "Battery info unavailable (sticky broadcast returned null)"

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0
            val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pluggedInt = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val healthInt = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
            val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)

            val detail = BatteryDetail(
                level = level,
                scale = scale,
                percentage = percentage,
                status = statusToString(statusInt),
                charging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
                        statusInt == BatteryManager.BATTERY_STATUS_FULL,
                pluggedType = pluggedToString(pluggedInt),
                health = healthToString(healthInt),
                voltage = voltage,
                voltageVolts = voltage / 1000f,
                temperature = temperature,
                temperatureCelsius = temperature / 10f,
                technology = technology,
                present = present
            )

            Log.d(TAG, "Battery detail: ${detail.percentage}% ${detail.status}")
            detail.toFormattedString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery detail", e)
            "Error getting battery info: ${e.message}"
        }
    }

    /**
     * Check if power save mode is active.
     */
    fun isPowerSaveMode(context: Context): String {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isPowerSave = powerManager.isPowerSaveMode
            buildString {
                appendLine("=== Power Save Mode ===")
                appendLine("Active: $isPowerSave")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check power save mode", e)
            "Error checking power save: ${e.message}"
        }
    }

    /**
     * Get battery capacity estimate in mAh.
     * Uses BatteryManager property on API 21+ or falls back to charging counter.
     */
    fun getBatteryCapacity(context: Context): String {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            val capacity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            } else -1L

            val currentNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            } else -1L

            val currentAverage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            } else -1L

            buildString {
                appendLine("=== Battery Capacity ===")
                if (capacity > 0) {
                    appendLine("Charge Counter: ${capacity}mAh")
                } else {
                    appendLine("Charge Counter: N/A")
                }
                if (currentNow != Long.MIN_VALUE && currentNow != -1L) {
                    appendLine("Current Now:    ${currentNow / 1000}mA")
                } else {
                    appendLine("Current Now:    N/A")
                }
                if (currentAverage != Long.MIN_VALUE && currentAverage != -1L) {
                    appendLine("Current Avg:    ${currentAverage / 1000}mA")
                } else {
                    appendLine("Current Avg:    N/A")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get battery capacity", e)
            "Error getting battery capacity: ${e.message}"
        }
    }

    /**
     * Get a quick battery percentage string.
     */
    fun getQuickLevel(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "$level%"
    }

    // ---- Helper methods for converting battery constants to strings ----

    private fun statusToString(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
        else -> "Unknown ($status)"
    }

    private fun pluggedToString(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        0 -> "None (Battery)"
        else -> "Unknown ($plugged)"
    }

    private fun healthToString(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Unknown"
        else -> "Unknown ($health)"
    }
}
