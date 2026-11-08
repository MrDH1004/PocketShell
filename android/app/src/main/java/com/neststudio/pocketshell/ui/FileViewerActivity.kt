package com.neststudio.pocketshell.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.FilePreview
import com.neststudio.pocketshell.core.PreviewKind
import com.neststudio.pocketshell.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Read-only preview of a PC file: text (UTF-8 / CP949) or image; anything else offers a download. */
class FileViewerActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var path: String
    private lateinit var name: String
    private lateinit var body: FrameLayout
    private lateinit var toolbar: MaterialToolbar

    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) FileTransfers.download(this, api, path, uri, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val apiClient = (application as PkshellApp).api()
        path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val size = intent.getLongExtra(EXTRA_SIZE, -1)
        if (apiClient == null || path.isEmpty()) {
            finish()
            return
        }
        api = apiClient

        toolbar = MaterialToolbar(this).apply {
            title = name
            subtitle = if (size >= 0) FilePreview.humanSize(size) else null
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
            menu.add(0, 1, 0, R.string.files_download).setIcon(R.drawable.ic_download)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { save.launch(name); true }
        }
        body = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        root.applySystemInsets(includeIme = false)

        val kind = FilePreview.kind(name, if (size >= 0) size else 0)
        if (kind == PreviewKind.NONE) message(getString(R.string.files_no_preview)) else load(kind)
    }

    private fun load(kind: PreviewKind) {
        message(getString(R.string.files_loading))
        lifecycleScope.launch {
            try {
                val bytes = ByteArrayOutputStream().use { out -> api.download(path, out); out.toByteArray() }
                if (kind == PreviewKind.IMAGE) showImage(bytes) else showText(bytes)
            } catch (e: IOException) {
                message(getString(R.string.files_load_failed, "${e.javaClass.simpleName}: ${e.message}"))
            } catch (e: OutOfMemoryError) {
                message(getString(R.string.files_no_preview))
            }
        }
    }

    private suspend fun showText(bytes: ByteArray) {
        if (FilePreview.looksBinary(bytes)) {
            message(getString(R.string.files_no_preview))
            return
        }
        val text = withContext(Dispatchers.Default) { FilePreview.decodeText(bytes) }
        val pad = (resources.displayMetrics.density * 16).toInt()
        val tv = TextView(this).apply {
            // Same font as the terminal (보기 설정), so code reads the same in both places.
            typeface = TerminalFont.of(com.neststudio.pocketshell.data.UiPrefs(this@FileViewerActivity).fontId).typeface(this@FileViewerActivity)
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@FileViewerActivity, R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
            this.text = text
        }
        // Line numbers: a gutter with the same font/size/spacing so rows line up (no wrapping on either side).
        val lineCount = text.count { it == '\n' } + 1
        val gutter = TextView(this).apply {
            typeface = tv.typeface
            textSize = 13f
            setLineSpacing(0f, 1.15f)
            gravity = android.view.Gravity.END
            setTextColor(androidx.core.content.ContextCompat.getColor(this@FileViewerActivity, R.color.text_muted))
            setPadding(pad, pad, pad / 2, pad)
            this.text = (1..lineCount).joinToString("\n")
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@FileViewerActivity, R.color.ink_1))
        }
        toolbarSubtitle(getString(R.string.files_lines, lineCount))
        val h = HorizontalScrollView(this).apply { addView(tv) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(gutter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(h, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val v = ScrollView(this).apply { addView(row) }
        body.removeAllViews()
        body.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private suspend fun showImage(bytes: ByteArray) {
        val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) // fills bounds only (returns null)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bitmap == null) {
            message(getString(R.string.files_no_preview))
            return
        }
        val iv = ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }
        toolbarSubtitle("${bitmap.width}×${bitmap.height}")
        // Pinch to zoom, one finger to pan while zoomed (same as the terminal).
        val zoomBox = ZoomPanLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        body.removeAllViews()
        body.addView(zoomBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Subtitle: size, plus what the preview adds (line count, image size). */
    private fun toolbarSubtitle(extra: String) {
        val size = intent.getLongExtra(EXTRA_SIZE, -1)
        toolbar.subtitle = listOfNotNull(if (size >= 0) FilePreview.humanSize(size) else null, extra).joinToString(" · ")
    }

    private fun message(text: String) {
        val pad = (resources.displayMetrics.density * 24).toInt()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@FileViewerActivity, R.color.text_secondary))
            setPadding(pad, pad, pad, pad)
        }
        body.removeAllViews()
        body.addView(tv)
        tv.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"

        fun intent(context: Context, path: String, name: String, size: Long): Intent =
            Intent(context, FileViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path).putExtra(EXTRA_NAME, name).putExtra(EXTRA_SIZE, size)
    }
}
