package com.neststudio.pocketshell.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.ui.PcListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick settings tile: the AI session counts of the paired PCs ([Board]); lit while an AI session waits on a
 * question. Pulling the shade down asks the PCs again when the counts are older than [STALE_MS]. Tap = PC list.
 */
class AgentTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshing: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        draw()
        val t = Board.totals(this)
        val stale = t.oldest == 0L || System.currentTimeMillis() - t.oldest > STALE_MS || t.unknown.isNotEmpty()
        if (stale && refreshing?.isActive != true && (application as PkshellApp).pcs().isNotEmpty()) {
            refreshing = scope.launch {
                Board.refreshAll(this@AgentTileService)
                draw()
            }
        }
    }

    override fun onStopListening() {
        refreshing?.cancel()
        refreshing = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PcListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun draw() {
        val tile = qsTile ?: return
        val pcCount = (application as PkshellApp).pcs().size
        val t = Board.totals(this)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notify)
        tile.label = getString(R.string.tile_label)
        val text = Board.headline(this, t, pcCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = text
        tile.contentDescription = "${getString(R.string.tile_label)}: $text"
        tile.state = if (t.waiting > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        const val STALE_MS = 30_000L
    }
}
