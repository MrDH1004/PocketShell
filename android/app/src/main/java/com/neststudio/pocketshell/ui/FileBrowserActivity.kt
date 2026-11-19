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
import com.neststudio.pocketshell.core.FileClipboard
import com.neststudio.pocketshell.core.FileKinds
import com.neststudio.pocketshell.core.FileOpResult
import com.neststudio.pocketshell.core.FilePreview
import com.neststudio.pocketshell.core.FolderHistory
import com.neststudio.pocketshell.core.FileSort
import com.neststudio.pocketshell.core.PcPath
import com.neststudio.pocketshell.core.ProjectAgent
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
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONException
import java.io.IOException
import java.time.Instant

/**
 * Project file browser: the session folder on the PC and its subfolders (not above it). Breadcrumbs jump to any
 * parent, back / forward go through visited folders, search filters the folder by name, sort is remembered. Tap a
 * folder to open it, a file to preview it; long-press for the command sheet (open, new session here, view, download,
 * rename, copy, cut, delete, select, copy path/name). Toolbar: upload a phone file here.
 *
 * PC mode ([pcIntent]): starts at "내 PC" (well-known folders and drives, path "") and may go anywhere; a folder can
 * be opened as a new session there (terminal or an AI CLI), handed back to the caller as the result.
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
    private var pcMode = false
    /** PC mode at "내 PC": the place behind each shown name. */
    private var placePaths: Map<String, String> = emptyMap()
    private var agents: List<ProjectAgent>? = null
    private lateinit var toolbar: MaterialToolbar
    /** Names selected in the current folder (selection mode while not empty). */
    private val selected = linkedSetOf<String>()
    private var pcId = ""
    private var baseTitle: CharSequence? = null
    private lateinit var pasteBar: View
    private lateinit var pasteText: TextView
    private val history = FolderHistory()
    private lateinit var navBack: View
    private lateinit var navForward: View
    private lateinit var navUp: View

    private val atTop get() = pcMode && current.isEmpty()
    private val selecting get() = selected.isNotEmpty()

    private val pickUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) upload(uri, overwrite = false)
    }
    private val saveDownload = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = pendingDownload
        pendingDownload = null
        if (uri != null && e != null) FileTransfers.download(this, api, PcPath.child(current, e.name), uri, e.name)
    }
    /** Several files or a folder on Android 8–9: the user picks a folder once and everything is saved into it. */
    private var pendingDownloads: List<FileTransfers.Item> = emptyList()
    private val saveDownloadsTo = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val items = pendingDownloads
        pendingDownloads = emptyList()
        if (tree != null && items.isNotEmpty()) FileTransfers.downloadAll(this, api, items, tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        WideScreen.limitWidth(findViewById(R.id.list), 840)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val apiClient = (application as PkshellApp).api()
        pcMode = intent.getBooleanExtra(EXTRA_PC, false)
        root = if (pcMode) "" else intent.getStringExtra(EXTRA_ROOT).orEmpty()
        if (apiClient == null || (!pcMode && root.isEmpty())) {
            Toast.makeText(this, R.string.files_no_folder, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        api = apiClient
        current = savedInstanceState?.getString(STATE_PATH)?.takeIf { pcMode || PcPath.inside(it, root) } ?: root
        history.restore(savedInstanceState?.getStringArrayList(STATE_BACK), savedInstanceState?.getStringArrayList(STATE_FORWARD))

        toolbar = findViewById(R.id.toolbar)
        toolbar.subtitle = intent.getStringExtra(EXTRA_TITLE)
        if (pcMode) toolbar.setTitle(R.string.pc_browser)
        baseTitle = toolbar.title
        pcId = (application as PkshellApp).pairing()?.id.orEmpty()
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { if (selecting) clearSelection() else goUpOrFinish() }
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
        toolbar.menu.add(0, MENU_NEW_FOLDER, 3, R.string.files_new_folder).setIcon(R.drawable.ic_new_folder)
        toolbar.menu.add(0, MENU_NEW_FILE, 3, R.string.files_new_file).setIcon(R.drawable.ic_add)
        toolbar.menu.add(0, MENU_REFRESH, 4, R.string.refresh)
        if (pcMode) {
            toolbar.menu.add(0, MENU_OPEN_HERE, 0, R.string.pc_open_here).setIcon(R.drawable.ic_new_terminal_here)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // Selection mode (long-press an entry): Explorer basics on the selected items.
        fun selectAction(id: Int, label: Int, icon: Int, always: Boolean) =
            toolbar.menu.add(1, id, 10 + id, label).setIcon(icon)
                .setShowAsAction(if (always) android.view.MenuItem.SHOW_AS_ACTION_ALWAYS else android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(1, MENU_DOWNLOAD, 16, R.string.files_download).setIcon(R.drawable.ic_download)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        selectAction(MENU_COPY, R.string.files_copy, R.drawable.ic_copy, true)
        selectAction(MENU_CUT, R.string.files_cut, R.drawable.ic_cut, true)
        selectAction(MENU_DELETE, R.string.files_delete, R.drawable.ic_delete, true)
        selectAction(MENU_RENAME, R.string.files_rename, R.drawable.ic_edit, false)
        selectAction(MENU_MORE, R.string.files_more, R.drawable.ic_more_vert, false)
        selectAction(MENU_SELECT_ALL, R.string.files_select_all, R.drawable.ic_check, false)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_OPEN_HERE -> chooseLaunch(current)
                MENU_SORT -> chooseSort()
                MENU_UPLOAD -> pickUpload.launch(arrayOf("*/*"))
                MENU_NEW_FOLDER -> newFolder()
                MENU_NEW_FILE -> newFile()
                MENU_REFRESH -> load()
                MENU_DOWNLOAD -> downloadSelected()
                MENU_COPY -> toClipboard(cut = false)
                MENU_CUT -> toClipboard(cut = true)
                MENU_DELETE -> confirmDelete()
                MENU_RENAME -> selectedEntries().singleOrNull()?.let { rename(it) }
                MENU_MORE -> selectedEntries().singleOrNull()?.let { e -> clearSelection(); fileActions(e) }
                MENU_SELECT_ALL -> {
                    for (i in 0 until adapter.count) adapter.getItem(i)?.let { e -> selected.add(e.name) }
                    updateMode()
                }
            }
            true
        }
        pasteBar = findViewById(R.id.pasteBar)
        pasteText = findViewById(R.id.pasteText)
        findViewById<View>(R.id.pasteCancel).setOnClickListener {
            FileClipboard.clear()
            updatePasteBar()
        }
        findViewById<View>(R.id.pasteHere).setOnClickListener { paste() }

        navBack = findViewById(R.id.navBack)
        navForward = findViewById(R.id.navForward)
        navUp = findViewById(R.id.navUp)
        navBack.setOnClickListener { history.back(current)?.let { go(it) } }
        navForward.setOnClickListener { history.forward(current)?.let { go(it) } }
        navUp.setOnClickListener { parentOf(current)?.let { open(it) } }
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
                val on = e.name in selected
                icon.setImageResource(if (on) R.drawable.ic_check else iconFor(cat))
                icon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, if (on || cat == FileCategory.FOLDER) R.color.accent else R.color.text_secondary),
                )
                v.setBackgroundResource(if (on) R.drawable.bg_row_selected else R.drawable.bg_row)
                v.findViewById<TextView>(android.R.id.text1).text = e.name
                v.findViewById<TextView>(android.R.id.text2).text = describe(e)
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemClickListener
            when {
                selecting -> toggle(e)
                e.dir -> open(pathOf(e))
                else -> preview(e)
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemLongClickListener true
            if (selecting) toggle(e) else fileActions(e)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val previous = if (selecting) null else history.back(current)
                when {
                    selecting -> clearSelection()
                    previous != null -> go(previous)
                    else -> goUpOrFinish()
                }
            }
        })
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::pasteBar.isInitialized) updatePasteBar() // copied or cut in another browser screen
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::current.isInitialized) outState.putString(STATE_PATH, current)
        val (b, f) = history.save()
        outState.putStringArrayList(STATE_BACK, b)
        outState.putStringArrayList(STATE_FORWARD, f)
    }

    /** Full PC path of a shown entry (at "내 PC" the place's own path). */
    private fun pathOf(e: FileEntry): String = if (atTop) placePaths[e.name] ?: e.name else PcPath.child(current, e.name)

    private fun describe(e: FileEntry): String {
        if (atTop) return placePaths[e.name].orEmpty()
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
        val crumbs = if (pcMode) Crumbs.ofPc(current, getString(R.string.pc_browser_top)) else Crumbs.of(current, root)
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
        val matching = all.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        val shown = if (atTop) matching else sort.apply(matching) // places keep the host's order (folders, then drives)
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

    /** Folder above [path] this browser may show, or null at its top. */
    private fun parentOf(path: String): String? =
        if (pcMode) path.takeIf { it.isNotEmpty() }?.let { PcPath.parentOrTop(it) } else PcPath.parentWithin(path, root)

    private fun goUpOrFinish() {
        val parent = parentOf(current)
        if (parent != null) open(parent) else finish()
    }

    /** Opens [path] as a new step: back returns here. */
    private fun open(path: String) {
        history.visit(current, path)
        go(path)
    }

    /** Shows [path] without touching the history (back / forward). */
    private fun go(path: String) {
        current = path
        selected.clear()
        // A name filter is for one folder: going elsewhere with it would hide the new folder's items.
        toolbar.menu.findItem(MENU_SEARCH)?.collapseActionView()
        query = ""
        load()
    }

    private fun updateNav() {
        fun View.on(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.35f }
        navBack.on(history.canBack)
        navForward.on(history.canForward)
        navUp.on(parentOf(current) != null)
    }

    private fun load() {
        renderCrumbs()
        updateNav()
        updateMode()
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                if (atTop) {
                    val places = api.places()
                    placePaths = places.associate { it.name to it.path }
                    all = places.map { FileEntry(it.name, true, 0, "") }
                } else {
                    all = api.listFiles(current).entries
                }
                render()
            } catch (e: ApiException) {
                val old = if (atTop) R.string.pc_host_old else R.string.files_host_old
                showMessage(if (e.code == 404 && e.body.isEmpty()) getString(old) else e.message.orEmpty())
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
        val path = pathOf(e)
        if (e.dir) {
            action(R.drawable.ic_folder_open, R.string.files_open) { open(path) }
            if (pcMode) action(R.drawable.ic_new_terminal_here, R.string.pc_open_here) { chooseLaunch(path) }
        } else {
            action(R.drawable.ic_visibility, R.string.files_view) { preview(e) }
            action(R.drawable.ic_download, R.string.files_download) { startDownload(e) }
        }
        if (e.dir) {
            // Add inside the pressed folder (it is opened, then the name is asked).
            action(R.drawable.ic_new_folder, R.string.files_new_folder_in) { open(path); newFolder() }
            action(R.drawable.ic_add, R.string.files_new_file_in) { open(path); newFile() }
            if (canPaste()) action(R.drawable.ic_paste, R.string.files_paste_into) { paste(path) }
        }
        if (!atTop) { // "내 PC" places are not files to rename, copy or delete
            if (e.dir) action(R.drawable.ic_download, R.string.files_download) { selectOnly(e); downloadSelected() }
            action(R.drawable.ic_edit, R.string.files_rename) { rename(e) }
            action(R.drawable.ic_copy, R.string.files_duplicate) { duplicate(e) }
            action(R.drawable.ic_copy, R.string.files_copy) { selectOnly(e); toClipboard(cut = false) }
            action(R.drawable.ic_cut, R.string.files_cut) { selectOnly(e); toClipboard(cut = true) }
            action(R.drawable.ic_delete, R.string.files_delete) { selectOnly(e); confirmDelete() }
            action(R.drawable.ic_check, R.string.files_select) { toggle(e) }
            action(R.drawable.ic_file, R.string.files_more) { details(e) }
        }
        action(R.drawable.ic_copy, R.string.files_copy_path) { copy(path) }
        action(R.drawable.ic_copy, R.string.files_copy_name) { copy(e.name) }
        // Many commands: the sheet scrolls instead of cutting the last ones off on a short screen.
        sheet.setContentView(androidx.core.widget.NestedScrollView(this).apply { addView(box) })
        sheet.show()
    }

    private fun selectOnly(e: FileEntry) {
        selected.clear()
        selected.add(e.name)
    }

    private fun toggle(e: FileEntry) {
        if (!selected.remove(e.name)) selected.add(e.name)
        updateMode()
    }

    private fun clearSelection() {
        selected.clear()
        updateMode()
    }

    private fun selectedEntries(): List<FileEntry> = all.filter { it.name in selected }

    private fun selectedPaths(): List<String> = selected.map { PcPath.child(current, it) }

    /** Toolbar for the mode (browsing or selection), the selection marks and the paste bar. */
    private fun updateMode() {
        val n = selected.size
        val menu = toolbar.menu
        menu.setGroupVisible(1, selecting)
        menu.findItem(MENU_RENAME)?.isVisible = n == 1
        menu.findItem(MENU_MORE)?.isVisible = n == 1
        menu.findItem(MENU_SEARCH)?.let { it.isVisible = !selecting || it.isActionViewExpanded }
        menu.findItem(MENU_SORT)?.isVisible = !selecting
        menu.findItem(MENU_REFRESH)?.isVisible = !selecting
        menu.findItem(MENU_UPLOAD)?.isVisible = !selecting && !atTop
        menu.findItem(MENU_NEW_FOLDER)?.isVisible = !selecting && !atTop
        menu.findItem(MENU_NEW_FILE)?.isVisible = !selecting && !atTop
        menu.findItem(MENU_OPEN_HERE)?.isVisible = !selecting && !atTop
        toolbar.title = if (selecting) getString(R.string.files_selected, n) else baseTitle
        toolbar.setNavigationIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_arrow_back)
        toolbar.navigationContentDescription = getString(if (selecting) R.string.files_select_done else R.string.back)
        adapter.notifyDataSetChanged()
        updatePasteBar()
    }

    private fun updatePasteBar() {
        val show = !FileClipboard.isEmpty && FileClipboard.pcId == pcId && !atTop && !selecting
        pasteBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val n = FileClipboard.paths.size
            pasteText.text = getString(if (FileClipboard.cut) R.string.files_clip_cut else R.string.files_clip_copied, n)
        }
    }

    private fun toClipboard(cut: Boolean) {
        FileClipboard.put(pcId, selectedPaths(), cut)
        clearSelection()
    }

    private fun canPaste() = !FileClipboard.isEmpty && FileClipboard.pcId == pcId && !atTop

    /** Pastes the copied / cut items into [dest] (default: this folder). */
    private fun paste(dest: String = current) {
        val clip = FileClipboard
        if (clip.cut && clip.allIn(dest)) {
            Toast.makeText(this, R.string.files_clip_same_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val paths = clip.paths
        val cut = clip.cut
        Toast.makeText(this, R.string.files_pasting, Toast.LENGTH_SHORT).show()
        runOp({ api.fileOp(if (cut) "move" else "copy", paths, dest = dest) }) { r ->
            if (cut) FileClipboard.clear() // moved: the old paths are gone (a copy can be pasted again, as in Explorer)
            getString(R.string.files_pasted, r.done)
        }
    }

    private fun confirmDelete() {
        val paths = selectedPaths()
        if (paths.isEmpty()) return
        val message = if (paths.size == 1) getString(R.string.files_delete_one, selected.first())
        else getString(R.string.files_delete_many, paths.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.files_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.files_delete_confirm) { _, _ ->
                clearSelection()
                runOp({ api.fileOp("delete", paths) }) { r -> getString(R.string.files_deleted, r.done) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rename(e: FileEntry) {
        askName(R.string.files_rename, e.name, selectStem = !e.dir) { name ->
            if (name == e.name) return@askName
            clearSelection()
            runOp({ api.fileOp("rename", listOf(PcPath.child(current, e.name)), name = name) }) { null }
        }
    }

    private fun newFolder() {
        val dir = current
        askName(R.string.files_new_folder, getString(R.string.files_new_folder), selectStem = false) { name ->
            runOp({ api.fileOp("mkdir", emptyList(), dest = dir, name = name) }) { null }
        }
    }

    /** An empty file in this folder (an empty upload: an existing name is refused, not overwritten). */
    private fun newFile() {
        val dir = current
        askName(R.string.files_new_file, getString(R.string.files_new_file_default), selectStem = true) { name ->
            runOp({
                try {
                    api.upload(dir, name, ByteArray(0).toRequestBody(null), overwrite = false)
                    FileOpResult(1, listOf(PcPath.child(dir, name)), emptyList())
                } catch (e: ApiException) {
                    if (e.code != 409) throw e
                    FileOpResult(0, emptyList(), listOf(getString(R.string.files_exists, name)))
                }
            }) { getString(R.string.files_created, name) }
        }
    }

    /** A copy next to the original ("이름 - 복사본"; the host picks a free name). */
    private fun duplicate(e: FileEntry) {
        val dir = current
        runOp({ api.fileOp("copy", listOf(PcPath.child(dir, e.name)), dest = dir) }) { getString(R.string.files_duplicated, e.name) }
    }

    private fun details(e: FileEntry) {
        val path = pathOf(e)
        val modified = runCatching {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(e.modified))
        }.getOrDefault(e.modified)
        val lines = buildList {
            add(getString(R.string.files_detail_kind, getString(if (e.dir) R.string.files_kind_folder else R.string.files_kind_file)))
            if (!e.dir) add(getString(R.string.files_detail_size, FilePreview.humanSize(e.size), e.size))
            if (modified.isNotEmpty()) add(getString(R.string.files_detail_modified, modified))
            add(getString(R.string.files_detail_path, path))
        }
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(lines.joinToString(NL))
            .setPositiveButton(R.string.confirm, null)
            .setNeutralButton(R.string.files_copy_path) { _, _ -> copy(path) }
            .show()
    }

    /** Name dialog; [selectStem] selects the name without its extension (renaming a file). */
    private fun askName(title: Int, initial: String, selectStem: Boolean, done: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            setText(initial)
            hint = getString(R.string.files_name_hint)
            isSingleLine = true
            val dot = initial.lastIndexOf('.')
            if (selectStem && dot > 0) setSelection(0, dot) else selectAll()
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((d * 24).toInt(), (d * 8).toInt(), (d * 24).toInt(), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) done(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /**
     * Runs a file operation, then reloads the folder. [success] gives the toast text (null = none); items that
     * failed are listed in a dialog.
     */
    private fun runOp(block: suspend () -> FileOpResult, success: (FileOpResult) -> String?) {
        val dir = current
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val r = block()
                success(r)?.let { if (r.done > 0) Toast.makeText(this@FileBrowserActivity, it, Toast.LENGTH_SHORT).show() }
                if (r.errors.isNotEmpty() && !isFinishing) {
                    AlertDialog.Builder(this@FileBrowserActivity)
                        .setTitle(R.string.files_op_failed)
                        .setMessage(r.errors.take(20).joinToString("\n"))
                        .setPositiveButton(R.string.confirm, null)
                        .show()
                }
            } catch (e: ApiException) {
                val text = if (e.code == 404 && e.body.isEmpty()) getString(R.string.files_op_host_old) else e.message.orEmpty()
                opFailed(text)
            } catch (e: IOException) {
                opFailed(getString(R.string.retry_connect_hint) + ErrorText.cause(this@FileBrowserActivity, "${e.javaClass.simpleName}: ${e.message}"))
            } catch (e: JSONException) {
                opFailed(e.message.orEmpty())
            } finally {
                swipe.isRefreshing = false
                if (dir == current) load()
            }
        }
    }

    private fun opFailed(text: String) {
        if (isFinishing) return
        AlertDialog.Builder(this).setTitle(R.string.files_op_failed).setMessage(text).setPositiveButton(R.string.confirm, null).show()
    }

    /** PC mode: 터미널 or an AI CLI found on the PC, then back to the session list, which opens it. */
    private fun chooseLaunch(path: String) {
        if (path.isEmpty()) return
        lifecycleScope.launch {
            val found = agents ?: try {
                api.projectOptions().agents
            } catch (e: ApiException) {
                emptyList()
            } catch (e: IOException) {
                emptyList()
            } catch (e: JSONException) {
                emptyList()
            }.also { agents = it }
            if (isFinishing) return@launch
            val labels = (listOf(getString(R.string.pc_open_terminal)) + found.map { it.name }).toTypedArray()
            AlertDialog.Builder(this@FileBrowserActivity)
                .setTitle(getString(R.string.pc_open_title, PcPath.name(path)))
                .setItems(labels) { _, which ->
                    setResult(RESULT_OK, Intent().putExtra(RESULT_PATH, path).putExtra(RESULT_AGENT, found.getOrNull(which - 1)?.id))
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("path", text))
        Toast.makeText(this, R.string.files_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Download button in selection mode: one file asks where to save it; several files or folders go to the Download
     * folder, a folder with everything under it (structure kept). With a folder, the count and size are confirmed first.
     */
    private fun downloadSelected() {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) return
        clearSelection()
        if (chosen.size == 1 && !chosen[0].dir) {
            startDownload(chosen[0])
            return
        }
        val base = current
        val hasDir = chosen.any { it.dir }
        lifecycleScope.launch {
            if (hasDir) Toast.makeText(this@FileBrowserActivity, R.string.files_download_scanning, Toast.LENGTH_SHORT).show()
            val items = try {
                FileTransfers.plan(api, base, chosen)
            } catch (e: FileTransfers.TooMany) {
                if (!isFinishing) AlertDialog.Builder(this@FileBrowserActivity).setTitle(R.string.files_download)
                    .setMessage(getString(R.string.files_download_too_many, FileTransfers.MAX_FILES))
                    .setPositiveButton(R.string.confirm, null).show()
                return@launch
            } catch (e: IOException) {
                showError(getString(R.string.files_download_failed), "${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            if (isFinishing) return@launch
            if (items.isEmpty()) {
                Toast.makeText(this@FileBrowserActivity, R.string.files_download_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val start = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    FileTransfers.downloadAll(this@FileBrowserActivity, api, items, null)
                } else {
                    pendingDownloads = items
                    saveDownloadsTo.launch(null)
                }
            }
            if (!hasDir) start()
            else AlertDialog.Builder(this@FileBrowserActivity)
                .setTitle(R.string.files_download)
                .setMessage(getString(R.string.files_download_confirm, items.size, FilePreview.humanSize(items.sumOf { it.size })))
                .setPositiveButton(R.string.files_download) { _, _ -> start() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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
        private const val STATE_BACK = "back"
        private const val STATE_FORWARD = "forward"
        private const val MENU_UPLOAD = 1
        private const val MENU_REFRESH = 2
        private const val MENU_SEARCH = 3
        private const val MENU_SORT = 4
        private const val MENU_OPEN_HERE = 5
        private const val MENU_NEW_FOLDER = 6
        private const val MENU_COPY = 7
        private const val MENU_CUT = 8
        private const val MENU_DELETE = 9
        private const val MENU_RENAME = 10
        private const val MENU_MORE = 11
        private const val MENU_SELECT_ALL = 12
        private const val MENU_DOWNLOAD = 13
        private const val MENU_NEW_FILE = 14
        private const val NL = "\n"
        private const val EXTRA_PC = "pc"
        const val RESULT_PATH = "path"
        const val RESULT_AGENT = "agent"

        fun intent(context: Context, root: String, title: String): Intent =
            Intent(context, FileBrowserActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_TITLE, title)

        /** The whole PC from "내 PC"; the result carries [RESULT_PATH] and [RESULT_AGENT] (null = terminal). */
        fun pcIntent(context: Context, title: String): Intent =
            Intent(context, FileBrowserActivity::class.java).putExtra(EXTRA_PC, true).putExtra(EXTRA_TITLE, title)
    }
}
