package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.DiffLineKind
import com.neststudio.pocketshell.core.DiffLines
import com.neststudio.pocketshell.core.GitFile
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Uncommitted changes of the project a session runs in (read-only): branch and the changed files, then one file's
 * diff with added / removed lines coloured. Opened once per screen: list mode without [EXTRA_FILE], diff mode with it.
 */
class GitChangesActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var message: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private val adapter = FileAdapter { openDiff(it) }
    private var dir = ""
    private var root = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_git)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)
        dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
        val file = intent.getStringExtra(EXTRA_FILE)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = if (file != null) file.substringAfterLast('/') else getString(R.string.git_title)
            subtitle = if (file != null) file.substringBeforeLast('/', "").ifBlank { null } else intent.getStringExtra(EXTRA_TITLE)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
            if (file == null) {
                menu.add(0, MENU_REFRESH, 0, R.string.refresh).setIcon(R.drawable.ic_refresh)
                    .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { if (it.itemId == MENU_REFRESH) load(); true }
            }
        }
        header = findViewById(R.id.header)
        message = findViewById(R.id.message)
        swipe = findViewById(R.id.swipe)
        if (file == null) {
            val list = findViewById<RecyclerView>(R.id.list)
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = adapter
            swipe.setOnRefreshListener { load() }
            load()
        } else {
            root = dir
            swipe.visibility = View.GONE
            findViewById<View>(R.id.diffScroll).visibility = View.VISIBLE
            loadDiff(file, intent.getBooleanExtra(EXTRA_UNTRACKED, false))
        }
    }

    private fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun load() {
        val api = (application as PkshellApp).api() ?: return finish()
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val s = api.gitStatus(dir)
                root = s.root
                val parts = buildList {
                    add(getString(R.string.git_branch, s.branch.ifBlank { "?" }))
                    if (s.ahead > 0) add(getString(R.string.git_ahead, s.ahead))
                    if (s.behind > 0) add(getString(R.string.git_behind, s.behind))
                    if (s.files.isNotEmpty()) add(getString(R.string.git_summary, s.files.size, s.totalAdded, s.totalRemoved))
                }
                header.text = parts.joinToString("  ·  ") + "\n" + s.root
                header.visibility = View.VISIBLE
                adapter.submit(s.files)
                showMessage(
                    when {
                        s.files.isEmpty() -> getString(R.string.git_clean)
                        s.truncated -> getString(R.string.git_truncated, s.files.size)
                        else -> null
                    },
                )
            } catch (e: ApiException) {
                // The PC says why (not a git repository, git not installed): its own words. A 404 without them is an
                // older PC that has no such route.
                val reason = pcReason(e)
                showMessage(
                    when {
                        reason != null && e.code != 401 -> reason
                        e.code == 404 || e.code == 405 -> getString(R.string.git_old_pc)
                        else -> ErrorText.of(this@GitChangesActivity, R.string.git_failed, e)
                    },
                )
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@GitChangesActivity, R.string.git_failed, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@GitChangesActivity, R.string.git_failed, e))
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun pcReason(e: ApiException): String? = try {
        org.json.JSONObject(e.body).optString("error", "").ifBlank { null }
    } catch (x: org.json.JSONException) {
        null
    }

    private fun openDiff(f: GitFile) {
        startActivity(
            Intent(this, GitChangesActivity::class.java)
                .putExtra(EXTRA_DIR, root)
                .putExtra(EXTRA_FILE, f.path)
                .putExtra(EXTRA_UNTRACKED, f.isUntracked),
        )
    }

    private fun loadDiff(file: String, untracked: Boolean) {
        val api = (application as PkshellApp).api() ?: return finish()
        val view = findViewById<TextView>(R.id.diff)
        lifecycleScope.launch {
            try {
                val d = api.gitDiff(root, file, untracked)
                view.text = colour(d.diff)
                showMessage(
                    when {
                        d.binary -> getString(R.string.git_binary)
                        d.truncated -> getString(R.string.git_diff_truncated)
                        d.diff.isBlank() -> getString(R.string.git_no_diff)
                        else -> null
                    },
                )
            } catch (e: ApiException) {
                showMessage(pcReason(e)?.takeIf { e.code != 401 } ?: ErrorText.of(this@GitChangesActivity, R.string.git_failed, e))
            } catch (e: IOException) {
                showMessage(ErrorText.of(this@GitChangesActivity, R.string.git_failed, e))
            } catch (e: org.json.JSONException) {
                showMessage(ErrorText.of(this@GitChangesActivity, R.string.git_failed, e))
            }
        }
    }

    /** Added lines green, removed red (with a faint background), hunk headers blue, git's own lines dim. */
    private fun colour(diff: String): CharSequence {
        val add = ContextCompat.getColor(this, R.color.diff_add)
        val del = ContextCompat.getColor(this, R.color.diff_del)
        val addBg = ContextCompat.getColor(this, R.color.diff_add_bg)
        val delBg = ContextCompat.getColor(this, R.color.diff_del_bg)
        val hunk = ContextCompat.getColor(this, R.color.accent)
        val meta = ContextCompat.getColor(this, R.color.text_muted)
        val out = SpannableStringBuilder()
        for (line in diff.trimEnd('\n').split('\n')) {
            val start = out.length
            out.append(line).append('\n')
            val end = out.length
            fun span(o: Any) = out.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when (DiffLines.kind(line)) {
                DiffLineKind.ADD -> { span(ForegroundColorSpan(add)); span(BackgroundColorSpan(addBg)) }
                DiffLineKind.DEL -> { span(ForegroundColorSpan(del)); span(BackgroundColorSpan(delBg)) }
                DiffLineKind.HUNK -> span(ForegroundColorSpan(hunk))
                DiffLineKind.META -> span(ForegroundColorSpan(meta))
                DiffLineKind.CONTEXT -> {}
            }
        }
        return out
    }

    private class FileAdapter(private val onClick: (GitFile) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var files: List<GitFile> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<GitFile>) {
            files = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = files.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_git_file, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val f = files[position]
            val v = holder.itemView
            val ctx = v.context
            val status = v.findViewById<TextView>(R.id.status)
            status.text = if (f.isUntracked) "N" else f.status
            status.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when (f.status) {
                        "?", "A" -> R.color.diff_add
                        "D" -> R.color.diff_del
                        "U" -> R.color.attention
                        else -> R.color.accent
                    },
                ),
            )
            v.findViewById<TextView>(R.id.name).text = f.name
            val folder = buildList {
                if (f.folder.isNotEmpty()) add(f.folder)
                f.from?.let { add(ctx.getString(R.string.git_renamed_from, it)) }
                if (f.staged) add(ctx.getString(R.string.git_staged))
            }.joinToString(" · ")
            v.findViewById<TextView>(R.id.folder).apply {
                text = folder
                visibility = if (folder.isEmpty()) View.GONE else View.VISIBLE
            }
            val counts = v.findViewById<TextView>(R.id.counts)
            val sb = SpannableStringBuilder()
            f.added?.let { sb.append("+$it", ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.diff_add)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            f.removed?.let {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append("−$it", ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.diff_del)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            counts.text = sb
            v.setOnClickListener { onClick(f) }
        }
    }

    companion object {
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_UNTRACKED = "untracked"
        private const val MENU_REFRESH = 1

        /** The changes of the repository [dir] is in; [title] (the session's name) goes under the screen title. */
        fun intent(context: Context, dir: String, title: String): Intent =
            Intent(context, GitChangesActivity::class.java).putExtra(EXTRA_DIR, dir).putExtra(EXTRA_TITLE, title)
    }
}
