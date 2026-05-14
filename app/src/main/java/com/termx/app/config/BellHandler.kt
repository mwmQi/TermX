package com.termx.app.config

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Handles terminal bell events.
 * Supports vibration, visual bell, or ignore modes.
 * Compatible with Termux bell-character property.
 */
object BellHandler {

    private var lastBellTime = 0L
    private const val BELL_THROTTLE_MS = 100L // Min time between bells

    /**
     * Handle a bell (BEL, 0x07) event from the terminal.
     */
    fun onBell(context: Context, mode: TermXProperties.BellMode, terminalView: View? = null) {
        val now = System.currentTimeMillis()
        if (now - lastBellTime < BELL_THROTTLE_MS) return
        lastBellTime = now

        when (mode) {
            TermXProperties.BellMode.VIBRATE -> vibrate(context)
            TermXProperties.BellMode.VISIBLE -> visualBell(terminalView)
            TermXProperties.BellMode.IGNORE -> { /* Do nothing */ }
        }
    }

    /**
     * Vibrate the device briefly.
     */
    private fun vibrate(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            // Vibration not available
        }
    }

    /**
     * Flash the terminal background briefly for visual bell.
     */
    private fun visualBell(terminalView: View?) {
        terminalView?.let { view ->
            // Flash white briefly
            val originalBg = view.background
            view.setBackgroundColor(0x33FFFFFF) // Semi-transparent white
            view.postDelayed({
                view.background = originalBg
            }, 150)
        }
    }
}
