package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.Crumbs
import com.neststudio.pocketshell.core.FileCategory
import com.neststudio.pocketshell.core.FileEntry
import com.neststudio.pocketshell.core.FileKinds
import com.neststudio.pocketshell.core.FilePreview
import com.neststudio.pocketshell.core.FileSort
import com.neststudio.pocketshell.core.PcPath
import com.neststudio.pocketshell.core.RelativeTime
import com.neststudio.pocketshell.data.UiPrefs
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import java.time.ZoneId
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONException
import java.io.IOException
import java.time.Instant

/**
 * Project file browser: the session folder on the PC and its subfolders (not above it). Breadcrumbs jump to any
 * parent, search filters the folder by name, sort is remembered. Tap a folder to open it, a file to preview it;
 * long-press for the action sheet (view, download, copy path/name). Toolbar: upload a phone file here.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var root: String
    private lateinit var current: String
    private lateinit var adapter: ArrayAdapter<FileEntry>
    private lateinit var crumbsView: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var summaryView: TextView
    private lateinit var prefs: UiPrefs
    private var all: List<FileEntry> = emptyList()
    private var query = ""
    private var sort = FileSort.NAME
    private lateinit var messageView: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private var pendingDownload: FileEntry? = null

    private val pickUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri, overwrite = false)
    }
    private val saveDownload = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = pendingDownload
        pendingDownload = null
        if (uri != null && e != null) FileTransfers.download(this, api, PcPath.child(current, e.name), uri, e.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        WideScreen.limitWidth(findViewById(R.id.list), 840)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val apiClient = (application as PkshellApp).api()
        root = intent.getStringExtra(EXTRA_ROOT).orEmpty()
        if (apiClient == null || root.isEmpty()) {
            Toast.makeText(this, R.string.files_no_folder, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        api = apiClient
        current = savedInstanceState?.getString(STATE_PATH)?.takeIf { PcPath.inside(it, root) } ?: root

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.subtitle = intent.getStringExtra(EXTRA_TITLE)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { goUpOrFinish() }
        prefs = UiPrefs(this)
        sort = FileSort.of(prefs.fileSort)
        val search = SearchView(this).apply {
            queryHint = getString(R.string.files_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String) = true
                override fun onQueryTextChange(q: String): Boolean {
                    this@FileBrowserActivity.query = q.trim()
                    render()
                    return true
                }
            })
        }
        toolbar.menu.add(0, MENU_SEARCH, 0, R.string.files_search).setIcon(R.drawable.ic_search).apply {
            actionView = search
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        }
        toolbar.menu.add(0, MENU_SORT, 1, R.string.files_sort).setIcon(R.drawable.ic_sort)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_UPLOAD, 2, R.string.files_upload).setIcon(R.drawable.ic_upload)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_REFRESH, 3, R.string.refresh)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_SORT -> chooseSort()
                MENU_UPLOAD -> pickUpload.launch(arrayOf("*/*"))
                MENU_REFRESH -> load()
            }
            true
        }

        crumbsView = findViewById(R.id.crumbs)
        crumbScroll = findViewById(R.id.crumbScroll)
        summaryView = findViewById(R.id.summary)
        messageView = findViewById(R.id.message)
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { load() }

        adapter = object : ArrayAdapter<FileEntry>(this, R.layout.item_file, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position) ?: return v
                val icon = v.findViewById<ImageView>(R.id.icon)
                val cat = FileKinds.category(e)
                icon.setImageResource(iconFor(cat))
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (cat == FileCategory.FOLDER) R.color.accent else R.color.text_secondary),
                )
                v.findViewById<TextView>(android.R.id.text1).text = e.name
                v.findViewById<TextView>(android.R.id.text2).text = describe(e)
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemClickListener
            if (e.dir) open(PcPath.child(current, e.name)) else preview(e)
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let { fileActions(it) }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goUpOrFinish()
        })
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::current.isInitialized) outState.putString(STATE_PATH, current)
    }

    private fun describe(e: FileEntry): String {
        val time = runCatching { RelativeTime.format(Instant.parse(e.modified), Instant.now(), ZoneId.systemDefault()) }.getOrDefault("")
        return if (e.dir) time else listOf(FilePreview.humanSize(e.size), time).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun iconFor(c: FileCategory): Int = when (c) {
        FileCategory.FOLDER -> R.drawable.ic_folder
        FileCategory.CODE -> R.drawable.ic_code
        FileCategory.IMAGE -> R.drawable.ic_image
        FileCategory.ARCHIVE -> R.drawable.ic_archive
        FileCategory.MEDIA -> R.drawable.ic_movie
        FileCategory.TEXT, FileCategory.OTHER -> R.drawable.ic_file
    }

    /** Breadcrumbs: root > a > b; every segment except the last jumps there. */
    private fun renderCrumbs() {
        crumbsView.removeAllViews()
        val crumbs = Crumbs.of(current, root)
        val d = resources.displayMetrics.density
        crumbs.forEachIndexed { i, (label, path) ->
            if (i > 0) {
                crumbsView.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_muted))
                }, LinearLayout.LayoutParams((d * 16).toInt(), (d * 16).toInt()))
            }
            val last = i == crumbs.lastIndex
            crumbsView.addView(TextView(this).apply {
                text = label
                textSize = 14f
                maxLines = 1
                setTextColor(ContextCompat.getColor(context, if (last) R.color.text_primary else R.color.text_secondary))
                if (last) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((d * 8).toInt(), 0, (d * 8).toInt(), 0)
                if (!last) {
                    setBackgroundResource(R.drawable.bg_row)
                    setOnClickListener { open(path) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (d * 32).toInt()))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** Applies search + sort to the loaded folder and updates the summary line. */
    private fun render() {
        val shown = sort.apply(all.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) })
        adapter.clear()
        adapter.addAll(shown)
        val dirs = all.count { it.dir }
        summaryView.text = if (query.isEmpty()) getString(R.string.files_summary, dirs, all.size - dirs)
        else getString(R.string.files_summary_filtered, shown.size, all.size)
        if (shown.isEmpty()) {
            messageView.setText(if (all.isEmpty()) R.string.files_empty else R.string.files_no_match)
            messageView.visibility = View.VISIBLE
        } else {
            messageView.visibility = View.GONE
        }
    }

    private fun chooseSort() {
        val modes = FileSort.entries
        val labels = arrayOf(getString(R.string.files_sort_name), getString(R.string.files_sort_modified), getString(R.string.files_sort_size))
        AlertDialog.Builder(this)
            .setTitle(R.string.files_sort)
            .setSingleChoiceItems(labels, modes.indexOf(sort)) { dialog, which ->
                sort = modes[which]
                prefs.fileSort = sort.id
                render()
                dialog.dismiss()
            }
            .show()
    }

    private fun goUpOrFinish() {
        val parent = PcPath.parentWithin(current, root)
        if (parent != null) open(parent) else finish()
    }

    private fun open(path: String) {
        current = path
        load()
    }

    private fun load() {
        renderCrumbs()
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                all = api.listFiles(current).entries
                render()
            } catch (e: ApiException) {
                showMessage(if (e.code == 404 && e.body.isEmpty()) getString(R.string.files_host_old) else e.message.orEmpty())
            } catch (e: IOException) {
                showMessage(getString(R.string.files_load_failed, e.message.orEmpty()))
            } catch (e: JSONException) {
                showMessage(getString(R.string.files_load_failed, e.message.orEmpty()))
            } finally {
                swipe.isRefreshing = false
            }
        }
    }

    private fun showMessage(text: String) {
        all = emptyList()
        summaryView.text = ""
        adapter.clear()
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun preview(e: FileEntry) {
        startActivity(FileViewerActivity.intent(this, PcPath.child(current, e.name), e.name, e.size))
    }

    /** Bottom action sheet for a long-pressed entry. */
    private fun fileActions(e: FileEntry) {
        val sheet = BottomSheetDialog(this)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (d * 8).toInt(), 0, (d * 16).toInt())
        }
        box.addView(TextView(this).apply {
            text = e.name
            textSize = 16f
            maxLines = 2
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((d * 24).toInt(), (d * 12).toInt(), (d * 24).toInt(), (d * 4).toInt())
        })
        box.addView(TextView(this).apply {
            text = describe(e)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setPadding((d * 24).toInt(), 0, (d * 24).toInt(), (d * 12).toInt())
        })
        fun action(icon: Int, label: Int, run: () -> Unit) {
            val row = layoutInflater.inflate(R.layout.item_sheet_action, box, false)
            row.findViewById<ImageView>(R.id.actionIcon).setImageResource(icon)
            row.findViewById<TextView>(R.id.actionLabel).setText(label)
            row.setOnClickListener { sheet.dismiss(); run() }
            box.addView(row)
        }
        val path = PcPath.child(current, e.name)
        if (e.dir) {
            action(R.drawable.ic_folder_open, R.string.files_open) { open(path) }
        } else {
            action(R.drawable.ic_visibility, R.string.files_view) { preview(e) }
            action(R.drawable.ic_download, R.string.files_download) { startDownload(e) }
        }
        action(R.drawable.ic_copy, R.string.files_copy_path) { copy(path) }
        action(R.drawable.ic_copy, R.string.files_copy_name) { copy(e.name) }
        sheet.setContentView(box)
        sheet.show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("path", text))
        Toast.makeText(this, R.string.files_copied, Toast.LENGTH_SHORT).show()
    }

    private fun startDownload(e: FileEntry) {
        pendingDownload = e
        saveDownload.launch(e.name)
    }

    private fun upload(uri: Uri, overwrite: Boolean) {
        val (name, size) = queryNameAndSize(uri)
        if (name == null) {
            Toast.makeText(this, R.string.files_upload_no_name, Toast.LENGTH_LONG).show()
            return
        }
        val dir = current
        Toast.makeText(this, getString(R.string.files_uploading, name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                api.upload(dir, name, uriBody(uri, size), overwrite)
                Toast.makeText(this@FileBrowserActivity, getString(R.string.files_uploaded, name), Toast.LENGTH_SHORT).show()
                if (dir == current) load()
            } catch (e: ApiException) {
                if (e.code == 409 && !overwrite) {
                    AlertDialog.Builder(this@FileBrowserActivity)
                        .setMessage(getString(R.string.files_overwrite_confirm, name))
                        .setPositiveButton(R.string.files_overwrite) { _, _ -> upload(uri, overwrite = true) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    showError(getString(R.string.files_upload_failed), e.message.orEmpty())
                }
            } catch (e: IOException) {
                showError(getString(R.string.files_upload_failed), "${e.javaClass.simpleName}: ${e.message}")
            } catch (e: SecurityException) {
                showError(getString(R.string.files_upload_failed), e.message.orEmpty())
            }
        }
    }

    /** Streams the picked document; reopened on each write so a retry on another host address works. */
    private fun uriBody(uri: Uri, size: Long): RequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다")
            input.source().use { sink.writeAll(it) }
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else -1L
                return name to size
            }
        }
        return uri.lastPathSegment to -1L
    }

    private fun showError(title: String, reason: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(getString(R.string.retry_connect_hint) + ErrorText.cause(this, reason))
            .setPositiveButton(R.string.confirm, null).show()
    }

    companion object {
        private const val EXTRA_ROOT = "root"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PATH = "path"
        private const val MENU_UPLOAD = 1
        private const val MENU_REFRESH = 2
        private const val MENU_SEARCH = 3
        private const val MENU_SORT = 4

        fun intent(context: Context, root: String, title: String): Intent =
            Intent(context, FileBrowserActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_TITLE, title)
    }
}
