package com.neststudio.pocketshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.TerminalText

/**
 * A session's screen and history as plain text. Long press selects with the system handles and copies; the search
 * field marks every match and steps through them (newest first, since the view opens at the end).
 */
class TerminalTextActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var count: TextView
    private var plain = ""
    private var matches: List<Int> = emptyList()
    private var current = -1
    private var queryLength = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal_text)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = true)

        // The text can be far larger than an Intent may carry, so the terminal hands it over in memory. It is gone
        // after the process was killed in the background: nothing to show then.
        plain = pending ?: run { finish(); return }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.term_select_title) }
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
            setNavigationOnClickListener { finish() }
            menu.add(0, MENU_COPY_ALL, 0, R.string.term_select_all_copy)
            setOnMenuItemClickListener {
                if (it.itemId == MENU_COPY_ALL) copyAll()
                true
            }
        }
        textView = findViewById(R.id.text)
        scroll = findViewById(R.id.scroll)
        count = findViewById(R.id.count)
        textView.text = plain
        // Newest output first. (Not fullScroll: it moves the focus into the text and away from the search field.)
        scroll.post { scroll.scrollTo(0, textView.bottom) }

        val query = findViewById<EditText>(R.id.query)
        query.doAfterTextChanged { search(it?.toString().orEmpty()) }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { step(forward = false); true } else false
        }
        findViewById<View>(R.id.prev).setOnClickListener { step(forward = false) }
        findViewById<View>(R.id.next).setOnClickListener { step(forward = true) }
        if (intent.getBooleanExtra(EXTRA_SEARCH, false)) {
            query.post {
                query.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun search(q: String) {
        matches = TerminalText.find(plain, q)
        queryLength = q.length
        current = TerminalText.firstShown(matches)
        render()
    }

    private fun step(forward: Boolean) {
        if (matches.isEmpty()) return
        current = TerminalText.step(current, matches.size, forward)
        render()
    }

    private fun render() {
        if (matches.isEmpty()) {
            textView.text = plain
            count.text = if (queryLength > 0) getString(R.string.term_search_none) else ""
            return
        }
        val span = SpannableString(plain)
        val all = ContextCompat.getColor(this, R.color.accent_container)
        val here = ContextCompat.getColor(this, R.color.accent)
        val onHere = ContextCompat.getColor(this, R.color.accent_on)
        matches.forEachIndexed { i, start ->
            val end = start + queryLength
            if (i == current) {
                span.setSpan(BackgroundColorSpan(here), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(ForegroundColorSpan(onHere), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                span.setSpan(BackgroundColorSpan(all), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        textView.text = span
        count.text = getString(R.string.term_search_count, current + 1, matches.size)
        val offset = matches[current]
        textView.post {
            val layout = textView.layout ?: return@post
            val line = layout.getLineForOffset(offset)
            // A third of the way down: context above the match, and clear of the keyboard.
            scroll.smoothScrollTo(0, (textView.top + layout.getLineTop(line) - scroll.height / 3).coerceAtLeast(0))
        }
    }

    private fun copyAll() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", plain))
        Toast.makeText(this, R.string.term_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) pending = null
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SEARCH = "search"
        private const val MENU_COPY_ALL = 1

        /** Text for the next [TerminalTextActivity] (see onCreate). */
        @Volatile private var pending: String? = null

        fun intent(context: Context, title: String, text: String, search: Boolean): Intent {
            pending = text
            return Intent(context, TerminalTextActivity::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_SEARCH, search)
        }
    }
}
