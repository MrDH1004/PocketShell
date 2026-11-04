package com.mpta.client.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mpta.client.R
import com.mpta.client.core.IntegrityException
import com.mpta.client.net.AppUpdater
import com.mpta.client.net.VerifiedUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Android self-update UI (DESIGN.md §11.4): check → dialog (version + notes) → download + SHA-256 → install via
 * FileProvider + ACTION_VIEW. If installing from this app is not allowed yet, opens the per-app "unknown
 * sources" settings and resumes the install when the user comes back.
 *
 * Must be constructed during the activity's initialization (it registers an activity result launcher).
 */
class AppUpdateController(private val activity: AppCompatActivity) {

    // Lazy: this controller is created as an activity field, before the activity has a base context.
    private val updater by lazy { AppUpdater(activity) }
    private var pendingApk: File? = null
    private var job: Job? = null

    private val unknownSourcesSettings =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingApk ?: return@registerForActivityResult
            if (activity.packageManager.canRequestPackageInstalls()) {
                pendingApk = null
                launchInstaller(apk)
            } else {
                toast(R.string.update_install_not_allowed)
            }
        }

    /** App start: at most once per day, silent unless an update is found. */
    fun autoCheck() {
        if (updater.shouldAutoCheck()) check(manual = false)
    }

    /** Menu "업데이트 확인". */
    fun manualCheck() = check(manual = true)

    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            try {
                val update = updater.check()
                when {
                    update != null -> showUpdateDialog(update)
                    manual -> toast(activity.getString(R.string.update_latest, updater.currentVersion))
                }
            } catch (e: IntegrityException) {
                if (manual) showError(e.message)
            } catch (e: IOException) {
                if (manual) showError(activity.getString(R.string.update_check_failed, e.message ?: ""))
            }
        }
    }

    private fun showUpdateDialog(update: VerifiedUpdate) {
        if (activity.isFinishing) return
        val c = update.candidate
        val notes = c.notes.trim().ifEmpty { activity.getString(R.string.update_no_notes) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, c.version.toString()))
            .setMessage(activity.getString(R.string.update_available_message, updater.currentVersion, c.version.toString(), notes))
            .setPositiveButton(R.string.update_download) { _, _ -> download(update) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(update: VerifiedUpdate) {
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setMessage(activity.getString(R.string.update_progress_unknown, 0L))
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        job = activity.lifecycleScope.launch {
            try {
                var lastPct = -1L
                val apk = updater.download(update) { done, total ->
                    if (total > 0) {
                        val pct = done * 100 / total
                        if (pct != lastPct) {
                            lastPct = pct
                            val text = activity.getString(R.string.update_progress, pct)
                            activity.runOnUiThread { progress.setMessage(text) }
                        }
                    } else {
                        val text = activity.getString(R.string.update_progress_unknown, done / 1024)
                        activity.runOnUiThread { progress.setMessage(text) }
                    }
                }
                progress.dismiss()
                install(apk)
            } catch (e: IntegrityException) {
                progress.dismiss()
                showError(activity.getString(R.string.update_integrity_failed, e.message ?: ""))
            } catch (e: IOException) {
                progress.dismiss()
                showError(activity.getString(R.string.update_download_failed, e.message ?: ""))
            } finally {
                if (progress.isShowing) progress.dismiss()
            }
        }
    }

    private fun install(apk: File) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(apk)
            return
        }
        pendingApk = apk
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                try {
                    unknownSourcesSettings.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    showError(activity.getString(R.string.update_settings_missing))
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> pendingApk = null }
            .show()
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showError(activity.getString(R.string.update_installer_missing))
        }
    }

    /** Debug builds only: override the release list URL (QA against a local release server). */
    fun showDebugUrlDialog() {
        val input = EditText(activity).apply {
            setText(updater.debugOverrideUrl ?: "")
            hint = AppUpdater.DEFAULT_RELEASES_URL
            setSingleLine()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_debug_url)
            .setMessage(R.string.update_debug_url_help)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ -> updater.debugOverrideUrl = input.text?.toString() }
            .setNeutralButton(R.string.update_debug_url_reset) { _, _ -> updater.debugOverrideUrl = null }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(text: String?) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(text ?: "")
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
