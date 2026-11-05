package com.mpta.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mpta.client.BuildConfig
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.HostUpdateStatus
import com.mpta.client.core.SessionInfo
import com.mpta.client.core.SessionSections
import com.mpta.client.net.ApiException
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
    private val adapter = SessionAdapter(::openSession, ::confirmDelete, ::newTerminalHere)
    private val appUpdate = AppUpdateController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as MptaApp
        if (app.pairing() == null) {
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_sessions)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = app.pairing()?.pcName
        toolbar.menu.add(0, MENU_REFRESH, 0, R.string.refresh)
        toolbar.menu.add(0, MENU_CHECK_UPDATE, 1, R.string.update_check)
        toolbar.menu.add(0, MENU_UNPAIR, 2, R.string.unpair)
        if (BuildConfig.DEBUG) toolbar.menu.add(0, MENU_DEBUG_UPDATE_URL, 3, R.string.update_debug_url)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_REFRESH -> refresh()
                MENU_CHECK_UPDATE -> appUpdate.manualCheck()
                MENU_UNPAIR -> confirmUnpair()
                MENU_DEBUG_UPDATE_URL -> appUpdate.showDebugUrlDialog()
            }
            true
        }

        message = findViewById(R.id.message)
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { refresh() }
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showNewSessionDialog() }

        hostUpdateBar = findViewById(R.id.hostUpdateBar)
        hostUpdateText = findViewById(R.id.hostUpdateText)
        hostUpdateApply = findViewById(R.id.hostUpdateApply)
        hostUpdateApply.setOnClickListener { confirmHostUpdate() }

        if (savedInstanceState == null) appUpdate.autoCheck()
    }

    override fun onResume() {
        super.onResume()
        if ((application as MptaApp).pairing() != null) refresh()
    }

    private fun refresh() {
        val api = (application as MptaApp).api() ?: return
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val sections = SessionSections.of(api.sessions())
                adapter.submit(sections, getString(R.string.section_running), getString(R.string.section_external), getString(R.string.section_recent))
                showMessage(if (adapter.itemCount == 0) getString(R.string.sessions_empty) else null)
                refreshHostUpdate()
            } catch (e: ApiException) {
                if (e.code == 401) showMessage("인증 실패: 이 기기의 등록이 취소되었습니다. 메뉴 > 페어링 해제 후 다시 페어링하세요.")
                else showMessage(e.message)
            } catch (e: IOException) {
                showMessage("PC에 연결할 수 없습니다: ${e.message}")
            } catch (e: org.json.JSONException) {
                showMessage("서버 응답 형식 오류: ${e.message}")
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    /** Shows `/api/info.update`; hosts without the field (pre-0.1.1) simply hide the bar. */
    private suspend fun refreshHostUpdate() {
        val api = (application as MptaApp).api() ?: return
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
        hostUpdateText.text = getString(R.string.host_update_status, status.current, status.latest.ifBlank { "?" }, stateText)
        hostUpdateApply.visibility = if (status.canApply) View.VISIBLE else View.GONE
        hostUpdateBar.visibility = View.VISIBLE
    }

    private fun confirmHostUpdate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.host_update_apply)
            .setMessage(R.string.host_update_apply_confirm)
            .setPositiveButton(R.string.confirm) { _, _ -> applyHostUpdate(force = false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyHostUpdate(force: Boolean) {
        val api = (application as MptaApp).api() ?: return
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
        val api = (application as MptaApp).api() ?: return
        val pid = s.pid ?: s.id.removePrefix("ext-").toIntOrNull()
        if (pid == null) {
            showMessage("외부 세션 PID를 알 수 없습니다")
            return
        }
        lifecycleScope.launch {
            try {
                openSession(api.newTerminalHere(pid))
            } catch (e: IOException) {
                showMessage("새 터미널 열기 실패: ${e.message}")
            } catch (e: org.json.JSONException) {
                showMessage("새 터미널 열기 실패: 서버 응답 형식 오류 (${e.message})")
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
        val api = (application as MptaApp).api() ?: return
        lifecycleScope.launch {
            try {
                val s = api.createSession(shell, title, null, null)
                openSession(s)
            } catch (e: IOException) {
                showMessage("세션 생성 실패: ${e.message}")
            } catch (e: org.json.JSONException) {
                showMessage("세션 생성 실패: 서버 응답 형식 오류 (${e.message})")
            }
        }
    }

    private fun confirmDelete(s: SessionInfo) {
        if (s.isExternal) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session)
            .setMessage(if (s.isRunningHost) R.string.delete_running_confirm else R.string.delete_history_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val api = (application as MptaApp).api() ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        api.delete(s.id)
                    } catch (e: IOException) {
                        showMessage("삭제 실패: ${e.message}")
                    }
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair)
            .setMessage(R.string.unpair_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                (application as MptaApp).unpair()
                startActivity(Intent(this, PairingActivity::class.java))
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
        const val HOST_RESTART_WAIT_MS = 5000L
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
        rows = out
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == 0) R.layout.item_section else R.layout.item_session
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).text = row.title
            is Row.Item -> {
                val s = row.session
                val v = holder.itemView
                v.findViewById<TextView>(R.id.title).text = s.title.ifBlank { s.shell.ifBlank { s.id } }
                v.findViewById<View>(R.id.badge).visibility = if (s.isExternal) View.VISIBLE else View.GONE
                // External consoles: only what tells them apart (AI CLI + folder); their "last activity" is always now.
                val detail = buildList {
                    when (s.agent) {
                        "claude" -> add("Claude")
                        "gpt" -> add("GPT")
                    }
                    if (!s.isExternal && s.shell.isNotBlank()) add(s.shell)
                    if (s.cwd.isNotBlank()) add(s.cwd)
                }.joinToString(" · ")
                v.findViewById<TextView>(R.id.detail).text = detail
                val time = v.findViewById<TextView>(R.id.time)
                time.text = if (s.isExternal) "" else formatTime(s.lastActivity)
                time.visibility = if (s.isExternal) View.GONE else View.VISIBLE
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
