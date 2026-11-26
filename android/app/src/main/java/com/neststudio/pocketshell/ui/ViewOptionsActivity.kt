package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.data.UiPrefs
import kotlin.math.roundToInt

/**
 * Terminal view options as their own screens (were one dialog): [MODE_FONT] = 폰트·크기 with a live preview,
 * [MODE_SCROLL] = 스크롤 감도. Changes are saved at once; the terminal applies them when it is shown again.
 */
class ViewOptionsActivity : AppCompatActivity() {

    private lateinit var prefs: UiPrefs
    private lateinit var content: LinearLayout
    private var mode = MODE_FONT

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = false)
        prefs = UiPrefs(this)
        content = findViewById(R.id.content)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FONT
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(if (mode == MODE_SCROLL) R.string.view_scroll_name else R.string.view_font_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.pcs_back)
            setNavigationOnClickListener { finish() }
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun muted(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
    }

    private fun render() {
        content.removeAllViews()
        val cards = SettingsCards(this, content)
        if (mode == MODE_SCROLL) renderScroll(cards) else renderFont(cards)
        cards.section("") {
            row(getString(R.string.view_reset_row), null, chevron = false) {
                if (mode == MODE_SCROLL) {
                    prefs.scrollPercent = UiPrefs.DEFAULT_SCROLL_PERCENT
                } else {
                    prefs.fontSizeSp = UiPrefs.DEFAULT_FONT_SP
                    prefs.fontId = UiPrefs.DEFAULT_FONT_ID
                }
                render()
            }
        }
    }

    private fun renderFont(cards: SettingsCards) {
        // Preview: how the terminal will look (font, size), on the terminal's background.
        val preview = TextView(this).apply {
            text = getString(R.string.view_font_preview)
            typeface = TerminalFont.of(prefs.fontId).typeface(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundResource(R.drawable.shape_card) // a framed box, like the cards below
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(16); it.marginEnd = dp(16); it.topMargin = dp(16)
        })
        cards.section(getString(R.string.view_font_section)) {
            val current = TerminalFont.of(prefs.fontId)
            for (f in TerminalFont.entries) {
                checkRow(f.label, f == current, f.typeface(this@ViewOptionsActivity)) {
                    prefs.fontId = f.id
                    render()
                }
            }
        }
        cards.section(getString(R.string.view_size_section)) {
            val label = TextView(this@ViewOptionsActivity).apply {
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            fun show(sp: Float) {
                label.text = getString(R.string.view_size_value, sp.roundToInt())
                preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }
            show(prefs.fontSizeSp)
            val min = UiPrefs.MIN_FONT_SP.toInt()
            val seek = SeekBar(this@ViewOptionsActivity).apply {
                max = UiPrefs.MAX_FONT_SP.toInt() - min
                progress = prefs.fontSizeSp.roundToInt() - min
                contentDescription = getString(R.string.view_size_section)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val sp = (value + min).toFloat()
                        prefs.fontSizeSp = sp
                        show(sp)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            custom(LinearLayout(this@ViewOptionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(seek)
                intent.getStringExtra(EXTRA_NOTE)?.takeIf { it.isNotBlank() }?.let { addView(muted(it)) }
            })
        }
    }

    private fun renderScroll(cards: SettingsCards) {
        cards.section(getString(R.string.view_scroll_name)) {
            val label = TextView(this@ViewOptionsActivity).apply {
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            fun show(p: Int) { label.text = getString(R.string.view_scroll_value, p) }
            show(prefs.scrollPercent)
            val step = UiPrefs.SCROLL_STEP
            val seek = SeekBar(this@ViewOptionsActivity).apply {
                max = (UiPrefs.MAX_SCROLL_PERCENT - UiPrefs.MIN_SCROLL_PERCENT) / step
                progress = (prefs.scrollPercent - UiPrefs.MIN_SCROLL_PERCENT) / step
                contentDescription = getString(R.string.view_scroll_name)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val p = UiPrefs.MIN_SCROLL_PERCENT + value * step
                        prefs.scrollPercent = p
                        show(p)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            custom(LinearLayout(this@ViewOptionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(seek)
                addView(muted(getString(R.string.view_scroll_hint)))
            })
        }
    }

    companion object {
        const val MODE_FONT = "font"
        const val MODE_SCROLL = "scroll"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_NOTE = "note"

        /** [note]: shown under the size slider (an external console is re-flowed to the phone's width). */
        fun intent(context: Context, mode: String, note: String? = null): Intent =
            Intent(context, ViewOptionsActivity::class.java).putExtra(EXTRA_MODE, mode).putExtra(EXTRA_NOTE, note)

        /** "D2Coding · 12sp" for a row's value. */
        fun fontSummary(prefs: UiPrefs, context: Context): String =
            TerminalFont.of(prefs.fontId).label.substringBefore(" (") + " · " + prefs.fontSizeSp.roundToInt() + "sp"
    }
}
