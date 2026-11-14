package com.neststudio.pocketshell.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.neststudio.pocketshell.R

/**
 * Floating update pill over the activity's content: "새 버전 x · 눌러서 설치" and a close button. Tapping it starts
 * the download (the pill shows the percentage), then the installer opens. Bottom of the screen (above the FAB and
 * the gesture bar), or at the top under the bar on the terminal screen whose bottom is the key bar.
 */
class UpdatePill(
    private val activity: AppCompatActivity,
    val version: String,
    atTop: Boolean,
    private val onTap: (UpdatePill) -> Unit,
    private val onClose: () -> Unit,
) {
    private val root = activity.findViewById<FrameLayout>(android.R.id.content)
    private val text: TextView
    private val view: LinearLayout
    private var busy = false

    init {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        text = TextView(activity).apply {
            setTextColor(ContextCompat.getColor(activity, R.color.accent_on))
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
        }
        val close = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent_on))
            contentDescription = activity.getString(R.string.update_later)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                remove()
                onClose()
            }
        }
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(ContextCompat.getColor(activity, R.color.accent))
            }
            elevation = dp(8).toFloat()
            minimumHeight = dp(48)
            setPaddingRelative(dp(18), 0, dp(4), 0)
            isClickable = true
            setOnClickListener { if (!busy) onTap(this@UpdatePill) }
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        idle()
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        lp.leftMargin = dp(16)
        lp.rightMargin = dp(16)
        // Clear the bars: status / top bar above, gesture bar and the session list's FAB below.
        // Before the first layout the insets are not known yet: fall back to the usual status / gesture bar sizes.
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())
        if (atTop) lp.topMargin = (insets?.top ?: dp(32)) + dp(68) else lp.bottomMargin = (insets?.bottom ?: dp(24)) + dp(96)
        root.addView(view, lp)
        view.alpha = 0f
        view.translationY = if (atTop) -dp(12).toFloat() else dp(12).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(250).start()
    }

    /** Waiting for a tap. */
    fun idle() {
        busy = false
        text.text = activity.getString(R.string.update_pill, version)
    }

    /** Downloading: [pct] 0..100, or -1 when the size is unknown. */
    fun progress(pct: Int) {
        busy = true
        text.text = if (pct >= 0) activity.getString(R.string.update_pill_progress, pct) else activity.getString(R.string.update_downloading)
    }

    fun remove() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.visibility = View.GONE
    }
}
