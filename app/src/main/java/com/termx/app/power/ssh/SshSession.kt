package com.termx.app.power.ssh

import android.util.Log
import com.termx.app.terminal.PtySession
import java.io.*
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents an active SSH session/connection.
 *
 * Each session corresponds to a single authenticated SSH connection from a remote
 * client. A session may have multiple channels:
 *
 *   - **Shell channel**: Interactive terminal access via PTY
 *   - **SFTP channel**: File transfer subsystem
 *   - **SCP channel**: Secure copy subsystem
 *   - **Direct-tcpip channel**: Port forwarding tunnel
 *
 * The session manages:
 *   - Client connection info (IP, port, username)
 *   - Authentication details and method used
 *   - Channel multiplexing (multiple channels per session)
 *   - PTY allocation for shell channels
 *   - Data flow between SSH client and local PTY/SFTP
 *   - Session logging for audit trails
 *   - Window size management for flow control
 *
 * Lifecycle:
 *   1. Created after successful SSH handshake and authentication
 *   2. Process loop handles channel open/close and data transfer
 *   3. Closed when client disconnects or server shuts down
 *
 * @param id Unique session identifier
 * @param clientAddress Client IP address
 * @param clientPort Client source port
 * @param socket The TCP socket for this connection
 * @param inputStream Input stream from the client
 * @param outputStream Output stream to the client
 * @param username Authenticated username
 * @param authMethod Authentication method used ("password" or "publickey")
 * @param serverRef Reference to the parent SshServer
 */
class SshSession(
    val id: Int,
    val clientAddress: String,
    val clientPort: Int,
    private val socket: Socket,
    private val inputStream: DataInputStream,
    private val outputStream: DataOutputStream,
    val username: String,
    val authMethod: String,
    private val serverRef: SshServer
) {

    companion object {
        private const val TAG = "SshSession"

        /** Maximum number of channels per session */
        const val MAX_CHANNELS = 20

        /** Default window size for channel flow control (1MB) */
        const val DEFAULT_WINDOW_SIZE = 1048576

        /** Maximum packet size for channel data */
        const val MAX_PACKET_SIZE = 32768

        /** Buffer size for reading PTY output */
        private const val PTY_READ_BUFFER_SIZE = 32768

        /** Maximum SFTP packet size */
        private const val SFTP_MAX_PACKET_SIZE = 65536

        /** SFTP protocol version supported */
        private const val SFTP_VERSION = 3

        // SSH message types (local reference)
        private const val SSH_MSG_GLOBAL_REQUEST = 80
        private const val SSH_MSG_REQUEST_SUCCESS = 81
        private const val SSH_MSG_REQUEST_FAILURE = 82
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

        // Channel open failure reasons
        private const val SSH_OPEN_ADMINISTRATIVELY_PROHIBITED = 1
        private const val SSH_OPEN_CONNECT_FAILED = 2
        private const val SSH_OPEN_UNKNOWN_CHANNEL_TYPE = 3
        private const val SSH_OPEN_RESOURCE_SHORTAGE = 4

        // Extended data types
        private const val EXTENDED_DATA_STDERR = 1

        // SFTP packet types
        private const val SSH_FXP_INIT = 1
        private const val SSH_FXP_VERSION = 2
        private const val SSH_FXP_OPEN = 3
        private const val SSH_FXP_CLOSE = 4
        private const val SSH_FXP_READ = 5
        private const val SSH_FXP_WRITE = 6
        private const val SSH_FXP_LSTAT = 7
        private const val SSH_FXP_FSTAT = 8
        private const val SSH_FXP_SETSTAT = 9
        private const val SSH_FXP_FSETSTAT = 10
        private const val SSH_FXP_OPENDIR = 11
        private const val SSH_FXP_READDIR = 12
        private const val SSH_FXP_REMOVE = 13
        private const val SSH_FXP_MKDIR = 14
        private const val SSH_FXP_RMDIR = 15
        private const val SSH_FXP_REALPATH = 16
        private const val SSH_FXP_STAT = 17
        private const val SSH_FXP_RENAME = 18
        private const val SSH_FXP_READLINK = 19
        private const val SSH_FXP_SYMLINK = 20
        private const val SSH_FXP_STATUS = 101
        private const val SSH_FXP_HANDLE = 102
        private const val SSH_FXP_DATA = 103
        private const val SSH_FXP_NAME = 104
        private const val SSH_FXP_ATTRS = 105
        private const val SSH_FXP_EXTENDED = 200
        private const val SSH_FXP_EXTENDED_REPLY = 201

        // SFTP status codes
        private const val SSH_FX_OK = 0
        private const val SSH_FX_EOF = 1
        private const val SSH_FX_NO_SUCH_FILE = 2
        private const val SSH_FX_PERMISSION_DENIED = 3
        private const val SSH_FX_FAILURE = 4
        private const val SSH_FX_BAD_MESSAGE = 5
        private const val SSH_FX_NO_CONNECTION = 6
        private const val SSH_FX_CONNECTION_LOST = 7
        private const val SSH_FX_OP_UNSUPPORTED = 8

        // File open flags
        private const val SSH_FXF_READ = 0x00000001
        private const val SSH_FXF_WRITE = 0x00000002
        private const val SSH_FXF_APPEND = 0x00000004
        private const val SSH_FXF_CREAT = 0x00000008
        private const val SSH_FXF_TRUNC = 0x00000010
        private const val SSH_FXF_EXCL = 0x00000020

        // File attribute flags
        private const val SSH_FILEXFER_ATTR_SIZE = 0x00000001
        private const val SSH_FILEXFER_ATTR_UIDGID = 0x00000002
        private const val SSH_FILEXFER_ATTR_PERMISSIONS = 0x00000004
        private const val SSH_FILEXFER_ATTR_ACMODTIME = 0x00000008
        private const val SSH_FILEXFER_ATTR_EXTENDED = -2147483648  // 0x80000000 as signed Int
    }

    // ---- Session State ----

    /** Whether this session is currently active */
    private val active = AtomicBoolean(true)

    /** Session start timestamp (set externally after auth) */
    var startTime: Long = System.currentTimeMillis()

    /** Session end timestamp */
    private var endTime: Long = 0L

    /** Duration of the session in milliseconds */
    val durationMs: Long get() = if (endTime > 0) endTime - startTime
        else if (active.get()) System.currentTimeMillis() - startTime else 0L

    /** Active channels keyed by channel ID */
    private val channels = ConcurrentHashMap<Int, SshChannel>()

    /** Channel ID counter */
    private val channelIdCounter = AtomicInteger(0)

    /** PTY sessions associated with shell channels */
    private val ptySessions = ConcurrentHashMap<Int, PtySession>()

    /** SFTP open file handles */
    private val sftpHandles = ConcurrentHashMap<String, SftpHandle>()

    /** Session log entries */
    private val sessionLog = CopyOnWriteArrayList<SessionLogEntry>()

    /** Thread for reading PTY output per channel */
    private val ptyReaderThreads = ConcurrentHashMap<Int, Thread>()

    // ---- Channel Management ----

    /**
     * Represents an SSH channel within this session.
     */
    data class SshChannel(
        val channelId: Int,
        val type: String,
        var senderWindow: Int,
        val senderMaxPacket: Int,
        var recipientWindow: Int = DEFAULT_WINDOW_SIZE,
        var recipientMaxPacket: Int = MAX_PACKET_SIZE,
        var recipientChannel: Int = -1,
        var ptyAllocated: Boolean = false,
        var ptyTerm: String = "xterm-256color",
        var ptyCols: Int = 80,
        var ptyRows: Int = 24,
        var ptyPixelWidth: Int = 640,
        var ptyPixelHeight: Int = 480,
        var subsystem: String? = null,
        var command: String? = null,
        var createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Represents an SFTP file handle.
     */
    data class SftpHandle(
        val id: String,
        val path: String,
        val flags: Int,
        val file: RandomAccessFile? = null,
        val isDirectory: Boolean = false,
        var directoryEntries: List<File>? = null,
        var directoryPosition: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * A session log entry for audit purposes.
     */
    data class SessionLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val event: String,
        val details: String = ""
    )

    // ---- Main Process Loop ----

    /**
     * Main session processing loop.
     *
     * Reads SSH packets from the client and dispatches them to the appropriate
     * handler based on message type. This loop runs until the session is closed.
     */
    fun processLoop() {
        log("session_start", "Session process loop started")

        try {
            while (active.get() && !socket.isClosed) {
                val packet = readSshPacket()
                if (packet == null) {
                    Log.d(TAG, "Session #$id: End of stream")
                    break
                }

                dispatchPacket(packet)
            }
        } catch (e: SocketException) {
            Log.d(TAG, "Session #$id: Socket closed: ${e.message}")
        } catch (e: IOException) {
            if (active.get()) {
                Log.w(TAG, "Session #$id: I/O error: ${e.message}")
            }
        } catch (e: Exception) {
            if (active.get()) {
                Log.e(TAG, "Session #$id: Unexpected error in process loop", e)
            }
        } finally {
            close()
        }
    }

    /**
     * Read an SSH packet from the client.
     */
    private fun readSshPacket(): SshPacket? {
        try {
            val packetLength = inputStream.readInt()
            if (packetLength < 5 || packetLength > 350000) return null

            val paddingLength = inputStream.readUnsignedByte()
            val payloadLength = packetLength - paddingLength - 1
            if (payloadLength < 1) return null

            val data = ByteArray(payloadLength)
            inputStream.readFully(data)

            val padding = ByteArray(paddingLength)
            inputStream.readFully(padding)

            val type = data[0].toInt() and 0xFF
            val payload = data.copyOfRange(1, data.size)

            return SshPacket(type, payload)

        } catch (e: IOException) {
            return null
        }
    }

    /**
     * Dispatch a received SSH packet to the appropriate handler.
     */
    private fun dispatchPacket(packet: SshPacket) {
        when (packet.type) {
            SSH_MSG_CHANNEL_OPEN -> handleChannelOpen(packet.payload)
            SSH_MSG_CHANNEL_REQUEST -> handleChannelRequest(packet.payload)
            SSH_MSG_CHANNEL_DATA -> handleChannelData(packet.payload)
            SSH_MSG_CHANNEL_EXTENDED_DATA -> handleChannelExtendedData(packet.payload)
            SSH_MSG_CHANNEL_WINDOW_ADJUST -> handleWindowAdjust(packet.payload)
            SSH_MSG_CHANNEL_EOF -> handleChannelEof(packet.payload)
            SSH_MSG_CHANNEL_CLOSE -> handleChannelClose(packet.payload)
            SSH_MSG_GLOBAL_REQUEST -> handleGlobalRequest(packet.payload)
            else -> {
                Log.d(TAG, "Session #$id: Unhandled message type ${packet.type}")
            }
        }
    }

    // ---- Channel Open ----

    /**
     * Handle a CHANNEL_OPEN request.
     * Supported channel types: session, direct-tcpip
     */
    private fun handleChannelOpen(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))

        try {
            val channelType = readSshString(input)
            val senderChannel = input.readInt()
            val senderWindow = input.readInt()
            val senderMaxPacket = input.readInt()

            Log.d(TAG, "Session #$id: Channel open request type=$channelType " +
                    "sender=$senderChannel window=$senderWindow maxPkt=$senderMaxPacket")

            when (channelType) {
                "session" -> handleSessionChannelOpen(
                    senderChannel, senderWindow, senderMaxPacket
                )
                "direct-tcpip" -> handleDirectTcpIpChannelOpen(
                    input, senderChannel, senderWindow, senderMaxPacket
                )
                else -> {
                    Log.w(TAG, "Session #$id: Unsupported channel type: $channelType")
                    sendChannelOpenFailure(senderChannel, SSH_OPEN_UNKNOWN_CHANNEL_TYPE,
                        "Unsupported channel type: $channelType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session #$id: Error parsing CHANNEL_OPEN", e)
        }
    }

    /**
     * Open a session channel (used for shell, SFTP, SCP, exec).
     */
    private fun handleSessionChannelOpen(
        senderChannel: Int, senderWindow: Int, senderMaxPacket: Int
    ) {
        if (channels.size >= MAX_CHANNELS) {
            sendChannelOpenFailure(senderChannel, SSH_OPEN_RESOURCE_SHORTAGE,
                "Too many channels")
            return
        }

        val channelId = channelIdCounter.incrementAndGet()
        val channel = SshChannel(
            channelId = channelId,
            type = "session",
            senderWindow = senderWindow,
            senderMaxPacket = senderMaxPacket,
            recipientChannel = senderChannel
        )
        channels[channelId] = channel

        sendChannelOpenConfirmation(channelId, senderChannel, senderWindow, senderMaxPacket)
        log("channel_open", "Session channel $channelId opened (remote=$senderChannel)")
    }

    /**
     * Open a direct-tcpip channel (for port forwarding).
     */
    private fun handleDirectTcpIpChannelOpen(
        input: DataInputStream, senderChannel: Int, senderWindow: Int, senderMaxPacket: Int
    ) {
        try {
            val destHost = readSshString(input)
            val destPort = input.readInt()
            val origHost = readSshString(input)
            val origPort = input.readInt()

            val success = serverRef.handleDirectTcpIpChannel(
                this, destHost, destPort, origHost, origPort
            )

            if (success) {
                val channelId = channelIdCounter.incrementAndGet()
                val channel = SshChannel(
                    channelId = channelId,
                    type = "direct-tcpip",
                    senderWindow = senderWindow,
                    senderMaxPacket = senderMaxPacket,
                    recipientChannel = senderChannel
                )
                channels[channelId] = channel
                sendChannelOpenConfirmation(channelId, senderChannel, senderWindow, senderMaxPacket)
                log("channel_open", "Direct-tcpip channel $channelId -> $destHost:$destPort")
            } else {
                sendChannelOpenFailure(senderChannel, SSH_OPEN_CONNECT_FAILED,
                    "Connection to $destHost:$destPort failed")
            }
        } catch (e: Exception) {
            sendChannelOpenFailure(senderChannel, SSH_OPEN_CONNECT_FAILED,
                "Failed to parse direct-tcpip request")
        }
    }

    // ---- Channel Request ----

    /**
     * Handle a CHANNEL_REQUEST message.
     * Supports: pty-req, shell, exec, subsystem, window-change, signal, env
     */
    private fun handleChannelRequest(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))

        try {
            val recipientChannel = input.readInt()
            val requestType = readSshString(input)
            val wantReply = input.readByte().toInt() != 0

            // Find the local channel that maps to this recipient channel
            val channel = channels.values.find { it.recipientChannel == recipientChannel }
            if (channel == null) {
                Log.w(TAG, "Session #$id: Channel request for unknown channel $recipientChannel")
                if (wantReply) sendChannelFailure(recipientChannel)
                return
            }

            Log.d(TAG, "Session #$id: Channel request type=$requestType " +
                    "channel=${channel.channelId} wantReply=$wantReply")

            when (requestType) {
                "pty-req" -> handlePtyRequest(channel, input, wantReply)
                "shell" -> handleShellRequest(channel, wantReply)
                "exec" -> handleExecRequest(channel, input, wantReply)
                "subsystem" -> handleSubsystemRequest(channel, input, wantReply)
                "window-change" -> handleWindowChange(channel, input)
                "signal" -> handleSignal(channel, input)
                "env" -> handleEnvRequest(channel, input, wantReply)
                "xon-xoff" -> { if (wantReply) sendChannelSuccess(recipientChannel) }
                "exit-status" -> { if (wantReply) sendChannelSuccess(recipientChannel) }
                "keepalive@openssh.com" -> { if (wantReply) sendChannelSuccess(recipientChannel) }
                else -> {
                    Log.d(TAG, "Session #$id: Unknown request type: $requestType")
                    if (wantReply) sendChannelFailure(recipientChannel)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session #$id: Error handling channel request", e)
        }
    }

    /**
     * Handle a PTY request.
     * Allocates a pseudo-terminal for the channel.
     */
    private fun handlePtyRequest(channel: SshChannel, input: DataInputStream, wantReply: Boolean) {
        try {
            val term = readSshString(input)
            val cols = input.readInt()
            val rows = input.readInt()
            val pixelWidth = input.readInt()
            val pixelHeight = input.readInt()
            val modesLen = input.readInt()
            val modes = ByteArray(modesLen)
            input.readFully(modes)

            channel.ptyAllocated = true
            channel.ptyTerm = term
            channel.ptyCols = cols
            channel.ptyRows = rows
            channel.ptyPixelWidth = pixelWidth
            channel.ptyPixelHeight = pixelHeight

            log("pty_request", "PTY allocated: $term ${cols}x${rows}")
            if (wantReply) sendChannelSuccess(channel.recipientChannel)
        } catch (e: Exception) {
            if (wantReply) sendChannelFailure(channel.recipientChannel)
        }
    }

    /**
     * Handle a shell request.
     * Starts a PTY session connecting to the TermX shell.
     */
    private fun handleShellRequest(channel: SshChannel, wantReply: Boolean) {
        try {
            startPtyForChannel(channel, null)
            if (wantReply) sendChannelSuccess(channel.recipientChannel)
            log("shell_request", "Shell started on channel ${channel.channelId}")
        } catch (e: Exception) {
            Log.e(TAG, "Session #$id: Failed to start shell", e)
            if (wantReply) sendChannelFailure(channel.recipientChannel)
        }
    }

    /**
     * Handle an exec request.
     * Runs a specific command in the PTY.
     */
    private fun handleExecRequest(channel: SshChannel, input: DataInputStream, wantReply: Boolean) {
        try {
            val command = readSshString(input)
            channel.command = command
            startPtyForChannel(channel, command)
            if (wantReply) sendChannelSuccess(channel.recipientChannel)
            log("exec_request", "Exec: $command")
        } catch (e: Exception) {
            if (wantReply) sendChannelFailure(channel.recipientChannel)
        }
    }

    /**
     * Handle a subsystem request (sftp, scp).
     */
    private fun handleSubsystemRequest(channel: SshChannel, input: DataInputStream, wantReply: Boolean) {
        try {
            val subsystem = readSshString(input)
            channel.subsystem = subsystem

            when (subsystem) {
                "sftp" -> {
                    // SFTP is handled inline via channel data
                    if (wantReply) sendChannelSuccess(channel.recipientChannel)
                    log("subsystem_sftp", "SFTP subsystem started on channel ${channel.channelId}")
                }
                "scp" -> {
                    // SCP is handled via exec with the scp command
                    if (wantReply) sendChannelSuccess(channel.recipientChannel)
                    log("subsystem_scp", "SCP subsystem started on channel ${channel.channelId}")
                }
                else -> {
                    Log.w(TAG, "Session #$id: Unknown subsystem: $subsystem")
                    if (wantReply) sendChannelFailure(channel.recipientChannel)
                }
            }
        } catch (e: Exception) {
            if (wantReply) sendChannelFailure(channel.recipientChannel)
        }
    }

    /**
     * Handle a window-change request.
     * Resizes the PTY for the channel.
     */
    private fun handleWindowChange(channel: SshChannel, input: DataInputStream) {
        try {
            val cols = input.readInt()
            val rows = input.readInt()
            val pixelWidth = input.readInt()
            val pixelHeight = input.readInt()

            channel.ptyCols = cols
            channel.ptyRows = rows

            // Resize the PTY if one is allocated
            ptySessions[channel.channelId]?.resize(cols, rows)
            log("window_change", "PTY resized to ${cols}x${rows}")
        } catch (e: Exception) {
            Log.w(TAG, "Session #$id: Error handling window change", e)
        }
    }

    /**
     * Handle a signal request.
     * Forwards the signal to the PTY process.
     */
    private fun handleSignal(channel: SshChannel, input: DataInputStream) {
        try {
            val signalName = readSshString(input)
            val signalNum = parseSignalName(signalName)

            if (signalNum > 0) {
                ptySessions[channel.channelId]?.sendSignal(signalNum)
                log("signal", "Signal $signalName ($signalNum) sent to PTY")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session #$id: Error handling signal", e)
        }
    }

    /**
     * Handle an environment variable request.
     */
    private fun handleEnvRequest(channel: SshChannel, input: DataInputStream, wantReply: Boolean) {
        try {
            val name = readSshString(input)
            val value = readSshString(input)
            log("env", "Env request: $name=$value (stored but not forwarded to PTY)")
            if (wantReply) sendChannelSuccess(channel.recipientChannel)
        } catch (e: Exception) {
            if (wantReply) sendChannelFailure(channel.recipientChannel)
        }
    }

    // ---- Channel Data ----

    /**
     * Handle CHANNEL_DATA — data from the client to be written to the PTY or SFTP.
     */
    private fun handleChannelData(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))

        try {
            val recipientChannel = input.readInt()
            val dataLen = input.readInt()
            val data = ByteArray(dataLen)
            input.readFully(data)

            // Find the channel
            val channel = channels.values.find { it.recipientChannel == recipientChannel } ?: return

            when {
                channel.subsystem == "sftp" -> handleSftpData(channel, data)
                channel.ptyAllocated || channel.command != null -> {
                    // Write data to the PTY
                    ptySessions[channel.channelId]?.write(data)
                }
                else -> {
                    Log.d(TAG, "Session #$id: Data on channel ${channel.channelId} with no PTY/subsystem")
                }
            }

            // Adjust window
            channel.recipientWindow -= dataLen
            if (channel.recipientWindow < DEFAULT_WINDOW_SIZE / 2) {
                sendWindowAdjust(channel.recipientChannel, DEFAULT_WINDOW_SIZE)
                channel.recipientWindow += DEFAULT_WINDOW_SIZE
            }

        } catch (e: Exception) {
            Log.w(TAG, "Session #$id: Error handling channel data", e)
        }
    }

    /**
     * Handle CHANNEL_EXTENDED_DATA (typically stderr).
     */
    private fun handleChannelExtendedData(payload: ByteArray) {
        // Extended data from client is unusual — typically only sent from server
        Log.d(TAG, "Session #$id: Received extended data (unusual)")
    }

    /**
     * Handle WINDOW_ADJUST message.
     */
    private fun handleWindowAdjust(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))
        try {
            val recipientChannel = input.readInt()
            val bytesToAdd = input.readInt()

            val channel = channels.values.find { it.recipientChannel == recipientChannel }
            channel?.let { ch ->
                ch.senderWindow += bytesToAdd
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session #$id: Error handling window adjust", e)
        }
    }

    /**
     * Handle CHANNEL_EOF.
     */
    private fun handleChannelEof(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))
        try {
            val recipientChannel = input.readInt()
            val channel = channels.values.find { it.recipientChannel == recipientChannel }

            channel?.let {
                Log.d(TAG, "Session #$id: EOF on channel ${it.channelId}")
                closePtyForChannel(it.channelId)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    /**
     * Handle CHANNEL_CLOSE.
     */
    private fun handleChannelClose(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))
        try {
            val recipientChannel = input.readInt()
            val channel = channels.values.find { it.recipientChannel == recipientChannel }

            channel?.let {
                closePtyForChannel(it.channelId)
                channels.remove(it.channelId)
                sendChannelClose(recipientChannel)
                log("channel_close", "Channel ${it.channelId} closed")
            }
        } catch (e: Exception) { /* ignore */ }
    }

    /**
     * Handle GLOBAL_REQUEST (typically for remote port forwarding).
     */
    private fun handleGlobalRequest(payload: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(payload))
        try {
            val requestName = readSshString(input)
            val wantReply = input.readByte().toInt() != 0

            Log.d(TAG, "Session #$id: Global request: $requestName (wantReply=$wantReply)")

            when (requestName) {
                "tcpip-forward" -> {
                    val bindHost = readSshString(input)
                    val bindPort = input.readInt()

                    if (serverRef.portForwardingAllowed) {
                        val tunnelId = "rport_${id}_${System.currentTimeMillis()}"
                        val tunnel = SshServer.PortForwardTunnel(
                            id = tunnelId,
                            type = SshServer.TunnelType.REMOTE,
                            bindAddress = bindHost,
                            bindPort = bindPort,
                            destinationHost = "localhost",
                            destinationPort = bindPort,
                            sessionId = id
                        )
                        serverRef.registerRemoteForward(tunnel)

                        if (wantReply) {
                            sendGlobalRequestSuccess(bindPort)
                        }
                        log("tcpip_forward", "Remote forward: $bindHost:$bindPort")
                    } else {
                        if (wantReply) sendGlobalRequestFailure()
                    }
                }
                "cancel-tcpip-forward" -> {
                    if (wantReply) sendGlobalRequestSuccess(0)
                }
                "keepalive@openssh.com" -> {
                    if (wantReply) sendGlobalRequestSuccess(0)
                }
                else -> {
                    if (wantReply) sendGlobalRequestFailure()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Session #$id: Error handling global request", e)
        }
    }

    // ---- PTY Management ----

    /**
     * Start a PTY session for a channel.
     *
     * @param channel The SSH channel requesting a PTY
     * @param command Optional command to execute (null for interactive shell)
     */
    private fun startPtyForChannel(channel: SshChannel, command: String?) {
        val shellPath = serverRef.shellPath
        val cwd = "/data/data/com.termx.app/files"

        // Build environment
        val env = mutableMapOf<String, String>()
        env["TERM"] = channel.ptyTerm
        env["COLUMNS"] = channel.ptyCols.toString()
        env["LINES"] = channel.ptyRows.toString()
        env["HOME"] = cwd
        env["PATH"] = "/data/data/com.termx.app/files/bin:/data/data/com.termx.app/files/usr/bin:/system/bin:/system/xbin"
        env["SHELL"] = shellPath
        env["USER"] = username
        env["LOGNAME"] = username
        env["SSH_CONNECTION"] = "$clientAddress $clientPort ${socket.localAddress?.hostAddress ?: "0.0.0.0"} ${socket.localPort}"

        // Create PTY session
        val pty = PtySession(
            shellPath = if (command != null) shellPath else shellPath,
            cwd = cwd,
            env = env
        )

        // Handle PTY output — send to SSH client as channel data
        pty.onOutput = { data ->
            if (active.get()) {
                sendChannelData(channel.recipientChannel, data)
            }
        }

        pty.onExit = { exitCode ->
            if (active.get()) {
                // Send exit status
                sendExitStatus(channel.recipientChannel, exitCode)
                sendChannelEof(channel.recipientChannel)
                sendChannelClose(channel.recipientChannel)
                closePtyForChannel(channel.channelId)
                channels.remove(channel.channelId)
                log("shell_exit", "Shell exited with code $exitCode")
            }
        }

        // Start the PTY
        pty.start()

        // Resize to match requested dimensions
        if (channel.ptyCols > 0 && channel.ptyRows > 0) {
            pty.resize(channel.ptyCols, channel.ptyRows)
        }

        // If a command was specified, send it
        if (command != null) {
            pty.write("$command\n")
        }

        ptySessions[channel.channelId] = pty

        // Start a reader thread to pump PTY output
        // (The PtySession already has its own reader, but we need to ensure
        // output is forwarded to the SSH client via the onOutput callback)
    }

    /**
     * Close the PTY for a given channel.
     */
    private fun closePtyForChannel(channelId: Int) {
        ptySessions.remove(channelId)?.close()
        ptyReaderThreads.remove(channelId)?.interrupt()
    }

    // ---- SFTP Handling ----

    /**
     * Handle incoming SFTP data on a channel.
     * SFTP operates as a subsystem within an SSH channel, with its own
     * request/response packet protocol.
     */
    private fun handleSftpData(channel: SshChannel, data: ByteArray) {
        try {
            val input = DataInputStream(ByteArrayInputStream(data))
            val sftpLen = input.readInt()
            val sftpType = input.readByte().toInt() and 0xFF
            val requestId = input.readInt()

            when (sftpType) {
                SSH_FXP_INIT -> handleSftpInit(channel, requestId, input)
                SSH_FXP_REALPATH -> handleSftpRealpath(channel, requestId, input)
                SSH_FXP_OPENDIR -> handleSftpOpenDir(channel, requestId, input)
                SSH_FXP_READDIR -> handleSftpReadDir(channel, requestId, input)
                SSH_FXP_OPEN -> handleSftpOpen(channel, requestId, input)
                SSH_FXP_READ -> handleSftpRead(channel, requestId, input)
                SSH_FXP_WRITE -> handleSftpWrite(channel, requestId, input)
                SSH_FXP_CLOSE -> handleSftpClose(channel, requestId, input)
                SSH_FXP_STAT, SSH_FXP_LSTAT -> handleSftpStat(channel, requestId, input, sftpType)
                SSH_FXP_FSTAT -> handleSftpFStat(channel, requestId, input)
                SSH_FXP_SETSTAT -> handleSftpSetStat(channel, requestId, input)
                SSH_FXP_MKDIR -> handleSftpMkdir(channel, requestId, input)
                SSH_FXP_RMDIR -> handleSftpRmdir(channel, requestId, input)
                SSH_FXP_REMOVE -> handleSftpRemove(channel, requestId, input)
                SSH_FXP_RENAME -> handleSftpRename(channel, requestId, input)
                SSH_FXP_READLINK -> handleSftpReadlink(channel, requestId, input)
                SSH_FXP_SYMLINK -> handleSftpSymlink(channel, requestId, input)
                SSH_FXP_EXTENDED -> handleSftpExtended(channel, requestId, input)
                else -> {
                    sendSftpStatus(channel, requestId, SSH_FX_OP_UNSUPPORTED,
                        "Unsupported SFTP operation: $sftpType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session #$id: SFTP error", e)
        }
    }

    /**
     * Handle SFTP INIT — protocol version negotiation.
     */
    private fun handleSftpInit(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val clientVersion = input.readInt()
        Log.d(TAG, "Session #$id: SFTP init, client version=$clientVersion")

        // Send version response
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(0) // length placeholder
        out.writeByte(SSH_FXP_VERSION)
        out.writeInt(SFTP_VERSION)

        // Extension announcements
        out.write(encodeSshString("posix-rename@openssh.com"))
        out.write(encodeSshString("1"))
        out.write(encodeSshString("statvfs@openssh.com"))
        out.write(encodeSshString("2"))

        val data = payload.toByteArray()
        // Fix length
        val len = data.size - 4
        data[0] = (len shr 24).toByte()
        data[1] = (len shr 16).toByte()
        data[2] = (len shr 8).toByte()
        data[3] = len.toByte()

        sendChannelData(channel.recipientChannel, data)
        log("sftp_init", "SFTP v$clientVersion, server v$SFTP_VERSION")
    }

    private fun handleSftpRealpath(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        sendSftpName(channel, requestId, listOf(SftpSubsystem.FileEntry(resolved, "", 0, 0, 0, 0)))
    }

    private fun handleSftpOpenDir(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        val dir = java.io.File(resolved)

        if (!dir.exists() || !dir.isDirectory) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "Directory not found: $path")
            return
        }

        val handleId = "dir_${System.nanoTime()}"
        val handle = SftpHandle(
            id = handleId, path = resolved, flags = SSH_FXF_READ,
            isDirectory = true,
            directoryEntries = dir.listFiles()?.toList() ?: emptyList(),
            directoryPosition = 0
        )
        sftpHandles[handleId] = handle

        sendSftpHandle(channel, requestId, handleId)
    }

    private fun handleSftpReadDir(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val handleId = readSshString(input)
        val handle = sftpHandles[handleId]

        if (handle == null || !handle.isDirectory) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "Invalid directory handle")
            return
        }

        val entries = handle.directoryEntries ?: emptyList()
        if (handle.directoryPosition >= entries.size) {
            sendSftpStatus(channel, requestId, SSH_FX_EOF, "End of directory")
            return
        }

        val batch = entries.subList(handle.directoryPosition,
            minOf(handle.directoryPosition + 100, entries.size))
        handle.directoryPosition += batch.size

        val fileEntries = batch.map { file ->
            SftpSubsystem.FileEntry(
                path = file.absolutePath,
                longName = formatFileListing(file),
                size = if (file.isFile) file.length() else 0,
                uid = 0, gid = 0,
                permissions = getFilePermissions(file),
                lastModified = file.lastModified()
            )
        }

        sendSftpName(channel, requestId, fileEntries)
    }

    private fun handleSftpOpen(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val flags = input.readInt()
        val resolved = serverRef.sftpSubsystem.resolvePath(path)

        try {
            val file = java.io.File(resolved)
            val mode = when {
                (flags and SSH_FXF_READ != 0) && (flags and SSH_FXF_WRITE != 0) -> "rw"
                (flags and SSH_FXF_WRITE != 0) -> "rw"
                else -> "r"
            }

            // Handle create/truncate flags
            if ((flags and SSH_FXF_CREAT) != 0 && !file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }

            val raf = RandomAccessFile(file, mode)

            if ((flags and SSH_FXF_TRUNC) != 0) {
                raf.setLength(0)
            }
            if ((flags and SSH_FXF_APPEND) != 0) {
                raf.seek(raf.length())
            }

            val handleId = "file_${System.nanoTime()}"
            val handle = SftpHandle(
                id = handleId, path = resolved, flags = flags,
                file = raf, isDirectory = false
            )
            sftpHandles[handleId] = handle

            sendSftpHandle(channel, requestId, handleId)

        } catch (e: Exception) {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Failed to open file: ${e.message}")
        }
    }

    private fun handleSftpRead(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val handleId = readSshString(input)
        val offset = input.readLong()
        val length = input.readInt()

        val handle = sftpHandles[handleId]
        if (handle == null || handle.isDirectory || handle.file == null) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "Invalid file handle")
            return
        }

        try {
            handle.file!!.seek(offset)
            val buffer = ByteArray(minOf(length, SFTP_MAX_PACKET_SIZE))
            val bytesRead = handle.file!!.read(buffer)

            if (bytesRead < 0) {
                sendSftpStatus(channel, requestId, SSH_FX_EOF, "End of file")
                return
            }

            sendSftpData(channel, requestId, buffer.copyOf(bytesRead))

        } catch (e: Exception) {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Read error: ${e.message}")
        }
    }

    private fun handleSftpWrite(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val handleId = readSshString(input)
        val offset = input.readLong()
        val dataLen = input.readInt()
        val data = ByteArray(dataLen)
        input.readFully(data)

        val handle = sftpHandles[handleId]
        if (handle == null || handle.isDirectory || handle.file == null) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "Invalid file handle")
            return
        }

        try {
            handle.file!!.seek(offset)
            handle.file!!.write(data)
            sendSftpStatus(channel, requestId, SSH_FX_OK, "Write successful")
        } catch (e: Exception) {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Write error: ${e.message}")
        }
    }

    private fun handleSftpClose(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val handleId = readSshString(input)
        val handle = sftpHandles.remove(handleId)

        try {
            handle?.file?.close()
        } catch (_: Exception) { }

        sendSftpStatus(channel, requestId, SSH_FX_OK, "Close successful")
    }

    private fun handleSftpStat(channel: SshChannel, requestId: Int, input: DataInputStream, type: Int) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        val file = java.io.File(resolved)

        if (!file.exists()) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "File not found: $path")
            return
        }

        sendSftpAttrs(channel, requestId, file)
    }

    private fun handleSftpFStat(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val handleId = readSshString(input)
        val handle = sftpHandles[handleId]

        if (handle == null) {
            sendSftpStatus(channel, requestId, SSH_FX_NO_SUCH_FILE, "Invalid handle")
            return
        }

        val file = java.io.File(handle.path)
        sendSftpAttrs(channel, requestId, file)
    }

    private fun handleSftpSetStat(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        // Read and discard attrs (best-effort on Android)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        sendSftpStatus(channel, requestId, SSH_FX_OK, "Setstat acknowledged (best-effort)")
    }

    private fun handleSftpMkdir(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        val dir = java.io.File(resolved)

        if (dir.mkdirs()) {
            sendSftpStatus(channel, requestId, SSH_FX_OK, "Directory created")
        } else {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Failed to create directory")
        }
    }

    private fun handleSftpRmdir(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        val dir = java.io.File(resolved)

        if (dir.deleteRecursively()) {
            sendSftpStatus(channel, requestId, SSH_FX_OK, "Directory removed")
        } else {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Failed to remove directory")
        }
    }

    private fun handleSftpRemove(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val path = readSshString(input)
        val resolved = serverRef.sftpSubsystem.resolvePath(path)
        val file = java.io.File(resolved)

        if (file.delete()) {
            sendSftpStatus(channel, requestId, SSH_FX_OK, "File removed")
        } else {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Failed to remove file")
        }
    }

    private fun handleSftpRename(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val oldPath = readSshString(input)
        val newPath = readSshString(input)
        val resolvedOld = serverRef.sftpSubsystem.resolvePath(oldPath)
        val resolvedNew = serverRef.sftpSubsystem.resolvePath(newPath)

        val oldFile = java.io.File(resolvedOld)
        val newFile = java.io.File(resolvedNew)

        if (oldFile.renameTo(newFile)) {
            sendSftpStatus(channel, requestId, SSH_FX_OK, "Rename successful")
        } else {
            sendSftpStatus(channel, requestId, SSH_FX_FAILURE, "Failed to rename")
        }
    }

    private fun handleSftpReadlink(channel: SshChannel, requestId: Int, input: DataInputStream) {
        sendSftpStatus(channel, requestId, SSH_FX_OP_UNSUPPORTED, "Symlinks not supported on Android")
    }

    private fun handleSftpSymlink(channel: SshChannel, requestId: Int, input: DataInputStream) {
        sendSftpStatus(channel, requestId, SSH_FX_OP_UNSUPPORTED, "Symlinks not supported on Android")
    }

    private fun handleSftpExtended(channel: SshChannel, requestId: Int, input: DataInputStream) {
        val name = readSshString(input)
        sendSftpStatus(channel, requestId, SSH_FX_OP_UNSUPPORTED, "Extended operation not supported: $name")
    }

    // ---- SFTP Response Helpers ----

    private fun sendSftpHandle(channel: SshChannel, requestId: Int, handleId: String) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(SSH_FXP_HANDLE)
        out.writeInt(requestId)
        out.write(encodeSshString(handleId))
        sendSftpPacket(channel, payload.toByteArray())
    }

    private fun sendSftpData(channel: SshChannel, requestId: Int, data: ByteArray) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(SSH_FXP_DATA)
        out.writeInt(requestId)
        out.writeInt(data.size)
        out.write(data)
        sendSftpPacket(channel, payload.toByteArray())
    }

    private fun sendSftpStatus(channel: SshChannel, requestId: Int, code: Int, message: String) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(SSH_FXP_STATUS)
        out.writeInt(requestId)
        out.writeInt(code)
        out.write(encodeSshString(message))
        out.write(encodeSshString("")) // language tag
        sendSftpPacket(channel, payload.toByteArray())
    }

    private fun sendSftpName(channel: SshChannel, requestId: Int, entries: List<SftpSubsystem.FileEntry>) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(SSH_FXP_NAME)
        out.writeInt(requestId)
        out.writeInt(entries.size)

        for (entry in entries) {
            out.write(encodeSshString(entry.path))
            out.write(encodeSshString(entry.longName))
            out.writeInt(SSH_FILEXFER_ATTR_SIZE or SSH_FILEXFER_ATTR_PERMISSIONS or SSH_FILEXFER_ATTR_ACMODTIME)
            out.writeLong(entry.size)
            out.writeInt(entry.permissions)
            out.writeInt((entry.lastModified / 1000).toInt()) // atime
            out.writeInt((entry.lastModified / 1000).toInt()) // mtime
        }

        sendSftpPacket(channel, payload.toByteArray())
    }

    private fun sendSftpAttrs(channel: SshChannel, requestId: Int, file: java.io.File) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeByte(SSH_FXP_ATTRS)
        out.writeInt(requestId)
        out.writeInt(SSH_FILEXFER_ATTR_SIZE or SSH_FILEXFER_ATTR_PERMISSIONS or SSH_FILEXFER_ATTR_ACMODTIME)
        out.writeLong(if (file.isFile) file.length() else 0)
        out.writeInt(getFilePermissions(file))
        out.writeInt((file.lastModified() / 1000).toInt())
        out.writeInt((file.lastModified() / 1000).toInt())
        sendSftpPacket(channel, payload.toByteArray())
    }

    private fun sendSftpPacket(channel: SshChannel, sftpPayload: ByteArray) {
        val packet = ByteArrayOutputStream()
        val out = DataOutputStream(packet)
        out.writeInt(sftpPayload.size)
        out.write(sftpPayload)
        sendChannelData(channel.recipientChannel, packet.toByteArray())
    }

    // ---- SSH Message Senders ----

    /**
     * SSH packet representation for internal use.
     */
    data class SshPacket(val type: Int, val payload: ByteArray)

    private fun sendChannelOpenConfirmation(
        channelId: Int, recipientChannel: Int, window: Int, maxPacket: Int
    ) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        out.writeInt(channelId)
        out.writeInt(window)
        out.writeInt(maxPacket)
        sendSshMessage(SSH_MSG_CHANNEL_OPEN_CONFIRMATION, payload.toByteArray())
    }

    private fun sendChannelOpenFailure(recipientChannel: Int, reason: Int, description: String) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        out.writeInt(reason)
        out.write(encodeSshString(description))
        out.write(encodeSshString("")) // language tag
        sendSshMessage(SSH_MSG_CHANNEL_OPEN_FAILURE, payload.toByteArray())
    }

    private fun sendChannelData(recipientChannel: Int, data: ByteArray) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        out.writeInt(data.size)
        out.write(data)
        sendSshMessage(SSH_MSG_CHANNEL_DATA, payload.toByteArray())
    }

    private fun sendChannelEof(recipientChannel: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        sendSshMessage(SSH_MSG_CHANNEL_EOF, payload.toByteArray())
    }

    private fun sendChannelClose(recipientChannel: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        sendSshMessage(SSH_MSG_CHANNEL_CLOSE, payload.toByteArray())
    }

    private fun sendWindowAdjust(recipientChannel: Int, bytesToAdd: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        out.writeInt(bytesToAdd)
        sendSshMessage(SSH_MSG_CHANNEL_WINDOW_ADJUST, payload.toByteArray())
    }

    private fun sendChannelSuccess(recipientChannel: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        sendSshMessage(SSH_MSG_CHANNEL_SUCCESS, payload.toByteArray())
    }

    private fun sendChannelFailure(recipientChannel: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        sendSshMessage(SSH_MSG_CHANNEL_FAILURE, payload.toByteArray())
    }

    private fun sendExitStatus(recipientChannel: Int, exitCode: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(recipientChannel)
        out.write(encodeSshString("exit-status"))
        out.writeByte(0) // want reply = false
        out.writeInt(exitCode)
        sendSshMessage(SSH_MSG_CHANNEL_REQUEST, payload.toByteArray())
    }

    private fun sendGlobalRequestSuccess(port: Int) {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        out.writeInt(port)
        sendSshMessage(SSH_MSG_REQUEST_SUCCESS, payload.toByteArray())
    }

    private fun sendGlobalRequestFailure() {
        sendSshMessage(SSH_MSG_REQUEST_FAILURE, ByteArray(0))
    }

    /**
     * Send an SSH message packet.
     */
    private fun sendSshMessage(type: Int, payload: ByteArray) {
        try {
            synchronized(outputStream) {
                val blockSize = 8
                val packetLen = 1 + payload.size
                val paddingLen = blockSize - ((packetLen + 1) % blockSize)
                val adjustedPadding = if (paddingLen < 4) paddingLen + blockSize else paddingLen

                val padding = ByteArray(adjustedPadding)

                outputStream.writeInt(packetLen + adjustedPadding)
                outputStream.writeByte(adjustedPadding)
                outputStream.writeByte(type)
                outputStream.write(payload)
                outputStream.write(padding)
                outputStream.flush()
            }
        } catch (e: IOException) {
            if (active.get()) {
                Log.w(TAG, "Session #$id: Failed to send SSH message type $type: ${e.message}")
            }
        }
    }

    // ---- Utility ----

    private fun readSshString(input: DataInputStream): String {
        val len = input.readInt()
        val data = ByteArray(len)
        input.readFully(data)
        return String(data, StandardCharsets.UTF_8)
    }

    private fun encodeSshString(s: String): ByteArray {
        val data = s.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeInt(data.size)
        out.write(data)
        return buf.toByteArray()
    }

    /**
     * Parse an SSH signal name to a signal number.
     */
    private fun parseSignalName(name: String): Int {
        return when (name) {
            "HUP" -> 1
            "INT" -> 2
            "QUIT" -> 3
            "KILL" -> 9
            "TERM" -> 15
            "TSTP" -> 20
            "CONT" -> 18
            "WINCH" -> 28
            "USR1" -> 10
            "USR2" -> 12
            else -> 0
        }
    }

    /**
     * Format a file listing (ls -l style) for SFTP long name.
     */
    private fun formatFileListing(file: java.io.File): String {
        val perms = if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
        val size = if (file.isFile) file.length() else 4096L
        val date = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(file.lastModified()))
        return "$perms 1 $username $username $size $date ${file.name}"
    }

    /**
     * Get Unix-style permissions for a file on Android.
     */
    private fun getFilePermissions(file: java.io.File): Int {
        var perms = 0
        if (file.canRead()) perms = perms or 0x124 // r--r--r--
        if (file.canWrite()) perms = perms or 0x092 // -w--w--w-
        if (file.canExecute()) perms = perms or 0x049 // --x--x--x
        if (file.isDirectory) perms = perms or 0x4000 // directory flag
        return perms
    }

    /**
     * Log a session event.
     */
    private fun log(event: String, details: String = "") {
        sessionLog.add(SessionLogEntry(event = event, details = details))
        if (sessionLog.size > 1000) {
            // Keep only the last 500 entries
            val excess = sessionLog.toList().take(sessionLog.size - 500)
            sessionLog.removeAll(excess)
        }
    }

    /**
     * Get session log entries.
     */
    fun getSessionLog(): List<SessionLogEntry> = sessionLog.toList()

    /**
     * Get session info as a formatted string.
     */
    fun getInfo(): String {
        return buildString {
            appendLine("Session #$id")
            appendLine("  User: $username")
            appendLine("  Client: $clientAddress:$clientPort")
            appendLine("  Auth: $authMethod")
            appendLine("  Duration: ${durationMs / 1000}s")
            appendLine("  Channels: ${channels.size}")
            appendLine("  Active: ${active.get()}")
        }
    }

    /**
     * Close this session and release all resources.
     */
    fun close() {
        if (!active.getAndSet(false)) return

        endTime = System.currentTimeMillis()
        log("session_end", "Session closed after ${durationMs / 1000}s")

        // Close all PTY sessions
        ptySessions.keys.toList().forEach { channelId ->
            closePtyForChannel(channelId)
        }
        ptySessions.clear()

        // Close all SFTP handles
        sftpHandles.values.forEach { handle ->
            try { handle.file?.close() } catch (_: Exception) { }
        }
        sftpHandles.clear()

        // Close all channels
        channels.clear()

        // Close socket
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: Exception) { }

        // Interrupt reader threads
        ptyReaderThreads.values.forEach { it.interrupt() }
        ptyReaderThreads.clear()

        Log.i(TAG, "Session #$id closed (user=$username, duration=${durationMs}ms)")
    }
}
