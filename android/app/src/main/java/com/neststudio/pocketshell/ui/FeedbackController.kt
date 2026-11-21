package com.neststudio.pocketshell.ui

import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.BuildConfig
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

/**
 * ⋮ 의견 보내기: kind (버그 수정 · 개선 · 의견 · 문의) + text, sent to a paired PC (`POST /api/feedback`), which saves
 * it as JSON in the shared OneDrive apppatch folder. Tries the current PC first, then the other paired PCs.
 */
object FeedbackController {
    /** Category ids the host accepts (Feedback.Categories) with their labels. */
    private val KINDS = listOf(
        "bug" to R.string.feedback_bug,
        "improve" to R.string.feedback_improve,
        "opinion" to R.string.feedback_opinion,
        "question" to R.string.feedback_question,
    )
    const val MAX_LENGTH = 5000

    fun show(activity: AppCompatActivity) {
        val app = activity.application as PkshellApp
        if (app.pcs().isEmpty()) {
            Toast.makeText(activity, R.string.feedback_no_pc, Toast.LENGTH_LONG).show()
            return
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val buttons = KINDS.map { (id, label) ->
            RadioButton(activity).apply {
                this.id = android.view.View.generateViewId()
                text = activity.getString(label)
                tag = id
                group.addView(this)
            }
        }
        buttons.first().isChecked = true
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.feedback_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_LENGTH))
        }
        val note = TextView(activity).apply {
            text = activity.getString(R.string.feedback_note)
            alpha = 0.7f
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(group)
            addView(input)
            addView(note)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.feedback_menu)
            .setView(box)
            .setPositiveButton(R.string.feedback_send, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isEmpty()) {
                    input.error = activity.getString(R.string.feedback_empty)
                    return@setOnClickListener
                }
                val kind = buttons.first { it.isChecked }.tag as String
                send.isEnabled = false
                activity.lifecycleScope.launch {
                    val error = submit(activity, kind, message)
                    send.isEnabled = true
                    if (error == null) {
                        dialog.dismiss() // the toast is shown by submit
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.feedback_failed) + ErrorText.cause(activity, error), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Null when a PC took it, else the last error. Own PCs only (not shared-session ones); all are checked at once and
     * the report goes to the first one that answers, preferring the current PC, so one offline PC does not hold it up.
     */
    private suspend fun submit(activity: AppCompatActivity, kind: String, message: String): String? {
        val app = activity.application as PkshellApp
        val active = app.pairing()
        val pcs = (listOfNotNull(active) + app.pcs().filter { it.id != active?.id }).filter { it.share == null }
        if (pcs.isEmpty()) return activity.getString(R.string.feedback_no_pc)
        val online = coroutineScope {
            pcs.map { pc -> async { withTimeoutOrNull(PROBE_TIMEOUT_MS) { runCatching { app.apiFor(pc).info() }.isSuccess } == true } }
                .awaitAll()
        }
        val order = pcs.filterIndexed { i, _ -> online[i] } + pcs.filterIndexed { i, _ -> !online[i] }
        val body = JSONObject()
            .put("category", kind)
            .put("message", message)
            .put("source", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        var lastError: String? = null
        for (pc in order.take(if (online.any { it }) online.count { it } else 1)) {
            try {
                val reply = JSONObject(app.apiFor(pc).request("POST", "/api/feedback", body))
                val shared = reply.optBoolean("shared")
                Toast.makeText(activity, if (shared) R.string.feedback_sent else R.string.feedback_saved_local, Toast.LENGTH_LONG).show()
                return null
            } catch (e: IOException) {
                lastError = e.message
            } catch (e: org.json.JSONException) {
                lastError = e.message
            }
        }
        return lastError ?: ""
    }

    private const val PROBE_TIMEOUT_MS = 6000L
}
