package com.termx.app.power.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.termx.app.MainActivity
import com.termx.app.power.WakeLockManager

/**
 * Foreground service for the TermX SSH/SFTP server.
 *
 * Keeps the SSH server running in the background with a persistent notification.
 * The notification displays:
 * - Server status (running/stopped) and port
 * - Active session count
 * - Quick actions: stop server, open terminal
 *
 * This service ensures the SSH server is not killed by the system
 * when the app is in the background, providing reliable remote access.
 *
 * Requires: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC
 */
class SshServerService : Service() {

    companion object {
        private const val TAG = "SshServerService"
        const val CHANNEL_ID = "termx_ssh_channel"
        const val NOTIFICATION_ID = 2001

        var isRunning = false
            private set

        // Intent actions from notification
        const val ACTION_START_SERVER = "com.termx.app.action.SSH_START"
        const val ACTION_STOP_SERVER = "com.termx.app.action.SSH_STOP"
        const val ACTION_OPEN_TERMINAL = "com.termx.app.action.SSH_OPEN"

        // Intent extras
        const val EXTRA_PORT = "port"
        const val EXTRA_BIND_ADDRESS = "bind_address"
    }

    private var sshServer: SshServer? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "SSH server service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val port = intent.getIntExtra(EXTRA_PORT, SshServer.DEFAULT_PORT)
                val bindAddress = intent.getStringExtra(EXTRA_BIND_ADDRESS) ?: "0.0.0.0"
                startSshServer(port, bindAddress)
            }
            ACTION_STOP_SERVER -> {
                stopSshServer()
                stopSelf()
            }
            ACTION_OPEN_TERMINAL -> {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("action", "ssh_sessions")
                }
                startActivity(launchIntent)
            }
            else -> {
                // Default: start server with default or saved settings
                val port = intent?.getIntExtra(EXTRA_PORT, SshServer.DEFAULT_PORT)
                    ?: SshServer.DEFAULT_PORT
                if (sshServer == null || !sshServer!!.isRunning) {
                    startSshServer(port, "0.0.0.0")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopSshServer()
        isRunning = false
        Log.i(TAG, "SSH server service destroyed")
    }

    /**
     * Start the SSH server on the specified port.
     */
    private fun startSshServer(port: Int, bindAddress: String) {
        try {
            sshServer = SshServer.getInstance(this)

            // Set up callbacks for notification updates
            sshServer?.onServerStateChanged = { state ->
                updateNotification()
            }
            sshServer?.onSessionCreated = { session ->
                updateNotification()
                Log.i(TAG, "SSH session created: ${session.username}@${session.clientAddress}")
            }
            sshServer?.onSessionClosed = { session ->
                updateNotification()
                Log.i(TAG, "SSH session closed: ${session.username}@${session.clientAddress}")
            }

            val success = sshServer?.start(port, bindAddress) ?: false
            if (success) {
                Log.i(TAG, "SSH server started on $bindAddress:$port")
                WakeLockManager.acquirePartial(this)
            } else {
                Log.w(TAG, "SSH server failed to start on $bindAddress:$port")
            }
            updateNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSH server", e)
        }
    }

    /**
     * Stop the SSH server.
     */
    private fun stopSshServer() {
        try {
            sshServer?.stop()
            sshServer = null
            WakeLockManager.release()
            Log.i(TAG, "SSH server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SSH server", e)
        }
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SSH Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the SSH/SFTP server running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Update the foreground notification with current server state.
     */
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Build the foreground notification.
     */
    private fun buildNotification(): Notification {
        val isServerRunning = sshServer?.isRunning == true
        val port = sshServer?.port ?: SshServer.DEFAULT_PORT
        val sessionCount = sshServer?.activeSessionCount ?: 0
        val uptime = sshServer?.getState()?.let {
            if (it.running) it.formatUptime() else "0:00:00"
        } ?: "0:00:00"

        val title = if (isServerRunning) "SSH Server Running" else "SSH Server"
        val content = if (isServerRunning) {
            "Port $port | $sessionCount session(s) | Up $uptime"
        } else {
            "Server stopped"
        }

        // Content intent — open app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open terminal action
        val openIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SshServerService::class.java).setAction(ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop server action
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SshServerService::class.java).setAction(ACTION_STOP_SERVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Sessions",
                openIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .build()
    }
}
