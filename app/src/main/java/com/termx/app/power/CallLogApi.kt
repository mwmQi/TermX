package com.termx.app.power

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Call Log API for TermX — access call history from terminal.
 *
 * Provides comprehensive call log access including listing, filtering by type,
 * statistics calculation, duration summaries, lookup by phone number, and
 * deletion of call log entries. All output is formatted for terminal display.
 *
 * Shell usage:
 *   termx-calllog list [limit]        List recent calls
 *   termx-calllog incoming [limit]    List incoming calls
 *   termx-calllog outgoing [limit]    List outgoing calls
 *   termx-calllog missed [limit]      List missed calls
 *   termx-calllog stats               Call statistics
 *   termx-calllog duration            Total call duration
 *   termx-calllog by-number <number>  Calls by number
 *   termx-calllog delete <id>         Delete call log entry
 *   termx-calllog clear               Clear all call log
 *
 * Requires: READ_CALL_LOG permission for reading, WRITE_CALL_LOG for deleting/clearing.
 */
@SuppressLint("MissingPermission")
object CallLogApi {

    private const val TAG = "CallLogApi"

    // Date format for displaying call timestamps
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /**
     * Data class representing a single call log entry.
     */
    data class CallEntry(
        val id: Long,
        val number: String,
        val name: String?,
        val type: Int,
        val typeLabel: String,
        val date: Long,
        val dateFormatted: String,
        val duration: Long,
        val durationFormatted: String,
        val isNew: Boolean,
        val isRead: Boolean
    ) {
        fun toFormattedString(): String = buildString {
            append("[$id] ")
            append(dateFormatted)
            append(" | ")
            append(typeLabel)
            append(" | ")
            append(if (name.isNullOrBlank()) number else "$name ($number)")
            append(" | ")
            append(durationFormatted)
            if (isNew) append(" [NEW]")
        }

        fun toDetailedString(): String = buildString {
            appendLine("=== Call #$id ===")
            appendLine("  Number:     $number")
            appendLine("  Name:       ${name ?: "Unknown"}")
            appendLine("  Type:       $typeLabel")
            appendLine("  Date:       $dateFormatted")
            appendLine("  Duration:   $durationFormatted")
            appendLine("  New:        $isNew")
            appendLine("  Read:       $isRead")
        }
    }

    /**
     * Data class for call statistics.
     */
    data class CallStats(
        val totalCalls: Int,
        val incomingCalls: Int,
        val outgoingCalls: Int,
        val missedCalls: Int,
        val rejectedCalls: Int,
        val blockedCalls: Int,
        val totalDuration: Long,
        val avgDuration: Long,
        val longestCall: Long,
        val uniqueNumbers: Int
    ) {
        fun toFormattedString(): String = buildString {
            appendLine("=== Call Statistics ===")
            appendLine("Total Calls:       $totalCalls")
            appendLine("Incoming:          $incomingCalls")
            appendLine("Outgoing:          $outgoingCalls")
            appendLine("Missed:            $missedCalls")
            appendLine("Rejected:          $rejectedCalls")
            appendLine("Blocked:           $blockedCalls")
            appendLine("Unique Numbers:    $uniqueNumbers")
            appendLine("Total Duration:    ${formatDuration(totalDuration)}")
            appendLine("Average Duration:  ${formatDuration(avgDuration)}")
            appendLine("Longest Call:      ${formatDuration(longestCall)}")
        }
    }

    // ---- Public API Methods ----

    /**
     * List recent calls from the call log.
     *
     * @param limit Maximum number of calls to return (default 20)
     * @return Formatted string with call list
     */
    fun listCalls(context: Context, limit: Int = 20): String {
        return try {
            val calls = queryCalls(context, limit = limit)
            if (calls.isEmpty()) {
                "No calls in call log"
            } else {
                buildString {
                    appendLine("=== Recent Calls (showing ${calls.size}) ===")
                    calls.forEach { appendLine(it.toFormattedString()) }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CALL_LOG permission required", e)
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list calls", e)
            "Error listing calls: ${e.message}"
        }
    }

    /**
     * List incoming calls only.
     *
     * @param limit Maximum number of calls to return
     */
    fun listIncoming(context: Context, limit: Int = 20): String {
        return listByType(context, CallLog.Calls.INCOMING_TYPE, "Incoming", limit)
    }

    /**
     * List outgoing calls only.
     *
     * @param limit Maximum number of calls to return
     */
    fun listOutgoing(context: Context, limit: Int = 20): String {
        return listByType(context, CallLog.Calls.OUTGOING_TYPE, "Outgoing", limit)
    }

    /**
     * List missed calls only.
     *
     * @param limit Maximum number of calls to return
     */
    fun listMissed(context: Context, limit: Int = 20): String {
        return listByType(context, CallLog.Calls.MISSED_TYPE, "Missed", limit)
    }

    /**
     * List rejected calls only.
     *
     * @param limit Maximum number of calls to return
     */
    fun listRejected(context: Context, limit: Int = 20): String {
        return listByType(context, CallLog.Calls.REJECTED_TYPE, "Rejected", limit)
    }

    /**
     * List blocked calls only.
     *
     * @param limit Maximum number of calls to return
     */
    fun listBlocked(context: Context, limit: Int = 20): String {
        return listByType(context, CallLog.Calls.BLOCKED_TYPE, "Blocked", limit)
    }

    /**
     * Get comprehensive call statistics.
     * Calculates totals, averages, and breakdowns by call type.
     */
    fun getStats(context: Context): String {
        return try {
            val calls = queryCalls(context, limit = Int.MAX_VALUE)
            if (calls.isEmpty()) {
                return "No calls in call log — statistics unavailable"
            }

            val incomingCount = calls.count { it.type == CallLog.Calls.INCOMING_TYPE }
            val outgoingCount = calls.count { it.type == CallLog.Calls.OUTGOING_TYPE }
            val missedCount = calls.count { it.type == CallLog.Calls.MISSED_TYPE }
            val rejectedCount = calls.count { it.type == CallLog.Calls.REJECTED_TYPE }
            val blockedCount = calls.count { it.type == CallLog.Calls.BLOCKED_TYPE }

            val callsWithDuration = calls.filter { it.duration > 0 }
            val totalDuration = callsWithDuration.sumOf { it.duration }
            val avgDuration = if (callsWithDuration.isNotEmpty()) totalDuration / callsWithDuration.size else 0L
            val longestCall = callsWithDuration.maxOfOrNull { it.duration } ?: 0L
            val uniqueNumbers = calls.map { it.number }.distinct().size

            val stats = CallStats(
                totalCalls = calls.size,
                incomingCalls = incomingCount,
                outgoingCalls = outgoingCount,
                missedCalls = missedCount,
                rejectedCalls = rejectedCount,
                blockedCalls = blockedCount,
                totalDuration = totalDuration,
                avgDuration = avgDuration,
                longestCall = longestCall,
                uniqueNumbers = uniqueNumbers
            )

            Log.d(TAG, "Stats computed: ${stats.totalCalls} calls")
            stats.toFormattedString()
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CALL_LOG permission required", e)
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute stats", e)
            "Error computing stats: ${e.message}"
        }
    }

    /**
     * Get total call duration breakdown.
     * Shows total, incoming, outgoing, and per-contact durations.
     */
    fun getDuration(context: Context): String {
        return try {
            val calls = queryCalls(context, limit = Int.MAX_VALUE)
            if (calls.isEmpty()) {
                return "No calls in call log"
            }

            val totalDuration = calls.sumOf { it.duration }
            val incomingDuration = calls.filter { it.type == CallLog.Calls.INCOMING_TYPE }.sumOf { it.duration }
            val outgoingDuration = calls.filter { it.type == CallLog.Calls.OUTGOING_TYPE }.sumOf { it.duration }

            // Top contacts by duration
            val contactDurations = calls
                .filter { it.duration > 0 }
                .groupBy { it.number }
                .mapValues { entry -> entry.value.sumOf { it.duration } }
                .entries
                .sortedByDescending { it.value }
                .take(10)

            return buildString {
                appendLine("=== Call Duration Summary ===")
                appendLine("Total:      ${formatDuration(totalDuration)}")
                appendLine("Incoming:   ${formatDuration(incomingDuration)}")
                appendLine("Outgoing:   ${formatDuration(outgoingDuration)}")
                appendLine("Missed:     0 (no duration)")
                appendLine()
                if (contactDurations.isNotEmpty()) {
                    appendLine("=== Top Contacts by Duration ===")
                    contactDurations.forEach { (number, duration) ->
                        val name = calls.find { it.number == number }?.name
                        val label = if (name.isNullOrBlank()) number else "$name"
                        appendLine("  $label: ${formatDuration(duration)}")
                    }
                }
            }
        } catch (e: SecurityException) {
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration", e)
            "Error getting duration: ${e.message}"
        }
    }

    /**
     * Find calls by phone number.
     *
     * @param number Phone number to search for (supports partial match)
     */
    fun getByNumber(context: Context, number: String): String {
        return try {
            if (number.isBlank()) {
                return "Error: Phone number required"
            }

            val calls = queryCalls(context, limit = Int.MAX_VALUE)
            val matching = calls.filter {
                it.number.contains(number, ignoreCase = true) ||
                it.number.replace("[^0-9]".toRegex(), "").contains(
                    number.replace("[^0-9]".toRegex(), "")
                )
            }

            if (matching.isEmpty()) {
                "No calls found for number: $number"
            } else {
                val totalDuration = matching.filter { it.duration > 0 }.sumOf { it.duration }
                buildString {
                    appendLine("=== Calls for '$number' (${matching.size}) ===")
                    matching.forEach { appendLine(it.toFormattedString()) }
                    appendLine()
                    appendLine("Total Duration: ${formatDuration(totalDuration)}")
                }
            }
        } catch (e: SecurityException) {
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find calls by number", e)
            "Error searching by number: ${e.message}"
        }
    }

    /**
     * Delete a specific call log entry by ID.
     *
     * @param id The call log entry ID to delete
     */
    fun deleteEntry(context: Context, id: Long): String {
        return try {
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString())
            )

            if (deleted > 0) {
                Log.i(TAG, "Call log entry $id deleted")
                "Call log entry $id deleted"
            } else {
                "Call log entry $id not found"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_CALL_LOG permission required", e)
            "Error: WRITE_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete call log entry", e)
            "Error deleting entry: ${e.message}"
        }
    }

    /**
     * Delete call log entries by phone number.
     *
     * @param number Phone number whose entries should be deleted
     */
    fun deleteByNumber(context: Context, number: String): String {
        return try {
            if (number.isBlank()) {
                return "Error: Phone number required"
            }

            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} = ?",
                arrayOf(number)
            )

            if (deleted > 0) {
                Log.i(TAG, "Deleted $deleted call log entries for $number")
                "Deleted $deleted call log entries for $number"
            } else {
                "No call log entries found for $number"
            }
        } catch (e: SecurityException) {
            "Error: WRITE_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete by number", e)
            "Error deleting by number: ${e.message}"
        }
    }

    /**
     * Clear the entire call log.
     * Use with caution — this action is irreversible.
     */
    fun clearAll(context: Context): String {
        return try {
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                null,
                null
            )

            Log.w(TAG, "Call log cleared: $deleted entries deleted")
            "Call log cleared: $deleted entries deleted"
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_CALL_LOG permission required", e)
            "Error: WRITE_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear call log", e)
            "Error clearing call log: ${e.message}"
        }
    }

    /**
     * Get detailed information about a specific call by ID.
     */
    fun getCallDetail(context: Context, id: Long): String {
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                "${CallLog.Calls._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    parseCallEntry(cursor).toDetailedString()
                } else {
                    "Call log entry $id not found"
                }
            } ?: "Error: Failed to query call log"
        } catch (e: SecurityException) {
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get call detail", e)
            "Error getting call detail: ${e.message}"
        }
    }

    // ---- Internal Helpers ----

    /**
     * List calls filtered by a specific call type.
     */
    private fun listByType(context: Context, type: Int, typeLabel: String, limit: Int): String {
        return try {
            val calls = queryCalls(context, type = type, limit = limit)
            if (calls.isEmpty()) {
                "No $typeLabel calls found"
            } else {
                buildString {
                    appendLine("=== $typeLabel Calls (${calls.size}) ===")
                    calls.forEach { appendLine(it.toFormattedString()) }
                }
            }
        } catch (e: SecurityException) {
            "Error: READ_CALL_LOG permission required"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list $typeLabel calls", e)
            "Error listing $typeLabel calls: ${e.message}"
        }
    }

    /**
     * Query the call log content provider with optional filters.
     *
     * @param type Call type filter (null = all types)
     * @param limit Maximum results to return
     */
    private fun queryCalls(context: Context, type: Int? = null, limit: Int = 20): List<CallEntry> {
        val calls = mutableListOf<CallEntry>()

        val selection = if (type != null) "${CallLog.Calls.TYPE} = ?" else null
        val selectionArgs = if (type != null) arrayOf(type.toString()) else null
        val sortOrder = "${CallLog.Calls.DATE} DESC"

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                calls.add(parseCallEntry(cursor))
                count++
            }
        }

        return calls
    }

    /**
     * Parse a cursor row into a CallEntry data class.
     */
    private fun parseCallEntry(cursor: android.database.Cursor): CallEntry {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID))
        val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
        val name = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE))
        val date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
        val duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION))
        val isNew = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.NEW)) == 1
        val isRead = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.IS_READ)) == 1

        return CallEntry(
            id = id,
            number = number,
            name = name,
            type = type,
            typeLabel = callTypeToString(type),
            date = date,
            dateFormatted = dateFormat.format(Date(date)),
            duration = duration,
            durationFormatted = formatDuration(duration),
            isNew = isNew,
            isRead = isRead
        )
    }

    /**
     * Convert a call type constant to a human-readable string.
     */
    private fun callTypeToString(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        else -> "Unknown ($type)"
    }

    /**
     * Format a duration in seconds to a human-readable string.
     * Examples: "0s", "45s", "2m 30s", "1h 5m 30s"
     */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"

        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hrs > 0 -> "${hrs}h ${mins}m ${secs}s"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }
}
