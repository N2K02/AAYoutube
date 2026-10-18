package com.aayoutube.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Owns the one WebView of the app. Lives in the ":web" process only.
 *
 * - The WebView is created lazily (the first command or screen needs it) and survives the
 *   screen closing, so audio keeps playing with the app swiped away or the screen off.
 * - It is created with the application context and re-based onto the activity while visible.
 * - The page script (assets/aay.js) reports what the video is doing; this class forwards it to the
 *   media process and carries out the media process's commands.
 */
object WebHost {

    private const val HOME_MOBILE = "https://m.youtube.com/"
    private const val HOME_DESKTOP = "https://www.youtube.com/"

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wrapper: MutableContextWrapper? = null
    private var web: WebView? = null
    private var scriptHandler: ScriptHandler? = null
    private var link: Messenger? = null
    private var frozen = false
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingUrl: String? = null
    private var lastUrl: String? = null
    private var lastDesktop = false
    private var jsSource: String? = null
    private var lastLinkRequest = 0L

    // What the screens show.
    val progress = MutableStateFlow(100)
    val offline = MutableStateFlow(false)
    val canGoBack = MutableStateFlow(false)
    val fullscreenView = MutableStateFlow<View?>(null)
    val snapshot = MutableStateFlow(Snap())
    val webVersion = MutableStateFlow(0)
    val inPip = MutableStateFlow(false)

    /** Set by the activity: asks the media service to link up with this process again. */
    var requestLink: (() -> Unit)? = null

    @Volatile var activityVisible = false
        private set

    /** The video is being shown on the car screen (see CarVideoService). */
    @Volatile var carShown = false
    val shown: Boolean get() = activityVisible || carShown

    fun init(application: Application) {
        app = application
        lastDesktop = Features.current.desktopSite
        scope.launch { Features.state.drop(1).collect { applyFeatures(it) } }
    }

    // ---------------------------------------------------------------- the WebView

    private fun home() = if (Features.current.desktopSite) HOME_DESKTOP else HOME_MOBILE

    private fun ensureWeb(): WebView {
        web?.let { return it }
        val context = MutableContextWrapper(app)
        wrapper = context
        val w = WebView(context)
        w.setBackgroundColor(Color.BLACK)
        configure(w)
        web = w
        installScript(w)
        val url = pendingUrl ?: home()
        pendingUrl = null
        w.loadUrl(url)
        return w
    }

    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = userAgent(Features.current.desktopSite)

        // Google refuses to sign in inside an "app WebView": a plain Chrome user agent (no "wv")
        // and no X-Requested-With header make it look like the normal browser.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(w, true)

        w.addJavascriptInterface(WebBridge(), "AAYNative")
        w.webViewClient = client
        w.webChromeClient = chrome
    }

    private fun userAgent(desktop: Boolean): String {
        val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(app))?.value ?: "Chrome/130.0.0.0"
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
        }
    }

    // ---------------------------------------------------------------- the page script

    private fun script(): String {
        val source = jsSource ?: app.assets.open("aay.js").bufferedReader().use { it.readText() }.also { jsSource = it }
        return "window.__AAY_CFG=" + Features.current.toJson() + ";\n" + source
    }

    private fun installScript(w: WebView) {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("https://*.youtube.com"))
        }
    }

    private fun applyFeatures(f: FeatureFlags) {
        val w = web ?: return
        if (f.desktopSite != lastDesktop) {
            lastDesktop = f.desktopSite
            w.settings.userAgentString = userAgent(f.desktopSite)
            installScript(w)
            w.loadUrl(home())
            return
        }
        installScript(w)
        js("window.__aay&&window.__aay.config(${f.toJson()})")
    }

    private fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    private fun cmd(name: String, arg: String? = null) {
        js("window.__aay&&window.__aay.cmd('$name'${if (arg == null) "" else ",$arg"})")
    }

    // ---------------------------------------------------------------- screens

    /** Called by the screen: hands over the WebView (re-based onto the activity). */
    fun attach(context: android.content.Context): WebView {
        val w = ensureWeb()
        wrapper?.setBaseContext(context)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(container: ViewGroup) {
        val mine = web?.parent === container
        container.removeAllViews()
        // Another screen (phone or car) may already have taken the page over; leave it alone then.
        if (mine) wrapper?.setBaseContext(app)
    }

    fun onActivityVisible(visible: Boolean) {
        noteVisibilityChange()
        activityVisible = visible
        if (visible) {
            main.removeCallbacks(freezeSoon)
            unfreeze()
        } else if (!snapshot.value.playing) {
            scheduleFreeze()
        }
        // Hidden screen = background mode: lowest video quality saves data and battery.
        js("window.__aay&&window.__aay.setBg(${!shown})")
    }

    /** Player buttons for the car screen: play, pause, next, prev. */
    fun cmd(name: String) {
        if (name == "pause") userPausedAt = SystemClock.elapsedRealtime()
        js("window.__aay&&window.__aay.cmd('$name')")
    }

    fun scrollBy(dx: Double, dy: Double) = js("window.scrollBy($dx,$dy)")

    fun showOnCar(on: Boolean) {
        carShown = on
        if (on) {
            main.removeCallbacks(freezeSoon)
            unfreeze()
        } else if (!snapshot.value.playing) {
            scheduleFreeze()
        }
        js("window.__aay&&window.__aay.setBg(${!shown})")
    }

    /** The screen is leaving the foreground (or came back): page pauses are ignored until it returns. */
    fun setHold(on: Boolean) {
        noteVisibilityChange()
        AppLog.add("VIEW", "Hold playback=$on")
        js("window.__aay&&window.__aay.setHold($on)")
    }

    fun pip(on: Boolean) = js("window.__aay&&window.__aay.pip($on)")

    fun openUrl(url: String) {
        unfreeze()
        load(normalise(url))
    }

    fun goBack() {
        web?.takeIf { it.canGoBack() }?.goBack()
    }

    fun reload() {
        offline.value = false
        val w = web ?: return
        if (w.url.isNullOrEmpty() || w.url == "about:blank") w.loadUrl(home()) else w.reload()
    }

    fun clearCache() {
        web?.clearCache(true)
    }

    /** Sign out and forget everything the page stored. */
    fun clearSession() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.loadUrl(home())
    }

    fun exitFullscreen() {
        val cb = customCallback
        customCallback = null
        fullscreenView.value = null
        cb?.onCustomViewHidden()
    }

    // ---------------------------------------------------------------- freeze / release

    // Nothing playing and no screen open: stop the page's timers and scripts after a short wait.
    private val freezeSoon = Runnable {
        if (!shown && !snapshot.value.playing) freeze()
    }

    private fun scheduleFreeze() {
        main.removeCallbacks(freezeSoon)
        main.postDelayed(freezeSoon, 45_000)
    }

    private fun freeze() {
        val w = web ?: return
        if (frozen) return
        frozen = true
        AppLog.add("POWER", "Page frozen")
        w.onPause()
        w.pauseTimers()
    }

    private fun unfreeze() {
        val w = web ?: return
        if (!frozen) return
        frozen = false
        AppLog.add("POWER", "Page resumed")
        w.onResume()
        w.resumeTimers()
    }

    /** Frees the WebView completely (nothing is playing and no screen is open). */
    fun release() {
        AppLog.add("POWER", "WebView released")
        exitFullscreen()
        val w = web ?: return
        web = null
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        (w.parent as? ViewGroup)?.removeView(w)
        w.stopLoading()
        w.destroy()
        wrapper = null
        frozen = false
        snapshot.value = Snap()
        webVersion.update { it + 1 }
    }

    /**
     * The media service let go. The page is kept (so you find it where you left it), just frozen
     * when idle. It is only freed on an explicit stop from the media service (Exit, or 15 minutes
     * paused with no screen open), or by Android if it needs the memory.
     */
    fun onLinkUnbound() {
        AppLog.add("LINK", "Media service disconnected")
        link = null
        if (!shown && !snapshot.value.playing) scheduleFreeze()
    }

    // ---------------------------------------------------------------- commands from the media process

    fun onCommand(m: Message) {
        val d = m.data ?: Bundle.EMPTY
        AppLog.add("LINK", "From media process: what=${m.what} data=${d.keySet().joinToString { k -> "$k=${d.get(k)}" }}")
        if (m.what != Msg.FREEZE) unfreeze()
        when (m.what) {
            Msg.REGISTER -> {
                link = m.replyTo
                if (snapshot.value.id.isNotEmpty()) send(Msg.STATE, snapshot.value.toBundle())
            }
            Msg.OPEN -> open(d.getString("id").orEmpty(), d.getLong("sec"))
            Msg.SEARCH -> search(d.getString("q").orEmpty())
            Msg.PLAY -> cmd("play")
            Msg.PAUSE -> { userPausedAt = SystemClock.elapsedRealtime(); cmd("pause") }
            Msg.SEEK -> cmd("seek", (d.getLong("ms") / 1000.0).toString())
            Msg.NEXT -> cmd("next")
            Msg.PREV -> cmd("prev")
            Msg.RATE -> cmd("rate", d.getFloat("rate", 1f).toString())
            Msg.STOP -> {
                cmd("pause")
                if (!shown) release()
            }
            Msg.FREEZE -> if (!shown && !snapshot.value.playing) freeze()
        }
    }

    private fun open(id: String, startSec: Long) {
        if (id.isBlank()) return
        val base = if (Features.current.desktopSite) "https://www.youtube.com" else "https://m.youtube.com"
        // aay=1 tells the page script to start playing as soon as the video is ready.
        load("$base/watch?v=${Uri.encode(id)}" + (if (startSec > 0) "&t=${startSec}s" else "") + "&aay=1")
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val base = if (Features.current.desktopSite) "https://www.youtube.com" else "https://m.youtube.com"
        // aay=play: the page script opens the first real video of the results and plays it.
        load("$base/results?search_query=${Uri.encode(query)}&aay=play")
    }

    private fun load(url: String) {
        val w = web
        if (w == null) {
            pendingUrl = url
            ensureWeb()
        } else {
            w.loadUrl(url)
        }
    }

    private fun normalise(url: String): String {
        val u = Uri.parse(url)
        val host = u.host?.lowercase().orEmpty()
        if (host == "youtu.be") {
            val id = u.lastPathSegment.orEmpty()
            val t = u.getQueryParameter("t")
            return "https://m.youtube.com/watch?v=$id" + (if (t != null) "&t=$t" else "")
        }
        return url
    }

    // ---------------------------------------------------------------- reports to the media process

    private fun send(what: Int, data: Bundle) {
        val l = link ?: return
        try {
            val m = Message.obtain(null, what)
            m.data = data
            l.send(m)
        } catch (e: RemoteException) {
            link = null
        }
    }

    /** Called on a WebView thread by [WebBridge]. */
    fun onJsState(json: String) {
        val s = runCatching {
            val o = JSONObject(json)
            Snap(
                id = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                art = o.optString("art"),
                playing = o.optBoolean("playing"),
                buffering = o.optBoolean("buffering"),
                ended = o.optBoolean("ended"),
                live = o.optBoolean("live"),
                ad = o.optBoolean("ad"),
                posMs = (o.optDouble("pos", 0.0) * 1000).toLong(),
                durMs = (o.optDouble("dur", 0.0) * 1000).toLong(),
                rate = o.optDouble("rate", 1.0).toFloat(),
                canNext = o.optBoolean("next"),
                canPrev = o.optBoolean("prev"),
            )
        }.getOrNull() ?: return
        main.post { publish(s) }
    }

    private var lastLogged = ""
    private var visibilityChangedAt = 0L
    private var userPausedAt = 0L
    private var resumeTries = 0

    /** The screen went to the background, into picture in picture, or came back. */
    fun noteVisibilityChange() {
        resumeTries = 0
        visibilityChangedAt = SystemClock.elapsedRealtime()
    }

    // The page sometimes pauses by itself when the screen changes state. If that happens right
    // after such a change, and nobody asked for a pause, carry on playing.
    private fun resumeIfDropped(old: Snap, new: Snap) {
        if (!Features.current.backgroundPlay) return
        if (!old.playing || new.playing || new.ended || new.id != old.id) return
        val now = SystemClock.elapsedRealtime()
        if (now - visibilityChangedAt < 4_000 && now - userPausedAt > 4_000 && resumeTries < 2) {
            resumeTries++
            AppLog.add("PLAY", "Paused by the page right after a screen change: resuming ($resumeTries/2)")
            cmd("play")
        }
    }

    private fun publish(s: Snap) {
        resumeIfDropped(snapshot.value, s)
        val key = "${s.id}|${s.playing}|${s.buffering}|${s.ad}|${s.ended}|${s.live}"
        if (key != lastLogged) {
            lastLogged = key
            AppLog.add("PLAY", "id=${s.id} \"${s.title}\" playing=${s.playing} buffering=${s.buffering} ad=${s.ad} ended=${s.ended} live=${s.live} pos=${s.posMs / 1000}s/${s.durMs / 1000}s rate=${s.rate} next=${s.canNext} prev=${s.canPrev}")
        }
        snapshot.value = s
        send(Msg.STATE, s.toBundle())
        if (!s.playing && !shown) scheduleFreeze()
        // The media service is not linked (it stopped while a screen was open): ask it to link again.
        if (link == null && s.playing) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLinkRequest > 5_000) {
                lastLinkRequest = now
                requestLink?.invoke()
            }
        }
    }

    // ---------------------------------------------------------------- WebView clients

    private fun allowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        // Google sign-in hops between many Google domains (google.co.uk, accounts.google.*, ...).
        if (GOOGLE_COUNTRY.matches(h)) return true
        return ALLOWED.any { h == it || h.endsWith(".$it") }
    }

    private fun isAdRequest(host: String, path: String): Boolean {
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (host.endsWith("youtube.com")) {
            return path.startsWith("/pagead/") || path.startsWith("/api/stats/ads") ||
                path.startsWith("/ptracking") || path.startsWith("/pcs/activeview") ||
                path.contains("/get_midroll_")
        }
        return false
    }

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            AppLog.add("NAV", "Navigate ${uri} main=${request.isForMainFrame} gesture=${request.hasGesture()} redirect=${request.isRedirect}")
            val scheme = uri.scheme.orEmpty()
            if (scheme != "http" && scheme != "https") return true
            val host = uri.host.orEmpty()
            if (host.equals("youtu.be", true)) {
                view.loadUrl(normalise(uri.toString()))
                return true
            }
            if (!allowedHost(host)) {
                AppLog.add("NAV", "Blocked host $host, opening outside the app=${request.isForMainFrame}")
                if (request.isForMainFrame) {
                    runCatching {
                        app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return true
            }
            if (Features.current.hideShorts) {
                val m = Regex("^/shorts/([\\w-]{6,})").find(uri.path.orEmpty())
                if (m != null) {
                    view.loadUrl("https://${host}/watch?v=${m.groupValues[1]}&aay=1")
                    return true
                }
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!Features.current.blockAds) return null
            val uri = request.url
            val ad = isAdRequest(uri.host.orEmpty().lowercase(), uri.path.orEmpty())
            if (ad) AppLog.add("ADS", "Blocked request ${uri.host}${uri.path}")
            return if (ad) emptyResponse else null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            AppLog.add("PAGE", "Started $url")
            progress.value = 5
            offline.value = false
            // Older WebViews cannot run a script before the page: inject as early as possible instead.
            if (scriptHandler == null) view.evaluateJavascript(script(), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            AppLog.add("PAGE", "Finished $url")
            progress.value = 100
            CookieManager.getInstance().flush()
            canGoBack.value = view.canGoBack()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canGoBack.value = view.canGoBack()
            if (!url.isNullOrEmpty() && url != "about:blank") lastUrl = url
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            AppLog.add("NET", "Error ${error.errorCode} ${error.description} for ${request.url} main=${request.isForMainFrame}")
            if (request.isForMainFrame) offline.value = true
        }

        // The page's renderer died (out of memory, crash): rebuild the WebView instead of crashing the app.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.add("CRASH", "Renderer gone, crashed=${detail.didCrash()} url=$lastUrl")
            val resume = lastUrl
            val visible = shown
            release()
            if (visible) {
                pendingUrl = resume
                webVersion.update { it + 1 }
            }
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            AppLog.add("JS", "${m.messageLevel()} ${m.message()} (${m.sourceId().orEmpty().substringAfterLast('/')}:${m.lineNumber()})")
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.value = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customCallback?.onCustomViewHidden()
            customCallback = callback
            fullscreenView.value = view
            AppLog.add("VIEW", "Fullscreen player opened")
        }

        override fun onHideCustomView() {
            customCallback = null
            fullscreenView.value = null
            AppLog.add("VIEW", "Fullscreen player closed")
        }

        // No grey play-button poster while a video loads.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
    }

    private val GOOGLE_COUNTRY = Regex("^([a-z0-9-]+\\.)*google\\.[a-z]{2,3}(\\.[a-z]{2})?$")

    private val ALLOWED = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "google.com", "gstatic.com",
        "googleusercontent.com", "googleapis.com", "recaptcha.net", "ytimg.com", "googlevideo.com", "ggpht.com", "gvt1.com",
    )

    private val BLOCKED_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    )
}

/** What the page script can call. Every method runs on a WebView thread, not the main thread. */
class WebBridge {
    @JavascriptInterface
    fun state(json: String) {
        WebHost.onJsState(json)
    }
}
