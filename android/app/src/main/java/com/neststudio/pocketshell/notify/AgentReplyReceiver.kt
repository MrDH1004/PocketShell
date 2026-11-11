package com.neststudio.pocketshell.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A notification action: types the chosen key ("1") or the typed reply into the session on its PC
 * (`POST /api/sessions/{id}/input`) and clears the notification; or turns that session's notifications off.
 */
class AgentReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY && intent.action != ACTION_MUTE) return
        val pcId = intent.getStringExtra(EXTRA_PC_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (intent.action == ACTION_MUTE) {
            com.neststudio.pocketshell.data.UiPrefs(context).setMuted(pcId, sessionId, true)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
            Toast.makeText(context, R.string.notify_muted_toast, Toast.LENGTH_LONG).show()
            return
        }
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        val text = (typed ?: intent.getStringExtra(EXTRA_TEXT)).orEmpty()
        val enter = intent.getBooleanExtra(EXTRA_ENTER, false)
        // A choice may be Enter alone (the highlighted one); a typed reply must say something.
        val nothingToSend = if (typed != null) text.isBlank() else text.isEmpty() && !enter
        val app = context.applicationContext as PkshellApp
        val pairing = app.store.get(pcId)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (pairing == null || nothingToSend) {
            nm.cancel(notificationId)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                withContext(Dispatchers.IO) { app.apiFor(pairing).sendInput(sessionId, text, enter) }
                nm.cancel(notificationId)
            } catch (e: IOException) {
                Log.w(TAG, "reply to $sessionId failed: ${e.message}")
                // Keep the notification so the answer can be tried again (or the session opened).
                Toast.makeText(context, context.getString(R.string.notify_reply_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AgentReply"
        const val ACTION_REPLY = "com.neststudio.pocketshell.AGENT_REPLY"
        const val ACTION_MUTE = "com.neststudio.pocketshell.AGENT_MUTE"
        const val EXTRA_PC_ID = "pcId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENTER = "enter"
        const val KEY_TEXT = "reply"
    }
}
