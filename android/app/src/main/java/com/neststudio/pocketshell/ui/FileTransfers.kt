package com.neststudio.pocketshell.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.FileEntry
import com.neststudio.pocketshell.core.PcPath
import com.neststudio.pocketshell.net.ApiClient
import kotlinx.coroutines.launch
import java.io.IOException

/** PC file → a document the user picked (CreateDocument), so no storage permission is needed. */
object FileTransfers {
    const val MAX_FILES = 5000
    private const val MAX_DEPTH = 32

    fun download(activity: AppCompatActivity, api: ApiClient, pcPath: String, target: Uri, name: String) {
        Toast.makeText(activity, activity.getString(R.string.files_downloading, name), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val out = activity.contentResolver.openOutputStream(target, "wt") ?: throw IOException("저장할 파일을 열 수 없습니다")
                out.use { api.download(pcPath, it) }
                Toast.makeText(activity, activity.getString(R.string.files_downloaded, name), Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                fail(activity, "${e.javaClass.simpleName}: ${e.message}")
            } catch (e: SecurityException) {
                fail(activity, e.message.orEmpty())
            }
        }
    }

    /** One file to save: [dir] is its folder under the target ("" = the target itself, "/" between levels). */
    data class Item(val pcPath: String, val dir: String, val name: String, val size: Long)

    class TooMany : Exception()

    /**
     * The files to save for the selected [entries] of [base]: a folder brings everything under it, keeping its
     * structure. More than [limit] files throws [TooMany], so a selected node_modules is not fetched by accident.
     */
    suspend fun plan(api: ApiClient, base: String, entries: List<FileEntry>, limit: Int = MAX_FILES): List<Item> {
        val out = mutableListOf<Item>()
        suspend fun walk(pcDir: String, dir: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (e in api.listFiles(pcDir).entries) {
                val path = PcPath.child(pcDir, e.name)
                if (e.dir) walk(path, "$dir/${e.name}", depth + 1)
                else {
                    out += Item(path, dir, e.name, e.size)
                    if (out.size > limit) throw TooMany()
                }
            }
        }
        for (e in entries) {
            val path = PcPath.child(base, e.name)
            if (e.dir) walk(path, e.name, 1) else out += Item(path, "", e.name, e.size)
            if (out.size > limit) throw TooMany()
        }
        return out
    }

    /**
     * Saves [items] into the phone's Download folder (Android 10+, no picker, no permission), or into the folder the
     * user picked ([tree], OpenDocumentTree) on older phones, with their folders. A name already there gets " (1)".
     * A dialog shows which file of how many and can cancel.
     */
    fun downloadAll(activity: AppCompatActivity, api: ApiClient, items: List<Item>, tree: Uri?) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.files_download)
            .setMessage(activity.getString(R.string.files_downloading_many, items.size))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        val job = activity.lifecycleScope.launch {
            val failed = mutableListOf<String>()
            val resolver = activity.contentResolver
            val root = tree?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
            val dirs = HashMap<String, Uri>()
            fun treeDir(dir: String): Uri? {
                if (root == null || dir.isEmpty()) return root
                dirs[dir]?.let { return it }
                val parent = treeDir(dir.substringBeforeLast('/', "")) ?: return null
                val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, dir.substringAfterLast('/'))
                if (made != null) dirs[dir] = made
                return made
            }
            for ((i, item) in items.withIndex()) {
                progress.setMessage(activity.getString(R.string.files_download_progress, i + 1, items.size, item.name))
                var doc: Uri? = null
                try {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase())
                        ?: "application/octet-stream"
                    doc = if (root != null) {
                        treeDir(item.dir)?.let { DocumentsContract.createDocument(resolver, it, mime, item.name) }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                val sub = if (item.dir.isEmpty()) "" else "/" + item.dir
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + sub)
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            },
                        )
                    } else null
                    if (doc == null) throw IOException("저장할 파일을 만들 수 없습니다")
                    val out = resolver.openOutputStream(doc, "wt") ?: throw IOException("저장할 파일을 열 수 없습니다")
                    out.use { api.download(item.pcPath, it) }
                    if (root == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(doc, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    }
                    doc = null
                } catch (e: IOException) {
                    failed += "${item.name}: ${e.javaClass.simpleName}: ${e.message}"
                } catch (e: SecurityException) {
                    failed += "${item.name}: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    failed += "${item.name}: ${e.message}"
                } catch (e: IllegalStateException) {
                    failed += "${item.name}: ${e.message}"
                } finally {
                    // A half-written file (failed or cancelled) is not left behind.
                    doc?.let { d -> runCatching { if (root != null) DocumentsContract.deleteDocument(resolver, d) else resolver.delete(d, null, null) } }
                }
            }
            progress.dismiss()
            if (failed.isEmpty()) {
                val where = if (root == null) R.string.files_downloaded_many_downloads else R.string.files_downloaded_many
                Toast.makeText(activity, activity.getString(where, items.size), Toast.LENGTH_LONG).show()
            } else {
                val more = if (failed.size > 10) "\n… (${failed.size})" else ""
                fail(activity, failed.take(10).joinToString("\n") + more)
            }
        }
        progress.setOnShowListener {
            progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                job.cancel()
                progress.dismiss()
                Toast.makeText(activity, R.string.files_download_cancelled, Toast.LENGTH_SHORT).show()
            }
        }
        progress.show()
    }

    private fun fail(activity: AppCompatActivity, reason: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.files_download_failed)
            .setMessage(activity.getString(R.string.retry_connect_hint) + ErrorText.cause(activity, reason))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
