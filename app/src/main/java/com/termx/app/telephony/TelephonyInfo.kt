package com.termx.app.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Telephony information provider for TermX API.
 * Similar to termux-telephony-deviceinfo and termux-telephony-cellinfo.
 */
object TelephonyInfo {

    private const val TAG = "TelephonyInfo"

    data class DeviceInfo(
        val deviceId: String,
        val deviceIdType: String,
        val subscriberId: String,
        val simSerialNumber: String,
        val simOperator: String,
        val simOperatorName: String,
        val simCountry: String,
        val networkOperator: String,
        val networkOperatorName: String,
        val networkCountry: String,
        val phoneType: String,
        val networkType: String,
        val simState: String,
        val isRoaming: Boolean,
        val lineNumber: String
    ) {
        fun toFormattedString(): String {
            return buildString {
                appendLine("=== Telephony Device Info ===")
                appendLine("Device ID:       $deviceId")
                appendLine("Device ID Type:  $deviceIdType")
                appendLine("Subscriber ID:   $subscriberId")
                appendLine("SIM Serial:      $simSerialNumber")
                appendLine("SIM Operator:    $simOperator ($simOperatorName)")
                appendLine("SIM Country:     $simCountry")
                appendLine("Network:         $networkOperator ($networkOperatorName)")
                appendLine("Network Country: $networkCountry")
                appendLine("Phone Type:      $phoneType")
                appendLine("Network Type:    $networkType")
                appendLine("SIM State:       $simState")
                appendLine("Roaming:         $isRoaming")
                appendLine("Line Number:     $lineNumber")
            }
        }
    }

    /**
     * Get telephony device information.
     * Requires READ_PHONE_STATE permission for some fields.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun getDeviceInfo(context: Context): DeviceInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val phoneTypeStr = when (tm.phoneType) {
            TelephonyManager.PHONE_TYPE_GSM -> "GSM"
            TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
            TelephonyManager.PHONE_TYPE_SIP -> "SIP"
            else -> "NONE"
        }

        val networkTypeStr = when (tm.networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            else -> "Unknown (${tm.networkType})"
        }

        val simStateStr = when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_READY -> "Ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN Required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK Required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network Locked"
            else -> "Unknown"
        }

        return DeviceInfo(
            deviceId = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.imei else tm.deviceId } catch (_: Exception) { "N/A" },
            deviceIdType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "IMEI" else "Device ID",
            subscriberId = try { tm.subscriberId ?: "N/A" } catch (_: Exception) { "N/A" },
            simSerialNumber = try { tm.simSerialNumber ?: "N/A" } catch (_: Exception) { "N/A" },
            simOperator = tm.simOperator ?: "N/A",
            simOperatorName = tm.simOperatorName ?: "N/A",
            simCountry = tm.simCountryIso?.uppercase() ?: "N/A",
            networkOperator = tm.networkOperator ?: "N/A",
            networkOperatorName = tm.networkOperatorName ?: "N/A",
            networkCountry = tm.networkCountryIso?.uppercase() ?: "N/A",
            phoneType = phoneTypeStr,
            networkType = networkTypeStr,
            simState = simStateStr,
            isRoaming = tm.isNetworkRoaming,
            lineNumber = try { tm.line1Number ?: "N/A" } catch (_: Exception) { "N/A" }
        )
    }
}
