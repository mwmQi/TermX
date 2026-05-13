package com.termx.app.power

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * USB Serial API for TermX — communicate with USB serial devices.
 *
 * Supports FTDI, CH34x, CP210x, PL2303, and CDC/ACM devices.
 *
 * Shell usage:
 *   termx-usb list                    List USB serial devices
 *   termx-usb open <device> [baud]    Open serial device (default 9600)
 *   termx-usb close <device>          Close serial device
 *   termx-usb read <device> [bytes]   Read from serial device
 *   termx-usb write <device> <data>   Write to serial device
 *   termx-usb config <device> [opts]  Configure baud, parity, stop bits
 *   termx-usb monitor <device>        Monitor serial output continuously
 *   termx-usb info <device>           Device information
 *   termx-usb baud <device> <rate>    Set baud rate
 */
object UsbSerialApi {

    private const val TAG = "UsbSerialApi"
    private const val DEFAULT_BAUD_RATE = 9600
    private const val DEFAULT_DATA_BITS = 8
    private const val DEFAULT_STOP_BITS = 1
    private const val DEFAULT_PARITY = 0  // None
    private const val READ_TIMEOUT_MS = 3000
    private const val BUFFER_SIZE = 4096

    /** Known USB serial device vendor/product IDs for auto-detection. */
    private val KNOWN_SERIAL_DEVICES = mapOf(
        // FTDI FT232RL
        Pair(0x0403, 0x6001) to "FTDI FT232RL",
        Pair(0x0403, 0x6014) to "FTDI FT232H",
        Pair(0x0403, 0x6015) to "FTDI FT231X",
        Pair(0x0403, 0x6045) to "FTDI FT230X",
        // CH340 / CH341
        Pair(0x1A86, 0x7523) to "CH340",
        Pair(0x1A86, 0x5523) to "CH341",
        // CP210x
        Pair(0x10C4, 0xEA60) to "CP2102",
        Pair(0x10C4, 0xEA70) to "CP2105",
        Pair(0x10C4, 0x80A9) to "CP2108",
        // PL2303
        Pair(0x067B, 0x2303) to "PL2303",
        Pair(0x067B, 0x23A3) to "PL2303TA",
        Pair(0x067B, 0x23B3) to "PL2303TB",
        // CDC/ACM (various)
        Pair(0x2341, 0x0043) to "Arduino Uno (CDC)",
        Pair(0x2341, 0x0010) to "Arduino Mega (CDC)",
        Pair(0x2341, 0x8036) to "Arduino Leonardo (CDC)",
        Pair(0x2A03, 0x0043) to "Arduino Uno Clone (CDC)",
        Pair(0x0483, 0x5740) to "STM32 CDC",
        Pair(0x1B4F, 0x9207) to "SparkFun SAMD21 (CDC)",
        // ESP32 / ESP8266
        Pair(0x10C4, 0xEA60) to "ESP32 (CP2102)",
        Pair(0x1A86, 0x7523) to "ESP8266 (CH340)",
        // Microchip
        Pair(0x04D8, 0x000A) to "Microchip CDC"
    )

    /** USB interface class for CDC/ACM devices. */
    private const val USB_CLASS_CDC = 0x02
    private const val USB_CLASS_CDC_DATA = 0x0A
    private const val USB_CLASS_VENDOR_SPEC = 0xFF

    /** Active serial connections indexed by device name. */
    private val connections = ConcurrentHashMap<String, SerialConnection>()

    /**
     * Represents an active serial connection with its configuration.
     */
    data class SerialConfig(
        var baudRate: Int = DEFAULT_BAUD_RATE,
        var dataBits: Int = DEFAULT_DATA_BITS,
        var stopBits: Int = DEFAULT_STOP_BITS,
        var parity: Int = DEFAULT_PARITY,
        var flowControl: Int = 0  // 0=None, 1=RTS/CTS, 2=XON/XOFF
    ) {
        fun parityString(): String = when (parity) {
            0 -> "None"
            1 -> "Odd"
            2 -> "Even"
            3 -> "Mark"
            4 -> "Space"
            else -> "Unknown($parity)"
        }

        fun flowControlString(): String = when (flowControl) {
            0 -> "None"
            1 -> "RTS/CTS"
            2 -> "XON/XOFF"
            else -> "Unknown($flowControl)"
        }

        override fun toString(): String = buildString {
            appendLine("  Baud Rate:   $baudRate")
            appendLine("  Data Bits:   $dataBits")
            appendLine("  Stop Bits:   $stopBits")
            appendLine("  Parity:      ${parityString()}")
            appendLine("  Flow Control:${flowControlString()}")
        }
    }

    /**
     * Holds state for an active serial connection.
     */
    data class SerialConnection(
        val deviceName: String,
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
        val interfaceNum: Int,
        val config: SerialConfig = SerialConfig(),
        var isOpen: Boolean = true,
        val openTime: Long = System.currentTimeMillis()
    )

    // ---- List devices ----

    /**
     * List all connected USB serial devices.
     */
    fun list(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList

            if (devices.isEmpty()) return "No USB devices found"

            val serialDevices = devices.values.filter { isSerialDevice(it) }

            if (serialDevices.isEmpty()) {
                return buildString {
                    appendLine("No USB serial devices found.")
                    appendLine("(${devices.size} USB device(s) connected, none are serial)")
                }
            }

            buildString {
                appendLine("=== USB Serial Devices (${serialDevices.size}) ===")
                appendLine()
                for ((index, device) in serialDevices.withIndex()) {
                    val name = getDeviceName(device)
                    val connected = connections.containsKey(device.deviceName)
                    appendLine("  ${index + 1}. $name")
                    appendLine("     Device:    ${device.deviceName}")
                    appendLine("     VID:PID:   ${"0x%04X".format(device.vendorId)}:${"0x%04X".format(device.productId)}")
                    appendLine("     Interface: ${device.interfaceCount}")
                    appendLine("     Status:    ${if (connected) "OPEN" else "Available"}")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list USB devices", e)
            "Error: ${e.message}"
        }
    }

    // ---- Open device ----

    /**
     * Open a USB serial device for communication.
     * @param deviceName The USB device name (from `list` command)
     * @param baudRate Baud rate (default 9600)
     */
    fun open(context: Context, deviceName: String, baudRate: Int = DEFAULT_BAUD_RATE): String {
        return try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            if (connections.containsKey(deviceName)) {
                return "Device $deviceName is already open"
            }

            val device = manager.deviceList[deviceName]
                ?: return "Error: Device '$deviceName' not found. Use 'termx-usb list' to see available devices."

            if (!isSerialDevice(device)) {
                return "Error: Device '$deviceName' does not appear to be a serial device"
            }

            if (!manager.hasPermission(device)) {
                // Request permission
                return "Error: USB permission not granted for $deviceName. " +
                    "Grant USB permission in system settings or reconnect the device."
            }

            // Find the correct interface and endpoints
            val serialInterface = findSerialInterface(device)
                ?: return "Error: Could not find serial interface on device $deviceName"

            val endpointIn = findEndpoint(serialInterface, UsbConstants.USB_DIR_IN)
            val endpointOut = findEndpoint(serialInterface, UsbConstants.USB_DIR_OUT)

            if (endpointIn == null || endpointOut == null) {
                return "Error: Could not find IN/OUT endpoints on device $deviceName"
            }

            // Open connection
            val connection = manager.openDevice(device)
                ?: return "Error: Failed to open USB connection to $deviceName"

            // Claim the interface
            if (!connection.claimInterface(serialInterface, true)) {
                connection.close()
                return "Error: Failed to claim USB interface on $deviceName"
            }

            val config = SerialConfig(baudRate = baudRate)
            val serialConn = SerialConnection(
                deviceName = deviceName,
                device = device,
                connection = connection,
                endpointIn = endpointIn,
                endpointOut = endpointOut,
                interfaceNum = serialInterface.id,
                config = config
            )

            // Configure the device (baud rate, etc.)
            val configured = configureDevice(serialConn)
            if (!configured) {
                Log.w(TAG, "Device configuration may not have fully succeeded for $deviceName")
            }

            connections[deviceName] = serialConn
            Log.i(TAG, "Opened $deviceName at ${baudRate} baud")

            buildString {
                appendLine("=== Serial Device Opened ===")
                appendLine("Device:  ${getDeviceName(device)}")
                appendLine("Name:    $deviceName")
                appendLine("Baud:    $baudRate")
                appendLine("Config:")
                append(config.toString())
            }
        } catch (e: SecurityException) {
            "Error: USB permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open $deviceName", e)
            "Error: ${e.message}"
        }
    }

    // ---- Close device ----

    /**
     * Close a USB serial device.
     */
    fun close(deviceName: String): String {
        return try {
            val conn = connections.remove(deviceName)
                ?: return "Error: Device '$deviceName' is not open"

            try {
                conn.connection.releaseInterface(conn.device.getInterface(conn.interfaceNum))
            } catch (_: Exception) {}

            try {
                conn.connection.close()
            } catch (_: Exception) {}

            conn.isOpen = false
            Log.i(TAG, "Closed $deviceName")
            "Closed serial device: $deviceName"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close $deviceName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Close all open serial devices.
     */
    fun closeAll(): String {
        val count = connections.size
        connections.keys.toList().forEach { close(it) }
        return "Closed $count serial device(s)"
    }

    // ---- Read ----

    /**
     * Read data from a serial device.
     * @param deviceName The device identifier
     * @param numBytes Maximum bytes to read (default 4096)
     */
    fun read(deviceName: String, numBytes: Int = BUFFER_SIZE): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            val buffer = ByteBuffer.allocate(numBytes.coerceAtMost(BUFFER_SIZE))
            val bytesRead = conn.connection.bulkTransfer(
                conn.endpointIn,
                buffer.array(),
                buffer.array().size,
                READ_TIMEOUT_MS
            )

            when {
                bytesRead < 0 -> "Read timeout — no data available"
                bytesRead == 0 -> "No data read (device may be idle)"
                else -> {
                    val data = buffer.array().copyOfRange(0, bytesRead)
                    val hexView = data.joinToString(" ") { "%02X".format(it) }
                    val asciiView = String(data).replace(Regex("[^\\x20-\\x7E]"), ".")

                    buildString {
                        appendLine("=== Read ${bytesRead} bytes from $deviceName ===")
                        appendLine("Hex:   $hexView")
                        appendLine("ASCII: $asciiView")
                        appendLine("Raw:   ${String(data)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    // ---- Write ----

    /**
     * Write data to a serial device.
     * @param deviceName The device identifier
     * @param data The data string to write
     */
    fun write(deviceName: String, data: String): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            val bytes = data.toByteArray(Charsets.UTF_8)
            val written = conn.connection.bulkTransfer(
                conn.endpointOut,
                bytes,
                bytes.size,
                READ_TIMEOUT_MS
            )

            when {
                written < 0 -> "Error: Write failed (timeout or device error)"
                written == 0 -> "Warning: 0 bytes written"
                else -> {
                    Log.d(TAG, "Wrote $written bytes to $deviceName")
                    "Wrote $written bytes to $deviceName"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Write raw hex bytes to a serial device.
     */
    fun writeHex(deviceName: String, hexString: String): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            val hexClean = hexString.replace(" ", "").replace("-", "")
            if (hexClean.length % 2 != 0) return "Error: Hex string must have even length"

            val bytes = hexClean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val written = conn.connection.bulkTransfer(
                conn.endpointOut,
                bytes,
                bytes.size,
                READ_TIMEOUT_MS
            )

            if (written < 0) "Error: Write failed" else "Wrote $written hex bytes to $deviceName"
        } catch (e: NumberFormatException) {
            "Error: Invalid hex string"
        } catch (e: Exception) {
            Log.e(TAG, "Hex write failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    // ---- Configure ----

    /**
     * Configure a serial device's communication parameters.
     * @param opts Configuration string in format "baud=115200,parity=even,stop=2,databits=7"
     */
    fun config(deviceName: String, opts: String): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            // Parse options
            val optMap = parseConfigOptions(opts)
            optMap["baud"]?.toIntOrNull()?.let { conn.config.baudRate = it }
            optMap["databits"]?.toIntOrNull()?.let { conn.config.dataBits = it }
            optMap["stop"]?.toIntOrNull()?.let { conn.config.stopBits = it }
            optMap["parity"]?.let { conn.config.parity = parseParity(it) }
            optMap["flow"]?.let { conn.config.flowControl = parseFlowControl(it) }

            val configured = configureDevice(conn)

            buildString {
                appendLine("=== Configuration for $deviceName ===")
                append(conn.config.toString())
                appendLine("  Applied:     ${if (configured) "Yes" else "Partial (device may not support all settings)"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Set the baud rate for a serial device.
     */
    fun setBaudRate(deviceName: String, baudRate: Int): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            if (!isValidBaudRate(baudRate)) {
                return "Error: Invalid baud rate $baudRate. Common rates: 300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600"
            }

            conn.config.baudRate = baudRate
            val configured = configureDevice(conn)

            "Baud rate set to $baudRate for $deviceName (applied: $configured)"
        } catch (e: Exception) {
            Log.e(TAG, "Baud rate change failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    // ---- Monitor ----

    /**
     * Monitor serial output continuously for a given duration.
     * Returns all data received during the monitoring period.
     * @param durationMs Duration to monitor in milliseconds (default 10000)
     */
    fun monitor(deviceName: String, durationMs: Long = 10000): String {
        return try {
            val conn = connections[deviceName]
                ?: return "Error: Device '$deviceName' is not open"

            val output = StringBuilder()
            output.appendLine("=== Monitoring $deviceName for ${durationMs}ms ===")
            output.appendLine()

            val startTime = System.currentTimeMillis()
            val buffer = ByteArray(BUFFER_SIZE)

            while (System.currentTimeMillis() - startTime < durationMs) {
                val bytesRead = conn.connection.bulkTransfer(
                    conn.endpointIn,
                    buffer,
                    buffer.size,
                    500  // Short timeout for responsive monitoring
                )

                if (bytesRead > 0) {
                    val data = buffer.copyOfRange(0, bytesRead)
                    val text = String(data)
                    output.append(text)

                    // Also show hex for non-printable data
                    val hasNonPrintable = data.any { it < 0x20 && it != 0x0A.toByte() && it != 0x0D.toByte() && it != 0x09.toByte() }
                    if (hasNonPrintable) {
                        output.appendLine()
                        output.append("  [HEX: ${data.joinToString(" ") { "%02X".format(it) }}]")
                    }
                }
            }

            output.appendLine()
            output.appendLine("--- Monitoring ended ---")
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Monitor failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    // ---- Info ----

    /**
     * Show detailed information about a USB serial device.
     */
    fun info(context: Context, deviceName: String): String {
        return try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val device = manager.deviceList[deviceName]
                ?: return "Error: Device '$deviceName' not found"

            val conn = connections[deviceName]
            val deviceInfo = getDeviceName(device)

            buildString {
                appendLine("=== USB Serial Device Info ===")
                appendLine("Name:         $deviceInfo")
                appendLine("Device:       ${device.deviceName}")
                appendLine("VID:          0x${"%04X".format(device.vendorId)}")
                appendLine("PID:          0x${"%04X".format(device.productId)}")
                appendLine("Manufacturer: ${device.manufacturerName ?: "N/A"}")
                appendLine("Product:      ${device.productName ?: "N/A"}")
                appendLine("Serial:       ${device.serialNumber ?: "N/A"}")
                appendLine("Class:        ${usbClassToString(device.deviceClass)}")
                appendLine("Subclass:     ${device.deviceSubclass}")
                appendLine("Interfaces:   ${device.interfaceCount}")
                appendLine("Config Count: ${device.configurationCount}")

                // Interface details
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    appendLine()
                    appendLine("  Interface $i:")
                    appendLine("    Class:    ${usbClassToString(iface.interfaceClass)}")
                    appendLine("    Subclass: ${iface.interfaceSubclass}")
                    appendLine("    Protocol: ${iface.interfaceProtocol}")
                    appendLine("    Endpoints: ${iface.endpointCount}")

                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                        val type = when (ep.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> "Bulk"
                            UsbConstants.USB_ENDPOINT_XFER_INT -> "Interrupt"
                            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "Isochronous"
                            else -> "Control"
                        }
                        appendLine("      EP $j: $dir $type (addr=${ep.address}, maxPkt=${ep.maxPacketSize})")
                    }
                }

                // Connection status
                appendLine()
                appendLine("Status:       ${if (conn != null) "OPEN" else "Closed"}")
                if (conn != null) {
                    appendLine("Open Since:   ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(conn.openTime))}")
                    append(conn.config.toString())
                }
            }
        } catch (e: SecurityException) {
            "Error: USB permission denied — ${e.message}"
        } catch (e: Exception) {
            Log.e(TAG, "Info failed for $deviceName", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Get the status of all open serial connections.
     */
    fun status(): String {
        return if (connections.isEmpty()) {
            "No serial devices currently open"
        } else {
            buildString {
                appendLine("=== Open Serial Connections (${connections.size}) ===")
                connections.values.forEach { conn ->
                    appendLine("  ${conn.deviceName}: ${getDeviceName(conn.device)} @ ${conn.config.baudRate} baud")
                }
            }
        }
    }

    // ---- Device detection helpers ----

    /**
     * Check if a USB device is likely a serial device.
     */
    private fun isSerialDevice(device: UsbDevice): Boolean {
        // Check known VID/PID
        if (KNOWN_SERIAL_DEVICES.containsKey(Pair(device.vendorId, device.productId))) {
            return true
        }

        // Check for CDC/ACM interface class
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC || iface.interfaceClass == USB_CLASS_CDC_DATA) {
                return true
            }
            // Vendor-specific class (FTDI, Prolific, etc.)
            if (iface.interfaceClass == USB_CLASS_VENDOR_SPEC) {
                // Check if it has bulk IN/OUT endpoints (typical of serial devices)
                val hasBulkIn = hasEndpointType(iface, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_BULK)
                val hasBulkOut = hasEndpointType(iface, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK)
                if (hasBulkIn && hasBulkOut) return true
            }
        }

        return false
    }

    /**
     * Get a human-readable name for a USB device.
     */
    private fun getDeviceName(device: UsbDevice): String {
        // Check known devices first
        KNOWN_SERIAL_DEVICES[Pair(device.vendorId, device.productId)]?.let { return it }

        // Use manufacturer/product name
        val manufacturer = device.manufacturerName
        val product = device.productName
        if (!manufacturer.isNullOrBlank() && !product.isNullOrBlank()) {
            return "$manufacturer $product"
        }
        if (!product.isNullOrBlank()) return product

        return "USB Device ${"0x%04X:0x%04X".format(device.vendorId, device.productId)}"
    }

    /**
     * Find the serial interface on a USB device.
     */
    private fun findSerialInterface(device: UsbDevice): UsbInterface? {
        // Prefer CDC/ACM interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC || iface.interfaceClass == USB_CLASS_CDC_DATA) {
                val hasIn = findEndpoint(iface, UsbConstants.USB_DIR_IN) != null
                val hasOut = findEndpoint(iface, UsbConstants.USB_DIR_OUT) != null
                if (hasIn && hasOut) return iface
            }
        }

        // Fall back to vendor-specific interface with bulk endpoints
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val hasIn = findEndpoint(iface, UsbConstants.USB_DIR_IN) != null
            val hasOut = findEndpoint(iface, UsbConstants.USB_DIR_OUT) != null
            if (hasIn && hasOut) return iface
        }

        return null
    }

    /**
     * Find an endpoint with the given direction on an interface.
     */
    private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == direction && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                return ep
            }
        }
        // Fall back to interrupt endpoints (some CDC devices use these)
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == direction) return ep
        }
        return null
    }

    private fun hasEndpointType(iface: UsbInterface, direction: Int, type: Int): Boolean {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == direction && ep.type == type) return true
        }
        return false
    }

    /**
     * Configure a serial device with the current connection settings.
     * Sends USB control transfers for baud rate, line coding, etc.
     */
    private fun configureDevice(conn: SerialConnection): Boolean {
        return try {
            // CDC SET_LINE_CODING request
            // bmRequestType: 0x21 (Host-to-device, Class, Interface)
            // bRequest: 0x20 (SET_LINE_CODING)
            val lineCoding = ByteArray(7)
            val baudRate = conn.config.baudRate

            // Line coding structure: dwDTERate (4 bytes LE), bCharFormat, bParityType, bDataBits
            lineCoding[0] = (baudRate and 0xFF).toByte()
            lineCoding[1] = ((baudRate shr 8) and 0xFF).toByte()
            lineCoding[2] = ((baudRate shr 16) and 0xFF).toByte()
            lineCoding[3] = ((baudRate shr 24) and 0xFF).toByte()
            lineCoding[4] = (conn.config.stopBits - 1).toByte()  // 0=1stop, 1=1.5stop, 2=2stop
            lineCoding[5] = conn.config.parity.toByte()
            lineCoding[6] = conn.config.dataBits.toByte()

            val result = conn.connection.controlTransfer(
                0x21,       // bmRequestType
                0x20,       // bRequest (SET_LINE_CODING)
                0,          // wValue
                conn.interfaceNum,  // wIndex
                lineCoding, // data
                7,          // data length
                2000        // timeout
            )

            Log.d(TAG, "SET_LINE_CODING result: $result for ${conn.deviceName}")
            result >= 0

            // Also set DTR and RTS signals
            val ctrlResult = conn.connection.controlTransfer(
                0x21,   // bmRequestType
                0x22,   // bRequest (SET_CONTROL_LINE_STATE)
                3,      // wValue (DTR=1, RTS=1)
                conn.interfaceNum,
                null, 0, 2000
            )
            Log.d(TAG, "SET_CONTROL_LINE_STATE result: $ctrlResult")

            result >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Device configuration failed for ${conn.deviceName}", e)
            false
        }
    }

    // ---- Parsing helpers ----

    private fun parseConfigOptions(opts: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        opts.split(",").forEach { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) {
                map[kv[0].trim().lowercase()] = kv[1].trim()
            }
        }
        return map
    }

    private fun parseParity(value: String): Int = when (value.lowercase()) {
        "none", "n", "0" -> 0
        "odd", "o", "1" -> 1
        "even", "e", "2" -> 2
        "mark", "m", "3" -> 3
        "space", "s", "4" -> 4
        else -> value.toIntOrNull() ?: 0
    }

    private fun parseFlowControl(value: String): Int = when (value.lowercase()) {
        "none", "n", "0" -> 0
        "rts", "rtscts", "hardware", "h", "1" -> 1
        "xon", "xonxoff", "software", "x", "2" -> 2
        else -> value.toIntOrNull() ?: 0
    }

    private fun isValidBaudRate(rate: Int): Boolean = rate in 50..1500000

    private fun usbClassToString(cls: Int): String = when (cls) {
        0x00 -> "Defined at Interface"
        0x02 -> "CDC (Communication)"
        0x0A -> "CDC-Data"
        0xFF -> "Vendor Specific"
        0x08 -> "Mass Storage"
        0x0E -> "Video"
        0x01 -> "Audio"
        0x03 -> "HID"
        0x06 -> "Still Image"
        0x07 -> "Printer"
        0x0B -> "Smart Card"
        0x0D -> "Content Security"
        0x10 -> "Audio/Video"
        0x11 -> "Billboard"
        0x12 -> "USB Type-C Bridge"
        else -> "0x${"%02X".format(cls)}"
    }
}
