package com.aayoutube.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.view.Display
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
    private var webDisplayId: Int? = null
    private var webGeneration = 0
    private var scriptHandler: ScriptHandler? = null
    private var link: Messenger? = null
    private var linkEpoch = -1
    private var frozen = false
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingUrl: String? = null
    private var lastUrl: String? = null
    // Quality changes can reload a watch document; preserve its video position.
    private var sameVideoReloadPosition: Pair<String, Long>? = null
    private var lastDesktop = false
    private var jsSource: String? = null
    private var lastLinkRequest = 0L
    private var lastRendererCrashAt = 0L
    private var rendererCrashCount = 0

    // What the screens show.
    val progress = MutableStateFlow(100)
    val offline = MutableStateFlow(false)
    val canGoBack = MutableStateFlow(false)
    /** The player page shows a video (watch or live page). */
    val onWatchPage = MutableStateFlow(false)
    /** The selected /watch?v= ID, set by navigation rather than a delayed
     * MediaSession report. Used to prevent old lyrics from flashing on a new video. */
    val selectedWatchVideoId = MutableStateFlow("")
    // The WebView instance survives full document loads. Old JS bridge
    // callbacks can arrive after SPA/full navigation and must not revive the
    // previous video's notification or its recovery watchdog.
    private var mainFrameAllowsPlayback = true
    // A /watch route can change video ID without recreating the WebView. Use
    // the current history ID to reject queued JS reports from the old video.
    private var expectedWatchVideoId: String? = null
    val fullscreenView = MutableStateFlow<View?>(null)
    val snapshot = MutableStateFlow(Snap())
    internal val lyricsClock = MutableStateFlow(LyricsClock())
    private var lyricsTracking = false
    val youtubeAccount = MutableStateFlow(YouTubeAccountState.CHECKING)
    val webVersion = MutableStateFlow(0)
    val inPip = MutableStateFlow(false)

    /** Hide previous lyrics at the start of navigation, not after the old
     * document eventually reports its new media session. Do not change the
     * playback identity here: YouTube may still be transitioning. */
    private fun noteSelectedLyricsRoute(uri: Uri?) {
        val id = uri?.getQueryParameter("v")
            ?.takeIf { uri.path == "/watch" && it.isNotBlank() }.orEmpty()
        selectedWatchVideoId.value = id
    }

    /** Set by the activity: asks the media service to link up with this process again. */
    var requestLink: (() -> Unit)? = null

    /** Set by the open screen: closes it (Exit in the notification). */
    var onQuit: (() -> Unit)? = null

    /** A car-only launch may have no MainActivity to receive Quit. */
    var onCarQuit: (() -> Unit)? = null

    @Volatile var activityVisible = false
        private set

    /** The video is being shown on the car screen (see LegacyProjectionActivity). */
    @Volatile var carShown = false
        private set
    private val carOwners = mutableSetOf<String>()
    val carProjectionActive = MutableStateFlow(false)

    /** Android Auto may briefly run an old and a new car activity; only the newest owns the player. */
    private val carHosts = CarHostRegistry()
    val activeCarHost = MutableStateFlow<String?>(null)
    /** Identity of the car window token the current renderer draws into (null: not in a car window). */
    private var carWindowId: Int? = null
    private var playingWhenCarLeft = false
    private var carLeftAt = 0L

    /** The app screen is on the car display (Android 15+): videos fill the screen there. */
    val carScreen = MutableStateFlow(false)
    val carDisplay: Boolean get() = carScreen.value

    fun setCarScreen(on: Boolean) {
        if (carScreen.value == on) return
        carScreen.value = on
        AppLog.add("VIEW", if (on) "On the car display: videos fill the screen" else "On the phone screen")
        web?.let { installScript(it) }
        js("window.__aay&&window.__aay.car(${carDisplay || carShown})")
    }
    val shown: Boolean get() = activityVisible || carShown

    fun init(application: Application) {
        app = application
        lastDesktop = Features.current.desktopSite
        scope.launch { Features.state.drop(1).collect { applyFeatures(it) } }
    }

    // ---------------------------------------------------------------- the WebView

    internal fun home() = if (Features.current.desktopSite) HOME_DESKTOP else HOME_MOBILE

    private fun displayId(context: Context): Int? =
        runCatching { context.display?.displayId }.getOrNull()

    private fun ensureWeb(baseContext: Context = app): WebView {
        web?.let { return it }
        webDisplayId = displayId(baseContext) ?: Display.DEFAULT_DISPLAY
        // Unlike a phone WebView that stays alive offscreen, the projected car
        // view must use the real display context *directly*, matching AABrowser.
        // A mutable wrapper created on display 0 can cache the wrong IMM and
        // graphics services even after its base context is replaced.
        val inCarDisplay = webDisplayId != Display.DEFAULT_DISPLAY
        val context: Context = if (inCarDisplay) baseContext
            else MutableContextWrapper(baseContext).also { wrapper = it }
        if (inCarDisplay) wrapper = null
        // A brand-new renderer has not drawn into any car window yet.
        carWindowId = null
        val w = BackgroundWebView(context)
        AppLog.add("VIEW", "WebView created display=" + webDisplayId +
            " directCarContext=" + inCarDisplay)
        webGeneration++
        w.setBackgroundColor(Color.BLACK)
        configure(w)
        // WebView.pauseTimers() is process-wide. An older idle/frozen player
        // may have paused Chromium's shared timers before being disposed.
        // A fresh player must always have live script/media timers.
        w.resumeTimers()
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
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return null
        return u.takeIf { uri.scheme == "https" && isYouTubeHost(uri.host) }
    }

    private fun saveUrl(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || !isYouTubeHost(u.host)) return
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

        w.addJavascriptInterface(WebBridge(webGeneration), "AAYNative")
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
        return "window.__AAY_CFG=" + Features.current.toJson() + ";window.__AAY_CAR=" + (carDisplay || carShown) + ";\n" + source
    }

    private fun installScript(w: WebView) {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("https://*.youtube.com"))
        }
    }

    private fun applyFeatures(f: FeatureFlags) {
        val desktopChanged = f.desktopSite != lastDesktop
        lastDesktop = f.desktopSite
        // The mini-player BrowseHost shares the same features and YouTube session.
        // Update both WebViews instead of leaving the browse page on stale settings.
        BrowseHost.applyFeatures(f, desktopChanged)
        val w = web ?: return
        if (desktopChanged) {
            w.settings.userAgentString = userAgent(f.desktopSite)
            installScript(w)
            youtubeAccount.value = YouTubeAccountState.CHECKING
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
        if (name == "pause") unexpectedPauseRecoveryUntil = 0L
        js("window.__aay&&window.__aay.cmd('$name'${if (arg == null) "" else ",$arg"})")
    }

    // ---------------------------------------------------------------- screens

    /**
     * Unlike AABrowser, our WebView may have been born in the phone/background
     * process before the car screen appeared. Chromium caches the display's
     * IME and video Surface; rebasing MutableContextWrapper alone is too late.
     */
    fun attach(context: Context, destination: ViewGroup? = null): WebView {
        val targetDisplay = displayId(context)
        if (web != null && PlayerDisplayPolicy.shouldRecreate(webDisplayId, targetDisplay)) {
            recreateRenderer(context, targetDisplay!!, "display")
        }
        val w = ensureWeb(context)
        // Compose calls AndroidView.update on ordinary state changes. Detaching
        // the same decoder-bearing WebView from its *existing* car frame on
        // each update can present a permanent black surface.
        if (w.parent !== destination || destination == null) {
            wrapper?.setBaseContext(context)
            (w.parent as? ViewGroup)?.removeView(w)
        }
        // A phone/background screen now owns the renderer, not a car window.
        carWindowId = null
        AppLog.add("VIEW", "WebView attach display=" + (targetDisplay ?: -1) +
            " created=" + (webDisplayId ?: -1))
        return w
    }

    /**
     * The projected car path. AABrowser creates its WebView inside each
     * CarActivity window and never moves it. Android Auto replaces that window
     * (new Surface / activity recreate on the same display, see the 14:37 log),
     * and a renderer moved into the replacement keeps playing audio over black.
     * So a car bind keeps the renderer only inside the window it already draws
     * into; any other display or car window gets a fresh renderer at the same
     * video position and play state.
     */
    fun attachToCarWindow(context: Context, frame: ViewGroup): WebView {
        val targetDisplay = displayId(context)
        val targetWindow = frame.windowToken?.let { System.identityHashCode(it) }
        val reason = if (web == null) null else PlayerDisplayPolicy.carRecreateReason(
            webDisplayId, targetDisplay, carWindowId, targetWindow)
        if (reason != null) recreateRenderer(context, targetDisplay ?: -1, reason)
        val w = ensureWeb(context)
        if (w.parent !== frame) (w.parent as? ViewGroup)?.removeView(w)
        carWindowId = targetWindow
        AppLog.add("CAR", "Car WebView bind display=" + (targetDisplay ?: -1) +
            " created=" + (webDisplayId ?: -1) + " window=" +
            (targetWindow?.let { Integer.toHexString(it) } ?: "none") +
            " renderer=" + (reason?.let { "new($it)" } ?: "kept"))
        return w
    }

    fun claimCarHost(token: String, rebind: () -> Unit) {
        carHosts.claim(token, rebind)
        activeCarHost.value = carHosts.active
        AppLog.add("CAR", "Car host active=" + token + " hosts=" + carHosts.size)
    }

    /** Do not let an old activity's late onStart steal a replacement's window. */
    fun resumeCarHost(token: String, rebind: () -> Unit): Boolean {
        val registered = carHosts.resume(token, rebind)
        activeCarHost.value = carHosts.active
        if (!registered) AppLog.add("CAR", "Ignoring stale car host onStart=" + token +
            " active=" + (carHosts.active ?: "none"))
        return registered
    }

    /** An old car activity's teardown must not take the player from the newer one. */
    fun releaseCarHost(token: String) {
        val wasActive = carHosts.isActive(token)
        val next = carHosts.release(token)
        activeCarHost.value = carHosts.active
        if (wasActive) AppLog.add("CAR", "Car host released=" + token +
            " next=" + (carHosts.active ?: "none"))
        next?.invoke()
    }

    fun isActiveCarHost(token: String): Boolean = carHosts.isActive(token)

    private fun recreateRenderer(context: Context, target: Int, reason: String) {
        val previous = web ?: return
        val state = snapshot.value
        val oldDisplay = webDisplayId
        val url = previous.url?.takeIf { it.startsWith("https://") } ?: lastUrl ?: home()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val isWatch = uri?.path.orEmpty().let { it.startsWith("/watch") || it.startsWith("/live/") }
        val now = SystemClock.elapsedRealtime()
        // The old car window's teardown can pause the page a moment before the
        // new window binds. Keep the user's play intent unless they paused.
        // A Chromium video renderer can pause just before Android Auto replaces
        // its window. Do not turn an active recovery into a permanently paused
        // new document: the new page would otherwise lose its watchdog intent.
        val play = CarWindowPlaybackIntent.shouldAutoplay(state, playbackWanted,
            playingWhenCarLeft && now - carLeftAt < 8_000 &&
                now - userPausedAt > 8_000)
        val restored = if (isWatch && state.id.isNotBlank()) {
            uri!!.buildUpon().clearQuery().apply {
                uri.queryParameterNames.filterNot { it == "t" || it == "aay" }.forEach { key ->
                    uri.getQueryParameters(key).forEach { value -> appendQueryParameter(key, value) }
                }
                if (state.posMs > 0) appendQueryParameter("t", "${state.posMs / 1000}s")
                appendQueryParameter("aay", if (play) "1" else "paused")
            }.build().toString()
        } else url
        AppLog.add("CAR", "Recreate Chromium $reason $oldDisplay -> $target" +
            " playing=$play position=${state.posMs / 1000}s")
        // A display transition is not Quit. Preserve car ownership, MediaSession,
        // snapshot and cookies; replace only the renderer and its cached services.
        exitFullscreen()
        web = null
        webGeneration++
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        (previous.parent as? ViewGroup)?.removeView(previous)
        previous.stopLoading()
        previous.destroy()
        wrapper = null
        webDisplayId = null
        frozen = false
        pendingUrl = restored
        val replacement = ensureWeb(context)
        replacement.onResume()
        replacement.resumeTimers()
        replacement.invalidate()
    }


    /**
     * Diagnose a genuinely black car page without mistaking the framework's
     * nonfatal InputMethodManager fallback warning for a decoder failure.
     * Logs only geometry/readyState/frame counters; never URLs or page text.
     */
    fun logCarRenderProbe() {
        val w = web ?: return
        if (!carShown && !carDisplay) return
        AppLog.add("CAR", "WebView geometry=" + w.width + "x" + w.height +
            " attached=" + w.isAttachedToWindow + " shown=" + w.isShown +
            " accelerated=" + w.isHardwareAccelerated +
            " display=" + (w.display?.displayId ?: -1))
        // Also reports the element painted at the screen centre (a page overlay
        // covering the video), the real page visibility and whether frames
        // keep decoding, so a black car screen can be classified from logcat.
        w.evaluateJavascript(
            """(function(){
                // Reconnects and SPA navigation can drop the document's car class.
                // Repair that state on the attached car renderer before probing.
                if(window.__aay && !window.__aay.isCar) window.__aay.car(true);
                function d(n){if(!n)return null;var c=(typeof n.className==='string'?n.className:'')
                    .trim().split(/\s+/).slice(0,2).join('.');
                    return (n.tagName||'').toLowerCase()+(n.id?'#'+n.id:'')+(c?'.'+c:'');}
                var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
                var r=v&&v.getBoundingClientRect();
                var top=null,inPlayer=null;
                try{var e=document.elementFromPoint(innerWidth/2,innerHeight/2);top=d(e);
                    var p=document.getElementById('movie_player')||(v&&v.parentElement);
                    inPlayer=!!(e&&p&&(p===e||p.contains(e)||e.contains(p)));}catch(x){}
                var hidden=null;
                try{hidden=Object.getOwnPropertyDescriptor(Document.prototype,'hidden').get.call(document);}catch(x){}
                return JSON.stringify({
                    ready:document.readyState,
                    viewport:[innerWidth,innerHeight],
                    hasVideo:!!v,
                    videoSize:v?[v.videoWidth,v.videoHeight]:null,
                    bounds:r?[Math.round(r.width),Math.round(r.height)]:null,
                    frames:v?(v.webkitDecodedFrameCount||0):0,
                    dropped:v?(v.webkitDroppedFrameCount||0):0,
                    t:v?Math.round(v.currentTime):null,
                    paused:v?v.paused:null,
                    vis:v?getComputedStyle(v).visibility:null,
                    hidden:hidden,
                    car:document.documentElement.classList.contains('aay-car'),
                    carRequested:!!(window.__aay&&window.__aay.isCar),
                    mini:!!(window.__aay&&window.__aay.isMini),
                    quality:(function(){var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');
                        try{return p&&p.getPlaybackQuality?p.getPlaybackQuality():null}catch(e){return null}})(),
                    top:top,
                    inPlayer:inPlayer
                });
            })()""".trimIndent()
        ) { result ->
            AppLog.add("CAR", "Chromium geometry probe=" + result.take(500))
        }
        // What is painted over the video: the hit-test stack at the centre down to
        // the <video>, plus any full-screen opaque fixed/absolute box that does not
        // contain it (hit-testing skips pointer-events:none covers). No page text.
        w.evaluateJavascript(
            """(function(){
                function d(n){if(!n)return '?';var c=(typeof n.className==='string'?n.className:'')
                    .trim().split(/\s+/).slice(0,2).join('.');
                    return (n.tagName||'').toLowerCase()+(n.id?'#'+n.id:'')+(c?'.'+c:'');}
                function bg(c){var b=c.backgroundColor;
                    return (b==='rgba(0, 0, 0, 0)'||b==='transparent')?'':' bg='+b.replace(/rgba?\(|\)|\s/g,'');}
                function info(n,c){return d(n)+(c.position!=='static'?' '+c.position:'')+
                    (c.zIndex!=='auto'?' z'+c.zIndex:'')+(c.opacity!=='1'?' op'+c.opacity:'')+
                    (c.visibility!=='visible'?' '+c.visibility:'')+bg(c);}
                var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
                var out=[],s=[];
                try{s=document.elementsFromPoint(innerWidth/2,innerHeight/2);}catch(x){}
                for(var i=0;i<s.length&&out.length<8;i++){out.push(info(s[i],getComputedStyle(s[i])));if(s[i]===v)break;}
                var covers=[],all=document.querySelectorAll('body *');
                for(var j=0;j<all.length&&covers.length<4;j++){var e=all[j];
                    if(e===v||(v&&e.contains(v)))continue;
                    var c=getComputedStyle(e);
                    if((c.position!=='fixed'&&c.position!=='absolute')||c.display==='none'||c.visibility!=='visible'||!bg(c))continue;
                    var r=e.getBoundingClientRect();
                    if(r.width<innerWidth*0.9||r.height<innerHeight*0.9)continue;
                    covers.push(info(e,c));}
                return 'stack='+out.join(' > ')+' | covers='+(covers.join(' ; ')||'none')+
                    ' | guarded='+document.querySelectorAll('.aay-car-off,.aay-car-clear').length;
            })()""".trimIndent()
        ) { result ->
            AppLog.add("CAR", "Chromium paint probe=" + result.take(900))
        }
    }

    // Also probe after the car page navigates to a video, not only after a bind.
    private val carWatchProbe = Runnable { logCarRenderProbe() }

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
        // Preserve the user's saved playback resolution; only an explicit battery-saving
        // setting may lower quality while fully backgrounded (never mini-player or PiP).
        refreshBackgroundQuality()
    }

    /** Player buttons for the car screen: play, pause, next, prev. */
    fun cmd(name: String) {
        if (name == "pause") {
            playbackWanted = false
            userPausedAt = SystemClock.elapsedRealtime()
            unexpectedPauseRecoveryUntil = 0L
            if (snapshot.value.id.isNotBlank())
                publish(snapshot.value.copy(playing = false, buffering = false, userPaused = true))
        } else if (name == "play") {
            playbackWanted = true
            snapshot.value = snapshot.value.copy(userPaused = false)
        }
        js("window.__aay&&window.__aay.cmd('$name')")
    }

    fun scrollBy(dx: Double, dy: Double) = js("window.scrollBy($dx,$dy)")

    fun showOnCar(on: Boolean, owner: String = "legacy-projection") {
        // AABrowser exposes classic projection and wide surfaces independently.
        // A temporary car/phone handoff must not freeze or reset the shared player.
        val alreadyProjected = carShown
        if (on) carOwners.add(owner) else carOwners.remove(owner)
        carShown = carOwners.isNotEmpty()
        if (alreadyProjected && !carShown) {
            playingWhenCarLeft = snapshot.value.playing
            carLeftAt = SystemClock.elapsedRealtime()
        }
        carProjectionActive.value = carShown
        AppLog.add("CAR", "Projection ${owner}=" + on + " active=" + carOwners.size)
        js("window.__aay&&window.__aay.car(${carDisplay || carShown})")
        if (carShown) {
            main.removeCallbacks(freezeSoon)
            if (!alreadyProjected) noteVisibilityChange()
            unfreeze()
            // Android can call WebView.onPause while moving between phone and
            // projected windows. Explicitly restore the native renderer (as
            // upstream AABrowser does), even when 'frozen' was already false.
            web?.let { it.onResume(); it.resumeTimers(); it.invalidate() }
        } else if (!snapshot.value.playing) {
            scheduleFreeze()
        }
        refreshBackgroundQuality()
    }

    /** Rebind an already-created phone AndroidView after a car window relinquishes it.
     * Even a stopped Activity needs the version bump so its retained Compose tree will
     * reclaim the WebView when it resumes. Never steal from a second active car host. */
    fun requestReattachment() {
        if (!carShown) webVersion.update { it + 1 }
    }

    /** The screen is leaving the foreground (or came back): page pauses are ignored until it returns. */
    fun setHold(on: Boolean) {
        noteVisibilityChange()
        AppLog.add("VIEW", "Hold playback=$on")
        js("window.__aay&&window.__aay.setHold($on)")
    }

    /** A car video tap must surface YouTube's native quality menu and seeker.
     * Only act in the full-size projected player; never disturb a mini video. */
    fun showCarControls() {
        if (!carShown || mode.value != PlayerMode.FULL || !isOnWatchPage()) return
        js("window.__aay&&window.__aay.showCarControls&&window.__aay.showCarControls()")
    }

    fun pip(on: Boolean) = js("window.__aay&&window.__aay.pip($on)")

    /** Use the measured native video/WebView bounds as the Android PiP source. */
    fun pipSourceRect(out: android.graphics.Rect): Boolean {
        // Only a real, full-size player is a valid transition origin.
        // The internal 224dp mini window must never seed Android's PiP bounds.
        if (mode.value != PlayerMode.FULL || !isOnWatchPage()) return false
        val w = web ?: return false
        if (!w.isAttachedToWindow || !w.getGlobalVisibleRect(out)) return false
        val density = w.resources.displayMetrics.density
        // Ignore stale/1px rectangles while Activity and car displays reparent.
        return StandardPipPolicy.isValidSource(out.width(), out.height(), density)
    }

    fun refreshBackgroundQuality() {
        js("window.__aay&&window.__aay.setBg(${!shown && !inPip.value})")
    }

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
        load(withCarPlaybackIntent(normalise(url)))
    }

    // ---------------------------------------------------------------- mini player

    /** FULL: the page fills the screen. MINI: small floating video over the browse page. HIDDEN: closed. */
    enum class PlayerMode { FULL, MINI, HIDDEN }

    val mode = MutableStateFlow(PlayerMode.FULL)

    fun isOnWatchPage(): Boolean {
        val route = runCatching { Uri.parse(web?.url.orEmpty()).path.orEmpty() }.getOrDefault("")
        // A YouTube SPA video may already be playing before WebView.url/history changes.
        // The media snapshot is authoritative during that brief navigation gap.
        return route.startsWith("/watch") || route.startsWith("/live/") ||
            onWatchPage.value || (snapshot.value.playing && snapshot.value.id.isNotBlank())
    }

    /** Called only for explicit player-quality menu picks, never for adaptive playback changes. */
    fun requestQualityFromBridge(quality: String, generation: Int) {
        if (!VideoQualityPolicy.isSupported(quality)) return
        main.post {
            if (generation != webGeneration || quality == Features.current.preferredQuality) return@post
            AppLog.add("PLAY", "Remember user-selected video quality=$quality")
            Features.update { it.copy(preferredQuality = quality) }
        }
    }

    /** Swipe down on the video (or back while it plays): shrink it and browse YouTube underneath. */
    fun requestMiniFromBridge(generation: Int) {
        main.post { if (generation == webGeneration) enterMini() }
    }

    fun enterMini() {
        if (fullscreenView.value != null || !isOnWatchPage() || mode.value == PlayerMode.MINI) return
        AppLog.add("VIEW", "Mini player on (playing=${snapshot.value.playing})")
        // Arm page-side pause protection *before* changing the WebView viewport. YouTube can
        // pause during the resize, before Compose finishes drawing the mini player.
        noteVisibilityChange()
        js("window.__aay&&window.__aay.mini(true)")
        mode.value = PlayerMode.MINI
        // Keep the same player WebView and browse independently underneath it.
        BrowseHost.showFrom(home())
    }

    /** Undo the phone mini-player viewport before projecting the same renderer.
     * Never reload, seek or force play; respect the user's paused state. */
    fun prepareForCarProjection() {
        val wasMini = mode.value == PlayerMode.MINI
        if (mode.value != PlayerMode.FULL) {
            AppLog.add("CAR", "Expand video from " + mode.value)
            noteVisibilityChange()
            mode.value = PlayerMode.FULL
            BrowseHost.pause()
        }
        if (wasMini) js("window.__aay&&window.__aay.mini(false)")
        js("window.__aay&&window.__aay.car(true)")
    }

    fun refitCarViewport() {
        if (!carShown) return
        // Android Auto sometimes replaces its window while the Chromium document
        // still believes it is on the phone. Reassert the car owner without a reload.
        // Never let a host resize turn the user's mini player back into fullscreen.
        if (mode.value == PlayerMode.MINI) {
            js("window.__aay&&(window.__aay.car(true),window.__aay.mini(true))")
        } else if (mode.value == PlayerMode.FULL) {
            js("window.__aay&&(window.__aay.car(true),window.__aay.refitCar&&window.__aay.refitCar())")
        }
    }

    fun expandPlayer() {
        if (mode.value == PlayerMode.FULL) return
        AppLog.add("VIEW", "Mini player expanded")
        noteVisibilityChange()
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
            if (!path.startsWith("/watch") && !path.startsWith("/shorts") &&
                Uri.parse(u).let { it.scheme == "https" && isYouTubeHost(it.host) }) return u
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

    /** Only poll the real video position while the user is viewing timed lyrics. */
    fun setLyricsTracking(active: Boolean) {
        if (lyricsTracking == active) return
        lyricsTracking = active
        AppLog.add("LYRICS", "Video clock tracking=" + active)
        if (!active) lyricsClock.value = LyricsClock()
        js("window.__aay&&window.__aay.lyricsSync($active)")
    }

    /** Invoked from the WebView's JavaScript bridge thread, never by another app. */
    fun onJsLyricsTime(id: String, seconds: Double, playing: Boolean, buffering: Boolean, rate: Double, generation: Int = webGeneration) {
        if (generation != webGeneration) return
        if (!seconds.isFinite() || seconds < 0 || seconds > 86_400 ||
            !rate.isFinite() || rate <= 0 || rate > 8.0) return
        main.post {
            // Reject a sample from a previous video or a disposed lyrics panel.
            if (generation != webGeneration || !lyricsTracking || id.isBlank() || id != snapshot.value.id) return@post
            lyricsClock.value = LyricsClock(
                videoId = id,
                posMs = (seconds * 1000).toLong(),
                playing = playing,
                buffering = buffering,
                rate = rate.toFloat(),
                sampledAtMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    fun clearCache() {
        web?.clearCache(true)
    }

    /** Sign out and forget everything the page stored, including the mini-player browser.
     * Cookie removal is asynchronous: reloading before it finishes could sign in again. */
    fun clearSession() {
        youtubeAccount.value = YouTubeAccountState.SIGNED_OUT
        val generation = webGeneration
        val page = web
        CookieManager.getInstance().removeAllCookies {
            main.post {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                // CookieManager's completion may arrive after Quit, renderer
                // replacement, or a car/phone handoff. Never navigate a new
                // page using a callback belonging to the old one.
                // The secondary browse page shares cookies, so invalidate it
                // regardless of which player renderer owned the sign-out.
                BrowseHost.onSessionCleared()
                if (generation != webGeneration || web !== page) {
                    AppLog.add("ACCOUNT", "Ignored delayed sign-out navigation for replaced WebView")
                    return@post
                }
                page?.clearCache(true)
                page?.loadUrl(home())
            }
        }
    }

    /** A deliberate pause from YouTube's native page controls is authoritative,
     * including while in PiP/mini/car. Bridge calls arrive off the main thread. */
    fun onJsUserPause(generation: Int) {
        if (generation != webGeneration) return
        main.post {
            if (generation != webGeneration) return@post
            playbackWanted = false
            userPausedAt = SystemClock.elapsedRealtime()
            unexpectedPauseRecoveryUntil = 0L
            // The player may be disconnected from the media process when the user
            // pauses YouTube itself. Save the authoritative intent for the next SYNC.
            if (snapshot.value.id.isNotBlank())
                publish(snapshot.value.copy(playing = false, buffering = false, userPaused = true))
            AppLog.add("PLAY", "Explicit pause from YouTube player controls")
            if (!shown) scheduleFreeze()
        }
    }

    /** Called by the injected script, using YouTube's own LOGGED_IN flag. */
    fun reportAccountStatus(value: String, generation: Int = webGeneration) {
        if (generation != webGeneration) return
        val state = YouTubeAccountState.fromScript(value) ?: return
        main.post { if (generation == webGeneration) youtubeAccount.value = state }
    }

    fun exitFullscreen() {
        val cb = customCallback
        customCallback = null
        fullscreenView.value = null
        cb?.onCustomViewHidden()
    }

    // ---------------------------------------------------------------- freeze / release

    // Nothing playing and no screen open: stop the page's timers and scripts after a short wait.
    private fun keepRecovering(): Boolean = playbackWanted &&
        (Features.current.backgroundPlay || carShown || inPip.value || shown)

    private val freezeSoon = Runnable {
        if (!shown && !snapshot.value.playing && !keepRecovering()) freeze()
    }

    private fun scheduleFreeze() {
        main.removeCallbacks(freezeSoon)
        // A previously playing stream may have dropped silently. Leave the
        // watchdog active until Play succeeds, the user pauses, or Stop/Quit.
        // Background play OFF still permits the normal idle freeze offscreen.
        if (keepRecovering()) return
        // Do not pause the WebView's own timers while it is attempting to recover
        // a spontaneous background pause. A deliberate user pause remains fast-idle.
        val recoveryMs = (unexpectedPauseRecoveryUntil - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        main.postDelayed(freezeSoon, maxOf(45_000L, recoveryMs))
    }

    private fun freeze() {
        val w = web ?: return
        if (frozen) return
        frozen = true
        AppLog.add("POWER", "Page frozen")
        (w as? BackgroundWebView)?.pauseForIdle()
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
    fun release(preserveCarOwners: Boolean = false) {
        AppLog.add("POWER", "WebView released")
        main.removeCallbacks(freezeSoon)
        exitFullscreen()
        val w = web
        web = null
        webGeneration++ // Ignore already-queued bridge reports from the disposed renderer.
        sameVideoReloadPosition = null
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        (w?.parent as? ViewGroup)?.removeView(w)
        w?.stopLoading()
        // Do not leave Chromium's process-wide timers paused after disposing
        // this WebView: Android Auto can request Play with no Activity present.
        // destroy() releases page work; pauseTimers() here would also freeze a
        // surviving BrowseHost and any subsequent newly created player.
        w?.resumeTimers()
        w?.destroy()
        wrapper = null
        webDisplayId = null
        carWindowId = null
        frozen = false
        snapshot.value = Snap()
        selectedWatchVideoId.value = ""
        mainFrameAllowsPlayback = false
        expectedWatchVideoId = null
        if (!preserveCarOwners) {
            // A stopped/idle renderer has no live video. Inform Android Auto
            // so its next Play comes from the saved last selection, not a
            // stale page identity or a phantom BUFFERING state.
            send(Msg.STATE, Snap().toBundle())
            playbackWanted = false
            carOwners.clear()
            carShown = false
            carProjectionActive.value = false
            carHosts.clear()
            activeCarHost.value = null
        }
        lyricsTracking = false
        lyricsClock.value = LyricsClock()
        youtubeAccount.value = YouTubeAccountState.CHECKING
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
        linkEpoch = -1
        if (!shown && !snapshot.value.playing) scheduleFreeze()
    }

    // ---------------------------------------------------------------- commands from the media process

    fun onCommand(m: Message) {
        val d = m.data ?: Bundle.EMPTY
        AppLog.add("LINK", "From media process: what=${m.what} keys=${d.keySet().sorted().joinToString()}")
        if (m.what != Msg.FREEZE) unfreeze()
        when (m.what) {
            Msg.REGISTER -> {
                link = m.replyTo
                linkEpoch = d.getInt("epoch", -1)
                // The service resets its reconnect budget only after this acknowledgement.
                send(Msg.REGISTER_ACK, Bundle().apply { putInt("epoch", linkEpoch) })
            }
            Msg.SYNC -> {
                if (web == null) send(Msg.PLAYER_MISSING, Bundle())
                else send(Msg.STATE, snapshot.value.toBundle())
            }
            Msg.OPEN -> open(d.getString("id").orEmpty(), d.getLong("sec"))
            Msg.SEARCH -> search(d.getString("q").orEmpty())
            Msg.PLAY -> {
                if (web == null || !mainFrameAllowsPlayback) {
                    val resumeId = d.getString("id").orEmpty()
                    if (resumeId.isNotBlank()) {
                        AppLog.add("MEDIA", "Restoring selected video for Play")
                        open(resumeId, d.getLong("sec", 0L))
                    } else {
                        AppLog.add("MEDIA", "Play ignored: no selected video to restore")
                        send(Msg.PLAYER_MISSING, Bundle())
                    }
                } else cmd("play")
            }
            Msg.PAUSE -> {
                userPausedAt = SystemClock.elapsedRealtime()
                cmd("pause")
                send(Msg.TRANSPORT_ACK, Bundle().apply {
                    putInt("command", Msg.PAUSE)
                    putLong("terminalId", d.getLong("terminalId", -1L))
                })
            }
            Msg.SEEK -> cmd("seek", (d.getLong("ms") / 1000.0).toString())
            Msg.NEXT -> cmd("next")
            Msg.PREV -> cmd("prev")
            Msg.RATE -> cmd("rate", d.getFloat("rate", 1f).toString())
            Msg.STOP -> {
                cmd("pause")
                if (!shown) release()
                send(Msg.TRANSPORT_ACK, Bundle().apply {
                    putInt("command", Msg.STOP)
                    putLong("terminalId", d.getLong("terminalId", -1L))
                })
            }
            Msg.QUIT -> {
                AppLog.add("LINK", "Exit")
                BrowseHost.release()
                runCatching {
                    m.replyTo?.send(Message.obtain(null, Msg.QUIT_ACK))
                }.onFailure { AppLog.add("LINK", "Quit acknowledgement failed") }
                cmd("pause")
                val close = onQuit ?: onCarQuit
                if (close != null) close() else release()
            }
            Msg.FREEZE -> if (!shown && !snapshot.value.playing && !keepRecovering()) freeze()
        }
    }

    private fun open(id: String, startSec: Long) {
        if (id.isBlank()) return
        // Android Auto may reissue PLAY or media selection after a browser
        // reconnect while the SAME Chromium player already survives. Reloading
        // it causes sound interruptions and extra OPEN -> page load loops.
        if (web != null && snapshot.value.id == id && isOnWatchPage()) {
            AppLog.add("LINK", "Reusing current video instead of reloading")
            val currentSec = snapshot.value.posMs.coerceAtLeast(0L) / 1000
            if (startSec > 0 && kotlin.math.abs(startSec - currentSec) > 4)
                cmd("seek", startSec.toString())
            if (!snapshot.value.playing) cmd("play")
            return
        }
        val base = if (Features.current.desktopSite) "https://www.youtube.com" else "https://m.youtube.com"
        // aay=1 tells the page script to start playing as soon as the video is ready.
        // Preserve playlist/Mix context when skipping via native media controls.
        // Dropping list= on every OPEN turns the next item into a standalone
        // video, which can break subsequent steering-wheel navigation.
        val currentUrl = web?.url.orEmpty()
        val currentUri = runCatching { Uri.parse(currentUrl) }.getOrNull()
        val playlistId = currentUri?.takeIf {
            it.path == "/watch" && allowedHost(it.host)
        }?.getQueryParameter("list")?.takeIf {
            it.length in 2..100 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
        }
        val playlist = playlistId?.let { "&list=" + Uri.encode(it) }.orEmpty()
        AppLog.add("MEDIA", "Open queued video id=$id playlistContext=${playlistId != null}")
        load("$base/watch?v=${Uri.encode(id)}" + playlist +
            (if (startSec > 0) "&t=${startSec}s" else "") + "&aay=1")
    }

    /** Legacy Assistant / car voice search, routed to the MediaCompat playback search. */
    fun voiceSearch(query: String) {
        if (query.isBlank()) return
        unfreeze()
        mode.value = PlayerMode.FULL
        search(query)
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val base = if (Features.current.desktopSite) "https://www.youtube.com" else "https://m.youtube.com"
        // aay=play: the page script opens the first real video of the results and plays it.
        load("$base/results?search_query=${Uri.encode(query)}&aay=play")
    }

    private fun load(url: String) {
        noteSelectedLyricsRoute(runCatching { Uri.parse(url) }.getOrNull())
        val w = web
        if (w == null) {
            pendingUrl = url
            ensureWeb()
        } else {
            w.loadUrl(url)
        }
    }

    /** Only a newly selected car video gets autoplay intent. A paused
     * session restored on startup, or a user pressing Pause, stays paused. */
    private fun withCarPlaybackIntent(url: String): String {
        if (!carShown && !carDisplay) return url
        val uri = Uri.parse(url)
        if (!allowedHost(uri.host) || !uri.path.orEmpty().let {
                it.startsWith("/watch") || it.startsWith("/live/")
            } || uri.getQueryParameter("aay") != null) return url
        return uri.buildUpon().appendQueryParameter("aay", "1").build().toString()
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
            if (what == Msg.STATE || what == Msg.PLAYER_MISSING ||
                what == Msg.RENDERER_GONE || what == Msg.TRANSPORT_ACK)
                data.putInt("epoch", linkEpoch)
            m.data = data
            l.send(m)
        } catch (e: RemoteException) {
            link = null
            linkEpoch = -1
        }
    }

    /** Called on a WebView thread by [WebBridge]. */
    fun onJsState(json: String, generation: Int = webGeneration) {
        if (generation != webGeneration) return
        val s = runCatching {
            val o = JSONObject(json)
            Snap(
                id = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                art = o.optString("art"),
                playing = o.optBoolean("playing"),
                userPaused = o.optBoolean("userPaused"),
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
        main.post {
            if (generation != webGeneration) return@post
            if (s.id.isNotBlank() && (!mainFrameAllowsPlayback ||
                    (expectedWatchVideoId != null && expectedWatchVideoId != s.id))) {
                AppLog.add("MEDIA", "Ignored stale video report after watch navigation")
                return@post
            }
            publish(s)
        }
    }

    private var lastLogged = ""
    private var visibilityChangedAt = 0L
    private var userPausedAt = 0L
    // Last confirmed user/page intent; do not freeze a dropped stream that
    // should recover, even after the initial native retry window expires.
    private var playbackWanted = false
    private var resumeTries = 0
    private var resumeWindowAt = 0L
    private var unexpectedPauseRecoveryUntil = 0L

    /** The screen went to the background, into picture in picture, or came back. */
    fun noteVisibilityChange() {
        // Keep the retry budget across layout transitions; it is reset by elapsed time.
        visibilityChangedAt = SystemClock.elapsedRealtime()
    }

    // YouTube/Chromium may drop playback long AFTER the screen was turned off.
    // A user pause delivered via the media session is authoritative; do not fight it.
    // Bound retries to prevent repeated play requests for an unavailable stream.
    private fun resumeIfDropped(old: Snap, new: Snap) {
        if (!old.playing || new.playing || new.buffering || new.ended ||
            new.id != old.id || new.id.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val background = !shown && !inPip.value && !carShown
        // Background-play OFF is respected when fully hidden. Unexpected pauses
        // in visible phone/car, mini-player or PiP still deserve recovery.
        if (background && !Features.current.backgroundPlay) return
        val mini = mode.value == PlayerMode.MINI && !inPip.value
        if (now - userPausedAt <= 5_000L) return
        unexpectedPauseRecoveryUntil = now + 120_000L
        if (now - resumeWindowAt > 60_000L) {
            resumeWindowAt = now
            resumeTries = 0
        }
        if (resumeTries >= 2) {
            AppLog.add("PLAY", "Unexpected pause: native recovery budget exhausted")
            return
        }
        resumeTries++
        AppLog.add("PLAY", "Unexpected pause in background=$background mini=$mini: native resume $resumeTries/2")
        cmd("play")
    }

    private var lastPlaybackServiceStartAt = 0L
    private var lastPlaybackServiceStartId = ""

    /** A MediaBrowser binding is not a started foreground service.
     * Launch it while playback begins from the visible activity/car host so
     * Android can display the real media notification and prioritize the WebView.
     */
    private fun ensurePlaybackService(previous: Snap, current: Snap) {
        if (!current.playing || current.id.isBlank() ||
            (previous.playing && previous.id == current.id)) return
        val now = SystemClock.elapsedRealtime()
        if (lastPlaybackServiceStartId == current.id &&
            now - lastPlaybackServiceStartAt < 4_000L) return
        lastPlaybackServiceStartAt = now
        lastPlaybackServiceStartId = current.id
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                app, Intent(app, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_PLAYBACK)
                    .putExtra(PlaybackService.EXTRA_PLAYBACK, current.toBundle())
            )
            AppLog.add("MEDIA", "Requested foreground playback notification")
        }.onFailure {
            AppLog.add("MEDIA", "Cannot start playback foreground service: " +
                it.javaClass.simpleName + ": " + it.message)
        }
    }

    private fun publish(incoming: Snap) {
        val previous = snapshot.value
        if (incoming.userPaused && !incoming.playing) {
            playbackWanted = false
            userPausedAt = SystemClock.elapsedRealtime()
        } else if (incoming.playing) playbackWanted = true
        if (incoming.ended || incoming.id.isBlank()) playbackWanted = false
        // Keep foreground media priority while the active watchdog retries a
        // spontaneous pause. Never preserve this for explicit Pause or ending.
        val s = incoming.copy(recovering = !incoming.playing && !incoming.userPaused &&
            !incoming.ended && incoming.id.isNotBlank() && keepRecovering())
        resumeIfDropped(previous, s)
        ensurePlaybackService(previous, s)
        val key = "${s.id}|${s.playing}|${s.buffering}|${s.ad}|${s.ended}|${s.live}"
        if (key != lastLogged) {
            lastLogged = key
            AppLog.add("PLAY", "id=${s.id} \"${s.title}\" playing=${s.playing} buffering=${s.buffering} ad=${s.ad} ended=${s.ended} live=${s.live} pos=${s.posMs / 1000}s/${s.durMs / 1000}s rate=${s.rate} next=${s.canNext} prev=${s.canPrev}")
        }
        snapshot.value = s
        if (s.playing) unexpectedPauseRecoveryUntil = 0L
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

    internal fun isYouTubeHost(host: String?): Boolean = YouTubeSavedHostPolicy.matches(host)

    internal fun allowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        // Google sign-in hops between many Google domains (google.co.uk, accounts.google.*, ...).
        if (GOOGLE_COUNTRY.matches(h)) return true
        return ALLOWED.any { h == it || h.endsWith(".$it") }
    }

    // AABrowser parity: YouTube/Googlevideo resources and ALL resources on a
    // YouTube page are script-managed. Network-blocking advertising endpoints
    // breaks some YouTube player streams before the page can skip the ad.
    internal fun isAdRequest(host: String, pageHost: String?): Boolean =
        YouTubeAdRequestPolicy.shouldBlock(host, pageHost)

    internal val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    // Updated on WebView's UI thread; read by shouldInterceptRequest's worker.
    @Volatile private var adPageHost: String? = "youtube.com"

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
            if (request.isForMainFrame) noteSelectedLyricsRoute(uri)
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
            val ad = isAdRequest(uri.host.orEmpty().lowercase(), adPageHost)
            if (ad) AppLog.add("ADS", "Blocked request ${uri.host}${uri.path}")
            return if (ad) emptyResponse else null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== web) return
            adPageHost = runCatching { Uri.parse(url).host }.getOrNull()
            val incomingId = runCatching { Uri.parse(url).getQueryParameter("v") }.getOrNull()
            val outgoingId = runCatching { Uri.parse(lastUrl).getQueryParameter("v") }.getOrNull()
            val previous = snapshot.value
            // A full document navigation destroys the old page's JS before it
            // can send the last empty snapshot. Clear the native media identity
            // here too, not just on YouTube's same-document SPA transitions.
            val route = runCatching { Uri.parse(url).path }.getOrNull()
            mainFrameAllowsPlayback = PlaybackRoutePolicy.isPlaybackRoute(route)
            expectedWatchVideoId = incomingId?.takeIf { route == "/watch" && it.isNotBlank() }
            selectedWatchVideoId.value = expectedWatchVideoId.orEmpty()
            if (PlaybackRoutePolicy.shouldClearPrevious(route, incomingId, previous.id)) {
                playbackWanted = false
                unexpectedPauseRecoveryUntil = 0L
                snapshot.value = Snap()
                send(Msg.STATE, Snap().toBundle())
                AppLog.add("PLAY", "Navigation cleared previous media session")
            }
            sameVideoReloadPosition = if (incomingId != null && incomingId == outgoingId &&
                incomingId == previous.id && previous.posMs >= 10_000L && !previous.ended)
                incomingId to previous.posMs else null
            AppLog.add("PAGE", "Started $url" +
                if (sameVideoReloadPosition != null) " (same-video position retained)" else "")
            lyricsClock.value = LyricsClock()
            youtubeAccount.value = YouTubeAccountState.CHECKING
            progress.value = 5
            offline.value = false
            // Older WebViews cannot run a script before the page: inject as early as possible instead.
            if (scriptHandler == null) view.evaluateJavascript(script(), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== web) return
            if (mode.value == PlayerMode.MINI) view.evaluateJavascript("window.__aay&&window.__aay.mini(true)", null)
            if (inPip.value) view.evaluateJavascript("window.__aay&&window.__aay.pip(true)", null)
            view.evaluateJavascript("window.__aay&&window.__aay.car(${carDisplay || carShown})", null)
            if (lyricsTracking) view.evaluateJavascript("window.__aay&&window.__aay.lyricsSync(true)", null)
            sameVideoReloadPosition?.let { (videoId, positionMs) ->
                if (runCatching { Uri.parse(url).getQueryParameter("v") }.getOrNull() == videoId) {
                    val seconds = positionMs / 1000.0
                    view.evaluateJavascript(
                        "window.__aay&&window.__aay.restoreAt&&window.__aay.restoreAt(" +
                            JSONObject.quote(videoId) + ",$seconds)", null)
                    AppLog.add("PLAY", "Restoring same-video document position=" + (positionMs / 1000) + "s")
                }
            }
            sameVideoReloadPosition = null
            AppLog.add("PAGE", "Finished $url")
            progress.value = 100
            CookieManager.getInstance().flush()
            canGoBack.value = view.canGoBack()
            // A new document resets __aay.isMini; reapply our layout if navigation or reload
            // happened while the mini player was open.
            if (mode.value == PlayerMode.MINI) js("window.__aay&&window.__aay.mini(true)")
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (view !== web) return
            adPageHost = runCatching { Uri.parse(url).host }.getOrNull()
            canGoBack.value = view.canGoBack()
            val wasWatch = onWatchPage.value
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            val route = parsed?.path
            mainFrameAllowsPlayback = PlaybackRoutePolicy.isPlaybackRoute(route)
            expectedWatchVideoId = parsed?.getQueryParameter("v")
                ?.takeIf { route == "/watch" && it.isNotBlank() }
            selectedWatchVideoId.value = expectedWatchVideoId.orEmpty()
            onWatchPage.value = mainFrameAllowsPlayback
            if (PlaybackRoutePolicy.shouldClearPrevious(
                    route, parsed?.getQueryParameter("v"), snapshot.value.id)) {
                // SPA navigation can move from video A to B without onPageStarted.
                playbackWanted = false
                unexpectedPauseRecoveryUntil = 0L
                publish(Snap())
            }
            if (carShown && onWatchPage.value && !wasWatch) {
                main.removeCallbacks(carWatchProbe)
                main.postDelayed(carWatchProbe, 3_000L)
                main.postDelayed(carWatchProbe, 9_000L)
            }
            if (!url.isNullOrEmpty() && url != "about:blank") {
                lastUrl = url
                saveUrl(url)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            AppLog.add("NET", "Error ${error.errorCode} ${error.description} for ${request.url} main=${request.isForMainFrame}")
            if (request.isForMainFrame) offline.value = true
        }

        // Preserve visible car ownership and playback intent while replacing the
        // renderer. Only two automatic resumptions are allowed per 15-second window.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (web !== view) return true
            val now = SystemClock.elapsedRealtime()
            rendererCrashCount = if (now - lastRendererCrashAt <= 15_000L) rendererCrashCount + 1 else 1
            lastRendererCrashAt = now
            val before = snapshot.value
            val mayResume = rendererCrashCount <= 2 && (before.playing || playbackWanted) &&
                !before.userPaused && before.id.isNotBlank()
            // Stop retaining a phantom recovery intent after the renderer crash
            // circuit-breaker has deliberately stopped automatic restarts.
            if (!mayResume) playbackWanted = false
            val reopen = if (before.id.isNotBlank()) {
                val base = if (Features.current.desktopSite) HOME_DESKTOP else HOME_MOBILE
                base + "watch?v=" + Uri.encode(before.id) +
                    (if (before.posMs > 0) "&t=" + before.posMs / 1000 + "s" else "") +
                    (if (mayResume) "&aay=1" else "&aay=paused")
            } else lastUrl
            AppLog.add("CRASH", "Renderer gone; repeat=$rendererCrashCount resume=$mayResume")
            if (mayResume) send(Msg.RENDERER_GONE, before.toBundle())
            else send(Msg.STATE, before.copy(playing = false, buffering = false).toBundle())
            release(preserveCarOwners = true)
            pendingUrl = reopen
            if (shown || mayResume) {
                if (!shown) main.post { if (web == null) ensureWeb() }
                webVersion.update { it + 1 }
            }
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            val cleaned = WebConsolePolicy.clean(m.message()) ?: return true
            AppLog.add("JS", "${m.messageLevel()} $cleaned (${m.sourceId().orEmpty().substringAfterLast('/')}:${m.lineNumber()})")
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

}

/** What the page script can call. Every method runs on a WebView thread, not the main thread. */
class WebBridge(private val generation: Int) {
    @JavascriptInterface
    fun state(json: String) {
        WebHost.onJsState(json, generation)
    }

    @JavascriptInterface
    fun userPause() {
        WebHost.onJsUserPause(generation)
    }

    @JavascriptInterface
    fun account(status: String) {
        WebHost.reportAccountStatus(status, generation)
    }

    /** Real HTMLVideoElement position, sampled only while timed lyrics are displayed. */
    @JavascriptInterface
    fun lyricsTime(id: String, seconds: Double, playing: Boolean, buffering: Boolean, rate: Double) {
        WebHost.onJsLyricsTime(id, seconds, playing, buffering, rate, generation)
    }

    /** Persist only explicit quality menu selections (not YouTube's adaptive downgrades). */
    @JavascriptInterface
    fun quality(value: String) {
        WebHost.requestQualityFromBridge(value, generation)
    }

    /** Swipe down on the playing video. */
    @JavascriptInterface
    fun mini() {
        WebHost.requestMiniFromBridge(generation)
    }
}
