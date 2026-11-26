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
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * The original YouTube player on the phone while Android Auto owns the single
 * audible engine (WebHost). A muted YouTube watch page that follows the engine's
 * real media clock; taps on its own controls go to the engine. It decodes only
 * while it is visible, and is released when projection ends.
 */
internal object PhoneMirror {
    private lateinit var app: Application
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var handler: ScriptHandler? = null
    private var token = ""
    private var source: String? = null
    private var loadedId = ""
    private var visible = false
    private var videoOnly = false
    private var lastTouchAt = 0L
    private var lastLoadAt = 0L
    @Volatile private var pageHost: String? = "youtube.com"
    val revision = MutableStateFlow(0)

    fun init(application: Application) { app = application }

    private fun script(): String {
        val js = source ?: app.assets.open("mirror.js").bufferedReader().use { it.readText() }.also { source = it }
        return "if(window.top===window.self)window.__AAYM_TOKEN=" + JSONObject.quote(token) + ";\n" + js
    }

    fun attach(context: Context): WebView {
        val w = web ?: create(context)
        wrapper?.setBaseContext(context)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(container: ViewGroup) {
        val mine = web?.parent === container
        container.removeAllViews()
        if (mine) wrapper?.setBaseContext(app)
    }

    /** Shown (full, mini or PiP) or not. Hidden: no decoding, no sync. */
    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        val w = web ?: return
        if (on) {
            w.onResume()
            w.resumeTimers()
            main.removeCallbacks(syncTick)
            main.post(syncTick)
        } else {
            main.removeCallbacks(syncTick)
            w.evaluateJavascript("window.__aayMirror&&window.__aayMirror.stop()", null)
            w.onPause()
        }
    }

    /** Mini-player and PiP show only the video; full shows YouTube's watch page. */
    fun setVideoOnly(on: Boolean) {
        videoOnly = on
        web?.evaluateJavascript("window.__aayMirror&&window.__aayMirror.layout($on)", null)
    }

    fun release() {
        main.removeCallbacks(syncTick)
        val w = web
        web = null
        runCatching { handler?.remove() }
        handler = null
        (w?.parent as? ViewGroup)?.removeView(w)
        w?.stopLoading()
        w?.destroy()
        wrapper = null
        loadedId = ""
        visible = false
        revision.value++
    }

    /** Load the engine's video, then keep the clock in step. */
    private val syncTick: Runnable = object : Runnable {
        override fun run() {
            val w = web ?: return
            if (!visible) return
            val s = WebHost.snapshot.value
            val now = SystemClock.elapsedRealtime()
            if (s.id.isBlank()) {
                main.postDelayed(this, PhoneMirrorPolicy.IDLE_SYNC_MS)
                return
            }
            if (s.id != loadedId) {
                if (now - lastLoadAt >= PhoneMirrorPolicy.MIN_RELOAD_MS) {
                    loadedId = s.id
                    lastLoadAt = now
                    val url = PhoneMirrorPolicy.watchUrl(WebHost.home(), s.id, WebHost.currentPlaylist(), s.posMs)
                    if (url != null && WebHost.isSafePageUrl(url)) {
                        AppLog.add("MIRROR", "Phone player follows id=${s.id}")
                        w.loadUrl(url)
                    }
                }
                main.postDelayed(this, PhoneMirrorPolicy.SYNC_MS)
                return
            }
            WebHost.mediaClock { posMs, playing, rate ->
                if (web !== w || !visible) return@mediaClock
                w.evaluateJavascript(
                    "window.__aayMirror&&window.__aayMirror.sync($posMs,$playing,$rate)", null)
            }
            main.postDelayed(this, PhoneMirrorPolicy.SYNC_MS)
        }
    }

    /** From the mirror's own YouTube controls (trusted touches only). */
    private fun onControl(action: String, value: Long) {
        AppLog.add("MIRROR", "Phone player control=$action")
        when (action) {
            "play" -> WebHost.cmd("play")
            "pause" -> WebHost.cmd("pause")
            "seek" -> WebHost.seekTo(value)
        }
    }

    /** A different video on the phone page: a selection only if the user just tapped. */
    private fun onNavigatedTo(uri: Uri, alreadyShown: Boolean): Boolean {
        val id = uri.getQueryParameter("v") ?: return false
        if (id == loadedId || id == WebHost.snapshot.value.id) return false
        if (!PhoneMirrorPolicy.userSelection(lastTouchAt, SystemClock.elapsedRealtime())) {
            // YouTube's own autoplay on the muted page: go back to the engine's video.
            if (alreadyShown) loadedId = ""
            return false
        }
        AppLog.add("MIRROR", "Phone player selected a video")
        if (alreadyShown) loadedId = id
        WebHost.openUrl(uri.toString())
        return !alreadyShown
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(context: Context): WebView {
        val c = MutableContextWrapper(context).also { wrapper = it }
        val w = WebView(c)
        token = java.util.UUID.randomUUID().toString()
        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchAt = SystemClock.elapsedRealtime()
                if (!v.hasFocus()) v.requestFocus()
            }
            false
        }
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false // muted autoplay that follows the car
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.textZoom = Features.current.pageZoom
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.userAgentString = WebHost.userAgent(Features.current.desktopSite)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        val t = token
        w.addJavascriptInterface(MirrorBridge(t) { action, value ->
            main.post { if (web === w && token == t) onControl(action, value) }
        }, "AAYMirror")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("https://*.youtube.com"))
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme != "https" || !WebHost.isSafePageUrl(uri.toString())) return true
                return onNavigatedTo(uri, alreadyShown = false)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                pageHost = uri.host
                onNavigatedTo(uri, alreadyShown = true)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageHost = runCatching { Uri.parse(url).host }.getOrNull()
                if (handler == null && WebHost.isYouTubeHost(pageHost)) view.evaluateJavascript(script(), null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (videoOnly) view.evaluateJavascript("window.__aayMirror&&window.__aayMirror.layout(true)", null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!Features.current.blockAds) return null
                val host = request.url.host.orEmpty().lowercase()
                return if (WebHost.isAdRequest(host, pageHost)) WebHost.emptyResponse else null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web !== view) return true
                AppLog.add("CRASH", "Phone player renderer gone: crashed=${detail.didCrash()}")
                release()
                return true
            }
        }
        web = w
        loadedId = ""
        return w
    }
}

/** What the mirror page script can call. */
internal class MirrorBridge(private val token: String, private val onControl: (String, Long) -> Unit) {
    @JavascriptInterface
    fun control(action: String, value: Double, providedToken: String?) {
        if (providedToken != token || action !in PhoneMirrorPolicy.ACTIONS) return
        onControl(action, value.toLong().coerceAtLeast(0L))
    }
}

/** Pure decisions for the phone mirror (unit-tested). */
internal object PhoneMirrorPolicy {
    const val SYNC_MS = 1_500L
    const val IDLE_SYNC_MS = 3_000L
    const val MIN_RELOAD_MS = 1_500L
    const val USER_SELECTION_MS = 3_000L
    val ACTIONS = setOf("play", "pause", "seek")

    private fun safeToken(value: String?, lengths: IntRange) =
        value?.takeIf { v -> v.length in lengths && v.all { it.isLetterOrDigit() || it == '_' || it == '-' } }

    /** Null for anything that is not a plain YouTube video ID. */
    fun watchUrl(home: String, id: String, list: String?, posMs: Long): String? {
        val video = safeToken(id, 11..11) ?: return null
        val playlist = safeToken(list, 2..100)
        return home.trimEnd('/') + "/watch?v=" + video +
            (if (playlist != null) "&list=$playlist" else "") +
            (if (posMs >= 1_000L) "&t=${posMs / 1000}s" else "")
    }

    fun userSelection(lastTouchAt: Long, nowMs: Long): Boolean =
        lastTouchAt != 0L && nowMs - lastTouchAt in 0..USER_SELECTION_MS
}
