package com.neststudio.pocketshell.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.ui.PcListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: how many AI sessions wait / work / are done on the paired PCs. Tapping it opens the PC list;
 * the refresh button asks the PCs now. It also refreshes when the system says so (every 30 min) and whenever the
 * notification service or the PC list learn new counts ([Board.publish]).
 */
class AgentWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context)
    }

    /** Asks the PCs in the background; [goAsync] keeps the receiver alive until the answers are drawn. */
    private fun refresh(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(android.content.ComponentName(app, AgentWidget::class.java))
        draw(app, manager, ids, refreshing = true)
        scope.launch {
            try {
                Board.refreshAll(app)
            } finally {
                draw(app, manager, ids)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.neststudio.pocketshell.WIDGET_REFRESH"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, refreshing: Boolean = false) {
            if (ids.isEmpty()) return
            val app = context.applicationContext as PkshellApp
            val pcCount = app.pcs().size
            val t = Board.totals(context)
            val views = RemoteViews(context.packageName, R.layout.widget_agents)
            views.setTextViewText(R.id.widgetHeadline, Board.headline(context, t, pcCount))
            views.setTextColor(
                R.id.widgetHeadline,
                ContextCompat.getColor(context, if (t.waiting > 0) R.color.attention else R.color.text_primary),
            )
            views.setTextViewText(
                R.id.widgetDetail,
                if (refreshing) context.getString(R.string.board_refreshing) else Board.detail(context, t, pcCount),
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, PcListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AgentWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetRefresh, refresh)
            manager.updateAppWidget(ids, views)
        }
    }
}
