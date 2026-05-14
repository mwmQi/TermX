package com.termx.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.termx.app.api.TermXApi

/**
 * Enhanced RUN_COMMAND intent receiver.
 * Allows external apps and Tasker to execute commands in TermX.
 *
 * Intent extras:
 *   com.termx.app.RUN_COMMAND_PATH     - Command path (required)
 *   com.termx.app.RUN_COMMAND_ARGUMENTS - Arguments (String[])
 *   com.termx.app.RUN_COMMAND_WORKDIR   - Working directory
 *   com.termx.app.RUN_COMMAND_BACKGROUND - Run in background (boolean)
 *
 * Requires allow-external-apps=true in termx.properties
 */
class RunCommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RUN_COMMAND = "com.termx.app.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termx.app.RUN_COMMAND_PATH"
        const val EXTRA_COMMAND_ARGUMENTS = "com.termx.app.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_COMMAND_WORKDIR = "com.termx.app.RUN_COMMAND_WORKDIR"
        const val EXTRA_COMMAND_BACKGROUND = "com.termx.app.RUN_COMMAND_BACKGROUND"
        const val EXTRA_COMMAND_SESSION_ACTION = "com.termx.app.RUN_COMMAND_SESSION_ACTION"

        private const val TAG = "RunCommandReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_COMMAND) return

        // Security check - must be enabled in properties
        val props = com.termx.app.config.TermXProperties(context)
        props.load()
        if (!props.allowExternalApps) {
            Log.w(TAG, "External app commands disabled. Set allow-external-apps=true in termx.properties")
            return
        }

        val commandPath = intent.getStringExtra(EXTRA_COMMAND_PATH) ?: return
        val arguments = intent.getStringArrayExtra(EXTRA_COMMAND_ARGUMENTS) ?: emptyArray()
        val workdir = intent.getStringExtra(EXTRA_COMMAND_WORKDIR) ?: "/"
        val background = intent.getBooleanExtra(EXTRA_COMMAND_BACKGROUND, false)

        val fullCommand = buildString {
            append(commandPath)
            for (arg in arguments) {
                append(" '")
                append(arg.replace("'", "'\\''"))
                append("'")
            }
        }

        Log.i(TAG, "Running external command: $fullCommand (workdir=$workdir, bg=$background)")

        // Launch the app and execute command
        val launchIntent = Intent(context, com.termx.app.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("run_command", fullCommand)
            putExtra("run_command_workdir", workdir)
            putExtra("run_command_background", background)
        }
        context.startActivity(launchIntent)
    }
}
