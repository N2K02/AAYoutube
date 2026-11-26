package com.aayoutube.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A second YouTube page for browsing while the video plays in the mini player (web process).
 *
 * It shares sign-in and settings with the player page but never plays the main video itself:
 * opening a video here sends it to the player page and expands it. It is created the first
 * time the mini player opens and paused whenever the app screen is hidden.
 */
open class BrowseSession(private val onSelected: () -> Unit = {}) {
    // Network requests are intercepted off-thread: do not call WebView.getUrl()
    // inside shouldInterceptRequest.
    @Volatile private var adPageHost: String? = "youtube.com"
    private lateinit var app: Application
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var handler: ScriptHandler? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRendererCrashAt = 0L
    private var rendererCrashCount = 0
    private var browseToken = ""
    private var browseDisplayId: Int? = null

    private fun browseScript(): String =
        "if(window.top===window.self)window.__AAY_BROWSE_TOKEN=" +
            org.json.JSONObject.quote(browseToken) + ";window.__AAY_BROWSE=true;\n" + WebHost.script()

    val created = MutableStateFlow(false)
    val revision = MutableStateFlow(0)

    open fun init(application: Application) {
        app = application
    }

    fun attach(context: Context): WebView {
        val displayId = runCatching { context.display?.displayId }.getOrNull() ?: android.view.Display.DEFAULT_DISPLAY
        if (web != null && browseDisplayId != displayId) {
            val previous = web!!
            val address = previous.url?.takeIf(WebHost::isSafePageUrl)
            runCatching { handler?.remove() }
            handler = null
            (previous.parent as? ViewGroup)?.removeView(previous)
            previous.destroy()
            web = null
            wrapper = null
            pendingUrl = address ?: pendingUrl
        }
        val w = web ?: create(context, displayId)
        wrapper?.setBaseContext(context)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(container: ViewGroup) {
        val mine = web?.parent === container
        container.removeAllViews()
        if (mine) wrapper?.setBaseContext(app)
    }

    fun canGoBack(): Boolean = web?.canGoBack() == true
    fun isAt(url: String): Boolean = BrowseNavigation.sameDestination(web?.url, url)

    /** Back inside the browse page; false when it has no history. */
    fun goBack(): Boolean {
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }

    /** Dispose the secondary WebView and its injected scripts during explicit Quit. */
    open fun release() {
        val previous = web
        web = null
        runCatching { handler?.remove() }
        handler = null
        (previous?.parent as? ViewGroup)?.removeView(previous)
        previous?.stopLoading()
        previous?.destroy()
        wrapper = null
        pendingUrl = null
        lastRendererCrashAt = 0L
        rendererCrashCount = 0
        created.value = false
        revision.value++
    }
    /** A second YouTube WebView must not keep a stale account UI after sign-out. */
    open fun onSessionCleared() {
        web?.clearCache(true)
        web?.loadUrl(WebHost.home())
    }

    /** Changes on Features also affect the browse page behind the mini player. */
    open fun applyFeatures(f: FeatureFlags, desktopChanged: Boolean) {
        val w = web ?: return
        w.settings.textZoom = if (browseDisplayId != android.view.Display.DEFAULT_DISPLAY) 100 else f.pageZoom
        if (desktopChanged) {
            w.settings.userAgentString = WebHost.userAgent(f.desktopSite)
            runCatching { handler?.remove() }
            handler = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                handler = WebViewCompat.addDocumentStartJavaScript(
                    w, browseScript(), setOf("https://*.youtube.com"),
                )
            }
            w.loadUrl(WebHost.home())
        } else {
            w.evaluateJavascript("window.__aay&&window.__aay.config(${f.toJson()})", null)
        }
    }


    /** Called when the mini player opens: start where the user came from. */
    fun navigate(url: String) {
        if (!WebHost.isSafePageUrl(url)) return
        val w = web
        if (w == null) {
            pendingUrl = url
            created.value = true
        } else if (!BrowseNavigation.sameDestination(w.url, url)) {
            w.loadUrl(url)
        } else {
            // Already there: scroll to top like YouTube's Home tab instead of reloading.
            w.evaluateJavascript("window.scrollTo(0,0)", null)
        }
    }

    fun showFrom(url: String) {
        if (!WebHost.isSafePageUrl(url)) return
        val w = web
        if (w == null) {
            pendingUrl = url
            created.value = true
        }
    }

    private var pendingUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(context: Context, displayId: Int): WebView {
        browseDisplayId = displayId
        val c = if (displayId == android.view.Display.DEFAULT_DISPLAY)
            MutableContextWrapper(context).also { wrapper = it } else context.also { wrapper = null }
        val w = WebView(c)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        // Compose hosts can leave the WebView unfocused; a text field tap then
        // never raises the keyboard. Take focus on the first touch.
        w.setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && !v.hasFocus()) v.requestFocus()
            false
        }
        browseToken = java.util.UUID.randomUUID().toString()
        w.setBackgroundColor(Color.BLACK)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true   // feed previews stay quiet
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.textZoom = if (displayId != android.view.Display.DEFAULT_DISPLAY) 100 else Features.current.pageZoom
        s.useWideViewPort = true
        // Car display: no overview zoom-out (tiny Home page) and no sticky pinch zoom.
        val carDisplay = displayId != android.view.Display.DEFAULT_DISPLAY
        s.loadWithOverviewMode = !carDisplay
        s.setSupportZoom(!carDisplay)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.userAgentString = WebHost.userAgent(Features.current.desktopSite)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        val token = browseToken
        w.addJavascriptInterface(BrowseBridge(token) { url ->
            main.post { if (web === w && browseToken == token) openInPlayer(url) }
        }, "AAYBrowse")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(
                w, browseScript(), setOf("https://*.youtube.com"),
            )
        }
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView, detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                if (web !== view) return true
                val now = SystemClock.elapsedRealtime()
                rendererCrashCount = if (now - lastRendererCrashAt <= 15_000L) rendererCrashCount + 1 else 1
                lastRendererCrashAt = now
                val retry = rendererCrashCount <= 2
                val address = view.url?.takeIf(WebHost::isSafePageUrl)
                AppLog.add("CRASH", "Browse renderer gone: crashed=${detail.didCrash()} count=$rendererCrashCount retry=$retry")
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { handler?.remove() }
                handler = null
                runCatching { view.destroy() }
                web = null
                wrapper = null
                pendingUrl = if (retry) address ?: WebHost.home() else null
                created.value = retry
                revision.value++
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https") return true
                if (isVideo(uri)) {
                    openInPlayer(uri.toString())
                    return true
                }
                return !WebHost.isSafePageUrl(uri.toString())
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!Features.current.blockAds) return null
                val uri = request.url
                return if (WebHost.isAdRequest(uri.host.orEmpty().lowercase(), adPageHost)) WebHost.emptyResponse else null
            }

            // YouTube changes pages without loading: catch a video page that slipped through.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = Uri.parse(url ?: return)
                adPageHost = uri.host
                if (isVideo(uri)) {
                    openInPlayer(url)
                    if (view.canGoBack()) view.goBack()
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                adPageHost = runCatching { Uri.parse(url).host }.getOrNull()
                if (handler == null && WebHost.isYouTubeHost(adPageHost))
                    view.evaluateJavascript(browseScript(), null)
            }
        }
        web = w
        w.loadUrl(pendingUrl?.takeIf(WebHost::isSafePageUrl) ?: WebHost.home())
        pendingUrl = null
        return w
    }

    private fun isVideo(uri: Uri): Boolean {
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        // Exact host or a real subdomain: "evilyoutube.com" must not count as YouTube.
        val youtube = host.equals("youtube.com", true) || host.endsWith(".youtube.com", true)
        return host.equals("youtu.be", true) ||
            (youtube && (path.startsWith("/watch") || path.startsWith("/shorts/") || path.startsWith("/live/")))
    }

    private val selections = SelectionDeduper()

    fun openInPlayer(url: String) {
        // Also called by the page script (AAYBrowse.open): accept only YouTube video links.
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !WebHost.isSafePageUrl(url) || !isVideo(uri)) {
            AppLog.add("NAV", "Ignored a request to open an unsafe or non-video link")
            return
        }
        // The page bridge, shouldOverrideUrlLoading and doUpdateVisitedHistory
        // can all report one tap. Only the first selection reaches the player.
        val id = uri.getQueryParameter("v") ?: uri.lastPathSegment.takeIf { uri.host.equals("youtu.be", true) }
        if (!selections.accept(SelectionDeduper.key(uri.path.orEmpty(), id, uri.getQueryParameter("list")),
                SystemClock.elapsedRealtime())) return
        main.post {
            AppLog.add("NAV", "Browse page opened a video")
            WebHost.openUrl(url)
            onSelected()
        }
    }
}

/** Independent browsing histories; both select into the same playback engine. */
object BrowseHost : BrowseSession() {
    val phoneMode = MutableStateFlow(WebHost.PlayerMode.FULL)
    val phone = BrowseSession { phoneMode.value = WebHost.PlayerMode.FULL }

    override fun init(application: Application) {
        super.init(application)
        phone.init(application)
    }
    override fun release() {
        phone.release()
        phoneMode.value = WebHost.PlayerMode.FULL
        super.release()
    }
    override fun applyFeatures(f: FeatureFlags, desktopChanged: Boolean) {
        super.applyFeatures(f, desktopChanged)
        phone.applyFeatures(f, desktopChanged)
    }
    override fun onSessionCleared() {
        super.onSessionCleared()
        phone.onSessionCleared()
    }

    /** Phone Home during projection: browse Home with the floating mini-player above it. */
    fun phoneHome() {
        phoneMode.value = ProjectedPhoneNav.home(WebHost.snapshot.value.id.isNotBlank())
        phone.navigate(WebHost.home())
    }
    fun phoneMini() {
        phoneMode.value = WebHost.PlayerMode.MINI
        phone.showFrom(WebHost.home())
    }
}

/** What the browse page script can call. */
class BrowseBridge(private val token: String, private val select: (String) -> Unit = {}) {
    @JavascriptInterface
    fun open(url: String, providedToken: String?) {
        if (providedToken == token) select(url)
    }
}
