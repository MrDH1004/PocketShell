package com.mpta.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.SessionInfo
import com.mpta.client.core.SessionSections
import com.mpta.client.net.ApiException
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
    private val adapter = SessionAdapter(::openSession, ::confirmDelete)

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
        toolbar.menu.add(0, MENU_UNPAIR, 1, R.string.unpair)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_REFRESH -> refresh()
                MENU_UNPAIR -> confirmUnpair()
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

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun openSession(s: SessionInfo) {
        startActivity(TerminalActivity.intent(this, s))
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
    }
}

private sealed class Row {
    data class Header(val title: String) : Row()
    data class Item(val session: SessionInfo) : Row()
}

private class SessionAdapter(
    private val onClick: (SessionInfo) -> Unit,
    private val onLongClick: (SessionInfo) -> Unit,
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
                val detail = buildList {
                    if (s.shell.isNotBlank()) add(s.shell)
                    if (s.isExternal && s.pid != null) add("PID ${s.pid}")
                    if (s.cwd.isNotBlank()) add(s.cwd)
                }.joinToString(" · ")
                v.findViewById<TextView>(R.id.detail).text = detail
                v.findViewById<TextView>(R.id.time).text = formatTime(s.lastActivity)
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
