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
    private lateinit var prefs: com.neststudio.pocketshell.data.UiPrefs
    private var notifyItem: android.view.MenuItem? = null
    private val askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, R.string.notify_enabled, Toast.LENGTH_SHORT).show()
            offerBatteryExemption()
        } else {
            prefs.agentNotify = false
            Toast.makeText(this, R.string.notify_denied, Toast.LENGTH_LONG).show()
        }
        applyNotifications()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pcs)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        prefs = com.neststudio.pocketshell.data.UiPrefs(this)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        notifyItem = toolbar.menu.add(0, MENU_NOTIFY, 0, R.string.notify_menu_off)
        toolbar.menu.add(0, MENU_QUIET, 0, R.string.quiet_title)
        toolbar.menu.add(0, MENU_REFRESH, 0, R.string.refresh)
        toolbar.menu.add(0, MENU_INSTALL, 1, R.string.pcs_install_menu)
        toolbar.menu.add(0, MENU_CHECK_UPDATE, 2, R.string.update_check)
        toolbar.menu.add(0, MENU_VIEW_SETTINGS, 3, R.string.view_settings)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_NOTIFY -> toggleNotifications()
                MENU_QUIET -> showQuietHours()
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
        ensureNotifications()
    }

    // ---- "an AI CLI stopped" notifications (AgentWatchService) ----

    private fun canPost(): Boolean = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** On by default: with a PC paired, ask for the notification permission once, then keep the service in step. */
    private fun ensureNotifications() {
        val hasPc = (application as PkshellApp).pcs().isNotEmpty()
        if (prefs.agentNotify && hasPc && !canPost()) {
            if (!prefs.notifyPermissionAsked && android.os.Build.VERSION.SDK_INT >= 33) {
                prefs.notifyPermissionAsked = true
                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            prefs.agentNotify = false // asked before and refused: reflect that in the menu
        }
        applyNotifications()
    }

    private fun applyNotifications() {
        notifyItem?.setTitle(if (prefs.agentNotify) R.string.notify_menu_off else R.string.notify_menu_on)
        com.neststudio.pocketshell.notify.AgentWatchService.apply(this)
    }

    private fun toggleNotifications() {
        if (prefs.agentNotify) {
            prefs.agentNotify = false
            Toast.makeText(this, R.string.notify_disabled, Toast.LENGTH_SHORT).show()
            applyNotifications()
            return
        }
        prefs.agentNotify = true
        if (!canPost()) {
            // Android shows its dialog only the first couple of times; after that it answers "denied" at once and
            // the denied message points to the system settings.
            prefs.notifyPermissionAsked = true
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        Toast.makeText(this, R.string.notify_enabled, Toast.LENGTH_SHORT).show()
        applyNotifications()
        offerBatteryExemption()
    }

    /** Quiet hours: on / off and the daily range. Each row changes one thing and shows the dialog again. */
    private fun showQuietHours() {
        val items = arrayOf(
            getString(if (prefs.quietEnabled) R.string.quiet_on else R.string.quiet_off),
            getString(R.string.quiet_start, com.neststudio.pocketshell.core.QuietHours.format(prefs.quietStart)),
            getString(R.string.quiet_end, com.neststudio.pocketshell.core.QuietHours.format(prefs.quietEnd)),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.quiet_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { prefs.quietEnabled = !prefs.quietEnabled; showQuietHours() }
                    1 -> pickTime(prefs.quietStart) { prefs.quietStart = it; showQuietHours() }
                    else -> pickTime(prefs.quietEnd) { prefs.quietEnd = it; showQuietHours() }
                }
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        android.app.TimePickerDialog(this, { _, hour, minute -> onPicked(hour * 60 + minute) }, minutes / 60, minutes % 60, true).show()
    }

    /** Doze delays the long poll on a phone left idle; offer the system setting that exempts the app (once). */
    private fun offerBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (prefs.batteryHintShown || pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.batteryHintShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.notify_battery_title)
            .setMessage(R.string.notify_battery_body)
            .setPositiveButton(R.string.notify_battery_open) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, R.string.notify_denied, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.notify_battery_later, null)
            .show()
    }

    private fun refresh() {
        val app = application as PkshellApp
        val pcs = app.pcs()
        probes.forEach { it.cancel() }
        // Keep what the last probe showed while the new one runs, so cards do not flash back to "checking" every
        // time the list comes back (return from a PC, alias saved); only PCs not seen yet start as checking.
        adapter.submit(pcs.map { p -> adapter.rowOf(p.id)?.copy(pc = p) ?: PcRow(p, PcState.CHECKING) })
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
            // Re-read: the alias may have been edited while this probe was running.
            var current = app.store.get(p.id) ?: return PcRow(p, PcState.OFFLINE)
            val hosts = (current.hosts + advertised).distinct()
            if (hosts != current.hosts) {
                current = current.copy(hosts = hosts)
                app.store.save(current)
            }
            val updateState = info.optJSONObject("update")?.optString("state", "") ?: ""
            PcRow(
                current, PcState.ONLINE, info.optString("version", ""), updateState == "available" || updateState == "ready",
                com.neststudio.pocketshell.core.AgentSummary.fromJson(info.optJSONObject("agents")),
            )
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
            .setTitle(p.displayName)
            .setItems(arrayOf(getString(R.string.pcs_actions_alias), getString(R.string.pcs_actions_remove))) { _, which ->
                if (which == 0) editAlias(p) else confirmRemove(p)
            }
            .show()
    }

    /** Alias shown instead of the PC's own name; saving it empty goes back to the PC name. */
    private fun editAlias(p: Pairing) {
        val input = EditText(this).apply {
            setText(p.alias)
            setSelection(text.length)
            hint = p.pcName
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(ALIAS_MAX))
        }
        val help = TextView(this).apply {
            text = getString(R.string.pcs_alias_help, p.pcName)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 13f
            // Line up with the text inside the field (the EditText insets its text by about 4dp).
            val inset = (4 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, 0)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(help)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_alias_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val app = application as PkshellApp
                app.store.get(p.id)?.let { app.store.save(it.copy(alias = input.text.toString().trim())) }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(p: Pairing) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_actions_remove)
            .setMessage(getString(R.string.pcs_remove_confirm, p.displayName))
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
        const val MENU_NOTIFY = 5
        const val MENU_QUIET = 6
        const val PROBE_TIMEOUT_MS = 8000L
        const val ALIAS_MAX = 30
        const val DOWNLOAD_URL = "https://github.com/MrDH1004/PocketShell/releases/latest"
    }
}

private enum class PcState { CHECKING, ONLINE, OFFLINE, UNAUTHORIZED }

private data class PcRow(
    val pc: Pairing,
    val state: PcState,
    val version: String = "",
    val hostUpdate: Boolean = false,
    /** AI sessions per state; null from a host that does not report it (older than 0.2.59). */
    val agents: com.neststudio.pocketshell.core.AgentSummary? = null,
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

    fun rowOf(id: String): PcRow? = rows.firstOrNull { it.pc.id == id }

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
        v.findViewById<TextView>(R.id.name).text = row.pc.displayName
        val status = when (row.state) {
            PcState.CHECKING -> ctx.getString(R.string.pcs_checking)
            PcState.ONLINE -> ctx.getString(if (row.hostUpdate) R.string.pcs_online_update else R.string.pcs_online, row.version.ifBlank { "?" })
            PcState.OFFLINE -> ctx.getString(R.string.pcs_offline)
            PcState.UNAUTHORIZED -> ctx.getString(R.string.pcs_unauthorized)
        }
        // What needs the user first: sessions waiting on an answer, then working, then finished.
        val a = row.agents?.takeIf { row.state == PcState.ONLINE && !it.isEmpty }
        val agentParts = if (a == null) emptyList() else listOfNotNull(
            if (a.waiting > 0) ctx.getString(R.string.pcs_agents_waiting, a.waiting) else null,
            if (a.working > 0) ctx.getString(R.string.pcs_agents_working, a.working) else null,
            if (a.done > 0) ctx.getString(R.string.pcs_agents_done, a.done) else null,
        )
        val agentsView = v.findViewById<TextView>(R.id.agents)
        agentsView.visibility = if (agentParts.isEmpty()) View.GONE else View.VISIBLE
        agentsView.text = agentParts.joinToString("  ·  ")
        agentsView.setTextColor(ContextCompat.getColor(ctx, if ((a?.waiting ?: 0) > 0) R.color.attention else R.color.accent))
        v.findViewById<TextView>(R.id.status).text = status
        // With an alias on top, the PC's own name leads the address line so the two stay tied together.
        val own = if (row.pc.alias.isNotBlank()) listOf(row.pc.pcName) else emptyList()
        v.findViewById<TextView>(R.id.hosts).text = (own + row.pc.hosts).joinToString("  ·  ")
        v.findViewById<View>(R.id.more).setOnClickListener { onLongClick(row.pc) }
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
