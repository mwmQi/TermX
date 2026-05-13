package com.termx.app.power.tunnel

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID

/**
 * Data model for tunnel configurations in TermX.
 *
 * Each tunnel configuration describes a complete tunnel setup including its type,
 * network parameters, filtering rules, bandwidth constraints, and persistence
 * options. Configurations can be serialized to/from JSON for persistence across
 * app restarts.
 *
 * Tunnel types:
 *   - LOCAL_FORWARD:  Forward a local port to a remote host:port (SSH -L style)
 *   - REMOTE_FORWARD: Forward a remote port to a local service (SSH -R style)
 *   - SOCKS_PROXY:    SOCKS4/5 dynamic proxy on a local port
 *   - REVERSE_SHELL:  Listen on a port and serve a shell on connection
 *
 * Example JSON:
 * ```json
 * {
 *   "id": "550e8400-e29b-41d4-a716-446655440000",
 *   "name": "My SSH Tunnel",
 *   "type": "LOCAL_FORWARD",
 *   "localPort": 8080,
 *   "localHost": "127.0.0.1",
 *   "remoteHost": "10.0.0.1",
 *   "remotePort": 80,
 *   "protocol": "TCP",
 *   "socksVersion": 5,
 *   "bandwidthLimitBps": 0,
 *   "isPersistent": true,
 *   "autoReconnect": true,
 *   "maxConnections": 100,
 *   "allowedIps": ["0.0.0.0/0"],
 *   "deniedIps": [],
 *   "createdAt": 1700000000000,
 *   "enabled": true
 * }
 * ```
 */
data class TunnelConfig(
    /** Unique identifier for this tunnel configuration. */
    val id: String = UUID.randomUUID().toString(),

    /** Human-readable name for this tunnel. */
    var name: String = "",

    /** The type of tunnel (LOCAL_FORWARD, REMOTE_FORWARD, SOCKS_PROXY, REVERSE_SHELL). */
    var type: TunnelType = TunnelType.LOCAL_FORWARD,

    /** Local bind address. Defaults to 127.0.0.1 for security. */
    var localHost: String = "127.0.0.1",

    /** Local port to bind/listen on. */
    var localPort: Int = 0,

    /** Remote host to forward to (not used for SOCKS_PROXY). */
    var remoteHost: String = "",

    /** Remote port to forward to (not used for SOCKS_PROXY). */
    var remotePort: Int = 0,

    /** Transport protocol (TCP or UDP). */
    var protocol: Protocol = Protocol.TCP,

    /** SOCKS proxy version (4 or 5), only relevant for SOCKS_PROXY type. */
    var socksVersion: Int = 5,

    /** Bandwidth limit in bytes per second. 0 = unlimited. */
    var bandwidthLimitBps: Long = 0,

    /** Whether this configuration should persist across app restarts. */
    var isPersistent: Boolean = false,

    /** Whether to auto-reconnect on failure. */
    var autoReconnect: Boolean = false,

    /** Maximum number of concurrent connections. 0 = unlimited. */
    var maxConnections: Int = 0,

    /**
     * IP filter rules for allowed connections. CIDR notation supported.
     * Empty list means allow all (after denied list is applied).
     * Example: ["192.168.1.0/24", "10.0.0.1"]
     */
    var allowedIps: MutableList<String> = mutableListOf(),

    /**
     * IP filter rules for denied connections. CIDR notation supported.
     * Denied rules take precedence over allowed rules.
     * Example: ["192.168.1.100", "10.0.0.0/8"]
     */
    var deniedIps: MutableList<String> = mutableListOf(),

    /** Timestamp when this configuration was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Whether this tunnel is enabled. Disabled tunnels won't auto-start. */
    var enabled: Boolean = true
) {

    companion object {
        private val TAG = "TunnelConfig"

        // JSON field names
        private val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_TYPE = "type"
        private const val KEY_LOCAL_HOST = "localHost"
        private const val KEY_LOCAL_PORT = "localPort"
        private const val KEY_REMOTE_HOST = "remoteHost"
        private const val KEY_REMOTE_PORT = "remotePort"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_SOCKS_VERSION = "socksVersion"
        private const val KEY_BANDWIDTH_LIMIT = "bandwidthLimitBps"
        private const val KEY_IS_PERSISTENT = "isPersistent"
        private const val KEY_AUTO_RECONNECT = "autoReconnect"
        private const val KEY_MAX_CONNECTIONS = "maxConnections"
        private const val KEY_ALLOWED_IPS = "allowedIps"
        private const val KEY_DENIED_IPS = "deniedIps"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_ENABLED = "enabled"

        /**
         * Deserialize a TunnelConfig from a JSON object.
         *
         * @param json The JSON object to parse.
         * @return A TunnelConfig instance, or null if parsing fails.
         */
        fun fromJSON(json: JSONObject): TunnelConfig? {
            return try {
                TunnelConfig(
                    id = json.optString(KEY_ID, UUID.randomUUID().toString()),
                    name = json.optString(KEY_NAME, ""),
                    type = TunnelType.fromString(json.optString(KEY_TYPE, "LOCAL_FORWARD")),
                    localHost = json.optString(KEY_LOCAL_HOST, "127.0.0.1"),
                    localPort = json.optInt(KEY_LOCAL_PORT, 0),
                    remoteHost = json.optString(KEY_REMOTE_HOST, ""),
                    remotePort = json.optInt(KEY_REMOTE_PORT, 0),
                    protocol = Protocol.fromString(json.optString(KEY_PROTOCOL, "TCP")),
                    socksVersion = json.optInt(KEY_SOCKS_VERSION, 5),
                    bandwidthLimitBps = json.optLong(KEY_BANDWIDTH_LIMIT, 0),
                    isPersistent = json.optBoolean(KEY_IS_PERSISTENT, false),
                    autoReconnect = json.optBoolean(KEY_AUTO_RECONNECT, false),
                    maxConnections = json.optInt(KEY_MAX_CONNECTIONS, 0),
                    allowedIps = parseStringList(json.optJSONArray(KEY_ALLOWED_IPS)),
                    deniedIps = parseStringList(json.optJSONArray(KEY_DENIED_IPS)),
                    createdAt = json.optLong(KEY_CREATED_AT, System.currentTimeMillis()),
                    enabled = json.optBoolean(KEY_ENABLED, true)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse TunnelConfig from JSON", e)
                null
            }
        }

        /**
         * Deserialize a TunnelConfig from a JSON string.
         *
         * @param jsonString The JSON string to parse.
         * @return A TunnelConfig instance, or null if parsing fails.
         */
        fun fromJSONString(jsonString: String): TunnelConfig? {
            return try {
                fromJSON(JSONObject(jsonString))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse TunnelConfig from JSON string", e)
                null
            }
        }

        /**
         * Parse a list of tunnel configurations from a JSON array.
         *
         * @param jsonArray The JSON array to parse.
         * @return A list of valid TunnelConfig instances.
         */
        fun fromJSONArray(jsonArray: JSONArray): List<TunnelConfig> {
            val configs = mutableListOf<TunnelConfig>()
            for (i in 0 until jsonArray.length()) {
                try {
                    jsonArray.optJSONObject(i)?.let { json ->
                        fromJSON(json)?.let { configs.add(it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping invalid tunnel config at index $i", e)
                }
            }
            return configs
        }

        /**
         * Parse a JSONArray of strings into a MutableList.
         */
        private fun parseStringList(jsonArray: JSONArray?): MutableList<String> {
            if (jsonArray == null) return mutableListOf()
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                jsonArray.optString(i)?.let { list.add(it) }
            }
            return list
        }

        /**
         * Create a local forward configuration with sensible defaults.
         *
         * @param localPort  The local port to listen on.
         * @param remoteHost The remote host to forward to.
         * @param remotePort The remote port to forward to.
         * @return A configured TunnelConfig for local forwarding.
         */
        fun localForward(localPort: Int, remoteHost: String, remotePort: Int): TunnelConfig {
            return TunnelConfig(
                name = "L:$localPort->$remoteHost:$remotePort",
                type = TunnelType.LOCAL_FORWARD,
                localPort = localPort,
                remoteHost = remoteHost,
                remotePort = remotePort
            )
        }

        /**
         * Create a remote forward configuration with sensible defaults.
         *
         * @param remotePort The remote port to listen on.
         * @param localHost  The local host to forward to.
         * @param localPort  The local port to forward to.
         * @return A configured TunnelConfig for remote forwarding.
         */
        fun remoteForward(remotePort: Int, localHost: String, localPort: Int): TunnelConfig {
            return TunnelConfig(
                name = "R:$remotePort->$localHost:$localPort",
                type = TunnelType.REMOTE_FORWARD,
                localHost = localHost,
                localPort = localPort,
                remotePort = remotePort
            )
        }

        /**
         * Create a SOCKS proxy configuration with sensible defaults.
         *
         * @param localPort   The local port to listen on.
         * @param socksVersion The SOCKS protocol version (4 or 5).
         * @return A configured TunnelConfig for SOCKS proxy.
         */
        fun socksProxy(localPort: Int, socksVersion: Int = 5): TunnelConfig {
            return TunnelConfig(
                name = "SOCKS$socksVersion:$localPort",
                type = TunnelType.SOCKS_PROXY,
                localPort = localPort,
                socksVersion = socksVersion
            )
        }

        /**
         * Create a reverse shell configuration with sensible defaults.
         *
         * @param port The port to listen on for incoming connections.
         * @return A configured TunnelConfig for reverse shell.
         */
        fun reverseShell(port: Int): TunnelConfig {
            return TunnelConfig(
                name = "RevShell:$port",
                type = TunnelType.REVERSE_SHELL,
                localPort = port
            )
        }
    }

    /**
     * Serialize this configuration to a JSON object.
     *
     * @return A JSONObject representing this tunnel configuration.
     */
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put(KEY_ID, id)
            put(KEY_NAME, name)
            put(KEY_TYPE, type.name)
            put(KEY_LOCAL_HOST, localHost)
            put(KEY_LOCAL_PORT, localPort)
            put(KEY_REMOTE_HOST, remoteHost)
            put(KEY_REMOTE_PORT, remotePort)
            put(KEY_PROTOCOL, protocol.name)
            put(KEY_SOCKS_VERSION, socksVersion)
            put(KEY_BANDWIDTH_LIMIT, bandwidthLimitBps)
            put(KEY_IS_PERSISTENT, isPersistent)
            put(KEY_AUTO_RECONNECT, autoReconnect)
            put(KEY_MAX_CONNECTIONS, maxConnections)
            put(KEY_ALLOWED_IPS, JSONArray(allowedIps))
            put(KEY_DENIED_IPS, JSONArray(deniedIps))
            put(KEY_CREATED_AT, createdAt)
            put(KEY_ENABLED, enabled)
        }
    }

    /**
     * Serialize this configuration to a JSON string.
     *
     * @return A formatted JSON string.
     */
    fun toJSONString(): String = toJSON().toString(2)

    /**
     * Check if a given IP address is allowed to connect based on the
     * allow/deny filter rules.
     *
     * Deny rules take precedence over allow rules. If no allow rules are
     * specified, all IPs are allowed (after deny filtering).
     *
     * @param clientIp The IP address to check.
     * @return true if the IP is allowed, false if denied.
     */
    fun isIpAllowed(clientIp: String): Boolean {
        // Check deny list first (takes precedence)
        for (cidr in deniedIps) {
            if (isIpInCidr(clientIp, cidr)) {
                return false
            }
        }

        // If allow list is empty, allow all (that weren't denied)
        if (allowedIps.isEmpty()) {
            return true
        }

        // Check allow list
        for (cidr in allowedIps) {
            if (isIpInCidr(clientIp, cidr)) {
                return true
            }
        }

        return false
    }

    /**
     * Check if an IP address falls within a CIDR range.
     * Supports both CIDR notation (e.g., "192.168.1.0/24") and
     * plain IP addresses (e.g., "192.168.1.1").
     *
     * @param ip   The IP address to check.
     * @param cidr The CIDR range or plain IP.
     * @return true if the IP is within the range.
     */
    private fun isIpInCidr(ip: String, cidr: String): Boolean {
        // Plain IP match (no CIDR notation)
        if (!cidr.contains("/")) {
            return ip == cidr
        }

        return try {
            val parts = cidr.split("/")
            val networkAddress = InetAddress.getByName(parts[0])
            val prefixLength = parts[1].toInt()

            val targetAddress = InetAddress.getByName(ip)

            // Both must be same address family (IPv4 or IPv6)
            if (networkAddress.address.size != targetAddress.address.size) {
                return false
            }

            val networkBytes = networkAddress.address
            val targetBytes = targetAddress.address

            // Full mask for IPv4 or IPv6
            val totalBits = networkBytes.size * 8
            if (prefixLength < 0 || prefixLength > totalBits) {
                return false
            }

            // Compare byte by byte up to the prefix length
            var bitsRemaining = prefixLength
            for (i in networkBytes.indices) {
                if (bitsRemaining <= 0) break

                val mask: Byte = if (bitsRemaining >= 8) {
                    0xFF.toByte()
                } else {
                    ((0xFF shl (8 - bitsRemaining)) and 0xFF).toByte()
                }

                if ((networkBytes[i].toInt() and mask.toInt()) != (targetBytes[i].toInt() and mask.toInt())) {
                    return false
                }
                bitsRemaining -= 8
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Invalid CIDR notation: $cidr", e)
            false
        }
    }

    /**
     * Validate this configuration. Returns a list of validation errors.
     * An empty list means the configuration is valid.
     *
     * @return A list of validation error messages.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        // Port range validation
        if (localPort < 0 || localPort > 65535) {
            errors.add("Local port must be between 0 and 65535")
        }

        if (type != TunnelType.SOCKS_PROXY && type != TunnelType.REVERSE_SHELL) {
            if (remotePort < 0 || remotePort > 65535) {
                errors.add("Remote port must be between 0 and 65535")
            }
        }

        // Remote host required for forwarding types
        if (type == TunnelType.LOCAL_FORWARD || type == TunnelType.REMOTE_FORWARD) {
            if (remoteHost.isBlank()) {
                errors.add("Remote host is required for ${type.name}")
            }
        }

        // SOCKS version validation
        if (type == TunnelType.SOCKS_PROXY && socksVersion !in listOf(4, 5)) {
            errors.add("SOCKS version must be 4 or 5")
        }

        // Local host validation
        if (localHost.isBlank()) {
            errors.add("Local host cannot be blank")
        }

        // Bandwidth limit validation
        if (bandwidthLimitBps < 0) {
            errors.add("Bandwidth limit cannot be negative")
        }

        // Max connections validation
        if (maxConnections < 0) {
            errors.add("Max connections cannot be negative")
        }

        // CIDR validation
        for (cidr in allowedIps + deniedIps) {
            if (!isValidCidr(cidr)) {
                errors.add("Invalid CIDR notation: $cidr")
            }
        }

        return errors
    }

    /**
     * Basic validation for CIDR notation.
     */
    private fun isValidCidr(cidr: String): Boolean {
        return try {
            if (cidr.contains("/")) {
                val parts = cidr.split("/")
                if (parts.size != 2) return false
                InetAddress.getByName(parts[0])
                val prefix = parts[1].toInt()
                prefix in 0..128
            } else {
                InetAddress.getByName(cidr)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate a short display string for this tunnel.
     * Format: "TYPE:localPort->remoteHost:remotePort"
     */
    fun toDisplayString(): String {
        return when (type) {
            TunnelType.LOCAL_FORWARD -> "L:$localPort->$remoteHost:$remotePort"
            TunnelType.REMOTE_FORWARD -> "R:$remotePort->$localHost:$localPort"
            TunnelType.SOCKS_PROXY -> "SOCKS$socksVersion:$localPort"
            TunnelType.REVERSE_SHELL -> "RevShell:$localPort"
        }
    }

    /**
     * Generate a summary line for list display.
     */
    fun toSummaryLine(): String {
        val persistentMark = if (isPersistent) " [P]" else ""
        val autoReconnectMark = if (autoReconnect) " [AR]" else ""
        val protoMark = if (protocol == Protocol.UDP) " (UDP)" else ""
        return "[$id] ${toDisplayString()}$protoMark$persistentMark$autoReconnectMark"
    }
}

/**
 * Enum representing the type of tunnel.
 */
enum class TunnelType(val displayName: String) {
    /** Forward a local port to a remote host:port (SSH -L style). */
    LOCAL_FORWARD("Local Forward"),

    /** Forward a remote port to a local service (SSH -R style). */
    REMOTE_FORWARD("Remote Forward"),

    /** SOCKS4/5 dynamic proxy on a local port (SSH -D style). */
    SOCKS_PROXY("SOCKS Proxy"),

    /** Listen on a port and serve a shell on connection. */
    REVERSE_SHELL("Reverse Shell");

    companion object {
        /**
         * Parse a TunnelType from its string name (case-insensitive).
         * Supports aliases: "local", "remote", "socks", "reverse-shell", "revshell".
         */
        fun fromString(value: String): TunnelType {
            return when (value.uppercase().replace("-", "_").replace(" ", "_")) {
                LOCAL_FORWARD.name, "LOCAL", "L" -> LOCAL_FORWARD
                REMOTE_FORWARD.name, "REMOTE", "R" -> REMOTE_FORWARD
                SOCKS_PROXY.name, "SOCKS", "DYNAMIC", "D" -> SOCKS_PROXY
                REVERSE_SHELL.name, "REVERSE_SHELL", "REVSHELL" -> REVERSE_SHELL
                else -> {
                    Log.w("TunnelType", "Unknown tunnel type: $value, defaulting to LOCAL_FORWARD")
                    LOCAL_FORWARD
                }
            }
        }
    }
}

/**
 * Enum representing the transport protocol.
 */
enum class Protocol(val displayName: String) {
    TCP("TCP"),
    UDP("UDP");

    companion object {
        fun fromString(value: String): Protocol {
            return when (value.uppercase()) {
                TCP.name -> TCP
                UDP.name -> UDP
                else -> {
                    Log.w("Protocol", "Unknown protocol: $value, defaulting to TCP")
                    TCP
                }
            }
        }
    }
}
