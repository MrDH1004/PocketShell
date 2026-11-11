package com.neststudio.pocketshell.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.AgentEvent
import com.neststudio.pocketshell.core.AgentState
import com.neststudio.pocketshell.core.Backoff
import com.neststudio.pocketshell.core.EventCursor
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.data.UiPrefs
import com.neststudio.pocketshell.net.ApiException
import com.neststudio.pocketshell.ui.PcListActivity
import com.neststudio.pocketshell.ui.SessionListActivity
import com.neststudio.pocketshell.ui.TerminalActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tells the user when an AI CLI on a paired PC stops: waiting on an approval prompt, or done (DESIGN.md §14).
 * A foreground service holds one long poll (`GET /api/events`) per PC, since there is no push server between the
 * phone and the PC. Notifications carry the prompt's choices (or a reply field) so they can be answered in place.
 */
class AgentWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Poll job per PC id, with the pairing it was started for (a changed pairing restarts it). */
    private val watches = HashMap<String, Pair<Pairing, Job>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pcs = (application as PkshellApp).pcs()
        if (pcs.isEmpty() || !UiPrefs(this).agentNotify) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val ongoing = ongoingNotification(pcs.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_ONGOING, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_ONGOING, ongoing)
        }
        sync(pcs)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Starts a poll for every paired PC and stops the ones for PCs that were removed or re-paired. */
    private fun sync(pcs: List<Pairing>) {
        val byId = pcs.associateBy { it.id }
        for (id in watches.keys.toList()) {
            val (pairing, job) = watches.getValue(id)
            // The alias is display only: a rename must not restart the poll (it would lose the cursor).
            if (byId[id]?.copy(alias = "") != pairing.copy(alias = "")) {
                job.cancel()
                watches.remove(id)
            }
        }
        for (p in pcs) {
            if (p.id !in watches) watches[p.id] = p to scope.launch { watch(p) }
        }
    }

    private suspend fun watch(p: Pairing) {
        val app = application as PkshellApp
        val api = app.apiFor(p)
        // The reply comes when an agent stops or after WAIT_SECONDS: the read timeout must outlast that.
        val client = api.http.newBuilder().readTimeout(WAIT_SECONDS + 20L, TimeUnit.SECONDS).build()
        var cursor = EventCursor.START
        val backoff = Backoff(RETRY_FIRST_MS, RETRY_MAX_MS)
        while (true) {
            try {
                val reply = api.events(cursor.seq, if (cursor == EventCursor.START) 0 else WAIT_SECONDS, client)
                backoff.reset()
                val step = cursor.accept(reply)
                cursor = step.cursor
                // The alias may have changed since this poll started.
                val current = app.store.get(p.id) ?: return
                step.announce.forEach { announce(current, it) }
            } catch (e: ApiException) {
                when (e.code) {
                    401 -> { Log.w(TAG, "${p.pcName}: token no longer valid, stopping"); return }
                    404 -> delay(OLD_HOST_RETRY_MS) // host older than 0.2.59: no /api/events yet
                    else -> delay(backoff.nextDelayMillis())
                }
            } catch (e: IOException) {
                delay(backoff.nextDelayMillis()) // PC off, other network: keep trying quietly
            } catch (e: JSONException) {
                Log.w(TAG, "${p.pcName}: unreadable events reply: ${e.message}")
                delay(backoff.nextDelayMillis())
            }
        }
    }

    private fun announce(p: Pairing, e: AgentEvent) {
        if (e.state != AgentState.WAITING && e.state != AgentState.DONE) return
        if (Foreground.isViewing(p.id, e.sessionId)) return // already looking at it
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val id = notificationId(p.id, e.sessionId)
        val waiting = e.state == AgentState.WAITING
        val name = e.title.ifBlank { e.sessionId }
        val text = when {
            waiting -> e.prompt?.question?.ifBlank { null } ?: getString(R.string.notify_waiting_text)
            else -> getString(R.string.notify_done_text)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(if (waiting) R.string.notify_waiting_title else R.string.notify_done_title, name))
            .setContentText(text)
            .setSubText(p.displayName)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Its own group: Android otherwise bundles it with the ongoing "waiting" notification and hides the buttons.
            .setGroup("agent-$id")
            .setContentIntent(openIntent(p, e, id))
        if (waiting && e.prompt != null) {
            val lines = e.prompt.options.withIndex().joinToString("\n") { (i, o) -> "${i + 1}. ${o.label}" }
            b.setStyle(NotificationCompat.BigTextStyle().bigText(if (text.isEmpty()) lines else "$text\n$lines"))
            // Android shows at most three actions: one per choice.
            e.prompt.options.take(MAX_ACTIONS).forEachIndexed { i, o ->
                val label = "${i + 1}. ${o.label}".let { if (it.length > ACTION_LABEL_MAX) it.take(ACTION_LABEL_MAX - 1) + "…" else it }
                b.addAction(0, label, replyIntent(p, e, id, o.key, enter = o.enter, requestSalt = "o$i"))
            }
        } else {
            // Finished (or a question we could not read): answer with the next instruction from the notification.
            val remote = RemoteInput.Builder(AgentReplyReceiver.KEY_TEXT).setLabel(getString(R.string.notify_reply_hint)).build()
            val action = NotificationCompat.Action.Builder(0, getString(R.string.notify_reply), replyIntent(p, e, id, null, enter = true, requestSalt = "r", mutable = true))
                .addRemoteInput(remote)
                .build()
            b.addAction(action)
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    /** Opens the session; PC list and session list sit below it so Back walks up the usual way. */
    private fun openIntent(p: Pairing, e: AgentEvent, id: Int): PendingIntent {
        val kind = if (e.sessionId.startsWith("ext-")) SessionInfo.KIND_EXTERNAL else SessionInfo.KIND_HOST
        val pid = e.sessionId.removePrefix("ext-").toIntOrNull()?.takeIf { kind == SessionInfo.KIND_EXTERNAL }
        val session = SessionInfo(e.sessionId, kind, e.title, "", "", SessionInfo.STATE_RUNNING, "", "", 0, 0, pid, null, e.agent)
        val stack = arrayOf(
            Intent(this, PcListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            Intent(this, SessionListActivity::class.java),
            TerminalActivity.intent(this, session).putExtra(TerminalActivity.EXTRA_PC_ID, p.id),
        )
        return PendingIntent.getActivities(this, id, stack, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun replyIntent(p: Pairing, e: AgentEvent, id: Int, text: String?, enter: Boolean, requestSalt: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(this, AgentReplyReceiver::class.java)
            .setAction(AgentReplyReceiver.ACTION_REPLY)
            .putExtra(AgentReplyReceiver.EXTRA_PC_ID, p.id)
            .putExtra(AgentReplyReceiver.EXTRA_SESSION_ID, e.sessionId)
            .putExtra(AgentReplyReceiver.EXTRA_NOTIFICATION_ID, id)
            .putExtra(AgentReplyReceiver.EXTRA_ENTER, enter)
        if (text != null) intent.putExtra(AgentReplyReceiver.EXTRA_TEXT, text)
        // RemoteInput needs a mutable PendingIntent (the system fills in the typed text).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(this, (id.toString() + requestSalt).hashCode(), intent, flags)
    }

    private fun ongoingNotification(pcCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, PcListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notify_ongoing_title))
            .setContentText(getString(R.string.notify_ongoing_text, pcCount))
            .setOngoing(true)
            .setGroup("watch")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "AgentWatch"
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_WATCH = "watch"
        private const val ID_ONGOING = 1
        private const val WAIT_SECONDS = 40
        private const val OLD_HOST_RETRY_MS = 10 * 60 * 1000L
        private const val RETRY_FIRST_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val MAX_ACTIONS = 3
        private const val ACTION_LABEL_MAX = 24

        fun notificationId(pcId: String, sessionId: String): Int = ("$pcId/$sessionId").hashCode() or 0x10000

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AGENT, context.getString(R.string.notify_channel_agent), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.notify_channel_agent_desc) },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WATCH, context.getString(R.string.notify_channel_watch), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = context.getString(R.string.notify_channel_watch_desc); setShowBadge(false) },
            )
        }

        /**
         * Starts, re-syncs (PC added / removed) or stops the service to match the setting and the paired PCs. Call
         * from a visible activity: Android does not let a background app start a foreground service.
         */
        fun apply(context: Context) {
            val app = context.applicationContext as PkshellApp
            val intent = Intent(context, AgentWatchService::class.java)
            if (UiPrefs(context).agentNotify && app.pcs().isNotEmpty()) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "cannot start from the background: ${e.message}")
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}

/** Which session is on screen right now, so its own stop does not raise a notification. */
object Foreground {
    @Volatile private var viewing: Pair<String, String>? = null

    fun enter(pcId: String, sessionId: String) { viewing = pcId to sessionId }

    fun leave(pcId: String, sessionId: String) { if (viewing == pcId to sessionId) viewing = null }

    fun isViewing(pcId: String, sessionId: String): Boolean = viewing == pcId to sessionId
}
