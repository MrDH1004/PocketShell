package com.neststudio.pocketshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.QuietHours
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.core.TerminalHistory
import com.neststudio.pocketshell.data.UiPrefs
import kotlin.math.roundToInt

/**
 * 설정: what used to be a long ⋮ list, grouped into cards — 알림 / 보기 / 세션 목록 / 업데이트 / 도움말.
 * Each row shows its current value on the right (or a switch); the screen redraws after every change.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: UiPrefs
    private lateinit var content: LinearLayout
    private val appUpdate = AppUpdateController(this)
    private val askNotifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, R.string.notify_enabled, Toast.LENGTH_SHORT).show()
            offerBatteryExemption(this, prefs)
        } else {
            prefs.agentNotify = false
            Toast.makeText(this, R.string.notify_denied, Toast.LENGTH_LONG).show()
        }
        applyNotifications()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)
        prefs = UiPrefs(this)
        content = findViewById(R.id.content)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.pcs_back)
            setNavigationOnClickListener { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val cards = SettingsCards(this, content)
        fun section(title: Int, rows: SettingsCards.Card.() -> Unit) = cards.section(getString(title), rows)
        section(R.string.settings_section_notify) {
            switchRow(getString(R.string.settings_agent_notify), getString(R.string.settings_agent_notify_sub), prefs.agentNotify) { toggleNotifications() }
            row(getString(R.string.quiet_title), if (prefs.quietEnabled) "${QuietHours.format(prefs.quietStart)} ~ ${QuietHours.format(prefs.quietEnd)}" else getString(R.string.settings_off)) { showQuietHours() }
        }
        section(R.string.settings_section_view) {
            row(getString(R.string.settings_font), TerminalFont.of(prefs.fontId).label.substringBefore(" (")) { viewSettings() }
            row(getString(R.string.settings_font_size), "${prefs.fontSizeSp.roundToInt()}sp") { viewSettings() }
            row(getString(R.string.settings_scroll), "${prefs.scrollPercent}%") { viewSettings() }
        }
        section(R.string.settings_section_list) {
            switchRow(getString(R.string.pc_load_menu), getString(R.string.settings_pc_load_sub), prefs.pcLoad) {
                prefs.pcLoad = !prefs.pcLoad
                render()
            }
            row(getString(R.string.recent_limit_menu), recentLabel(prefs.recentLimit)) { showRecentLimitDialog() }
            row(getString(R.string.terminal_history_menu), historyLabel(prefs.terminalHistory).substringBefore(" (")) { showTerminalHistoryDialog() }
        }
        section(R.string.settings_section_update) {
            row(getString(R.string.settings_version), BuildConfig.VERSION_NAME, chevron = false, onClick = null)
            row(getString(R.string.update_check), null) { appUpdate.manualCheck() }
            row(getString(R.string.patch_notes_menu), null) { PatchNotesController.showAll(this@SettingsActivity) }
        }
        section(R.string.settings_section_help) {
            row(getString(R.string.pcs_install_menu), null) { showInstallGuide() }
            row(getString(R.string.feedback_menu), null) { FeedbackController.show(this@SettingsActivity) }
        }
    }

    // ---- actions ----

    private fun viewSettings() = ViewSettingsDialog.show(this) { render() }

    private fun recentLabel(n: Int): String = when (n) {
        0 -> getString(R.string.recent_limit_off)
        SessionSections.RECENT_ALL -> getString(R.string.recent_limit_all)
        else -> getString(R.string.recent_limit_n, n)
    }

    private fun historyLabel(h: TerminalHistory): String = getString(
        when (h) {
            TerminalHistory.SMALL -> R.string.terminal_history_small
            TerminalHistory.NORMAL -> R.string.terminal_history_normal
            TerminalHistory.LARGE -> R.string.terminal_history_large
            TerminalHistory.ALL -> R.string.terminal_history_all
        },
    )

    private fun showRecentLimitDialog() {
        val choices = UiPrefs.RECENT_LIMIT_CHOICES
        AlertDialog.Builder(this)
            .setTitle(R.string.recent_limit_menu)
            .setSingleChoiceItems(choices.map(::recentLabel).toTypedArray(), choices.indexOf(prefs.recentLimit)) { dialog, which ->
                prefs.recentLimit = choices[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTerminalHistoryDialog() {
        val choices = TerminalHistory.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_history_menu)
            .setSingleChoiceItems(choices.map(::historyLabel).toTypedArray(), choices.indexOf(prefs.terminalHistory)) { dialog, which ->
                prefs.terminalHistory = choices[which]
                dialog.dismiss()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun canPost(): Boolean = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun applyNotifications() {
        com.neststudio.pocketshell.notify.AgentWatchService.apply(this)
        render()
    }

    private fun toggleNotifications() {
        if (prefs.agentNotify) {
            prefs.agentNotify = false
            Toast.makeText(this, R.string.notify_disabled, Toast.LENGTH_SHORT).show()
            applyNotifications()
            return
        }
        prefs.agentNotify = true
        if (!canPost()) {
            // Android shows its dialog only the first couple of times; after that it answers "denied" at once and
            // the denied message points to the system settings.
            prefs.notifyPermissionAsked = true
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        Toast.makeText(this, R.string.notify_enabled, Toast.LENGTH_SHORT).show()
        applyNotifications()
        offerBatteryExemption(this, prefs)
    }

    /** Quiet hours: on / off and the daily range. Each row changes one thing and shows the dialog again. */
    private fun showQuietHours() {
        val items = arrayOf(
            getString(if (prefs.quietEnabled) R.string.quiet_on else R.string.quiet_off),
            getString(R.string.quiet_start, QuietHours.format(prefs.quietStart)),
            getString(R.string.quiet_end, QuietHours.format(prefs.quietEnd)),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.quiet_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { prefs.quietEnabled = !prefs.quietEnabled; showQuietHours() }
                    1 -> pickTime(prefs.quietStart) { prefs.quietStart = it; showQuietHours() }
                    else -> pickTime(prefs.quietEnd) { prefs.quietEnd = it; showQuietHours() }
                }
            }
            .setPositiveButton(R.string.confirm, null)
            .setOnDismissListener { render() }
            .show()
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        android.app.TimePickerDialog(this, { _, hour, minute -> onPicked(hour * 60 + minute) }, minutes / 60, minutes % 60, true).show()
    }

    private fun showInstallGuide() {
        val url = PcListActivity.DOWNLOAD_URL
        AlertDialog.Builder(this)
            .setTitle(R.string.pcs_install_menu)
            .setMessage(getString(R.string.pcs_install_body) + "\n\n" + url)
            .setPositiveButton(R.string.pcs_install_open) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: android.content.ActivityNotFoundException) {
                    copyLink(this, url)
                }
            }
            .setNeutralButton(R.string.pcs_install_copy) { _, _ -> copyLink(this, url) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)

        fun copyLink(context: Context, url: String) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PocketShell", url))
            Toast.makeText(context, R.string.pcs_link_copied, Toast.LENGTH_SHORT).show()
        }

        /** Doze delays the long poll on a phone left idle; offer the system setting that exempts the app (once). */
        fun offerBatteryExemption(activity: AppCompatActivity, prefs: UiPrefs) {
            val pm = activity.getSystemService(android.os.PowerManager::class.java) ?: return
            if (prefs.batteryHintShown || pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            prefs.batteryHintShown = true
            AlertDialog.Builder(activity)
                .setTitle(R.string.notify_battery_title)
                .setMessage(R.string.notify_battery_body)
                .setPositiveButton(R.string.notify_battery_open) { _, _ ->
                    try {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(activity, R.string.notify_denied, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.notify_battery_later, null)
                .show()
        }
    }
}
