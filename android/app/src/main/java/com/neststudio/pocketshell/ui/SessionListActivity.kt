package com.neststudio.pocketshell.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.HostUpdateStatus
import com.neststudio.pocketshell.core.ProjectOptions
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.data.UiPrefs
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Session list with 실행 중 / 외부 / 최근 sections, pull-to-refresh and new session dialog (AC-9, AC-19). */
class SessionListActivity : AppCompatActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var message: TextView
    private lateinit var hostUpdateBar: View
    private lateinit var hostUpdateText: TextView
    private lateinit var hostUpdateApply: Button
    private var hostUpdateStatus: HostUpdateStatus? = null
    private val adapter = SessionAdapter(::openSession, ::confirmDelete, ::newTerminalHere)
    private val appUpdate = AppUpdateController(this)
    private var autoRefresh: kotlinx.coroutines.Job? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_MAIN) {
            // App icon: start at the PC list (this activity remains the launcher component for existing icons).
            startActivity(Intent(this, PcListActivity::class.java))
            finish()
            return
        }
        val app = application as PkshellApp
        if (app.pairing() == null) {
            finish() // back to the PC list
            return
        }
        setContentView(R.layout.activity_sessions)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = app.pairing()?.displayName
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(R.string.pcs_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_REFRESH, 0, R.string.refresh)
        toolbar.menu.add(0, MENU_CHECK_UPDATE, 1, R.string.update_check)
        toolbar.menu.add(0, MENU_RECENT_LIMIT, 2, R.string.recent_limit_menu)
        toolbar.menu.add(0, MENU_VIEW_SETTINGS, 3, R.string.view_settings)
        toolbar.menu.add(0, MENU_UNPAIR, 4, R.string.unpair)
        if (BuildConfig.DEBUG) toolbar.menu.add(0, MENU_DEBUG_UPDATE_URL, 5, R.string.update_debug_url)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_REFRESH -> refresh()
                MENU_CHECK_UPDATE -> appUpdate.manualCheck()
                MENU_RECENT_LIMIT -> showRecentLimitDialog()
                MENU_VIEW_SETTINGS -> ViewSettingsDialog.show(this)
                MENU_UNPAIR -> confirmUnpair()
                MENU_DEBUG_UPDATE_URL -> appUpdate.showDebugUrlDialog()
            }
            true
        }

        message = findViewById(R.id.message)
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { refresh() }
        val list = findViewById<RecyclerView>(R.id.list)
        // Wide screens (tablet, landscape): session cards in columns, section titles across the full width.
        val columns = WideScreen.columns(this)
        list.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == 0) columns else 1
            }
        }
        if (columns > 1) {
            // Cards keep 16dp to the screen edge and get 16dp between columns (8dp list padding + 8dp margins).
            val pad = (8 * resources.displayMetrics.density).toInt()
            list.setPaddingRelative(pad, list.paddingTop, pad, list.paddingBottom)
            adapter.compact = true
        }
        list.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showNewChooser() }

        hostUpdateBar = findViewById(R.id.hostUpdateBar)
        hostUpdateText = findViewById(R.id.hostUpdateText)
        hostUpdateApply = findViewById(R.id.hostUpdateApply)
        hostUpdateApply.setOnClickListener { confirmHostUpdate() }
    }

    override fun onResume() {
        super.onResume()
        appUpdate.autoCheck() // at most every 3 hours (UpdatePolicy), silent unless an update is found
        if ((application as PkshellApp).pairing() != null) refresh()
        // A PC paired just now lands here without passing the PC list: make sure its notifications are being watched.
        com.neststudio.pocketshell.notify.AgentWatchService.apply(this)
        // Agent states (작업 중 / 입력 대기) change on their own: keep the cards current while the list is shown.
        autoRefresh = lifecycleScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                refresh(silent = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoRefresh?.cancel()
        autoRefresh = null
    }

    /** [silent]: the periodic refresh. No spinner, no host update check, and a failure leaves the list as it is. */
    private fun refresh(silent: Boolean = false) {
        val api = (application as PkshellApp).api() ?: return
        if (loading) return
        loading = true
        if (!silent) swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                if (silent) {
                    try {
                        val sections = SessionSections.of(api.sessions(), UiPrefs(this@SessionListActivity).recentLimit)
                        adapter.submit(sections, getString(R.string.section_running), getString(R.string.section_external), getString(R.string.section_recent))
                    } catch (e: IOException) {
                        // keep showing what we have
                    } catch (e: org.json.JSONException) {
                    }
                    return@launch
                }

                val sections = SessionSections.of(api.sessions(), UiPrefs(this@SessionListActivity).recentLimit)
                adapter.submit(sections, getString(R.string.section_running), getString(R.string.section_external), getString(R.string.section_recent))
                showMessage(if (adapter.itemCount == 0) getString(R.string.sessions_empty) else null)
                refreshHostUpdate()
            } catch (e: ApiException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_load_sessions, e))
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_load_sessions, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_load_sessions, e))
            } finally {
                loading = false
                swipe.isRefreshing = false
            }
        }
    }

    /** Shows `/api/info.update`; hosts without the field (pre-0.1.1) simply hide the bar. */
    private suspend fun refreshHostUpdate() {
        val api = (application as PkshellApp).api() ?: return
        val status = try {
            HostUpdateStatus.fromInfo(api.info())
        } catch (e: IOException) {
            null
        }
        if (status == null || !status.isVisible) {
            hostUpdateBar.visibility = View.GONE
            return
        }
        val stateText = when (status.state) {
            HostUpdateStatus.STATE_AVAILABLE -> getString(R.string.host_update_state_available)
            HostUpdateStatus.STATE_DOWNLOADING -> getString(R.string.host_update_state_downloading)
            HostUpdateStatus.STATE_READY -> getString(R.string.host_update_state_ready)
            HostUpdateStatus.STATE_APPLYING -> getString(R.string.host_update_state_applying)
            HostUpdateStatus.STATE_ERROR -> getString(R.string.host_update_state_error, status.error ?: "")
            else -> status.state
        }
        hostUpdateStatus = status
        hostUpdateText.text = getString(R.string.host_update_status, status.current, status.latest.ifBlank { "?" }, stateText)
        hostUpdateApply.visibility = if (status.canApply) View.VISIBLE else View.GONE
        hostUpdateBar.visibility = View.VISIBLE
    }

    private fun confirmHostUpdate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.host_update_apply)
            .setMessage(getString(R.string.host_update_apply_confirm, hostUpdateStatus?.current ?: "?", hostUpdateStatus?.latest?.ifBlank { "?" } ?: "?"))
            .setPositiveButton(R.string.confirm) { _, _ -> applyHostUpdate(force = false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyHostUpdate(force: Boolean) {
        val api = (application as PkshellApp).api() ?: return
        hostUpdateApply.isEnabled = false
        lifecycleScope.launch {
            try {
                api.applyHostUpdate(force)
                showMessage(getString(R.string.host_update_applying_message))
                // The host restarts; give it a moment before reloading the list.
                delay(HOST_RESTART_WAIT_MS)
                refresh()
            } catch (e: ApiException) {
                if (e.code == 409 && !force) {
                    confirmForceHostUpdate(HostUpdateStatus.runningSessionsFrom409(e.body))
                } else {
                    showMessage(getString(R.string.host_update_failed, e.message ?: ""))
                }
            } catch (e: IOException) {
                showMessage(getString(R.string.host_update_failed, e.message ?: ""))
            } finally {
                hostUpdateApply.isEnabled = true
            }
        }
    }

    private fun confirmForceHostUpdate(running: Int?) {
        if (isFinishing) return
        val msg = if (running != null) getString(R.string.host_update_running_n, running)
        else getString(R.string.host_update_running)
        AlertDialog.Builder(this)
            .setTitle(R.string.host_update_running_title)
            .setMessage(msg)
            .setPositiveButton(R.string.host_update_force) { _, _ -> applyHostUpdate(force = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun openSession(s: SessionInfo) {
        startActivity(TerminalActivity.intent(this, s))
    }

    /** POST /api/external/{pid}/adopt: a new host session in the console's folder (the running program stays put). */
    private fun newTerminalHere(s: SessionInfo) {
        val api = (application as PkshellApp).api() ?: return
        val pid = s.pid ?: s.id.removePrefix("ext-").toIntOrNull()
        if (pid == null) {
            showMessage(getString(R.string.err_external_pid))
            return
        }
        lifecycleScope.launch {
            try {
                openSession(api.newTerminalHere(pid))
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_new_terminal, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_new_terminal, e))
            }
        }
    }

    private fun showNewChooser() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.new_project), getString(R.string.new_terminal))) { _, which ->
                if (which == 0) loadProjectOptions() else showNewSessionDialog()
            }
            .show()
    }

    private fun loadProjectOptions() {
        val api = (application as PkshellApp).api() ?: return
        lifecycleScope.launch {
            val options = try {
                api.projectOptions()
            } catch (e: ApiException) {
                showMessage(if (e.code == 404) getString(R.string.project_host_old) else ErrorText.of(this@SessionListActivity, R.string.err_project_options, e))
                return@launch
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_project_options, e))
                return@launch
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_project_options, e))
                return@launch
            }
            when {
                options.roots.isEmpty() -> showInfo(getString(R.string.project_no_roots))
                options.agents.isEmpty() -> showInfo(getString(R.string.project_no_agents))
                else -> showNewProjectDialog(options)
            }
        }
    }

    private fun showInfo(text: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(R.string.new_project).setMessage(text).setPositiveButton(R.string.confirm, null).show()
    }

    private fun showNewProjectDialog(options: ProjectOptions) {
        if (isFinishing) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_project, null)
        val rootSpinner = view.findViewById<Spinner>(R.id.rootSpinner)
        rootSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.roots)
        val nameInput = view.findViewById<EditText>(R.id.nameInput)
        val preview = view.findViewById<TextView>(R.id.pathPreview)
        val agentGroup = view.findViewById<RadioGroup>(R.id.agentGroup)
        options.agents.forEachIndexed { i, a ->
            agentGroup.addView(RadioButton(this).apply { id = View.generateViewId(); text = a.name; tag = a.id; isChecked = i == 0 })
        }
        fun selectedRoot(): String = options.roots[rootSpinner.selectedItemPosition.coerceIn(0, options.roots.size - 1)]
        fun updatePreview() {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            preview.text = getString(R.string.project_path_preview, selectedRoot().trimEnd('\\') + "\\" + name.ifEmpty { "…" })
        }
        nameInput.doAfterTextChanged { updatePreview() }
        rootSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = updatePreview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updatePreview()
        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                val agent = agentGroup.findViewById<RadioButton>(agentGroup.checkedRadioButtonId)?.tag as? String
                    ?: options.agents[0].id
                createProject(selectedRoot(), nameInput.text?.toString()?.trim().orEmpty(), agent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createProject(root: String, name: String, agent: String) {
        val api = (application as PkshellApp).api() ?: return
        lifecycleScope.launch {
            try {
                openSession(api.createProject(root, name, agent))
            } catch (e: ApiException) {
                // The host explains why (folder exists, bad name …); show it as is when it says something.
                showInfo(e.message?.takeIf { it.isNotBlank() } ?: ErrorText.of(this@SessionListActivity, R.string.err_create_project, e))
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_project, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_project, e))
            }
        }
    }

    private fun showNewSessionDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_new_session, null)
        val group = view.findViewById<RadioGroup>(R.id.shellGroup)
        group.check(R.id.shellPwsh)
        val titleInput = view.findViewById<EditText>(R.id.titleInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_session)
            .setView(view)
            .setPositiveButton(R.string.create) { _, _ ->
                val shell = when (group.checkedRadioButtonId) {
                    R.id.shellPowershell -> "powershell"
                    R.id.shellCmd -> "cmd"
                    R.id.shellBash -> "bash"
                    else -> "pwsh"
                }
                createSession(shell, titleInput.text?.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createSession(shell: String, title: String?) {
        val api = (application as PkshellApp).api() ?: return
        lifecycleScope.launch {
            try {
                val s = api.createSession(shell, title, null, null)
                openSession(s)
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            }
        }
    }

    private fun confirmDelete(s: SessionInfo) {
        if (s.isExternal) return
        val name = s.title.ifBlank { s.shell.ifBlank { s.id } }
        AlertDialog.Builder(this)
            .setTitle(getString(if (s.isRunningHost) R.string.delete_running_title else R.string.delete_history_title, name))
            .setMessage(if (s.isRunningHost) R.string.delete_running_confirm else R.string.delete_history_confirm)
            .setPositiveButton(if (s.isRunningHost) R.string.delete_running_action else R.string.delete_history_action) { _, _ ->
                val api = (application as PkshellApp).api() ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        api.delete(s.id)
                    } catch (e: IOException) {
                        showMessage(ErrorText.of(this@SessionListActivity, R.string.err_delete_session, e))
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRecentLimitDialog() {
        val prefs = UiPrefs(this)
        val choices = UiPrefs.RECENT_LIMIT_CHOICES
        val labels = choices.map { if (it == 0) getString(R.string.recent_limit_off) else getString(R.string.recent_limit_n, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.recent_limit_menu)
            .setSingleChoiceItems(labels, choices.indexOf(prefs.recentLimit)) { dialog, which ->
                prefs.recentLimit = choices[which]
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair)
            .setMessage(R.string.unpair_confirm)
            .setPositiveButton(R.string.unpair) { _, _ ->
                (application as PkshellApp).unpair()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MENU_REFRESH = 1
        const val MENU_UNPAIR = 2
        const val MENU_CHECK_UPDATE = 3
        const val MENU_DEBUG_UPDATE_URL = 4
        const val MENU_RECENT_LIMIT = 5
        const val MENU_VIEW_SETTINGS = 6
        const val HOST_RESTART_WAIT_MS = 5000L
        const val AUTO_REFRESH_MS = 5000L
    }
}

private sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val session: SessionInfo) : Row()
}

private class SessionAdapter(
    private val onClick: (SessionInfo) -> Unit,
    private val onLongClick: (SessionInfo) -> Unit,
    /** External row icon: new PocketShell terminal with the same shell, folder and size. */
    private val onNewHere: (SessionInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    /** Grid mode: 8dp side margins on cards (the list adds 8dp padding), section titles shifted to match. */
    var compact = false

    fun submit(s: SessionSections, running: String, external: String, recent: String) {
        val out = ArrayList<Row>()
        fun section(title: String, items: List<SessionInfo>) {
            if (items.isEmpty()) return
            out.add(Row.Header(title))
            items.forEach { out.add(Row.Item(it)) }
        }
        section(running, s.running)
        section(external, s.external)
        section(recent, s.recent)
        if (out == rows) return // periodic refresh with nothing new: leave the list (and any press in progress) alone
        rows = out
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == 0) R.layout.item_section else R.layout.item_session
        val view = inflater.inflate(layout, parent, false)
        if (compact) {
            val d = parent.resources.displayMetrics.density
            if (viewType == 0) {
                view.setPaddingRelative((12 * d).toInt(), view.paddingTop, (12 * d).toInt(), view.paddingBottom)
            } else {
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = (8 * d).toInt()
                    it.marginEnd = (8 * d).toInt()
                }
            }
        }
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).text = row.title
            is Row.Item -> {
                val s = row.session
                val v = holder.itemView
                v.findViewById<TextView>(R.id.title).text = s.title.ifBlank { s.shell.ifBlank { s.id } }
                val live = s.isExternal || s.isRunningHost
                v.findViewById<View>(R.id.stateDot).backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(v.context, if (live) R.color.live else R.color.text_muted),
                )
                // External consoles: only what tells them apart (AI CLI + folder).
                val detail = buildList {
                    when (s.agent) {
                        "claude" -> add("Claude")
                        "gpt" -> add("GPT")
                    }
                    if (!s.isExternal && s.shell.isNotBlank()) add(s.shell)
                    if (s.cwd.isNotBlank()) add(s.cwd)
                }.joinToString(" · ")
                v.findViewById<TextView>(R.id.detail).text = detail
                val state = v.findViewById<TextView>(R.id.agentState)
                val (label, color) = when (s.agentState) {
                    com.neststudio.pocketshell.core.AgentState.WAITING -> R.string.agent_waiting to R.color.attention
                    com.neststudio.pocketshell.core.AgentState.WORKING -> R.string.agent_working to R.color.accent
                    com.neststudio.pocketshell.core.AgentState.DONE -> R.string.agent_done to R.color.text_secondary
                    else -> 0 to 0
                }
                if (label == 0 || !live) {
                    state.visibility = View.GONE
                } else {
                    state.visibility = View.VISIBLE
                    state.setText(label)
                    state.setTextColor(androidx.core.content.ContextCompat.getColor(v.context, color))
                }
                val time = v.findViewById<TextView>(R.id.time)
                // External consoles: the host reports the last title/folder/screen change (older hosts: always now).
                time.text = formatTime(s.lastActivity)
                time.visibility = if (time.text.isNullOrBlank()) View.GONE else View.VISIBLE
                val newHere = v.findViewById<View>(R.id.newHere)
                newHere.visibility = if (s.isExternal) View.VISIBLE else View.GONE
                newHere.setOnClickListener { onNewHere(s) }
                v.setOnClickListener { onClick(s) }
                v.setOnLongClickListener { onLongClick(s); true }
            }
        }
    }

    private fun formatTime(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            "최근 활동 " + TIME_FORMAT.format(OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()))
        } catch (e: DateTimeParseException) {
            iso
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
