package com.neststudio.pocketshell.notify

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.AgentSummary
import com.neststudio.pocketshell.core.BoardTotals
import com.neststudio.pocketshell.core.PcStatus
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * How many AI sessions wait / work / are done on the paired PCs, for the home-screen widget and the quick settings
 * tile. Filled by whoever already asks the PCs (the notification service, the PC list) and by the widget's own
 * refresh; each change redraws both.
 */
object Board {
    private const val TAG = "Board"
    private const val PREFS = "status_board"
    private const val PROBE_TIMEOUT_MS = 6000L

    fun statuses(context: Context): Map<String, PcStatus> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (k, v) -> PcStatus.fromJson(v as? String)?.let { k to it } }.toMap()
    }

    fun totals(context: Context): BoardTotals {
        val app = context.applicationContext as PkshellApp
        return BoardTotals.of(app.pcs().map { it.id to it.displayName }, statuses(context))
    }

    /** Records [pcId]'s counts (null = unreachable) and redraws the widget and tile when they changed. */
    fun put(context: Context, pcId: String, summary: AgentSummary?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = PcStatus.fromJson(prefs.getString(pcId, null))
        val now = System.currentTimeMillis()
        prefs.edit().putString(pcId, PcStatus(summary, now).toJson()).apply()
        // Same counts: only the time moved. Redraw at most once a minute for that.
        if (old != null && old.summary == summary && now - old.at < 60_000) return
        publish(context)
    }

    /** Drops PCs that are no longer paired. */
    fun prune(context: Context) {
        val app = context.applicationContext as PkshellApp
        val ids = app.pcs().map { it.id }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stale = prefs.all.keys.filter { it !in ids }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach(::remove) }.apply()
        publish(context)
    }

    /** Asks every paired PC (`GET /api/info`) at once. */
    suspend fun refreshAll(context: Context) {
        val app = context.applicationContext as PkshellApp
        prune(context)
        coroutineScope {
            app.pcs().map { p ->
                async {
                    val summary = try {
                        withTimeoutOrNull(PROBE_TIMEOUT_MS) { app.apiFor(p).info() }?.let { text ->
                            AgentSummary.fromJson(JSONObject(text).optJSONObject("agents")) ?: AgentSummary(0, 0, 0)
                        }
                    } catch (e: ApiException) {
                        null
                    } catch (e: IOException) {
                        null
                    } catch (e: JSONException) {
                        null
                    }
                    put(context, p.id, summary)
                }
            }.awaitAll()
        }
        publish(context)
    }

    /** Redraws every placed widget and asks the system to refresh the tile. */
    fun publish(context: Context) {
        val c = context.applicationContext
        try {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, AgentWidget::class.java))
            if (ids.isNotEmpty()) AgentWidget.draw(c, manager, ids)
        } catch (e: RuntimeException) {
            Log.w(TAG, "widget update failed: ${e.message}")
        }
        try {
            TileService.requestListeningState(c, ComponentName(c, AgentTileService::class.java))
        } catch (e: RuntimeException) {
            Log.w(TAG, "tile update failed: ${e.message}")
        }
    }

    /** "입력 대기 2 · 작업 중 1", or why there are no counts. */
    fun headline(context: Context, t: BoardTotals, pcCount: Int): String = when {
        pcCount == 0 -> context.getString(R.string.board_no_pcs)
        t.online.isEmpty() && t.offline.isNotEmpty() -> context.getString(R.string.board_all_offline)
        t.online.isEmpty() -> context.getString(R.string.board_unknown)
        t.agents == 0 -> context.getString(R.string.board_idle)
        else -> listOfNotNull(
            if (t.waiting > 0) context.getString(R.string.pcs_agents_waiting, t.waiting) else null,
            if (t.working > 0) context.getString(R.string.pcs_agents_working, t.working) else null,
            if (t.done > 0) context.getString(R.string.pcs_agents_done, t.done) else null,
        ).joinToString(" · ")
    }

    /** "PC 2대 · 14:05" or "연결 안 됨: 집 PC · 14:05". */
    fun detail(context: Context, t: BoardTotals, pcCount: Int): String {
        if (pcCount == 0) return context.getString(R.string.board_open_app)
        val parts = ArrayList<String>()
        when {
            // The headline already says none can be reached: just which.
            t.offline.isNotEmpty() && t.online.isEmpty() -> parts.add(t.offline.joinToString(", "))
            t.offline.isNotEmpty() -> parts.add(context.getString(R.string.notify_ongoing_offline, t.offline.joinToString(", ")))
            else -> parts.add(context.getString(R.string.board_pcs, t.online.size))
        }
        if (t.oldest > 0) parts.add(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.oldest)))
        return parts.joinToString(" · ")
    }
}
