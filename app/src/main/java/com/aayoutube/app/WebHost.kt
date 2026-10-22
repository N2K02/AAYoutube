package com.aayoutube.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
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
    /** The player page shows a video (watch or live page). */
    val onWatchPage = MutableStateFlow(false)
    val fullscreenView = MutableStateFlow<View?>(null)
    val snapshot = MutableStateFlow(Snap())
    val webVersion = MutableStateFlow(0)
    val inPip = MutableStateFlow(false)

    /** Set by the activity: asks the media service to link up with this process again. */
    var requestLink: (() -> Unit)? = null

    /** Set by the open screen: closes it (Exit in the notification). */
    var onQuit: (() -> Unit)? = null

    @Volatile var activityVisible = false
        private set

    /** The video is being shown on the car screen (see CarVideoService). */
    @Volatile var carShown = false

    /** The app screen is on the car display (Android 15+): videos fill the screen there. */
    val carScreen = MutableStateFlow(false)
    val carDisplay: Boolean get() = carScreen.value

    fun setCarScreen(on: Boolean) {
        if (carScreen.value == on) return
        carScreen.value = on
        AppLog.add("VIEW", if (on) "On the car display: videos fill the screen" else "On the phone screen")
        web?.let { installScript(it) }
        js("window.__aay&&window.__aay.car($on)")
    }
    val shown: Boolean get() = activityVisible || carShown

    fun init(application: Application) {
        app = application
        lastDesktop = Features.current.desktopSite
        scope.launch { Features.state.drop(1).collect { applyFeatures(it) } }
    }

    // ---------------------------------------------------------------- the WebView

    internal fun home() = if (Features.current.desktopSite) HOME_DESKTOP else HOME_MOBILE

    private fun ensureWeb(): WebView {
        web?.let { return it }
        val context = MutableContextWrapper(app)
        wrapper = context
        val w = BackgroundWebView(context)
        w.setBackgroundColor(Color.BLACK)
        configure(w)
        web = w
        installScript(w)
        var url = pendingUrl ?: savedUrl() ?: home()
        // Reopening on the video we left: show it paused. Playing on its own is for Android Auto
        // and for videos you pick (those load with aay=1).
        // Only when the phone screen opens it: Android Auto (car display, media player) plays.
        val phoneScreen = activityVisible && !carDisplay && !carShown
        if (pendingUrl == null && phoneScreen && Uri.parse(url).path.orEmpty().let { it.startsWith("/watch") || it.startsWith("/live/") }) {
            url += (if (url.contains('?')) "&" else "?") + "aay=paused"
        }
        pendingUrl = null
        w.loadUrl(url)
        return w
    }

    // ---------------------------------------------------------------- resume last page

    private val webPrefs by lazy { app.getSharedPreferences("aay_web", Context.MODE_PRIVATE) }

    private fun savedUrl(): String? {
        if (!Features.current.resumeLast) return null
        val u = webPrefs.getString("lastUrl", null) ?: return null
        val host = Uri.parse(u).host.orEmpty()
        return u.takeIf { host.endsWith("youtube.com") }
    }

    private fun saveUrl(url: String) {
        val u = Uri.parse(url)
        if (!u.host.orEmpty().endsWith("youtube.com")) return
        // Without our autoplay flags: opening the app should not start a video on its own.
        val clean = u.buildUpon().clearQuery().apply {
            u.queryParameterNames.filter { it != "aay" }.forEach { k -> u.getQueryParameters(k).forEach { v -> appendQueryParameter(k, v) } }
        }.build().toString()
        webPrefs.edit().putString("lastUrl", clean).apply()
    }

    private fun configure(w: WebView) {
        val s = w.settings
        s.textZoom = Features.current.pageZoom
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

    internal fun userAgent(desktop: Boolean): String {
        val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(app))?.value ?: "Chrome/130.0.0.0"
        return if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
        }
    }

    // ---------------------------------------------------------------- the page script

    internal fun script(): String {
        val source = jsSource ?: app.assets.open("aay.js").bufferedReader().use { it.readText() }.also { jsSource = it }
        return "window.__AAY_CFG=" + Features.current.toJson() + ";window.__AAY_CAR=" + carDisplay + ";\n" + source
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
        w.settings.textZoom = f.pageZoom
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
        // Links reach here from the page script, other apps and Android Auto. loadUrl() skips
        // shouldOverrideUrlLoading, so check them here: only YouTube/Google pages, over http(s).
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || (scheme != "https" && scheme != "http") || !allowedHost(uri.host)) {
            AppLog.add("NAV", "Refused to open $url in the player: not a YouTube link")
            return
        }
        unfreeze()
        mode.value = PlayerMode.FULL
        load(normalise(url))
    }

    // ---------------------------------------------------------------- mini player

    /** FULL: the page fills the screen. MINI: small floating video over the browse page. HIDDEN: closed. */
    enum class PlayerMode { FULL, MINI, HIDDEN }

    val mode = MutableStateFlow(PlayerMode.FULL)

    fun isOnWatchPage(): Boolean =
        runCatching { Uri.parse(web?.url.orEmpty()).path.orEmpty() }.getOrDefault("").let {
            it.startsWith("/watch") || it.startsWith("/live/")
        }

    /** Swipe down on the video (or back while it plays): shrink it and browse YouTube underneath. */
    fun enterMini() {
        if (fullscreenView.value != null || !isOnWatchPage() || mode.value == PlayerMode.MINI) return
        AppLog.add("VIEW", "Mini player on")
        mode.value = PlayerMode.MINI
        // Like the YouTube app: Home underneath (or wherever you were browsing before).
        BrowseHost.showFrom(home())
        js("window.__aay&&window.__aay.mini(true)")
    }

    fun expandPlayer() {
        if (mode.value == PlayerMode.FULL) return
        AppLog.add("VIEW", "Mini player expanded")
        mode.value = PlayerMode.FULL
        js("window.__aay&&window.__aay.mini(false)")
    }

    fun closePlayer() {
        AppLog.add("VIEW", "Mini player closed")
        cmd("pause")
        mode.value = PlayerMode.HIDDEN
    }

    /** Where to start browsing when the video shrinks: the page you came from, else YouTube home. */
    fun browseStartUrl(): String {
        val list = web?.copyBackForwardList() ?: return home()
        for (i in list.currentIndex - 1 downTo 0) {
            val u = list.getItemAtIndex(i)?.url ?: continue
            val path = Uri.parse(u).path.orEmpty()
            if (!path.startsWith("/watch") && !path.startsWith("/shorts") && Uri.parse(u).host.orEmpty().endsWith("youtube.com")) return u
        }
        return home()
    }

    /** Goes back in the page. False when there is nowhere to go (already on YouTube home). */
    /** Opening the app from its icon: YouTube Home, unless something is playing. */
    fun goHomeIfIdle() {
        val w = web ?: return
        if (snapshot.value.playing || Features.current.resumeLast) return
        val path = runCatching { Uri.parse(w.url.orEmpty()).path.orEmpty() }.getOrDefault("")
        if (path.isEmpty() || path == "/") return
        AppLog.add("NAV", "App opened: YouTube home")
        mode.value = PlayerMode.FULL
        w.loadUrl(home())
    }

    fun goBack(): Boolean {
        val w = web ?: return false
        if (w.canGoBack()) {
            AppLog.add("NAV", "Back in page history")
            w.goBack()
            return true
        }
        val path = runCatching { Uri.parse(w.url.orEmpty()).path.orEmpty() }.getOrDefault("")
        if (path.isNotEmpty() && path != "/") {
            AppLog.add("NAV", "Back with no history: YouTube home")
            w.loadUrl(home())
            return true
        }
        return false
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
            Msg.QUIT -> {
                AppLog.add("LINK", "Exit")
                cmd("pause")
                val close = onQuit
                if (close != null) close() else release()
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
                queue = o.optJSONArray("queue")?.let { a ->
                    (0 until minOf(a.length(), 40)).mapNotNull { i ->
                        a.optJSONObject(i)?.let { q -> QItem(q.optString("id"), q.optString("t"), q.optString("a")) }
                    }.filter { it.id.isNotEmpty() }
                }.orEmpty(),
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

    internal fun allowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        // Google sign-in hops between many Google domains (google.co.uk, accounts.google.*, ...).
        if (GOOGLE_COUNTRY.matches(h)) return true
        return ALLOWED.any { h == it || h.endsWith(".$it") }
    }

    internal fun isAdRequest(host: String, path: String): Boolean {
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (host.endsWith("youtube.com")) {
            return path.startsWith("/pagead/") || path.startsWith("/api/stats/ads") ||
                path.startsWith("/ptracking") || path.startsWith("/pcs/activeview") ||
                path.contains("/get_midroll_")
        }
        return false
    }

    internal val emptyResponse: WebResourceResponse
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
            if (mode.value == PlayerMode.MINI) view.evaluateJavascript("window.__aay&&window.__aay.mini(true)", null)
            view.evaluateJavascript("window.__aay&&window.__aay.car($carDisplay)", null)
            AppLog.add("PAGE", "Finished $url")
            progress.value = 100
            CookieManager.getInstance().flush()
            canGoBack.value = view.canGoBack()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canGoBack.value = view.canGoBack()
            onWatchPage.value = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
                .let { it.startsWith("/watch") || it.startsWith("/live/") }
            if (!url.isNullOrEmpty() && url != "about:blank") {
                lastUrl = url
                saveUrl(url)
            }
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

        // Protected (Widevine) video is allowed, so paid/rented YouTube films can play; camera and microphone stay off.
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            AppLog.add("PERM", "Page asked for ${request.resources.joinToString()} -> granted ${drm.joinToString().ifEmpty { "nothing" }}")
            if (drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
        }
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

    /** Bottom navigation PiP action; never launch PiP on the car display. */
    @JavascriptInterface
    fun enterPip() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { MainActivity.pipRequester?.invoke() }
    }

    /** Swipe down on the playing video. */
    @JavascriptInterface
    fun mini() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { WebHost.enterMini() }
    }
}
