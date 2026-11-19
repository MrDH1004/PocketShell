package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * A home screen shortcut to a project folder on a PC (or an app icon long-press entry): opens the session running
 * there, else resumes the newest closed one there, else the PC's session list. Session ids change (a console's id is
 * its pid), so the shortcut keeps the folder, not the session.
 */
class ShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PkshellApp
        val pcId = intent.getStringExtra(EXTRA_PC).orEmpty()
        val cwd = intent.getStringExtra(EXTRA_CWD).orEmpty()
        val pc = app.store.get(pcId)
        if (pc == null || cwd.isBlank()) {
            Toast.makeText(this, R.string.shortcut_pc_gone, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PcListActivity::class.java))
            finish()
            return
        }
        app.select(pc.id)
        lifecycleScope.launch {
            val target = try {
                withTimeout(LOAD_TIMEOUT_MS) { SessionSections.forFolder(app.apiFor(pc).sessions(), cwd) }
            } catch (e: ApiException) {
                fail(getString(R.string.shortcut_unreachable, pc.displayName))
                return@launch
            } catch (e: IOException) {
                fail(getString(R.string.shortcut_unreachable, pc.displayName))
                return@launch
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                fail(getString(R.string.shortcut_unreachable, pc.displayName))
                return@launch
            } catch (e: org.json.JSONException) {
                fail(getString(R.string.shortcut_unreachable, pc.displayName))
                return@launch
            }
            if (target == null) {
                Toast.makeText(this@ShortcutActivity, getString(R.string.shortcut_no_session, SessionInfo.projectName(cwd) ?: cwd), Toast.LENGTH_LONG).show()
                startActivity(Intent(this@ShortcutActivity, SessionListActivity::class.java))
            } else {
                startActivity(TerminalActivity.intent(this@ShortcutActivity, target).putExtra(TerminalActivity.EXTRA_PC_ID, pc.id))
            }
            finish()
        }
    }

    private fun fail(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_PC = "pc"
        private const val EXTRA_CWD = "cwd"
        private const val LOAD_TIMEOUT_MS = 8000L
        private const val MAX_RECENT = 4

        private fun intent(context: Context, pcId: String, cwd: String): Intent =
            Intent(context, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PC, pcId)
                .putExtra(EXTRA_CWD, cwd)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        private fun info(context: Context, pcId: String, s: SessionInfo, rank: Int = 0): ShortcutInfoCompat {
            val name = s.displayTitle
            return ShortcutInfoCompat.Builder(context, "folder:" + pcId + ":" + SessionSections.dirKey(s.cwd))
                .setShortLabel(name.take(24))
                .setLongLabel(name)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent(context, pcId, s.cwd))
                .setRank(rank)
                .build()
        }

        /** Opening a session puts its folder first among the app icon's long-press entries (up to [MAX_RECENT]). */
        fun noteOpened(context: Context, pcId: String, s: SessionInfo) {
            if (s.cwd.isBlank()) return
            try {
                val max = minOf(MAX_RECENT, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
                if (max <= 0) return
                ShortcutManagerCompat.pushDynamicShortcut(context, info(context, pcId, s))
                val dyn = ShortcutManagerCompat.getDynamicShortcuts(context)
                if (dyn.size > max) ShortcutManagerCompat.removeDynamicShortcuts(context, dyn.sortedBy { it.rank }.drop(max).map { it.id })
            } catch (e: IllegalStateException) {
                // rate-limited by the launcher: the list just stays as it was
            } catch (e: IllegalArgumentException) {
            }
        }
    }
}
