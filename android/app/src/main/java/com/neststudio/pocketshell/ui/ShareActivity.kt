package com.neststudio.pocketshell.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.AgentState
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.ShareTargets
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import java.io.File
import java.io.IOException

/**
 * "Share → PocketShell" from any app: text (or a link) is typed into the chosen session without Enter, an image is
 * attached the way the terminal's image button does (PC clipboard + Alt+V, Claude Code). The user picks one live
 * session from all paired PCs, AI sessions waiting on a question first; the terminal then opens on it.
 */
class ShareActivity : AppCompatActivity() {

    private var text: String? = null
    private var image: Uri? = null
    /** [image] copied into the app's cache while this activity holds the sharing app's read grant. */
    private var copy: kotlinx.coroutines.Deferred<File?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent?.action != Intent.ACTION_SEND) return finish()
        val type = intent.type.orEmpty()
        if (type.startsWith("image/")) image = streamOf(intent)
        text = ShareTargets.text(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
        if (image == null && text == null) return done(getString(R.string.share_nothing))
        val app = application as PkshellApp
        val pcs = app.pcs()
        if (pcs.isEmpty()) return done(getString(R.string.share_no_pcs))
        if (savedInstanceState != null) return finish() // a dialog does not survive recreation; share again
        image?.let { uri -> copy = lifecycleScope.async(Dispatchers.IO) { copyToCache(uri) } }
        load(app, pcs)
    }

    /** EXTRA_STREAM, else the first ClipData item (senders put the granted Uri there too). */
    private fun streamOf(intent: Intent): Uri? {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        return extra ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    /**
     * The terminal opens after this activity is gone, and the sharing app's read grant belongs to this one: the
     * bytes are taken now. Null when the image cannot be read or is larger than [MAX_IMAGE_BYTES].
     */
    private fun copyToCache(uri: Uri): File? {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val out = File(dir, "image-${System.currentTimeMillis()}")
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { src ->
                out.outputStream().use { dst ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) throw IOException("image larger than ${MAX_IMAGE_BYTES shr 20} MB")
                        dst.write(buf, 0, n)
                    }
                }
            }
            out
        } catch (e: IOException) {
            android.util.Log.w("Share", "cannot read the shared image", e)
            out.delete()
            null
        } catch (e: SecurityException) {
            android.util.Log.w("Share", "no permission for the shared image", e)
            out.delete()
            null
        }
    }

    private fun load(app: PkshellApp, pcs: List<Pairing>) {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.share_loading)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        lifecycleScope.launch {
            val results = pcs.map { p ->
                async {
                    val list = try {
                        withTimeoutOrNull(LOAD_TIMEOUT_MS) { app.apiFor(p).sessions() }
                    } catch (e: ApiException) {
                        null
                    } catch (e: IOException) {
                        null
                    } catch (e: JSONException) {
                        null
                    }
                    p to list
                }
            }.awaitAll()
            if (isFinishing) return@launch
            progress.setOnCancelListener(null)
            progress.dismiss()
            // Only an image, and it cannot be read: say so before asking where to send nothing.
            if (text == null && copy?.await() == null) return@launch done(getString(R.string.share_image_unreadable))
            val unreachable = results.filter { it.second == null }.map { it.first.displayName }
            val targets = ShareTargets.orderBy(results.flatMap { (p, list) -> list.orEmpty().map { p to it } }) { it.second }
            choose(targets, unreachable, pcs.size > 1)
        }
    }

    private fun choose(targets: List<Pair<Pairing, SessionInfo>>, unreachable: List<String>, manyPcs: Boolean) {
        val note = if (unreachable.isEmpty()) null else getString(R.string.share_unreachable, unreachable.joinToString(", "))
        if (targets.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.share_label)
                .setMessage(listOfNotNull(getString(R.string.share_none), note).joinToString("\n\n"))
                .setPositiveButton(R.string.confirm, null)
                .setOnDismissListener { finish() }
                .show()
            return
        }
        val labels = targets.map { (p, s) -> label(p, s, manyPcs) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (note == null) getString(R.string.share_title) else "${getString(R.string.share_title)}\n$note")
            .setItems(labels) { _, which -> open(targets[which].first, targets[which].second) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!opened) finish() }
            .show()
    }

    private var opened = false

    /** "flowdesk · 집 PC\nClaude · 입력 대기". */
    private fun label(p: Pairing, s: SessionInfo, manyPcs: Boolean): String {
        val first = if (manyPcs) "${s.displayTitle} · ${p.displayName}" else s.displayTitle
        val agent = when (s.agent) {
            "claude" -> "Claude"
            "gpt" -> "GPT"
            null -> null
            else -> s.agent
        }
        val state = when (s.agentState) {
            AgentState.WAITING -> getString(R.string.agent_waiting)
            AgentState.WORKING -> getString(R.string.agent_working)
            AgentState.DONE -> getString(R.string.agent_done)
            else -> null
        }
        val second = listOfNotNull(agent, state).joinToString(" · ").ifEmpty { s.shell.ifBlank { null } }
        return if (second == null) first else "$first\n$second"
    }

    private fun open(p: Pairing, s: SessionInfo) {
        opened = true
        lifecycleScope.launch {
            val file = copy?.await()
            if (image != null && file == null && text == null) return@launch done(getString(R.string.share_image_unreadable))
            if (image != null && file == null) Toast.makeText(this@ShareActivity, R.string.share_image_unreadable, Toast.LENGTH_LONG).show()
            (application as PkshellApp).select(p.id)
            val i = TerminalActivity.intent(this@ShareActivity, s).putExtra(TerminalActivity.EXTRA_PC_ID, p.id)
            text?.let { i.putExtra(TerminalActivity.EXTRA_SHARE_TEXT, it) }
            file?.let { i.putExtra(TerminalActivity.EXTRA_SHARE_IMAGE, Uri.fromFile(it)) }
            startActivity(i)
            finish()
        }
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 6000L
        /** Same order as the host's clipboard limit; the terminal shrinks the picture before sending anyway. */
        const val MAX_IMAGE_BYTES = 50L shl 20
    }
}
