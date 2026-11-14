package com.neststudio.pocketshell.ui

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
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.IntegrityException
import com.neststudio.pocketshell.core.ReleaseNotes
import com.neststudio.pocketshell.net.AppUpdater
import com.neststudio.pocketshell.net.VerifiedUpdate
import com.neststudio.pocketshell.PkshellApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
class AppUpdateController(private val activity: AppCompatActivity, private val pillAtTop: Boolean = false) {

    // Lazy: this controller is created as an activity field, before the activity has a base context.
    private val updater by lazy { AppUpdater(activity) }
    private var pendingApk: File? = null
    private var job: Job? = null
    private var pill: UpdatePill? = null

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

    /**
     * Screen shown: checks at most every 15 minutes and, when a newer release is known, shows the floating update
     * pill (tap = download and install). Silent otherwise.
     */
    fun autoCheck() {
        found?.let { showPill(it) }
        if (updater.shouldAutoCheck()) check(manual = false)
    }

    /** Menu "업데이트 확인". */
    fun manualCheck() = check(manual = true)

    /**
     * Checks both sources at once: the paired PC host over the LAN (`GET /api/app/android`, no GitHub rate limit) and
     * GitHub Releases. Either one is enough; the dialog lets the user pick where to download from.
     * While [PC_SOURCE] is off, only GitHub is checked.
     */
    private fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            val api = (activity.application as PkshellApp).api()
            updater.pcApi = api
            val pc = async { if (api == null || !PC_SOURCE) null else runCatching { updater.checkPc(api) } }
            val gh = async { runCatching { updater.check() } }
            val pcResult = pc.await()
            val ghResult = gh.await()
            val pcUpdate = pcResult?.getOrNull()
            val ghUpdate = ghResult.getOrNull()
            val newest = listOfNotNull(pcUpdate, ghUpdate).maxByOrNull { it.candidate.version }
            if (newest != null) found = newest
            when {
                !manual && newest != null -> showPill(newest)
                pcUpdate != null || ghUpdate != null -> showUpdateDialog(pcUpdate, ghUpdate)
                !manual -> Unit
                ghResult.isSuccess || pcResult?.isSuccess == true ->
                    toast(activity.getString(R.string.update_latest, updater.currentVersion))
                !PC_SOURCE -> showError(
                    activity.getString(R.string.update_check_failed, ghResult.exceptionOrNull()?.message ?: ""),
                )
                else -> showError(
                    activity.getString(
                        R.string.update_check_failed_both,
                        pcResult?.exceptionOrNull()?.message ?: activity.getString(R.string.update_pc_unpaired),
                        ghResult.exceptionOrNull()?.message ?: "",
                    ),
                )
            }
        }
    }

    private fun showUpdateDialog(pc: VerifiedUpdate?, gh: VerifiedUpdate?) {
        if (activity.isFinishing) return
        val newest = listOfNotNull(pc, gh).maxBy { it.candidate.version }
        val notes = ReleaseNotes.clean(gh?.candidate?.notes.orEmpty()).ifEmpty { activity.getString(R.string.update_no_notes) }
        val b = AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, updater.currentVersion, newest.candidate.version.toString(), notes))
            .setNegativeButton(R.string.update_later, null)
        if (pc != null) {
            b.setPositiveButton(activity.getString(R.string.update_from_pc, pc.candidate.version.toString())) { _, _ -> download(pc) }
            if (gh != null) b.setNeutralButton(activity.getString(R.string.update_from_github, gh.candidate.version.toString())) { _, _ -> download(gh) }
        } else if (gh != null) {
            b.setPositiveButton(activity.getString(R.string.update_from_github, gh.candidate.version.toString())) { _, _ -> download(gh) }
        }
        b.show()
    }

    /** Floating "새 버전 x · 눌러서 설치" over the screen; closing it hides that version until a newer one. */
    private fun showPill(update: VerifiedUpdate) {
        if (activity.isFinishing) return
        val version = update.candidate.version.toString()
        if (version == dismissedVersion || version == updater.currentVersion) return
        if (pill?.version == version) return
        pill?.remove()
        pill = UpdatePill(activity, version, pillAtTop,
            onTap = { p -> pillDownload(update, p) },
            onClose = {
                dismissedVersion = version
                pill = null
            })
    }

    /** One tap: download with the progress in the pill, then straight to the installer. */
    private fun pillDownload(update: VerifiedUpdate, p: UpdatePill) {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            try {
                p.progress(0)
                val apk = updater.download(update) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    activity.runOnUiThread { p.progress(pct) }
                }
                p.remove()
                pill = null
                install(apk)
            } catch (e: IntegrityException) {
                p.idle()
                showError(activity.getString(R.string.update_integrity_failed, e.message ?: ""))
            } catch (e: IOException) {
                p.idle()
                showError(activity.getString(R.string.update_download_failed, e.message ?: ""))
            }
        }
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

        /** Newest release found this run (shared by every screen's controller, so each shows the pill at once). */
        @Volatile var found: VerifiedUpdate? = null

        /** Version whose pill the user closed this run. */
        @Volatile var dismissedVersion: String? = null

        /** Phone app from the paired PC over the same network (`GET /api/app/android`). Off for now: GitHub only. */
        const val PC_SOURCE = false
    }
}
