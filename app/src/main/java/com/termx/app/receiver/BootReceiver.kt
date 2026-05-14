package com.termx.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot receiver that auto-starts TermX when the device boots.
 * Similar to Termux:Boot - runs scripts from ~/.termux/boot/ on startup.
 *
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DIR = ".termx/boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Device boot completed, checking for boot scripts...")

        // Check if boot scripts exist
        val bootDir = java.io.File(context.filesDir, BOOT_DIR)
        if (!bootDir.exists()) {
            bootDir.mkdirs()
            Log.d(TAG, "Created boot directory: ${bootDir.absolutePath}")
            Log.d(TAG, "Place scripts in ${bootDir.absolutePath} to run on boot")
            return
        }

        val scripts = bootDir.listFiles { file ->
            file.isFile && file.canExecute()
        } ?: return

        if (scripts.isEmpty()) {
            Log.d(TAG, "No boot scripts found")
            return
        }

        // Execute each boot script
        for (script in scripts.sortedBy { it.name }) {
            try {
                Log.i(TAG, "Executing boot script: ${script.name}")
                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", script.absolutePath),
                    arrayOf("HOME=${context.filesDir.absolutePath}"),
                    context.filesDir
                )
                val exitCode = process.waitFor()
                Log.i(TAG, "Boot script ${script.name} exited with code $exitCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute boot script: ${script.name}", e)
            }
        }
    }
}
