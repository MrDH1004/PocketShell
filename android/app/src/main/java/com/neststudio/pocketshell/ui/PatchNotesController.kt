package com.neststudio.pocketshell.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.PatchNotes
import com.neststudio.pocketshell.core.SemVer
import com.neststudio.pocketshell.data.UiPrefs
import com.neststudio.pocketshell.net.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Patch notes from the repository's PATCHNOTES.md on GitHub: shown once when the app opens after an update, and
 * in full from the ⋮ menu (패치내역).
 */
object PatchNotesController {
    const val URL = "https://raw.githubusercontent.com/MrDH1004/PocketShell/main/PATCHNOTES.md"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    /** Checked once per app process; a failed fetch (offline) tries again at the next start. */
    private var checked = false

    fun showOnceAfterUpdate(activity: AppCompatActivity) {
        val prefs = UiPrefs(activity)
        val current = SemVer.parse(BuildConfig.VERSION_NAME) ?: return
        if (checked || prefs.patchNotesSeen == current.toString()) return
        checked = true
        activity.lifecycleScope.launch {
            val entries = try {
                fetch()
            } catch (e: IOException) {
                checked = false
                return@launch
            }
            val fresh = PatchNotes.since(entries, SemVer.parse(prefs.patchNotesSeen), current)
            prefs.patchNotesSeen = current.toString()
            if (fresh.isEmpty() || activity.isFinishing || activity.isDestroyed) return@launch
            show(activity, activity.getString(R.string.patch_notes_updated, current.toString()), PatchNotes.format(fresh))
        }
    }

    fun showAll(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val entries = try {
                fetch()
            } catch (e: IOException) {
                Toast.makeText(activity, activity.getString(R.string.patch_notes_failed) + ErrorText.cause(activity, e.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val text = if (entries.isEmpty()) activity.getString(R.string.patch_notes_empty) else PatchNotes.format(entries)
            show(activity, activity.getString(R.string.patch_notes_menu), text)
        }
    }

    private fun show(activity: AppCompatActivity, title: String, text: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private suspend fun fetch(): List<PatchNotes.Entry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(URL).header("User-Agent", AppUpdater.USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            PatchNotes.parse(resp.body?.string().orEmpty())
        }
    }
}
