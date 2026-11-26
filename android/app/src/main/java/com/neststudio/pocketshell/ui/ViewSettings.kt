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
