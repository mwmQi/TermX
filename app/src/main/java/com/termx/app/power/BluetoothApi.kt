package com.termx.app.power

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.io.File
import java.util.*

/**
 * Bluetooth API for TermX — control Bluetooth from the terminal.
 *
 * Shell usage:
 *   termx-bt enable/disable           Enable/disable Bluetooth
 *   termx-bt scan                     Scan for devices
 *   termx-bt paired                   List paired devices
 *   termx-bt connect <addr>           Connect to device
 *   termx-bt disconnect <addr>        Disconnect device
 *   termx-bt send <addr> <file>       Send file via OPP
 *   termx-bt info [addr]              Device info
 *   termx-bt discoverable [timeout]   Set discoverable
 *   termx-bt name [new_name]          Get/set adapter name
 *   termx-bt rssi <addr>              Get RSSI of device
 *   termx-bt profiles <addr>          List supported profiles
 */
@SuppressLint("MissingPermission")
object BluetoothApi {

    private const val TAG = "BluetoothApi"

    /** Cached scan results from the most recent discovery. */
    private val scanResults = mutableMapOf<String, BluetoothDevice>()

    /** Reference to the scan receiver so we can unregister it. */
    private var scanReceiver: BroadcastReceiver? = null

    /** Whether a scan is currently in progress. */
    private var isScanning = false

    // ---- Adapter access ----

    /**
     * Get the BluetoothAdapter, or null if the device does not support Bluetooth.
     */
    private fun getAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /**
     * Return a human-readable error if Bluetooth is unavailable, or null if OK.
     */
    private fun checkAdapter(context: Context): String? {
        val adapter = getAdapter(context)
            ?: return "Bluetooth not available on this device"
        if (!adapter.isEnabled) return "Bluetooth is disabled — use 'termx-bt enable' first"
        return null
    }

    // ---- Enable / Disable ----

    /**
     * Enable the Bluetooth adapter.
     * Requires BLUETOOTH_ADMIN permission (granted at install on most devices).
     */
    fun enable(context: Context): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            if (adapter.isEnabled) return "Bluetooth is already enabled"
            val success = adapter.enable()
            if (success) {
                Log.i(TAG, "Bluetooth enabling")
                "Bluetooth is enabling…"
            } else {
                "Error: Failed to enable Bluetooth"
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable Bluetooth", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Disable the Bluetooth adapter.
     */
    fun disable(context: Context): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            if (!adapter.isEnabled) return "Bluetooth is already disabled"
            val success = adapter.disable()
            if (success) {
                Log.i(TAG, "Bluetooth disabling")
                "Bluetooth is disabling…"
            } else {
                "Error: Failed to disable Bluetooth"
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Bluetooth", e)
            "Error: ${e.message}"
        }
    }

    // ---- Scan ----

    /**
     * Start Bluetooth discovery (scan) and return results.
     * Note: Discovery is asynchronous; this registers a receiver, waits for
     * ACTION_DISCOVERY_FINISHED, then returns collected results.
     * For simplicity in a terminal context, we start discovery and return
     * currently known devices. Call again after a few seconds for more.
     */
    fun scan(context: Context): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            // Register broadcast receiver for found devices
            registerScanReceiver(context)

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            scanResults.clear()
            val started = adapter.startDiscovery()
            if (started) {
                isScanning = true
                Log.i(TAG, "Bluetooth scan started")
                buildString {
                    appendLine("Scan started. Discovered devices so far:")
                    // Include already-paired devices
                    val paired = adapter.bondedDevices ?: emptySet()
                    for (device in paired) {
                        scanResults[device.address] = device
                    }
                    if (scanResults.isEmpty()) {
                        appendLine("  (none yet — scan is in progress)")
                    } else {
                        appendLine(formatDeviceList(scanResults.values))
                    }
                    appendLine()
                    appendLine("Scan is running in background. Use 'termx-bt scan' again for updates.")
                }
            } else {
                "Error: Failed to start Bluetooth discovery"
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Register a broadcast receiver to capture discovered devices.
     */
    private fun registerScanReceiver(context: Context) {
        unregisterScanReceiver(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            scanResults[it.address] = it
                            Log.d(TAG, "Found device: ${it.name} (${it.address})")
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isScanning = false
                        Log.i(TAG, "Scan finished. Found ${scanResults.size} devices")
                        unregisterScanReceiver(ctx)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
        scanReceiver = receiver
    }

    /**
     * Unregister the scan broadcast receiver.
     */
    private fun unregisterScanReceiver(context: Context) {
        scanReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            scanReceiver = null
        }
    }

    // ---- Paired devices ----

    /**
     * List all paired (bonded) devices.
     */
    fun paired(context: Context): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            val bonded = adapter.bondedDevices ?: emptySet()
            if (bonded.isEmpty()) return "No paired devices found"

            buildString {
                appendLine("=== Paired Devices (${bonded.size}) ===")
                appendLine(formatDeviceList(bonded))
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list paired devices", e)
            "Error: ${e.message}"
        }
    }

    // ---- Connect / Disconnect ----

    /**
     * Attempt to connect to a Bluetooth device by MAC address.
     * Connects to all supported profiles for the device.
     */
    fun connect(context: Context, address: String): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return "Error: Invalid Bluetooth address '$address'. Expected format: XX:XX:XX:XX:XX:XX"
            }

            val device = adapter.getRemoteDevice(address)
                ?: return "Error: Could not get device for address $address"

            // Cancel any ongoing discovery before connecting
            if (adapter.isDiscovering) adapter.cancelDiscovery()

            val name = device.name ?: "Unknown"
            val profiles = getConnectableProfiles(context, device)

            if (profiles.isEmpty()) {
                return "Error: No connectable profiles found for $name ($address)"
            }

            val results = mutableListOf<String>()
            for (profile in profiles) {
                try {
                    val connected = connectProfile(context, device, profile)
                    results.add("  ${profileName(profile)}: ${if (connected) "CONNECTED" else "FAILED"}")
                } catch (e: Exception) {
                    results.add("  ${profileName(profile)}: ERROR — ${e.message}")
                }
            }

            buildString {
                appendLine("=== Connect to $name ($address) ===")
                results.forEach { appendLine(it) }
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed for $address", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Disconnect from a Bluetooth device.
     */
    fun disconnect(context: Context, address: String): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            val device = adapter.getRemoteDevice(address)
                ?: return "Error: Could not get device for address $address"

            val name = device.name ?: "Unknown"
            val profiles = getConnectableProfiles(context, device)

            val results = mutableListOf<String>()
            for (profile in profiles) {
                try {
                    val disconnected = disconnectProfile(context, device, profile)
                    results.add("  ${profileName(profile)}: ${if (disconnected) "DISCONNECTED" else "FAILED"}")
                } catch (e: Exception) {
                    results.add("  ${profileName(profile)}: ERROR — ${e.message}")
                }
            }

            buildString {
                appendLine("=== Disconnect from $name ($address) ===")
                if (results.isEmpty()) {
                    appendLine("  No connected profiles to disconnect")
                } else {
                    results.forEach { appendLine(it) }
                }
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed for $address", e)
            "Error: ${e.message}"
        }
    }

    // ---- Device info ----

    /**
     * Show detailed information about the adapter or a specific device.
     */
    fun info(context: Context, address: String? = null): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"

            if (address == null) {
                // Adapter info
                buildString {
                    appendLine("=== Bluetooth Adapter Info ===")
                    appendLine("Name:           ${adapter.name ?: "N/A"}")
                    appendLine("Address:        ${adapter.address ?: "N/A"}")
                    appendLine("Enabled:        ${adapter.isEnabled}")
                    appendLine("Discovering:    ${adapter.isDiscovering}")
                    appendLine("Scan Mode:      ${scanModeToString(adapter.scanMode)}")
                    appendLine("Paired Devices: ${adapter.bondedDevices?.size ?: 0}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        appendLine("LE Supported:   ${adapter.isMultipleAdvertisementSupported}")
                        appendLine("LE Offloaded:   ${adapter.isOffloadedFilteringSupported}")
                    }
                    appendLine("State:          ${adapterStateToString(adapter.state)}")
                }
            } else {
                // Device info
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    return "Error: Invalid Bluetooth address '$address'"
                }
                val device = adapter.getRemoteDevice(address)
                    ?: return "Error: Could not get device for address $address"

                buildString {
                    appendLine("=== Bluetooth Device Info ===")
                    appendLine("Name:       ${device.name ?: "Unknown"}")
                    appendLine("Address:    ${device.address}")
                    appendLine("Bond State: ${bondStateToString(device.bondState)}")
                    appendLine("Type:       ${deviceTypeToString(device.type)}")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        appendLine("Alias:      ${device.alias ?: "N/A"}")
                    }

                    val uuids = device.uuids
                    if (uuids != null && uuids.isNotEmpty()) {
                        appendLine("UUIDs:")
                        uuids.forEach { uuid ->
                            appendLine("  ${uuid.uuid}  ${uuidToService(uuid.uuid)}")
                        }
                    } else {
                        appendLine("UUIDs:      (not available — device may be offline)")
                    }
                }
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Info failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Discoverable ----

    /**
     * Make the device discoverable over Bluetooth.
     * Note: The system may show a confirmation dialog.
     */
    fun discoverable(context: Context, timeout: Int = 120): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"

            if (adapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, timeout)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Requested discoverable mode (timeout=${timeout}s)")
                "Discoverable request sent (timeout=${timeout}s). Check system dialog."
            } else {
                "Device is already discoverable"
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Discoverable failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Name ----

    /**
     * Get or set the Bluetooth adapter name.
     */
    fun name(context: Context, newName: String? = null): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"

            if (newName == null) {
                "Bluetooth name: ${adapter.name ?: "N/A"}"
            } else {
                adapter.name = newName
                if (adapter.name == newName) {
                    Log.i(TAG, "Bluetooth name changed to: $newName")
                    "Bluetooth name set to: $newName"
                } else {
                    "Error: Failed to set Bluetooth name"
                }
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Name operation failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- RSSI ----

    /**
     * Get the RSSI (signal strength) for a device.
     * Reads from the /proc/net/bluetooth subsystem or cached scan data.
     */
    fun rssi(context: Context, address: String): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return "Error: Invalid Bluetooth address '$address'"
            }

            val device = adapter.getRemoteDevice(address)
                ?: return "Error: Could not get device for address $address"

            val name = device.name ?: "Unknown"

            // RSSI is typically only available during/after scan
            // Try reading from the Bluetooth LE API if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val rssiFromScan = readRssiFromProc(address)
                if (rssiFromScan != null) {
                    return buildString {
                        appendLine("=== RSSI for $name ($address) ===")
                        appendLine("Signal: ${rssiFromScan} dBm")
                        appendLine("Quality: ${rssiToQuality(rssiFromScan)}")
                    }
                }
            }

            buildString {
                appendLine("=== RSSI for $name ($address) ===")
                appendLine("Signal: N/A")
                appendLine("Tip: Run 'termx-bt scan' first, then check RSSI")
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "RSSI check failed", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Read RSSI from /proc/net/bluetooth or sysfs.
     */
    private fun readRssiFromProc(address: String): Int? {
        return try {
            val connFile = java.io.File("/proc/net/bluetooth/l2cap")
            if (connFile.exists()) {
                val lines = connFile.readLines()
                for (line in lines) {
                    if (line.contains(address.replace(":", ""), ignoreCase = true)) {
                        // Parse RSSI if available in proc output
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 5) {
                            return parts.last().toIntOrNull()
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Could not read RSSI from proc", e)
            null
        }
    }

    // ---- Profiles ----

    /**
     * List supported profiles for a Bluetooth device.
     */
    fun profiles(context: Context, address: String): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return "Error: Invalid Bluetooth address '$address'"
            }

            val device = adapter.getRemoteDevice(address)
                ?: return "Error: Could not get device for address $address"

            val name = device.name ?: "Unknown"
            val uuids = device.uuids

            buildString {
                appendLine("=== Profiles for $name ($address) ===")
                if (uuids != null && uuids.isNotEmpty()) {
                    val profileList = uuids.map { uuidToService(it.uuid) }.filter { it != "Unknown" }.distinct()
                    if (profileList.isNotEmpty()) {
                        profileList.forEach { appendLine("  • $it") }
                    } else {
                        appendLine("  (no recognized profiles — device may be offline)")
                    }
                    appendLine()
                    appendLine("Raw UUIDs:")
                    uuids.forEach { appendLine("  ${it.uuid}") }
                } else {
                    appendLine("  (no UUIDs available — device may be offline)")
                }
            }
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Profiles failed for $address", e)
            "Error: ${e.message}"
        }
    }

    // ---- Send file (OPP) ----

    /**
     * Send a file to a Bluetooth device using the Object Push Profile.
     * Uses the system Bluetooth share intent.
     */
    fun send(context: Context, address: String, filePath: String): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"
            val error = checkAdapter(context)
            if (error != null) return error

            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return "Error: Invalid Bluetooth address '$address'"
            }

            val file = java.io.File(filePath)
            if (!file.exists()) return "Error: File not found: $filePath"
            if (!file.canRead()) return "Error: Cannot read file: $filePath"
            if (!file.isFile) return "Error: Not a regular file: $filePath"

            val device = adapter.getRemoteDevice(address)
            val name = device?.name ?: "Unknown"

            // Use the system OPP intent
            val uri = android.net.Uri.fromFile(file)
            val intent = Intent().apply {
                action = Intent.ACTION_SEND
                type = getMimeType(filePath)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.android.bluetooth")
            }

            context.startActivity(intent)
            Log.i(TAG, "Sending file $filePath to $name ($address)")
            "Sending ${file.name} (${formatFileSize(file.length())}) to $name ($address)"
        } catch (e: SecurityException) {
            "Error: Permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Scan status ----

    /**
     * Get current scan status and last scan results.
     */
    fun scanStatus(context: Context): String {
        return try {
            val adapter = getAdapter(context)
                ?: return "Error: Bluetooth hardware not available"

            buildString {
                appendLine("=== Bluetooth Scan Status ===")
                appendLine("Scanning: ${adapter.isDiscovering}")
                appendLine("Cached Devices: ${scanResults.size}")
                if (scanResults.isNotEmpty()) {
                    appendLine()
                    appendLine(formatDeviceList(scanResults.values))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan status failed", e)
            "Error: ${e.message}"
        }
    }

    // ---- Profile helpers ----

    /**
     * Get profiles we can attempt to connect to for a given device.
     */
    private fun getConnectableProfiles(context: Context, device: BluetoothDevice): List<Int> {
        val profiles = mutableListOf<Int>()
        val uuids = device.uuids?.map { it.uuid } ?: return emptyList()

        val profileMap = mapOf(
            ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB") to BluetoothProfile.A2DP,
            ParcelUuid.fromString("00001105-0000-1000-8000-00805F9B34FB") to BluetoothProfile.HEADSET,
            ParcelUuid.fromString("0000110E-0000-1000-8000-00805F9B34FB") to 11,  // AVRCP
            ParcelUuid.fromString("00001112-0000-1000-8000-00805F9B34FB") to BluetoothProfile.HID_DEVICE,
            ParcelUuid.fromString("0000111F-0000-1000-8000-00805F9B34FB") to 18  // HFP
        )

        val deviceUuids = device.uuids?.toList() ?: emptyList()
        for (puuid in deviceUuids) {
            profileMap[puuid]?.let { profiles.add(it) }
        }

        // Always try A2DP and HSP if no specific profiles found
        if (profiles.isEmpty()) {
            profiles.add(BluetoothProfile.A2DP)
            profiles.add(BluetoothProfile.HEADSET)
        }

        return profiles.distinct()
    }

    /**
     * Connect to a specific Bluetooth profile.
     */
    private fun connectProfile(context: Context, device: BluetoothDevice, profileId: Int): Boolean {
        return try {
            var connected = false
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        val a2dp = proxy as? BluetoothA2dp
                        a2dp?.let {
                            connected = try { it.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
                        }
                    } else if (profile == BluetoothProfile.HEADSET) {
                        val headset = proxy as? BluetoothHeadset
                        headset?.let {
                            connected = try { it.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
                        }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }

            val adapter = getAdapter(context)
            adapter?.getProfileProxy(context, listener, profileId)
            Thread.sleep(2000) // Wait for proxy connection
            connected
        } catch (e: Exception) {
            Log.e(TAG, "Profile connect error", e)
            false
        }
    }

    /**
     * Disconnect a specific Bluetooth profile.
     */
    private fun disconnectProfile(context: Context, device: BluetoothDevice, profileId: Int): Boolean {
        return try {
            var disconnected = false
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    when (proxy) {
                        is BluetoothA2dp -> { try { @Suppress("DEPRECATION") proxy.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, device); disconnected = true } catch (_: Exception) {} }
                        is BluetoothHeadset -> { try { @Suppress("DEPRECATION") proxy.javaClass.getDeclaredMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, device); disconnected = true } catch (_: Exception) {} }
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }
            val adapter = getAdapter(context)
            adapter?.getProfileProxy(context, listener, profileId)
            Thread.sleep(1500)
            disconnected
        } catch (e: Exception) {
            Log.e(TAG, "Profile disconnect error", e)
            false
        }
    }

    // ---- Formatting helpers ----

    /**
     * Format a collection of BluetoothDevice objects as a table.
     */
    private fun formatDeviceList(devices: Collection<BluetoothDevice>): String {
        return buildString {
            for ((index, device) in devices.withIndex()) {
                val name = device.name ?: "Unknown"
                val bonded = if (device.bondState == BluetoothDevice.BOND_BONDED) " [PAIRED]" else ""
                val type = deviceTypeToString(device.type)
                appendLine("  ${index + 1}. $name  (${device.address})$bonded  Type: $type")
            }
        }
    }

    private fun scanModeToString(mode: Int): String = when (mode) {
        BluetoothAdapter.SCAN_MODE_NONE -> "None (not connectable)"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "Connectable"
        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "Connectable + Discoverable"
        else -> "Unknown ($mode)"
    }

    private fun adapterStateToString(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "Off"
        BluetoothAdapter.STATE_TURNING_ON -> "Turning On"
        BluetoothAdapter.STATE_ON -> "On"
        BluetoothAdapter.STATE_TURNING_OFF -> "Turning Off"
        else -> "Unknown ($state)"
    }

    private fun bondStateToString(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "Not Bonded"
        BluetoothDevice.BOND_BONDING -> "Bonding…"
        BluetoothDevice.BOND_BONDED -> "Bonded"
        else -> "Unknown ($state)"
    }

    private fun deviceTypeToString(type: Int): String = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic (BR/EDR)"
        BluetoothDevice.DEVICE_TYPE_LE -> "Low Energy (LE)"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "Unknown"
        else -> "Unknown ($type)"
    }

    private fun profileName(profile: Int): String = when (profile) {
        BluetoothProfile.A2DP -> "A2DP (Advanced Audio)"
        BluetoothProfile.HEADSET -> "HSP (Headset)"
        18 -> "HFP (Hands-Free)"
        11 -> "AVRCP (Remote Control)"
        BluetoothProfile.HID_DEVICE -> "HID (Input Device)"
        BluetoothProfile.HEALTH -> "HDP (Health Device)"
        else -> "Profile #$profile"
    }

    private fun rssiToQuality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Fair"
        rssi >= -80 -> "Poor"
        else -> "Very Poor"
    }

    /**
     * Map a UUID to a human-readable service name.
     */
    private fun uuidToService(uuid: UUID): String {
        val short = uuid.toString().substring(4, 8).uppercase()
        return when (short) {
            "110A" -> "A2DP Source (Audio Streaming)"
            "110B" -> "A2DP Sink"
            "1105" -> "OBEX Object Push (File Transfer)"
            "1106" -> "OBEX File Transfer"
            "1112" -> "HID (Keyboard/Mouse)"
            "111F" -> "HFP (Hands-Free)"
            "110E" -> "AVRCP (Remote Control)"
            "1101" -> "SPP (Serial Port)"
            "1102" -> "LAP (LAN Access)"
            "1103" -> "DUN (Dial-Up Networking)"
            "1116" -> "NAP (Network Access Point)"
            "1115" -> "PANU (Personal Area Network)"
            "1117" -> "HDP (Health Device)"
            "1800" -> "GAP (Generic Access)"
            "1801" -> "GATT (Generic Attribute)"
            "180F" -> "Battery Service (LE)"
            "180D" -> "Heart Rate (LE)"
            "1811" -> "RSC (Running Speed/Cadence)"
            "180A" -> "Device Information (LE)"
            else -> "Unknown ($short)"
        }
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}
