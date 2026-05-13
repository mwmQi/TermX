package com.termx.app.power.tunnel

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Traffic statistics tracking for TermX tunnels.
 *
 * Provides comprehensive metrics for each tunnel including aggregate
 * byte counts, connection tracking, throughput calculations, and
 * per-connection breakdowns. Statistics are tracked in real-time using
 * atomic operations for thread safety.
 *
 * Usage:
 * ```kotlin
 * val stats = TunnelStatistics("tunnel-123")
 * stats.recordBytesSent(1024)
 * stats.recordBytesReceived(2048)
 * stats.recordConnection(InetSocketAddress("10.0.0.1", 43210))
 * // ...
 * val summary = stats.getSummary()
 * ```
 *
 * Metrics tracked:
 *   - Bytes sent / received (aggregate)
 *   - Connections accepted / currently active
 *   - Average throughput (bytes/sec) for send and receive
 *   - Peak throughput for send and receive
 *   - Total connection duration
 *   - Error count
 *   - Per-connection stats (IP, bytes, duration)
 */
class TunnelStatistics(
    /** The tunnel ID these statistics belong to. */
    val tunnelId: String
) {

    companion object {
        private const val TAG = "TunnelStatistics"

        // Throughput calculation window in milliseconds
        private const val THROUGHPUT_WINDOW_MS = 5000L

        // Maximum number of per-connection records to retain
        private const val MAX_CONNECTION_HISTORY = 500
    }

    // ── Aggregate Counters ──────────────────────────────────────────────

    /** Total bytes sent through this tunnel. */
    private val bytesSent = AtomicLong(0)

    /** Total bytes received through this tunnel. */
    private val bytesReceived = AtomicLong(0)

    /** Total number of connections accepted (including closed). */
    private val connectionsAccepted = AtomicLong(0)

    /** Number of currently active connections. */
    private val connectionsActive = AtomicLong(0)

    /** Number of connection/errors encountered. */
    private val errorCount = AtomicLong(0)

    // ── Throughput Tracking ─────────────────────────────────────────────

    /** Timestamp of the last throughput sample for sent bytes. */
    @Volatile
    private var lastSentSampleTime: Long = System.currentTimeMillis()

    /** Bytes sent at the last throughput sample point. */
    private val lastSentSampleBytes = AtomicLong(0)

    /** Current average send throughput in bytes/sec. */
    @Volatile
    private var avgSendThroughput: Double = 0.0

    /** Peak send throughput in bytes/sec. */
    @Volatile
    private var peakSendThroughput: Double = 0.0

    /** Timestamp of the last throughput sample for received bytes. */
    @Volatile
    private var lastRecvSampleTime: Long = System.currentTimeMillis()

    /** Bytes received at the last throughput sample point. */
    private val lastRecvSampleBytes = AtomicLong(0)

    /** Current average receive throughput in bytes/sec. */
    @Volatile
    private var avgRecvThroughput: Double = 0.0

    /** Peak receive throughput in bytes/sec. */
    @Volatile
    private var peakRecvThroughput: Double = 0.0

    // ── Timing ──────────────────────────────────────────────────────────

    /** When this tunnel was started. */
    @Volatile
    var startedAt: Long = 0L
        private set

    /** When this tunnel was stopped. 0 if still running. */
    @Volatile
    var stoppedAt: Long = 0L
        private set

    // ── Per-Connection Tracking ─────────────────────────────────────────

    /**
     * Per-connection statistics. Key is a unique connection ID.
     * Thread-safe concurrent map; oldest entries are evicted when
     * MAX_CONNECTION_HISTORY is reached.
     */
    private val connectionStats = ConcurrentHashMap<String, ConnectionStats>()

    /** Ordered list of connection IDs for FIFO eviction. */
    private val connectionOrder = Collections.synchronizedList(mutableListOf<String>())

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Mark the tunnel as started. Resets timing counters.
     */
    fun markStarted() {
        startedAt = System.currentTimeMillis()
        stoppedAt = 0L
        lastSentSampleTime = startedAt
        lastRecvSampleTime = startedAt
        lastSentSampleBytes.set(0)
        lastRecvSampleBytes.set(0)
    }

    /**
     * Mark the tunnel as stopped.
     */
    fun markStopped() {
        stoppedAt = System.currentTimeMillis()
    }

    /**
     * Record bytes sent through the tunnel.
     *
     * @param count Number of bytes sent.
     */
    fun recordBytesSent(count: Long) {
        bytesSent.addAndGet(count)
        updateSendThroughput()
    }

    /**
     * Record bytes received through the tunnel.
     *
     * @param count Number of bytes received.
     */
    fun recordBytesReceived(count: Long) {
        bytesReceived.addAndGet(count)
        updateRecvThroughput()
    }

    /**
     * Record a new incoming connection.
     *
     * @param clientAddress The remote address of the connecting client.
     * @return A connection ID for tracking this connection.
     */
    fun recordConnection(clientAddress: java.net.InetSocketAddress): String {
        val connId = "conn-${connectionsAccepted.incrementAndGet()}"
        connectionsActive.incrementAndGet()

        val stats = ConnectionStats(
            id = connId,
            clientIp = clientAddress.address.hostAddress ?: "unknown",
            clientPort = clientAddress.port,
            connectedAt = System.currentTimeMillis()
        )
        connectionStats[connId] = stats
        connectionOrder.add(connId)

        // Evict oldest entries if over limit
        while (connectionOrder.size > MAX_CONNECTION_HISTORY) {
            val oldestId = connectionOrder.removeAt(0)
            connectionStats.remove(oldestId)
        }

        return connId
    }

    /**
     * Record that a connection has been closed.
     *
     * @param connId The connection ID returned by recordConnection.
     */
    fun recordDisconnection(connId: String) {
        connectionsActive.decrementAndGet()
        connectionStats[connId]?.let {
            it.disconnectedAt = System.currentTimeMillis()
        }
    }

    /**
     * Record an error.
     */
    fun recordError() {
        errorCount.incrementAndGet()
    }

    // ── Throughput Calculation ──────────────────────────────────────────

    /**
     * Update the send throughput calculation using a sliding window.
     */
    private fun updateSendThroughput() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSentSampleTime
        if (elapsed >= THROUGHPUT_WINDOW_MS) {
            val currentBytes = bytesSent.get()
            val deltaBytes = currentBytes - lastSentSampleBytes.get()
            val throughput = (deltaBytes.toDouble() / elapsed) * 1000.0

            avgSendThroughput = if (avgSendThroughput == 0.0) {
                throughput
            } else {
                // Exponential moving average
                avgSendThroughput * 0.7 + throughput * 0.3
            }

            if (throughput > peakSendThroughput) {
                peakSendThroughput = throughput
            }

            lastSentSampleTime = now
            lastSentSampleBytes.set(currentBytes)
        }
    }

    /**
     * Update the receive throughput calculation using a sliding window.
     */
    private fun updateRecvThroughput() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRecvSampleTime
        if (elapsed >= THROUGHPUT_WINDOW_MS) {
            val currentBytes = bytesReceived.get()
            val deltaBytes = currentBytes - lastRecvSampleBytes.get()
            val throughput = (deltaBytes.toDouble() / elapsed) * 1000.0

            avgRecvThroughput = if (avgRecvThroughput == 0.0) {
                throughput
            } else {
                avgRecvThroughput * 0.7 + throughput * 0.3
            }

            if (throughput > peakRecvThroughput) {
                peakRecvThroughput = throughput
            }

            lastRecvSampleTime = now
            lastRecvSampleBytes.set(currentBytes)
        }
    }

    // ── Query Methods ───────────────────────────────────────────────────

    /** Get total bytes sent. */
    fun getBytesSent(): Long = bytesSent.get()

    /** Get total bytes received. */
    fun getBytesReceived(): Long = bytesReceived.get()

    /** Get total bytes (sent + received). */
    fun getTotalBytes(): Long = bytesSent.get() + bytesReceived.get()

    /** Get number of accepted connections. */
    fun getConnectionsAccepted(): Long = connectionsAccepted.get()

    /** Get number of currently active connections. */
    fun getConnectionsActive(): Long = connectionsActive.get()

    /** Get error count. */
    fun getErrorCount(): Long = errorCount.get()

    /** Get average send throughput in bytes/sec. */
    fun getAvgSendThroughput(): Double = avgSendThroughput

    /** Get peak send throughput in bytes/sec. */
    fun getPeakSendThroughput(): Double = peakSendThroughput

    /** Get average receive throughput in bytes/sec. */
    fun getAvgRecvThroughput(): Double = avgRecvThroughput

    /** Get peak receive throughput in bytes/sec. */
    fun getPeakRecvThroughput(): Double = peakRecvThroughput

    /**
     * Get the duration this tunnel has been running (or ran for if stopped).
     *
     * @return Duration in milliseconds.
     */
    fun getDurationMs(): Long {
        val end = if (stoppedAt > 0) stoppedAt else System.currentTimeMillis()
        return if (startedAt > 0) end - startedAt else 0L
    }

    /**
     * Get a snapshot of all connection statistics.
     *
     * @return List of ConnectionStats for all tracked connections.
     */
    fun getConnectionStats(): List<ConnectionStats> {
        return connectionStats.values.toList()
    }

    /**
     * Get a specific connection's stats.
     *
     * @param connId The connection ID.
     * @return The ConnectionStats, or null if not found.
     */
    fun getConnectionStats(connId: String): ConnectionStats? {
        return connectionStats[connId]
    }

    /**
     * Get a human-readable summary of these statistics.
     *
     * @return A formatted summary string.
     */
    fun getSummary(): String {
        val duration = getDurationMs()
        val durationStr = formatDuration(duration)
        val totalBytes = getTotalBytes()

        return buildString {
            appendLine("=== Tunnel Statistics: $tunnelId ===")
            appendLine("Duration:        $durationStr")
            appendLine("Bytes Sent:      ${formatBytes(bytesSent.get())}")
            appendLine("Bytes Received:  ${formatBytes(bytesReceived.get())}")
            appendLine("Total Bytes:     ${formatBytes(totalBytes)}")
            appendLine("Connections:     ${connectionsAccepted.get()} accepted, ${connectionsActive.get()} active")
            appendLine("Send Throughput: ${formatThroughput(avgSendThroughput)} avg, ${formatThroughput(peakSendThroughput)} peak")
            appendLine("Recv Throughput: ${formatThroughput(avgRecvThroughput)} avg, ${formatThroughput(peakRecvThroughput)} peak")
            appendLine("Errors:          ${errorCount.get()}")
        }
    }

    /**
     * Reset all statistics counters to zero.
     */
    fun reset() {
        bytesSent.set(0)
        bytesReceived.set(0)
        connectionsAccepted.set(0)
        connectionsActive.set(0)
        errorCount.set(0)
        avgSendThroughput = 0.0
        peakSendThroughput = 0.0
        avgRecvThroughput = 0.0
        peakRecvThroughput = 0.0
        startedAt = 0L
        stoppedAt = 0L
        connectionStats.clear()
        connectionOrder.clear()
    }

    // ── JSON Export ─────────────────────────────────────────────────────

    /**
     * Export statistics to a JSON object.
     *
     * @return A JSONObject containing all statistics.
     */
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("tunnelId", tunnelId)
            put("bytesSent", bytesSent.get())
            put("bytesReceived", bytesReceived.get())
            put("totalBytes", getTotalBytes())
            put("connectionsAccepted", connectionsAccepted.get())
            put("connectionsActive", connectionsActive.get())
            put("errorCount", errorCount.get())
            put("avgSendThroughput", avgSendThroughput)
            put("peakSendThroughput", peakSendThroughput)
            put("avgRecvThroughput", avgRecvThroughput)
            put("peakRecvThroughput", peakRecvThroughput)
            put("durationMs", getDurationMs())
            put("startedAt", startedAt)
            put("stoppedAt", stoppedAt)

            val connectionsArray = JSONArray()
            connectionStats.values.forEach { conn ->
                connectionsArray.put(conn.toJSON())
            }
            put("connections", connectionsArray)
        }
    }

    /**
     * Export statistics as a formatted JSON string.
     *
     * @return A formatted JSON string.
     */
    fun toJSONString(): String = toJSON().toString(2)

    // ── Formatting Helpers ──────────────────────────────────────────────

    /**
     * Format a byte count into a human-readable string.
     * Examples: "1.5 KB", "2.3 MB", "1.1 GB"
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.1f GB".format(gb)
    }

    /**
     * Format a throughput value into a human-readable string.
     * Examples: "512 B/s", "1.5 KB/s", "100.0 MB/s"
     */
    private fun formatThroughput(bytesPerSec: Double): String {
        if (bytesPerSec < 1024) return "%.0f B/s".format(bytesPerSec)
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return "%.1f KB/s".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB/s".format(mb)
    }

    /**
     * Format a duration in milliseconds into a human-readable string.
     * Examples: "5s", "1m 30s", "2h 15m"
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

/**
 * Statistics for a single connection within a tunnel.
 */
class ConnectionStats(
    /** Unique connection identifier. */
    val id: String,

    /** Client IP address. */
    val clientIp: String,

    /** Client source port. */
    val clientPort: Int,

    /** Timestamp when the connection was established. */
    val connectedAt: Long
) {
    /** Timestamp when the connection was closed. 0 if still active. */
    var disconnectedAt: Long = 0L

    /** Bytes sent on this connection. */
    private val bytesSent = AtomicLong(0)

    /** Bytes received on this connection. */
    private val bytesReceived = AtomicLong(0)

    /** Record bytes sent on this connection. */
    fun recordBytesSent(count: Long) {
        bytesSent.addAndGet(count)
    }

    /** Record bytes received on this connection. */
    fun recordBytesReceived(count: Long) {
        bytesReceived.addAndGet(count)
    }

    /** Get bytes sent. */
    fun getBytesSent(): Long = bytesSent.get()

    /** Get bytes received. */
    fun getBytesReceived(): Long = bytesReceived.get()

    /** Get total bytes (sent + received). */
    fun getTotalBytes(): Long = bytesSent.get() + bytesReceived.get()

    /**
     * Get the duration of this connection.
     *
     * @return Duration in milliseconds.
     */
    fun getDurationMs(): Long {
        val end = if (disconnectedAt > 0) disconnectedAt else System.currentTimeMillis()
        return end - connectedAt
    }

    /**
     * Check if this connection is still active.
     */
    fun isActive(): Boolean = disconnectedAt == 0L

    /**
     * Export this connection's stats to JSON.
     */
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("clientIp", clientIp)
            put("clientPort", clientPort)
            put("connectedAt", connectedAt)
            put("disconnectedAt", disconnectedAt)
            put("bytesSent", bytesSent.get())
            put("bytesReceived", bytesReceived.get())
            put("totalBytes", getTotalBytes())
            put("durationMs", getDurationMs())
            put("active", isActive())
        }
    }

    /**
     * Get a summary string for this connection.
     */
    fun toSummaryLine(): String {
        val duration = getDurationMs() / 1000
        val status = if (isActive()) "ACTIVE" else "CLOSED"
        return "[$id] $clientIp:$clientPort - ${getTotalBytes()} bytes, ${duration}s [$status]"
    }
}
