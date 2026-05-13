package com.termx.app.power.tunnel

import android.content.Context
import android.util.Log
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/**
 * Port forwarding and tunneling engine for TermX.
 *
 * Provides TCP/UDP port forwarding, SOCKS proxy, and reverse shell
 * capabilities directly from the terminal.
 *
 * Tunnel types:
 *   - Local forward:  Forwards local port to remote host:port
 *   - Remote forward: Forwards remote port to local service
 *   - Dynamic (SOCKS): SOCKS4/5 proxy on local port
 *   - Reverse shell:  Listens on port, provides shell on connection
 *
 * Shell usage:
 *   termx-tunnel forward <local_port> <remote_host> <remote_port>  Local port forward
 *   termx-tunnel reverse <remote_port> <local_host> <local_port>  Remote port forward
 *   termx-tunnel socks <local_port>                                SOCKS5 proxy
 *   termx-tunnel reverse-shell <port>                              Reverse shell
 *   termx-tunnel list                                              List active tunnels
 *   termx-tunnel stop <id>                                         Stop a tunnel
 *   termx-tunnel stop-all                                          Stop all tunnels
 *   termx-tunnel status [id]                                       Tunnel status
 *   termx-tunnel stats <id>                                        Traffic statistics
 */
class PortForwarder(private val context: Context) {

    companion object {
        private const val TAG = "PortForwarder"

        /** Buffer size for TCP relay. */
        private const val BUFFER_SIZE = 8192

        /** Timeout for connecting to remote host (ms). */
        private const val CONNECT_TIMEOUT_MS = 15000

        /** Timeout for SOCKS connection handshake (ms). */
        private const val HANDSHAKE_TIMEOUT_MS = 30000

        /** Maximum reconnection attempts for auto-reconnect tunnels. */
        private const val MAX_RECONNECT_ATTEMPTS = 10

        /** Delay between reconnection attempts (ms). */
        private const val RECONNECT_DELAY_MS = 3000L

        /** Filename for persistent tunnel configurations. */
        private const val PERSISTENT_CONFIG_FILE = "tunnel_configs.json"

        // ── Singleton Instance ──────────────────────────────────────────

        @Volatile
        private var instance: PortForwarder? = null

        /**
         * Get the singleton instance of PortForwarder.
         * Thread-safe double-checked locking.
         *
         * @param context Application context.
         * @return The PortForwarder instance.
         */
        fun getInstance(context: Context): PortForwarder {
            return instance ?: synchronized(this) {
                instance ?: PortForwarder(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ── Internal State ──────────────────────────────────────────────────

    /**
     * Map of active tunnels. Key is tunnel ID, value is the tunnel handle.
     * Thread-safe concurrent map for concurrent start/stop operations.
     */
    private val activeTunnels = ConcurrentHashMap<String, TunnelHandle>()

    /** Thread pool for tunnel accept loops and data relay. */
    private val tunnelExecutor = Executors.newCachedThreadPool { r ->
        Thread(r).apply {
            isDaemon = true
            name = "TermX-Tunnel-${System.currentTimeMillis()}"
        }
    }

    /** Scheduler for reconnection and maintenance tasks. */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TermX-Tunnel-Scheduler")
    }

    /** Whether the forwarder has been initialized with persistent configs. */
    private val initialized = AtomicBoolean(false)

    // ── Initialization ──────────────────────────────────────────────────

    /**
     * Initialize the port forwarder. Loads persistent tunnel configurations
     * and optionally auto-starts them.
     *
     * Should be called once during app startup.
     */
    fun initialize() {
        if (initialized.getAndSet(true)) return

        Log.i(TAG, "Initializing PortForwarder")
        loadPersistentConfigs()

        // Auto-start enabled persistent tunnels
        for ((id, handle) in activeTunnels) {
            if (handle.config.enabled && handle.config.isPersistent) {
                Log.d(TAG, "Auto-starting persistent tunnel: $id")
                startTunnel(id)
            }
        }
    }

    // ── Tunnel Lifecycle ────────────────────────────────────────────────

    /**
     * Create and register a new tunnel from a configuration.
     * Does not start the tunnel; call [startTunnel] to activate it.
     *
     * @param config The tunnel configuration.
     * @return The tunnel ID, or null if validation fails.
     */
    fun createTunnel(config: TunnelConfig): String? {
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            Log.e(TAG, "Invalid tunnel config: ${errors.joinToString(", ")}")
            return null
        }

        val handle = TunnelHandle(
            config = config,
            statistics = TunnelStatistics(config.id)
        )
        activeTunnels[config.id] = handle

        // Save if persistent
        if (config.isPersistent) {
            savePersistentConfigs()
        }

        Log.i(TAG, "Tunnel created: ${config.id} (${config.toDisplayString()})")
        return config.id
    }

    /**
     * Start a previously created tunnel.
     *
     * @param tunnelId The ID of the tunnel to start.
     * @return true if the tunnel started successfully.
     */
    fun startTunnel(tunnelId: String): Boolean {
        val handle = activeTunnels[tunnelId]
        if (handle == null) {
            Log.w(TAG, "Tunnel not found: $tunnelId")
            return false
        }

        if (handle.state.get() != TunnelState.STOPPED) {
            Log.w(TAG, "Tunnel $tunnelId is already running or starting")
            return false
        }

        handle.state.set(TunnelState.STARTING)
        handle.statistics.markStarted()

        val success = when (handle.config.type) {
            TunnelType.LOCAL_FORWARD -> startLocalForward(handle)
            TunnelType.REMOTE_FORWARD -> startRemoteForward(handle)
            TunnelType.SOCKS_PROXY -> startSocksProxy(handle)
            TunnelType.REVERSE_SHELL -> startReverseShell(handle)
        }

        if (success) {
            handle.state.set(TunnelState.RUNNING)
            Log.i(TAG, "Tunnel started: $tunnelId (${handle.config.toDisplayString()})")
        } else {
            handle.state.set(TunnelState.STOPPED)
            handle.statistics.markStopped()
            Log.e(TAG, "Failed to start tunnel: $tunnelId")
        }

        return success
    }

    /**
     * Stop a running tunnel.
     *
     * @param tunnelId The ID of the tunnel to stop.
     * @return true if the tunnel was stopped.
     */
    fun stopTunnel(tunnelId: String): Boolean {
        val handle = activeTunnels[tunnelId]
        if (handle == null) {
            Log.w(TAG, "Tunnel not found: $tunnelId")
            return false
        }

        if (handle.state.get() == TunnelState.STOPPED) {
            Log.w(TAG, "Tunnel $tunnelId is already stopped")
            return true
        }

        handle.state.set(TunnelState.STOPPING)
        shutdownTunnelHandle(handle)
        handle.state.set(TunnelState.STOPPED)
        handle.statistics.markStopped()

        Log.i(TAG, "Tunnel stopped: $tunnelId")
        return true
    }

    /**
     * Stop all active tunnels.
     *
     * @return Number of tunnels stopped.
     */
    fun stopAll(): Int {
        var count = 0
        for ((id, handle) in activeTunnels) {
            if (handle.state.get() != TunnelState.STOPPED) {
                stopTunnel(id)
                count++
            }
        }
        Log.i(TAG, "Stopped $count tunnel(s)")
        return count
    }

    /**
     * Remove a tunnel entirely (stops it first if running, then removes config).
     *
     * @param tunnelId The ID of the tunnel to remove.
     * @return true if the tunnel was removed.
     */
    fun removeTunnel(tunnelId: String): Boolean {
        val handle = activeTunnels.remove(tunnelId) ?: return false

        if (handle.state.get() != TunnelState.STOPPED) {
            shutdownTunnelHandle(handle)
            handle.statistics.markStopped()
        }

        savePersistentConfigs()
        Log.i(TAG, "Tunnel removed: $tunnelId")
        return true
    }

    /**
     * Restart a tunnel (stop then start).
     *
     * @param tunnelId The ID of the tunnel to restart.
     * @return true if the tunnel restarted successfully.
     */
    fun restartTunnel(tunnelId: String): Boolean {
        stopTunnel(tunnelId)
        Thread.sleep(500) // Brief pause for socket cleanup
        return startTunnel(tunnelId)
    }

    // ── Tunnel Type Implementations ─────────────────────────────────────

    /**
     * Start a local port forward (SSH -L style).
     * Listens on localPort and forwards all connections to remoteHost:remotePort.
     */
    private fun startLocalForward(handle: TunnelHandle): Boolean {
        val config = handle.config
        val serverSocket: ServerSocket

        try {
            val bindAddr = InetAddress.getByName(config.localHost)
            serverSocket = ServerSocket(config.localPort, 50, bindAddr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind local port ${config.localPort}", e)
            handle.statistics.recordError()
            return false
        }

        handle.serverSocket = serverSocket
        val running = AtomicBoolean(true)
        handle.runningFlag = running

        tunnelExecutor.submit {
            Log.d(TAG, "Local forward listening on ${config.localHost}:${config.localPort}")

            while (running.get()) {
                try {
                    val clientSocket = serverSocket.accept()

                    // Check max connections
                    if (config.maxConnections > 0 && handle.statistics.getConnectionsActive() >= config.maxConnections) {
                        Log.w(TAG, "Max connections reached, rejecting")
                        clientSocket.close()
                        continue
                    }

                    // Check IP filter
                    val clientIp = (clientSocket.remoteSocketAddress as? InetSocketAddress)
                        ?.address?.hostAddress
                    if (clientIp != null && !config.isIpAllowed(clientIp)) {
                        Log.w(TAG, "IP denied: $clientIp")
                        clientSocket.close()
                        continue
                    }

                    // Record connection
                    val clientAddr = clientSocket.remoteSocketAddress as? InetSocketAddress
                    val connId = if (clientAddr != null) {
                        handle.statistics.recordConnection(clientAddr)
                    } else null

                    // Connect to remote
                    tunnelExecutor.submit {
                        var remoteSocket: Socket? = null
                        try {
                            remoteSocket = Socket()
                            remoteSocket.connect(
                                InetSocketAddress(config.remoteHost, config.remotePort),
                                CONNECT_TIMEOUT_MS
                            )

                            // Relay data bidirectionally
                            relayTcp(
                                clientSocket, remoteSocket,
                                handle, connId, config.bandwidthLimitBps
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to connect to remote ${config.remoteHost}:${config.remotePort}", e)
                            handle.statistics.recordError()
                        } finally {
                            try { clientSocket.close() } catch (_: Exception) {}
                            try { remoteSocket?.close() } catch (_: Exception) {}
                            connId?.let { handle.statistics.recordDisconnection(it) }
                        }
                    }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected accept error", e)
                    handle.statistics.recordError()
                }
            }
        }

        return true
    }

    /**
     * Start a remote port forward (SSH -R style).
     * In this context, "remote forward" means accepting connections on
     * a port and forwarding them to a local service.
     * Note: True remote forwarding requires a remote SSH server; this
     * implementation works when acting as the server side.
     */
    private fun startRemoteForward(handle: TunnelHandle): Boolean {
        val config = handle.config

        // Remote forward in standalone mode: listen on remotePort,
        // forward to localHost:localPort
        val listenPort = if (config.remotePort > 0) config.remotePort else config.localPort
        val serverSocket: ServerSocket

        try {
            serverSocket = ServerSocket(listenPort, 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind remote forward port $listenPort", e)
            handle.statistics.recordError()
            return false
        }

        handle.serverSocket = serverSocket
        val running = AtomicBoolean(true)
        handle.runningFlag = running

        tunnelExecutor.submit {
            Log.d(TAG, "Remote forward listening on :$listenPort -> ${config.localHost}:${config.localPort}")

            while (running.get()) {
                try {
                    val clientSocket = serverSocket.accept()

                    // Check IP filter
                    val clientIp = (clientSocket.remoteSocketAddress as? InetSocketAddress)
                        ?.address?.hostAddress
                    if (clientIp != null && !config.isIpAllowed(clientIp)) {
                        clientSocket.close()
                        continue
                    }

                    val clientAddr = clientSocket.remoteSocketAddress as? InetSocketAddress
                    val connId = if (clientAddr != null) {
                        handle.statistics.recordConnection(clientAddr)
                    } else null

                    // Connect to local service
                    tunnelExecutor.submit {
                        var localSocket: Socket? = null
                        try {
                            localSocket = Socket()
                            localSocket.connect(
                                InetSocketAddress(config.localHost, config.localPort),
                                CONNECT_TIMEOUT_MS
                            )

                            relayTcp(
                                clientSocket, localSocket,
                                handle, connId, config.bandwidthLimitBps
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to connect to local ${config.localHost}:${config.localPort}", e)
                            handle.statistics.recordError()
                        } finally {
                            try { clientSocket.close() } catch (_: Exception) {}
                            try { localSocket?.close() } catch (_: Exception) {}
                            connId?.let { handle.statistics.recordDisconnection(it) }
                        }
                    }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected accept error", e)
                    handle.statistics.recordError()
                }
            }
        }

        return true
    }

    /**
     * Start a SOCKS proxy on the configured local port.
     * Delegates to the [SocksProxy] implementation.
     */
    private fun startSocksProxy(handle: TunnelHandle): Boolean {
        val config = handle.config
        val proxy = SocksProxy(
            bindPort = config.localPort,
            socksVersion = config.socksVersion,
            statistics = handle.statistics,
            config = config,
            bindAddress = config.localHost,
            maxConnections = if (config.maxConnections > 0) config.maxConnections else 100
        )

        handle.socksProxy = proxy
        val started = proxy.start()

        if (started) {
            // Monitor thread to detect if proxy stops unexpectedly
            handle.runningFlag = AtomicBoolean(true)
            tunnelExecutor.submit {
                while (handle.runningFlag!!.get()) {
                    if (!proxy.isRunning()) {
                        Log.w(TAG, "SOCKS proxy stopped unexpectedly for tunnel ${config.id}")
                        handle.statistics.recordError()
                        if (config.autoReconnect) {
                            scheduleReconnect(handle)
                        }
                        break
                    }
                    Thread.sleep(2000)
                }
            }
        }

        return started
    }

    /**
     * Start a reverse shell listener.
     * Listens on the configured port and spawns a shell process
     * for each incoming connection.
     */
    private fun startReverseShell(handle: TunnelHandle): Boolean {
        val config = handle.config
        val serverSocket: ServerSocket

        try {
            val bindAddr = InetAddress.getByName(config.localHost)
            serverSocket = ServerSocket(config.localPort, 50, bindAddr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind reverse shell port ${config.localPort}", e)
            handle.statistics.recordError()
            return false
        }

        handle.serverSocket = serverSocket
        val running = AtomicBoolean(true)
        handle.runningFlag = running

        tunnelExecutor.submit {
            Log.d(TAG, "Reverse shell listening on ${config.localHost}:${config.localPort}")

            while (running.get()) {
                try {
                    val clientSocket = serverSocket.accept()

                    // Check IP filter
                    val clientIp = (clientSocket.remoteSocketAddress as? InetSocketAddress)
                        ?.address?.hostAddress
                    if (clientIp != null && !config.isIpAllowed(clientIp)) {
                        Log.w(TAG, "IP denied for reverse shell: $clientIp")
                        clientSocket.close()
                        continue
                    }

                    // Record connection
                    val clientAddr = clientSocket.remoteSocketAddress as? InetSocketAddress
                    val connId = if (clientAddr != null) {
                        handle.statistics.recordConnection(clientAddr)
                    } else null

                    // Spawn shell for this connection
                    tunnelExecutor.submit {
                        var process: Process? = null
                        try {
                            Log.i(TAG, "Reverse shell connection from ${clientSocket.remoteSocketAddress}")

                            // Launch a shell process
                            val procBuilder = ProcessBuilder("/system/bin/sh", "-i")
                            procBuilder.redirectErrorStream(true)
                            process = procBuilder.start()

                            val shellInput = process!!.outputStream
                            val shellOutput = process!!.inputStream

                            // Thread: Shell output -> Client
                            val outputThread = Thread({
                                try {
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        val read = shellOutput.read(buffer)
                                        if (read < 0) break
                                        clientSocket.getOutputStream().write(buffer, 0, read)
                                        clientSocket.getOutputStream().flush()
                                        handle.statistics.recordBytesSent(read.toLong())
                                        connId?.let { cid ->
                                            handle.statistics.getConnectionStats(cid)
                                                ?.recordBytesSent(read.toLong())
                                        }
                                    }
                                } catch (_: Exception) {}
                            }, "RevShell-Output")

                            // Thread: Client -> Shell input
                            val inputThread = Thread({
                                try {
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        val read = clientSocket.getInputStream().read(buffer)
                                        if (read < 0) break
                                        shellInput.write(buffer, 0, read)
                                        shellInput.flush()
                                        handle.statistics.recordBytesReceived(read.toLong())
                                        connId?.let { cid ->
                                            handle.statistics.getConnectionStats(cid)
                                                ?.recordBytesReceived(read.toLong())
                                        }
                                    }
                                } catch (_: Exception) {}
                            }, "RevShell-Input")

                            outputThread.start()
                            inputThread.start()

                            // Wait for process to exit or client to disconnect
                            process!!.waitFor()
                            outputThread.join(1000)
                            inputThread.join(1000)

                        } catch (e: Exception) {
                            Log.e(TAG, "Reverse shell error", e)
                            handle.statistics.recordError()
                        } finally {
                            process?.destroy()
                            try { clientSocket.close() } catch (_: Exception) {}
                            connId?.let { handle.statistics.recordDisconnection(it) }
                        }
                    }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected accept error", e)
                    handle.statistics.recordError()
                }
            }
        }

        return true
    }

    // ── TCP Relay with Bandwidth Throttling ─────────────────────────────

    /**
     * Relay data bidirectionally between two TCP sockets with
     * optional bandwidth throttling.
     *
     * Uses two threads for full-duplex relay. Implements a simple
     * token-bucket style bandwidth limiter when bandwidthLimitBps > 0.
     *
     * @param client     The client-side socket.
     * @param remote     The remote-side socket.
     * @param handle     The tunnel handle for statistics.
     * @param connId     Optional connection ID for per-connection stats.
     * @param bandwidthLimitBps  Bandwidth limit in bytes/sec (0 = unlimited).
     */
    private fun relayTcp(
        client: Socket,
        remote: Socket,
        handle: TunnelHandle,
        connId: String?,
        bandwidthLimitBps: Long
    ) {
        val running = AtomicBoolean(true)

        // Thread: Client -> Remote
        val sendThread = Thread({
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    val read = client.getInputStream().read(buffer)
                    if (read < 0) break

                    remote.getOutputStream().write(buffer, 0, read)
                    remote.getOutputStream().flush()

                    // Throttle if bandwidth limit is set
                    if (bandwidthLimitBps > 0) {
                        throttle(read.toLong(), bandwidthLimitBps)
                    }

                    // Record statistics
                    handle.statistics.recordBytesSent(read.toLong())
                    connId?.let { cid ->
                        handle.statistics.getConnectionStats(cid)?.recordBytesSent(read.toLong())
                    }
                }
            } catch (_: Exception) {
            } finally {
                running.set(false)
            }
        }, "Relay-Send")

        // Thread: Remote -> Client
        val recvThread = Thread({
            try {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    val read = remote.getInputStream().read(buffer)
                    if (read < 0) break

                    client.getOutputStream().write(buffer, 0, read)
                    client.getOutputStream().flush()

                    // Throttle if bandwidth limit is set
                    if (bandwidthLimitBps > 0) {
                        throttle(read.toLong(), bandwidthLimitBps)
                    }

                    // Record statistics
                    handle.statistics.recordBytesReceived(read.toLong())
                    connId?.let { cid ->
                        handle.statistics.getConnectionStats(cid)?.recordBytesReceived(read.toLong())
                    }
                }
            } catch (_: Exception) {
            } finally {
                running.set(false)
            }
        }, "Relay-Recv")

        sendThread.start()
        recvThread.start()

        try {
            sendThread.join()
            recvThread.join()
        } catch (_: InterruptedException) {}
    }

    /**
     * Simple bandwidth throttling using a sleep-based approach.
     * Calculates the time it should take to send [bytes] at the
     * configured bandwidth limit and sleeps for the difference.
     *
     * @param bytes            Number of bytes just sent/received.
     * @param bandwidthLimitBps Maximum bytes per second.
     */
    private fun throttle(bytes: Long, bandwidthLimitBps: Long) {
        if (bandwidthLimitBps <= 0) return

        // Calculate how long this many bytes should take at the limit
        val expectedTimeMs = (bytes * 1000) / bandwidthLimitBps
        if (expectedTimeMs > 0) {
            try {
                Thread.sleep(expectedTimeMs)
            } catch (_: InterruptedException) {}
        }
    }

    // ── UDP Forwarding ──────────────────────────────────────────────────

    /**
     * Start a UDP forward tunnel.
     * Forwards UDP packets from a local port to a remote host:port.
     *
     * @param config The tunnel configuration (must have protocol = UDP).
     * @return The tunnel ID, or null on failure.
     */
    fun createUdpForward(config: TunnelConfig): String? {
        if (config.protocol != Protocol.UDP) {
            Log.e(TAG, "UDP forward requires UDP protocol")
            return null
        }

        val id = createTunnel(config) ?: return null
        val handle = activeTunnels[id] ?: return null

        handle.state.set(TunnelState.STARTING)
        handle.statistics.markStarted()

        try {
            val localSocket = DatagramSocket(config.localPort, InetAddress.getByName(config.localHost))
            handle.datagramSocket = localSocket

            val remoteAddress = InetSocketAddress(config.remoteHost, config.remotePort)
            val running = AtomicBoolean(true)
            handle.runningFlag = running

            handle.state.set(TunnelState.RUNNING)

            // Thread: Local -> Remote
            tunnelExecutor.submit {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        localSocket.receive(packet)

                        // Forward to remote
                        val sendPacket = DatagramPacket(
                            packet.data, packet.offset, packet.length,
                            remoteAddress
                        )
                        localSocket.send(sendPacket)

                        handle.statistics.recordBytesSent(packet.length.toLong())
                    } catch (e: SocketTimeoutException) {
                        // Normal
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.e(TAG, "UDP forward error (local->remote)", e)
                            handle.statistics.recordError()
                        }
                    }
                }
            }

            // Thread: Remote -> Local
            tunnelExecutor.submit {
                val buffer = ByteArray(BUFFER_SIZE)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        localSocket.receive(packet)

                        // Forward back to original sender (handled by the same socket)
                        handle.statistics.recordBytesReceived(packet.length.toLong())
                    } catch (e: SocketTimeoutException) {
                        // Normal
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.e(TAG, "UDP forward error (remote->local)", e)
                        }
                    }
                }
            }

            Log.i(TAG, "UDP forward started: ${config.localPort} -> ${config.remoteHost}:${config.remotePort}")
            return id

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP forward", e)
            handle.state.set(TunnelState.STOPPED)
            handle.statistics.recordError()
            return null
        }
    }

    // ── Auto-Reconnect ──────────────────────────────────────────────────

    /**
     * Schedule a reconnection attempt for a tunnel with autoReconnect enabled.
     * Uses exponential backoff with a maximum number of attempts.
     */
    private fun scheduleReconnect(handle: TunnelHandle) {
        if (!handle.config.autoReconnect) return

        val id = handle.config.id
        handle.reconnectAttempts.incrementAndGet()

        val attempts = handle.reconnectAttempts.get()
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached for tunnel $id")
            handle.state.set(TunnelState.ERROR)
            return
        }

        val delay = RECONNECT_DELAY_MS * (1L shl minOf(attempts - 1, 5)) // Exponential backoff
        Log.i(TAG, "Scheduling reconnect for tunnel $id in ${delay}ms (attempt $attempts)")

        scheduler.schedule({
            if (handle.state.get() == TunnelState.STOPPED || handle.state.get() == TunnelState.ERROR) {
                Log.i(TAG, "Attempting to reconnect tunnel $id")
                startTunnel(id)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    // ── Status and Query Methods ────────────────────────────────────────

    /**
     * List all registered tunnels with their current status.
     *
     * @return A formatted list string.
     */
    fun listTunnels(): String {
        if (activeTunnels.isEmpty()) {
            return "No tunnels configured."
        }

        return buildString {
            appendLine("Active Tunnels (${activeTunnels.size}):")
            appendLine("─".repeat(60))
            for ((id, handle) in activeTunnels) {
                val state = handle.state.get()
                val stateIcon = when (state) {
                    TunnelState.RUNNING -> "●"
                    TunnelState.STARTING -> "◐"
                    TunnelState.STOPPING -> "◑"
                    TunnelState.STOPPED -> "○"
                    TunnelState.ERROR -> "✖"
                }
                val proto = if (handle.config.protocol == Protocol.UDP) " [UDP]" else ""
                appendLine("$stateIcon [$id] ${handle.config.toDisplayString()}$proto - $state")
                if (handle.config.name.isNotBlank()) {
                    appendLine("  Name: ${handle.config.name}")
                }
                val active = handle.statistics.getConnectionsActive()
                val accepted = handle.statistics.getConnectionsAccepted()
                if (accepted > 0) {
                    appendLine("  Connections: $active active / $accepted total")
                }
            }
        }
    }

    /**
     * Get the status of a specific tunnel.
     *
     * @param tunnelId The tunnel ID (optional; if null, returns all).
     * @return A formatted status string.
     */
    fun getStatus(tunnelId: String? = null): String {
        if (tunnelId != null) {
            val handle = activeTunnels[tunnelId]
                ?: return "Tunnel not found: $tunnelId"
            return formatTunnelStatus(handle)
        }

        return buildString {
            for ((id, handle) in activeTunnels) {
                appendLine(formatTunnelStatus(handle))
                appendLine()
            }
        }
    }

    /**
     * Format a detailed status string for a single tunnel.
     */
    private fun formatTunnelStatus(handle: TunnelHandle): String {
        val config = handle.config
        val stats = handle.statistics

        return buildString {
            appendLine("Tunnel: ${config.name.ifBlank { config.id }}")
            appendLine("  ID:     ${config.id}")
            appendLine("  Type:   ${config.type.displayName}")
            appendLine("  State:  ${handle.state.get()}")
            appendLine("  Bind:   ${config.localHost}:${config.localPort}")
            if (config.type == TunnelType.LOCAL_FORWARD || config.type == TunnelType.REMOTE_FORWARD) {
                appendLine("  Target: ${config.remoteHost}:${config.remotePort}")
            }
            appendLine("  Proto:  ${config.protocol.displayName}")
            if (config.type == TunnelType.SOCKS_PROXY) {
                appendLine("  SOCKS:  v${config.socksVersion}")
            }
            if (config.bandwidthLimitBps > 0) {
                appendLine("  BW Limit: ${config.bandwidthLimitBps} B/s")
            }
            appendLine("  Connections: ${stats.getConnectionsActive()} active / ${stats.getConnectionsAccepted()} total")
            appendLine("  Bytes:   ${stats.getBytesSent()} sent / ${stats.getBytesReceived()} received")
            appendLine("  Errors:  ${stats.getErrorCount()}")
            appendLine("  Uptime:  ${formatDuration(stats.getDurationMs())}")
            appendLine("  Persistent: ${config.isPersistent}")
            appendLine("  Auto-Reconnect: ${config.autoReconnect}")
        }
    }

    /**
     * Get traffic statistics for a specific tunnel.
     *
     * @param tunnelId The tunnel ID.
     * @return A formatted statistics string.
     */
    fun getStats(tunnelId: String): String {
        val handle = activeTunnels[tunnelId]
            ?: return "Tunnel not found: $tunnelId"
        return handle.statistics.getSummary()
    }

    /**
     * Export tunnel statistics as JSON.
     *
     * @param tunnelId The tunnel ID.
     * @return JSON string of statistics.
     */
    fun exportStatsJSON(tunnelId: String): String {
        val handle = activeTunnels[tunnelId]
            ?: return "{\"error\": \"Tunnel not found: $tunnelId\"}"
        return handle.statistics.toJSONString()
    }

    /**
     * Get all active tunnel IDs.
     */
    fun getActiveTunnelIds(): List<String> {
        return activeTunnels.filter {
            it.value.state.get() == TunnelState.RUNNING
        }.keys.toList()
    }

    /**
     * Get all registered tunnel IDs (including stopped ones).
     */
    fun getAllTunnelIds(): List<String> {
        return activeTunnels.keys.toList()
    }

    /**
     * Get the tunnel configuration for a given ID.
     */
    fun getTunnelConfig(tunnelId: String): TunnelConfig? {
        return activeTunnels[tunnelId]?.config
    }

    /**
     * Get the tunnel state for a given ID.
     */
    fun getTunnelState(tunnelId: String): TunnelState? {
        return activeTunnels[tunnelId]?.state?.get()
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    /**
     * Shut down all resources associated with a tunnel handle.
     */
    private fun shutdownTunnelHandle(handle: TunnelHandle) {
        // Stop the running flag
        handle.runningFlag?.set(false)

        // Close server socket
        try {
            handle.serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        handle.serverSocket = null

        // Stop SOCKS proxy if applicable
        handle.socksProxy?.stop()
        handle.socksProxy = null

        // Close datagram socket if applicable
        try {
            handle.datagramSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing datagram socket", e)
        }
        handle.datagramSocket = null
    }

    /**
     * Shutdown the port forwarder entirely.
     * Stops all tunnels and releases resources.
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down PortForwarder")
        stopAll()
        tunnelExecutor.shutdown()
        scheduler.shutdown()
        try {
            if (!tunnelExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                tunnelExecutor.shutdownNow()
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (_: InterruptedException) {}
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * Save persistent tunnel configurations to internal storage.
     * Only tunnels with [TunnelConfig.isPersistent] = true are saved.
     */
    private fun savePersistentConfigs() {
        try {
            val persistentConfigs = activeTunnels.values
                .filter { it.config.isPersistent }
                .map { it.config.toJSON() }

            val jsonArray = org.json.JSONArray(persistentConfigs)
            val jsonStr = jsonArray.toString(2)

            val file = File(context.filesDir, PERSISTENT_CONFIG_FILE)
            file.writeText(jsonStr)

            Log.d(TAG, "Saved ${persistentConfigs.size} persistent tunnel configs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save persistent configs", e)
        }
    }

    /**
     * Load persistent tunnel configurations from internal storage.
     */
    private fun loadPersistentConfigs() {
        try {
            val file = File(context.filesDir, PERSISTENT_CONFIG_FILE)
            if (!file.exists()) return

            val jsonStr = file.readText()
            val jsonArray = org.json.JSONArray(jsonStr)
            val configs = TunnelConfig.fromJSONArray(jsonArray)

            for (config in configs) {
                val handle = TunnelHandle(
                    config = config,
                    statistics = TunnelStatistics(config.id)
                )
                handle.state.set(TunnelState.STOPPED)
                activeTunnels[config.id] = handle
            }

            Log.i(TAG, "Loaded ${configs.size} persistent tunnel configs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persistent configs", e)
        }
    }

    // ── CLI Command Handling ────────────────────────────────────────────

    /**
     * Process a CLI command for the tunnel subsystem.
     *
     * This method is the main entry point when the user runs
     * `termx-tunnel <command> [args...]` from the terminal.
     *
     * @param args Command-line arguments.
     * @return Output string to display in the terminal.
     */
    fun processCommand(args: List<String>): String {
        if (args.isEmpty()) {
            return getUsage()
        }

        return when (args[0]) {
            "forward", "local", "l" -> cmdForward(args)
            "reverse", "remote", "r" -> cmdReverse(args)
            "socks", "dynamic", "d" -> cmdSocks(args)
            "reverse-shell", "revshell", "rs" -> cmdReverseShell(args)
            "udp" -> cmdUdp(args)
            "list", "ls" -> listTunnels()
            "stop" -> cmdStop(args)
            "stop-all" -> {
                val count = stopAll()
                "Stopped $count tunnel(s)."
            }
            "start" -> cmdStart(args)
            "restart" -> cmdRestart(args)
            "status" -> cmdStatus(args)
            "stats" -> cmdStats(args)
            "remove", "rm" -> cmdRemove(args)
            "help" -> getUsage()
            else -> "Unknown command: ${args[0]}\n${getUsage()}"
        }
    }

    private fun cmdForward(args: List<String>): String {
        if (args.size < 4) {
            return "Usage: termx-tunnel forward <local_port> <remote_host> <remote_port>"
        }
        val localPort = args[1].toIntOrNull() ?: return "Invalid local port: ${args[1]}"
        val remoteHost = args[2]
        val remotePort = args[3].toIntOrNull() ?: return "Invalid remote port: ${args[3]}"

        val config = TunnelConfig.localForward(localPort, remoteHost, remotePort)
        val id = createTunnel(config) ?: return "Failed to create tunnel (port in use or invalid config)"
        val started = startTunnel(id)
        return if (started) "Local forward started: $localPort -> $remoteHost:$remotePort (id: $id)"
        else "Failed to start local forward"
    }

    private fun cmdReverse(args: List<String>): String {
        if (args.size < 4) {
            return "Usage: termx-tunnel reverse <remote_port> <local_host> <local_port>"
        }
        val remotePort = args[1].toIntOrNull() ?: return "Invalid remote port: ${args[1]}"
        val localHost = args[2]
        val localPort = args[3].toIntOrNull() ?: return "Invalid local port: ${args[3]}"

        val config = TunnelConfig.remoteForward(remotePort, localHost, localPort)
        val id = createTunnel(config) ?: return "Failed to create tunnel"
        val started = startTunnel(id)
        return if (started) "Remote forward started: :$remotePort -> $localHost:$localPort (id: $id)"
        else "Failed to start remote forward"
    }

    private fun cmdSocks(args: List<String>): String {
        if (args.size < 2) {
            return "Usage: termx-tunnel socks <local_port> [4|5]"
        }
        val localPort = args[1].toIntOrNull() ?: return "Invalid local port: ${args[1]}"
        val version = if (args.size >= 3) args[2].toIntOrNull() ?: 5 else 5

        val config = TunnelConfig.socksProxy(localPort, version)
        val id = createTunnel(config) ?: return "Failed to create tunnel"
        val started = startTunnel(id)
        return if (started) "SOCKS$version proxy started on :$localPort (id: $id)"
        else "Failed to start SOCKS proxy"
    }

    private fun cmdReverseShell(args: List<String>): String {
        if (args.size < 2) {
            return "Usage: termx-tunnel reverse-shell <port>"
        }
        val port = args[1].toIntOrNull() ?: return "Invalid port: ${args[1]}"

        val config = TunnelConfig.reverseShell(port)
        val id = createTunnel(config) ?: return "Failed to create tunnel"
        val started = startTunnel(id)
        return if (started) "Reverse shell listening on :$port (id: $id)"
        else "Failed to start reverse shell"
    }

    private fun cmdUdp(args: List<String>): String {
        if (args.size < 4) {
            return "Usage: termx-tunnel udp <local_port> <remote_host> <remote_port>"
        }
        val localPort = args[1].toIntOrNull() ?: return "Invalid local port: ${args[1]}"
        val remoteHost = args[2]
        val remotePort = args[3].toIntOrNull() ?: return "Invalid remote port: ${args[3]}"

        val config = TunnelConfig.localForward(localPort, remoteHost, remotePort).apply {
            protocol = Protocol.UDP
        }
        val id = createUdpForward(config) ?: return "Failed to create UDP tunnel"
        return "UDP forward started: $localPort -> $remoteHost:$remotePort (id: $id)"
    }

    private fun cmdStop(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-tunnel stop <id>"
        val id = args[1]
        val stopped = stopTunnel(id)
        return if (stopped) "Tunnel $id stopped." else "Failed to stop tunnel $id."
    }

    private fun cmdStart(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-tunnel start <id>"
        val id = args[1]
        val started = startTunnel(id)
        return if (started) "Tunnel $id started." else "Failed to start tunnel $id."
    }

    private fun cmdRestart(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-tunnel restart <id>"
        val id = args[1]
        val restarted = restartTunnel(id)
        return if (restarted) "Tunnel $id restarted." else "Failed to restart tunnel $id."
    }

    private fun cmdStatus(args: List<String>): String {
        val id = args.getOrNull(1)
        return getStatus(id)
    }

    private fun cmdStats(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-tunnel stats <id>"
        return getStats(args[1])
    }

    private fun cmdRemove(args: List<String>): String {
        if (args.size < 2) return "Usage: termx-tunnel remove <id>"
        val id = args[1]
        val removed = removeTunnel(id)
        return if (removed) "Tunnel $id removed." else "Tunnel $id not found."
    }

    /**
     * Get CLI usage help text.
     */
    fun getUsage(): String {
        return """
            |TermX Tunnel - Port Forwarding & Tunneling
            |
            |Usage: termx-tunnel <command> [arguments]
            |
            |Commands:
            |  forward <lport> <rhost> <rport>   Local port forward (TCP)
            |  reverse <rport> <lhost> <lport>   Remote port forward (TCP)
            |  socks <lport> [4|5]               SOCKS4/5 proxy
            |  reverse-shell <port>               Reverse shell listener
            |  udp <lport> <rhost> <rport>       UDP port forward
            |  list                               List all tunnels
            |  start <id>                         Start a tunnel
            |  stop <id>                          Stop a tunnel
            |  restart <id>                       Restart a tunnel
            |  stop-all                           Stop all tunnels
            |  status [id]                        Tunnel status
            |  stats <id>                         Traffic statistics
            |  remove <id>                        Remove a tunnel
            |  help                               Show this help
            |
            |Tunnel States: ● Running  ◐ Starting  ◑ Stopping  ○ Stopped  ✖ Error
        """.trimMargin()
    }

    // ── Utility ─────────────────────────────────────────────────────────

    /**
     * Format a duration in milliseconds into a human-readable string.
     */
    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h ${minutes % 60}m"
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

// ── Supporting Types ────────────────────────────────────────────────────

/**
 * Internal handle for an active tunnel.
 * Holds all runtime state: the server socket, statistics, SOCKS proxy
 * instance, running flag, and reconnection counter.
 */
class TunnelHandle(
    /** The tunnel configuration. */
    val config: TunnelConfig,

    /** Traffic statistics for this tunnel. */
    val statistics: TunnelStatistics
) {
    /** Current state of the tunnel. */
    val state: AtomicReference<TunnelState> = AtomicReference(TunnelState.STOPPED)

    /** Server socket for TCP-based tunnels. */
    @Volatile
    var serverSocket: ServerSocket? = null

    /** Datagram socket for UDP-based tunnels. */
    @Volatile
    var datagramSocket: DatagramSocket? = null

    /** SOCKS proxy instance (only for SOCKS_PROXY type). */
    @Volatile
    var socksProxy: SocksProxy? = null

    /** Flag to signal the accept loop to stop. */
    @Volatile
    var runningFlag: AtomicBoolean? = null

    /** Number of reconnection attempts since last successful start. */
    val reconnectAttempts: AtomicInteger = AtomicInteger(0)
}

/**
 * Enum representing the runtime state of a tunnel.
 */
enum class TunnelState(val displayName: String) {
    STOPPED("Stopped"),
    STARTING("Starting"),
    RUNNING("Running"),
    STOPPING("Stopping"),
    ERROR("Error")
}
