package np.com.narayanipauroti.kds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Full-screen host for the KDS PWA.
 *
 * Uses the Android System WebView that ships with every Android TV, so no browser
 * (Chrome etc.) has to be installed. The address bar never exists and the URL is
 * never shown to staff; browser features the WebView lacks (notifications, print,
 * share, downloads, file upload, pop-ups, permission prompts) are provided natively.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var splash: View
    private lateinit var offlineView: View
    private lateinit var offlineTitle: TextView
    private lateinit var offlineMessage: TextView
    private lateinit var retryButton: Button

    private lateinit var notifier: KdsNotifier
    private lateinit var bridge: JsBridge
    private lateinit var cursor: CursorController

    private var webView: WebView? = null
    private var popupWebView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var startScript: ScriptHandler? = null
    private var injectScriptOnPageLoad = false
    private val viewportScript: String? by lazy {
        if (BuildConfig.VIEWPORT_WIDTH <= 0) null
        else assets.open("kds_viewport.js").bufferedReader().use { it.readText() }
            .replace("__KDS_VIEWPORT_WIDTH__", BuildConfig.VIEWPORT_WIDTH.toString())
    }
    private var lastPermissionState: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { reload() }
    private var mainFrameFailed = false
    private var lastBackPress = 0L

    private class PendingPermissions(val permissions: List<String>, val callback: (Set<String>) -> Unit)

    private val permissionQueue = ArrayDeque<PendingPermissions>()
    private var permissionInFlight: PendingPermissions? = null

    /** App scope (like a PWA manifest "scope"): pages under it get the native browser features. */
    private val scopeUri: Uri = Uri.parse(BuildConfig.SCOPE)
    private val trustedOrigin = originOf(scopeUri)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        container = findViewById(R.id.webview_container)
        progress = findViewById(R.id.progress)
        splash = findViewById(R.id.splash)
        offlineView = findViewById(R.id.offline_view)
        offlineTitle = findViewById(R.id.offline_title)
        offlineMessage = findViewById(R.id.offline_message)
        retryButton = findViewById(R.id.retry_button)
        retryButton.setOnClickListener { if (webView == null) recreate() else reload() }

        notifier = KdsNotifier(this)
        bridge = JsBridge(this, notifier)
        cursor = CursorController(root) { activeInputTarget() }
        // Pointer sits above the web content but below the splash/offline screens.
        root.addView(
            cursor.view,
            root.indexOfChild(container) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        enterImmersiveMode()
        if (!createWebView(savedInstanceState)) return
        checkWebViewVersion()
        requestInitialPermissions()
        handleNotificationIntent(intent)
    }

    // ---------------------------------------------------------------- WebView setup

    private fun createWebView(savedInstanceState: Bundle?): Boolean {
        val wv = try {
            WebView(this)
        } catch (e: Exception) {
            // Android System WebView disabled or missing (extremely rare on TVs).
            splash.visibility = View.GONE
            showOffline(true, R.string.webview_missing_title, R.string.webview_missing_message)
            return false
        }
        configureWebView(wv, allowPopups = true)
        wv.webViewClient = KdsWebViewClient()
        wv.webChromeClient = KdsChromeClient(isPopup = false)
        installBridge(wv)

        container.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView = wv

        if (savedInstanceState == null || wv.restoreState(savedInstanceState) == null) {
            wv.loadUrl(BuildConfig.START_URL)
        }
        wv.requestFocus()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView, allowPopups: Boolean) {
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.isHapticFeedbackEnabled = false
        // No text-selection / "search the web" / link menus: keep it looking like an app.
        wv.setOnLongClickListener { true }

        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Allow order-alert sounds to play without a user gesture.
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(allowPopups)
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString TapTillKDS-AndroidTV/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(wv, url, userAgent, contentDisposition, mimeType)
        }
    }

    /** Exposes window.KdsBridge to the KDS origin only and injects assets/kds_bridge.js. */
    private fun installBridge(wv: WebView) {
        val origins = setOf(trustedOrigin)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, JsBridge.JS_OBJECT, origins) { _, message, _, isMainFrame, _ ->
                if (isMainFrame && isInScope(webView?.url)) message.data?.let { bridge.handle(it) }
            }
        } else {
            wv.addJavascriptInterface(LegacyBridge(), JsBridge.JS_OBJECT)
        }
        startScript = null
        injectScriptOnPageLoad = !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        updateStartScript(wv)
        // Fixed layout width (VIEWPORT_WIDTH) so the KDS looks the same on every TV.
        viewportScript?.let { script ->
            if (!injectScriptOnPageLoad) WebViewCompat.addDocumentStartJavaScript(wv, script, setOf(trustedOrigin))
        }
    }

    private fun updateStartScript(wv: WebView) {
        lastPermissionState = notifier.permissionState()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            startScript?.remove()
            startScript = WebViewCompat.addDocumentStartJavaScript(wv, bridge.script(), setOf(trustedOrigin))
        }
    }

    /** Old WebViews without document-start scripts: inject on page load instead. */
    private fun injectLegacyScripts(view: WebView) {
        view.evaluateJavascript(bridge.script(), null)
        viewportScript?.let { view.evaluateJavascript(it, null) }
    }

    /** Fallback for old WebViews without WEB_MESSAGE_LISTENER. */
    private inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            handler.post { if (isInScope(webView?.url)) bridge.handle(message) }
        }
    }

    private fun originOf(uri: Uri): String = buildString {
        append(uri.scheme?.lowercase()).append("://").append(uri.host?.lowercase())
        if (uri.port != -1) append(':').append(uri.port)
    }

    /** True if [url] is inside the app scope: same origin and a path under the scope path. */
    private fun isInScope(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val uri = Uri.parse(url)
        if (originOf(uri) != trustedOrigin) return false
        val path = uri.path.orEmpty().ifEmpty { "/" }
        return path.startsWith(scopeUri.path.orEmpty().ifEmpty { "/" })
    }

    /** Permission prompts only carry an origin; trust it when it matches and the page is in scope. */
    private fun isTrustedOrigin(origin: String?): Boolean {
        if (origin.isNullOrEmpty() || originOf(Uri.parse(origin)) != trustedOrigin) return false
        return isInScope(webView?.url) || isInScope(popupWebView?.url)
    }

    /** Sends a JSON message to the page's bridge script (trusted origin only). */
    fun sendToPage(json: String) {
        val wv = webView ?: return
        if (!isInScope(wv.url)) return
        wv.evaluateJavascript("window.__kdsOnNative&&window.__kdsOnNative(${JSONObject.quote(json)})", null)
    }

    private fun checkWebViewVersion() {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return
        val major = pkg.versionName?.substringBefore('.')?.toIntOrNull() ?: return
        if (major >= MIN_WEBVIEW_MAJOR) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "warned_webview_$major"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.webview_outdated, major))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- WebViewClient

    private inner class KdsWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isWebScheme(request.url)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
            url == null || !isWebScheme(Uri.parse(url))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            if (injectScriptOnPageLoad && isInScope(url)) injectLegacyScripts(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            if (injectScriptOnPageLoad && isInScope(url)) injectLegacyScripts(view)
            if (!mainFrameFailed) {
                showOffline(false)
                splash.visibility = View.GONE
            }
            CookieManager.getInstance().flush()
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onMainFrameError(R.string.offline_title, R.string.offline_message)
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            // Pre-API 23 path (only reported for the main frame).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                onMainFrameError(R.string.offline_title, R.string.offline_message)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
            sslHandler.cancel()
            if (error.url == view.url || isInScope(error.url) && view.progress < 100) {
                onMainFrameError(R.string.ssl_error_title, R.string.ssl_error_message)
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Renderer crashed or was killed for memory: rebuild instead of letting the app die.
            closePopup()
            hideCustomView()
            container.removeView(view)
            view.destroy()
            webView = null
            createWebView(null)
            return true
        }
    }

    /** Pop-up windows (window.open / target=_blank) opened by the page. */
    private inner class PopupWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isWebScheme(request.url)

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            closePopup()
            return true
        }
    }

    /** Only http(s) stays in the app; other schemes (intent:, market:, …) are blocked so nothing opens a browser. */
    private fun isWebScheme(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"

    // ---------------------------------------------------------------- WebChromeClient

    private inner class KdsChromeClient(private val isPopup: Boolean) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (isPopup) return
            progress.visibility = if (newProgress < 100 && splash.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        }

        // No default poster (grey play icon) on <video>.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // ----- Location

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!isTrustedOrigin(origin)) {
                callback.invoke(origin, false, false)
                return
            }
            withPermissions(LOCATION_PERMISSIONS) { granted ->
                callback.invoke(origin, granted.isNotEmpty(), false)
            }
        }

        // ----- Camera / microphone (getUserMedia)

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!isTrustedOrigin(request.origin.toString())) {
                request.deny()
                return
            }
            val needed = request.resources.mapNotNull { RESOURCE_PERMISSIONS[it] }.distinct()
            withPermissions(needed) { granted ->
                val allowed = request.resources.filter { resource ->
                    val permission = RESOURCE_PERMISSIONS[resource]
                    permission == null || permission in granted
                }
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }
        }

        // ----- File upload (<input type=file>)

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = callback
            val intent = params.createIntent()
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                startActivityForResult(
                    Intent.createChooser(intent, params.title ?: getString(R.string.choose_file)),
                    REQ_FILE_CHOOSER
                )
                true
            } catch (e: ActivityNotFoundException) {
                fileChooserCallback = null
                toast(R.string.no_file_picker)
                false
            }
        }

        // ----- Pop-up windows

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (isPopup) return false
            val popup = openPopup()
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (isPopup) closePopup()
        }

        // ----- Full-screen video / Fullscreen API

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            root.addView(
                view,
                root.indexOfChild(cursor.view),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            container.visibility = View.GONE
        }

        override fun onHideCustomView() = hideCustomView()

        // ----- JavaScript dialogs, titled with the app name instead of the page URL

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            dialog(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            dialog(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            val input = EditText(this@MainActivity).apply {
                setText(defaultValue.orEmpty())
                setSingleLine()
            }
            dialog(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (isFinishing) {
                result.confirm()
                return true
            }
            dialog(getString(R.string.leave_page))
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private fun dialog(message: String?): AlertDialog.Builder =
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.app_name)
            .setMessage(message)

    private fun openPopup(): WebView {
        closePopup()
        val popup = WebView(this)
        configureWebView(popup, allowPopups = false)
        popup.webViewClient = PopupWebViewClient()
        popup.webChromeClient = KdsChromeClient(isPopup = true)
        root.addView(
            popup,
            root.indexOfChild(cursor.view),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        popupWebView = popup
        popup.requestFocus()
        return popup
    }

    private fun closePopup() {
        val popup = popupWebView ?: return
        popupWebView = null
        root.removeView(popup)
        popup.destroy()
        webView?.requestFocus()
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customView = null
        root.removeView(view)
        container.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView?.requestFocus()
    }

    /** The view that remote-control pointer input goes to, or null when a native screen is showing. */
    private fun activeInputTarget(): View? = when {
        offlineView.visibility == View.VISIBLE || splash.visibility == View.VISIBLE -> null
        customView != null -> customView
        popupWebView != null -> popupWebView
        else -> webView
    }

    // ---------------------------------------------------------------- Errors / reload

    private fun onMainFrameError(@StringRes title: Int, @StringRes message: Int) {
        mainFrameFailed = true
        splash.visibility = View.GONE
        showOffline(true, title, message)
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun showOffline(
        show: Boolean,
        @StringRes title: Int = R.string.offline_title,
        @StringRes message: Int = R.string.offline_message
    ) {
        if (show) {
            offlineTitle.setText(title)
            offlineMessage.setText(message)
            offlineView.visibility = View.VISIBLE
            retryButton.requestFocus()
        } else {
            offlineView.visibility = View.GONE
            handler.removeCallbacks(retryRunnable)
            webView?.requestFocus()
        }
    }

    private fun reload() {
        handler.removeCallbacks(retryRunnable)
        val wv = webView ?: return
        val current = wv.url
        if (current.isNullOrEmpty() || !isInScope(current)) {
            wv.loadUrl(BuildConfig.START_URL)
        } else {
            wv.reload()
        }
    }

    // ---------------------------------------------------------------- Permissions

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Requests [permissions] (queued, one system dialog at a time); callback gets those granted. */
    private fun withPermissions(permissions: List<String>, callback: (Set<String>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || permissions.all(::isGranted)) {
            callback(permissions.filter(::isGranted).toSet())
            return
        }
        permissionQueue.addLast(PendingPermissions(permissions, callback))
        pumpPermissionQueue()
    }

    private fun pumpPermissionQueue() {
        if (permissionInFlight != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val next = permissionQueue.removeFirstOrNull() ?: return
        val missing = next.permissions.filterNot(::isGranted)
        if (missing.isEmpty()) {
            next.callback(next.permissions.toSet())
            pumpPermissionQueue()
            return
        }
        permissionInFlight = next
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val request = permissionInFlight ?: return
        permissionInFlight = null
        request.callback(request.permissions.filter(::isGranted).toSet())
        refreshNotificationPermission()
        pumpPermissionQueue()
    }

    /** Asks for everything the KDS may use once, on first launch, so service isn't interrupted later. */
    private fun requestInitialPermissions() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_PERMISSIONS, false)) {
            promptAutoStartIfNeeded()
            return
        }
        prefs.edit().putBoolean(KEY_ASKED_PERMISSIONS, true).apply()
        val permissions = LOCATION_PERMISSIONS + listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        withPermissions(permissions) { promptAutoStartIfNeeded() }
    }

    fun requestNotificationPermission(callback: (String) -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
        withPermissions(permissions) {
            refreshNotificationPermission()
            val state = notifier.permissionState()
            callback(if (state == "default") "denied" else state)
        }
    }

    private fun refreshNotificationPermission() {
        val wv = webView ?: return
        if (notifier.permissionState() == lastPermissionState) return
        updateStartScript(wv)
        bridge.pushPermissionState()
    }

    /**
     * Android 10+ only lets an app open itself at boot if it may "display over other apps".
     * Ask once; on TVs without that settings screen, explain the adb command instead.
     */
    private fun promptAutoStartIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_OVERLAY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_OVERLAY, true).apply()

        val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.autostart_title)
            .setMessage(getString(R.string.autostart_message, packageName))
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(settingsIntent)
                } catch (e: ActivityNotFoundException) {
                    toast(R.string.autostart_no_settings)
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // ---------------------------------------------------------------- Native features used by the bridge

    fun printPage() {
        val wv = popupWebView ?: webView ?: return
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            toast(R.string.print_unavailable)
            return
        }
        try {
            val jobName = getString(R.string.app_name)
            printManager.print(jobName, wv.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        } catch (e: Exception) {
            toast(R.string.print_unavailable)
        }
    }

    fun share(title: String, text: String, url: String): Boolean {
        val content = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, content)
            .putExtra(Intent.EXTRA_SUBJECT, title)
        return try {
            startActivity(Intent.createChooser(send, title.ifBlank { null }))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun handleDownload(view: WebView, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:") -> view.evaluateJavascript(
                "window.__kdsDownloadBlob&&window.__kdsDownloadBlob(${JSONObject.quote(url)},${JSONObject.quote(fileName)})",
                null
            )
            url.startsWith("data:") -> saveDataUrl(url, fileName)
            else -> withStoragePermission {
                if (DownloadHelper.enqueue(this, url, userAgent, fileName, mimeType)) {
                    toast(getString(R.string.download_started, fileName))
                } else {
                    toast(R.string.download_failed)
                }
            }
        }
    }

    fun saveDataUrl(dataUrl: String, fileName: String) = withStoragePermission {
        if (DownloadHelper.saveDataUrl(this, dataUrl, fileName)) {
            toast(getString(R.string.download_saved, fileName))
        } else {
            toast(R.string.download_failed)
        }
    }

    private fun withStoragePermission(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            block()
            return
        }
        withPermissions(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
            if (granted.isNotEmpty()) block() else toast(R.string.storage_permission_denied)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE_CHOOSER) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return
        }
        val clip = data?.clipData
        val uris = if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        callback.onReceiveValue(uris)
    }

    // ---------------------------------------------------------------- Notifications

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val tag = intent?.getStringExtra(KdsNotifier.EXTRA_NOTIFICATION_TAG) ?: return
        intent.removeExtra(KdsNotifier.EXTRA_NOTIFICATION_TAG)
        bridge.notificationClicked(tag)
    }

    // ---------------------------------------------------------------- Input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With REMOTE_POINTER=false the D-pad/OK keys go straight to the page's own remote navigation.
        if (BuildConfig.REMOTE_POINTER && cursor.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) cursor.onPointerInput(event.x, event.y)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        cursor.onPointerInput(event.x, event.y)
        return super.dispatchTouchEvent(event)
    }

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
        if (customView != null) {
            hideCustomView()
            return
        }
        popupWebView?.let { popup ->
            if (popup.canGoBack()) popup.goBack() else closePopup()
            return
        }
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
            toast(R.string.press_back_again)
        }
    }

    // ---------------------------------------------------------------- Window / lifecycle

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode() else cursor.reset()
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
        popupWebView?.onResume()
        refreshNotificationPermission()
    }

    override fun onPause() {
        cursor.reset()
        webView?.onPause()
        popupWebView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        closePopup()
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun toast(@StringRes message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val RETRY_DELAY_MS = 10_000L
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
        private const val MIN_WEBVIEW_MAJOR = 90
        private const val REQ_PERMISSIONS = 1
        private const val REQ_FILE_CHOOSER = 2
        private const val PREFS = "kds"
        private const val KEY_ASKED_PERMISSIONS = "asked_initial_permissions"
        private const val KEY_ASKED_OVERLAY = "asked_overlay_permission"
        private val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

        private val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        /** Web permission resource -> Android runtime permission. */
        private val RESOURCE_PERMISSIONS = mapOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO
        )
    }
}
