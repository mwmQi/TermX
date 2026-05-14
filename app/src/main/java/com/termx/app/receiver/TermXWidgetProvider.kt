package com.termx.app.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.termx.app.MainActivity
import com.termx.app.R

/**
 * Home screen widget for TermX.
 * Provides quick-launch shortcuts:
 * - Tap widget → Open terminal
 * - New Session button → Create new session
 * - Wake Lock button → Toggle wake lock
 *
 * Similar to Termux widget.
 */
class TermXWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_OPEN_TERMINAL = "com.termx.app.widget.OPEN_TERMINAL"
        const val ACTION_NEW_SESSION = "com.termx.app.widget.NEW_SESSION"
        const val ACTION_TOGGLE_WAKELOCK = "com.termx.app.widget.TOGGLE_WAKELOCK"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_TERMINAL -> {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            }
            ACTION_NEW_SESSION -> {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("action", "new_session")
                }
                context.startActivity(launchIntent)
            }
            ACTION_TOGGLE_WAKELOCK -> {
                com.termx.app.power.WakeLockManager.toggle(context)
                // Refresh all widgets
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, TermXWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(context, appWidgetManager, id)
                }
            }
        }
        super.onReceive(context, intent)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_terminal)

        // Open terminal on widget tap
        val openIntent = Intent(context, TermXWidgetProvider::class.java).apply {
            action = ACTION_OPEN_TERMINAL
        }
        views.setOnClickPendingIntent(
            R.id.widget_layout,
            PendingIntent.getBroadcast(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // New session button
        val newSessionIntent = Intent(context, TermXWidgetProvider::class.java).apply {
            action = ACTION_NEW_SESSION
        }
        views.setOnClickPendingIntent(
            R.id.widget_new_session,
            PendingIntent.getBroadcast(
                context, 1, newSessionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Wake lock button
        val wakeLockIntent = Intent(context, TermXWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_WAKELOCK
        }
        views.setOnClickPendingIntent(
            R.id.widget_wake_lock,
            PendingIntent.getBroadcast(
                context, 2, wakeLockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Update wake lock indicator
        val wakeHeld = com.termx.app.power.WakeLockManager.isWakeLockHeld()
        views.setTextViewText(
            R.id.widget_wake_lock,
            if (wakeHeld) "🔓 Wake: ON" else "🔒 Wake: OFF"
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
