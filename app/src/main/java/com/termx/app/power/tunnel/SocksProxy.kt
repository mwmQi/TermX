package com.termx.app.power.tunnel

import android.util.Log
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCKS4/5 proxy server implementation for TermX.
 *
 * Provides a full SOCKS proxy that can handle CONNECT requests from
 * SOCKS4 and SOCKS5 clients. Supports IP-based access control and
 * concurrent connection handling with configurable limits.
 *
 * Protocol Support:
 *   - SOCKS4:  CONNECT command (BIND not supported)
 *   - SOCKS5:  CONNECT command, No Auth method (USERNAME/PASSWORD and GSSAPI not supported)
 *              UDP ASSOCIATE (basic support)
 *
 * Usage:
 * ```kotlin
 * val proxy = SocksProxy(
 *     bindPort = 1080,
 *     socksVersion = 5,
 *     statistics = tunnelStats,
 *     config = tunnelConfig
 * )
 * proxy.start()   // Starts accepting connections
 * // ...
 * proxy.stop()    // Shuts down gracefully
 * ```
 *
 * SOCKS5 Protocol Flow:
 *   1. Client sends greeting: [VER, NMETHODS, METHODS...]
 *   2. Server selects method: [VER, METHOD]  (0x00 = No Auth)
 *   3. Client sends request: [VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT]
 *   4. Server responds:      [VER, REP, RSV, ATYP, BND.ADDR, BND.PORT]
 *
 * SOCKS4 Protocol Flow:
 *   1. Client sends: [VER, CMD, DSTPORT, DSTIP, USERID, 0x00]
 *   2. Server responds: [0x00, REP, DSTPORT, DSTIP]
 */
class SocksProxy(
    /** Port to bind the proxy server to. */
    private val bindPort: Int,

    /** SOCKS protocol version (4 or 5). */
    private val socksVersion: Int = 5,

    /** Optional statistics tracker. */
    private val statistics: TunnelStatistics? = null,

    /** Optional tunnel configuration for IP filtering. */
    private val config: TunnelConfig? = null,

    /** Bind address (default: 127.0.0.1 for security). */
    private val bindAddress: String = "127.0.0.1",

    /** Maximum number of concurrent proxy connections. */
    private val maxConnections: Int = 100
) {

    companion object {
        private const val TAG = "SocksProxy"

        // ── SOCKS5 Constants ────────────────────────────────────────────

        /** SOCKS protocol version. */
        private const val SOCKS_VERSION_5: Byte = 0x05
        private const val SOCKS_VERSION_4: Byte = 0x04

        /** SOCKS5 authentication methods. */
        private const val AUTH_NO_AUTH: Byte = 0x00
        private const val AUTH_GSSAPI: Byte = 0x01
        private const val AUTH_USERNAME_PASSWORD: Byte = 0x02
        private const val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()

        /** SOCKS5 command types. */
        private const val CMD_CONNECT: Byte = 0x01
        private const val CMD_BIND: Byte = 0x02
        private const val CMD_UDP_ASSOCIATE: Byte = 0x03

        /** SOCKS5 address types. */
        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04

        /** SOCKS5 reply codes. */
        private const val REP_SUCCEEDED: Byte = 0x00
        private const val REP_GENERAL_FAILURE: Byte = 0x01
        private const val REP_CONNECTION_NOT_ALLOWED: Byte = 0x02
        private const val REP_NETWORK_UNREACHABLE: Byte = 0x03
        private const val REP_HOST_UNREACHABLE: Byte = 0x04
        private const val REP_CONNECTION_REFUSED: Byte = 0x05
        private const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
        private const val REP_ADDRESS_NOT_SUPPORTED: Byte = 0x08

        // ── SOCKS4 Constants ────────────────────────────────────────────

        /** SOCKS4 reply codes. */
        private const val SOCKS4_GRANTED: Byte = 0x5A
        private const val SOCKS4_REJECTED: Byte = 0x5B
        private const val SOCKS4_IDENT_FAILED: Byte = 0x5C
        private const val SOCKS4_MISMATCH: Byte = 0x5D

        /** Buffer size for data relay. */
        private const val BUFFER_SIZE = 8192

        /** Timeout for connecting to the target host (ms). */
        private const val CONNECT_TIMEOUT_MS = 15000
    }

    // ── State ───────────────────────────────────────────────────────────

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val activeConnections = AtomicInteger(0)

    /**
     * Start the SOCKS proxy server.
     *
     * Binds to the configured address/port and begins accepting
     * connections on a background thread pool.
     *
     * @return true if the server started successfully.
     */
    fun start(): Boolean {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "SOCKS proxy already running on port $bindPort")
            return true
        }

        return try {
            val bindAddr = InetAddress.getByName(bindAddress)
            serverSocket = ServerSocket(bindPort, 50, bindAddr)
            executor = Executors.newCachedThreadPool()

            Log.i(TAG, "SOCKS$socksVersion proxy started on $bindAddress:$bindPort")

            // Accept loop on a dedicated thread
            Thread({
                acceptLoop()
            }, "SocksProxy-Acceptor-$bindPort").start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SOCKS proxy on port $bindPort", e)
            isRunning.set(false)
            false
        }
    }

    /**
     * Stop the SOCKS proxy server gracefully.
     *
     * Closes the server socket and shuts down the thread pool.
     * Active connections are allowed to finish within a timeout.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "Stopping SOCKS proxy on port $bindPort")

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }

        executor?.let {
            it.shutdown()
            if (!it.awaitTermination(5, TimeUnit.SECONDS)) {
                it.shutdownNow()
            }
        }

        serverSocket = null
        executor = null

        Log.i(TAG, "SOCKS proxy stopped")
    }

    /**
     * Check if the proxy is currently running.
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Get the number of currently active proxy connections.
     */
    fun getActiveConnectionCount(): Int = activeConnections.get()

    /**
     * Main accept loop. Spawns a new task for each incoming connection.
     */
    private fun acceptLoop() {
        val server = serverSocket ?: return

        while (isRunning.get()) {
            try {
                val clientSocket = server.accept()

                // Enforce max connections
                if (activeConnections.get() >= maxConnections) {
                    Log.w(TAG, "Max connections ($maxConnections) reached, rejecting: ${clientSocket.remoteSocketAddress}")
                    clientSocket.close()
                    continue
                }

                // Check IP filter
                val clientIp = (clientSocket.remoteSocketAddress as? InetSocketAddress)
                    ?.address?.hostAddress

                if (clientIp != null && config != null && !config.isIpAllowed(clientIp)) {
                    Log.w(TAG, "IP denied by filter: $clientIp")
                    clientSocket.close()
                    continue
                }

                activeConnections.incrementAndGet()
                executor?.submit {
                    try {
                        handleClient(clientSocket)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling client", e)
                        statistics?.recordError()
                    } finally {
                        activeConnections.decrementAndGet()
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Accept error", e)
                }
                // Socket closed during shutdown is expected
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected accept error", e)
            }
        }
    }

    /**
     * Handle a single SOCKS client connection.
     * Determines the SOCKS version from the first byte and dispatches
     * to the appropriate protocol handler.
     */
    private fun handleClient(clientSocket: Socket) {
        val clientInput = clientSocket.getInputStream()
        val clientOutput = clientSocket.getOutputStream()

        // Record the connection
        val clientAddr = clientSocket.remoteSocketAddress as? InetSocketAddress
        var connId: String? = null
        if (clientAddr != null && statistics != null) {
            connId = statistics.recordConnection(clientAddr)
        }

        try {
            // Peek at the first byte to determine SOCKS version
            clientSocket.soTimeout = 30000 // 30s handshake timeout

            val firstByte = clientInput.read()
            if (firstByte < 0) {
                Log.w(TAG, "Client closed connection before sending data")
                return
            }

            when (firstByte.toByte()) {
                SOCKS_VERSION_5 -> handleSocks5(firstByte, clientInput, clientOutput, connId)
                SOCKS_VERSION_4 -> handleSocks4(firstByte, clientInput, clientOutput, connId)
                else -> Log.w(TAG, "Unknown SOCKS version: $firstByte")
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Handshake timeout for ${clientSocket.remoteSocketAddress}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS client", e)
        } finally {
            connId?.let { statistics?.recordDisconnection(it) }
        }
    }

    // ── SOCKS5 Implementation ───────────────────────────────────────────

    /**
     * Handle a SOCKS5 connection.
     *
     * Flow:
     *   1. Read greeting (we already read the version byte)
     *   2. Select No Auth method
     *   3. Read request
     *   4. Process command (CONNECT or UDP ASSOCIATE)
     *   5. Relay data
     */
    private fun handleSocks5(
        versionByte: Int,
        input: InputStream,
        output: OutputStream,
        connId: String?
    ) {
        // Step 1: Read number of auth methods
        val numMethods = input.read()
        if (numMethods < 0) return

        val methods = ByteArray(numMethods)
        readFully(input, methods, numMethods)

        // Step 2: Select No Auth (0x00) if available
        val hasNoAuth = methods.any { it == AUTH_NO_AUTH }
        if (!hasNoAuth) {
            // No acceptable method
            output.write(byteArrayOf(SOCKS_VERSION_5, AUTH_NO_ACCEPTABLE))
            output.flush()
            return
        }

        // Send method selection: No Auth
        output.write(byteArrayOf(SOCKS_VERSION_5, AUTH_NO_AUTH))
        output.flush()

        // Step 3: Read SOCKS5 request
        val reqHeader = ByteArray(4)
        if (readFully(input, reqHeader, 4) < 4) return

        val ver = reqHeader[0]
        val cmd = reqHeader[1]
        // reqHeader[2] is RSV (reserved)
        val atyp = reqHeader[3]

        if (ver != SOCKS_VERSION_5) {
            sendSocks5Reply(output, REP_GENERAL_FAILURE)
            return
        }

        // Parse destination address
        val destAddress: String
        when (atyp) {
            ATYP_IPV4 -> {
                val addrBytes = ByteArray(4)
                if (readFully(input, addrBytes, 4) < 4) return
                destAddress = InetAddress.getByAddress(addrBytes).hostAddress
            }
            ATYP_DOMAIN -> {
                val domainLen = input.read()
                if (domainLen <= 0) return
                val domainBytes = ByteArray(domainLen)
                if (readFully(input, domainBytes, domainLen) < domainLen) return
                destAddress = String(domainBytes)
            }
            ATYP_IPV6 -> {
                val addrBytes = ByteArray(16)
                if (readFully(input, addrBytes, 16) < 16) return
                destAddress = InetAddress.getByAddress(addrBytes).hostAddress
            }
            else -> {
                sendSocks5Reply(output, REP_ADDRESS_NOT_SUPPORTED)
                return
            }
        }

        // Read destination port (2 bytes, big-endian)
        val portBytes = ByteArray(2)
        if (readFully(input, portBytes, 2) < 2) return
        val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // Step 4: Process command
        when (cmd) {
            CMD_CONNECT -> {
                handleSocks5Connect(output, destAddress, destPort, input, connId)
            }
            CMD_UDP_ASSOCIATE -> {
                handleSocks5UdpAssociate(output, input, destAddress, destPort, connId)
            }
            CMD_BIND -> {
                Log.w(TAG, "SOCKS5 BIND command not supported")
                sendSocks5Reply(output, REP_COMMAND_NOT_SUPPORTED)
            }
            else -> {
                Log.w(TAG, "SOCKS5 unknown command: $cmd")
                sendSocks5Reply(output, REP_COMMAND_NOT_SUPPORTED)
            }
        }
    }

    /**
     * Handle a SOCKS5 CONNECT request.
     * Establishes a TCP connection to the target and relays data.
     */
    private fun handleSocks5Connect(
        output: OutputStream,
        destAddress: String,
        destPort: Int,
        input: InputStream,
        connId: String?
    ) {
        Log.d(TAG, "SOCKS5 CONNECT to $destAddress:$destPort")

        val remoteSocket: Socket
        try {
            remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(destAddress, destPort), CONNECT_TIMEOUT_MS)
        } catch (e: ConnectException) {
            sendSocks5Reply(output, REP_CONNECTION_REFUSED)
            return
        } catch (e: SocketTimeoutException) {
            sendSocks5Reply(output, REP_HOST_UNREACHABLE)
            return
        } catch (e: Exception) {
            sendSocks5Reply(output, REP_NETWORK_UNREACHABLE)
            return
        }

        // Send success reply with bound address info
        val localAddr = remoteSocket.localAddress.address
        val localPort = remoteSocket.localPort
        val reply = ByteBuffer.allocate(10).apply {
            put(SOCKS_VERSION_5)     // VER
            put(REP_SUCCEEDED)       // REP
            put(0)                   // RSV
            put(ATYP_IPV4)           // ATYP
            put(localAddr)           // BND.ADDR (4 bytes)
            putShort(localPort.toShort()) // BND.PORT
        }.array()
        output.write(reply)
        output.flush()

        // Relay data between client and remote
        relayData(input, remoteSocket.getInputStream(), output, remoteSocket.getOutputStream(), connId)

        try { remoteSocket.close() } catch (_: Exception) {}
    }

    /**
     * Handle a SOCKS5 UDP ASSOCIATE request.
     * Provides basic UDP relay support.
     */
    private fun handleSocks5UdpAssociate(
        output: OutputStream,
        input: InputStream,
        destAddress: String,
        destPort: Int,
        connId: String?
    ) {
        Log.d(TAG, "SOCKS5 UDP ASSOCIATE for client association")

        // Open a UDP socket for relaying
        val udpSocket = DatagramSocket(0)
        val relayPort = udpSocket.localPort
        val relayAddress = InetAddress.getByName(bindAddress)

        // Send success reply with UDP relay address
        val reply = ByteBuffer.allocate(10).apply {
            put(SOCKS_VERSION_5)
            put(REP_SUCCEEDED)
            put(0)
            put(ATYP_IPV4)
            put(relayAddress.address)
            putShort(relayPort.toShort())
        }.array()
        output.write(reply)
        output.flush()

        // Start UDP relay in background threads
        val udpRelayRunning = AtomicBoolean(true)

        // Thread to relay UDP packets from client target back through our socket
        val relayThread = Thread({
            val buffer = ByteArray(65535)
            while (udpRelayRunning.get()) {
                try {
                    udpSocket.soTimeout = 1000
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)

                    // Track received bytes
                    statistics?.recordBytesReceived(packet.length.toLong())
                    connId?.let { cid ->
                        statistics?.getConnectionStats(cid)?.recordBytesReceived(packet.length.toLong())
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue
                } catch (e: Exception) {
                    if (udpRelayRunning.get()) {
                        Log.w(TAG, "UDP relay receive error", e)
                    }
                }
            }
        }, "SOCKS5-UDP-Relay-$bindPort")

        relayThread.start()

        // Keep the TCP connection alive while UDP is active
        try {
            val buf = ByteArray(1)
            while (input.read(buf) >= 0) {
                // Client TCP connection is kept open; when it closes, we shut down UDP
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            udpRelayRunning.set(false)
            udpSocket.close()
            try { relayThread.join(2000) } catch (_: Exception) {}
        }
    }

    /**
     * Send a SOCKS5 reply with no bound address.
     */
    private fun sendSocks5Reply(output: OutputStream, replyCode: Byte) {
        val reply = byteArrayOf(
            SOCKS_VERSION_5,  // VER
            replyCode,        // REP
            0,                // RSV
            ATYP_IPV4,        // ATYP
            0, 0, 0, 0,      // BND.ADDR
            0, 0              // BND.PORT
        )
        output.write(reply)
        output.flush()
    }

    // ── SOCKS4 Implementation ───────────────────────────────────────────

    /**
     * Handle a SOCKS4 connection.
     *
     * Flow:
     *   1. Read request (we already read the version byte)
     *   2. Parse destination and establish connection
     *   3. Relay data
     */
    private fun handleSocks4(
        versionByte: Int,
        input: InputStream,
        output: OutputStream,
        connId: String?
    ) {
        // Read command byte
        val cmd = input.read()
        if (cmd < 0) return

        // Read destination port (2 bytes, big-endian)
        val portBytes = ByteArray(2)
        if (readFully(input, portBytes, 2) < 2) return
        val destPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // Read destination IP (4 bytes)
        val ipBytes = ByteArray(4)
        if (readFully(input, ipBytes, 4) < 4) return

        // Check for SOCKS4a extension (IP starts with 0.0.0.x)
        var destAddress: String
        val isSocks4a = ipBytes[0] == 0.toByte() && ipBytes[1] == 0.toByte() &&
                        ipBytes[2] == 0.toByte() && ipBytes[3] != 0.toByte()

        // Read user ID (null-terminated string)
        val userId = StringBuilder()
        var b: Int
        while (input.read().also { b = it } > 0) {
            userId.append(b.toChar())
        }

        if (isSocks4a) {
            // Read domain name (null-terminated)
            val domain = StringBuilder()
            while (input.read().also { b = it } > 0) {
                domain.append(b.toChar())
            }
            destAddress = domain.toString()
        } else {
            destAddress = InetAddress.getByAddress(ipBytes).hostAddress
        }

        // Only support CONNECT command
        if (cmd != 1) {
            Log.w(TAG, "SOCKS4 unsupported command: $cmd")
            sendSocks4Reply(output, SOCKS4_REJECTED, destPort, ipBytes)
            return
        }

        Log.d(TAG, "SOCKS4 CONNECT to $destAddress:$destPort")

        // Establish connection to target
        val remoteSocket: Socket
        try {
            remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(destAddress, destPort), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            sendSocks4Reply(output, SOCKS4_REJECTED, destPort, ipBytes)
            return
        }

        // Send granted reply
        sendSocks4Reply(output, SOCKS4_GRANTED, destPort, ipBytes)

        // Relay data
        relayData(input, remoteSocket.getInputStream(), output, remoteSocket.getOutputStream(), connId)

        try { remoteSocket.close() } catch (_: Exception) {}
    }

    /**
     * Send a SOCKS4 reply.
     */
    private fun sendSocks4Reply(output: OutputStream, code: Byte, port: Int, ip: ByteArray) {
        val reply = ByteBuffer.allocate(8).apply {
            put(0)          // Null byte (not version)
            put(code)       // Result code
            putShort(port.toShort())  // Port
            put(ip)         // IP address
        }.array()
        output.write(reply)
        output.flush()
    }

    // ── Data Relay ──────────────────────────────────────────────────────

    /**
     * Bidirectional data relay between client and remote.
     * Uses two threads: one for client->remote and one for remote->client.
     *
     * @param clientInput   Client input stream (data FROM client).
     * @param remoteInput   Remote input stream (data FROM remote).
     * @param clientOutput  Client output stream (data TO client).
     * @param remoteOutput  Remote output stream (data TO remote).
     * @param connId        Optional connection ID for statistics tracking.
     */
    private fun relayData(
        clientInput: InputStream,
        remoteInput: InputStream,
        clientOutput: OutputStream,
        remoteOutput: OutputStream,
        connId: String?
    ) {
        val running = AtomicBoolean(true)

        // Thread: Client -> Remote
        val sendThread = Thread({
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    val read = clientInput.read(buffer)
                    if (read < 0) break

                    remoteOutput.write(buffer, 0, read)
                    remoteOutput.flush()

                    // Track statistics
                    statistics?.recordBytesSent(read.toLong())
                    connId?.let { cid ->
                        statistics?.getConnectionStats(cid)?.recordBytesSent(read.toLong())
                    }
                }
            } catch (e: Exception) {
                // Connection closed or error
            } finally {
                running.set(false)
            }
        }, "SOCKS-Relay-Send")

        // Thread: Remote -> Client
        val recvThread = Thread({
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    val read = remoteInput.read(buffer)
                    if (read < 0) break

                    clientOutput.write(buffer, 0, read)
                    clientOutput.flush()

                    // Track statistics
                    statistics?.recordBytesReceived(read.toLong())
                    connId?.let { cid ->
                        statistics?.getConnectionStats(cid)?.recordBytesReceived(read.toLong())
                    }
                }
            } catch (e: Exception) {
                // Connection closed or error
            } finally {
                running.set(false)
            }
        }, "SOCKS-Relay-Recv")

        sendThread.start()
        recvThread.start()

        // Wait for both threads to finish
        try {
            sendThread.join()
            recvThread.join()
        } catch (_: InterruptedException) {}
    }

    // ── Utility Methods ─────────────────────────────────────────────────

    /**
     * Read exactly [length] bytes from the input stream into [buffer]
     * starting at offset 0.
     *
     * @return Total bytes read, or -1 if the stream ended prematurely.
     */
    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) return -1
            offset += read
        }
        return offset
    }
}
