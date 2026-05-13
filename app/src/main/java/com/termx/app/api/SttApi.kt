package com.termx.app.api

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File

/**
 * Speech-to-Text API for TermX. Voice recognition using Android SpeechRecognizer.
 *
 * Usage: am broadcast -a com.termx.app.api.STT_START --es language "en_US" --ei timeout 10000
 *        am broadcast -a com.termx.app.api.STT_STOP
 *        am broadcast -a com.termx.app.api.STT_RESULT
 * Requires: RECORD_AUDIO permission
 * Results saved to cache/stt_result.txt
 */
object SttApi {

    private const val TAG = "SttApi"
    private const val RESULT_FILE = "stt_result.txt"

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastResult = ""
    private var lastError = ""

    /** Check if speech recognition is available. */
    fun isAvailable(context: Context): Boolean = try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false }

    /** Start listening for speech. */
    fun startListening(context: Context, language: String = "en_US", timeoutMs: Int = 10000): String { return try {
        if (!isAvailable(context)) return "Error: Speech recognition not available"
        stopListening(context)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(TermXListener(context))
            sr.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
        isListening = true; lastError = ""
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (isListening) stopListening(context) }, timeoutMs.toLong())
        Log.i(TAG, "STT started (lang=$language)"); "Listening started (language: $language, timeout: ${timeoutMs}ms)"
    } catch (e: SecurityException) { "Error: RECORD_AUDIO permission required" }
    catch (e: Exception) { Log.e(TAG, "STT start failed", e); "Error: ${e.message}" }
    }

    /** Stop listening. */
    fun stopListening(context: Context): String = try {
        speechRecognizer?.stopListening(); isListening = false
        "Listening stopped. Last: ${lastResult.ifEmpty { "none" }}"
    } catch (e: Exception) { isListening = false; "Error: ${e.message}" }

    /** Get last recognition result. */
    fun getResult(context: Context): String {
        if (lastResult.isNotEmpty()) {
            val output = "=== STT Result ===\nText: $lastResult\nTime: ${System.currentTimeMillis()}"
            File(context.cacheDir, RESULT_FILE).writeText(output); return output
        }
        return if (lastError.isNotEmpty()) "Last error: $lastError" else "No result. Start listening first."
    }

    /** Release SpeechRecognizer resources. */
    fun destroy() { try { speechRecognizer?.destroy(); speechRecognizer = null; isListening = false } catch (_: Exception) {} }

    private fun saveResult(context: Context, content: String) {
        try { File(context.cacheDir, RESULT_FILE).writeText(content) } catch (e: Exception) { Log.e(TAG, "Save failed", e) }
    }

    private class TermXListener(private val ctx: Context) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "Ready for speech") }
        override fun onBeginningOfSpeech() { Log.d(TAG, "Speech began") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onError(error: Int) {
            isListening = false
            lastError = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                else -> "Error code $error"
            }
            Log.e(TAG, "STT error: $lastError"); saveResult(ctx, "Error: $lastError")
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            lastResult = matches?.firstOrNull() ?: ""
            val confs = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) else null
            val conf = confs?.firstOrNull() ?: 0f
            isListening = false
            val output = "=== STT Result ===\nText: $lastResult\nConfidence: ${"%.0f".format(conf * 100)}%"
            saveResult(ctx, output); Log.i(TAG, "STT result: $lastResult")
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrEmpty()) { lastResult = partial; Log.d(TAG, "Partial: $partial") }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
