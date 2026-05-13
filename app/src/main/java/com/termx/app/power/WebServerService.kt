package com.termx.app.power

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
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service for the TermX Web Server.
 *
 * Keeps the HTTP/HTTPS server running in the background with a persistent
 * notification. The notification displays:
 * - Server status (running/stopped), port, and served directory
 * - Total requests served and data transferred
 * - Quick actions: stop server, view logs, open in browser
 *
 * This service ensures the web server is not killed by the system
 * when the app is in the background, providing reliable file serving
 * and web hosting capabilities.
 *
 * Requires: FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC
 */
class WebServerService : Service() {

    companion object {
        private const val TAG = "WebServerService"
        const val CHANNEL_ID = "termx_webserver_channel"
        const val NOTIFICATION_ID = 2002

        var isRunning = false
            private set

        // Intent actions from notification
        const val ACTION_START_SERVER = "com.termx.app.action.WEB_START"
        const val ACTION_STOP_SERVER = "com.termx.app.action.WEB_STOP"
        const val ACTION_VIEW_LOGS = "com.termx.app.action.WEB_LOGS"
        const val ACTION_OPEN_BROWSER = "com.termx.app.action.WEB_BROWSER"

        // Intent extras
        const val EXTRA_PORT = "port"
        const val EXTRA_DIRECTORY = "directory"
    }

    /** Track requests for notification updates. */
    private val lastRequestCount = AtomicLong(0)

    /** Thread to periodically update notification with server stats. */
    private var updateThread: Thread? = null

    @Volatile
    private var shouldUpdate = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Web server service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val directory = intent.getStringExtra(EXTRA_DIRECTORY) ?: "/sdcard"
                startWebServer(port, directory)
            }
            ACTION_STOP_SERVER -> {
                stopWebServer()
                stopSelf()
            }
            ACTION_VIEW_LOGS -> {
                // Open app to web server logs view
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("action", "webserver_logs")
                }
                startActivity(launchIntent)
            }
            ACTION_OPEN_BROWSER -> {
                // Open the web server URL in a browser
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("http://127.0.0.1:$port")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
            }
            else -> {
                // Default: start server with default settings
                val port = intent?.getIntExtra(EXTRA_PORT, 8080) ?: 8080
                val directory = intent?.getStringExtra(EXTRA_DIRECTORY) ?: "/sdcard"
                startWebServer(port, directory)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
        isRunning = false
        Log.i(TAG, "Web server service destroyed")
    }

    /**
     * Start the web server.
     */
    private fun startWebServer(port: Int, directory: String) {
        try {
            val result = WebServerApi.start(this, port, directory)
            Log.i(TAG, "Web server start result: $result")

            // Start periodic notification updates
            startNotificationUpdates()

            WakeLockManager.acquirePartial(this)
            updateNotification()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
        }
    }

    /**
     * Stop the web server.
     */
    private fun stopWebServer() {
        try {
            stopNotificationUpdates()
            WebServerApi.stop()
            WakeLockManager.release()
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }
    }

    /**
     * Start a background thread to periodically update the notification
     * with current server statistics.
     */
    private fun startNotificationUpdates() {
        shouldUpdate = true
        updateThread = Thread({
            while (shouldUpdate && !Thread.interrupted()) {
                try {
                    Thread.sleep(5000) // Update every 5 seconds
                    updateNotification()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "WebServer-NotificationUpdater").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Stop the notification update thread.
     */
    private fun stopNotificationUpdates() {
        shouldUpdate = false
        updateThread?.interrupt()
        updateThread = null
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the HTTP server running in background"
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
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    /**
     * Build the foreground notification.
     */
    private fun buildNotification(): Notification {
        val status = WebServerApi.status()
        val isServerRunning = status.contains("Running:   true")
        val portLine = status.substringAfter("Port:").substringBefore("\n").trim()
        val reqLine = status.substringAfter("Requests:").substringBefore("\n").trim()
        val dirLine = status.substringAfter("Directory:").substringBefore("\n").trim()

        val title = if (isServerRunning) "Web Server Running" else "Web Server"
        val content = if (isServerRunning) {
            "Port $portLine | $reqLine req | $dirLine"
        } else {
            "Server stopped"
        }

        // Content intent — open app
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // View logs action
        val logsIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WebServerService::class.java).setAction(ACTION_VIEW_LOGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Open in browser action
        val browserIntent = PendingIntent.getService(
            this, 2,
            Intent(this, WebServerService::class.java).setAction(ACTION_OPEN_BROWSER)
                .putExtra(EXTRA_PORT, portLine.toIntOrNull() ?: 8080),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop server action
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, WebServerService::class.java).setAction(ACTION_STOP_SERVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_recent_history,
                "Logs",
                logsIntent
            )
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open",
                browserIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent
            )
            .build()
    }
}
