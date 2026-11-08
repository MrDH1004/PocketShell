package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.FontRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.data.UiPrefs
import kotlin.math.roundToInt

/** Terminal fonts: the system monospace plus two bundled OFL fonts (licenses in assets/licenses). */
enum class TerminalFont(val id: String, val label: String, @FontRes private val res: Int) {
    SYSTEM("system", "시스템 기본 (monospace)", 0),
    D2CODING("d2coding", "D2Coding (한글 고정폭)", R.font.d2coding),
    JETBRAINS("jetbrains", "JetBrains Mono", R.font.jetbrains_mono);

    fun typeface(context: Context): Typeface {
        if (res == 0) return Typeface.MONOSPACE
        return try {
            ResourcesCompat.getFont(context, res) ?: Typeface.MONOSPACE
        } catch (e: Resources.NotFoundException) {
            Log.w("pkshell", "font $id not loaded", e)
            Typeface.MONOSPACE
        }
    }

    companion object {
        fun of(id: String): TerminalFont = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** "보기 설정" dialog: font size + font. Changes are saved at once and reported to [onChanged] for live preview. */
object ViewSettingsDialog {
    /** [note]: shown under the size (e.g. an external console caps the size to fit the PC window). */
    fun show(context: Context, note: String? = null, onChanged: () -> Unit = {}) {
        val prefs = UiPrefs(context)
        val pad = (context.resources.displayMetrics.density * 20).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun addNote() {
            if (note.isNullOrBlank()) return
            box.addView(TextView(context).apply {
                text = note
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_muted))
            })
        }

        val sizeLabel = TextView(context)
        fun showSize(sp: Float) { sizeLabel.text = context.getString(R.string.view_font_size, sp.roundToInt()) }
        showSize(prefs.fontSizeSp)
        box.addView(sizeLabel)

        val min = UiPrefs.MIN_FONT_SP.toInt()
        val seek = SeekBar(context).apply {
            max = UiPrefs.MAX_FONT_SP.toInt() - min
            progress = prefs.fontSizeSp.roundToInt() - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val sp = (value + min).toFloat()
                    prefs.fontSizeSp = sp
                    showSize(sp)
                    onChanged()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addNote()

        box.addView(TextView(context).apply {
            text = context.getString(R.string.view_font)
            setPadding(0, pad / 2, 0, 0)
        })
        val current = TerminalFont.of(prefs.fontId)
        val group = RadioGroup(context)
        for (f in TerminalFont.entries) {
            val rb = RadioButton(context).apply {
                id = ViewGroup.generateViewId()
                text = f.label
                typeface = f.typeface(context)
                tag = f.id
            }
            group.addView(rb)
            if (f == current) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val id = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.fontId = id
            onChanged()
        }
        box.addView(group)

        AlertDialog.Builder(context)
            .setTitle(R.string.view_settings)
            .setView(box)
            .setPositiveButton(R.string.confirm, null)
            .setNeutralButton(R.string.view_reset) { _, _ ->
                prefs.fontSizeSp = UiPrefs.DEFAULT_FONT_SP
                prefs.fontId = TerminalFont.SYSTEM.id
                onChanged()
            }
            .show()
    }
}
