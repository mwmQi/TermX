package com.termx.app.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Manages wake locks to keep the CPU running during long-running commands.
 * Similar to Termux's wake lock feature (`termux-wake-lock`).
 *
 * Two types:
 * - FULL_WAKE_LOCK: Keeps screen and CPU on (battery intensive)
 * - PARTIAL_WAKE_LOCK: Keeps CPU on but allows screen to turn off (recommended)
 */
object WakeLockManager {

    private const val TAG = "WakeLockManager"
    private const val WAKELOCK_TAG = "TermX::WakeLock"

    private var wakeLock: PowerManager.WakeLock? = null
    private var isHeld = false

    /**
     * Acquire a partial wake lock (CPU stays on, screen can turn off).
     */
    fun acquirePartial(context: Context) {
        acquire(context, PowerManager.PARTIAL_WAKE_LOCK)
    }

    /**
     * Acquire a full wake lock (screen and CPU stay on).
     */
    fun acquireFull(context: Context) {
        acquire(context, PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK)
    }

    /**
     * Acquire a wake lock of the specified type.
     */
    private fun acquire(context: Context, flags: Int) {
        if (isHeld) {
            Log.w(TAG, "Wake lock already held")
            return
        }

        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(flags, WAKELOCK_TAG).apply {
                acquire()
            }
            isHeld = true
            Log.i(TAG, "Wake lock acquired (flags=$flags)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Release the wake lock.
     */
    fun release() {
        if (!isHeld) return

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            isHeld = false
            Log.i(TAG, "Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    /**
     * Check if wake lock is currently held.
     */
    fun isWakeLockHeld(): Boolean = isHeld

    /**
     * Toggle wake lock on/off.
     */
    fun toggle(context: Context): Boolean {
        if (isHeld) {
            release()
        } else {
            acquirePartial(context)
        }
        return isHeld
    }

    /**
     * Get wake lock status text.
     */
    fun getStatus(): String {
        return if (isHeld) {
            "Wake lock: ON (CPU stays active)"
        } else {
            "Wake lock: OFF (CPU may sleep)"
        }
    }
}
