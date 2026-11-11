package com.neststudio.pocketshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Start screen: every paired PC with its live state. Tap a PC to open its sessions; add another PC with a PIN. */
class PcListActivity : AppCompatActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: View
    private val adapter = PcAdapter(::open, ::showActions)
    private val appUpdate = AppUpdateController(this)
    private var probes: List<Job> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pcs)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.menu.add(0, MENU_REFRESH, 0, R.string.refresh)
        toolbar.menu.add(0, MENU_INSTALL, 1, R.string.pcs_install_menu)
        toolbar.menu.add(0, MENU_CHECK_UPDATE, 2, R.string.update_check)
        toolbar.menu.add(0, MENU_VIEW_SETTINGS, 3, R.string.view_settings)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_REFRESH -> refresh()
                MENU_INSTALL -> showInstallGuide()
                MENU_CHECK_UPDATE -> appUpdate.manualCheck()
                MENU_VIEW_SETTINGS -> ViewSettingsDialog.show(this)
            }
            true
        }

        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { refresh() }
        empty = findViewById(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        val columns = WideScreen.columns(this)
        list.layoutManager = GridLayoutManager(this, columns)
        if (columns > 1) {
            val pad = (8 * resources.displayMetrics.density).toInt()
            list.setPaddingRelative(pad, list.paddingTop, pad, list.paddingBottom)
            adapter.compact = true
        }
        list.adapter = adapter
        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener { addPc() }
        findViewById<Button>(R.id.emptyAdd).setOnClickListener { addPc() }
        findViewById<Button>(R.id.emptyInstall).setOnClickListener { showInstallGuide() }
    }

    override fun onResume() {
        super.onResume()
        appUpdate.autoCheck() // at most every 3 hours (UpdatePolicy), silent unless an update is found
        refresh()
    }

    private fun refresh() {
        val app = application as PkshellApp
        val pcs = app.pcs()
        probes.forEach { it.cancel() }
        adapter.submit(pcs.map { PcRow(it, PcState.CHECKING) })
        empty.visibility = if (pcs.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fab).visibility = if (pcs.isEmpty()) View.GONE else View.VISIBLE
        swipe.isRefreshing = false
        probes = pcs.map { p -> lifecycleScope.launch { adapter.update(probe(app, p)) } }
    }

    /** GET /api/info within [PROBE_TIMEOUT_MS]; also stores addresses the PC added since pairing (e.g. Tailscale). */
    private suspend fun probe(app: PkshellApp, p: Pairing): PcRow {
        return try {
            val text = withTimeoutOrNull(PROBE_TIMEOUT_MS) { app.apiFor(p).info() } ?: return PcRow(p, PcState.OFFLINE)
            val info = JSONObject(text)
            val advertised = info.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.optString(it, "").trim() } }
                .orEmpty().filter { it.isNotEmpty() }
            var current = p
            val hosts = (p.hosts + advertised).distinct()
            if (hosts != p.hosts) {
                current = p.copy(hosts = hosts)
                app.store.save(current)
            }
            val updateState = info.optJSONObject("update")?.optString("state", "") ?: ""
            PcRow(current, PcState.ONLINE, info.optString("version", ""), updateState == "available" || updateState == "ready")
        } catch (e: ApiException) {
            PcRow(p, if (e.code == 401) PcState.UNAUTHORIZED else PcState.OFFLINE)
        } catch (e: IOException) {
            PcRow(p, PcState.OFFLINE)
        } catch (e: JSONException) {
            PcRow(p, PcState.OFFLINE)
        }
    }

    private fun open(p: Pairing) {
        (application as PkshellApp).select(p.id)
        startActivity(Intent(this, SessionListActivity::class.java))
    }

    private fun addPc() {
        startActivity(Intent(this, PairingActivity::class.java))
    }

    private fun showActions(p: Pairing) {
        AlertDialog.Builder(this)
            .setTitle(p.pcName)
            .setItems(arrayOf(getString(R.string.pcs_actions_rename), getString(R.string.pcs_actions_remove))) { _, which ->
                if (which == 0) rename(p) else confirmRemove(p)
            }
            .show()
    }

    private fun rename(p: Pairing) {
        val input = EditText(this).apply {
            setText(p.pcName)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_rename_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val app = application as PkshellApp
                    app.store.get(p.id)?.let { app.store.save(it.copy(pcName = name)) }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(p: Pairing) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_actions_remove)
            .setMessage(getString(R.string.pcs_remove_confirm, p.pcName))
            .setPositiveButton(R.string.unpair) { _, _ ->
                (application as PkshellApp).unpair(p.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInstallGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_install_menu)
            .setMessage(getString(R.string.pcs_install_body) + "\n\n" + DOWNLOAD_URL)
            .setPositiveButton(R.string.pcs_install_open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
                } catch (e: android.content.ActivityNotFoundException) {
                    copyLink()
                }
            }
            .setNeutralButton(R.string.pcs_install_copy) { _, _ -> copyLink() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyLink() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PocketShell", DOWNLOAD_URL))
        Toast.makeText(this, R.string.pcs_link_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MENU_REFRESH = 1
        const val MENU_INSTALL = 2
        const val MENU_CHECK_UPDATE = 3
        const val MENU_VIEW_SETTINGS = 4
        const val PROBE_TIMEOUT_MS = 8000L
        const val DOWNLOAD_URL = "https://github.com/MrDH1004/PocketShell/releases/latest"
    }
}

private enum class PcState { CHECKING, ONLINE, OFFLINE, UNAUTHORIZED }

private data class PcRow(
    val pc: Pairing,
    val state: PcState,
    val version: String = "",
    val hostUpdate: Boolean = false,
)

private class PcAdapter(
    private val onClick: (Pairing) -> Unit,
    private val onLongClick: (Pairing) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<PcRow> = emptyList()
    var compact = false

    fun submit(list: List<PcRow>) {
        rows = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    fun update(row: PcRow) {
        val i = rows.indexOfFirst { it.pc.id == row.pc.id }
        if (i < 0) return
        rows = rows.toMutableList().also { it[i] = row }
        notifyItemChanged(i)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pc, parent, false)
        if (compact) {
            val d = parent.resources.displayMetrics.density
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = (8 * d).toInt()
                it.marginEnd = (8 * d).toInt()
            }
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        val v = holder.itemView
        val ctx = v.context
        v.findViewById<TextView>(R.id.name).text = row.pc.pcName
        v.findViewById<TextView>(R.id.status).text = when (row.state) {
            PcState.CHECKING -> ctx.getString(R.string.pcs_checking)
            PcState.ONLINE -> ctx.getString(if (row.hostUpdate) R.string.pcs_online_update else R.string.pcs_online, row.version.ifBlank { "?" })
            PcState.OFFLINE -> ctx.getString(R.string.pcs_offline)
            PcState.UNAUTHORIZED -> ctx.getString(R.string.pcs_unauthorized)
        }
        v.findViewById<TextView>(R.id.hosts).text = row.pc.hosts.joinToString("  ·  ")
        val dotColor = when (row.state) {
            PcState.ONLINE -> R.color.live
            PcState.CHECKING -> R.color.text_secondary
            else -> R.color.text_muted
        }
        v.findViewById<View>(R.id.stateDot).backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, dotColor))
        v.setOnClickListener { onClick(row.pc) }
        v.setOnLongClickListener { onLongClick(row.pc); true }
    }
}
