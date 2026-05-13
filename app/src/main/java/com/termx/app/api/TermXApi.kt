package com.termx.app.api

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.termx.app.storage.StorageSetup

/**
 * TermX API commands - utility functions accessible from the terminal.
 * Similar to Termux:API commands (termux-clipboard-get, termux-open-url, etc.)
 *
 * These can be called via shell scripts as:
 *   am broadcast -a com.termx.app.api.<COMMAND>
 */
object TermXApi {

    private const val ACTION_PREFIX = "com.termx.app.api"

    // ---- URL Opening ----

    /**
     * Open a URL in the default browser.
     */
    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No browser found to open URL", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open a file with the appropriate app.
     */
    fun openFile(context: Context, path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                Toast.makeText(context, "File not found: $path", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val mimeType = getMimeType(path)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Share text content via Android share sheet.
     */
    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- Clipboard ----

    /**
     * Get text from clipboard.
     */
    fun getClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    /**
     * Set text to clipboard.
     */
    fun setClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TermX", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ---- Toast ----

    /**
     * Show a toast message.
     */
    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, message, duration).show()
    }

    // ---- Vibration ----

    /**
     * Vibrate the device.
     */
    fun vibrate(context: Context, durationMs: Long = 100) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    // ---- Storage ----

    /**
     * Setup storage access.
     */
    fun setupStorage(context: Context): String {
        if (!StorageSetup.hasStoragePermission(context)) {
            StorageSetup.requestStoragePermission(context)
            return "Storage permission requested. Please grant permission and run again."
        }
        val result = StorageSetup.setupStorage(context)
        return result.message
    }

    // ---- Notification ----

    /**
     * Show a notification.
     */
    fun showNotification(context: Context, title: String, content: String, id: Int = 2000) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

        // Ensure channel exists
        val channelId = "termx_api_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "TermX Notifications",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = android.app.Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }

    // ---- Dialog ----

    /**
     * Show a dialog (requires activity context).
     */
    fun showDialog(context: Context, title: String, message: String) {
        if (context is android.app.Activity) {
            context.runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---- Battery / Device Info ----

    /**
     * Get battery info.
     */
    fun getBatteryInfo(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery info unavailable"

        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        val pct = (level * 100 / scale.toFloat()).toInt()
        val charging = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ==
                android.os.BatteryManager.BATTERY_STATUS_CHARGING
        val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
        val plugType = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        return "Battery: $pct% | Charging: $charging | Plugged: $plugType"
    }

    // ---- Utilities ----

    /**
     * Get MIME type from file extension.
     */
    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "log", "sh", "py", "js", "java", "kt", "c", "cpp", "h", "xml", "json", "yaml", "yml", "toml", "conf", "cfg", "ini" -> "text/plain"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz", "tgz" -> "application/gzip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Get all available API commands as help text.
     */
    fun getHelpText(): String {
        return """
TermX v3.0 Power — Complete API Reference:
===========================================

CORE:
  setup-storage          Setup storage symlinks
  open-url <url>         Open URL in browser
  open-file <path>       Open file with app
  share-text <text>      Share via Android
  clipboard-get/set      Clipboard access
  vibrate [ms]           Vibrate device
  toast <msg>            Show toast
  notify <t> <msg>       System notification
  battery-info           Battery status

LOCATION & SENSORS:
  termx-location         GPS location
  termx-sensor-info      List sensors
  termx-sensor-read      Read sensor

TELEPHONY & MEDIA:
  termx-telephony        Phone info
  termx-media play|pause|stop   Media player
  termx-download <url>   Download file
  termx-volume [stream]  Volume control
  termx-tts <text>       Text-to-speech

WIFI:
  termx-wifi-info        Connection info
  termx-wifi-scan        Scan networks

X11 DISPLAY:
  termx-display start|stop|list|resize|screenshot
  termx-vnc start|stop|status

PRIORITY 3 APIs:
  termx-camera photo|video|info
  termx-sms send|inbox|sent|delete
  termx-fingerprint auth|available|enrolled
  termx-contact list|search|add|delete
  termx-stt start|stop|result
  termx-nfc read|write|available
  termx-battery info|health|power-save
  termx-notification show|cancel|ongoing|progress
  termx-file-picker open|save|dir
  termx-screenshot [path]
  termx-app install|uninstall|list|info|launch

POWER FEATURES — SSH/SFTP:
  termx-ssh start [port]   Start SSH server (default 8022)
  termx-ssh stop|status|restart|keygen|keys|sessions

POWER FEATURES — CRON:
  termx-cron add|remove|list|enable|disable|run|logs|start|stop|status

POWER FEATURES — TUNNEL:
  termx-tunnel forward|reverse|socks|reverse-shell|udp|list|stop|stats

POWER FEATURES — BLUETOOTH:
  termx-bt enable|disable|scan|paired|connect|disconnect|send|info

POWER FEATURES — USB SERIAL:
  termx-usb list|open|close|read|write|config|monitor|info

POWER FEATURES — WEB SERVER:
  termx-http start|stop|status|auth|logs|dir|port

POWER FEATURES — PROCESS MONITOR:
  termx-ps list|top|info|kill|tree|threads|mem|cpu|find

POWER FEATURES — FLASHLIGHT:
  termx-flash on|off|toggle|blink|sos|strobe|status

POWER FEATURES — CALL LOG:
  termx-calllog list|incoming|outgoing|missed|stats|duration|clear

POWER FEATURES — SCREEN RECORDER:
  termx-record start|stop|status|pause|resume

POWER FEATURES — MACRO:
  termx-macro record|stop|play|list|show|delete|export|import|loop

POWER FEATURES — INTENT BRIDGE:
  termx-intent send|start|service|uri|settings|dial|sms|email|market|share|web

POWER FEATURES — ENCRYPTION:
  termx-crypt encrypt|decrypt|hash|aes-enc|aes-dec|base64-encode|base64-decode|gen-key|list-algos

POWER FEATURES — NETWORK TOOLS:
  termx-net ping|traceroute|dns|whois|port-scan|ip|public-ip|interfaces|arp|connections|curl|wget|ssl-info|subnet|speed-test

POWER FEATURES — PROFILES:
  termx-profile list|create|delete|switch|current|show|env|set|clone|rename|export|import|reset

Usage: am broadcast -a com.termx.app.api.<ACTION> [--es key value]
""".trimIndent()
    }
}
