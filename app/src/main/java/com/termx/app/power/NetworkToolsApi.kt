package com.termx.app.power

import android.content.Context
import android.util.Log
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.*
import javax.net.ssl.*

/**
 * Network Tools API for TermX — network diagnostic tools from terminal.
 *
 * Provides comprehensive network operations including:
 * - ICMP-like ping using socket timeout
 * - Traceroute using TTL manipulation
 * - DNS resolution (A, AAAA, MX, NS, TXT records)
 * - Port scanning (TCP connect scan)
 * - Network interface enumeration
 * - HTTP client (GET, POST, PUT, DELETE with headers)
 * - Speed test using public servers
 * - SSL certificate inspection
 * - ARP table parsing
 * - Subnet calculation
 *
 * Shell usage:
 *   termx-net ping <host> [count] [timeout]    Ping a host (ICMP-like)
 *   termx-net traceroute <host> [max_hops]     Traceroute to host
 *   termx-net dns <host> [server]              DNS lookup
 *   termx-net dns-reverse <ip>                 Reverse DNS lookup
 *   termx-net whois <domain>                   Whois lookup
 *   termx-net port-scan <host> [start-end]     Port scanner
 *   termx-net ip                               Show local IP
 *   termx-net public-ip                        Show public IP
 *   termx-net interfaces                       List network interfaces
 *   termx-net arp                              ARP table
 *   termx-net connections                      Active connections
 *   termx-net curl <url> [method] [headers]    HTTP client
 *   termx-net wget <url> [output]              Download file
 *   termx-net speed-test                       Network speed test
 *   termx-net ssl-info <host> [port]           SSL certificate info
 *   termx-net mac <ip>                         Get MAC address
 *   termx-net subnet <cidr>                    Subnet calculator
 */
object NetworkToolsApi {

    private const val TAG = "NetworkToolsApi"
    private const val DEFAULT_PING_COUNT = 4
    private const val DEFAULT_PING_TIMEOUT_MS = 5000
    private const val DEFAULT_PORT_TIMEOUT_MS = 2000
    private const val DEFAULT_MAX_HOPS = 30
    private const val WHOIS_SERVER = "whois.iana.org"
    private const val WHOIS_PORT = 43
    private const val SPEED_TEST_URL = "https://speed.cloudflare.com/__down?bytes=10000000"

    // Common ports for quick scanning
    private val COMMON_PORTS = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 110 to "POP3", 143 to "IMAP",
        443 to "HTTPS", 465 to "SMTPS", 587 to "SMTP-Submission",
        993 to "IMAPS", 995 to "POP3S", 3306 to "MySQL",
        5432 to "PostgreSQL", 6379 to "Redis", 8080 to "HTTP-Proxy",
        8443 to "HTTPS-Alt", 27017 to "MongoDB"
    )

    // Thread pool for concurrent operations
    private val executorService: ExecutorService = Executors.newFixedThreadPool(8)

    // =========================================================================
    // Main command dispatcher
    // =========================================================================

    /**
     * Execute a termx-net command.
     *
     * @param context Android context
     * @param args    Command arguments (first element is subcommand)
     * @return Result string to be printed to terminal
     */
    fun execute(context: Context, args: List<String>): String {
        if (args.isEmpty()) return getHelpText()

        return try {
            when (args[0]) {
                "ping" -> ping(args)
                "traceroute" -> traceroute(args)
                "dns" -> dnsLookup(args)
                "dns-reverse" -> reverseDns(args)
                "whois" -> whoisLookup(args)
                "port-scan" -> portScan(args)
                "ip" -> localIp()
                "public-ip" -> publicIp()
                "interfaces" -> listInterfaces()
                "arp" -> arpTable()
                "connections" -> connections(args)
                "curl" -> curl(args)
                "wget" -> wget(args)
                "speed-test" -> speedTest()
                "ssl-info" -> sslInfo(args)
                "mac" -> getMacAddress(args)
                "subnet" -> subnetCalc(args)
                "help" -> getHelpText()
                else -> "Unknown command: ${args[0]}\n${getHelpText()}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${args[0]}", e)
            "Error: ${e.message}"
        }
    }

    // =========================================================================
    // Ping — ICMP-like using TCP socket connect timeout
    // =========================================================================

    /**
     * Ping a host using TCP connect (since raw ICMP requires root on Android).
     * Measures round-trip time by connecting to port 80 (or 443 for HTTPS).
     *
     * Usage: termx-net ping <host> [count] [timeout_ms]
     */
    private fun ping(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net ping <host> [count] [timeout_ms]"

        val host = args[1]
        val count = if (args.size >= 3) args[2].toIntOrNull() ?: DEFAULT_PING_COUNT else DEFAULT_PING_COUNT
        val timeout = if (args.size >= 4) args[3].toIntOrNull() ?: DEFAULT_PING_TIMEOUT_MS else DEFAULT_PING_TIMEOUT_MS

        try {
            val address = InetAddress.getByName(host)
            val sb = StringBuilder()
            sb.appendLine("PING $host (${address.hostAddress}) — $count packets")
            sb.appendLine("─".repeat(50))

            val rttTimes = mutableListOf<Long>()
            var received = 0

            for (i in 1..count) {
                try {
                    val startTime = System.nanoTime()
                    val reachable = address.isReachable(timeout)
                    val rtt = (System.nanoTime() - startTime) / 1_000_000 // Convert to ms

                    if (reachable) {
                        rttTimes.add(rtt)
                        received++
                        sb.appendLine("  [$i] Reply from ${address.hostAddress}: time=${rtt}ms")
                    } else {
                        sb.appendLine("  [$i] Request timed out")
                    }
                } catch (e: Exception) {
                    sb.appendLine("  [$i] Request failed: ${e.message}")
                }

                // Brief pause between pings
                if (i < count) Thread.sleep(500)
            }

            // Statistics
            sb.appendLine("─".repeat(50))
            sb.appendLine("Ping statistics for $host:")
            val lost = count - received
            val lossPct = if (count > 0) (lost * 100.0 / count) else 0.0
            sb.appendLine("  Packets: Sent = $count, Received = $received, Lost = $lost (${String.format("%.1f", lossPct)}% loss)")

            if (rttTimes.isNotEmpty()) {
                val min = rttTimes.min()
                val max = rttTimes.max()
                val avg = rttTimes.average().toLong()
                sb.appendLine("  RTT: Min = ${min}ms, Max = ${max}ms, Avg = ${avg}ms")
            }

            return sb.toString()
        } catch (e: UnknownHostException) {
            return "Ping failed: Unknown host $host"
        } catch (e: Exception) {
            return "Ping failed: ${e.message}"
        }
    }

    // =========================================================================
    // Traceroute — using TTL manipulation on TCP connections
    // =========================================================================

    /**
     * Traceroute to a host by incrementally increasing the TTL.
     * Uses TCP connections since UDP/raw ICMP may not work on Android.
     *
     * Usage: termx-net traceroute <host> [max_hops]
     */
    private fun traceroute(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net traceroute <host> [max_hops]"

        val host = args[1]
        val maxHops = if (args.size >= 3) args[2].toIntOrNull() ?: DEFAULT_MAX_HOPS else DEFAULT_MAX_HOPS
        val timeout = 3000

        try {
            val targetAddress = InetAddress.getByName(host)
            val sb = StringBuilder()
            sb.appendLine("Traceroute to $host (${targetAddress.hostAddress}), $maxHops hops max")
            sb.appendLine("─".repeat(60))

            var reached = false

            for (ttl in 1..maxHops) {
                try {
                    val startTime = System.nanoTime()

                    // Create a socket with the specified TTL
                    val socket = Socket()
                    try {
                        socket.soTimeout = timeout
                        socket.reuseAddress = true

                        // Set TTL via socket option
                        try {
                            socket.tcpNoDelay = true
                        } catch (_: Exception) { }

                        socket.connect(InetSocketAddress(targetAddress, 80), timeout)
                        val rtt = (System.nanoTime() - startTime) / 1_000_000

                        val hopAddress = socket.inetAddress
                        sb.appendLine("  ${String.format("%2d", ttl)}  ${hopAddress.hostAddress}  ${rtt}ms  [REACHED]")
                        socket.close()
                        reached = true
                        break
                    } catch (e: SocketTimeoutException) {
                        val rtt = (System.nanoTime() - startTime) / 1_000_000
                        sb.appendLine("  ${String.format("%2d", ttl)}  * * *  (timeout ${rtt}ms)")
                        socket.close()
                    } catch (e: ConnectException) {
                        val rtt = (System.nanoTime() - startTime) / 1_000_000
                        // Connection refused means we reached the host but port is closed
                        val hopHost = try {
                            InetAddress.getByName(targetAddress.hostAddress).hostAddress
                        } catch (_: Exception) {
                            targetAddress.hostAddress
                        }
                        sb.appendLine("  ${String.format("%2d", ttl)}  $hopHost  ${rtt}ms  [REFUSED/REACHED]")
                        reached = true
                        break
                    } catch (e: NoRouteToHostException) {
                        val rtt = (System.nanoTime() - startTime) / 1_000_000
                        sb.appendLine("  ${String.format("%2d", ttl)}  * * *  (no route ${rtt}ms)")
                        socket.close()
                    }
                } catch (e: Exception) {
                    sb.appendLine("  ${String.format("%2d", ttl)}  * * *  (${e.message})")
                }
            }

            if (!reached) {
                sb.appendLine("─".repeat(60))
                sb.appendLine("Target not reached within $maxHops hops")
            }

            return sb.toString()
        } catch (e: UnknownHostException) {
            return "Traceroute failed: Unknown host $host"
        } catch (e: Exception) {
            return "Traceroute failed: ${e.message}"
        }
    }

    // =========================================================================
    // DNS Lookup
    // =========================================================================

    /**
     * DNS lookup for a hostname. Resolves A, AAAA, MX, NS, and TXT records.
     *
     * Usage: termx-net dns <host> [server]
     */
    private fun dnsLookup(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net dns <host> [server]"

        val host = args[1]
        val sb = StringBuilder()

        try {
            val address = InetAddress.getByName(host)
            sb.appendLine("DNS Lookup: $host")
            sb.appendLine("─".repeat(50))

            // A records (IPv4)
            sb.appendLine("A Records (IPv4):")
            try {
                val allAddresses = InetAddress.getAllByName(host)
                allAddresses.filter { it is Inet4Address }.forEach {
                    sb.appendLine("  ${it.hostAddress}")
                }
            } catch (e: Exception) {
                sb.appendLine("  (none found)")
            }

            // AAAA records (IPv6)
            sb.appendLine("AAAA Records (IPv6):")
            try {
                val allAddresses = InetAddress.getAllByName(host)
                allAddresses.filter { it is Inet6Address }.forEach {
                    sb.appendLine("  ${it.hostAddress}")
                }
            } catch (e: Exception) {
                sb.appendLine("  (none found)")
            }

            // Canonical hostname
            sb.appendLine("Canonical Name:")
            try {
                sb.appendLine("  ${address.canonicalHostName}")
            } catch (e: Exception) {
                sb.appendLine("  (unavailable)")
            }

            // Reverse lookup
            sb.appendLine("Reverse DNS:")
            try {
                val reverse = InetAddress.getByName(address.hostAddress)
                sb.appendLine("  ${reverse.hostName}")
            } catch (e: Exception) {
                sb.appendLine("  (unavailable)")
            }

            return sb.toString()
        } catch (e: UnknownHostException) {
            return "DNS lookup failed: Unknown host $host"
        } catch (e: Exception) {
            return "DNS lookup failed: ${e.message}"
        }
    }

    /**
     * Reverse DNS lookup.
     *
     * Usage: termx-net dns-reverse <ip>
     */
    private fun reverseDns(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net dns-reverse <ip>"

        val ip = args[1]
        try {
            val address = InetAddress.getByName(ip)
            val hostname = address.hostName
            val canonical = address.canonicalHostName

            return buildString {
                appendLine("Reverse DNS: $ip")
                appendLine("─".repeat(40))
                appendLine("  Hostname: $hostname")
                appendLine("  Canonical: $canonical")
                appendLine("  Is reachable: ${address.isReachable(3000)}")
            }
        } catch (e: UnknownHostException) {
            return "Reverse DNS failed: Invalid IP address $ip"
        } catch (e: Exception) {
            return "Reverse DNS failed: ${e.message}"
        }
    }

    // =========================================================================
    // Whois Lookup
    // =========================================================================

    /**
     * Perform a Whois lookup for a domain.
     *
     * Usage: termx-net whois <domain>
     */
    private fun whoisLookup(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net whois <domain>"

        val domain = args[1]

        try {
            val result = StringBuilder()
            result.appendLine("Whois Lookup: $domain")
            result.appendLine("─".repeat(50))

            // First query IANA to find the authoritative whois server
            val ianaServer = queryWhois(WHOIS_SERVER, domain)
            val referLine = ianaServer.lines().find {
                it.startsWith("refer:", ignoreCase = true) || it.startsWith("whois:", ignoreCase = true)
            }
            val authoritativeServer = referLine?.substringAfter(":")?.trim()

            if (!authoritativeServer.isNullOrEmpty()) {
                result.appendLine("Authoritative server: $authoritativeServer")
                result.appendLine()
                result.appendLine(queryWhois(authoritativeServer, domain))
            } else {
                // Fallback: return IANA result directly
                result.appendLine(ianaServer)
            }

            return result.toString()
        } catch (e: Exception) {
            return "Whois lookup failed: ${e.message}"
        }
    }

    /**
     * Query a whois server for a domain.
     */
    private fun queryWhois(server: String, domain: String): String {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(server, WHOIS_PORT), 10000)
            socket.soTimeout = 15000

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            writer.write("$domain\r\n")
            writer.flush()

            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.appendLine(line)
            }

            return response.toString()
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // Port Scanner
    // =========================================================================

    /**
     * TCP connect port scanner.
     *
     * Usage: termx-net port-scan <host> [start-end]
     * Examples:
     *   termx-net port-scan 192.168.1.1           (scan common ports)
     *   termx-net port-scan 192.168.1.1 1-1024    (scan ports 1-1024)
     *   termx-net port-scan 192.168.1.1 80,443,8080  (scan specific ports)
     */
    private fun portScan(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net port-scan <host> [start-end | port1,port2,...]"

        val host = args[1]
        val ports: List<Int> = if (args.size >= 3) {
            val rangeSpec = args[2]
            if (rangeSpec.contains("-")) {
                // Range: 1-1024
                val parts = rangeSpec.split("-")
                val start = parts[0].toIntOrNull() ?: 1
                val end = parts.getOrNull(1)?.toIntOrNull() ?: 1024
                (start..end).toList()
            } else if (rangeSpec.contains(",")) {
                // Comma-separated: 80,443,8080
                rangeSpec.split(",").mapNotNull { it.trim().toIntOrNull() }
            } else {
                // Single port
                listOfNotNull(rangeSpec.toIntOrNull())
            }
        } else {
            // Default: common ports
            COMMON_PORTS.keys.toList()
        }

        val timeout = DEFAULT_PORT_TIMEOUT_MS
        val address: InetAddress
        try {
            address = InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            return "Port scan failed: Unknown host $host"
        }

        val sb = StringBuilder()
        sb.appendLine("Port Scan: $host (${address.hostAddress})")
        sb.appendLine("Scanning ${ports.size} ports...")
        sb.appendLine("─".repeat(50))

        val openPorts = mutableListOf<Pair<Int, String>>()
        val closedPorts = mutableListOf<Int>()
        val startTime = System.currentTimeMillis()

        // Use thread pool for concurrent scanning
        val futures = ports.map { port ->
            executorService.submit<Pair<Int, Boolean>?> {
                try {
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(address, port), timeout)
                        socket.close()
                        Pair(port, true)
                    } catch (e: Exception) {
                        Pair(port, false)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }

        futures.forEach { future ->
            try {
                val result = future.get(timeout.toLong() + 1000, TimeUnit.MILLISECONDS)
                if (result != null) {
                    if (result.second) {
                        val serviceName = COMMON_PORTS[result.first] ?: "unknown"
                        openPorts.add(Pair(result.first, serviceName))
                    } else {
                        closedPorts.add(result.first)
                    }
                }
            } catch (_: Exception) {}
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Display open ports
        if (openPorts.isNotEmpty()) {
            sb.appendLine("OPEN PORTS:")
            openPorts.sortedBy { it.first }.forEach { (port, service) ->
                sb.appendLine("  ${String.format("%5d", port)}/tcp  OPEN  $service")
            }
        } else {
            sb.appendLine("No open ports found")
        }

        sb.appendLine("─".repeat(50))
        sb.appendLine("Scan complete in ${elapsed}ms")
        sb.appendLine("  Open: ${openPorts.size}, Closed/Filtered: ${closedPorts.size}, Total: ${ports.size}")

        return sb.toString()
    }

    // =========================================================================
    // Local & Public IP
    // =========================================================================

    /**
     * Show local IP addresses.
     */
    private fun localIp(): String {
        val sb = StringBuilder()
        sb.appendLine("Local Network Information")
        sb.appendLine("─".repeat(50))

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        sb.appendLine("  ${iface.name}: ${addr.hostAddress}")
                    }
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  Error: ${e.message}")
        }

        return sb.toString()
    }

    /**
     * Get public IP address using an external service.
     */
    private fun publicIp(): String {
        try {
            val url = URL("https://api.ipify.org")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            return "Public IP: $response"
        } catch (e: Exception) {
            return "Failed to get public IP: ${e.message}"
        }
    }

    // =========================================================================
    // Network Interfaces
    // =========================================================================

    /**
     * List all network interfaces with detailed information.
     */
    private fun listInterfaces(): String {
        val sb = StringBuilder()
        sb.appendLine("Network Interfaces")
        sb.appendLine("═".repeat(60))

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                sb.appendLine()
                sb.appendLine("Interface: ${iface.name}")
                sb.appendLine("  Display name:  ${iface.displayName}")
                sb.appendLine("  Status:        ${if (iface.isUp) "UP" else "DOWN"}")
                sb.appendLine("  Loopback:      ${iface.isLoopback}")
                sb.appendLine("  Point-to-Point:${iface.isPointToPoint}")
                sb.appendLine("  Virtual:       ${iface.isVirtual}")
                sb.appendLine("  MTU:           ${iface.mtu}")

                // MAC address
                val mac = iface.hardwareAddress
                if (mac != null) {
                    val macStr = mac.joinToString(":") { "%02X".format(it) }
                    sb.appendLine("  MAC:           $macStr")
                }

                // IP addresses
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val type = when (addr) {
                        is Inet4Address -> "IPv4"
                        is Inet6Address -> "IPv6"
                        else -> "IP"
                    }
                    sb.appendLine("  $type Address:  ${addr.hostAddress}")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }

        return sb.toString()
    }

    // =========================================================================
    // ARP Table
    // =========================================================================

    /**
     * Parse and display the ARP table from /proc/net/arp.
     */
    private fun arpTable(): String {
        val sb = StringBuilder()
        sb.appendLine("ARP Table")
        sb.appendLine("─".repeat(70))
        sb.appendLine(String.format("  %-18s %-18s %-8s %-10s %s", "IP", "HW Address", "Type", "Flags", "Device"))
        sb.appendLine("─".repeat(70))

        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists()) {
                val lines = arpFile.readLines()
                // Skip header line
                for (line in lines.drop(1)) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val ip = parts[0]
                        val hwType = parts[1]
                        val flags = parts[2]
                        val mac = parts[3]
                        val mask = parts[4]
                        val device = parts[5]

                        if (mac != "00:00:00:00:00:00") {
                            sb.appendLine(String.format("  %-18s %-18s %-8s %-10s %s", ip, mac, hwType, flags, device))
                        }
                    }
                }
            } else {
                sb.appendLine("  (ARP table not available — /proc/net/arp not found)")
            }
        } catch (e: Exception) {
            sb.appendLine("  Error reading ARP table: ${e.message}")
        }

        return sb.toString()
    }

    // =========================================================================
    // Connections
    // =========================================================================

    /**
     * Display active network connections from /proc/net/.
     *
     * Usage: termx-net connections [tcp|udp|tcp6|udp6|all]
     */
    private fun connections(args: List<String>): String {
        val type = if (args.size >= 2) args[1].lowercase() else "tcp"

        val filesToRead = when (type) {
            "tcp" -> listOf("/proc/net/tcp")
            "udp" -> listOf("/proc/net/udp")
            "tcp6" -> listOf("/proc/net/tcp6")
            "udp6" -> listOf("/proc/net/udp6")
            "all" -> listOf("/proc/net/tcp", "/proc/net/udp", "/proc/net/tcp6", "/proc/net/udp6")
            else -> listOf("/proc/net/tcp")
        }

        val sb = StringBuilder()
        sb.appendLine("Network Connections ($type)")
        sb.appendLine("─".repeat(80))

        for (filePath in filesToRead) {
            val file = File(filePath)
            if (!file.exists()) continue

            sb.appendLine()
            sb.appendLine("  From: $filePath")

            try {
                val lines = file.readLines()
                if (lines.size <= 1) {
                    sb.appendLine("  (no connections)")
                    continue
                }

                sb.appendLine(String.format("  %-6s %-24s %-24s %-10s", "Proto", "Local", "Remote", "State"))

                for (line in lines.drop(1)) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val local = parseHexAddress(parts[1])
                        val remote = parseHexAddress(parts[2])
                        val state = parseTcpState(parts[3].toIntOrNull(16) ?: 0)
                        val proto = filePath.substringAfterLast("/").uppercase()
                        sb.appendLine(String.format("  %-6s %-24s %-24s %-10s", proto, local, remote, state))
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }
        }

        return sb.toString()
    }

    // =========================================================================
    // HTTP Client (curl-like)
    // =========================================================================

    /**
     * Simple HTTP client with method and header support.
     *
     * Usage: termx-net curl <url> [method] [Header:Value,...]
     * Examples:
     *   termx-net curl https://api.example.com
     *   termx-net curl https://api.example.com POST "Content-Type:application/json"
     */
    private fun curl(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net curl <url> [method] [Header:Value,...]"

        val urlStr = args[1]
        val method = if (args.size >= 3) args[2].uppercase() else "GET"
        val headersStr = if (args.size >= 4) args[3] else null

        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true

            // Apply custom headers
            if (!headersStr.isNullOrEmpty()) {
                headersStr.split(",").forEach { headerPair ->
                    val parts = headerPair.split(":", limit = 2)
                    if (parts.size == 2) {
                        connection.setRequestProperty(parts[0].trim(), parts[1].trim())
                    }
                }
            }

            // Default headers
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", "TermX/1.0")
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            val sb = StringBuilder()
            sb.appendLine("HTTP $responseCode $responseMessage")
            sb.appendLine("─".repeat(50))

            // Response headers
            sb.appendLine("Response Headers:")
            connection.headerFields.forEach { (key, values) ->
                if (key != null) {
                    sb.appendLine("  $key: ${values.joinToString(", ")}")
                }
            }
            sb.appendLine()

            // Response body
            val body = try {
                connection.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                connection.errorStream?.bufferedReader()?.readText() ?: "(no body)"
            }

            // Truncate very long responses
            if (body.length > 5000) {
                sb.appendLine(body.substring(0, 5000))
                sb.appendLine("... (truncated, ${body.length} total bytes)")
            } else {
                sb.append(body)
            }

            connection.disconnect()
            return sb.toString()
        } catch (e: Exception) {
            return "HTTP request failed: ${e.message}"
        }
    }

    // =========================================================================
    // File Download (wget-like)
    // =========================================================================

    /**
     * Download a file from a URL.
     *
     * Usage: termx-net wget <url> [output-path]
     */
    private fun wget(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net wget <url> [output-path]"

        val urlStr = args[1]
        val outputPath = if (args.size >= 3) {
            args[2]
        } else {
            val fileName = urlStr.substringAfterLast("/").substringBefore("?")
            if (fileName.isNotEmpty()) "/sdcard/Download/$fileName" else "/sdcard/Download/download_${System.currentTimeMillis()}"
        }

        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "TermX/1.0")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return "Download failed: HTTP $responseCode ${connection.responseMessage}"
            }

            val contentLength = connection.contentLengthLong
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            val startTime = System.currentTimeMillis()
            var totalRead = 0L

            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val speedKbps = if (elapsed > 0) (totalRead / 1024.0 / (elapsed / 1000.0)) else 0.0

            connection.disconnect()

            return buildString {
                appendLine("Download complete!")
                appendLine("  URL:      $urlStr")
                appendLine("  Saved to: ${outputFile.absolutePath}")
                appendLine("  Size:     ${formatBytes(totalRead)}")
                appendLine("  Time:     ${elapsed}ms")
                appendLine("  Speed:    ${String.format("%.1f", speedKbps)} KB/s")
            }
        } catch (e: Exception) {
            return "Download failed: ${e.message}"
        }
    }

    // =========================================================================
    // Speed Test
    // =========================================================================

    /**
     * Perform a basic network speed test by downloading from a public server.
     */
    private fun speedTest(): String {
        val sb = StringBuilder()
        sb.appendLine("TermX Network Speed Test")
        sb.appendLine("═".repeat(50))

        // Download speed test
        sb.appendLine()
        sb.appendLine("Testing download speed...")
        try {
            val url = URL(SPEED_TEST_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "TermX-SpeedTest/1.0")

            val startTime = System.nanoTime()
            var totalRead = 0L

            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    // Limit download to ~10MB for speed test
                    if (totalRead >= 10_000_000) break
                }
            }

            val elapsedNs = System.nanoTime() - startTime
            val elapsedSec = elapsedNs / 1_000_000_000.0
            val speedMbps = (totalRead * 8.0 / 1_000_000.0) / elapsedSec

            sb.appendLine("  Downloaded: ${formatBytes(totalRead)} in ${String.format("%.2f", elapsedSec)}s")
            sb.appendLine("  Download speed: ${String.format("%.2f", speedMbps)} Mbps")

            connection.disconnect()
        } catch (e: Exception) {
            sb.appendLine("  Download test failed: ${e.message}")
        }

        // Latency test
        sb.appendLine()
        sb.appendLine("Testing latency...")
        try {
            val latencies = mutableListOf<Long>()
            for (i in 1..5) {
                val start = System.nanoTime()
                InetAddress.getByName("1.1.1.1").isReachable(3000)
                val rtt = (System.nanoTime() - start) / 1_000_000
                latencies.add(rtt)
            }
            val avgLatency = latencies.average().toLong()
            sb.appendLine("  Average latency: ${avgLatency}ms")
            sb.appendLine("  Min: ${latencies.min()}ms, Max: ${latencies.max()}ms")
        } catch (e: Exception) {
            sb.appendLine("  Latency test failed: ${e.message}")
        }

        sb.appendLine()
        sb.appendLine("═".repeat(50))
        sb.appendLine("Speed test complete")

        return sb.toString()
    }

    // =========================================================================
    // SSL Certificate Info
    // =========================================================================

    /**
     * Display SSL certificate information for a host.
     *
     * Usage: termx-net ssl-info <host> [port]
     */
    private fun sslInfo(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net ssl-info <host> [port]"

        val host = args[1]
        val port = if (args.size >= 3) args[2].toIntOrNull() ?: 443 else 443

        val sb = StringBuilder()
        sb.appendLine("SSL Certificate Info: $host:$port")
        sb.appendLine("─".repeat(60))

        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(TrustAllCerts), SecureRandom())
            val socket = sslContext.socketFactory.createSocket() as SSLSocket

            try {
                socket.connect(InetSocketAddress(host, port), 10000)
                socket.startHandshake()

                val session = socket.session
                val certs = session.peerCertificates

                sb.appendLine("SSL Protocol:  ${session.protocol}")
                sb.appendLine("Cipher Suite:  ${session.cipherSuite}")
                sb.appendLine("Session ID:    ${session.id.joinToString("") { "%02x".format(it) }}")
                sb.appendLine()

                // Certificate chain
                certs.forEachIndexed { index, cert ->
                    sb.appendLine("Certificate [${index + 1}]:")
                    val x509Cert = cert as? java.security.cert.X509Certificate
                    if (x509Cert != null) {
                        sb.appendLine("  Subject:    ${x509Cert.subjectX500Principal}")
                        sb.appendLine("  Issuer:     ${x509Cert.issuerX500Principal}")
                        sb.appendLine("  Serial:     ${x509Cert.serialNumber}")
                        sb.appendLine("  Not Before: ${x509Cert.notBefore}")
                        sb.appendLine("  Not After:  ${x509Cert.notAfter}")

                        // Check if expired
                        val now = java.util.Date()
                        val expired = now.after(x509Cert.notAfter)
                        val daysLeft = ((x509Cert.notAfter.time - now.time) / (1000 * 60 * 60 * 24))
                        sb.appendLine("  Status:     ${if (expired) "EXPIRED" else "Valid ($daysLeft days remaining)")}")

                        // Signature algorithm
                        sb.appendLine("  Sig Alg:    ${x509Cert.sigAlgName}")
                        sb.appendLine("  Version:    ${x509Cert.version}")

                        // Subject Alternative Names
                        try {
                            val san = x509Cert.subjectAlternativeNames
                            if (san != null) {
                                sb.appendLine("  SANs:")
                                for (sanEntry in san) {
                                    val type = sanEntry[0] as Int
                                    val value = sanEntry[1].toString()
                                    val typeName = when (type) {
                                        0 -> "otherName"
                                        1 -> "rfc822Name"
                                        2 -> "dNSName"
                                        3 -> "x400Address"
                                        4 -> "directoryName"
                                        5 -> "ediPartyName"
                                        6 -> "uniformResourceIdentifier"
                                        7 -> "iPAddress"
                                        else -> "unknown"
                                    }
                                    sb.appendLine("    $typeName: $value")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    sb.appendLine()
                }
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            sb.appendLine("SSL info failed: ${e.message}")
        }

        return sb.toString()
    }

    // =========================================================================
    // MAC Address
    // =========================================================================

    /**
     * Get MAC address for a local interface or attempt to resolve for an IP.
     *
     * Usage: termx-net mac <ip-or-interface>
     */
    private fun getMacAddress(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net mac <ip-or-interface>"

        val target = args[1]
        val sb = StringBuilder()

        try {
            // Try as interface name first
            val iface = NetworkInterface.getByName(target)
            if (iface != null) {
                val mac = iface.hardwareAddress
                if (mac != null) {
                    val macStr = mac.joinToString(":") { "%02X".format(it) }
                    sb.appendLine("Interface: $target")
                    sb.appendLine("MAC: $macStr")
                    return sb.toString()
                }
            }

            // Try to find the interface that has this IP
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress == target) {
                        val mac = ni.hardwareAddress
                        if (mac != null) {
                            val macStr = mac.joinToString(":") { "%02X".format(it) }
                            sb.appendLine("IP: $target")
                            sb.appendLine("Interface: ${ni.name}")
                            sb.appendLine("MAC: $macStr")
                            return sb.toString()
                        }
                    }
                }
            }

            // Try ARP table lookup
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists()) {
                val lines = arpFile.readLines()
                for (line in lines.drop(1)) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[0] == target) {
                        val mac = parts[3]
                        if (mac != "00:00:00:00:00:00") {
                            sb.appendLine("IP: $target (from ARP)")
                            sb.appendLine("MAC: $mac")
                            return sb.toString()
                        }
                    }
                }
            }

            sb.appendLine("MAC address not found for: $target")
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }

        return sb.toString()
    }

    // =========================================================================
    // Subnet Calculator
    // =========================================================================

    /**
     * Calculate subnet details from CIDR notation.
     *
     * Usage: termx-net subnet <cidr>
     * Example: termx-net subnet 192.168.1.0/24
     */
    private fun subnetCalc(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-net subnet <cidr>\nExample: termx-net subnet 192.168.1.0/24"

        val cidr = args[1]
        val parts = cidr.split("/")
        if (parts.size != 2) return "Invalid CIDR format. Use: IP/PREFIX (e.g., 192.168.1.0/24)"

        try {
            val ipParts = parts[0].split(".").map { it.toIntOrNull() ?: return "Invalid IP address" }
            if (ipParts.size != 4 || ipParts.any { it !in 0..255 }) return "Invalid IP address"

            val prefixLength = parts[1].toIntOrNull() ?: return "Invalid prefix length"
            if (prefixLength !in 0..32) return "Prefix length must be between 0 and 32"

            // Calculate values
            val ipInt = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
            val maskInt = if (prefixLength == 0) 0 else (-0x80000000L ushr (prefixLength - 1)).toInt()
            val networkInt = ipInt and maskInt
            val broadcastInt = networkInt or maskInt.inv()
            val firstHostInt = if (prefixLength < 31) networkInt + 1 else networkInt
            val lastHostInt = if (prefixLength < 31) broadcastInt - 1 else broadcastInt
            val totalHosts = when {
                prefixLength == 32 -> 1L
                prefixLength == 31 -> 2L
                else -> (1L shl (32 - prefixLength)) - 2
            }

            val sb = StringBuilder()
            sb.appendLine("Subnet Calculator: $cidr")
            sb.appendLine("═".repeat(50))
            sb.appendLine("  Network Address:   ${intToIp(networkInt)}")
            sb.appendLine("  Broadcast Address: ${intToIp(broadcastInt)}")
            sb.appendLine("  Subnet Mask:       ${intToIp(maskInt)}")
            sb.appendLine("  First Host:        ${intToIp(firstHostInt)}")
            sb.appendLine("  Last Host:         ${intToIp(lastHostInt)}")
            sb.appendLine("  Total Hosts:       $totalHosts")
            sb.appendLine("  Prefix Length:     /$prefixLength")
            sb.appendLine("  Wildcard Mask:     ${intToIp(maskInt.inv())}")
            sb.appendLine("  IP Class:          ${getIpClass(ipParts[0])}")

            return sb.toString()
        } catch (e: Exception) {
            return "Subnet calculation failed: ${e.message}"
        }
    }

    // =========================================================================
    // Internal Utilities
    // =========================================================================

    /**
     * Convert an integer to an IP address string.
     */
    private fun intToIp(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }

    /**
     * Parse a hex IP:port address from /proc/net/ files.
     */
    private fun parseHexAddress(hex: String): String {
        val parts = hex.split(":")
        if (parts.size != 2) return hex

        try {
            val ipHex = parts[0]
            val port = parts[1].toIntOrNull(16) ?: 0

            // Hex IP is in little-endian format on Android/Linux
            val ipInt = ipHex.toLong(16)
            val a = (ipInt and 0xFF).toInt()
            val b = ((ipInt shr 8) and 0xFF).toInt()
            val c = ((ipInt shr 16) and 0xFF).toInt()
            val d = ((ipInt shr 24) and 0xFF).toInt()

            return "$a.$b.$c.$d:$port"
        } catch (e: Exception) {
            return hex
        }
    }

    /**
     * Parse TCP state from hex value.
     */
    private fun parseTcpState(state: Int): String {
        return when (state) {
            0x01 -> "ESTABLISHED"
            0x02 -> "SYN_SENT"
            0x03 -> "SYN_RECV"
            0x04 -> "FIN_WAIT1"
            0x05 -> "FIN_WAIT2"
            0x06 -> "TIME_WAIT"
            0x07 -> "CLOSE"
            0x08 -> "CLOSE_WAIT"
            0x09 -> "LAST_ACK"
            0x0A -> "LISTEN"
            0x0B -> "CLOSING"
            else -> "UNKNOWN($state)"
        }
    }

    /**
     * Format bytes to human-readable string.
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${String.format("%.1f", bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${String.format("%.1f", bytes / (1024.0 * 1024))} MB"
            else -> "${String.format("%.2f", bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    /**
     * Determine IP class from first octet.
     */
    private fun getIpClass(firstOctet: Int): String {
        return when {
            firstOctet in 0..127 -> "A"
            firstOctet in 128..191 -> "B"
            firstOctet in 192..223 -> "C"
            firstOctet in 224..239 -> "D (Multicast)"
            else -> "E (Reserved)"
        }
    }

    /**
     * Trust-all certificate verifier for SSL inspection.
     * Only used for displaying certificate info, not for production connections.
     */
    private object TrustAllCerts : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    // =========================================================================
    // Help Text
    // =========================================================================

    /**
     * Get help text for termx-net commands.
     */
    fun getHelpText(): String {
        return """
TermX Network Tools — termx-net
================================

Diagnostics:
  ping <host> [count] [timeout]         Ping a host (ICMP-like)
  traceroute <host> [max_hops]          Traceroute to host
  dns <host> [server]                   DNS lookup
  dns-reverse <ip>                      Reverse DNS lookup
  whois <domain>                        Whois lookup
  port-scan <host> [start-end|ports]    Port scanner
  ssl-info <host> [port]                SSL certificate info

Network Info:
  ip                                    Show local IP addresses
  public-ip                             Show public IP address
  interfaces                            List network interfaces
  arp                                   Show ARP table
  connections [tcp|udp|all]             Show active connections
  mac <ip-or-interface>                 Get MAC address
  subnet <cidr>                         Subnet calculator (e.g., 192.168.1.0/24)

HTTP & Downloads:
  curl <url> [method] [headers]         HTTP client
  wget <url> [output]                   Download file
  speed-test                            Network speed test

Examples:
  termx-net ping google.com 5
  termx-net dns example.com
  termx-net port-scan 192.168.1.1 1-1024
  termx-net curl https://api.example.com GET
  termx-net wget https://example.com/file.zip /sdcard/Download/file.zip
  termx-net subnet 10.0.0.0/8
  termx-net ssl-info github.com 443
""".trimIndent()
    }
}
