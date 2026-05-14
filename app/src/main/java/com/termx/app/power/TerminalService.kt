package com.termx.app.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.termx.app.MainActivity
import com.termx.app.session.SessionManager

/**
 * Enhanced terminal service with notification controls.
 * Provides a persistent notification with:
 * - Session count display
 * - Wake lock toggle
 * - New session button
 * - Quick exit
 */
class TerminalService : Service() {

    companion object {
        const val CHANNEL_ID = "termx_terminal_channel"
        const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set

        // Intent actions from notification
        const val ACTION_NEW_SESSION = "com.termx.app.action.NEW_SESSION"
        const val ACTION_TOGGLE_WAKELOCK = "com.termx.app.action.TOGGLE_WAKELOCK"
        const val ACTION_CLOSE_ALL = "com.termx.app.action.CLOSE_ALL"
    }

    private val sessionManager by lazy { SessionManager.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_NEW_SESSION -> {
                // Open app with new session
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("action", "new_session")
                }
                startActivity(launchIntent)
            }
            ACTION_TOGGLE_WAKELOCK -> {
                WakeLockManager.toggle(this)
                updateNotification()
            }
            ACTION_CLOSE_ALL -> {
                sessionManager.closeAllSessions()
                WakeLockManager.release()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        WakeLockManager.release()
        isRunning = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terminal Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps terminal sessions running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val sessionCount = sessionManager.activeSessionCount
        val wakeLockStatus = if (WakeLockManager.isWakeLockHeld()) " | Wake: ON" else ""

        // Pending intent for opening the app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // New session action
        val newSessionIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_NEW_SESSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toggle wake lock action
        val wakeLockIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TerminalService::class.java).setAction(ACTION_TOGGLE_WAKELOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Close all action
        val closeAllIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TerminalService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TermX")
            .setContentText("$sessionCount session(s)$wakeLockStatus")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_add,
                "New Session",
                newSessionIntent
            )
            .addAction(
                if (WakeLockManager.isWakeLockHeld()) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_idle_lock,
                if (WakeLockManager.isWakeLockHeld()) "Wake: ON" else "Wake: OFF",
                wakeLockIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Close All",
                closeAllIntent
            )
            .build()
    }
}
