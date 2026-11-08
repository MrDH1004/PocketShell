package com.mpta.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
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
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.FileEntry
import com.mpta.client.core.FilePreview
import com.mpta.client.core.PcPath
import com.mpta.client.net.ApiClient
import com.mpta.client.net.ApiException
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONException
import java.io.IOException
import java.time.Instant

/**
 * Project file browser: the session folder on the PC and its subfolders (not above it). Tap a folder to open it,
 * a file to preview it; long-press a file to download it to the phone. Toolbar: upload a phone file here.
 */
class FileBrowserActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var root: String
    private lateinit var current: String
    private lateinit var adapter: ArrayAdapter<FileEntry>
    private lateinit var pathView: TextView
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
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)

        val apiClient = (application as MptaApp).api()
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
        toolbar.menu.add(0, MENU_UPLOAD, 0, R.string.files_upload).setIcon(R.drawable.ic_upload)
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, MENU_REFRESH, 1, R.string.refresh)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_UPLOAD -> pickUpload.launch(arrayOf("*/*"))
                MENU_REFRESH -> load()
            }
            true
        }

        pathView = findViewById(R.id.path)
        messageView = findViewById(R.id.message)
        swipe = findViewById(R.id.swipe)
        swipe.setOnRefreshListener { load() }

        adapter = object : ArrayAdapter<FileEntry>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position) ?: return v
                v.findViewById<TextView>(android.R.id.text1).text = if (e.dir) "${e.name}/" else e.name
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
            val e = adapter.getItem(pos)
            if (e != null && !e.dir) fileActions(e)
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
        val time = runCatching {
            DateFormat.format("yyyy-MM-dd HH:mm", Instant.parse(e.modified).toEpochMilli()).toString()
        }.getOrDefault("")
        return if (e.dir) time else "${FilePreview.humanSize(e.size)} · $time"
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
        val rel = PcPath.relative(current, root)
        pathView.text = if (rel.isEmpty()) root else "$root\n› $rel"
        swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val listing = api.listFiles(current)
                adapter.clear()
                adapter.addAll(listing.entries)
                messageView.visibility = if (listing.entries.isEmpty()) View.VISIBLE else View.GONE
                messageView.setText(R.string.files_empty)
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
        adapter.clear()
        messageView.text = text
        messageView.visibility = View.VISIBLE
    }

    private fun preview(e: FileEntry) {
        startActivity(FileViewerActivity.intent(this, PcPath.child(current, e.name), e.name, e.size))
    }

    private fun fileActions(e: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(arrayOf(getString(R.string.files_view), getString(R.string.files_download))) { _, which ->
                if (which == 0) preview(e) else startDownload(e)
            }
            .show()
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
        AlertDialog.Builder(this).setTitle(title).setMessage(reason).setPositiveButton(R.string.confirm, null).show()
    }

    companion object {
        private const val EXTRA_ROOT = "root"
        private const val EXTRA_TITLE = "title"
        private const val STATE_PATH = "path"
        private const val MENU_UPLOAD = 1
        private const val MENU_REFRESH = 2

        fun intent(context: Context, root: String, title: String): Intent =
            Intent(context, FileBrowserActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_TITLE, title)
    }
}
