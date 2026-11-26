package com.neststudio.pocketshell.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.neststudio.pocketshell.R
import kotlin.math.roundToInt

/**
 * Grouped settings rows (설정 screen, terminal ⋮ sheet): a muted section title over a rounded card whose rows are split
 * by hairlines. A row shows its current value on the right with a chevron, or a switch; the whole row is the target.
 */
class SettingsCards(private val context: Context, private val container: LinearLayout) {

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).roundToInt()

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    fun section(title: String, rows: Card.() -> Unit) {
        container.addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(color(R.color.text_muted))
            setPaddingRelative(dp(20), dp(20), dp(20), dp(8))
        })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_card)
            clipToOutline = true
        }
        Card(card).rows()
        if (card.childCount == 0) {
            container.removeViewAt(container.childCount - 1) // no rows: no title either
            return
        }
        container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(16)
            it.marginEnd = dp(16)
        })
    }

    inner class Card(private val box: LinearLayout) {
        private fun add(v: View) {
            if (box.childCount > 0) {
                box.addView(View(context).apply { setBackgroundColor(color(R.color.hairline)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.marginStart = dp(16) })
            }
            box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun line(title: String, sub: String?): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPaddingRelative(dp(16), dp(10), dp(12), dp(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(color(R.color.text_primary))
                })
                if (sub != null) addView(TextView(context).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(color(R.color.text_muted))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        /** Title with the current value on the right; a chevron when it opens something. */
        fun row(title: String, value: String?, chevron: Boolean = true, onClick: (() -> Unit)?) {
            val l = line(title, null)
            if (value != null) l.addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(color(R.color.text_secondary))
                setPaddingRelative(dp(12), 0, dp(4), 0)
            })
            if (chevron && onClick != null) l.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_muted))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            if (onClick != null) {
                l.setBackgroundResource(rippleBg())
                l.setOnClickListener { onClick() }
            }
            l.contentDescription = listOfNotNull(title, value).joinToString(", ")
            add(l)
        }

        /** One choice of a list: title (in [face] when given) and ✓ on the current one; the whole row picks it. */
        fun checkRow(title: String, checked: Boolean, face: android.graphics.Typeface? = null, onClick: () -> Unit) {
            val l = line(title, null)
            if (face != null) ((l.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).typeface = face
            l.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
                visibility = if (checked) View.VISIBLE else View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            l.setBackgroundResource(rippleBg())
            l.setOnClickListener { onClick() }
            l.contentDescription = if (checked) "$title, ${context.getString(R.string.settings_selected)}" else title
            add(l)
        }

        /** Any view as a row (a slider with its label …), padded like the other rows. */
        fun custom(v: View) {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
                addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            add(wrap)
        }

        /** Title (and a line under it) with a switch; the whole row toggles. */
        fun switchRow(title: String, sub: String?, on: Boolean, onToggle: () -> Unit) {
            val l = line(title, sub)
            l.addView(MaterialSwitch(context).apply {
                isChecked = on
                isClickable = false // the row handles taps
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            l.setBackgroundResource(rippleBg())
            l.setOnClickListener { onToggle() }
            l.contentDescription = "$title, ${context.getString(if (on) R.string.settings_on else R.string.settings_off)}"
            add(l)
        }
    }

    private fun rippleBg(): Int {
        val out = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }
}
