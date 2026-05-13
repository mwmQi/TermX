package com.termx.app.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.util.Log
import com.termx.app.TermXApp
import com.termx.app.location.LocationProvider
import com.termx.app.media.MediaManager
import com.termx.app.pkg.TermXPackageManager
import com.termx.app.power.*
import com.termx.app.power.cron.CronScheduler
import com.termx.app.power.ssh.SshServer
import com.termx.app.power.tunnel.PortForwarder
import com.termx.app.sensor.SensorProvider
import com.termx.app.telephony.TelephonyInfo
import com.termx.app.x11.X11Manager
import java.io.File

/**
 * TermX API receiver — central command dispatcher for ALL TermX features.
 *
 * Handles:
 *   - Core API: URL, file, clipboard, toast, vibrate, notify, dialog, storage, battery, wifi
 *   - Extended API: location, sensor, telephony, media, download, volume, TTS, wifi-scan/enable
 *   - Package Manager: install, uninstall, update, upgrade, search, show, upgradable, clean
 *   - X11 Display: start/stop/status/resize/screenshot displays (:0, :1, :2, etc.)
 *   - Priority 3: camera, SMS, fingerprint, contact, STT, NFC, battery detail,
 *                 notification with actions, file picker, screenshot, app install
 *   - Power Features: SSH/SFTP, Cron, Tunnel, Bluetooth, USB Serial, Web Server,
 *                     Process Monitor, Flashlight, Call Log, Screen Recorder,
 *                     Macro System, Intent Bridge, Encryption, Network Tools, Profiles
 */
class TermXApiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermXApiReceiver"

        // Core API actions
        const val ACTION_OPEN_URL = "com.termx.app.api.OPEN_URL"
        const val ACTION_OPEN_FILE = "com.termx.app.api.OPEN_FILE"
        const val ACTION_SHARE_TEXT = "com.termx.app.api.SHARE_TEXT"
        const val ACTION_CLIPBOARD_GET = "com.termx.app.api.CLIPBOARD_GET"
        const val ACTION_CLIPBOARD_SET = "com.termx.app.api.CLIPBOARD_SET"
        const val ACTION_TOAST = "com.termx.app.api.TOAST"
        const val ACTION_VIBRATE = "com.termx.app.api.VIBRATE"
        const val ACTION_NOTIFY = "com.termx.app.api.NOTIFY"
        const val ACTION_DIALOG = "com.termx.app.api.DIALOG"
        const val ACTION_SETUP_STORAGE = "com.termx.app.api.SETUP_STORAGE"
        const val ACTION_STORAGE_INFO = "com.termx.app.api.STORAGE_INFO"
        const val ACTION_BATTERY_INFO = "com.termx.app.api.BATTERY_INFO"
        const val ACTION_WIFI_INFO = "com.termx.app.api.WIFI_INFO"
        const val ACTION_HELP = "com.termx.app.api.HELP"

        // Extended API actions
        const val ACTION_LOCATION = "com.termx.app.api.LOCATION"
        const val ACTION_SENSOR_INFO = "com.termx.app.api.SENSOR_INFO"
        const val ACTION_SENSOR_READ = "com.termx.app.api.SENSOR_READ"
        const val ACTION_TELEPHONY_INFO = "com.termx.app.api.TELEPHONY_INFO"
        const val ACTION_MEDIA_PLAYER = "com.termx.app.api.MEDIA_PLAYER"
        const val ACTION_DOWNLOAD = "com.termx.app.api.DOWNLOAD"
        const val ACTION_VOLUME = "com.termx.app.api.VOLUME"
        const val ACTION_TTS = "com.termx.app.api.TTS"
        const val ACTION_WIFI_ENABLE = "com.termx.app.api.WIFI_ENABLE"
        const val ACTION_WIFI_SCAN = "com.termx.app.api.WIFI_SCAN"

        // Package Manager actions
        const val ACTION_PKG_INSTALL = "com.termx.app.api.PKG_INSTALL"
        const val ACTION_PKG_UNINSTALL = "com.termx.app.api.PKG_UNINSTALL"
        const val ACTION_PKG_UPDATE = "com.termx.app.api.PKG_UPDATE"
        const val ACTION_PKG_UPGRADE = "com.termx.app.api.PKG_UPGRADE"
        const val ACTION_PKG_SEARCH = "com.termx.app.api.PKG_SEARCH"
        const val ACTION_PKG_SHOW = "com.termx.app.api.PKG_SHOW"
        const val ACTION_PKG_UPGRADABLE = "com.termx.app.api.PKG_UPGRADABLE"
        const val ACTION_PKG_CLEAN = "com.termx.app.api.PKG_CLEAN"
        const val ACTION_RELOAD_SETTINGS = "com.termx.app.api.RELOAD_SETTINGS"

        // X11 Display actions
        const val ACTION_X11_DISPLAY = "com.termx.app.api.X11_DISPLAY"
        const val ACTION_X11_VNC = "com.termx.app.api.X11_VNC"

        // Priority 3 API actions
        const val ACTION_CAMERA = "com.termx.app.api.CAMERA"
        const val ACTION_SMS = "com.termx.app.api.SMS"
        const val ACTION_FINGERPRINT = "com.termx.app.api.FINGERPRINT"
        const val ACTION_CONTACT = "com.termx.app.api.CONTACT"
        const val ACTION_STT = "com.termx.app.api.STT"
        const val ACTION_NFC = "com.termx.app.api.NFC"
        const val ACTION_BATTERY = "com.termx.app.api.BATTERY"
        const val ACTION_NOTIFICATION = "com.termx.app.api.NOTIFICATION"
        const val ACTION_FILE_PICKER = "com.termx.app.api.FILE_PICKER"
        const val ACTION_SCREENSHOT = "com.termx.app.api.SCREENSHOT"
        const val ACTION_APP = "com.termx.app.api.APP"

        // Power Feature actions
        const val ACTION_SSH = "com.termx.app.api.SSH"
        const val ACTION_CRON = "com.termx.app.api.CRON"
        const val ACTION_TUNNEL = "com.termx.app.api.TUNNEL"
        const val ACTION_BLUETOOTH = "com.termx.app.api.BLUETOOTH"
        const val ACTION_USB_SERIAL = "com.termx.app.api.USB_SERIAL"
        const val ACTION_WEB_SERVER = "com.termx.app.api.WEB_SERVER"
        const val ACTION_PROCESS = "com.termx.app.api.PROCESS"
        const val ACTION_FLASHLIGHT = "com.termx.app.api.FLASHLIGHT"
        const val ACTION_CALL_LOG = "com.termx.app.api.CALL_LOG"
        const val ACTION_SCREEN_RECORD = "com.termx.app.api.SCREEN_RECORD"
        const val ACTION_MACRO = "com.termx.app.api.MACRO"
        const val ACTION_INTENT = "com.termx.app.api.INTENT"
        const val ACTION_ENCRYPTION = "com.termx.app.api.ENCRYPTION"
        const val ACTION_NET_TOOLS = "com.termx.app.api.NET_TOOLS"
        const val ACTION_PROFILE = "com.termx.app.api.PROFILE"

        /** Write result to cache file for shell scripts to read */
        private fun writeResult(context: Context, filename: String, content: String) {
            File(context.cacheDir, filename).writeText(content)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // ============================================================
            // CORE API
            // ============================================================
            ACTION_OPEN_URL -> {
                val url = intent.getStringExtra("url")
                if (url != null) TermXApi.openUrl(context, url)
            }
            ACTION_OPEN_FILE -> {
                val path = intent.getStringExtra("path")
                if (path != null) TermXApi.openFile(context, path)
            }
            ACTION_SHARE_TEXT -> {
                val text = intent.getStringExtra("text")
                if (text != null) TermXApi.shareText(context, text)
            }
            ACTION_CLIPBOARD_GET -> {
                val text = TermXApi.getClipboard(context)
                writeResult(context, "clipboard.txt", text)
            }
            ACTION_CLIPBOARD_SET -> {
                val text = intent.getStringExtra("text")
                if (text != null) TermXApi.setClipboard(context, text)
            }
            ACTION_TOAST -> {
                val msg = intent.getStringExtra("message") ?: "TermX"
                TermXApi.showToast(context, msg)
            }
            ACTION_VIBRATE -> {
                val duration = intent.getLongExtra("duration", 100)
                TermXApi.vibrate(context, duration)
            }
            ACTION_NOTIFY -> {
                val title = intent.getStringExtra("title") ?: "TermX"
                val content = intent.getStringExtra("content") ?: ""
                TermXApi.showNotification(context, title, content)
            }
            ACTION_DIALOG -> {
                val title = intent.getStringExtra("title") ?: "TermX"
                val message = intent.getStringExtra("message") ?: ""
                TermXApi.showDialog(context, title, message)
            }
            ACTION_SETUP_STORAGE -> {
                val result = TermXApi.setupStorage(context)
                TermXApi.showToast(context, result)
            }
            ACTION_STORAGE_INFO -> {
                val info = com.termx.app.storage.StorageSetup.getStorageInfo(context)
                writeResult(context, "storage_info.txt", info)
            }
            ACTION_BATTERY_INFO -> {
                val info = TermXApi.getBatteryInfo(context)
                TermXApi.showToast(context, info)
                writeResult(context, "battery_info.txt", info)
            }
            ACTION_WIFI_INFO -> {
                val info = getWifiInfo(context)
                TermXApi.showToast(context, info)
            }
            ACTION_HELP -> {
                Log.i(TAG, getFullHelpText())
            }

            // ============================================================
            // EXTENDED API
            // ============================================================
            ACTION_LOCATION -> {
                val provider = intent.getStringExtra("provider") ?: LocationManager.GPS_PROVIDER
                LocationProvider.requestLocation(context, provider) { result ->
                    val output = result.toFormattedString()
                    writeResult(context, "location.txt", output)
                    TermXApi.showToast(context, "Location: ${result.latitude}, ${result.longitude}")
                }
            }
            ACTION_SENSOR_INFO -> {
                val info = SensorProvider.getSensorInfo(context)
                writeResult(context, "sensor_info.txt", info)
                TermXApi.showToast(context, "Sensor info saved")
            }
            ACTION_SENSOR_READ -> {
                val sensorType = intent.getIntExtra("sensor_type", android.hardware.Sensor.TYPE_ACCELEROMETER)
                SensorProvider.readSensor(context, sensorType) { data ->
                    val output = data?.toFormattedString() ?: "Sensor not available"
                    writeResult(context, "sensor_data.txt", output)
                }
            }
            ACTION_TELEPHONY_INFO -> {
                val info = TelephonyInfo.getDeviceInfo(context)
                val output = info.toFormattedString()
                writeResult(context, "telephony_info.txt", output)
                TermXApi.showToast(context, "Telephony info saved")
            }
            ACTION_MEDIA_PLAYER -> {
                val action = intent.getStringExtra("action") ?: "info"
                val source = intent.getStringExtra("source")
                val result = when (action) {
                    "play" -> if (source != null) MediaManager.play(context, source) else "No source specified"
                    "pause" -> MediaManager.pause()
                    "resume" -> MediaManager.resume()
                    "stop" -> MediaManager.stop()
                    else -> MediaManager.info()
                }
                writeResult(context, "media_info.txt", result)
            }
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra("url")
                val filename = intent.getStringExtra("filename")
                if (url != null) startDownload(context, url, filename)
            }
            ACTION_VOLUME -> {
                val stream = intent.getStringExtra("stream") ?: "music"
                val level = intent.getIntExtra("level", -1)
                val volumeInfo = getVolumeInfo(context, stream, level)
                writeResult(context, "volume_info.txt", volumeInfo)
            }
            ACTION_TTS -> {
                val text = intent.getStringExtra("text") ?: ""
                val language = intent.getStringExtra("language") ?: "en"
                if (text.isNotEmpty()) speakTTS(context, text, language)
            }
            ACTION_WIFI_ENABLE -> {
                val enable = intent.getBooleanExtra("enable", true)
                setWifiEnabled(context, enable)
            }
            ACTION_WIFI_SCAN -> {
                val results = getWifiScanResults(context)
                writeResult(context, "wifi_scan.txt", results)
            }

            // ============================================================
            // PACKAGE MANAGER
            // ============================================================
            ACTION_PKG_INSTALL -> {
                val packages = intent.getStringExtra("packages") ?: ""
                if (packages.isNotEmpty()) {
                    Thread {
                        val pkgManager = TermXApp.getPackageManager()
                        val pkgList = packages.split(" ").filter { it.isNotEmpty() }
                        val result = pkgManager.installPackages(pkgList) { msg, pct ->
                            Log.d(TAG, "Install: $msg ($pct%)")
                        }
                        Log.i(TAG, "Install result: ${result.message}")
                    }.start()
                    TermXApi.showToast(context, "Installing: $packages")
                }
            }
            ACTION_PKG_UNINSTALL -> {
                val packages = intent.getStringExtra("packages") ?: ""
                if (packages.isNotEmpty()) {
                    Thread {
                        val pkgManager = TermXApp.getPackageManager()
                        val pkgList = packages.split(" ").filter { it.isNotEmpty() }
                        for (pkg in pkgList) {
                            val result = pkgManager.uninstallPackage(pkg)
                            Log.i(TAG, "Uninstall $pkg: ${result.message}")
                        }
                    }.start()
                    TermXApi.showToast(context, "Removing: $packages")
                }
            }
            ACTION_PKG_UPDATE -> {
                Thread {
                    val pkgManager = TermXApp.getPackageManager()
                    val result = pkgManager.updateIndex(true)
                    Log.i(TAG, "Update result: ${result.packageCount} packages")
                    TermXApi.showToast(context, "Updated: ${result.packageCount} packages available")
                }.start()
            }
            ACTION_PKG_UPGRADE -> {
                Thread {
                    val pkgManager = TermXApp.getPackageManager()
                    val result = pkgManager.upgradeAll()
                    Log.i(TAG, "Upgrade: ${result.upgradedPackages.size} upgraded")
                }.start()
                TermXApi.showToast(context, "Upgrading packages...")
            }
            ACTION_PKG_SEARCH -> {
                val query = intent.getStringExtra("query") ?: ""
                if (query.isNotEmpty()) {
                    Thread {
                        val pkgManager = TermXApp.getPackageManager()
                        val results = pkgManager.search(query)
                        val output = if (results.isEmpty()) {
                            "No packages found for '$query'"
                        } else {
                            results.joinToString("\n") { pkg ->
                                "${pkg.name} ${pkg.version} - ${pkg.description}"
                            }
                        }
                        writeResult(context, "pkg_search.txt", output)
                    }.start()
                }
            }
            ACTION_PKG_SHOW -> {
                val pkgName = intent.getStringExtra("package") ?: ""
                if (pkgName.isNotEmpty()) {
                    Thread {
                        val pkgManager = TermXApp.getPackageManager()
                        val pkg = pkgManager.showInfo(pkgName)
                        val output = if (pkg != null) {
                            buildString {
                                appendLine("Package: ${pkg.name}")
                                appendLine("Version: ${pkg.version}")
                                appendLine("Architecture: ${pkg.arch}")
                                appendLine("Description: ${pkg.description}")
                                appendLine("Depends: ${pkg.depends.joinToString(", ")}")
                                appendLine("Installed: ${if (pkg.installed) "yes (${pkg.installedVersion})" else "no"}")
                                appendLine("Category: ${pkg.category}")
                                appendLine("License: ${pkg.license}")
                                appendLine("Homepage: ${pkg.homepage}")
                                appendLine("Size: ${pkg.size} bytes")
                            }
                        } else {
                            "Package not found: $pkgName"
                        }
                        writeResult(context, "pkg_show.txt", output)
                    }.start()
                }
            }
            ACTION_PKG_UPGRADABLE -> {
                Thread {
                    val pkgManager = TermXApp.getPackageManager()
                    val upgradable = pkgManager.listUpgradable()
                    val output = if (upgradable.isEmpty()) {
                        "All packages are up to date"
                    } else {
                        upgradable.joinToString("\n") { pkg ->
                            "${pkg.name} ${pkg.installedVersion} -> ${pkg.version}"
                        }
                    }
                    writeResult(context, "pkg_upgradable.txt", output)
                }.start()
            }
            ACTION_PKG_CLEAN -> {
                Thread {
                    val pkgManager = TermXApp.getPackageManager()
                    pkgManager.cleanCache()
                    TermXApi.showToast(context, "Package cache cleaned")
                }.start()
            }
            ACTION_RELOAD_SETTINGS -> {
                Log.i(TAG, "Settings reload requested")
            }

            // ============================================================
            // X11 DISPLAY
            // ============================================================
            ACTION_X11_DISPLAY -> {
                val command = intent.getStringExtra("command") ?: "status"
                val displayNum = intent.getIntExtra("display_num", 0)
                when (command) {
                    "start" -> {
                        val width = intent.getIntExtra("width", 1024)
                        val height = intent.getIntExtra("height", 768)
                        val vncPort = intent.getIntExtra("vnc_port", 5900 + displayNum)
                        val result = X11Manager.startDisplay(context, displayNum, width, height, vncPort = vncPort)
                        val status = if (result) "Display :$displayNum started: ${width}x${height}" else "Failed to start display"
                        writeResult(context, "x11_status.txt", X11Manager.getStatus())
                        TermXApi.showToast(context, status)
                    }
                    "stop" -> {
                        X11Manager.stopDisplay(displayNum)
                        TermXApi.showToast(context, "Display :$displayNum stopped")
                    }
                    "status" -> {
                        writeResult(context, "x11_status.txt", X11Manager.getStatus())
                    }
                    "list" -> {
                        val list = X11Manager.listDisplays().joinToString("\n") {
                            ":${it.displayNum} ${it.width}x${it.height}"
                        }
                        writeResult(context, "x11_list.txt", list.ifEmpty { "No displays running" })
                    }
                    "resize" -> {
                        val width = intent.getIntExtra("width", 1024)
                        val height = intent.getIntExtra("height", 768)
                        X11Manager.resizeDisplay(displayNum, width, height)
                    }
                    "screenshot" -> {
                        val path = intent.getStringExtra("path")
                            ?: "/data/data/com.termx.app/files/home/screenshot_${displayNum}_${System.currentTimeMillis()}.png"
                        X11Manager.takeScreenshot(displayNum, path)
                    }
                }
            }
            ACTION_X11_VNC -> {
                val command = intent.getStringExtra("command") ?: "status"
                val result = X11Manager.handleVncCommand(context, listOf(command))
                writeResult(context, "vnc_status.txt", result)
            }

            // ============================================================
            // PRIORITY 3: Camera, SMS, Fingerprint, Contacts, STT, NFC,
            //             Battery Detail, Notification, File Picker,
            //             Screenshot, App Install
            // ============================================================
            ACTION_CAMERA -> {
                val action = intent.getStringExtra("action") ?: "info"
                when (action) {
                    "photo" -> {
                        val facing = intent.getStringExtra("facing") ?: "back"
                        val outputPath = intent.getStringExtra("output")
                            ?: "/data/data/com.termx.app/files/home/photo_${System.currentTimeMillis()}.jpg"
                        val result = CameraApi.takePhoto(context, outputPath, facing)
                        writeResult(context, "camera_result.txt", result)
                    }
                    "video" -> {
                        val duration = intent.getIntExtra("duration", 10)
                        val outputPath = intent.getStringExtra("output")
                            ?: "/data/data/com.termx.app/files/home/video_${System.currentTimeMillis()}.mp4"
                        val result = CameraApi.recordVideo(context, outputPath, duration)
                        writeResult(context, "camera_result.txt", result)
                    }
                    "info" -> {
                        val info = CameraApi.getCameraInfo(context)
                        writeResult(context, "camera_info.txt", info)
                    }
                }
            }
            ACTION_SMS -> {
                val action = intent.getStringExtra("action") ?: "inbox"
                when (action) {
                    "send" -> {
                        val number = intent.getStringExtra("number") ?: ""
                        val message = intent.getStringExtra("message") ?: ""
                        val result = SmsApi.sendSms(context, number, message)
                        writeResult(context, "sms_result.txt", result)
                    }
                    "inbox" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        val result = SmsApi.readInbox(context, limit)
                        writeResult(context, "sms_inbox.txt", result)
                    }
                    "sent" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        val result = SmsApi.readSent(context, limit)
                        writeResult(context, "sms_sent.txt", result)
                    }
                    "delete" -> {
                        val id = intent.getIntExtra("id", -1)
                        val result = SmsApi.deleteSms(context, id.toLong())
                        writeResult(context, "sms_result.txt", result)
                    }
                }
            }
            ACTION_FINGERPRINT -> {
                val action = intent.getStringExtra("action") ?: "status"
                val result = when (action) {
                    "available" -> FingerprintApi.isHardwareAvailable(context).toString()
                    "enrolled" -> FingerprintApi.isEnrolled(context).toString()
                    else -> FingerprintApi.getStatus(context)
                }
                writeResult(context, "fingerprint_result.txt", result)
            }
            ACTION_CONTACT -> {
                val action = intent.getStringExtra("action") ?: "list"
                when (action) {
                    "list" -> {
                        val limit = intent.getIntExtra("limit", 50)
                        val result = ContactApi.listContacts(context, limit)
                        writeResult(context, "contacts.txt", result)
                    }
                    "get" -> {
                        val id = intent.getLongExtra("id", -1)
                        val result = ContactApi.getContactById(context, id)
                        writeResult(context, "contact.txt", result)
                    }
                    "search" -> {
                        val query = intent.getStringExtra("query") ?: ""
                        val result = ContactApi.searchContacts(context, query)
                        writeResult(context, "contacts_search.txt", result)
                    }
                    "add" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        val phone = intent.getStringExtra("phone") ?: ""
                        val email = intent.getStringExtra("email") ?: ""
                        val result = ContactApi.addContact(context, name, phone, email)
                        writeResult(context, "contact_result.txt", result)
                    }
                    "delete" -> {
                        val id = intent.getLongExtra("id", -1)
                        val result = ContactApi.deleteContact(context, id)
                        writeResult(context, "contact_result.txt", result)
                    }
                }
            }
            ACTION_STT -> {
                val action = intent.getStringExtra("action") ?: "start"
                when (action) {
                    "start" -> {
                        val language = intent.getStringExtra("language") ?: "en-US"
                        val result = SttApi.startListening(context, language)
                        writeResult(context, "stt_result.txt", result)
                    }
                    "stop" -> {
                        val result = SttApi.stopListening(context)
                        writeResult(context, "stt_result.txt", result)
                    }
                    "result" -> {
                        val result = SttApi.getResult(context)
                        writeResult(context, "stt_text.txt", result)
                    }
                }
            }
            ACTION_NFC -> {
                val action = intent.getStringExtra("action") ?: "available"
                when (action) {
                    "available" -> {
                        val result = NfcApi.checkAvailability(context)
                        writeResult(context, "nfc_result.txt", result)
                    }
                    "read" -> {
                        val result = NfcApi.checkAvailability(context)
                        writeResult(context, "nfc_result.txt", result)
                    }
                    "write" -> {
                        val text = intent.getStringExtra("text") ?: ""
                        writeResult(context, "nfc_result.txt", "NFC write requires active tag - use termx-nfc write <text> in terminal")
                    }
                }
            }
            ACTION_BATTERY -> {
                val action = intent.getStringExtra("action") ?: "info"
                val result = when (action) {
                    "health" -> BatteryApi.getBatteryDetail(context)
                    "power-save" -> BatteryApi.isPowerSaveMode(context)
                    else -> BatteryApi.getBatteryDetail(context)
                }
                val filename = when (action) {
                    "health" -> "battery_health.txt"
                    "power-save" -> "battery_powersave.txt"
                    else -> "battery_detail.txt"
                }
                writeResult(context, filename, result)
            }
            ACTION_NOTIFICATION -> {
                val action = intent.getStringExtra("action") ?: "show"
                val result = when (action) {
                    "show" -> {
                        val title = intent.getStringExtra("title") ?: "TermX"
                        val content = intent.getStringExtra("content") ?: ""
                        val id = intent.getIntExtra("id", 3000)
                        NotificationApi.showNotification(context, title, content, id)
                    }
                    "cancel" -> {
                        val id = intent.getIntExtra("id", 3000)
                        NotificationApi.cancelNotification(context, id)
                    }
                    "ongoing" -> {
                        val title = intent.getStringExtra("title") ?: "TermX"
                        val content = intent.getStringExtra("content") ?: ""
                        NotificationApi.showOngoingNotification(context, title, content)
                    }
                    "progress" -> {
                        val title = intent.getStringExtra("title") ?: "TermX"
                        val content = intent.getStringExtra("content") ?: ""
                        val progress = intent.getIntExtra("progress", 50)
                        NotificationApi.showProgressNotification(context, title, content, progress)
                    }
                    else -> "Unknown notification action: $action"
                }
                writeResult(context, "notif_result.txt", result)
            }
            ACTION_FILE_PICKER -> {
                val action = intent.getStringExtra("action") ?: "open"
                val mimeType = intent.getStringExtra("mime_type") ?: "*/*"
                when (action) {
                    "open" -> {
                        val result = FilePickerApi.openFilePicker(context, mimeType)
                        writeResult(context, "filepicker_result.txt", result)
                    }
                    "save" -> {
                        val filename = intent.getStringExtra("filename") ?: "file.txt"
                        val result = FilePickerApi.openSaveFilePicker(context, filename, mimeType)
                        writeResult(context, "filepicker_result.txt", result)
                    }
                    "dir" -> {
                        val result = FilePickerApi.openDirectoryPicker(context)
                        writeResult(context, "filepicker_result.txt", result)
                    }
                }
            }
            ACTION_SCREENSHOT -> {
                val outputPath = intent.getStringExtra("path")
                    ?: "/data/data/com.termx.app/files/home/screenshot_${System.currentTimeMillis()}.png"
                val result = ScreenshotApi.takeScreenshot(context, outputPath)
                writeResult(context, "screenshot_result.txt", result)
            }
            ACTION_APP -> {
                val action = intent.getStringExtra("action") ?: "list"
                when (action) {
                    "install" -> {
                        val path = intent.getStringExtra("path") ?: ""
                        val result = AppInstallApi.installApk(context, path)
                        writeResult(context, "app_result.txt", result)
                    }
                    "uninstall" -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val result = AppInstallApi.uninstallApp(context, pkg)
                        writeResult(context, "app_result.txt", result)
                    }
                    "list" -> {
                        val filter = intent.getStringExtra("filter") ?: "all"
                        val result = AppInstallApi.listApps(context, filter)
                        writeResult(context, "app_list.txt", result)
                    }
                    "info" -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val result = AppInstallApi.getAppInfo(context, pkg)
                        writeResult(context, "app_info.txt", result)
                    }
                    "launch" -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val result = AppInstallApi.launchApp(context, pkg)
                        writeResult(context, "app_result.txt", result)
                    }
                    "installed" -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        val result = AppInstallApi.isAppInstalled(context, pkg)
                        writeResult(context, "app_result.txt", result)
                    }
                }
            }

            // ============================================================
            // POWER FEATURES: SSH/SFTP Server
            // ============================================================
            ACTION_SSH -> {
                val action = intent.getStringExtra("action") ?: "status"
                val sshServer = SshServer.getInstance(context)
                when (action) {
                    "start" -> {
                        val port = intent.getIntExtra("port", 8022)
                        val result = sshServer.start(port)
                        writeResult(context, "ssh_result.txt", if (result) "SSH server started on port $port" else "Failed to start SSH server")
                    }
                    "stop" -> {
                        sshServer.stop()
                        writeResult(context, "ssh_result.txt", "SSH server stopped")
                    }
                    "status" -> {
                        writeResult(context, "ssh_result.txt", sshServer.getStatusText())
                    }
                    "restart" -> {
                        val restarted = sshServer.restart()
                        writeResult(context, "ssh_result.txt", if (restarted) "SSH restarted" else "Failed to restart")
                    }
                    "keygen" -> {
                        val type = intent.getStringExtra("type") ?: "rsa"
                        val keyMgr = com.termx.app.power.ssh.HostKeyManager(context)
                        val result = keyMgr.generateKey(type)
                        writeResult(context, "ssh_result.txt", "Key generated: ${result.keyType} (${result.keySize} bits)\nFingerprint: ${result.fingerprintSha256}")
                    }
                    "keys" -> {
                        val keyMgr = com.termx.app.power.ssh.HostKeyManager(context)
                        val result = keyMgr.getAllKeyInfo().joinToString("\n") { "${it.type} ${it.keySize}bits SHA256:${it.fingerprintSha256}" }
                        writeResult(context, "ssh_keys.txt", result)
                    }
                    "key-add" -> {
                        val pubkey = intent.getStringExtra("pubkey") ?: ""
                        val added = SshServer.getInstance(context).addAuthorizedKey(pubkey)
                        writeResult(context, "ssh_result.txt", if (added) "Key added" else "Failed to add key")
                    }
                    "sessions" -> {
                        writeResult(context, "ssh_sessions.txt", sshServer.getStatusText())
                    }
                    "config" -> {
                        writeResult(context, "ssh_config.txt", sshServer.generateDefaultConfig())
                    }
                }
            }

            // ============================================================
            // POWER FEATURES: Cron/Scheduler
            // ============================================================
            ACTION_CRON -> {
                val args = intent.getStringArrayListExtra("args") ?: arrayListOf()
                val result = CronScheduler.processShellCommand(context, args)
                writeResult(context, "cron_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Port Forwarding/Tunnel
            // ============================================================
            ACTION_TUNNEL -> {
                val args = intent.getStringArrayListExtra("args") ?: arrayListOf()
                val forwarder = PortForwarder.getInstance(context)
                val result = forwarder.processCommand(args)
                writeResult(context, "tunnel_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Bluetooth
            // ============================================================
            ACTION_BLUETOOTH -> {
                val action = intent.getStringExtra("action") ?: "info"
                val result = when (action) {
                    "enable" -> BluetoothApi.enable(context)
                    "disable" -> BluetoothApi.disable(context)
                    "scan" -> BluetoothApi.scan(context)
                    "paired" -> BluetoothApi.paired(context)
                    "connect" -> {
                        val addr = intent.getStringExtra("address") ?: ""
                        BluetoothApi.connect(context, addr)
                    }
                    "disconnect" -> {
                        val addr = intent.getStringExtra("address") ?: ""
                        BluetoothApi.disconnect(context, addr)
                    }
                    "send" -> {
                        val addr = intent.getStringExtra("address") ?: ""
                        val file = intent.getStringExtra("file") ?: ""
                        BluetoothApi.send(context, addr, file)
                    }
                    "info" -> {
                        val addr = intent.getStringExtra("address")
                        if (addr != null) BluetoothApi.info(context, addr)
                        else BluetoothApi.info(context)
                    }
                    "discoverable" -> BluetoothApi.discoverable(context)
                    "name" -> {
                        val newName = intent.getStringExtra("name")
                        if (newName != null) BluetoothApi.name(context, newName)
                        else BluetoothApi.name(context)
                    }
                    else -> BluetoothApi.info(context)
                }
                writeResult(context, "bluetooth_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: USB Serial
            // ============================================================
            ACTION_USB_SERIAL -> {
                val action = intent.getStringExtra("action") ?: "list"
                val result = when (action) {
                    "list" -> UsbSerialApi.list(context)
                    "open" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        val baud = intent.getIntExtra("baud", 9600)
                        UsbSerialApi.open(context, device, baud)
                    }
                    "close" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        UsbSerialApi.close(device)
                    }
                    "read" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        val bytes = intent.getIntExtra("bytes", 256)
                        UsbSerialApi.read(device, bytes)
                    }
                    "write" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        val data = intent.getStringExtra("data") ?: ""
                        UsbSerialApi.write(device, data)
                    }
                    "config" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        val baud = intent.getIntExtra("baud", 9600)
                        UsbSerialApi.config(device, "baud=$baud")
                    }
                    "info" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        UsbSerialApi.info(context, device)
                    }
                    "monitor" -> {
                        val device = intent.getStringExtra("device") ?: ""
                        val duration = intent.getIntExtra("duration", 30)
                        UsbSerialApi.monitor(device, duration.toLong())
                    }
                    else -> UsbSerialApi.list(context)
                }
                writeResult(context, "usb_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Web Server
            // ============================================================
            ACTION_WEB_SERVER -> {
                val action = intent.getStringExtra("action") ?: "status"
                val result = when (action) {
                    "start" -> {
                        val port = intent.getIntExtra("port", 8080)
                        val dir = intent.getStringExtra("dir") ?: ""
                        WebServerApi.start(context, port, dir)
                    }
                    "stop" -> WebServerApi.stop()
                    "status" -> WebServerApi.status()
                    "port" -> {
                        val port = intent.getIntExtra("port", 8080)
                        WebServerApi.setPort(port)
                    }
                    "dir" -> {
                        val dir = intent.getStringExtra("dir") ?: ""
                        WebServerApi.setDirectory(dir)
                    }
                    "auth" -> {
                        val user = intent.getStringExtra("user") ?: "termx"
                        val pass = intent.getStringExtra("pass") ?: "termx"
                        WebServerApi.setAuth(user, pass)
                    }
                    "no-auth" -> WebServerApi.disableAuth()
                    "logs" -> WebServerApi.logs()
                    "clear-logs" -> WebServerApi.clearLogs()
                    else -> WebServerApi.status()
                }
                writeResult(context, "webserver_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Process Monitor
            // ============================================================
            ACTION_PROCESS -> {
                val action = intent.getStringExtra("action") ?: "list"
                val result = when (action) {
                    "list" -> ProcessMonitorApi.list()
                    "top" -> ProcessMonitorApi.top()
                    "info" -> {
                        val pid = intent.getIntExtra("pid", -1)
                        if (pid >= 0) ProcessMonitorApi.info(pid) else "Usage: process info <pid>"
                    }
                    "kill" -> {
                        val pid = intent.getIntExtra("pid", -1)
                        if (pid >= 0) ProcessMonitorApi.kill(pid) else "Usage: process kill <pid>"
                    }
                    "tree" -> ProcessMonitorApi.tree()
                    "threads" -> {
                        val pid = intent.getIntExtra("pid", -1)
                        if (pid >= 0) ProcessMonitorApi.threads(pid) else "Usage: process threads <pid>"
                    }
                    "mem" -> ProcessMonitorApi.mem()
                    "cpu" -> ProcessMonitorApi.cpu()
                    "find" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        ProcessMonitorApi.find(name)
                    }
                    else -> ProcessMonitorApi.list()
                }
                writeResult(context, "process_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Flashlight
            // ============================================================
            ACTION_FLASHLIGHT -> {
                val action = intent.getStringExtra("action") ?: "status"
                val result = when (action) {
                    "on" -> FlashlightApi.turnOn(context)
                    "off" -> FlashlightApi.turnOff(context)
                    "toggle" -> FlashlightApi.toggle(context)
                    "blink" -> {
                        val onMs = intent.getIntExtra("on_ms", 200).toLong()
                        val offMs = intent.getIntExtra("off_ms", 200).toLong()
                        val count = intent.getIntExtra("count", 5)
                        FlashlightApi.blink(context, onMs, offMs, count)
                    }
                    "sos" -> FlashlightApi.sos(context)
                    "strobe" -> {
                        val freq = intent.getIntExtra("freq", 5).toDouble()
                        FlashlightApi.strobe(context, freq)
                    }
                    "status" -> FlashlightApi.getStatus(context)
                    else -> FlashlightApi.getStatus(context)
                }
                writeResult(context, "flashlight_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Call Log
            // ============================================================
            ACTION_CALL_LOG -> {
                val action = intent.getStringExtra("action") ?: "list"
                val result = when (action) {
                    "list" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        CallLogApi.listCalls(context, limit)
                    }
                    "incoming" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        CallLogApi.listIncoming(context, limit)
                    }
                    "outgoing" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        CallLogApi.listOutgoing(context, limit)
                    }
                    "missed" -> {
                        val limit = intent.getIntExtra("limit", 20)
                        CallLogApi.listMissed(context, limit)
                    }
                    "stats" -> CallLogApi.getStats(context)
                    "duration" -> CallLogApi.getDuration(context)
                    "by-number" -> {
                        val number = intent.getStringExtra("number") ?: ""
                        CallLogApi.getByNumber(context, number)
                    }
                    "clear" -> CallLogApi.clearAll(context)
                    else -> CallLogApi.listCalls(context)
                }
                writeResult(context, "calllog_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Screen Recorder
            // ============================================================
            ACTION_SCREEN_RECORD -> {
                val action = intent.getStringExtra("action") ?: "status"
                val result = when (action) {
                    "start" -> {
                        val duration = intent.getIntExtra("duration", 0)
                        val output = intent.getStringExtra("output") ?: ""
                        val config = ScreenRecorderApi.RecordingConfig(durationMs = duration.toLong(), outputPath = output)
                        ScreenRecorderApi.startRecording(context, config)
                    }
                    "stop" -> ScreenRecorderApi.stopRecording()
                    "status" -> ScreenRecorderApi.getStatus()
                    else -> ScreenRecorderApi.getStatus()
                }
                writeResult(context, "record_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Macro System
            // ============================================================
            ACTION_MACRO -> {
                val action = intent.getStringExtra("action") ?: "list"
                val result = when (action) {
                    "record" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        MacroSystem.record(context, name)
                    }
                    "stop" -> MacroSystem.stopRecording(context)
                    "play" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        MacroSystem.play(context, name)
                    }
                    "list" -> MacroSystem.list(context)
                    "show" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        MacroSystem.show(context, name)
                    }
                    "delete" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        MacroSystem.delete(context, name)
                    }
                    "export" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        MacroSystem.export(context, name)
                    }
                    "import" -> {
                        val path = intent.getStringExtra("path") ?: ""
                        MacroSystem.import(context, path)
                    }
                    "loop" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        val count = intent.getIntExtra("count", 1)
                        MacroSystem.loop(context, name, count)
                    }
                    else -> MacroSystem.list(context)
                }
                writeResult(context, "macro_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Intent Bridge
            // ============================================================
            ACTION_INTENT -> {
                val action = intent.getStringExtra("action") ?: "help"
                val result = when (action) {
                    "send" -> {
                        val intentAction = intent.getStringExtra("intent_action") ?: ""
                        val extras = intent.getStringExtra("extras") ?: ""
                        IntentBridgeApi.sendBroadcast(context, intentAction, emptyMap())
                    }
                    "start" -> {
                        val intentAction = intent.getStringExtra("intent_action") ?: ""
                        val uri = intent.getStringExtra("uri")
                        IntentBridgeApi.startActivity(context, intentAction, uri)
                    }
                    "service" -> {
                        val intentAction = intent.getStringExtra("intent_action") ?: ""
                        IntentBridgeApi.startService(context, intentAction)
                    }
                    "uri" -> {
                        val uri = intent.getStringExtra("uri") ?: ""
                        IntentBridgeApi.openUri(context, uri)
                    }
                    "settings" -> {
                        val panel = intent.getStringExtra("panel") ?: "main"
                        IntentBridgeApi.openSettings(context, panel)
                    }
                    "dial" -> {
                        val number = intent.getStringExtra("number") ?: ""
                        IntentBridgeApi.dial(context, number)
                    }
                    "sms" -> {
                        val number = intent.getStringExtra("number") ?: ""
                        val text = intent.getStringExtra("text") ?: ""
                        IntentBridgeApi.sms(context, number, text)
                    }
                    "email" -> {
                        val to = intent.getStringExtra("to") ?: ""
                        val subject = intent.getStringExtra("subject") ?: ""
                        IntentBridgeApi.email(context, to, subject)
                    }
                    "market" -> {
                        val pkg = intent.getStringExtra("package") ?: ""
                        IntentBridgeApi.market(context, pkg)
                    }
                    "share" -> {
                        val text = intent.getStringExtra("text") ?: ""
                        IntentBridgeApi.share(context, text)
                    }
                    "web" -> {
                        val url = intent.getStringExtra("url") ?: ""
                        IntentBridgeApi.web(context, url)
                    }
                    "list" -> IntentBridgeApi.listHistory()
                    else -> "Usage: intent <send|start|service|uri|settings|dial|sms|email|market|share|web|list>"
                }
                writeResult(context, "intent_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Encryption
            // ============================================================
            ACTION_ENCRYPTION -> {
                val action = intent.getStringExtra("action") ?: "help"
                val args = mutableListOf(action)
                when (action) {
                    "encrypt" -> {
                        intent.getStringExtra("input")?.let { args.add(it) }
                        intent.getStringExtra("output")?.let { args.add(it) }
                        intent.getStringExtra("password")?.let { args.add(it) }
                    }
                    "decrypt" -> {
                        intent.getStringExtra("input")?.let { args.add(it) }
                        intent.getStringExtra("output")?.let { args.add(it) }
                        intent.getStringExtra("password")?.let { args.add(it) }
                    }
                    "hash" -> {
                        intent.getStringExtra("file")?.let { args.add(it) }
                        intent.getStringExtra("algo")?.let { args.add(it) }
                    }
                    "hash-text" -> {
                        intent.getStringExtra("text")?.let { args.add(it) }
                        intent.getStringExtra("algo")?.let { args.add(it) }
                    }
                    "aes-enc" -> {
                        intent.getStringExtra("text")?.let { args.add(it) }
                        intent.getStringExtra("password")?.let { args.add(it) }
                    }
                    "aes-dec" -> {
                        intent.getStringExtra("ciphertext")?.let { args.add(it) }
                        intent.getStringExtra("password")?.let { args.add(it) }
                    }
                    "base64-encode" -> {
                        intent.getStringExtra("input")?.let { args.add(it) }
                    }
                    "base64-decode" -> {
                        intent.getStringExtra("input")?.let { args.add(it) }
                    }
                    "gen-key" -> {
                        intent.getStringExtra("algo")?.let { args.add(it) }
                        intent.getIntExtra("bits", 0).takeIf { it > 0 }?.toString()?.let { args.add(it) }
                    }
                }
                val result = EncryptionApi.execute(context, args)
                writeResult(context, "crypt_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Network Tools
            // ============================================================
            ACTION_NET_TOOLS -> {
                val action = intent.getStringExtra("action") ?: "help"
                val args = mutableListOf(action)
                when (action) {
                    "ping" -> {
                        intent.getStringExtra("host")?.let { args.add(it) }
                        intent.getIntExtra("count", 0).takeIf { it > 0 }?.toString()?.let { args.add(it) }
                        intent.getIntExtra("timeout", 0).takeIf { it > 0 }?.toString()?.let { args.add(it) }
                    }
                    "traceroute" -> {
                        intent.getStringExtra("host")?.let { args.add(it) }
                        intent.getIntExtra("max_hops", 0).takeIf { it > 0 }?.toString()?.let { args.add(it) }
                    }
                    "dns" -> {
                        intent.getStringExtra("host")?.let { args.add(it) }
                        intent.getStringExtra("server")?.let { args.add(it) }
                    }
                    "dns-reverse" -> {
                        intent.getStringExtra("ip")?.let { args.add(it) }
                    }
                    "whois" -> {
                        intent.getStringExtra("domain")?.let { args.add(it) }
                    }
                    "port-scan" -> {
                        intent.getStringExtra("host")?.let { args.add(it) }
                        val startPort = intent.getIntExtra("start_port", 1)
                        val endPort = intent.getIntExtra("end_port", 1024)
                        args.add("$startPort-$endPort")
                    }
                    "curl" -> {
                        intent.getStringExtra("url")?.let { args.add(it) }
                        intent.getStringExtra("method")?.let { args.add(it) }
                        intent.getStringExtra("headers")?.let { args.add(it) }
                    }
                    "wget" -> {
                        intent.getStringExtra("url")?.let { args.add(it) }
                        intent.getStringExtra("output")?.let { args.add(it) }
                    }
                    "ssl-info" -> {
                        intent.getStringExtra("host")?.let { args.add(it) }
                        intent.getIntExtra("port", 0).takeIf { it > 0 }?.toString()?.let { args.add(it) }
                    }
                    "subnet" -> {
                        intent.getStringExtra("cidr")?.let { args.add(it) }
                    }
                }
                val result = NetworkToolsApi.execute(context, args)
                writeResult(context, "net_result.txt", result)
            }

            // ============================================================
            // POWER FEATURES: Profile System
            // ============================================================
            ACTION_PROFILE -> {
                val action = intent.getStringExtra("action") ?: "list"
                val args = mutableListOf(action)
                when (action) {
                    "list" -> {}
                    "create" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "delete" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "switch" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "current" -> {}
                    "show" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "env" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "set" -> {
                        intent.getStringExtra("name")?.let { args.add(it) }
                        intent.getStringExtra("key")?.let { args.add(it) }
                        intent.getStringExtra("value")?.let { args.add(it) }
                    }
                    "clone" -> {
                        intent.getStringExtra("src")?.let { args.add(it) }
                        intent.getStringExtra("dst")?.let { args.add(it) }
                    }
                    "rename" -> {
                        intent.getStringExtra("old")?.let { args.add(it) }
                        intent.getStringExtra("new")?.let { args.add(it) }
                    }
                    "export" -> intent.getStringExtra("name")?.let { args.add(it) }
                    "import" -> intent.getStringExtra("path")?.let { args.add(it) }
                    "reset" -> intent.getStringExtra("name")?.let { args.add(it) }
                }
                val result = ProfileManager.execute(context, args)
                writeResult(context, "profile_result.txt", result)
            }

            else -> {
                Log.w(TAG, "Unknown API action: ${intent.action}")
            }
        }
    }

    // ================================================================
    // Private helper methods
    // ================================================================

    private fun getWifiInfo(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        val ssid = info?.ssid?.removeSurrounding("\"") ?: "Not connected"
        val ip = info?.ipAddress?.let {
            String.format("%d.%d.%d.%d", it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff)
        } ?: "N/A"
        return "WiFi: ${if (wifiManager.isWifiEnabled) "ON" else "OFF"} | SSID: $ssid | IP: $ip"
    }

    private fun startDownload(context: Context, url: String, filename: String?) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                setTitle(filename ?: url.substringAfterLast("/"))
                setDescription("TermX Download")
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (filename != null) {
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename)
                }
            }
            downloadManager.enqueue(request)
            TermXApi.showToast(context, "Download started: ${filename ?: url}")
        } catch (e: Exception) {
            TermXApi.showToast(context, "Download failed: ${e.message}")
        }
    }

    private fun getVolumeInfo(context: Context, stream: String, level: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val streamType = when (stream) {
            "music" -> android.media.AudioManager.STREAM_MUSIC
            "ring" -> android.media.AudioManager.STREAM_RING
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            "system" -> android.media.AudioManager.STREAM_SYSTEM
            "voice_call" -> android.media.AudioManager.STREAM_VOICE_CALL
            else -> android.media.AudioManager.STREAM_MUSIC
        }

        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val currentVolume = audioManager.getStreamVolume(streamType)

        if (level >= 0) {
            audioManager.setStreamVolume(streamType, level.coerceIn(0, maxVolume), 0)
            return "Volume set: $level/$maxVolume ($stream)"
        }

        return "Volume: $currentVolume/$maxVolume ($stream)"
    }

    private fun speakTTS(context: Context, text: String, language: String) {
        try {
            val tts = android.speech.tts.TextToSpeech(context.applicationContext, null)
            tts.language = java.util.Locale(language)
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "termx_tts")
            TermXApi.showToast(context, "Speaking: $text")
        } catch (e: Exception) {
            TermXApi.showToast(context, "TTS failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifiEnabled(context: Context, enable: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled = enable
            TermXApi.showToast(context, "WiFi ${if (enable) "enabled" else "disabled"}")
        } catch (e: Exception) {
            TermXApi.showToast(context, "Cannot change WiFi: ${e.message}")
        }
    }

    private fun getWifiScanResults(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val sb = StringBuilder("=== WiFi Scan Results ===\n")
        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults
        for (result in results.sortedByDescending { it.level }) {
            sb.appendLine("  ${result.SSID} | BSSID: ${result.BSSID} | Level: ${result.level}dBm | Freq: ${result.frequency}MHz")
        }
        return sb.toString()
    }

    private fun getFullHelpText(): String {
        return """
TermX API Commands — Complete Reference:
=========================================

Core:
  open-url <url>           Open URL in browser
  open-file <path>         Open file with appropriate app
  share-text <text>        Share text via Android
  clipboard-get            Get clipboard contents
  clipboard-set <text>     Set clipboard contents
  toast <message>          Show toast notification
  vibrate [ms]             Vibrate device
  notify <title> <msg>     Show notification
  dialog <title> <msg>     Show dialog
  setup-storage            Setup storage symlinks
  storage-info             Storage information
  battery-info             Battery information

Location & Sensors:
  location [provider]      Get GPS location
  sensor-info              List all sensors
  sensor-read <type>       Read sensor value

Telephony & Media:
  telephony-info           Device/phone information
  media-player play|pause|resume|stop  Media playback
  download <url> [name]    Download file
  volume [stream] [level]  Get/set volume
  tts <text> [lang]        Text-to-speech

WiFi:
  wifi-info                WiFi connection info
  wifi-enable [true|false] Enable/disable WiFi
  wifi-scan                Scan WiFi networks

X11 Display:
  termx-display start|stop|status|list|resize|screenshot
  termx-vnc start|stop|status|clients

Priority 3 APIs:
  termx-camera photo|video|info     Camera operations
  termx-sms send|inbox|sent|delete  SMS operations
  termx-fingerprint auth|available|enrolled  Biometric auth
  termx-contact list|get|search|add|delete    Contacts
  termx-stt start|stop|result      Speech-to-text
  termx-nfc read|write|available    NFC operations
  termx-battery info|health|power-save  Detailed battery
  termx-notification show|cancel|ongoing|progress  Notifications
  termx-file-picker open|save|dir   File picker
  termx-screenshot [path]          Screenshot
  termx-app install|uninstall|list|info|launch  App management

Power Features — SSH/SFTP:
  termx-ssh start [port]   Start SSH server (default 8022)
  termx-ssh stop           Stop SSH server
  termx-ssh status         Server status
  termx-ssh keygen [type]  Generate host key
  termx-ssh keys           List authorized keys
  termx-ssh sessions       List active sessions

Power Features — Cron:
  termx-cron add|remove|list|enable|disable|run|logs|start|stop|status

Power Features — Tunnel:
  termx-tunnel forward|reverse|socks|reverse-shell|udp|list|stop|stats

Power Features — Bluetooth:
  termx-bt enable|disable|scan|paired|connect|disconnect|send|info

Power Features — USB Serial:
  termx-usb list|open|close|read|write|config|monitor|info

Power Features — Web Server:
  termx-http start|stop|status|auth|logs|dir|port

Power Features — Process Monitor:
  termx-ps list|top|info|kill|tree|threads|mem|cpu|find

Power Features — Flashlight:
  termx-flash on|off|toggle|blink|sos|strobe|status

Power Features — Call Log:
  termx-calllog list|incoming|outgoing|missed|stats|duration|clear

Power Features — Screen Recorder:
  termx-record start|stop|status

Power Features — Macro:
  termx-macro record|stop|play|list|show|delete|export|import|loop

Power Features — Intent Bridge:
  termx-intent send|start|service|uri|settings|dial|sms|email|market|share|web

Power Features — Encryption:
  termx-crypt encrypt|decrypt|hash|hash-text|aes-enc|aes-dec|base64-encode|base64-decode|gen-key|list-algos

Power Features — Network Tools:
  termx-net ping|traceroute|dns|whois|port-scan|ip|public-ip|interfaces|arp|connections|curl|wget|ssl-info|subnet|speed-test

Power Features — Profiles:
  termx-profile list|create|delete|switch|current|show|env|set|clone|rename|export|import|reset

Shell usage:
  am broadcast -a com.termx.app.api.<ACTION> [--es key value]
""".trimIndent()
    }
}
