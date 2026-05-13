package com.termx.app.power

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Intent Bridge API for TermX — send/receive Android Intents from terminal.
 *
 * Provides a comprehensive bridge between the terminal and Android's Intent system,
 * allowing users to send broadcasts, start activities and services, open URIs,
 * access system settings, and use common intent shortcuts (dial, SMS, email, web, etc.)
 *
 * Shell usage:
 *   termx-intent send <action> [extras]  Send a broadcast intent
 *   termx-intent start <action>          Start activity by intent
 *   termx-intent service <action>        Start service by intent
 *   termx-intent uri <uri>               Open URI with intent
 *   termx-intent settings <panel>        Open settings panel
 *   termx-intent list                    List recent intents
 *   termx-intent watch <action>          Watch for intent broadcasts
 *   termx-intent dial <number>           Dial a phone number
 *   termx-intent sms <number> <text>     Open SMS app
 *   termx-intent email <to> [subject]    Open email intent
 *   termx-intent market <package>        Open Play Store
 *   termx-intent share <text>            Share text
 *   termx-intent web <url>               Open URL
 *
 * No special permissions required for most operations.
 * Some operations (dial, SMS) require the CALL_PHONE or SEND_SMS permissions.
 */
object IntentBridgeApi {

    private const val TAG = "IntentBridgeApi"
    private const val MAX_INTENT_HISTORY = 100

    // Intent history for the list command
    private val intentHistory = Collections.synchronizedList(mutableListOf<IntentRecord>())

    // Active broadcast watchers
    private val activeWatchers = Collections.synchronizedMap(mutableMapOf<String, BroadcastReceiver>())

    /**
     * Represents a record of a sent/received intent for history tracking.
     */
    data class IntentRecord(
        val type: String,           // "sent", "started", "received"
        val action: String?,
        val dataUri: String?,
        val extras: Map<String, String>,
        val component: String?,
        val timestamp: Long
    ) {
        fun toFormattedString(): String = buildString {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            append("[$time] [$type] ")
            append(action ?: "no-action")
            if (!dataUri.isNullOrBlank()) append(" data=$dataUri")
            if (!component.isNullOrBlank()) append(" cmp=$component")
            if (extras.isNotEmpty()) {
                append(" extras={${extras.entries.joinToString(", ") { "${it.key}=${it.value}" }}}")
            }
        }
    }

    // ---- Broadcast Intent Methods ----

    /**
     * Send a broadcast intent with the specified action and optional extras.
     *
     * @param context Application context
     * @param action The intent action string (e.g., "android.intent.action.BOOT_COMPLETED")
     * @param extras Optional key-value pairs to include as extras
     * @param category Optional category to add to the intent
     * @param dataUri Optional data URI to set on the intent
     */
    fun sendBroadcast(context: Context, action: String,
                      extras: Map<String, Any> = emptyMap(),
                      category: String? = null,
                      dataUri: String? = null): String {
        return try {
            if (action.isBlank()) {
                return "Error: Intent action required"
            }

            val intent = Intent(action)

            // Set data URI if provided
            if (!dataUri.isNullOrBlank()) {
                intent.data = Uri.parse(dataUri)
            }

            // Add category if provided
            if (!category.isNullOrBlank()) {
                intent.addCategory(category)
            }

            // Add extras
            addExtrasToIntent(intent, extras)

            context.sendBroadcast(intent)

            // Record in history
            recordIntent("sent", intent)

            Log.i(TAG, "Broadcast sent: $action")
            buildString {
                appendLine("Broadcast sent: $action")
                if (!dataUri.isNullOrBlank()) appendLine("  Data: $dataUri")
                if (!category.isNullOrBlank()) appendLine("  Category: $category")
                if (extras.isNotEmpty()) {
                    appendLine("  Extras:")
                    extras.forEach { (k, v) -> appendLine("    $k = $v") }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for broadcast: $action", e)
            "Error: Permission denied for broadcast '$action'"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast: $action", e)
            "Error sending broadcast: ${e.message}"
        }
    }

    /**
     * Send an ordered broadcast intent.
     *
     * @param context Application context
     * @param action The intent action string
     * @param extras Optional extras
     * @param permission Optional receiver permission
     */
    fun sendOrderedBroadcast(context: Context, action: String,
                             extras: Map<String, Any> = emptyMap(),
                             permission: String? = null): String {
        return try {
            if (action.isBlank()) {
                return "Error: Intent action required"
            }

            val intent = Intent(action)
            addExtrasToIntent(intent, extras)

            if (permission != null) {
                context.sendOrderedBroadcast(intent, permission)
            } else {
                context.sendOrderedBroadcast(intent, null)
            }

            recordIntent("sent", intent)
            Log.i(TAG, "Ordered broadcast sent: $action")
            "Ordered broadcast sent: $action"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ordered broadcast: $action", e)
            "Error sending ordered broadcast: ${e.message}"
        }
    }

    // ---- Activity Methods ----

    /**
     * Start an activity by intent action.
     *
     * @param context Application context
     * @param action The intent action (e.g., "android.intent.action.VIEW")
     * @param dataUri Optional data URI
     * @param extras Optional extras
     * @param flags Optional intent flags
     */
    fun startActivity(context: Context, action: String,
                      dataUri: String? = null,
                      extras: Map<String, Any> = emptyMap(),
                      flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK): String {
        return try {
            if (action.isBlank()) {
                return "Error: Intent action required"
            }

            val intent = Intent(action)
            intent.flags = flags

            if (!dataUri.isNullOrBlank()) {
                intent.data = Uri.parse(dataUri)
            }

            addExtrasToIntent(intent, extras)

            // Verify the activity can be resolved
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                return "Error: No activity found to handle action '$action'"
            }

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Activity started: $action")
            "Activity started: $action"
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity found for action: $action", e)
            "Error: No activity found for action '$action'"
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for activity: $action", e)
            "Error: Permission denied for activity '$action'"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity: $action", e)
            "Error starting activity: ${e.message}"
        }
    }

    /**
     * Start an activity by explicitly specifying the component (package/class).
     *
     * @param context Application context
     * @param packageName Target package name
     * @param className Target class name
     * @param extras Optional extras
     */
    fun startActivityByComponent(context: Context, packageName: String, className: String,
                                 extras: Map<String, Any> = emptyMap()): String {
        return try {
            val intent = Intent()
            intent.setClassName(packageName, className)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addExtrasToIntent(intent, extras)

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Activity started: $packageName/$className")
            "Activity started: $packageName/$className"
        } catch (e: ActivityNotFoundException) {
            "Error: Activity not found: $packageName/$className"
        } catch (e: SecurityException) {
            "Error: Permission denied: $packageName/$className"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity by component", e)
            "Error starting activity: ${e.message}"
        }
    }

    // ---- Service Methods ----

    /**
     * Start a service by intent action.
     *
     * @param context Application context
     * @param action The intent action
     * @param extras Optional extras
     */
    fun startService(context: Context, action: String,
                     extras: Map<String, Any> = emptyMap()): String {
        return try {
            if (action.isBlank()) {
                return "Error: Intent action required"
            }

            val intent = Intent(action)
            addExtrasToIntent(intent, extras)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            recordIntent("started", intent)

            Log.i(TAG, "Service started: $action")
            "Service started: $action"
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for service: $action", e)
            "Error: Permission denied for service '$action'"
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Service start failed (not allowed in background): $action", e)
            "Error: Service not allowed in background. Use foreground service."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: $action", e)
            "Error starting service: ${e.message}"
        }
    }

    /**
     * Start a service by component name.
     */
    fun startServiceByComponent(context: Context, packageName: String, className: String,
                                extras: Map<String, Any> = emptyMap()): String {
        return try {
            val intent = Intent()
            intent.setClassName(packageName, className)
            addExtrasToIntent(intent, extras)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            recordIntent("started", intent)
            Log.i(TAG, "Service started: $packageName/$className")
            "Service started: $packageName/$className"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service by component", e)
            "Error starting service: ${e.message}"
        }
    }

    // ---- URI Methods ----

    /**
     * Open a URI with an ACTION_VIEW intent.
     *
     * @param context Application context
     * @param uri The URI to open
     */
    fun openUri(context: Context, uri: String): String {
        return try {
            if (uri.isBlank()) {
                return "Error: URI required"
            }

            val parsedUri = Uri.parse(uri)
            val intent = Intent(Intent.ACTION_VIEW, parsedUri)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                return "Error: No app found to handle URI '$uri'"
            }

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "URI opened: $uri")
            "URI opened: $uri"
        } catch (e: ActivityNotFoundException) {
            "Error: No app found to handle URI '$uri'"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URI: $uri", e)
            "Error opening URI: ${e.message}"
        }
    }

    // ---- Settings Methods ----

    /**
     * Open a system settings panel.
     *
     * @param context Application context
     * @param panel The settings panel identifier. Common values:
     *   "settings"      - Main settings
     *   "wifi"          - Wi-Fi settings
     *   "bluetooth"     - Bluetooth settings
     *   "wireless"      - Wireless & networks
     *   "airplane"      - Airplane mode
     *   "data_usage"    - Data usage
     *   "battery"       - Battery settings
     *   "display"       - Display settings
     *   "sound"         - Sound settings
     *   "storage"       - Storage settings
     *   "apps"          - App settings
     *   "security"      - Security settings
     *   "privacy"       - Privacy settings
     *   "location"      - Location settings
     *   "about"         - About phone
     *   "accessibility" - Accessibility settings
     *   "date"          - Date & time settings
     *   "developer"     - Developer options
     *   "nfc"           - NFC settings
     *   "notification"  - Notification settings (API 26+)
     */
    fun openSettings(context: Context, panel: String): String {
        return try {
            val action = settingsPanelToAction(panel)
                ?: return "Error: Unknown settings panel '$panel'"

            val intent = Intent(action)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Settings opened: $panel")
            "Settings opened: $panel"
        } catch (e: ActivityNotFoundException) {
            "Error: Settings panel '$panel' not available on this device"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: $panel", e)
            "Error opening settings: ${e.message}"
        }
    }

    // ---- Watch Methods ----

    /**
     * Start watching for broadcast intents with a specific action.
     * The watcher will log all matching broadcasts to the intent history.
     *
     * @param context Application context
     * @param action The intent action to watch for
     */
    fun watch(context: Context, action: String): String {
        return try {
            if (action.isBlank()) {
                return "Error: Intent action required"
            }

            // Don't register duplicate watchers
            if (activeWatchers.containsKey(action)) {
                return "Already watching action: $action"
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == action) {
                        recordIntent("received", intent)
                        Log.d(TAG, "Watched intent received: $action")
                    }
                }
            }

            val filter = IntentFilter(action)
            context.registerReceiver(receiver, filter)
            activeWatchers[action] = receiver

            Log.i(TAG, "Watching for intent action: $action")
            "Watching for intent action: $action"
        } catch (e: SecurityException) {
            "Error: Permission denied to watch '$action'"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to watch intent: $action", e)
            "Error watching intent: ${e.message}"
        }
    }

    /**
     * Stop watching for a specific intent action.
     *
     * @param context Application context
     * @param action The action to stop watching
     */
    fun unwatch(context: Context, action: String): String {
        val receiver = activeWatchers.remove(action)
        return if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
                Log.i(TAG, "Stopped watching: $action")
                "Stopped watching: $action"
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister watcher for: $action", e)
                "Watcher for '$action' removed (may have already been unregistered)"
            }
        } else {
            "Not watching action: $action"
        }
    }

    /**
     * Stop all active watchers.
     */
    fun unwatchAll(context: Context): String {
        val count = activeWatchers.size
        activeWatchers.forEach { (action, receiver) ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister watcher: $action", e)
            }
        }
        activeWatchers.clear()
        return "Stopped $count watchers"
    }

    /**
     * List all active watchers.
     */
    fun listWatchers(): String {
        return if (activeWatchers.isEmpty()) {
            "No active watchers"
        } else {
            buildString {
                appendLine("=== Active Intent Watchers (${activeWatchers.size}) ===")
                activeWatchers.keys.sorted().forEach { action ->
                    appendLine("  $action")
                }
            }
        }
    }

    // ---- History Methods ----

    /**
     * List recent intents from the history log.
     *
     * @param limit Maximum number of records to show
     */
    fun listHistory(limit: Int = 20): String {
        val history = intentHistory.takeLast(limit)
        if (history.isEmpty()) {
            return "No intent history"
        }

        return buildString {
            appendLine("=== Intent History (last ${history.size}) ===")
            history.reversed().forEach { appendLine(it.toFormattedString()) }
        }
    }

    /**
     * Clear the intent history.
     */
    fun clearHistory(): String {
        val count = intentHistory.size
        intentHistory.clear()
        return "Cleared $count intent history records"
    }

    // ---- Shortcut Methods ----

    /**
     * Dial a phone number (opens the dialer, does not place the call).
     *
     * @param context Application context
     * @param number Phone number to dial
     */
    fun dial(context: Context, number: String): String {
        return try {
            if (number.isBlank()) {
                return "Error: Phone number required"
            }

            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:$number")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Dialing: $number")
            "Dialing: $number"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial: $number", e)
            "Error dialing: ${e.message}"
        }
    }

    /**
     * Open the SMS app with a pre-filled number and message.
     *
     * @param context Application context
     * @param number Phone number to send SMS to
     * @param text Optional pre-filled message text
     */
    fun sms(context: Context, number: String, text: String = ""): String {
        return try {
            if (number.isBlank()) {
                return "Error: Phone number required"
            }

            val uri = Uri.parse("smsto:$number")
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            if (text.isNotBlank()) {
                intent.putExtra("sms_body", text)
            }

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "SMS intent: $number")
            "SMS intent opened: $number"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SMS: $number", e)
            "Error opening SMS: ${e.message}"
        }
    }

    /**
     * Open the email app with a pre-filled recipient and subject.
     *
     * @param context Application context
     * @param to Email address
     * @param subject Optional email subject
     * @param body Optional email body text
     */
    fun email(context: Context, to: String, subject: String = "", body: String = ""): String {
        return try {
            if (to.isBlank()) {
                return "Error: Email address required"
            }

            val intent = Intent(Intent.ACTION_SENDTO)
            intent.data = Uri.parse("mailto:$to")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            if (subject.isNotBlank() || body.isNotBlank()) {
                val extras = Bundle().apply {
                    if (subject.isNotBlank()) putString(Intent.EXTRA_SUBJECT, subject)
                    if (body.isNotBlank()) putString(Intent.EXTRA_TEXT, body)
                }
                intent.putExtras(extras)
            }

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Email intent: $to")
            "Email intent opened: $to"
        } catch (e: ActivityNotFoundException) {
            "Error: No email app found"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open email: $to", e)
            "Error opening email: ${e.message}"
        }
    }

    /**
     * Open the Google Play Store page for a specific app.
     *
     * @param context Application context
     * @param packageName The app's package name
     */
    fun market(context: Context, packageName: String): String {
        return try {
            if (packageName.isBlank()) {
                return "Error: Package name required"
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("market://details?id=$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Fallback to web Play Store
                intent.data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                context.startActivity(intent)
            }

            recordIntent("started", intent)
            Log.i(TAG, "Market opened: $packageName")
            "Play Store opened: $packageName"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Play Store: $packageName", e)
            "Error opening Play Store: ${e.message}"
        }
    }

    /**
     * Share text content using Android's share sheet.
     *
     * @param context Application context
     * @param text Text content to share
     * @param title Optional title for the share dialog
     */
    fun share(context: Context, text: String, title: String = "Share via TermX"): String {
        return try {
            if (text.isBlank()) {
                return "Error: Text to share is required"
            }

            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.putExtra(Intent.EXTRA_SUBJECT, title)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val chooser = Intent.createChooser(intent, title)
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            context.startActivity(chooser)
            recordIntent("sent", intent)

            Log.i(TAG, "Share intent sent")
            "Share dialog opened"
        } catch (e: ActivityNotFoundException) {
            "Error: No app found to share text"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share text", e)
            "Error sharing text: ${e.message}"
        }
    }

    /**
     * Open a URL in the default web browser.
     *
     * @param context Application context
     * @param url URL to open (will prepend https:// if no scheme specified)
     */
    fun web(context: Context, url: String): String {
        return try {
            if (url.isBlank()) {
                return "Error: URL required"
            }

            val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

            val uri = Uri.parse(finalUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                return "Error: No browser found to open URL"
            }

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "URL opened: $finalUrl")
            "URL opened: $finalUrl"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
            "Error opening URL: ${e.message}"
        }
    }

    /**
     * Open a map location.
     *
     * @param context Application context
     * @param query Location query (address or coordinates)
     */
    fun map(context: Context, query: String): String {
        return try {
            if (query.isBlank()) {
                return "Error: Location query required"
            }

            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "Map opened: $query")
            "Map opened: $query"
        } catch (e: ActivityNotFoundException) {
            "Error: No map app found"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open map: $query", e)
            "Error opening map: ${e.message}"
        }
    }

    /**
     * Install an APK from a file URI.
     *
     * @param context Application context
     * @param apkPath Path to the APK file
     */
    fun installApk(context: Context, apkPath: String): String {
        return try {
            if (apkPath.isBlank()) {
                return "Error: APK path required"
            }

            val file = java.io.File(apkPath)
            if (!file.exists()) {
                return "Error: APK file not found: $apkPath"
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

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            context.startActivity(intent)
            recordIntent("started", intent)

            Log.i(TAG, "APK install started: $apkPath")
            "APK install started: $apkPath"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK: $apkPath", e)
            "Error installing APK: ${e.message}"
        }
    }

    // ---- Internal Helpers ----

    /**
     * Add extras from a map to an intent, handling different value types.
     */
    private fun addExtrasToIntent(intent: Intent, extras: Map<String, Any>) {
        extras.forEach { (key, value) ->
            when (value) {
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Short -> intent.putExtra(key, value)
                is Byte -> intent.putExtra(key, value)
                is CharSequence -> intent.putExtra(key, value)
                is Bundle -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }

    /**
     * Record an intent in the history log.
     */
    private fun recordIntent(type: String, intent: Intent) {
        val extrasMap = mutableMapOf<String, String>()
        intent.extras?.keySet()?.forEach { key ->
            val value = intent.extras?.get(key)
            if (value != null) {
                extrasMap[key] = value.toString()
            }
        }

        val record = IntentRecord(
            type = type,
            action = intent.action,
            dataUri = intent.dataString,
            extras = extrasMap,
            component = intent.component?.flattenToString(),
            timestamp = System.currentTimeMillis()
        )

        intentHistory.add(record)

        // Trim history if it exceeds max size
        while (intentHistory.size > MAX_INTENT_HISTORY) {
            intentHistory.removeAt(0)
        }
    }

    /**
     * Convert a settings panel name to the corresponding Android settings action.
     */
    private fun settingsPanelToAction(panel: String): String? = when (panel.lowercase().trim()) {
        "settings" -> Settings.ACTION_SETTINGS
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "wireless", "network" -> Settings.ACTION_WIRELESS_SETTINGS
        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        "data_usage", "data" -> Settings.ACTION_DATA_USAGE_SETTINGS
        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound", "audio" -> Settings.ACTION_SOUND_SETTINGS
        "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
        "security" -> Settings.ACTION_SECURITY_SETTINGS
        "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
        "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "date", "datetime", "time" -> Settings.ACTION_DATE_SETTINGS
        "developer", "dev" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        "nfc" -> Settings.ACTION_NFC_SETTINGS
        "notification" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Settings.ACTION_APP_NOTIFICATION_SETTINGS
            } else null
        }
        "locale", "language" -> Settings.ACTION_LOCALE_SETTINGS
        "input", "keyboard" -> Settings.ACTION_INPUT_METHOD_SETTINGS
        "vpn" -> Settings.ACTION_VPN_SETTINGS
        else -> null
    }

    /**
     * Release all resources and unregister all watchers.
     */
    fun release(context: Context): String {
        val watcherCount = activeWatchers.size
        activeWatchers.forEach { (_, receiver) ->
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
        activeWatchers.clear()
        intentHistory.clear()
        Log.i(TAG, "IntentBridgeApi released ($watcherCount watchers unregistered)")
        return "IntentBridgeApi released ($watcherCount watchers unregistered)"
    }
}
