package com.termx.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Media player for TermX API.
 * Similar to termux-media-player.
 *
 * Usage:
 *   termx-media-player play <file|url>
 *   termx-media-player pause
 *   termx-media-player resume
 *   termx-media-player stop
 *   termx-media-player info
 */
object MediaManager {

    private const val TAG = "MediaManager"

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: String? = null
    private var isPrepared = false

    /**
     * Play a media file or URL.
     */
    fun play(context: Context, source: String): String {
        stop()

        return try {
            mediaPlayer = MediaPlayer().apply {
                if (source.startsWith("http://") || source.startsWith("https://")) {
                    setDataSource(source)
                } else {
                    val file = File(source)
                    if (!file.exists()) {
                        return "Error: File not found: $source"
                    }
                    setDataSource(context, Uri.fromFile(file))
                }

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnPreparedListener {
                    it.start()
                    isPrepared = true
                    Log.i(TAG, "Playback started: $source")
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }

                setOnCompletionListener {
                    currentTrack = null
                    isPrepared = false
                    Log.i(TAG, "Playback completed")
                }

                prepareAsync()
            }

            currentTrack = source
            "Playing: $source"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play: $source", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Pause playback.
     */
    fun pause(): String {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    "Paused"
                } else {
                    "Not playing"
                }
            } ?: "No track loaded"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Resume playback.
     */
    fun resume(): String {
        return try {
            mediaPlayer?.let {
                if (isPrepared && !it.isPlaying) {
                    it.start()
                    "Resumed"
                } else {
                    "Cannot resume"
                }
            } ?: "No track loaded"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Stop playback.
     */
    fun stop(): String {
        return try {
            mediaPlayer?.let {
                it.stop()
                it.release()
                isPrepared = false
                currentTrack = null
            }
            mediaPlayer = null
            "Stopped"
        } catch (e: Exception) {
            "Stopped"
        }
    }

    /**
     * Get current playback info.
     */
    fun info(): String {
        return if (mediaPlayer != null && isPrepared) {
            val mp = mediaPlayer!!
            val currentPos = try { mp.currentPosition / 1000 } catch (_: Exception) { 0 }
            val duration = try { mp.duration / 1000 } catch (_: Exception) { 0 }
            val state = if (mp.isPlaying) "Playing" else "Paused"

            "State: $state\n" +
            "Track: $currentTrack\n" +
            "Position: ${currentPos}s / ${duration}s"
        } else {
            "No track loaded"
        }
    }

    /**
     * Set volume (0.0 to 1.0).
     */
    fun setVolume(left: Float, right: Float): String {
        return try {
            mediaPlayer?.setVolume(left, right)
            "Volume set: L=${"%.2f".format(left)} R=${"%.2f".format(right)}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
