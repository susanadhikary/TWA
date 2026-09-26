package np.com.narayanipauroti.kds

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen WebView host for the KDS PWA.
 *
 * Android TV devices generally ship without Chrome, so a Trusted Web Activity
 * is not an option; the system WebView is used instead.
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var offlineView: View
    private lateinit var retryButton: Button
    private var webView: WebView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { reload() }
    private var mainFrameFailed = false
    private var lastBackPress = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.webview_container)
        progress = findViewById(R.id.progress)
        offlineView = findViewById(R.id.offline_view)
        retryButton = findViewById(R.id.retry_button)
        retryButton.setOnClickListener { reload() }

        enterImmersiveMode()
        createWebView(savedInstanceState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(savedInstanceState: Bundle?) {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Allow order-alert sounds to play without a user gesture.
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString NarayaniKDS-AndroidTV/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = KdsWebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        container.addView(wv)
        webView = wv

        if (savedInstanceState == null || wv.restoreState(savedInstanceState) == null) {
            wv.loadUrl(BuildConfig.START_URL)
        }
        wv.requestFocus()
    }

    private inner class KdsWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme ?: return true
            // Keep all web navigation inside the app; block intents/other schemes
            // since a TV usually has nothing to hand them to.
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            if (!mainFrameFailed) showOffline(false)
            CookieManager.getInstance().flush()
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) onMainFrameError()
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            // Pre-API 23 path.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) onMainFrameError()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed for memory; rebuild the WebView
            // instead of letting the whole app die (important for an always-on display).
            container.removeView(view)
            view.destroy()
            webView = null
            createWebView(null)
            return true
        }
    }

    private fun onMainFrameError() {
        mainFrameFailed = true
        showOffline(true)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun showOffline(show: Boolean) {
        offlineView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            retryButton.requestFocus()
        } else {
            handler.removeCallbacks(retryRunnable)
            webView?.requestFocus()
        }
    }

    private fun reload() {
        handler.removeCallbacks(retryRunnable)
        val wv = webView ?: return
        val current = wv.url
        if (current.isNullOrEmpty() || current == "about:blank" || !isSameHost(current)) {
            wv.loadUrl(BuildConfig.START_URL)
        } else {
            wv.reload()
        }
    }

    private fun isSameHost(url: String): Boolean =
        Uri.parse(url).host == Uri.parse(BuildConfig.START_URL).host

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // MENU on the remote (or F5 on a keyboard) reloads the page.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_F5 -> {
                reload()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (offlineView.visibility != View.VISIBLE && wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        // Require a double press so the kitchen display isn't closed by accident.
        val now = System.currentTimeMillis()
        if (now - lastBackPress < EXIT_CONFIRM_WINDOW_MS) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            lastBackPress = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val RETRY_DELAY_MS = 10_000L
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
    }
}
