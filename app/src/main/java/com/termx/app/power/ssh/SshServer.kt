package com.termx.app.power.ssh

import android.content.Context
import android.util.Log
import java.io.*
import java.net.*
import java.security.*
import java.security.spec.*
import java.math.BigInteger
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.*
import javax.crypto.spec.*

/**
 * Built-in SSH/SFTP server for TermX.
 *
 * Provides secure remote shell access and file transfer capabilities.
 * Allows connecting to the device's terminal session from a remote computer
 * using any standard SSH client.
 *
 * Features:
 *   - SSH v2 protocol server on configurable port (default 8022)
 *   - Password and public key authentication
 *   - Interactive shell access (connects to TermX PTY)
 *   - SFTP subsystem for file transfer
 *   - SCP support for file copy
 *   - Multiple concurrent sessions
 *   - Port forwarding (local/remote)
 *   - Host key management
 *
 * Shell usage:
 *   termx-ssh start [port]          Start SSH server
 *   termx-ssh stop                  Stop SSH server
 *   termx-ssh status                Show server status
 *   termx-ssh keygen [type]         Generate host key
 *   termx-ssh keys                  List authorized keys
 *   termx-ssh key-add <pubkey>      Add authorized public key
 *   termx-ssh key-remove <pubkey>   Remove authorized key
 *   termx-ssh sessions              List active sessions
 *   termx-ssh config                Show/edit SSH config
 */
class SshServer(private val context: Context) {

    companion object {
        private const val TAG = "SshServer"

        /** Default SSH port (commonly used by Android SSH servers) */
        const val DEFAULT_PORT = 8022

        /** Default shell path */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** SSH configuration directory relative to app files dir */
        const val SSH_DIR = ".ssh"

        /** Authorized keys file name */
        const val AUTHORIZED_KEYS_FILE = "authorized_keys"

        /** SSH server config file name */
        const val SSH_CONFIG_FILE = "sshd_config"

        /** Maximum concurrent sessions allowed */
        const val MAX_SESSIONS = 10

        /** Socket backlog for pending connections */
        private const val SOCKET_BACKLOG = 50

        /** Thread pool size for handling SSH connections */
        private const val THREAD_POOL_SIZE = 8

        /** Socket read timeout in milliseconds */
        private const val SOCKET_TIMEOUT_MS = 30000

        /** Keep-alive interval in seconds */
        private const val KEEP_ALIVE_INTERVAL_S = 30

        // SSH protocol constants
        private const val SSH_PROTOCOL_VERSION = "SSH-2.0-TermX_1.0"
        private const val SSH_MAGIC = "SSH-"
        private const val BLOCK_SIZE = 8

        // SSH message types
        private const val SSH_MSG_DISCONNECT = 1
        private const val SSH_MSG_IGNORE = 2
        private const val SSH_MSG_UNIMPLEMENTED = 3
        private const val SSH_MSG_DEBUG = 4
        private const val SSH_MSG_SERVICE_REQUEST = 5
        private const val SSH_MSG_SERVICE_ACCEPT = 6
        private const val SSH_MSG_KEXINIT = 20
        private const val SSH_MSG_NEWKEYS = 21
        private const val SSH_MSG_KEXDH_INIT = 30
        private const val SSH_MSG_KEXDH_REPLY = 31
        private const val SSH_MSG_USERAUTH_REQUEST = 50
        private const val SSH_MSG_USERAUTH_FAILURE = 51
        private const val SSH_MSG_USERAUTH_SUCCESS = 52
        private const val SSH_MSG_USERAUTH_BANNER = 53
        private const val SSH_MSG_GLOBAL_REQUEST = 80
        private const val SSH_MSG_CHANNEL_OPEN = 90
        private const val SSH_MSG_CHANNEL_OPEN_CONFIRMATION = 91
        private const val SSH_MSG_CHANNEL_OPEN_FAILURE = 92
        private const val SSH_MSG_CHANNEL_WINDOW_ADJUST = 93
        private const val SSH_MSG_CHANNEL_DATA = 94
        private const val SSH_MSG_CHANNEL_EXTENDED_DATA = 95
        private const val SSH_MSG_CHANNEL_EOF = 96
        private const val SSH_MSG_CHANNEL_CLOSE = 97
        private const val SSH_MSG_CHANNEL_REQUEST = 98
        private const val SSH_MSG_CHANNEL_SUCCESS = 99
        private const val SSH_MSG_CHANNEL_FAILURE = 100

        // Channel types
        private const val CHANNEL_SESSION = "session"
        private const val CHANNEL_DIRECT_TCPIP = "direct-tcpip"
        private const val CHANNEL_FORWARDED_TCPIP = "forwarded-tcpip"

        // Subsystem names
        private const val SUBSYSTEM_SFTP = "sftp"
        private const val SUBSYSTEM_SCP = "scp"

        // Disconnect reason codes
        private const val DISCONNECT_HOST_NOT_ALLOWED = 1
        private const val DISCONNECT_PROTOCOL_ERROR = 2
        private const val DISCONNECT_KEY_EXCHANGE_FAILED = 3
        private const val DISCONNECT_RESERVED = 4
        private const val DISCONNECT_MAC_ERROR = 5
        private const val DISCONNECT_COMPRESSION_ERROR = 6
        private const val DISCONNECT_SERVICE_NOT_AVAILABLE = 7
        private const val DISCONNECT_PROTOCOL_VERSION_NOT_SUPPORTED = 8
        private const val DISCONNECT_HOST_KEY_NOT_VERIFIABLE = 9
        private const val DISCONNECT_CONNECTION_LOST = 10
        private const val DISCONNECT_BY_APPLICATION = 11
        private const val DISCONNECT_TOO_MANY_CONNECTIONS = 12
        private const val DISCONNECT_AUTH_CANCELLED_BY_USER = 13
        private const val DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE = 14
        private const val DISCONNECT_ILLEGAL_USER_NAME = 15

        // Singleton instance
        @Volatile
        private var instance: SshServer? = null

        /**
         * Get or create the singleton SshServer instance.
         */
        fun getInstance(context: Context): SshServer {
            return instance ?: synchronized(this) {
                instance ?: SshServer(context.applicationContext).also { instance = it }
            }
        }
    }

    // ---- Server State ----

    /** Whether the server is currently running */
    private val running = AtomicBoolean(false)

    /** Server socket that accepts incoming connections */
    @Volatile private var serverSocket: ServerSocket? = null

    /** Thread pool for connection handling */
    private var threadPool: ExecutorService? = null

    /** Acceptor thread that listens for new connections */
    private var acceptorThread: Thread? = null

    /** Active SSH sessions keyed by session ID */
    private val sessions = ConcurrentHashMap<Int, SshSession>()

    /** Session ID counter */
    private val sessionIdCounter = AtomicInteger(0)

    /** Host key manager */
    val hostKeyManager: HostKeyManager by lazy { HostKeyManager(context) }

    /** SFTP subsystem factory */
    val sftpSubsystem: SftpSubsystem by lazy { SftpSubsystem(context) }

    // ---- Configuration ----

    /** Server port */
    @Volatile var port: Int = DEFAULT_PORT
        private set

    /** Server bind address (0.0.0.0 for all interfaces) */
    @Volatile var bindAddress: String = "0.0.0.0"
        private set

    /** Whether password authentication is enabled */
    @Volatile var passwordAuthEnabled: Boolean = true

    /** Whether public key authentication is enabled */
    @Volatile var publicKeyAuthEnabled: Boolean = true

    /** Shell path for new sessions */
    @Volatile var shellPath: String = DEFAULT_SHELL

    /** Login username (defaults to "termx") */
    @Volatile var loginUsername: String = "termx"

    /** Login password (null = no password auth) */
    @Volatile var loginPassword: String? = "termx"

    /** Whether the server starts on boot */
    @Volatile var startOnBoot: Boolean = false

    /** Whether port forwarding is allowed */
    @Volatile var portForwardingAllowed: Boolean = true

    /** Idle timeout in seconds (0 = no timeout) */
    @Volatile var idleTimeoutSeconds: Int = 0

    /** Authorized public keys (loaded from file) */
    private val authorizedKeys = CopyOnWriteArrayList<String>()

    /** Port forwarding tunnels */
    private val portForwardTunnels = ConcurrentHashMap<String, PortForwardTunnel>()

    /** Server start time (0 if not started) */
    @Volatile var startTime: Long = 0L
        private set

    /** Total connections accepted since server start */
    @Volatile var totalConnections: Long = 0L
        private set

    // ---- Event Callbacks ----

    /** Called when a new SSH session is created */
    var onSessionCreated: ((SshSession) -> Unit)? = null

    /** Called when an SSH session is closed */
    var onSessionClosed: ((SshSession) -> Unit)? = null

    /** Called when server state changes (started, stopped, error) */
    var onServerStateChanged: ((ServerState) -> Unit)? = null

    /** Whether the server is currently running */
    val isRunning: Boolean get() = running.get()

    /** Current active session count */
    val activeSessionCount: Int get() = sessions.size

    /** List of all active sessions */
    val activeSessions: List<SshSession> get() = sessions.values.toList()

    // ---- Data Classes ----

    /** Server state information */
    data class ServerState(
        val running: Boolean,
        val port: Int,
        val bindAddress: String,
        val activeSessions: Int,
        val uptime: Long,
        val totalConnections: Long
    ) {
        fun formatUptime(): String {
            val seconds = uptime / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return "%02d:%02d:%02d".format(hours, minutes, secs)
        }
    }

    /** Port forwarding tunnel definition */
    data class PortForwardTunnel(
        val id: String,
        val type: TunnelType,
        val bindAddress: String,
        val bindPort: Int,
        val destinationHost: String,
        val destinationPort: Int,
        val sessionId: Int,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** Tunnel type for port forwarding */
    enum class TunnelType {
        LOCAL,   // -L: Local port forwarding (client -> server -> remote)
        REMOTE   // -R: Remote port forwarding (remote -> server -> client)
    }

    // ---- Server Lifecycle ----

    /**
     * Start the SSH server on the specified port.
     *
     * @param port Port to listen on (default: 8022)
     * @param bindAddress Address to bind to (default: 0.0.0.0 = all interfaces)
     * @return true if the server started successfully
     */
    fun start(port: Int = DEFAULT_PORT, bindAddress: String = "0.0.0.0"): Boolean {
        if (running.get()) {
            Log.w(TAG, "SSH server is already running on port ${this.port}")
            return false
        }

        this.port = port
        this.bindAddress = bindAddress

        try {
            // Ensure host keys exist
            hostKeyManager.ensureHostKeys()

            // Load authorized keys
            loadAuthorizedKeys()

            // Load SSH config
            loadConfig()

            // Create thread pool
            threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE) { r ->
                Thread(r, "SshServer-Worker-${sessionIdCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }

            // Create server socket
            val addr = if (bindAddress == "0.0.0.0") null else InetAddress.getByName(bindAddress)
            serverSocket = ServerSocket(port, SOCKET_BACKLOG, addr)
            serverSocket?.reuseAddress = true

            running.set(true)
            startTime = System.currentTimeMillis()
            totalConnections = 0

            // Start acceptor thread
            acceptorThread = Thread({ acceptConnections() }, "SshServer-Acceptor").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "SSH server started on $bindAddress:$port")
            onServerStateChanged?.invoke(getState())
            return true

        } catch (e: BindException) {
            Log.e(TAG, "Failed to bind to port $port: address already in use", e)
            cleanup()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSH server", e)
            cleanup()
            return false
        }
    }

    /**
     * Stop the SSH server and disconnect all sessions.
     */
    fun stop() {
        if (!running.getAndSet(false)) {
            Log.w(TAG, "SSH server is not running")
            return
        }

        Log.i(TAG, "Stopping SSH server...")
        cleanup()
        onServerStateChanged?.invoke(getState())
        Log.i(TAG, "SSH server stopped")
    }

    /**
     * Restart the SSH server.
     *
     * @return true if the server restarted successfully
     */
    fun restart(): Boolean {
        val savedPort = port
        val savedBindAddress = bindAddress
        stop()
        Thread.sleep(500) // Brief pause for cleanup
        return start(savedPort, savedBindAddress)
    }

    /**
     * Get the current server state.
     */
    fun getState(): ServerState {
        val uptime = if (running.get()) System.currentTimeMillis() - startTime else 0L
        return ServerState(
            running = running.get(),
            port = port,
            bindAddress = bindAddress,
            activeSessions = activeSessionCount,
            uptime = uptime,
            totalConnections = totalConnections
        )
    }

    // ---- Connection Handling ----

    /**
     * Main accept loop that listens for incoming SSH connections.
     * Runs on the acceptor thread.
     */
    private fun acceptConnections() {
        Log.d(TAG, "Acceptor thread started, listening on port $port")

        while (running.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                clientSocket.tcpNoDelay = true
                clientSocket.soTimeout = SOCKET_TIMEOUT_MS
                clientSocket.keepAlive = true

                totalConnections++

                // Check session limit
                if (sessions.size >= MAX_SESSIONS) {
                    Log.w(TAG, "Max sessions ($MAX_SESSIONS) reached, rejecting connection from ${clientSocket.inetAddress}")
                    sendDisconnectAndClose(clientSocket, DISCONNECT_TOO_MANY_CONNECTIONS,
                        "Too many concurrent sessions")
                    continue
                }

                // Submit connection to thread pool
                threadPool?.execute {
                    handleConnection(clientSocket)
                } ?: run {
                    clientSocket.close()
                }

            } catch (e: SocketException) {
                if (running.get()) {
                    Log.e(TAG, "Socket error in accept loop", e)
                }
                // Server socket closed — exit loop
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        }

        Log.d(TAG, "Acceptor thread ended")
    }

    /**
     * Handle a single SSH connection.
     * This runs on a worker thread from the pool.
     */
    private fun handleConnection(clientSocket: Socket) {
        val sessionId = sessionIdCounter.incrementAndGet()
        val clientAddr = clientSocket.inetAddress?.hostAddress ?: "unknown"
        val clientPort = clientSocket.port

        Log.i(TAG, "New connection #$sessionId from $clientAddr:$clientPort")

        var session: SshSession? = null

        try {
            // Perform SSH protocol handshake
            val handshakeResult = performHandshake(clientSocket, sessionId)
            if (!handshakeResult.success) {
                Log.w(TAG, "Handshake failed for session #$sessionId: ${handshakeResult.errorMessage}")
                safeClose(clientSocket)
                return
            }

            // Create session object
            session = SshSession(
                id = sessionId,
                clientAddress = clientAddr,
                clientPort = clientPort,
                socket = clientSocket,
                inputStream = handshakeResult.inputStream!!,
                outputStream = handshakeResult.outputStream!!,
                username = handshakeResult.username,
                authMethod = handshakeResult.authMethod,
                serverRef = this
            )
            session.startTime = System.currentTimeMillis()

            // Register session
            sessions[sessionId] = session
            onSessionCreated?.invoke(session)

            Log.i(TAG, "Session #$sessionId authenticated as '${handshakeResult.username}' " +
                    "via ${handshakeResult.authMethod} from $clientAddr")

            // Main session loop — process channels and data
            session.processLoop()

        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Session #$sessionId timed out")
        } catch (e: SocketException) {
            // Client disconnected — normal
            Log.d(TAG, "Session #$sessionId disconnected: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Session #$sessionId I/O error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Session #$sessionId unexpected error", e)
        } finally {
            // Cleanup session
            session?.let {
                it.close()
                sessions.remove(sessionId)
                onSessionClosed?.invoke(it)
                Log.i(TAG, "Session #$sessionId closed (duration: ${it.durationMs}ms)")
            }
            safeClose(clientSocket)
        }
    }

    // ---- SSH Protocol Handshake ----

    /**
     * Result of the SSH protocol handshake.
     */
    data class HandshakeResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val inputStream: DataInputStream? = null,
        val outputStream: DataOutputStream? = null,
        val username: String = "",
        val authMethod: String = ""
    )

    /**
     * Perform the full SSH handshake:
     *   1. Protocol version exchange
     *   2. Key exchange (KEXINIT)
     *   3. Diffie-Hellman key exchange
     *   4. New keys activation
     *   5. Authentication
     *
     * This implements a simplified SSH v2 protocol suitable for
     * connecting from standard SSH clients.
     */
    private fun performHandshake(socket: Socket, sessionId: Int): HandshakeResult {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            // Step 1: Protocol version exchange
            val clientVersion = exchangeProtocolVersion(input, output)
            if (clientVersion == null) {
                return HandshakeResult(false, "Protocol version exchange failed")
            }

            Log.d(TAG, "Session #$sessionId: Client version: $clientVersion")

            // Validate client supports SSH-2.0
            if (!clientVersion.startsWith("SSH-2.") && !clientVersion.startsWith("SSH-1.99")) {
                return HandshakeResult(false, "Unsupported SSH version: $clientVersion",
                    inputStream = input, outputStream = output)
            }

            // Step 2: Key exchange initialization (KEXINIT)
            val kexResult = performKeyExchange(input, output, sessionId)
            if (!kexResult.success) {
                return HandshakeResult(false, kexResult.errorMessage,
                    inputStream = input, outputStream = output)
            }

            // Step 3: Authentication
            val authResult = performAuthentication(input, output, sessionId, kexResult)
            if (!authResult.success) {
                return HandshakeResult(false, authResult.errorMessage,
                    inputStream = input, outputStream = output)
            }

            return HandshakeResult(
                success = true,
                inputStream = input,
                outputStream = output,
                username = authResult.username,
                authMethod = authResult.authMethod
            )

        } catch (e: Exception) {
            return HandshakeResult(false, "Handshake error: ${e.message}")
        }
    }

    /**
     * Exchange SSH protocol version strings.
     * The server sends its version first, then reads the client's version.
     */
    private fun exchangeProtocolVersion(input: DataInputStream, output: DataOutputStream): String? {
        try {
            // Send our version string (CR LF terminated)
            val versionBytes = "$SSH_PROTOCOL_VERSION\r\n".toByteArray()
            output.write(versionBytes)
            output.flush()

            // Read client version string (may be preceded by banner lines)
            val versionBuf = ByteArrayOutputStream()
            var prevWasCr = false
            var maxLines = 50 // Max banner lines before version

            while (maxLines-- > 0) {
                val b = input.read()
                if (b == -1) return null // EOF

                versionBuf.write(b)

                // Check for CR LF sequence
                if (b == '\n'.code && prevWasCr) {
                    val line = versionBuf.toString("UTF-8").trimEnd('\r', '\n')
                    if (line.startsWith(SSH_MAGIC)) {
                        return line
                    }
                    // Not a version line — reset and continue
                    versionBuf.reset()
                    prevWasCr = false
                } else {
                    prevWasCr = (b == '\r'.code)
                }

                // Prevent reading excessively long lines
                if (versionBuf.size() > 4096) {
                    return null
                }
            }

            return null

        } catch (e: IOException) {
            Log.e(TAG, "Protocol version exchange error", e)
            return null
        }
    }

    // ---- Key Exchange ----

    /**
     * Result of the key exchange phase.
     */
    data class KexResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val sessionKey: ByteArray = ByteArray(0),
        val sessionIdBytes: ByteArray = ByteArray(0),
        val serverHostKey: ByteArray = ByteArray(0),
        val clientKexCookie: ByteArray = ByteArray(0)
    )

    /**
     * Perform the SSH key exchange.
     *
     * Implements a simplified Diffie-Hellman Group 14 key exchange (diffie-hellman-group14-sha256)
     * with host key verification using RSA/ECDSA.
     *
     * In production, this would use the full SSH binary packet protocol with
     * proper encryption/MAC. This implementation provides the structural framework
     * for the key exchange while relying on Java's cryptographic primitives.
     */
    private fun performKeyExchange(input: DataInputStream, output: DataOutputStream,
                                    sessionId: Int): KexResult {
        try {
            // Generate server KEXINIT cookie (16 random bytes)
            val serverCookie = ByteArray(16).also { SecureRandom().nextBytes(it) }

            // Build server KEXINIT message
            // Name lists: kex, hostkey, encryption, mac, compression, languages
            val kexAlgorithms = "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1," +
                    "ecdh-sha2-nistp256"
            val hostKeyAlgorithms = "ssh-rsa,ecdsa-sha2-nistp256,ssh-ed25519"
            val encAlgorithms = "aes128-ctr,aes192-ctr,aes256-ctr,aes128-cbc,aes256-cbc"
            val macAlgorithms = "hmac-sha2-256,hmac-sha1"
            val compAlgorithms = "none,zlib"

            val serverKexinit = buildKexinitMessage(
                cookie = serverCookie,
                kexAlgorithms = kexAlgorithms,
                hostKeyAlgorithms = hostKeyAlgorithms,
                encC2S = encAlgorithms,
                encS2C = encAlgorithms,
                macC2S = macAlgorithms,
                macS2C = macAlgorithms,
                compC2S = compAlgorithms,
                compS2C = compAlgorithms
            )

            // Send KEXINIT
            sendSshPacket(output, SSH_MSG_KEXINIT, serverKexinit)

            // Read client KEXINIT
            val clientKexPacket = readSshPacket(input)
            if (clientKexPacket == null || clientKexPacket.type != SSH_MSG_KEXINIT) {
                return KexResult(false, "Expected KEXINIT from client")
            }

            // Parse client KEXINIT to find negotiated algorithms
            val clientKexCookie = clientKexPacket.payload.copyOfRange(0, 16)

            // Determine negotiated algorithm (first match)
            val negotiatedKex = negotiateAlgorithm(
                kexAlgorithms, extractNameList(clientKexPacket.payload, 16)
            ) ?: return KexResult(false, "No matching key exchange algorithm")

            Log.d(TAG, "Session #$sessionId: Negotiated kex=$negotiatedKex")

            // Read DH init (client's public value)
            val dhInitPacket = readSshPacket(input)
            if (dhInitPacket == null || dhInitPacket.type != SSH_MSG_KEXDH_INIT) {
                return KexResult(false, "Expected KEXDH_INIT from client")
            }

            // Generate DH key pair and compute shared secret
            val dhKeyPair = generateDhKeyPair()
            val clientPublic = decodeMpint(dhInitPacket.payload)

            // Compute shared secret
            val sharedSecret = computeDhSharedSecret(dhKeyPair, clientPublic)

            // Get host key for signing
            val hostKeyPair = hostKeyManager.getActiveKeyPair()
            val hostKeyBlob = encodeHostKey(hostKeyPair)

            // Compute session ID (H = hash(V_C || V_S || I_C || I_S || K_S || e || f || K))
            val sessionIdHash = computeSessionId(
                clientVersion = SSH_PROTOCOL_VERSION,
                serverVersion = SSH_PROTOCOL_VERSION,
                clientKexinit = clientKexPacket.payload,
                serverKexinit = serverKexinit,
                hostKeyBlob = hostKeyBlob,
                clientDhPublic = clientPublic,
<<<<<<< HEAD
                serverDhPublic = dhKeyPair.public,
=======
                serverDhPublic = dhKeyPair.publicKey,
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
                sharedSecret = sharedSecret
            )

            // Sign the exchange hash with the host key
            val signature = signWithHostKey(hostKeyPair, sessionIdHash)

            // Build KEXDH_REPLY message
<<<<<<< HEAD
            val replyPayload = buildKexdhReplyPayload(hostKeyBlob, dhKeyPair.public, signature)
=======
            val replyPayload = buildKexdhReplyPayload(hostKeyBlob, dhKeyPair.publicKey, signature)
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)

            // Send KEXDH_REPLY
            sendSshPacket(output, SSH_MSG_KEXDH_REPLY, replyPayload)

            // Send NEWKEYS
            sendSshPacket(output, SSH_MSG_NEWKEYS, ByteArray(0))

            // Read NEWKEYS from client
            val newKeysPacket = readSshPacket(input)
            if (newKeysPacket == null || newKeysPacket.type != SSH_MSG_NEWKEYS) {
                return KexResult(false, "Expected NEWKEYS from client")
            }

            // Derive encryption keys from shared secret
            val sessionKey = deriveSessionKey(sharedSecret, sessionIdHash)

            Log.d(TAG, "Session #$sessionId: Key exchange completed successfully")

            return KexResult(
                success = true,
                sessionKey = sessionKey,
                sessionIdBytes = sessionIdHash,
                serverHostKey = hostKeyBlob,
                clientKexCookie = clientKexCookie
            )

        } catch (e: Exception) {
            Log.e(TAG, "Key exchange error for session #$sessionId", e)
            return KexResult(false, "Key exchange failed: ${e.message}")
        }
    }

    // ---- Authentication ----

    /**
     * Result of the authentication phase.
     */
    data class AuthResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val username: String = "",
        val authMethod: String = ""
    )

    /**
     * Perform SSH user authentication.
     * Supports password and public key methods.
     */
    private fun performAuthentication(input: DataInputStream, output: DataOutputStream,
                                       sessionId: Int, kexResult: KexResult): AuthResult {
        try {
            // Request the 'ssh-connection' service
            val serviceRequest = readSshPacket(input)
            if (serviceRequest?.type == SSH_MSG_SERVICE_REQUEST) {
                // Accept the service
                sendSshPacket(output, SSH_MSG_SERVICE_ACCEPT,
                    encodeString("ssh-connection"))
            }

            // Authentication loop — allow multiple attempts
            var attempts = 0
            val maxAttempts = 5

            while (attempts++ < maxAttempts) {
                val authPacket = readSshPacket(input)
                if (authPacket == null) {
                    return AuthResult(false, "Connection closed during auth")
                }

                if (authPacket.type != SSH_MSG_USERAUTH_REQUEST) {
                    // Not an auth request — skip
                    continue
                }

                // Parse auth request
                val authInfo = parseAuthRequest(authPacket.payload)
                Log.d(TAG, "Session #$sessionId: Auth request user='${authInfo.username}' " +
                        "method='${authInfo.method}'")

                // Validate username
                if (authInfo.username != loginUsername) {
                    Log.w(TAG, "Session #$sessionId: Invalid username '${authInfo.username}'")
                    sendAuthFailure(output, "publickey,password")
                    continue
                }

                // Try public key auth first
                if (authInfo.method == "publickey" && publicKeyAuthEnabled) {
                    val pubKeyResult = verifyPublicKey(authInfo.publicKeyBlob, authInfo.signature)
                    if (pubKeyResult) {
                        sendSshPacket(output, SSH_MSG_USERAUTH_SUCCESS, ByteArray(0))
                        Log.i(TAG, "Session #$sessionId: Public key auth succeeded for '${authInfo.username}'")
                        return AuthResult(true, username = authInfo.username, authMethod = "publickey")
                    } else {
                        sendAuthFailure(output, "publickey,password")
                        continue
                    }
                }

                // Try password auth
                if (authInfo.method == "password" && passwordAuthEnabled) {
                    if (loginPassword != null && authInfo.password == loginPassword) {
                        sendSshPacket(output, SSH_MSG_USERAUTH_SUCCESS, ByteArray(0))
                        Log.i(TAG, "Session #$sessionId: Password auth succeeded for '${authInfo.username}'")
                        return AuthResult(success = true, username = authInfo.username,
                            authMethod = "password")
                    } else {
                        Log.w(TAG, "Session #$sessionId: Password auth failed for '${authInfo.username}'")
                        sendAuthFailure(output, "publickey,password")
                        continue
                    }
                }

                // No supported auth method
                sendAuthFailure(output, if (publicKeyAuthEnabled && passwordAuthEnabled)
                    "publickey,password" else if (publicKeyAuthEnabled) "publickey" else "password")
            }

            return AuthResult(false, "Max authentication attempts exceeded")

        } catch (e: Exception) {
            Log.e(TAG, "Authentication error for session #$sessionId", e)
            return AuthResult(false, "Authentication error: ${e.message}")
        }
    }

    /**
     * Parse a USERAUTH_REQUEST payload.
     */
    private fun parseAuthRequest(payload: ByteArray): AuthRequestInfo {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val username = readSshString(input)
        val service = readSshString(input)
        val method = readSshString(input)

        var password: String? = null
        var publicKeyBlob: ByteArray? = null
        var signature: ByteArray? = null

        when (method) {
            "password" -> {
                val hasChange = input.readByte().toInt() != 0
                password = readSshString(input)
            }
            "publickey" -> {
                val hasSignature = input.readByte().toInt() != 0
                val pkAlgorithm = readSshString(input)
                publicKeyBlob = readSshBlob(input)
                if (hasSignature) {
                    signature = readSshBlob(input)
                }
            }
        }

        return AuthRequestInfo(
            username = username,
            service = service,
            method = method,
            password = password,
            publicKeyBlob = publicKeyBlob,
            signature = signature
        )
    }

    private data class AuthRequestInfo(
        val username: String,
        val service: String,
        val method: String,
        val password: String? = null,
        val publicKeyBlob: ByteArray? = null,
        val signature: ByteArray? = null
    )

    /**
     * Verify a public key against the authorized_keys file.
     */
    private fun verifyPublicKey(publicKeyBlob: ByteArray?, signature: ByteArray?): Boolean {
        if (publicKeyBlob == null) return false

        // Convert public key blob to a comparable format
        val keyFingerprint = computeFingerprint(publicKeyBlob)

        for (authorizedKey in authorizedKeys) {
            try {
                // Parse the authorized key line (format: type base64-key comment)
                val parts = authorizedKey.trim().split("\\s+".toRegex())
                if (parts.size < 2) continue

                val keyType = parts[0]
                val keyData = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)

                // Compare fingerprints
                val authorizedFingerprint = computeFingerprint(keyData)
                if (keyFingerprint == authorizedFingerprint) {
                    return true
                }

                // Direct blob comparison as fallback
                if (keyData.contentEquals(publicKeyBlob)) {
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }

        return false
    }

    /**
     * Send authentication failure message.
     */
    private fun sendAuthFailure(output: DataOutputStream, methods: String) {
        val payload = ByteArrayOutputStream()
        val dataOut = DataOutputStream(payload)
        dataOut.write(encodeString(methods))
        dataOut.writeByte(0) // partial success = false
        sendSshPacket(output, SSH_MSG_USERAUTH_FAILURE, payload.toByteArray())
    }

    // ---- SSH Packet Protocol ----

    /**
     * Represents a parsed SSH packet.
     */
    data class SshPacket(
        val type: Int,
        val payload: ByteArray
    )

    /**
     * Build a KEXINIT message payload.
     */
    private fun buildKexinitMessage(
        cookie: ByteArray,
        kexAlgorithms: String,
        hostKeyAlgorithms: String,
        encC2S: String,
        encS2C: String,
        macC2S: String,
        macS2C: String,
        compC2S: String,
        compS2C: String
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)

        // Cookie (16 bytes)
        out.write(cookie)

        // Name lists (each as length-prefixed string)
        out.write(encodeString(kexAlgorithms))
        out.write(encodeString(hostKeyAlgorithms))
        out.write(encodeString(encC2S))
        out.write(encodeString(encS2C))
        out.write(encodeString(macC2S))
        out.write(encodeString(macS2C))
        out.write(encodeString(compC2S))
        out.write(encodeString(compS2C))
        out.write(encodeString("")) // languages C->S
        out.write(encodeString("")) // languages S->C
        out.writeByte(0) // first kex packet follows = false
        out.writeInt(0) // reserved

        return buf.toByteArray()
    }

    /**
     * Build a KEXDH_REPLY payload.
     */
    private fun buildKexdhReplyPayload(
        hostKeyBlob: ByteArray,
        dhPublic: java.math.BigInteger,
        signature: ByteArray
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)

        // Server host key
        out.write(encodeSshBlob(hostKeyBlob))

        // DH public value (f)
        out.write(encodeMpint(dhPublic))

        // Signature
        out.write(encodeSshBlob(signature))

        return buf.toByteArray()
    }

    /**
     * Send an SSH packet (unencrypted during initial handshake).
     * Format: packet_length || padding_length || type || payload || padding || MAC
     */
    private fun sendSshPacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        val blockSize = BLOCK_SIZE

        // Minimum padding is 4 bytes, total (1 + payload_len + padding) must be multiple of block_size
        val minPadding = 4
        val packetLen = 1 + payload.size
        val paddingLen = blockSize - ((packetLen + 1) % blockSize)
        val adjustedPadding = if (paddingLen < minPadding) paddingLen + blockSize else paddingLen

        val padding = ByteArray(adjustedPadding)

        val buf = ByteArrayOutputStream()
        val dataOut = DataOutputStream(buf)

        dataOut.writeInt(packetLen + adjustedPadding) // packet_length
        dataOut.writeByte(adjustedPadding)             // padding_length
        dataOut.writeByte(type)                        // message type
        dataOut.write(payload)                         // payload
        dataOut.write(padding)                         // random padding

        output.write(buf.toByteArray())
        output.flush()
    }

    /**
     * Read an SSH packet (unencrypted during initial handshake).
     */
    private fun readSshPacket(input: DataInputStream): SshPacket? {
        try {
            val packetLength = input.readInt()
            if (packetLength < 5 || packetLength > 350000) {
                Log.e(TAG, "Invalid packet length: $packetLength")
                return null
            }

            val paddingLength = input.readUnsignedByte()
            val payloadLength = packetLength - paddingLength - 1

            if (payloadLength < 1) {
                Log.e(TAG, "Invalid payload length: $payloadLength")
                return null
            }

            val data = ByteArray(payloadLength)
            input.readFully(data)

            // Read and discard padding
            val padding = ByteArray(paddingLength)
            input.readFully(padding)

            val type = data[0].toInt() and 0xFF
            val payload = data.copyOfRange(1, data.size)

            return SshPacket(type, payload)

        } catch (e: IOException) {
            Log.d(TAG, "Error reading SSH packet: ${e.message}")
            return null
        }
    }

    // ---- Cryptographic Helpers ----

    /**
     * Generate a DH Group 14 key pair.
     * Uses the well-known DH Group 14 prime (2048-bit).
     */
    private fun generateDhKeyPair(): DHKeyPair {
        val p = BigInteger(DH_GROUP_14_PRIME_HEX, 16)
        val g = BigInteger("2")
        val random = SecureRandom()

        // Generate private key x: 1 < x < (p-1)/2
        val x = BigInteger(p.bitLength() - 2, random).abs().add(BigInteger.ONE)
        val y = g.modPow(x, p) // public key y = g^x mod p

        return DHKeyPair(privateKey = x, publicKey = y, p = p, g = g)
    }

    /**
     * Compute the DH shared secret.
     */
    private fun computeDhSharedSecret(keyPair: DHKeyPair, clientPublic: java.math.BigInteger): java.math.BigInteger {
        return clientPublic.modPow(keyPair.privateKey, keyPair.p)
    }

    /**
     * Derive session encryption keys from the shared secret and session ID.
     * Uses the SSH key derivation formula:
     *   key = HASH(K || H || X || session_id)
     * where X is a single byte identifier (A, B, C, ...).
     */
    private fun deriveSessionKey(sharedSecret: java.math.BigInteger, sessionIdHash: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")

        // Client->Server IV
        val ivC2S = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'A'.code, 16)
        // Server->Client IV
        val ivS2C = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'B'.code, 16)
        // Client->Server encryption key
        val encKeyC2S = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'C'.code, 32)
        // Server->Client encryption key
        val encKeyS2C = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'D'.code, 32)
        // Client->Server MAC key
        val macKeyC2S = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'E'.code, 32)
        // Server->Client MAC key
        val macKeyS2C = deriveKeyPart(digest, sharedSecret, sessionIdHash, 'F'.code, 32)

        // Return combined key material (for use by session)
        val result = ByteArrayOutputStream()
        result.write(ivC2S)
        result.write(ivS2C)
        result.write(encKeyC2S)
        result.write(encKeyS2C)
        result.write(macKeyC2S)
        result.write(macKeyS2C)
        return result.toByteArray()
    }

    /**
     * Derive a single key part using the SSH key derivation formula.
     */
    private fun deriveKeyPart(
        digest: MessageDigest,
        sharedSecret: BigInteger,
        sessionIdHash: ByteArray,
        identifier: Int,
        requiredLength: Int
    ): ByteArray {
        var key = ByteArray(0)

        // First iteration: HASH(K || H || X || session_id)
        digest.reset()
        digest.update(encodeMpint(sharedSecret))
        digest.update(sessionIdHash)
        digest.update(identifier.toByte())
        digest.update(sessionIdHash)
        key = digest.digest()

        // Additional iterations if more bytes are needed
        while (key.size < requiredLength) {
            digest.reset()
            digest.update(encodeMpint(sharedSecret))
            digest.update(sessionIdHash)
            digest.update(key)
            val nextPart = digest.digest()
            key = key + nextPart
        }

        return key.copyOfRange(0, requiredLength)
    }

    /**
     * Compute the session ID (exchange hash) from the key exchange parameters.
     * H = HASH(V_C || V_S || I_C || I_S || K_S || e || f || K)
     */
    private fun computeSessionId(
        clientVersion: String,
        serverVersion: String,
        clientKexinit: ByteArray,
        serverKexinit: ByteArray,
        hostKeyBlob: ByteArray,
        clientDhPublic: java.math.BigInteger,
        serverDhPublic: java.math.BigInteger,
        sharedSecret: java.math.BigInteger
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(encodeString(clientVersion))
        digest.update(encodeString(serverVersion))
        digest.update(encodeSshBlob(clientKexinit))
        digest.update(encodeSshBlob(serverKexinit))
        digest.update(encodeSshBlob(hostKeyBlob))
        digest.update(encodeMpint(clientDhPublic))
        digest.update(encodeMpint(serverDhPublic))
        digest.update(encodeMpint(sharedSecret))

        return digest.digest()
    }

    /**
     * Sign data with the host key for KEXDH_REPLY.
     */
    private fun signWithHostKey(keyPair: KeyPair, data: ByteArray): ByteArray {
        try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(keyPair.private)
            sig.update(data)
            val rawSig = sig.sign()

            // Wrap in SSH signature format: algorithm-name || signature-blob
            val buf = ByteArrayOutputStream()
            val out = DataOutputStream(buf)
            out.write(encodeString("ssh-rsa"))
            out.write(encodeSshBlob(rawSig))
            return buf.toByteArray()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign with host key", e)
            return ByteArray(0)
        }
    }

    /**
     * Encode the host key as an SSH public key blob.
     */
    private fun encodeHostKey(keyPair: KeyPair): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)

        // Key type
        out.write(encodeString("ssh-rsa"))

        // RSA public exponent (e)
        val rsaPub = keyPair.public as java.security.interfaces.RSAPublicKey
        out.write(encodeMpint(rsaPub.publicExponent))

        // RSA modulus (n)
        out.write(encodeMpint(rsaPub.modulus))

        return buf.toByteArray()
    }

    // ---- SSH Encoding Helpers ----

    /**
     * Encode a string as SSH wire format (uint32 length + UTF-8 data).
     */
    private fun encodeString(s: String): ByteArray {
        val data = s.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Encode a byte array as an SSH string/blob (uint32 length + data).
     */
    private fun encodeSshBlob(data: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Encode a BigInteger as an SSH mpint (uint32 length + signed big-endian).
     */
    private fun encodeMpint(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        // Remove leading zero byte if it's just a sign bit
        val trimmed = if (raw.size > 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        // Add leading zero byte if high bit is set (to indicate positive)
        val final = if ((trimmed[0].toInt() and 0x80) != 0) {
            byteArrayOf(0) + trimmed
        } else {
            trimmed
        }
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(final.size)
        out.write(final)
        return buf.toByteArray()
    }

    /**
     * Decode an SSH mpint from a byte array.
     */
    private fun decodeMpint(data: ByteArray): java.math.BigInteger {
        return java.math.BigInteger(1, data) // Always positive
    }

    /**
     * Read an SSH string from a DataInputStream.
     */
    private fun readSshString(input: DataInputStream): String {
        val len = input.readInt()
        val data = ByteArray(len)
        input.readFully(data)
        return String(data, Charsets.UTF_8)
    }

    /**
     * Read an SSH blob from a DataInputStream.
     */
    private fun readSshBlob(input: DataInputStream): ByteArray {
        val len = input.readInt()
        val data = ByteArray(len)
        input.readFully(data)
        return data
    }

    /**
     * Extract a name list from a KEXINIT payload at the given offset.
     * Simplified: returns the raw string at the offset position.
     */
    private fun extractNameList(payload: ByteArray, offset: Int): String {
        try {
            val input = DataInputStream(ByteArrayInputStream(payload, offset, payload.size - offset))
            return readSshString(input)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Negotiate the first matching algorithm between client and server lists.
     */
    private fun negotiateAlgorithm(serverList: String, clientList: String): String? {
        val serverAlgos = serverList.split(",").map { it.trim() }
        val clientAlgos = clientList.split(",").map { it.trim() }

        for (algo in clientAlgos) {
            if (algo in serverAlgos) return algo
        }
        return null
    }

    /**
     * Compute a SHA-256 fingerprint of a byte array.
     */
    private fun computeFingerprint(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString(":") { "%02x".format(it) }
    }

    // ---- DH Group 14 Prime (RFC 3526) ----

    private val DH_GROUP_14_PRIME_HEX =
        "FFFFFFFF" + "FFFFFFFF" + "C90FDAA2" + "2168C234" + "C4C6628B" + "80DC1CD1" +
        "29024E08" + "8A67CC74" + "020BBEA6" + "3B139B22" + "514A0879" + "8E3404DD" +
        "EF9519B3" + "CD3A431B" + "302B0A6D" + "F25F1437" + "4FE1356D" + "6D51C245" +
        "E485B576" + "625E7EC6" + "F44C42E9" + "A637ED6B" + "0BFF5CB6" + "F406B7ED" +
        "EE386BFB" + "5A899FA5" + "AE9F2411" + "7C4B1FE6" + "49286651" + "ECE45B3D" +
        "C2007CB8" + "A163BF05" + "98DA4836" + "1C55D39A" + "69163FA8" + "FD24CF5F" +
        "83655D23" + "DCA3AD96" + "1C62F356" + "208552BB" + "9ED52907" + "7096966D" +
        "670C354E" + "4ABC9804" + "F1746C08" + "CA18217C" + "32905E46" + "2E36CE3B" +
        "E39E772C" + "180E8603" + "9B2783A2" + "EC07A28F" + "B5C55DF0" + "6F4C52C9" +
        "DE2BCBF6" + "95581718" + "3995497C" + "EA956AE5" + "15D22618" + "98FA0510" +
        "15728E5A" + "8AACAA68" + "FFFFFFFF" + "FFFFFFFF"

    /**
     * Simple DH key pair holder.
     */
    private data class DHKeyPair(
        val privateKey: java.math.BigInteger,
        val publicKey: java.math.BigInteger,
        val p: java.math.BigInteger,
        val g: java.math.BigInteger
    )

    // ---- Authorized Keys Management ----

    /**
     * Load authorized public keys from the ~/.ssh/authorized_keys file.
     */
    fun loadAuthorizedKeys() {
        authorizedKeys.clear()
        val keysFile = File(context.filesDir, "$SSH_DIR/$AUTHORIZED_KEYS_FILE")
        if (!keysFile.exists()) {
            Log.d(TAG, "No authorized_keys file found")
            return
        }

        try {
            keysFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    authorizedKeys.add(trimmed)
                }
            }
            Log.i(TAG, "Loaded ${authorizedKeys.size} authorized key(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load authorized keys", e)
        }
    }

    /**
     * Add an authorized public key.
     *
     * @param publicKeyLine The public key line (format: type base64-key [comment])
     * @return true if the key was added successfully
     */
    fun addAuthorizedKey(publicKeyLine: String): Boolean {
        val trimmed = publicKeyLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false

        // Validate basic format
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.size < 2) return false

        // Validate key type
        val validTypes = setOf("ssh-rsa", "ssh-dss", "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-ed25519")
        if (parts[0] !in validTypes) return false

        // Check for duplicate
        if (authorizedKeys.any { it.trim() == trimmed }) {
            Log.w(TAG, "Key already exists in authorized_keys")
            return false
        }

        authorizedKeys.add(trimmed)
        saveAuthorizedKeys()
        Log.i(TAG, "Added authorized key: ${parts[0]} ${parts.getOrElse(2) { "" }}")
        return true
    }

    /**
     * Remove an authorized public key.
     *
     * @param publicKeyLine The public key line or fingerprint to remove
     * @return true if the key was removed
     */
    fun removeAuthorizedKey(publicKeyLine: String): Boolean {
        val trimmed = publicKeyLine.trim()
        val removed = authorizedKeys.removeAll { it.trim() == trimmed }
        if (removed) {
            saveAuthorizedKeys()
            Log.i(TAG, "Removed authorized key")
        }
        return removed
    }

    /**
     * Get all authorized public keys.
     */
    fun getAuthorizedKeys(): List<String> = authorizedKeys.toList()

    /**
     * Save authorized keys to file.
     */
    private fun saveAuthorizedKeys() {
        try {
            val sshDir = File(context.filesDir, SSH_DIR)
            if (!sshDir.exists()) sshDir.mkdirs()

            val keysFile = File(sshDir, AUTHORIZED_KEYS_FILE)
            keysFile.writeText(authorizedKeys.joinToString("\n") + "\n")

            // Set file permissions (owner read/write only)
            try {
                val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "600", keysFile.absolutePath))
                chmod.waitFor()
            } catch (_: Exception) { /* Best effort on Android */ }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save authorized keys", e)
        }
    }

    // ---- Port Forwarding ----

    /**
     * Register a local port forwarding tunnel.
     */
    fun registerLocalForward(tunnel: PortForwardTunnel) {
        if (!portForwardingAllowed) {
            Log.w(TAG, "Port forwarding is not allowed")
            return
        }
        portForwardTunnels[tunnel.id] = tunnel
        Log.i(TAG, "Registered local forward: ${tunnel.bindPort} -> ${tunnel.destinationHost}:${tunnel.destinationPort}")
    }

    /**
     * Register a remote port forwarding tunnel.
     */
    fun registerRemoteForward(tunnel: PortForwardTunnel) {
        if (!portForwardingAllowed) {
            Log.w(TAG, "Port forwarding is not allowed")
            return
        }
        portForwardTunnels[tunnel.id] = tunnel
        Log.i(TAG, "Registered remote forward: ${tunnel.bindPort} -> ${tunnel.destinationHost}:${tunnel.destinationPort}")
    }

    /**
     * Unregister a port forwarding tunnel.
     */
    fun unregisterForward(tunnelId: String) {
        portForwardTunnels.remove(tunnelId)
    }

    /**
     * Get all active port forwarding tunnels.
     */
    fun getPortForwardTunnels(): List<PortForwardTunnel> = portForwardTunnels.values.toList()

    /**
     * Handle a direct-tcpip channel request (local port forwarding).
     * The client wants to forward a connection through the server.
     */
    fun handleDirectTcpIpChannel(
        session: SshSession,
        destinationHost: String,
        destinationPort: Int,
        originatorAddress: String,
        originatorPort: Int
    ): Boolean {
        if (!portForwardingAllowed) return false

        try {
            // Attempt to connect to the destination
            val destSocket = Socket()
            destSocket.connect(InetSocketAddress(destinationHost, destinationPort), 10000)
            destSocket.tcpNoDelay = true

            val tunnelId = "fwd_${session.id}_${System.currentTimeMillis()}"
            val tunnel = PortForwardTunnel(
                id = tunnelId,
                type = TunnelType.LOCAL,
                bindAddress = originatorAddress,
                bindPort = originatorPort,
                destinationHost = destinationHost,
                destinationPort = destinationPort,
                sessionId = session.id
            )
            registerLocalForward(tunnel)
            return true

        } catch (e: Exception) {
            Log.w(TAG, "Failed to establish direct-tcpip to $destinationHost:$destinationPort", e)
            return false
        }
    }

    // ---- Configuration ----

    /**
     * Load SSH server configuration from file.
     */
    private fun loadConfig() {
        val configFile = File(context.filesDir, "$SSH_DIR/$SSH_CONFIG_FILE")
        if (!configFile.exists()) return

        try {
            configFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach

                val eqIdx = trimmed.indexOf(' ')
                if (eqIdx < 0) return@forEach

                val key = trimmed.substring(0, eqIdx).trim().lowercase()
                val value = trimmed.substring(eqIdx + 1).trim()

                when (key) {
                    "port" -> value.toIntOrNull()?.let { port = it }
                    "passwordauthentication" -> passwordAuthEnabled = value == "yes"
                    "pubkeyauthentication" -> publicKeyAuthEnabled = value == "yes"
                    "permittunnel" -> portForwardingAllowed = value == "yes"
                    "shellpath" -> shellPath = value
                    "loginuser" -> loginUsername = value
                    "idletimeout" -> value.toIntOrNull()?.let { idleTimeoutSeconds = it }
                }
            }
            Log.d(TAG, "SSH config loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SSH config", e)
        }
    }

    /**
     * Generate the default sshd_config file content.
     */
    fun generateDefaultConfig(): String {
        return """
# TermX SSH Server Configuration
# Place in ~/.ssh/sshd_config

# Port to listen on
Port $DEFAULT_PORT

# Authentication methods
PasswordAuthentication yes
PubkeyAuthentication yes

# Shell path
ShellPath /system/bin/sh

# Login username
LoginUser termx

# Port forwarding
PermitTunnel yes

# Idle timeout in seconds (0 = no timeout)
IdleTimeout 0
        """.trimIndent()
    }

    /**
     * Write the default SSH config if it doesn't exist.
     */
    fun ensureDefaultConfig() {
        val sshDir = File(context.filesDir, SSH_DIR)
        if (!sshDir.exists()) sshDir.mkdirs()

        val configFile = File(sshDir, SSH_CONFIG_FILE)
        if (!configFile.exists()) {
            configFile.writeText(generateDefaultConfig())
            Log.i(TAG, "Created default SSH config at: ${configFile.absolutePath}")
        }

        // Ensure authorized_keys file exists
        val keysFile = File(sshDir, AUTHORIZED_KEYS_FILE)
        if (!keysFile.exists()) {
            keysFile.writeText("# TermX Authorized Keys\n# Add public keys, one per line\n")
        }
    }

    // ---- Session Management ----

    /**
     * Close a specific session by ID.
     */
    fun closeSession(sessionId: Int): Boolean {
        val session = sessions.remove(sessionId)
        if (session != null) {
            session.close()
            onSessionClosed?.invoke(session)
            return true
        }
        return false
    }

    /**
     * Close all active sessions.
     */
    fun closeAllSessions() {
        val sessionsCopy = sessions.values.toList()
        sessions.clear()
        sessionsCopy.forEach { session ->
            session.close()
            onSessionClosed?.invoke(session)
        }
    }

    // ---- Utility ----

    /**
     * Send a disconnect message and close a socket.
     */
    private fun sendDisconnectAndClose(socket: Socket, reason: Int, message: String) {
        try {
            val output = DataOutputStream(socket.getOutputStream())
            val payload = ByteArrayOutputStream()
            val dataOut = DataOutputStream(payload)
            dataOut.writeInt(reason)
            dataOut.write(encodeString(message))
            dataOut.write(encodeString("")) // language tag

            sendSshPacket(output, SSH_MSG_DISCONNECT, payload.toByteArray())
            output.flush()
        } catch (_: Exception) {
            // Best effort
        } finally {
            safeClose(socket)
        }
    }

    /**
     * Safely close a socket, ignoring errors.
     */
    private fun safeClose(socket: Socket) {
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: Exception) { }
    }

    /**
     * Cleanup all server resources.
     */
    private fun cleanup() {
        // Close all sessions
        closeAllSessions()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null

        // Shutdown thread pool
        threadPool?.shutdown()
        try {
            threadPool?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) { }
        threadPool = null

        // Interrupt acceptor thread
        acceptorThread?.interrupt()
        acceptorThread = null

        // Clear port forwarding
        portForwardTunnels.clear()

        startTime = 0L
    }

    /**
     * Get server status as a formatted string.
     */
    fun getStatusText(): String {
        val state = getState()
        return buildString {
            appendLine("TermX SSH Server")
            appendLine("  Status: ${if (state.running) "RUNNING" else "STOPPED"}")
            appendLine("  Port: ${state.port}")
            appendLine("  Bind: ${state.bindAddress}")
            appendLine("  Sessions: ${state.activeSessions}/${MAX_SESSIONS}")
            appendLine("  Uptime: ${state.formatUptime()}")
            appendLine("  Total connections: ${state.totalConnections}")
            appendLine("  Password auth: ${if (passwordAuthEnabled) "enabled" else "disabled"}")
            appendLine("  Public key auth: ${if (publicKeyAuthEnabled) "enabled" else "disabled"}")
            appendLine("  Port forwarding: ${if (portForwardingAllowed) "allowed" else "denied"}")
            appendLine("  Host key: ${hostKeyManager.getActiveKeyInfo()}")

            if (sessions.isNotEmpty()) {
                appendLine()
                appendLine("Active Sessions:")
                sessions.values.forEach { s ->
                    appendLine("  #${s.id}: ${s.username}@${s.clientAddress} " +
                            "(auth=${s.authMethod}, duration=${s.durationMs / 1000}s)")
                }
            }
        }
    }

<<<<<<< HEAD
    // ---- BigInteger wrapper (for DH) ----

    private class BigInteger private constructor(private val value: java.math.BigInteger) : Number(), Comparable<BigInteger> {

        constructor(hexStr: String, radix: Int) : this(java.math.BigInteger(hexStr, radix))
        constructor(ba: ByteArray) : this(java.math.BigInteger(ba))
        constructor(signum: Int, magnitude: ByteArray) : this(java.math.BigInteger(signum, magnitude))
        constructor(bitLength: Int, random: SecureRandom) : this(java.math.BigInteger(bitLength, random))

        fun modPow(exp: BigInteger, m: BigInteger): BigInteger =
            BigInteger(value.modPow(exp.value, m.value))

        fun abs(): BigInteger = BigInteger(value.abs())

        fun add(other: BigInteger): BigInteger = BigInteger(value.add(other.value))

        val bitLength: Int get() = value.bitLength()

        fun toByteArray(): ByteArray = value.toByteArray()

        override fun compareTo(other: BigInteger): Int = value.compareTo(other.value)

        override fun toByte(): Byte = value.toByte()
        override fun toChar(): Char = value.toChar()
        override fun toDouble(): Double = value.toDouble()
        override fun toFloat(): Float = value.toFloat()
        override fun toInt(): Int = value.toInt()
        override fun toLong(): Long = value.toLong()
        override fun toShort(): Short = value.toShort()

        companion object {
            val ONE: BigInteger = BigInteger(java.math.BigInteger.ONE)
            val ZERO: BigInteger = BigInteger(java.math.BigInteger.ZERO)
        }
    }
=======
>>>>>>> 0edb222 (Fix all 307 compilation errors - BUILD SUCCESSFUL)
}
