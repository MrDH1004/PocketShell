package com.neststudio.pocketshell.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.WebBookmark
import com.neststudio.pocketshell.core.WebBookmarks
import com.neststudio.pocketshell.data.UiPrefs
import com.neststudio.pocketshell.net.LocalWebProxy

/**
 * Web view through the PC: every request of this WebView goes to [LocalWebProxy] (proxy override), which has the
 * PC open the connection (`/ws/tunnel`). Pages only the PC can reach (company intranet, the PC's own localhost)
 * load unchanged — links, cookies and HTTPS work end to end. Address bar, back/forward, reload, bookmarks.
 */
class WebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var prefs: UiPrefs
    private var proxy: LocalWebProxy? = null
    private var proxyReady = false
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        findViewById<View>(R.id.root).applySystemInsets(includeIme = true)
        prefs = UiPrefs(this)

        val api = (application as PkshellApp).api()
        if (api == null) {
            finish()
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            AlertDialog.Builder(this).setMessage(R.string.web_unsupported)
                .setPositiveButton(R.string.confirm) { _, _ -> finish() }.setCancelable(false).show()
            return
        }

        web = findViewById(R.id.web)
        address = findViewById(R.id.address)
        progress = findViewById(R.id.progress)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = client
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }
        }
        web.setDownloadListener { _, _, _, _, _ -> Toast.makeText(this, R.string.web_no_download, Toast.LENGTH_SHORT).show() }

        address.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(address.text.toString())
                true
            } else false
        }
        findViewById<View>(R.id.back).setOnClickListener { if (web.canGoBack()) web.goBack() else finish() }
        findViewById<View>(R.id.refresh).setOnClickListener { web.reload() }
        findViewById<View>(R.id.more).setOnClickListener { showMenu(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        // Every request of this app's WebViews goes to the loopback proxy, which has the PC make the connection.
        val p = LocalWebProxy { host, port -> api.openTunnel(host, port) }
        p.start()
        proxy = p
        val config = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:${p.port}")
            .removeImplicitRules() // "localhost" too goes to the PC (its own web servers)
            .build()
        ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(this)) {
            proxyReady = true
            pending?.let { load(it) }
            pending = null
        }

        val start = intent.getStringExtra(EXTRA_URL) ?: prefs.webLastUrl.ifBlank { null }
        if (start != null) load(start)
        else {
            address.requestFocus()
            showIntro()
        }
    }

    override fun onDestroy() {
        if (proxy != null) {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this)) {}
            proxy?.stop()
            proxy = null
        }
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private fun showIntro() {
        val body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;padding:16px;color:#A3A9B2;background:#0E1013'>" +
            "<p>${getString(R.string.web_intro)}</p>"
        web.loadDataWithBaseURL(null, body, "text/html", "utf-8", null)
    }

    private fun go(input: String) {
        val url = WebBookmarks.normalize(input) ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        load(url)
    }

    private fun load(url: String) {
        address.setText(url)
        if (!proxyReady) {
            pending = url // the proxy override applies asynchronously: never let a page go out directly
            return
        }
        web.loadUrl(url)
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.web_forward).isEnabled = web.canGoForward()
        menu.menu.add(0, 2, 1, R.string.web_bookmark_add).isEnabled = web.url?.startsWith("http") == true
        menu.menu.add(0, 3, 2, R.string.web_bookmarks)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> web.goForward()
                2 -> web.url?.let { url ->
                    prefs.webBookmarks = WebBookmarks.add(prefs.webBookmarks, WebBookmark(web.title?.ifBlank { url } ?: url, url))
                    Toast.makeText(this, R.string.web_bookmark_added, Toast.LENGTH_SHORT).show()
                }
                3 -> showBookmarks()
            }
            true
        }
        menu.show()
    }

    private fun showBookmarks() {
        val list = prefs.webBookmarks
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setTitle(R.string.web_bookmarks).setMessage(R.string.web_bookmarks_empty)
                .setPositiveButton(R.string.confirm, null).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.web_bookmarks)
            .setItems(list.map { "${it.title}\n${it.url}" }.toTypedArray()) { _, which -> load(list[which].url) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener { // long-press an entry to delete it

            dialog.listView.setOnItemLongClickListener { _, _, pos, _ -> confirmRemove(list[pos], dialog); true }
        }
        dialog.show()
    }

    private fun confirmRemove(b: WebBookmark, parent: AlertDialog) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.web_bookmark_remove, b.title))
            .setPositiveButton(R.string.web_delete) { _, _ ->
                prefs.webBookmarks = WebBookmarks.remove(prefs.webBookmarks, b.url)
                parent.dismiss()
                showBookmarks()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url.startsWith("http")) {
                if (!address.hasFocus()) address.setText(url)
                prefs.webLastUrl = url
            }
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            val p = proxy
            // Our own proxy's credentials (asked by the WebView on the 407); anything else is the site's login.
            if (p != null && realm == "PocketShell" && host.startsWith("127.0.0.1")) {
                handler.proceed(p.user, p.secret)
                return
            }
            askLogin(host, handler)
        }

        @SuppressLint("WebViewClientOnReceivedSslError") // the user decides, per page, after a warning
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            AlertDialog.Builder(this@WebActivity)
                .setTitle(R.string.web_ssl_title)
                .setMessage(getString(R.string.web_ssl_message, android.net.Uri.parse(error.url).host ?: error.url))
                .setPositiveButton(R.string.web_ssl_continue) { _, _ -> handler.proceed() }
                .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }
    }

    /** A site's own HTTP login (intranet Basic/NTLM prompt). */
    private fun askLogin(host: String, handler: HttpAuthHandler) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val user = EditText(this).apply { hint = getString(R.string.web_user); isSingleLine = true }
        val pass = EditText(this).apply {
            hint = getString(R.string.web_password)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(user)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.web_login_title, host))
            .setView(box)
            .setPositiveButton(R.string.web_login) { _, _ -> handler.proceed(user.text.toString(), pass.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String? = null): Intent =
            Intent(context, WebActivity::class.java).apply { if (url != null) putExtra(EXTRA_URL, url) }
    }
}
