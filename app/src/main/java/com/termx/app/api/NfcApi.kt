package com.termx.app.api

import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log
import java.io.IOException
import java.nio.charset.Charset

/**
 * NFC API for TermX. Read/write NFC tags, check availability.
 *
 * Usage: am broadcast -a com.termx.app.api.NFC_CHECK
 *        am broadcast -a com.termx.app.api.NFC_READ  (requires tag discovery intent)
 *        am broadcast -a com.termx.app.api.NFC_WRITE --es message "Hello NFC"
 * Note: Tag read/write requires NFC discovery intent from the system.
 */
object NfcApi {

    private const val TAG = "NfcApi"

    /** Check NFC availability and status. */
    fun checkAvailability(context: Context): String = try {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        buildString {
            appendLine("=== NFC Status ===")
            when {
                adapter == null -> { appendLine("Available: false"); appendLine("Reason: No NFC hardware") }
                !adapter.isEnabled -> { appendLine("Available: true"); appendLine("Enabled: false") }
                else -> { appendLine("Available: true"); appendLine("Enabled: true") }
            }
        }
    } catch (e: Exception) { Log.e(TAG, "NFC check failed", e); "Error: ${e.message}" }

    fun isNfcAvailable(context: Context): Boolean = try { NfcAdapter.getDefaultAdapter(context) != null } catch (_: Exception) { false }
    fun isNfcEnabled(context: Context): Boolean = try { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true } catch (_: Exception) { false }

    /** Read NFC tag from a discovered Tag object. */
    fun readTag(tag: Tag): String = try {
        val uid = tag.id.joinToString(":") { "%02X".format(it) }
        val ndef = Ndef.get(tag)
        var ndefMsg: String? = null; var maxSize = 0; var writable = false
        if (ndef != null) {
            maxSize = ndef.maxSize; writable = ndef.isWritable
            try { ndef.connect(); ndef.ndefMessage?.let { ndefMsg = parseNdefMessage(it) } }
            catch (e: IOException) { Log.w(TAG, "NDEF connect failed", e) }
            finally { try { ndef.close() } catch (_: IOException) {} }
        }
        Log.i(TAG, "NFC read: uid=$uid")
        "=== NFC Tag ===\nUID: $uid\nNDEF: ${ndef != null}\nWritable: $writable\nMax: ${maxSize}B\n" +
            (ndefMsg?.let { "Message: $it" } ?: "No NDEF data")
    } catch (e: Exception) { Log.e(TAG, "NFC read failed", e); "Error: ${e.message}" }

    /** Write NDEF text message to tag. */
    fun writeTag(tag: Tag, message: String): String = try {
        if (message.isBlank()) return "Error: Message empty"
        val ndef = Ndef.get(tag) ?: return "Error: Tag doesn't support NDEF. Tech: ${tag.techList.joinToString()}"
        try {
            ndef.connect()
            if (!ndef.isWritable) return "Error: Tag is read-only"
            val msg = createTextNdefMessage(message)
            if (ndef.maxSize < msg.toByteArray().size) return "Error: Message too large"
            ndef.writeNdefMessage(msg)
            Log.i(TAG, "NFC written: ${message.length} chars"); "Wrote ${message.length} chars to tag"
        } catch (e: FormatException) { "Error: Format error - ${e.message}" }
        catch (e: IOException) { "Error: I/O error - ${e.message}" }
        finally { try { ndef.close() } catch (_: IOException) {} }
    } catch (e: Exception) { Log.e(TAG, "NFC write failed", e); "Error: ${e.message}" }

    private fun parseNdefMessage(msg: NdefMessage): String = msg.records.joinToString(" | ") { parseNdefRecord(it) }

    private fun parseNdefRecord(record: NdefRecord): String = try {
        when {
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type contentEquals NdefRecord.RTD_TEXT -> {
                val p = record.payload; val enc = if ((p[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"
                String(p, (p[0].toInt() and 0x3F) + 1, p.size - (p[0].toInt() and 0x3F) - 1, Charset.forName(enc))
            }
            record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type contentEquals NdefRecord.RTD_URI -> {
                val p = record.payload; uriPrefix(p[0].toInt()) + String(p, 1, p.size - 1, Charset.forName("UTF-8"))
            }
            record.toUri() != null -> record.toUri().toString()
            else -> if (Build.VERSION.SDK_INT >= 21) try { String(record.payload, Charset.forName("UTF-8")) }
                catch (_: Exception) { "Binary(${record.payload.size}B)" } else "NDEF(tnf=${record.tnf})"
        }
    } catch (_: Exception) { "Unparseable" }

    private fun createTextNdefMessage(text: String): NdefMessage {
        val langB = "en".toByteArray(Charset.forName("US-ASCII"))
        val textB = text.toByteArray(Charset.forName("UTF-8"))
        val payload = ByteArray(1 + langB.size + textB.size)
        payload[0] = (langB.size and 0x3F).toByte()
        System.arraycopy(langB, 0, payload, 1, langB.size)
        System.arraycopy(textB, 0, payload, 1 + langB.size, textB.size)
        return NdefMessage(NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload))
    }

    private fun uriPrefix(b: Int): String = when (b) {
        1 -> "http://www."; 2 -> "https://www."; 3 -> "http://"; 4 -> "https://"
        5 -> "tel:"; 6 -> "mailto:"; else -> ""
    }
}
