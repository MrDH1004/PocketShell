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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.neststudio.pocketshell.core.PcLoad
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.HostUpdateStatus
import com.neststudio.pocketshell.core.ProjectOptions
import com.neststudio.pocketshell.core.SessionAction
import com.neststudio.pocketshell.core.SessionActions
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.core.UpdateRefusal
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
    private val adapter = SessionAdapter(::openSession, ::showSessionActions)
    private val appUpdate = AppUpdateController(this)
    private var autoRefresh: kotlinx.coroutines.Job? = null
    private var loadRefresh: kotlinx.coroutines.Job? = null
    private lateinit var pcLoad: View
    private var loading = false

    /** This PC shared one session with this phone: only that session, nothing of the owner's (no new sessions, files). */
    private var guest: com.neststudio.pocketshell.core.ShareGrant? = null
    /** The shared session opens by itself once, when the list first shows it. */
    private var guestOpened = false

    /** PC 탐색기 → a folder picked to open: a session there (terminal or the chosen AI CLI). */
    private val pcBrowser = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(FileBrowserActivity.RESULT_PATH)
        if (r.resultCode == RESULT_OK && !path.isNullOrBlank()) openFolder(path, r.data?.getStringExtra(FileBrowserActivity.RESULT_AGENT))
    }

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
        guest = app.pairing()?.share
        guestOpened = savedInstanceState?.getBoolean(STATE_GUEST_OPENED) ?: false
        // One line: the PC's name (no "세션" title above it), so the bar keeps its normal height.
        toolbar.title = guest?.let { g ->
            getString(R.string.guest_subtitle, app.pairing()?.displayName.orEmpty(),
                getString(if (g.readOnly) R.string.collab_mode_view_short else R.string.collab_mode_edit_short), untilText(g))
        } ?: app.pairing()?.displayName
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationContentDescription(R.string.pcs_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_PC_BROWSER, 0, R.string.pc_browser).setIcon(R.drawable.ic_folder_open)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_WEB, 0, R.string.web_view).setIcon(R.drawable.ic_language)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        // Everything else (알림, 보기, 세션 목록, 업데이트, 도움말) lives in 설정.
        toolbar.menu.add(0, MENU_REFRESH, 0, R.string.refresh)
        toolbar.menu.add(0, MENU_SETTINGS, 1, R.string.settings_title)
        toolbar.menu.add(0, MENU_UNPAIR, 2, R.string.unpair)
        if (BuildConfig.DEBUG) toolbar.menu.add(0, MENU_DEBUG_UPDATE_URL, 5, R.string.update_debug_url)
        if (guest != null) listOf(MENU_PC_BROWSER, MENU_WEB).forEach { toolbar.menu.removeItem(it) }
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_PC_BROWSER -> pcBrowser.launch(FileBrowserActivity.pcIntent(this, app.pairing()?.displayName.orEmpty()))
                MENU_WEB -> startActivity(WebActivity.intent(this))
                MENU_REFRESH -> refresh()
                MENU_SETTINGS -> startActivity(SettingsActivity.intent(this))
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
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) != 1) columns else 1
            }
        }
        if (columns > 1) {
            // Cards keep 16dp to the screen edge and get 16dp between columns (8dp list padding + 8dp margins).
            val pad = (8 * resources.displayMetrics.density).toInt()
            list.setPaddingRelative(pad, list.paddingTop, pad, list.paddingBottom)
            adapter.compact = true
        }
        adapter.guest = guest != null
        list.adapter = adapter
        // Scrolling down to the end of 최근 (or pulling up while already there) loads its next 20.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !rv.canScrollVertically(1)) adapter.onReachedEnd(rv)
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && !rv.canScrollVertically(1)) adapter.onReachedEnd(rv)
            }
        })
        findViewById<FloatingActionButton>(R.id.fab).apply {
            setOnClickListener { showNewChooser() }
            visibility = if (guest != null) View.GONE else View.VISIBLE
        }

        hostUpdateBar = findViewById(R.id.hostUpdateBar)
        hostUpdateText = findViewById(R.id.hostUpdateText)
        hostUpdateApply = findViewById(R.id.hostUpdateApply)
        hostUpdateApply.setOnClickListener { confirmHostUpdate() }
        pcLoad = findViewById(R.id.pcLoad)
        listOf(R.id.loadCpu to "CPU", R.id.loadGpu to "GPU", R.id.loadRam to "RAM").forEach { (id, name) ->
            pcLoad.findViewById<View>(id).findViewById<TextView>(R.id.loadName).text = name
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdate.autoCheck() // at most every 15 minutes (UpdatePolicy); a newer version shows the floating update pill
        if ((application as PkshellApp).pairing() != null) refresh()
        // A PC paired just now lands here without passing the PC list: make sure its notifications are being watched.
        com.neststudio.pocketshell.notify.AgentWatchService.apply(this)
        startPcLoad()
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
        loadRefresh?.cancel()
        loadRefresh = null
    }

    /** PC load above the sessions, every [LOAD_REFRESH_MS] while shown. Off (⋮ menu), a guest or an older host: hidden. */
    private fun startPcLoad() {
        loadRefresh?.cancel()
        loadRefresh = null
        val api = (application as PkshellApp).api()
        if (api == null || guest != null || !UiPrefs(this).pcLoad) {
            showPcLoad(null)
            return
        }
        loadRefresh = lifecycleScope.launch {
            while (true) {
                try {
                    showPcLoad(api.pcLoad())
                } catch (e: ApiException) {
                    showPcLoad(null)
                    if (e.code == 404 || e.code == 403) return@launch // host before 0.2.105, or not allowed
                } catch (e: IOException) {
                    // PC unreachable for a moment: keep the last values
                } catch (e: org.json.JSONException) {
                    showPcLoad(null)
                    return@launch
                }
                delay(LOAD_REFRESH_MS)
            }
        }
    }

    private fun showPcLoad(l: PcLoad?) {
        val visible = l != null
        if ((pcLoad.visibility == View.VISIBLE) != visible) {
            pcLoad.visibility = if (visible) View.VISIBLE else View.GONE
            adapter.tightTop = visible
        }
        if (l == null) return
        fun meter(id: Int, p: Double?, desc: String, extra: String? = null) {
            val v = pcLoad.findViewById<View>(id)
            val value = v.findViewById<TextView>(R.id.loadValue)
            value.text = PcLoad.percentText(p) + (extra?.let { " ($it)" } ?: "")
            val high = (p ?: 0.0) >= LOAD_HIGH
            value.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (high) R.color.attention else R.color.text_primary))
            v.contentDescription = desc
        }
        meter(R.id.loadCpu, l.cpu, "CPU " + PcLoad.percentText(l.cpu))
        meter(R.id.loadGpu, l.gpu, "GPU " + PcLoad.percentText(l.gpu))
        val gb = PcLoad.gbText(l.memUsed, l.memTotal).takeIf { l.memTotal > 0 }
        meter(R.id.loadRam, l.memPercent, getString(R.string.pc_load_ram_desc, PcLoad.percentText(l.memPercent), gb ?: "–"), gb)
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
                        val (list, favs) = api.sessionList()
                        favorites = favs
                        submit(SessionSections.of(list, UiPrefs(this@SessionListActivity).recentLimit, favs))
                    } catch (e: IOException) {
                        // keep showing what we have
                    } catch (e: org.json.JSONException) {
                    }
                    return@launch
                }

                val (all, favs) = api.sessionList()
                favorites = favs
                submit(SessionSections.of(all, UiPrefs(this@SessionListActivity).recentLimit, favs))
                val empty = if (guest != null) R.string.guest_session_closed else R.string.sessions_empty
                showMessage(if (adapter.itemCount == 0) getString(empty) else null)
                // Shared session: open it right away the first time (the list only has it).
                val shared = guest?.let { g -> all.firstOrNull { it.id == g.sessionId } }
                if (shared != null && !guestOpened) {
                    guestOpened = true
                    openSession(shared)
                }
                refreshHostUpdate()
            } catch (e: ApiException) {
                // A guest whose share ran out (or was stopped) gets 401: say so instead of a pairing error.
                showMessage(if (guest != null && e.code == 401) getString(R.string.guest_ended) else ErrorText.of(this@SessionListActivity, R.string.err_load_sessions, e))
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

    /** Starred folders from the last list (host favorites.json). */
    private var favorites: List<String> = emptyList()

    private fun submit(sections: SessionSections) = adapter.submit(
        sections, getString(R.string.section_running),
        getString(R.string.section_favorites), getString(R.string.section_recent),
    )

    private fun toggleFavorite(s: SessionInfo) {
        val on = !SessionSections.isFavorite(favorites, s.cwd)
        runSessionAction(R.string.err_favorite) { api ->
            favorites = api.setFavorite(s.cwd, on)
            android.widget.Toast.makeText(this, if (on) R.string.favorite_added else R.string.favorite_removed, android.widget.Toast.LENGTH_SHORT).show()
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

    private fun applyHostUpdate(force: Boolean, killWorking: Boolean = false) {
        val api = (application as PkshellApp).api() ?: return
        hostUpdateApply.isEnabled = false
        lifecycleScope.launch {
            try {
                api.applyHostUpdate(force, killWorking)
                showMessage(getString(R.string.host_update_applying_message))
                // The host restarts; give it a moment before reloading the list.
                delay(HOST_RESTART_WAIT_MS)
                refresh()
            } catch (e: ApiException) {
                val refusal = if (e.code == 409) UpdateRefusal.parse(e.body) else null
                when {
                    refusal != null && !force -> confirmForceHostUpdate(refusal)
                    // Forced, and an AI is in the middle of a turn: the host wants a second, separate yes.
                    refusal != null && !killWorking && refusal.working > 0 -> confirmKillWorking(refusal)
                    else -> showMessage(getString(R.string.host_update_failed, e.message ?: ""))
                }
            } catch (e: IOException) {
                showMessage(getString(R.string.host_update_failed, e.message ?: ""))
            } finally {
                hostUpdateApply.isEnabled = true
            }
        }
    }

    private fun confirmForceHostUpdate(refusal: UpdateRefusal) {
        if (isFinishing) return
        val running = refusal.running
        val msg = if (running != null) getString(R.string.host_update_running_n, running)
        else getString(R.string.host_update_running)
        AlertDialog.Builder(this)
            .setTitle(R.string.host_update_running_title)
            .setMessage(withSessionLines(msg, refusal))
            .setPositiveButton(R.string.host_update_force) { _, _ -> applyHostUpdate(force = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmKillWorking(refusal: UpdateRefusal) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.host_update_working_title)
            .setMessage(withSessionLines(getString(R.string.host_update_working_n, refusal.working), refusal))
            .setPositiveButton(R.string.host_update_kill_working) { _, _ -> applyHostUpdate(force = true, killWorking = true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** [message] followed by the sessions the update would end, one per line (at most eight). */
    private fun withSessionLines(message: String, refusal: UpdateRefusal): String {
        if (refusal.sessions.isEmpty()) return message
        val lines = refusal.sessions.take(UPDATE_SESSION_LINES).map { s ->
            "· " + when (s.agentState) {
                com.neststudio.pocketshell.core.AgentState.WORKING -> getString(R.string.host_update_session_working, s.title)
                com.neststudio.pocketshell.core.AgentState.WAITING -> getString(R.string.host_update_session_waiting, s.title)
                else -> s.title
            }
        }.toMutableList()
        val more = refusal.sessions.size - UPDATE_SESSION_LINES
        if (more > 0) lines.add("· " + getString(R.string.host_update_more, more))
        return message + "\n\n" + lines.joinToString("\n")
    }

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun openSession(s: SessionInfo) {
        if (s.isFavoriteStub) {
            // A starred folder with nothing left in it: a new session there.
            val api = (application as PkshellApp).api() ?: return
            lifecycleScope.launch {
                try {
                    openSession(api.createSession(null, null, null, null, cwd = s.cwd))
                } catch (e: IOException) {
                    showMessage(ErrorText.of(this@SessionListActivity, R.string.err_new_terminal, e))
                } catch (e: org.json.JSONException) {
                    showMessage(ErrorText.of(this@SessionListActivity, R.string.err_new_terminal, e))
                }
            }
            return
        }
        startActivity(TerminalActivity.intent(this, s))
        // The app icon's long-press list: the folders opened last (not for a session shared by someone else).
        val pc = (application as PkshellApp).pairing()
        if (pc != null && pc.share == null) ShortcutActivity.noteOpened(this, pc.id, s)
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
            val path = getString(R.string.project_path_preview, selectedRoot().trimEnd('\\') + "\\" + name.ifEmpty { "…" })
            preview.text = if (options.isDefault) path + "\n" + getString(R.string.project_default_root) else path
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
                val s = api.createProject(root, name, agent, UiPrefs(this@SessionListActivity).lastGrid)
                val note = if (s.reused) getString(R.string.session_reused) else getString(R.string.project_started_in, root.trimEnd('\\') + "\\" + name)
                Toast.makeText(this@SessionListActivity, note, Toast.LENGTH_LONG).show()
                openSession(s)
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
                val grid = UiPrefs(this@SessionListActivity).lastGrid
                val s = api.createSession(shell, title, grid?.first, grid?.second)
                openSession(s)
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            }
        }
    }

    private fun openFolder(path: String, agent: String?) {
        val api = (application as PkshellApp).api() ?: return
        lifecycleScope.launch {
            try {
                // Start at the phone's last grid: an AI CLI draws for the size it starts at.
                val grid = UiPrefs(this@SessionListActivity).lastGrid
                val s = api.createSession(null, com.neststudio.pocketshell.core.PcPath.name(path), grid?.first, grid?.second, cwd = path, agent = agent)
                if (s.reused) Toast.makeText(this@SessionListActivity, R.string.session_reused, Toast.LENGTH_LONG).show()
                openSession(s)
            } catch (e: ApiException) {
                showInfo(e.message?.takeIf { it.isNotBlank() } ?: ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@SessionListActivity, R.string.err_create_session, e))
            }
        }
    }

    private fun nameOf(s: SessionInfo) = s.displayTitle

    /** Long press: 세션 종료 / 삭제 / 폴더까지 삭제, whichever apply to this row. Each asks before it acts. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GUEST_OPENED, guestOpened)
    }

    /** "11/17 15:30까지" for a share's end ("" when the PC did not say). */
    private fun untilText(g: com.neststudio.pocketshell.core.ShareGrant): String {
        val ms = g.expiresAtMillis ?: return ""
        val t = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return getString(R.string.guest_until, DateTimeFormatter.ofPattern("M/d HH:mm").format(t))
    }

    private fun showSessionActions(s: SessionInfo) {
        if (guest != null) return // a shared session belongs to its owner
        val actions = SessionActions.of(s)
        val sheet = BottomSheetDialog(this)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (d * 8).toInt(), 0, (d * 16).toInt())
        }
        box.addView(TextView(this).apply {
            text = nameOf(s)
            textSize = 16f
            maxLines = 2
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((d * 24).toInt(), (d * 12).toInt(), (d * 24).toInt(), (d * 4).toInt())
        })
        box.addView(TextView(this).apply {
            text = s.cwd
            textSize = 12f
            maxLines = 2
            visibility = if (s.cwd.isBlank()) View.GONE else View.VISIBLE
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setPadding((d * 24).toInt(), 0, (d * 24).toInt(), (d * 12).toInt())
        })
        for (action in actions) {
            val (icon, label) = when (action) {
                SessionAction.GIT -> R.drawable.ic_git to R.string.git_menu
                SessionAction.END -> R.drawable.ic_stop to R.string.session_end
                SessionAction.NEW_TERMINAL -> R.drawable.ic_new_terminal_here to R.string.new_terminal_here
                SessionAction.FAVORITE -> if (SessionSections.isFavorite(favorites, s.cwd)) R.drawable.ic_star to R.string.favorite_remove
                    else R.drawable.ic_star_border to R.string.favorite_add
                SessionAction.DELETE -> R.drawable.ic_delete to (if (s.isRunningHost) R.string.session_delete else R.string.session_delete_record)
                SessionAction.DELETE_WITH_FOLDER -> R.drawable.ic_folder_delete to R.string.session_delete_folder
            }
            val row = layoutInflater.inflate(R.layout.item_sheet_action, box, false)
            row.findViewById<ImageView>(R.id.actionIcon).setImageResource(icon)
            row.findViewById<TextView>(R.id.actionLabel).setText(label)
            row.setOnClickListener {
                sheet.dismiss()
                when (action) {
                    SessionAction.GIT -> startActivity(GitChangesActivity.intent(this, s.cwd, nameOf(s)))
                    SessionAction.END -> confirmEnd(s)
                    SessionAction.NEW_TERMINAL -> newTerminalHere(s)
                    SessionAction.FAVORITE -> toggleFavorite(s)
                    SessionAction.DELETE -> confirmDelete(s)
                    SessionAction.DELETE_WITH_FOLDER -> confirmDeleteWithFolder(s)
                }
            }
            box.addView(row)
        }
        sheet.setContentView(box)
        sheet.show()
    }

    /** Runs a session action and reloads the list; a refusal from the PC is shown with the PC's own reason. */
    private fun runSessionAction(failed: Int, call: suspend (com.neststudio.pocketshell.net.ApiClient) -> Unit) {
        val api = (application as PkshellApp).api() ?: return
        lifecycleScope.launch {
            try {
                call(api)
                showMessage(null)
            } catch (e: ApiException) {
                showMessage(
                    when (e.code) {
                        // The PC answered and said why (folder in use, not a project folder, ...): no generic hint.
                        400, 409, 500 -> getString(failed) + ErrorText.cause(this@SessionListActivity, e.message)
                        404, 405 -> getString(R.string.err_format, getString(failed), getString(R.string.err_hint_update_pc))
                        else -> ErrorText.of(this@SessionListActivity, failed, e)
                    },
                )
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@SessionListActivity, failed, e))
            }
            refresh()
        }
    }

    private fun confirmEnd(s: SessionInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.end_title, nameOf(s)))
            .setMessage(if (s.isExternal) R.string.end_external_confirm else R.string.end_host_confirm)
            .setPositiveButton(R.string.end_action) { _, _ -> runSessionAction(R.string.err_end_session) { it.end(s.id) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(s: SessionInfo) {
        if (s.isExternal) return
        AlertDialog.Builder(this)
            .setTitle(getString(if (s.isRunningHost) R.string.delete_running_title else R.string.delete_history_title, nameOf(s)))
            .setMessage(if (s.isRunningHost) R.string.delete_running_confirm else R.string.delete_history_confirm)
            .setPositiveButton(if (s.isRunningHost) R.string.delete_running_action else R.string.delete_history_action) { _, _ ->
                runSessionAction(R.string.err_delete_session) { it.delete(s.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteWithFolder(s: SessionInfo) {
        if (s.isExternal) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_folder_title, nameOf(s)))
            .setMessage(getString(if (s.isRunningHost) R.string.delete_folder_running_confirm else R.string.delete_folder_confirm, s.cwd))
            .setPositiveButton(R.string.delete_folder_action) { _, _ ->
                runSessionAction(R.string.err_delete_folder) { it.delete(s.id, withFolder = true) }
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
        const val STATE_GUEST_OPENED = "guestOpened"
        const val MENU_REFRESH = 1
        const val MENU_UNPAIR = 2
        const val MENU_SETTINGS = 3
        const val MENU_DEBUG_UPDATE_URL = 4
        const val MENU_PC_BROWSER = 7
        const val MENU_WEB = 8
        const val HOST_RESTART_WAIT_MS = 5000L
        const val AUTO_REFRESH_MS = 5000L
        const val LOAD_REFRESH_MS = 2000L
        /** A value at or above this percent turns amber. */
        const val LOAD_HIGH = 85.0
    }
}

private sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val session: SessionInfo) : Row()
    /** End of a paged 최근: the rest of it ([left] more). Reaching it loads the next page by itself. */
    data class More(val left: Int) : Row()
}

private class SessionAdapter(
    private val onClick: (SessionInfo) -> Unit,
    private val onLongClick: (SessionInfo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    /** Grid mode: 8dp side margins on cards (the list adds 8dp padding), section titles shifted to match. */
    var compact = false

    /** A shared session on someone else's PC: no per-row owner actions (new terminal, web, long press). */
    var guest = false

    /** The PC load bar sits above the list: the first section title drops most of its top gap. */
    var tightTop = false
        set(value) {
            if (field == value) return
            field = value
            if (rows.isNotEmpty()) notifyItemChanged(0)
        }

    private var last: (() -> Unit)? = null

    /** 최근 shows this many rows; scrolling down to the 더보기 line (or tapping it) adds as many again. */
    private var recentShown = RECENT_PAGE

    /** The 더보기 line reads 불러오는 중 for a moment before the next page comes in, so the line is seen. */
    private var loadingMore = false

    fun submit(s: SessionSections, running: String, favorites: String, recent: String) {
        last = { submit(s, running, favorites, recent) }
        val out = ArrayList<Row>()
        fun section(title: String, items: List<SessionInfo>, total: Int = items.size) {
            if (items.isEmpty()) return
            out.add(Row.Header(if (total > items.size) "$title $total" else title))
            items.forEach { out.add(Row.Item(it)) }
        }
        // 실행 중: PocketShell's sessions and consoles opened on the PC together, newest first (the latter marked PC).
        section(running, (s.running + s.external).sortedByDescending { it.lastActivity })
        section(favorites, s.favorites)
        section(recent, s.recent.take(recentShown), s.recent.size)
        if (s.recent.size > recentShown) out.add(Row.More(s.recent.size - recentShown))
        if (out == rows) return // periodic refresh with nothing new: leave the list (and any press in progress) alone
        rows = out
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Item -> 1
        is Row.More -> 2
    }

    private fun showMore() {
        loadingMore = false
        recentShown += RECENT_PAGE
        last?.invoke()
    }

    /** The user scrolled down to the end of the list: if it ends with 더보기, load the next page after a short pause. */
    fun onReachedEnd(list: RecyclerView) {
        if (loadingMore || rows.lastOrNull() !is Row.More) return
        loadingMore = true
        notifyItemChanged(rows.size - 1)
        list.postDelayed({ if (loadingMore) showMore() }, MORE_DELAY_MS)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == 2) {
            val d = parent.resources.displayMetrics.density
            // Plain text line, not a button: "아래로 내려 더보기 (n개 더)".
            val t = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER
                minHeight = (48 * d).toInt()
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColor(parent.context, R.color.text_muted))
            }
            return object : RecyclerView.ViewHolder(t) {}
        }
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
            is Row.More -> (holder.itemView as TextView).apply {
                text = if (loadingMore) context.getString(R.string.recent_loading) else context.getString(R.string.recent_more, row.left)
                setOnClickListener { showMore() }
            }
            is Row.Header -> (holder.itemView as TextView).apply {
                text = row.title
                val d = resources.displayMetrics.density
                setPaddingRelative(paddingStart, ((if (position == 0 && tightTop) 8 else 24) * d).toInt(), paddingEnd, paddingBottom)
            }
            is Row.Item -> {
                val s = row.session
                val v = holder.itemView
                v.findViewById<TextView>(R.id.title).text = s.displayTitle
                v.findViewById<View>(R.id.pcBadge).visibility = if (s.isExternal) View.VISIBLE else View.GONE
                val live = s.isExternal || s.isRunningHost
                v.findViewById<View>(R.id.stateDot).backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(v.context, if (live) R.color.live else R.color.text_muted),
                )
                // Under the name: the AI CLI's mark (one colour) and the folder; the shell only when there is no folder.
                val mark = v.findViewById<android.widget.ImageView>(R.id.agentMark)
                val markRes = when (s.agent) {
                    "claude" -> R.drawable.ic_agent_claude
                    "gpt" -> R.drawable.ic_agent_gpt
                    else -> 0
                }
                mark.visibility = if (markRes == 0) View.GONE else View.VISIBLE
                if (markRes != 0) mark.setImageResource(markRes)
                val detail = v.findViewById<TextView>(R.id.detail)
                detail.text = if (s.isFavoriteStub) s.cwd + " · " + v.context.getString(R.string.favorite_no_record)
                    else s.cwd.ifBlank { if (s.isExternal) "" else s.shell }
                detail.contentDescription = listOfNotNull(
                    when (s.agent) { "claude" -> "Claude"; "gpt" -> "GPT"; else -> null }, detail.text.toString().ifBlank { null },
                ).joinToString(", ")
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
                // A web server runs in this project's folder (host ≥ 0.2.88): open it in the web view.
                val webHere = v.findViewById<View>(R.id.webHere)
                webHere.visibility = if (s.webPorts.isNotEmpty() && !guest) View.VISIBLE else View.GONE
                webHere.setOnClickListener { openProjectWeb(v.context, s) }
                v.setOnClickListener { onClick(s) }
                v.setOnLongClickListener { onLongClick(s); true }
            }
        }
    }

    /** The project's web server in the web view (http://localhost:port, opened by the PC); several → pick one. */
    private fun openProjectWeb(context: android.content.Context, s: SessionInfo) {
        val ports = s.webPorts
        fun open(port: Int) = context.startActivity(WebActivity.intent(context, "http://localhost:$port"))
        if (ports.size == 1) {
            open(ports[0])
            return
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.web_project_pick, s.displayTitle))
            .setItems(ports.map { "localhost:$it" }.toTypedArray()) { _, which -> open(ports[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatTime(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            com.neststudio.pocketshell.core.SessionTime.short(OffsetDateTime.parse(iso).toInstant(), java.time.Instant.now(), ZoneId.systemDefault())
        } catch (e: DateTimeParseException) {
            iso
        }
    }
}

/** 최근 rows per page in the session list. */
private const val RECENT_PAGE = 20
private const val MORE_DELAY_MS = 400L

/** Sessions listed in the update confirmation before "and n more". */
private const val UPDATE_SESSION_LINES = 8
