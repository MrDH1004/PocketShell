package com.neststudio.pocketshell.ui

import android.content.Context
import androidx.annotation.StringRes
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.net.ApiException
import org.json.JSONException

/** Error copy: "<what failed>. <how to recover>" with the raw cause on its own line. */
object ErrorText {
    fun of(context: Context, @StringRes action: Int, e: Exception): String {
        val hint = when {
            e is ApiException && e.code == 401 -> R.string.err_hint_unpaired
            e is ApiException -> R.string.err_hint_rejected
            e is JSONException -> R.string.err_hint_update_pc
            else -> R.string.err_hint_connect
        }
        return context.getString(R.string.err_format, context.getString(action), context.getString(hint)) + cause(context, e.message)
    }

    /** "\n(<cause>)", or nothing when there is no cause to show. */
    fun cause(context: Context, message: String?): String =
        if (message.isNullOrBlank()) "" else context.getString(R.string.err_cause, message)
}
