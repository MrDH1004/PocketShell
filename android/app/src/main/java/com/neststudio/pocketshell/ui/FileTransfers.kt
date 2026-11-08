package com.neststudio.pocketshell.ui

import android.net.Uri
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

    private fun fail(activity: AppCompatActivity, reason: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.files_download_failed)
            .setMessage(reason)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
