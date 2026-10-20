package com.aayoutube.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
object BrowseHost {
    private lateinit var app: Application
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var handler: ScriptHandler? = null
    private val main = Handler(Looper.getMainLooper())

    val created = MutableStateFlow(false)

    fun init(application: Application) {
        app = application
    }

    fun attach(context: Context): WebView {
        val w = web ?: create()
        wrapper?.setBaseContext(context)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(container: ViewGroup) {
        val mine = web?.parent === container
        container.removeAllViews()
        if (mine) wrapper?.setBaseContext(app)
    }

    /** Back inside the browse page; false when it has no history. */
    fun goBack(): Boolean {
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack()
        return true
    }

    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }

    /** Called when the mini player opens: start where the user came from. */
    fun showFrom(url: String) {
        val w = web
        if (w == null) {
            pendingUrl = url
            created.value = true
        }
    }

    private var pendingUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val c = MutableContextWrapper(app)
        wrapper = c
        val w = WebView(c)
        w.setBackgroundColor(Color.BLACK)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true   // feed previews stay quiet
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.textZoom = Features.current.pageZoom
        s.userAgentString = WebHost.userAgent(Features.current.desktopSite)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(BrowseBridge(), "AAYBrowse")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(
                w, "window.__AAY_BROWSE=true;\n" + WebHost.script(), setOf("https://*.youtube.com"),
            )
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https" && uri.scheme != "http") return true
                if (isVideo(uri)) {
                    openInPlayer(uri.toString())
                    return true
                }
                return !WebHost.allowedHost(uri.host)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!Features.current.blockAds) return null
                val uri = request.url
                return if (WebHost.isAdRequest(uri.host.orEmpty().lowercase(), uri.path.orEmpty())) WebHost.emptyResponse else null
            }

            // YouTube changes pages without loading: catch a video page that slipped through.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = Uri.parse(url ?: return)
                if (isVideo(uri)) {
                    openInPlayer(url)
                    if (view.canGoBack()) view.goBack()
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (handler == null) view.evaluateJavascript("window.__AAY_BROWSE=true;\n" + WebHost.script(), null)
            }
        }
        web = w
        w.loadUrl(pendingUrl ?: WebHost.home())
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

    fun openInPlayer(url: String) {
        // Also called by the page script (AAYBrowse.open): accept only YouTube video links.
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !isVideo(uri)) {
            AppLog.add("NAV", "Ignored a request to open a non-video link: $url")
            return
        }
        main.post {
            AppLog.add("NAV", "Browse page opened a video: $url")
            WebHost.openUrl(url)
        }
    }
}

/** What the browse page script can call. */
class BrowseBridge {
    @JavascriptInterface
    fun open(url: String) = BrowseHost.openInPlayer(url)
}
