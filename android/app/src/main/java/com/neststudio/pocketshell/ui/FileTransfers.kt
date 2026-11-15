package com.neststudio.pocketshell.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.net.ApiClient
import kotlinx.coroutines.launch
import java.io.IOException

/** PC file → a document the user picked (CreateDocument), so no storage permission is needed. */
object FileTransfers {
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

    /**
     * Several PC files at once: into the phone's Download folder (Android 10+, no picker, no permission), or into the
     * folder the user picked ([tree], OpenDocumentTree) on older phones. A name already there gets " (1)".
     */
    fun downloadAll(activity: AppCompatActivity, api: ApiClient, files: List<Pair<String, String>>, tree: Uri?) {
        Toast.makeText(activity, activity.getString(R.string.files_downloading_many, files.size), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val failed = mutableListOf<String>()
            val resolver = activity.contentResolver
            val parent = tree?.let { DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) }
            for ((pcPath, name) in files) {
                var doc: Uri? = null
                try {
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                        ?: "application/octet-stream"
                    doc = if (parent != null) DocumentsContract.createDocument(resolver, parent, mime, name)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    ) else null
                    if (doc == null) throw IOException("저장할 파일을 만들 수 없습니다")
                    val out = resolver.openOutputStream(doc, "wt") ?: throw IOException("저장할 파일을 열 수 없습니다")
                    out.use { api.download(pcPath, it) }
                    if (parent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(doc, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    }
                    doc = null
                } catch (e: IOException) {
                    failed += "$name: ${e.javaClass.simpleName}: ${e.message}"
                } catch (e: SecurityException) {
                    failed += "$name: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    failed += "$name: ${e.message}"
                } finally {
                    // A half-written file is not left behind.
                    doc?.let { runCatching { if (parent != null) DocumentsContract.deleteDocument(resolver, it) else resolver.delete(it, null, null) } }
                }
            }
            if (failed.isEmpty()) {
                val where = if (parent == null) R.string.files_downloaded_many_downloads else R.string.files_downloaded_many
                Toast.makeText(activity, activity.getString(where, files.size), Toast.LENGTH_LONG).show()
            } else fail(activity, failed.joinToString("\n"))
        }
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
