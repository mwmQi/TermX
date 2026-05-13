package com.termx.app.power

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BroadcastReceiver for USB device attach/detach events.
 *
 * Handles:
 * - USB_DEVICE_ATTACHED: Detects when a USB serial device is connected,
 *   auto-opens it with default baud rate, and logs the event.
 * - USB_DEVICE_DETACHED: Detects when a USB device is disconnected,
 *   auto-closes any open serial connection, and logs the event.
 *
 * The receiver also writes event logs to the cache directory for
 * shell scripts to read, and can trigger notification alerts for
 * USB device changes.
 *
 * Registered in AndroidManifest.xml for:
 *   - android.hardware.usb.action.USB_DEVICE_ATTACHED
 *   - android.hardware.usb.action.USB_DEVICE_DETACHED
 */
class UsbAttachReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbAttachReceiver"

        // Cache file paths for shell script access
        private const val CACHE_USB_EVENTS = "usb_events.txt"
        private const val CACHE_USB_ATTACHED = "usb_attached.txt"
        private const val CACHE_USB_DETACHED = "usb_detached.txt"

        // Maximum number of events to keep in the log
        private const val MAX_EVENT_LOG_SIZE = 100

        // Action constants for intent handling
        const val ACTION_USB_PERMISSION = "com.termx.app.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    handleDeviceAttached(context, device)
                } else {
                    Log.w(TAG, "USB_DEVICE_ATTACHED received but device is null")
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    handleDeviceDetached(context, device)
                } else {
                    Log.w(TAG, "USB_DEVICE_DETACHED received but device is null")
                }
            }
            ACTION_USB_PERMISSION -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device != null && granted) {
                    Log.i(TAG, "USB permission granted for device: ${device.deviceName}")
                    // Auto-open the device now that we have permission
                    autoOpenDevice(context, device)
                } else {
                    Log.w(TAG, "USB permission denied for device: ${device?.deviceName}")
                }
            }
            else -> {
                Log.w(TAG, "Unknown USB action: ${intent.action}")
            }
        }
    }

    /**
     * Handle a USB device being attached.
     *
     * Detects serial devices, logs the event, and optionally auto-opens
     * the connection.
     */
    private fun handleDeviceAttached(context: Context, device: UsbDevice) {
        val deviceName = device.deviceName
        val vendorId = device.vendorId
        val productId = device.productId
        val manufacturer = device.manufacturerName ?: "Unknown"
        val product = device.productName ?: "Unknown"

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        Log.i(TAG, "USB device attached: $deviceName " +
                "[VID=0x${"%04X".format(vendorId)} PID=0x${"%04X".format(productId)}] " +
                "$manufacturer $product")

        // Build event info
        val eventInfo = buildString {
            appendLine("=== USB Device Attached ===")
            appendLine("Timestamp:     $timestamp")
            appendLine("Device:        $deviceName")
            appendLine("VID:PID:       0x${"%04X".format(vendorId)}:0x${"%04X".format(productId)}")
            appendLine("Manufacturer:  $manufacturer")
            appendLine("Product:       $product")
            appendLine("Serial:        ${device.serialNumber ?: "N/A"}")
            appendLine("Interfaces:    ${device.interfaceCount}")
            appendLine("Class:         ${usbClassToString(device.deviceClass)}")
        }

        // Write to cache for shell scripts
        writeResult(context, CACHE_USB_ATTACHED, eventInfo)

        // Append to event log
        appendEventLog(context, "[ATTACHED] $timestamp $deviceName $manufacturer $product " +
                "VID=0x${"%04X".format(vendorId)} PID=0x${"%04X".format(productId)}")

        // Check if this is a serial device and attempt auto-open
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (isSerialDevice(device)) {
            Log.i(TAG, "Serial device detected: $deviceName")

            // Check if we have permission
            if (usbManager.hasPermission(device)) {
                autoOpenDevice(context, device)
            } else {
                // Request permission — the result will come back via ACTION_USB_PERMISSION
                Log.i(TAG, "Requesting USB permission for $deviceName")
                requestUsbPermission(context, device)
            }
        }
    }

    /**
     * Handle a USB device being detached.
     *
     * Closes any open serial connection and logs the event.
     */
    private fun handleDeviceDetached(context: Context, device: UsbDevice) {
        val deviceName = device.deviceName
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        Log.i(TAG, "USB device detached: $deviceName")

        // Build event info
        val eventInfo = buildString {
            appendLine("=== USB Device Detached ===")
            appendLine("Timestamp:     $timestamp")
            appendLine("Device:        $deviceName")
            appendLine("VID:PID:       0x${"%04X".format(device.vendorId)}:0x${"%04X".format(device.productId)}")
        }

        // Write to cache
        writeResult(context, CACHE_USB_DETACHED, eventInfo)

        // Append to event log
        appendEventLog(context, "[DETACHED] $timestamp $deviceName " +
                "VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)}")

        // Auto-close the serial connection if open
        try {
            val closeResult = UsbSerialApi.close(deviceName)
            Log.i(TAG, "Auto-close result for $deviceName: $closeResult")
        } catch (e: Exception) {
            Log.w(TAG, "Error auto-closing $deviceName on detach", e)
        }
    }

    /**
     * Auto-open a USB serial device with default baud rate.
     */
    private fun autoOpenDevice(context: Context, device: UsbDevice) {
        try {
            val result = UsbSerialApi.open(context, device.deviceName, 9600)
            Log.i(TAG, "Auto-open result: $result")

            // Update the attached info file with open status
            val existingInfo = File(context.cacheDir, CACHE_USB_ATTACHED)
                .readText()
            writeResult(context, CACHE_USB_ATTACHED,
                existingInfo + "Auto-Opened:   Yes (9600 baud)\n")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-open device ${device.deviceName}", e)
        }
    }

    /**
     * Request USB permission from the user.
     */
    private fun requestUsbPermission(context: Context, device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val permissionIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission", e)
        }
    }

    /**
     * Check if a USB device appears to be a serial device.
     * Uses the same detection logic as UsbSerialApi.
     */
    private fun isSerialDevice(device: UsbDevice): Boolean {
        // Known serial device VID/PID pairs
        val knownDevices = mapOf(
            Pair(0x0403, 0x6001) to "FTDI FT232RL",
            Pair(0x0403, 0x6014) to "FTDI FT232H",
            Pair(0x0403, 0x6015) to "FTDI FT231X",
            Pair(0x1A86, 0x7523) to "CH340",
            Pair(0x1A86, 0x5523) to "CH341",
            Pair(0x10C4, 0xEA60) to "CP2102",
            Pair(0x067B, 0x2303) to "PL2303",
            Pair(0x2341, 0x0043) to "Arduino Uno",
            Pair(0x2341, 0x0010) to "Arduino Mega"
        )

        if (knownDevices.containsKey(Pair(device.vendorId, device.productId))) {
            return true
        }

        // Check for CDC/ACM interface class
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 0x02 || iface.interfaceClass == 0x0A) {
                return true
            }
        }

        return false
    }

    /**
     * Convert USB class code to human-readable string.
     */
    private fun usbClassToString(cls: Int): String = when (cls) {
        0x00 -> "Defined at Interface"
        0x02 -> "CDC"
        0x0A -> "CDC-Data"
        0xFF -> "Vendor Specific"
        0x08 -> "Mass Storage"
        0x0E -> "Video"
        0x01 -> "Audio"
        0x03 -> "HID"
        0x06 -> "Still Image"
        0x07 -> "Printer"
        0x0B -> "Smart Card"
        else -> "0x${"%02X".format(cls)}"
    }

    /**
     * Write a result string to a cache file for shell scripts to read.
     */
    private fun writeResult(context: Context, filename: String, content: String) {
        try {
            File(context.cacheDir, filename).writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result to $filename", e)
        }
    }

    /**
     * Append an event to the USB event log file.
     * Trims the log if it exceeds MAX_EVENT_LOG_SIZE lines.
     */
    private fun appendEventLog(context: Context, eventLine: String) {
        try {
            val logFile = File(context.cacheDir, CACHE_USB_EVENTS)
            val existing = if (logFile.exists()) logFile.readText() else ""
            val lines = (existing.trimEnd() + "\n" + eventLine).lines()
                .filter { it.isNotBlank() }

            // Trim to max size (keep most recent)
            val trimmed = if (lines.size > MAX_EVENT_LOG_SIZE) {
                lines.takeLast(MAX_EVENT_LOG_SIZE)
            } else {
                lines
            }

            logFile.writeText(trimmed.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append event log", e)
        }
    }
}
