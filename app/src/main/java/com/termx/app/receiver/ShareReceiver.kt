package com.termx.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.termx.app.MainActivity

/**
 * Receiver for shared text from other apps.
 * When text is shared to TermX, it opens the terminal and pastes/inputs the text.
 *
 * Add intent-filter for ACTION_SEND in manifest.
 */
class ShareReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ShareReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrEmpty()) {
                    Log.i(TAG, "Received shared text: ${sharedText.take(100)}...")

                    // Launch MainActivity with the shared text
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, sharedText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(launchIntent)
                }
            }
        }
    }
}
