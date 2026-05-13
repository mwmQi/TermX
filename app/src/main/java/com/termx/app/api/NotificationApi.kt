package com.termx.app.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Notification API for TermX (with actions, progress, reply).
 *
 * Usage: am broadcast -a com.termx.app.api.NOTIFY_SHOW --es title "Hi" --es content "Hello"
 *        am broadcast -a com.termx.app.api.NOTIFY_CANCEL --ei id 2000
 *        am broadcast -a com.termx.app.api.NOTIFY_ONGOING --es title "Running" --es content "Active"
 *        am broadcast -a com.termx.app.api.NOTIFY_PROGRESS --es title "DL" --ei progress 50 --ei max 100
 *        am broadcast -a com.termx.app.api.NOTIFY_REPLY --es title "Chat" --es content "Msg"
 * Requires: POST_NOTIFICATIONS permission (API 33+)
 */
object NotificationApi {

    private const val TAG = "NotificationApi"
    private const val CHANNEL_ID = "termx_api_channel"
    private val activeIds = mutableSetOf<Int>()

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TermX Notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications from TermX terminal API"; enableLights(true); enableVibration(true)
            })
        }
    }

    private fun builder(context: Context, title: String, content: String) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(context)).apply {
            setContentTitle(title); setContentText(content)
            setSmallIcon(android.R.drawable.ic_dialog_info)
            setStyle(Notification.BigTextStyle().bigText(content))
        }

    /** Show a notification with optional action buttons. */
    fun showNotification(context: Context, title: String, content: String, id: Int = 2000): String = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(id, builder(context, title, content).setAutoCancel(true).build())
        activeIds.add(id); Log.i(TAG, "Notify shown id=$id"); "Notification shown (id=$id)"
    } catch (e: Exception) { Log.e(TAG, "Notify failed", e); "Error: ${e.message}" }

    /** Cancel a notification by ID. */
    fun cancelNotification(context: Context, id: Int): String = try {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        activeIds.remove(id); "Notification $id cancelled"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Show an ongoing (non-dismissible) notification. */
    fun showOngoingNotification(context: Context, title: String, content: String, id: Int = 3000): String = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(id, builder(context, title, content).setOngoing(true).setAutoCancel(false).build())
        activeIds.add(id); "Ongoing notification shown (id=$id)"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Show a notification with progress bar. */
    fun showProgressNotification(context: Context, title: String, content: String,
                                 progress: Int, max: Int = 100, id: Int = 4000): String = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(id, builder(context, title, content).setProgress(max, progress, false).setOngoing(true).build())
        activeIds.add(id)
        val pct = if (max > 0) progress * 100 / max else 0
        "Progress notification shown (id=$id, $pct%)"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** Show notification with reply action (direct reply). */
    fun showReplyNotification(context: Context, title: String, content: String, id: Int = 5000): String = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val replyIntent = PendingIntent.getBroadcast(context, id,
            Intent("com.termx.app.api.NOTIFY_REPLY").putExtra("notification_id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = builder(context, title, content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.addAction(Notification.Action.Builder(0, "Reply", replyIntent)
                .addRemoteInput(android.app.RemoteInput.Builder("reply_text").setLabel("Reply").build()).build())
        }
        nm.notify(id, b.build()); activeIds.add(id); "Reply notification shown (id=$id)"
    } catch (e: Exception) { "Error: ${e.message}" }

    /** List active notification IDs created by TermX. */
    fun listActiveNotifications(context: Context): String = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val systemActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            nm.activeNotifications?.map { it.id }?.toSet() ?: emptySet() else emptySet()
        buildString {
            appendLine("=== Active Notifications ===")
            appendLine("TermX IDs: ${activeIds.joinToString(", ").ifEmpty { "none" }}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                appendLine("System count: ${systemActive.size}")
        }
    } catch (e: Exception) { "Error: ${e.message}" }
}
