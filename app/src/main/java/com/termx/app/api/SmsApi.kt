package com.termx.app.api

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

/**
 * SMS API for TermX. Send, read, delete SMS messages.
 *
 * Usage: am broadcast -a com.termx.app.api.SMS_SEND --es destination "+1234" --es message "Hi"
 *        am broadcast -a com.termx.app.api.SMS_INBOX --ei limit 10
 *        am broadcast -a com.termx.app.api.SMS_DELETE --ei id 42
 * Requires: SEND_SMS, READ_SMS permissions
 */
object SmsApi {

    private const val TAG = "SmsApi"
    private val SMS_URI = Uri.parse("content://sms")
    private val INBOX_URI = Uri.parse("content://sms/inbox")
    private val SENT_URI = Uri.parse("content://sms/sent")

    data class SmsMessage(val id: Long, val address: String, val body: String,
                          val date: Long, val type: Int, val read: Int) {
        fun toFormattedString() = "ID: $id | From: $address | Type: $typeStr | " +
            "${if (read == 1) "read" else "unread"} | Body: $body"
        val typeStr: String get() = when (type) { 1->"inbox"; 2->"sent"; 3->"draft"; 4->"outbox"; else->"type$type" }
    }

    /** Send an SMS message. */
    @SuppressLint("MissingPermission")
    fun sendSms(context: Context, destination: String, message: String): String { return try {
        if (destination.isBlank()) return "Error: Destination number required"
        if (message.isBlank()) return "Error: Message body required"
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = smsManager.divideMessage(message)
        if (parts.size > 1) smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
        else smsManager.sendTextMessage(destination, null, message, null, null)
        Log.i(TAG, "SMS sent to $destination"); "SMS sent to $destination (${message.length} chars)"
    } catch (e: SecurityException) { "Error: SEND_SMS permission required" }
    catch (e: Exception) { Log.e(TAG, "SMS send failed", e); "Error: ${e.message}" } }

    /** Read SMS inbox messages. */
    @SuppressLint("MissingPermission")
    fun readInbox(context: Context, limit: Int = 10, filter: String? = null): String = try {
        val msgs = querySms(context, INBOX_URI, limit, filter)
        if (msgs.isEmpty()) "No messages in inbox" else "=== SMS Inbox (${msgs.size}) ===\n" + msgs.joinToString("\n") { it.toFormattedString() }
    } catch (e: SecurityException) { "Error: READ_SMS permission required" }
    catch (e: Exception) { Log.e(TAG, "Inbox read failed", e); "Error: ${e.message}" }

    /** Read sent SMS messages. */
    @SuppressLint("MissingPermission")
    fun readSent(context: Context, limit: Int = 10): String = try {
        val msgs = querySms(context, SENT_URI, limit, null)
        if (msgs.isEmpty()) "No sent messages" else "=== SMS Sent (${msgs.size}) ===\n" + msgs.joinToString("\n") { it.toFormattedString() }
    } catch (e: SecurityException) { "Error: READ_SMS permission required" }
    catch (e: Exception) { "Error: ${e.message}" }

    /** Get SMS by ID. */
    @SuppressLint("MissingPermission")
    fun getSmsById(context: Context, id: Long): String = try {
        context.contentResolver.query(SMS_URI, null, "_id = ?", arrayOf(id.toString()), null)?.use { cur ->
            if (cur.moveToFirst()) parseSmsRow(cur).toFormattedString() else "SMS $id not found"
        } ?: "SMS $id not found"
    } catch (e: SecurityException) { "Error: READ_SMS permission required" }
    catch (e: Exception) { "Error: ${e.message}" }

    /** Delete SMS by ID. */
    @SuppressLint("MissingPermission")
    fun deleteSms(context: Context, id: Long): String = try {
        val deleted = context.contentResolver.delete(SMS_URI, "_id = ?", arrayOf(id.toString()))
        if (deleted > 0) { Log.i(TAG, "SMS $id deleted"); "SMS $id deleted" } else "SMS $id not found"
    } catch (e: SecurityException) { "Error: WRITE_SMS permission required" }
    catch (e: Exception) { "Error: ${e.message}" }

    private fun querySms(context: Context, uri: Uri, limit: Int, filter: String?): List<SmsMessage> {
        val msgs = mutableListOf<SmsMessage>()
        val sel = filter?.let { "address LIKE ? OR body LIKE ?" }
        val args = filter?.let { arrayOf("%$it%", "%$it%") }
        context.contentResolver.query(uri, null, sel, args, "date DESC")?.use { cur ->
            var count = 0
            while (cur.moveToNext() && count < limit) { msgs.add(parseSmsRow(cur)); count++ }
        }
        return msgs
    }

    private fun parseSmsRow(c: android.database.Cursor): SmsMessage {
        fun col(name: String) = c.getColumnIndex(name).let { if (it >= 0) it else -1 }
        return SmsMessage(
            id = col("_id").let { if (it >= 0) c.getLong(it) else -1 },
            address = col("address").let { if (it >= 0) c.getString(it) ?: "" else "" },
            body = col("body").let { if (it >= 0) c.getString(it) ?: "" else "" },
            date = col("date").let { if (it >= 0) c.getLong(it) else 0L },
            type = col("type").let { if (it >= 0) c.getInt(it) else 0 },
            read = col("read").let { if (it >= 0) c.getInt(it) else 0 }
        )
    }
}
