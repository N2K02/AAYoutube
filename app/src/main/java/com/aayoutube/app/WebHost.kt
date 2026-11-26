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
import android.content.res.Configuration
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
import kotlinx.coroutines.delay
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
    internal val rendererAllocated: Boolean get() = web != null
    private var webDisplayId: Int? = null
    private var webGeneration = 0
    internal val rendererGeneration: Int get() = webGeneration
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
    private var webResizeCount = 0
    private var lastWebResizeLogAt = 0L

    // What the screens show.
    val progress = MutableStateFlow(100)
    val offline = MutableStateFlow(false)
    val canGoBack = MutableStateFlow(false)
    /** The player page shows a video (watch or live page). */
    val onWatchPage = MutableStateFlow(false)
    /** Phone held sideways on a watch page: the video fills the screen (car layout rules). */
    val landscapeFill = MutableStateFlow(false)
    /** The user left the filled landscape video for the watch page; reset in portrait. */
    val landscapeDismissed = MutableStateFlow(false)

    /** First swipe down / Back from the landscape video: show the page with its playlist. */
    fun dismissLandscapeFill() {
        if (!landscapeFill.value) return
        landscapeDismissed.value = true
        transit()
    }

    /** Android Auto: watch page with playlist instead of the video filling the screen. */
    val carPage = MutableStateFlow(false)

    fun setCarPage(on: Boolean) {
        if (carPage.value == on) return
        carPage.value = on
        transit()
        js("window.__aay&&window.__aay.carPage&&window.__aay.carPage($on)")
    }

    /**
     * YouTube's fullscreen / minimise button while the app fills the screen itself
     * (phone sideways, Android Auto): switch between the filled video and the watch
     * page with its playlist, never YouTube's "more videos" panel.
     */
    fun toggleFilledVideo() {
        if (mode.value != PlayerMode.FULL || fullscreenView.value != null) return
        when (FilledVideoToggle.target(
            car = carShown,
            landscape = app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            carPage = carPage.value,
            landscapeFill = landscapeFill.value,
        )) {
            FilledVideoToggle.Target.CarWatchPage -> setCarPage(true)
            FilledVideoToggle.Target.CarFilled -> setCarPage(false)
            FilledVideoToggle.Target.PhoneWatchPage -> dismissLandscapeFill()
            FilledVideoToggle.Target.PhoneFilled -> { landscapeDismissed.value = false; transit() }
            FilledVideoToggle.Target.None -> Unit
        }
    }

    /** Layout transition ahead: arm page-side pause protection for a moment so the
     * sound does not stop while YouTube resizes or re-parents the video. */
    fun transit() {
        noteVisibilityChange()
        js("window.__aay&&window.__aay.transit&&window.__aay.transit(1500)")
    }
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
    // The video navigated away from: only its late reports are stale. The page
    // address can lag behind the player (Mix/autoplay), so any other ID is current.
    private var staleReportId: String? = null
    // Observe transport arrival separately from IPC delivery and actual playback.
    private var skipTraceSerial = 0
    private var skipTraceActive = false
    private var skipTraceExpected: String? = null
    private var skipTraceOrigin = ""
    private var skipTraceStartedAt = 0L
    private var modeHandoffStartedAt = 0L
    private val modeMeter = ModeTransitionMeter()
    private val modeDeadline = Runnable {
        modeMeter.expire(SystemClock.elapsedRealtime())?.let { e ->
            modeHandoffStartedAt = 0L
            AppLog.add("CAR", "Mode handoff cancelled direction=${e.direction} elapsedMs=${e.elapsedMs} reason=${e.reason}")
        }
    }
    /** The main renderer keeps playing (audio) after its last car window closed. */
    val carAudioRetained = MutableStateFlow(false)
    /** The retained renderer is in no window: the next car bind may reuse it. */
    private var retainedRendererDetached = false

    private fun beginModeHandoff(toVideo: Boolean) {
        val state = snapshot.value
        val now = SystemClock.elapsedRealtime()
        val direction = if (toVideo) "Music->Video" else "Video->Music"
        val previous = modeMeter.currentDirection
        val previousElapsed = modeMeter.elapsed(now)
        when (modeMeter.start(direction, state.id, state.posMs, webGeneration, state.playing, now)) {
            ModeTransitionMeter.Start.DUPLICATE -> {
                AppLog.add("CAR", "Mode handoff duplicate ignored direction=$direction")
                return
            }
            ModeTransitionMeter.Start.SUPERSEDED ->
                AppLog.add("CAR", "Mode handoff superseded direction=$previous elapsedMs=$previousElapsed by=$direction")
            ModeTransitionMeter.Start.STARTED -> Unit
        }
        modeHandoffStartedAt = now
        main.removeCallbacks(modeDeadline)
        if (modeMeter.running) main.postDelayed(modeDeadline, 30_000L)
        AppLog.add("CAR", "Mode handoff start direction=$direction positionMs=${state.posMs} " +
            "generation=$webGeneration playing=${state.playing} buffering=${state.buffering}")
    }

    /**
     * The snapshot can arrive late: ask the real media clock whether playback is advancing now,
     * so a delayed report is not mistaken for (or hides) the actual recovery.
     */
    private fun confirmHandoffByClock(direction: String, reportedMs: Long) {
        val askedAt = SystemClock.elapsedRealtime()
        mediaClock { c ->
            val lagMs = SystemClock.elapsedRealtime() - askedAt
            AppLog.add("CAR", "Mode handoff clock check direction=$direction playing=${c?.playing} " +
                "clockMs=${c?.posMs} reportedMs=$reportedMs confirmed=${HandoffConfirm.confirmed(c?.playing, c?.posMs, reportedMs)} " +
                "lagMs=$lagMs")
        }
    }

    private fun reportModeHandoff(state: Snap) {
        if (!modeMeter.running) return
        when (val e = modeMeter.onState(state.id, state.playing, state.buffering, state.userPaused,
            state.ended, state.posMs, webGeneration, SystemClock.elapsedRealtime())) {
            is ModeTransitionMeter.Event.Buffering ->
                AppLog.add("CAR", "Mode handoff buffering direction=${e.direction} elapsedMs=${e.elapsedMs}")
            is ModeTransitionMeter.Event.Completed -> {
                main.removeCallbacks(modeDeadline)
                modeHandoffStartedAt = 0L
                confirmHandoffByClock(e.direction, state.posMs)
                AppLog.add("CAR", "Mode handoff complete direction=${e.direction} elapsedMs=${e.elapsedMs} " +
                    "estimatedInterruptionMs=${e.interruptionMs}(playback clock; audible output is not measured) " +
                    "bufferingMs=${e.bufferingMs} " +
                    "rendererRecreated=${e.rendererRecreated} positionMs=${state.posMs}")
            }
            is ModeTransitionMeter.Event.Cancelled -> {
                main.removeCallbacks(modeDeadline)
                modeHandoffStartedAt = 0L
                AppLog.add("CAR", "Mode handoff cancelled direction=${e.direction} elapsedMs=${e.elapsedMs} reason=${e.reason}")
            }
            null -> Unit
        }
    }

    /** Leave audio-retention: the phone (or nothing) owns the renderer again. */
    private fun endAudioRetention(reason: String, reattachPhone: Boolean) {
        if (!carAudioRetained.value && !retainedRendererDetached) return
        carAudioRetained.value = false
        retainedRendererDetached = false
        syncWebAudioMode()
        AppLog.add("CAR", "Audio retention ended: $reason")
        if (reattachPhone && !carShown) webVersion.update { it + 1 }
        refreshBackgroundQuality()
    }

    /** Android Auto disconnected: nothing can show the retained car renderer any more.
     * The connection callback can precede the video window's teardown; showOnCar()
     * reconciles again once the last car window has closed. */
    fun onCarDisconnected() {
        if (carShown) {
            AppLog.add("CAR", "Car disconnected while a video window is still open: reconcile on teardown")
            return
        }
        if (carAudioRetained.value || retainedRendererDetached)
            endAudioRetention("Android Auto disconnected", reattachPhone = true)
        BrowseHost.phoneMode.value = PlayerMode.FULL
        MemoryCheckpoint.record("car-disconnected")
    }
    private val carDisconnected: Boolean get() = carConnectionType == CarConnectionPolicy.NOT_CONNECTED
    private fun traceTransport(command: String, expected: String? = null) {
        val serial = ++skipTraceSerial
        skipTraceActive = true
        skipTraceExpected = expected
        skipTraceOrigin = snapshot.value.id
        skipTraceStartedAt = SystemClock.elapsedRealtime()
        AppLog.add("MEDIA", "WebView received $command origin=$skipTraceOrigin expected=${expected.orEmpty()} serial=$serial")
        main.postDelayed({
            if (skipTraceSerial != serial) return@postDelayed
            AppLog.add("MEDIA", "Transport unconfirmed after 7s: command=$command expected=${expected.orEmpty()} " +
                "actual=${snapshot.value.id} playing=${snapshot.value.playing} " +
                "webReady=${web != null} page=${web?.url.orEmpty().take(120)}")
            skipTraceExpected = null
            skipTraceActive = false
            skipTraceSerial++
        }, 7_000L)
    }
    private fun confirmTransport(current: Snap) {
        if (!skipTraceActive) return
        val expected = skipTraceExpected
        if (current.id.isNotBlank() && current.playing &&
            (if (expected != null) current.id == expected
             else current.id != skipTraceOrigin)) {
            AppLog.add("MEDIA", "Transport confirmed: id=${current.id} playing=true elapsedMs=${SystemClock.elapsedRealtime() - skipTraceStartedAt}")
            skipTraceExpected = null
            skipTraceActive = false
            skipTraceSerial++
        }
    }
    private fun cancelTransportTrace() {
        skipTraceActive = false
        skipTraceSerial++
        skipTraceExpected = null
    }

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
    private var carBoundDisplayId: Int? = null
    private var playingWhenCarLeft = false
    private var carLeftAt = 0L

    /** The app screen is on the car display (Android 15+): videos fill the screen there. */
    val carScreen = MutableStateFlow(false)
    val carDisplay: Boolean get() = carScreen.value

    fun setCarScreen(on: Boolean) {
        if (carScreen.value == on) return
        carScreen.value = on
        NativePlayback.onVisibilityChanged()
        AppLog.add("VIEW", if (on) "On the car display: videos fill the screen" else "On the phone screen")
        web?.let { installScript(it) }
        syncWebAudioMode()
        js("window.__aay&&window.__aay.car(${carDisplay || carShown})")
    }
    val shown: Boolean get() = activityVisible || carShown
    /** The phone shows a preview of the car player (or its retained audio), not the page. */
    val phonePreviewOnly: Boolean get() = carShown || carAudioRetained.value

    fun init(application: Application) {
        app = application
        lastDesktop = Features.current.desktopSite
        scope.launch { Features.state.drop(1).collect { applyFeatures(it) } }
        watchCarConnection(application)
    }

    private var carConnectionType = -1
    /** Android Auto disconnect ends audio retention: no car can show that renderer again. */
    private fun watchCarConnection(application: Application) {
        runCatching {
            androidx.car.app.connection.CarConnection(application).type.observeForever { type ->
                val previous = carConnectionType
                carConnectionType = type
                AppLog.add("CAR", "Car connection type=$type previous=$previous")
                if (CarConnectionPolicy.disconnected(previous, type)) onCarDisconnected()
            }
        }.onFailure { AppLog.add("CAR", "Car connection watch unavailable: " + it.javaClass.simpleName) }
    }

    // ---------------------------------------------------------------- the WebView

    internal fun home() = if (Features.current.desktopSite) HOME_DESKTOP else HOME_MOBILE

    /** Validate explicit loads as WebView.loadUrl does not invoke navigation override. */
    internal fun isSafePageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null && allowedHost(uri.host) &&
            uri.userInfo == null && uri.port == -1
    }

    private var bridgeToken = ""

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
        PerformanceMonitor.diagnostics.rendererAllocations++
        PerformanceMonitor.onPrimaryRendererCreated()
        NetworkMonitor.start(app)
        bridgeToken = java.util.UUID.randomUUID().toString()
        w.setBackgroundColor(Color.BLACK)
        // Actual WebView geometry changes are a cheap proxy for potential
        // decoder Surface rebinding. Do not confuse them with Codec2 creation.
        w.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0 ||
                (width == oldRight - oldLeft && height == oldBottom - oldTop)) return@addOnLayoutChangeListener
            webResizeCount++
            val now = SystemClock.elapsedRealtime()
            if (now - lastWebResizeLogAt >= 1_000L) {
                lastWebResizeLogAt = now
                AppLog.add("VIDEO", "WebView surface geometry=${width}x${height} " +
                    "resizeCount=$webResizeCount mode=${mode.value} pip=${inPip.value}")
            }
        }
        configure(w)
        // WebView.pauseTimers() is process-wide. An older idle/frozen player
        // may have paused Chromium's shared timers before being disposed.
        // A fresh player must always have live script/media timers.
        w.resumeTimers()
        web = w
        installScript(w)
        var url = if (sessionClearing) sessionResetUrl ?: "about:blank"
            else (pendingUrl ?: savedUrl())?.takeIf(::isSafePageUrl) ?: home()
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
        return u.takeIf { isSafePageUrl(it) && isYouTubeHost(uri.host) }
    }

    private fun saveUrl(url: String) {
        val u = Uri.parse(url)
        if (!isSafePageUrl(url) || !isYouTubeHost(u.host)) return
        // Without our autoplay flags: opening the app should not start a video on its own.
        val clean = u.buildUpon().clearQuery().apply {
            u.queryParameterNames.filter { it != "aay" && it != "aay_reset" }.forEach { k -> u.getQueryParameters(k).forEach { v -> appendQueryParameter(k, v) } }
        }.build().toString()
        webPrefs.edit().putString("lastUrl", clean).apply()
    }

    private fun configure(w: WebView) {
        val s = w.settings
        s.textZoom = if (webDisplayId != Display.DEFAULT_DISPLAY) 100 else Features.current.pageZoom
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        // Same WebView settings as AABrowser's browser page.
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        // On the car display overview mode zooms YouTube's mobile Home out to a
        // tiny desktop-like width, and a stray pinch zoom sticks; keep 1:1 there.
        val car = webDisplayId != Display.DEFAULT_DISPLAY
        s.loadWithOverviewMode = !car
        if (car) s.setSupportZoom(false)
        // Refuse HTTP subresources on authenticated HTTPS pages.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.isClickable = true
        w.isLongClickable = true
        s.userAgentString = userAgent(Features.current.desktopSite)

        // Google refuses to sign in inside an "app WebView": a plain Chrome user agent (no "wv")
        // and no X-Requested-With header make it look like the normal browser.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(w, true)

        w.addJavascriptInterface(WebBridge(webGeneration, bridgeToken), "AAYNative")
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
        registerThermalWatch()
        val cfg = Features.current.toJson().put("decodeCaps", DecoderCapabilities.json())
            .put("thermal", thermalStatus())
            .put("perfLevel", PerformanceMonitor.level.value.ordinal)
            .put("diagMs", PerformanceMonitor.diagnosticsIntervalMs())
        return "if(window.top===window.self)window.__AAY_BRIDGE_TOKEN=" +
            JSONObject.quote(bridgeToken) + ";window.__AAY_CFG=" + cfg +
            ";window.__AAY_CAR=" + (carDisplay || carShown) +
            ";window.__AAY_AUDIO_ONLY=" + carAudioRetained.value + ";\n" + source
    }

    private fun installScript(w: WebView) {
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("https://*.youtube.com"))
        }
    }

    private fun applyFeatures(f: FeatureFlags) {
        NativePlayback.onVisibilityChanged()
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
        w.settings.textZoom = if (webDisplayId != Display.DEFAULT_DISPLAY) 100 else f.pageZoom
        installScript(w)
        js("window.__aay&&window.__aay.config(${f.toJson()})")
        syncWebAudioMode()
    }

    private fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    private fun cmd(name: String, arg: String? = null) {
        if (sessionClearing && name != "pause") return
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
        retainedRendererDetached = false
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
    fun attachToCarWindow(context: Context, frame: ViewGroup, rebind: (() -> Unit)? = null): WebView {
        val targetDisplay = displayId(context)
        val targetWindow = frame.windowToken?.let { System.identityHashCode(it) }
        val forced = forcedCarRecreate
        // Surface ownership trail: system codec/ImageReader warnings are correlated against these
        // lines; such warnings alone never recreate a healthy renderer.
        AppLog.add("SURFACE", "Car window bind display=$targetDisplay window=$targetWindow " +
            "previousDisplay=$webDisplayId previousWindow=$carWindowId generation=$webGeneration forced=$forced")
        forcedCarRecreate = null
        // Repeated AndroidView/window binds must not destroy a retained renderer
        // while its cross-display trial is still running or already verified.
        val existing = web
        if (forced == null && existing != null && existing.parent === frame &&
            targetDisplay == carBoundDisplayId && targetWindow == carWindowId) {
            if (rebind != null) { carRebind = rebind; carRebindOwner = activeCarHost.value }
            return existing
        }
        // A retained (audio-only) renderer is in no window: reuse it with its buffers.
        val detachedReuse = web != null && retainedRendererDetached && web?.parent == null
        val trial = forced == null && CarRendererReusePolicy.canTrial(webDisplayId, targetDisplay,
            detachedReuse, snapshot.value.playing && !snapshot.value.buffering && !snapshot.value.userPaused,
            rebind != null && activeCarHost.value != null && targetWindow != null, SystemClock.elapsedRealtime(), lastCrossDisplayFailureAt)
        val reason = if (web == null) null else CarRendererReusePolicy.reason(
            webDisplayId, targetDisplay, carWindowId, targetWindow, detachedReuse, forced, trial)
        val reused = reason == null && detachedReuse && (trial || (carWindowId != null && carWindowId != targetWindow))
        retainedRendererDetached = false
        if (reason != null) recreateRenderer(context, targetDisplay ?: -1, reason)
        val w = ensureWeb(context)
        val sameBinding = w.parent === frame && carWindowId == targetWindow && reason == null
        if (w.parent !== frame) (w.parent as? ViewGroup)?.removeView(w)
        carWindowId = targetWindow
        carBoundDisplayId = targetDisplay
        if (rebind != null) { carRebind = rebind; carRebindOwner = activeCarHost.value }
        if (!sameBinding) AppLog.add("CAR", "Car WebView bind display=" + (targetDisplay ?: -1) +
            " created=" + (webDisplayId ?: -1) + " window=" +
            (targetWindow?.let { Integer.toHexString(it) } ?: "none") +
            " renderer=" + (reason?.let { "new($it)" } ?: if (reused) "reused(retained)" else "kept"))
        if (reused) {
            w.onResume(); w.resumeTimers(); w.invalidate()
            scheduleCarRenderVerify(trial)
        }
        return w
    }

    private var forcedCarRecreate: String? = null
    private var carRebind: (() -> Unit)? = null
    private var carRebindOwner: String? = null
    private var lastCarRenderRecoveryAt = 0L
    private var lastCrossDisplayFailureAt = 0L
    private var carVerifyEpoch = 0L
    private val displayTrial = RendererTrial()
    private val crossDisplayTrial: Boolean get() = displayTrial.active
    private var carTrialTimeout: Runnable? = null
    private var carVerifySamples = 0
    private var carBlackSamples = 0
    private var carVerifyGeneration = 0
    private var carVerifyLastPos = -1L
    private val carRenderVerify: Runnable = object : Runnable {
        override fun run() {
            val w = web ?: return
            if (!carShown || carVerifyGeneration != webGeneration) return
            val epoch = carVerifyEpoch
            // The real media clock and decoder counters, not the sparse snapshot reports.
            w.evaluateJavascript("(function(){var v=document.querySelector('video.html5-main-video')||" +
                "document.querySelector('video');if(!v)return null;return JSON.stringify([" +
                "Math.round(v.currentTime*1000),v.webkitDecodedFrameCount||0,v.videoWidth||0])})()") { raw ->
                val a = runCatching {
                    org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                }.getOrNull()
                if (web === w && verifyStillOwned(epoch))
                    sampleCarRender(a?.optLong(0, -1L) ?: -1L, a?.optLong(1, -1L) ?: -1L, a?.optInt(2, 0) ?: 0, epoch)
            }
        }
    }
    private var carVerifyLastFrames = -1L
    private var carVerifyOwner: String? = null
    private fun verifyStillOwned(epoch: Long): Boolean =
        epoch == carVerifyEpoch && carShown && carVerifyGeneration == webGeneration && activeCarHost.value == carVerifyOwner

    private fun sampleCarRender(clockMs: Long, decodedFrames: Long, videoWidth: Int, epoch: Long) {
        if (!verifyStillOwned(epoch)) return
        val s = snapshot.value
        val advancing = clockMs >= 0 && carVerifyLastPos >= 0 && clockMs > carVerifyLastPos
        // Healthy decoder + advancing clock + black window = a paint failure, not a decode stall.
        val decoding = decodedFrames >= 0 && carVerifyLastFrames >= 0 &&
            decodedFrames > carVerifyLastFrames && videoWidth > 0
        carVerifyLastPos = clockMs
        carVerifyLastFrames = decodedFrames
        scope.launch {
            val dark = CarPhonePreview.darkPercent()
            if (!verifyStillOwned(epoch)) return@launch
            var black = CarRendererReusePolicy.blackSample(dark, s.playing, s.buffering, advancing && decoding)
            if (black) {
                // A dark scene still shows YouTube's bright controls; a dead surface does not.
                js("window.__aay&&window.__aay.showCarControls&&window.__aay.showCarControls()")
                delay(CarRendererReusePolicy.CONTROLS_PAINT_MS)
                if (!verifyStillOwned(epoch)) return@launch
                val withControls = CarPhonePreview.darkPercent()
                black = CarRendererReusePolicy.stillBlackWithControls(withControls)
                AppLog.add("CAR", "Reused renderer black check with controls dark=${withControls ?: -1}% black=$black")
            }
            if (crossDisplayTrial && CarRendererReusePolicy.trialVerified(advancing, decoding, dark)) {
                finishDisplayTrial("verified")
                carTrialTimeout?.let(main::removeCallbacks)
                carTrialTimeout = null
                return@launch
            }
            carBlackSamples = if (black) carBlackSamples + 1 else 0
            carVerifySamples++
            AppLog.add("CAR", "Reused renderer check sample=$carVerifySamples dark=${dark ?: -1}% " +
                "playing=${s.playing} advancing=$advancing decoding=$decoding black=$black")
            val now = SystemClock.elapsedRealtime()
            if ((crossDisplayTrial && carVerifySamples >= CarRendererReusePolicy.MAX_VERIFY_SAMPLES) ||
                CarRendererReusePolicy.shouldRecover(carBlackSamples, now, lastCarRenderRecoveryAt)) {
                val rebind = carRebind
                if (rebind == null || carRebindOwner != carVerifyOwner) {
                    AppLog.add("CAR", "Renderer recovery skipped: car window no longer owns the player")
                    return@launch
                }
                lastCarRenderRecoveryAt = now
                if (crossDisplayTrial) lastCrossDisplayFailureAt = now
                val trialFailed = crossDisplayTrial
                finishDisplayTrial("unverified")
                carTrialTimeout?.let(main::removeCallbacks)
                carTrialTimeout = null
                carBlackSamples = 0
                AppLog.add("CAR", "Reused renderer paints black: safe renderer recovery")
                forcedCarRecreate = if (trialFailed) "cross-display-unverified" else "window-black"
                rebind()
                // Never leave a forced reason for an unrelated later bind.
                forcedCarRecreate = null
            } else if (carVerifySamples < CarRendererReusePolicy.MAX_VERIFY_SAMPLES ||
                (carBlackSamples > 0 && carVerifySamples < CarRendererReusePolicy.MAX_VERIFY_SAMPLES + 2)) {
                main.postDelayed(carRenderVerify, CarRendererReusePolicy.VERIFY_DELAYS_MS)
            } else AppLog.add("CAR", "Reused renderer check finished blackSamples=$carBlackSamples")
        }
    }
    private fun finishDisplayTrial(reason: String) {
        displayTrial.finish(SystemClock.elapsedRealtime())?.let { elapsed ->
            AppLog.add("CAR", "Cross-display trial result=$reason elapsedMs=$elapsed generation=$webGeneration")
        }
    }

    private fun scheduleCarRenderVerify(trial: Boolean = false) {
        main.removeCallbacks(carRenderVerify)
        finishDisplayTrial("superseded")
        if (trial) {
            displayTrial.start(SystemClock.elapsedRealtime())
            AppLog.add("CAR", "Cross-display trial start generation=$webGeneration display=$carBoundDisplayId")
        }
        carVerifyEpoch++
        carVerifySamples = 0
        carBlackSamples = 0
        carVerifyLastPos = -1L
        carVerifyLastFrames = -1L
        carVerifyGeneration = webGeneration
        carVerifyOwner = activeCarHost.value
        carTrialTimeout?.let(main::removeCallbacks)
        carTrialTimeout = null
        if (trial) {
            val epoch = carVerifyEpoch
            val timeout = Runnable {
                if (crossDisplayTrial && verifyStillOwned(epoch) && carRebindOwner == carVerifyOwner) {
                    finishDisplayTrial("timeout")
                    carVerifyEpoch++
                    main.removeCallbacks(carRenderVerify)
                    lastCrossDisplayFailureAt = SystemClock.elapsedRealtime()
                    forcedCarRecreate = "cross-display-timeout"
                    AppLog.add("CAR", "Cross-display verification timed out; using safe renderer")
                    try { carRebind?.invoke() } finally { forcedCarRecreate = null }
                }
            }
            carTrialTimeout = timeout
            main.postDelayed(timeout, RendererTrial.TIMEOUT_MS)
        }
        main.postDelayed(carRenderVerify, CarRendererReusePolicy.VERIFY_DELAYS_MS)
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
        if (carVerifyOwner == token) {
            carVerifyEpoch++
            finishDisplayTrial("owner-released")
            main.removeCallbacks(carRenderVerify)
            carTrialTimeout?.let(main::removeCallbacks)
            carTrialTimeout = null
        }
        if (carRebindOwner == token) { carRebind = null; carRebindOwner = null }
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
                uri.queryParameterNames.filterNot { it == "t" || it == "start" || it == "aay" }.forEach { key ->
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
        cancelOtherMediaWork("Renderer display changed")
        exitFullscreen()
        web = null
        webGeneration++
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        (previous.parent as? ViewGroup)?.removeView(previous)
        previous.stopLoading()
        previous.destroy()
        PerformanceMonitor.diagnostics.rendererReleases++
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
        // Capture the owner now: delayed JS responses must not update a newer car host.
        val probeCarHost = activeCarHost.value.orEmpty()
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
                    videoReady:v?v.readyState:0,
                    seeking:v?v.seeking:false,
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
            if (ExperimentalCarVideoMonitor.state.value.enabled &&
                ExperimentalCarVideoMonitor.state.value.owner == probeCarHost
            ) runCatching {
                // evaluateJavascript wraps a JSON.stringify return as a JSON string.
                // Decode only geometry and frame counters, never URL or document text.
                val raw = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching
                val info = JSONObject(raw)
                val size = info.optJSONArray("videoSize")
                ExperimentalCarVideoMonitor.decoder(
                    owner = probeCarHost,
                    decoded = info.optInt("frames", -1),
                    dropped = info.optInt("dropped", -1),
                    width = size?.optInt(0, 0) ?: 0,
                    height = size?.optInt(1, 0) ?: 0,
                    paused = if (info.isNull("paused")) null else info.optBoolean("paused"),
                    monotonicMs = SystemClock.elapsedRealtime(),
                    positionSeconds = info.optDouble("t", Double.NaN)
                        .takeIf { it.isFinite() },
                    hasVideo = info.optBoolean("hasVideo", false),
                    readyState = info.optInt("videoReady", 0),
                    seeking = info.optBoolean("seeking", false),
                )
            }.onFailure {
                AppLog.add("CAR", "Experimental decoder probe unavailable: " +
                    it.javaClass.simpleName)
            }
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
        if (mine && carShown && playbackWanted) {
            js("window.__aay&&window.__aay.modeTransit&&window.__aay.modeTransit()")
            AppLog.add("CAR", "Playback handoff before car surface detach")
        }
        if (mine) AppLog.add("SURFACE", "Engine detached from ${if (carShown) "car" else "phone"} window generation=$webGeneration")
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

    /** Set while the app itself resumes playback (recovery), so it is not taken for the user's Play. */
    private var automaticPlay = false
    private fun autoPlay() {
        automaticPlay = true
        try { cmd("play") } finally { automaticPlay = false }
    }

    /** Player buttons for the car screen: play, pause, next, prev. */
    fun cmd(name: String) {
        if (sessionClearing && name != "pause") return
        // Experimental native audio owns playback: play/pause go to it; next/prev hand the
        // queue back to the page (the coordinator silences native first, never two engines).
        if (NativePlayback.command(name)) {
            if (name in setOf("play", "pause")) PhoneMirror.onExplicitTransport()
            return
        }
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
        // Play from the app, notification, car or headset is the user's: it ends an
        // "other app is playing" hold instead of being deferred by it (one press is enough).
        val userPlay = name == "play" && !automaticPlay
        if (userPlay && otherMediaHeldSince != 0L) endOtherMediaHold("user pressed Play")
        js(if (userPlay) "window.__aay&&window.__aay.cmd('play','user')" else "window.__aay&&window.__aay.cmd('$name')")
        if (name in setOf("play", "pause", "next", "prev")) PhoneMirror.onExplicitTransport()
    }

    fun scrollBy(dx: Double, dy: Double) = js("window.scrollBy($dx,$dy)")

    /** Playlist of the engine's current watch page, if any. */
    fun currentPlaylist(): String? = runCatching {
        Uri.parse(web?.url.orEmpty()).takeIf { it.path == "/watch" }?.getQueryParameter("list")
    }.getOrNull()

    /**
     * The engine's real media clock with its identity and health. The answer is dropped if the
     * engine's renderer was replaced while the request was pending.
     */
    internal fun mediaClock(result: (EngineClock?) -> Unit) {
        val w = web ?: run { result(null); return }
        val gen = webGeneration
        w.evaluateJavascript("(function(){var v=document.querySelector('video.html5-main-video')||" +
            "document.querySelector('video');if(!v)return null;" +
            "var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');" +
            "var id='';try{var d=p&&p.getVideoData&&p.getVideoData();id=(d&&d.video_id)||''}catch(e){}" +
            "if(!id){id=new URLSearchParams(location.search).get('v')||'';if(!id){" +
            "var m=/^\\/(?:live|shorts)\\/([\\w-]{11})/.exec(location.pathname);id=m?m[1]:''}}" +
            "return JSON.stringify([Math.round(v.currentTime*1000),!v.paused&&!v.ended,v.playbackRate||1,id," +
            "!!v.seeking,v.readyState>=3,!!(p&&p.classList&&p.classList.contains('ad-showing'))])})()") { raw ->
            if (gen != webGeneration || web !== w) { result(null); return@evaluateJavascript }
            val clock = runCatching {
                val json = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching null
                val a = org.json.JSONArray(json)
                EngineClock(a.getString(3), a.getLong(0).coerceAtLeast(0L), a.getBoolean(1),
                    a.getDouble(2).coerceIn(0.25, 4.0), a.getBoolean(4), a.getBoolean(5), a.getBoolean(6))
            }.getOrNull()
            result(clock)
        }
    }

    /** Sign-out: selection dedupe must not carry over from the old session. */
    fun resetSelectionGuards() = openDeduper.reset()

    /** Seek the single shared player (phone remote, car, media session). */
    fun seekTo(positionMs: Long) {
        if (sessionClearing) return
        val s = snapshot.value
        if (s.id.isBlank() || s.live || s.durMs <= 0L) return
        val target = positionMs.coerceIn(0L, s.durMs)
        if (NativePlayback.seek(target)) {
            snapshot.value = s.copy(posMs = target)
            return
        }
        cmd("seek", (target / 1000.0).toString())
        snapshot.value = s.copy(posMs = target)
        PhoneMirror.onExplicitTransport()
    }

    fun showOnCar(on: Boolean, owner: String = "legacy-projection") {
        // AABrowser exposes classic projection and wide surfaces independently.
        // A temporary car/phone handoff must not freeze or reset the shared player.
        val alreadyProjected = carShown
        if (on) carOwners.add(owner) else carOwners.remove(owner)
        carShown = carOwners.isNotEmpty()
        if (alreadyProjected != carShown && playbackWanted) {
            js("window.__aay&&window.__aay.modeTransit&&window.__aay.modeTransit()")
            beginModeHandoff(carShown)
        }
        if (alreadyProjected && !carShown && web != null && playbackWanted && !carDisconnected &&
            snapshot.value.id.isNotBlank() && !snapshot.value.userPaused && !snapshot.value.ended) {
            // Keep the live document and buffers after the final projected window closes.
            // The phone must not steal/recreate this renderer just to show its UI.
            carAudioRetained.value = true
            retainedRendererDetached = true
            AppLog.add("CAR", "Retaining audio renderer on video exit; generation=$webGeneration")
        }
        // retainedRendererDetached survives until the next car bind consumes it.
        if (carShown) carAudioRetained.value = false
        if (!carShown) setCarPage(false)
        // The disconnect arrived before this teardown: finish it now.
        if (alreadyProjected && !carShown && carDisconnected) main.post { onCarDisconnected() }
        if (alreadyProjected && !carShown) {
            playingWhenCarLeft = snapshot.value.playing
            carLeftAt = SystemClock.elapsedRealtime()
        }
        carProjectionActive.value = carShown
        AppLog.add("CAR", "Projection ${owner}=" + on + " active=" + carOwners.size)
        syncWebAudioMode()
        js("window.__aay&&window.__aay.car(${carDisplay || carShown})")
        if (carShown) {
            main.removeCallbacks(freezeSoon)
            if (!alreadyProjected) noteVisibilityChange()
            unfreeze()
            // Android can call WebView.onPause while moving between phone and
            // projected windows. Explicitly restore the native renderer (as
            // upstream AABrowser does), even when 'frozen' was already false.
            web?.let { it.onResume(); it.resumeTimers(); it.invalidate() }
            // Car took ownership while PiP was active: the shared WebView moves to
            // the car display, leaving the PiP overlay with a grey surface. Clear
            // the PiP state so the JS player exits PiP mode and the system
            // dismisses the floating window.
            if (!alreadyProjected && inPip.value) {
                AppLog.add("CAR", "Car started while PiP active — clearing PiP")
                inPip.value = false
                pip(false)
            }
        } else if (!snapshot.value.playing) {
            scheduleFreeze()
        }
        refreshBackgroundQuality()
    }

    /** Rebind an already-created phone AndroidView after a car window relinquishes it.
     * Even a stopped Activity needs the version bump so its retained Compose tree will
     * reclaim the WebView when it resumes. Never steal from a second active car host. */
    fun requestReattachment() {
        if (!carShown && !carAudioRetained.value) webVersion.update { it + 1 }
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

    fun pip(on: Boolean) {
        if (on) NativePlayback.returnToWeb("pip-visible")
        js("window.__aay&&window.__aay.pip($on)")
        syncWebAudioMode(pipEntering = on)
    }

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

    private fun backgroundWebAudio(): Boolean = PlaybackHandoffPolicy.backgroundWebAudio(
        Features.current.backgroundPlay, shown, inPip.value, carProjectionActive() || carDisplay)

    private fun syncWebAudioMode(pipEntering: Boolean = false) {
        js("window.__aay&&window.__aay.audioMode&&window.__aay.audioMode(${carAudioRetained.value},${!pipEntering && backgroundWebAudio()})")
    }

    fun refreshBackgroundQuality() {
        NativePlayback.onVisibilityChanged()
        js("window.__aay&&window.__aay.setBg(${!shown && !inPip.value})")
        syncWebAudioMode()
    }

    fun openUrl(url: String) {
        if (sessionClearing) return
        // Links reach here from the page script, other apps and Android Auto. loadUrl() skips
        // shouldOverrideUrlLoading, so validate HTTPS and trusted destinations directly.
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (uri == null || scheme != "https" || !isSafePageUrl(url)) {
            AppLog.add("NAV", "Refused to open external or unsupported player link")
            return
        }
        unfreeze()
        val wasMini = mode.value == PlayerMode.MINI
        mode.value = PlayerMode.FULL
        // Opening a video from the secondary browse/playlist WebView is an
        // explicit play selection, unlike restoring a previously paused page.
        // Match native media queue OPEN, which already appends aay=1.
        val destination = withCarPlaybackIntent(normalise(url))
        val target = Uri.parse(destination)
        val playRoute = target.path == "/watch" && !target.getQueryParameter("v").isNullOrBlank()
        val explicitPaused = target.getQueryParameter("aay") == "paused"
        val selectedId = target.getQueryParameter("v")
        val selectedList = target.getQueryParameter("list")
        val startMs = TimestampParam.parseMs(target.getQueryParameter("t") ?: target.getQueryParameter("start"))
        val key = SelectionDeduper.key(target.path.orEmpty(), selectedId, selectedList, startMs)
        if (!openDeduper.accept(key, SystemClock.elapsedRealtime())) {
            PhoneMirror.noteDuplicateSelection()
            AppLog.add("NAV", "Duplicate video selection ignored")
            return
        }
        val currentList = runCatching { Uri.parse(web?.url.orEmpty()).getQueryParameter("list") }.getOrNull()
        if (web != null && SelectedVideoReuse.reuse(snapshot.value.id, isOnWatchPage(), selectedId,
                selectedList, currentList, explicitPaused)) {
            // The same video is already loaded: keep its buffer instead of reloading the page.
            AppLog.add("NAV", "Selected video already loaded: reusing player startMs=${startMs ?: -1}")
            // Leaving the mini-player without a reload: restore the full layout explicitly.
            if (wasMini) {
                transit()
                js("window.__aay&&window.__aay.mini(false)")
            }
            if (startMs != null) seekTo(startMs)
            if (!snapshot.value.playing) autoPlay()
            return
        }
        val autoplayUrl = if (playRoute && !explicitPaused && target.getQueryParameter("aay") == null)
            target.buildUpon().appendQueryParameter("aay", "1").build().toString()
        else destination
        if (playRoute && !explicitPaused) AppLog.add("PLAY", "Selected video: autoplay requested")
        load(autoplayUrl)
    }

    // ---------------------------------------------------------------- mini player

    /** FULL: the page fills the screen. MINI: small floating video over the browse page. HIDDEN: closed. */
    enum class PlayerMode { FULL, MINI, HIDDEN }

    val mode = MutableStateFlow(PlayerMode.FULL)
    private val openDeduper = SelectionDeduper()

    fun isOnWatchPage(): Boolean {
        val route = runCatching { Uri.parse(web?.url.orEmpty()).path.orEmpty() }.getOrDefault("")
        // A YouTube SPA video may already be playing before WebView.url/history changes.
        // The media snapshot is authoritative during that brief navigation gap.
        return route.startsWith("/watch") || route.startsWith("/live/") ||
            onWatchPage.value || (snapshot.value.playing && snapshot.value.id.isNotBlank())
    }

    // Track the WebView-owning process separately from the media service and
    // Chromium's sandboxed renderer. Renderer CPU requires external UID/PID ADB
    // sampling on Android; a JS callback cannot accurately measure that CPU.
    private var perfElapsed = 0L
    private var perfCpu = 0L
    private val recentWebCpu = ArrayDeque<Int>()
    private val previewPressure = ProcessPressurePolicy()
    internal fun underProcessPressure(): Boolean = previewPressure.active(SystemClock.elapsedRealtime())

    internal fun onJsPerformance(payload: String, generation: Int) {
        if (payload.length > 1024) return
        main.post {
            if (generation != webGeneration || web == null) return@post
            val d = runCatching { JSONObject(payload) }.getOrNull() ?: return@post
            val sec = d.optInt("sec", 0)
            if (sec !in 10..120) return@post
            fun metric(key: String, max: Int = 100_000): Int = d.optInt(key, 0).coerceIn(0, max)
            val now = SystemClock.elapsedRealtime()
            val cpu = android.os.Process.getElapsedCpuTime()
            val pct = if (perfElapsed > 0 && now > perfElapsed && cpu >= perfCpu)
                ((cpu - perfCpu) * 100 / (now - perfElapsed)).toInt().coerceIn(0, 1000) else -1
            perfElapsed = now
            perfCpu = cpu
            if (pct >= 0) {
                previewPressure.update(pct, now)
                recentWebCpu.addLast(pct)
                if (recentWebCpu.size > 10) recentWebCpu.removeFirst()
            }
            val avg = if (recentWebCpu.isEmpty()) -1 else recentWebCpu.sum() / recentWebCpu.size
            val peak = recentWebCpu.maxOrNull() ?: -1
            val droppedPct = d.optInt("droppedPct", -1).coerceIn(-1, 100)
            // Audio-only windows decode no frames: -1 never counts as dropping.
            PerformanceMonitor.onDroppedFrames(if (d.optBoolean("nativeAudioOwns", false)) -1 else droppedPct)
            val tier = d.optString("tier").take(12).filter { it.isLetterOrDigit() }
            val codec = d.optString("codec").take(45).filter { it.isLetterOrDigit() || it in ".,- " }
            AppLog.add("PERF", "sec=$sec webCpuPct=$pct webAvgPct=$avg webPeakPct=$peak previewPressure=${previewPressure.active(now)} " +
                "rendererCpuPct=adb-only domTicks=${metric("ticks")} domMs=${metric("tickMs")} " +
                "mutations=${metric("mutations")} resourceLoads=${metric("sourceLoads")} " +
                "layoutChanges=${metric("layout")} refits=${metric("refits")} " +
                "recoveries=${metric("recoveries")} longTasks=${metric("longTasks")} " +
                "frames=${metric("frames")} droppedPct=$droppedPct " +
                "decodedFrames=${metric("decoded")} presentedEstFrames=${metric("presentedEst")} " +
                "displayedFrames=${d.optInt("displayed", -1).coerceIn(-1, 1_000_000)} " +
                "bufferEvents=${metric("bufferEvents", 1_000)} bufferMs=${metric("bufferMs", 600_000)} " +
                "clockStallMs=${metric("clockStallMs", 600_000)} " +
                "dupReports=${metric("dupReports")} domObserved=${d.optBoolean("observed", true)} " +
                "perfLevel=${PerformanceMonitor.level.value} mirrorRenderer=${PhoneMirror.rendererAllocated} " +
                "size=${metric("width", 8192)}x${metric("height", 8192)} tier=$tier " +
                "codec=${codec.ifEmpty { "unknown" }} thermal=${thermalStatus()} " +
                "audioOnly=${d.optBoolean("audioOnly", false)} car=$carShown mode=${mode.value} " +
                "javaHeapKb=${(Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / 1024} " +
                "nativeHeapKb=${android.os.Debug.getNativeHeapAllocatedSize() / 1024}")
            if (Features.current.nativeAudio) {
                fun audioCount(key: String) = d.optInt(key, -1).coerceIn(-1, 256)
                AppLog.add("NATIVE-AUDIO", "probe=metadata-only audioFormats=${audioCount("audioFormats")} " +
                    "direct=${audioCount("audioDirect")} wrapped=${audioCount("audioWrapped")} " +
                    "aac=${audioCount("audioAac")} opus=${audioCount("audioOpus")} " +
                    "sabr=${audioCount("audioSabr")} hls=${audioCount("audioHls")} dash=${audioCount("audioDash")}")
            }
            AppLog.add("PLAYER-UI", "centerCount=${metric("centerCount", 20)} centerPlaying=${d.optBoolean("centerPlaying", false)} mode=${mode.value}")
            AppLog.add("PERF", "engine ${PerformanceMonitor.summary()} " +
                "rendererCpuPct=adb-only(${PerformanceMonitor.diagnostics.liveRenderers()} live)")
        }
    }

    /** Adaptive performance level changed: page-side work, quality ceiling and diagnostics window follow. */
    internal fun applyPerformanceLevel(level: PerfLevel) {
        js("window.__aay&&window.__aay.perfLevel&&window.__aay.perfLevel(${level.ordinal},${PerformanceMonitor.diagnosticsIntervalMs()})")
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

    /** A speed the user picked in YouTube's own menu becomes the default, when remembered. */
    fun requestSpeedFromBridge(rate: Double, generation: Int) {
        if (!rate.isFinite() || rate < 0.25 || rate > 2.0) return
        main.post {
            val speed = PlayerPrefsPolicy.normalizeSpeed(rate.toFloat())
            if (generation != webGeneration || !Features.current.rememberSpeed ||
                speed == Features.current.playbackSpeed) return@post
            Features.update { it.copy(playbackSpeed = speed) }
        }
    }

    /** Swipe down on the video (or back while it plays): shrink it and browse YouTube underneath. */
    fun requestMiniFromBridge(generation: Int) {
        main.post {
            if (generation != webGeneration) return@post
            // From the filled landscape video, the first swipe shows the watch page;
            // swiping the player there again opens the mini player.
            if (landscapeFill.value) dismissLandscapeFill() else enterMini()
        }
    }

    fun setLandscapeFill(on: Boolean) {
        if (landscapeFill.value == on) return
        landscapeFill.value = on
        transit()
        js("window.__aay&&window.__aay.land&&window.__aay.land($on)")
    }

    fun enterMini() {
        if (fullscreenView.value != null || !isOnWatchPage() || mode.value == PlayerMode.MINI) return
        setCarPage(false)
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
        // Like the YouTube app: expanding sideways shows the watch page and playlist.
        onFullscreenClosed()
        transit()
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
    private val sessionGate = SessionResetGate()
    val sessionClearing: Boolean get() = sessionGate.clearing
    private val sessionHomePending: Boolean get() = sessionGate.homePending
    private val sessionCookiePending: Boolean get() = sessionGate.cookiePending
    private var sessionResetUrl: String? = null
    private val sessionResetTimeout = Runnable {
        if (sessionClearing) {
            AppLog.add("ACCOUNT", "Session reset timed out; clearing the playback document")
            resetSessionPlayback()
            web?.stopLoading()
            web?.loadUrl("about:blank")
            youtubeAccount.value = YouTubeAccountState.CHECKING
            // Cookie removal cannot be cancelled. Keep authentication blocked until its callback.
            if (sessionCookiePending) {
                AppLog.add("ACCOUNT", "Cookie removal still pending; authentication remains blocked")
            } else {
                finishSessionReset()
            }
        }
    }

    private fun resetSessionPlayback() {
        NativePlayback.release()
        playbackWanted = false
        mainFrameAllowsPlayback = false
        expectedWatchVideoId = null
        pendingUrl = home()
        lastUrl = home()
        saveUrl(home())
        snapshot.value = Snap()
        selectedWatchVideoId.value = ""
        send(Msg.STATE, Snap().toBundle())
    }

    private fun finishSessionReset() {
        if (!sessionGate.finish()) return
        main.removeCallbacks(sessionResetTimeout)
        sessionResetUrl = null
        BrowseHost.onSessionCleared()
    }

    fun clearSession() {
        if (sessionClearing) return
        cmd("pause")
        val serial = sessionGate.begin() ?: return
        sessionResetUrl = null
        // The phone's secondary player stops and drops pending work before any data is cleared.
        PhoneMirror.beginSignOut()
        BrowseHost.beginAccountClear()
        web?.stopLoading()
        web?.loadUrl("about:blank")
        resetSessionPlayback()
        main.removeCallbacks(sessionResetTimeout)
        main.postDelayed(sessionResetTimeout, 20_000L)
        youtubeAccount.value = YouTubeAccountState.CHECKING
        CookieManager.getInstance().removeAllCookies {
            main.post {
                if (!sessionGate.cookiesFinished(serial)) return@post
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                resetSessionPlayback()
                youtubeAccount.value = YouTubeAccountState.SIGNED_OUT
                sessionResetUrl = Uri.parse(home()).buildUpon().appendQueryParameter("aay_reset", serial.toString()).build().toString()
                main.removeCallbacks(sessionResetTimeout)
                main.postDelayed(sessionResetTimeout, 20_000L)
                val current = web
                current?.stopLoading()
                current?.clearCache(true)
                if (current == null) finishSessionReset() else {
                    pendingUrl = null
                    current.loadUrl(sessionResetUrl!!)
                }
            }
        }
    }

    /** A deliberate pause from YouTube's native page controls is authoritative,
     * including while in PiP/mini/car. Bridge calls arrive off the main thread. */
    fun onJsFullscreenButton(generation: Int) {
        if (generation != webGeneration) return
        main.post { if (generation == webGeneration) toggleFilledVideo() }
    }

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
        main.post { if (generation == webGeneration && !sessionClearing) youtubeAccount.value = state }
    }

    fun exitFullscreen() {
        val cb = customCallback
        customCallback = null
        fullscreenView.value = null
        cb?.onCustomViewHidden()
        onFullscreenClosed()
    }

    /** Leaving YouTube's fullscreen while sideways shows the watch page, not our fill layout. */
    private fun onFullscreenClosed() {
        if (app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            landscapeDismissed.value = true
    }

    // ---------------------------------------------------------------- freeze / release

    // Nothing playing and no screen open: stop the page's timers and scripts after a short wait.
    private fun keepRecovering(): Boolean = playbackWanted &&
        (Features.current.backgroundPlay || carShown || carAudioRetained.value || inPip.value || shown)

    private val freezeSoon = Runnable {
        if (!shown && !snapshot.value.playing && !keepRecovering()) freeze()
    }

    private fun scheduleFreeze() {
        main.removeCallbacks(freezeSoon)
        // A previously playing stream may have dropped silently. Leave the
        // watchdog active until Play succeeds, the user pauses, or Stop/Quit.
        // Background play OFF still permits the normal idle freeze offscreen.
        if (keepRecovering()) {
            AppLog.add("POWER", "Idle freeze skipped while playback recovery is requested")
            return
        }
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
        AppLog.add("POWER", "Page frozen playing=${snapshot.value.playing} wanted=$playbackWanted")
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
        // The phone's secondary player never outlives the engine it follows (Quit, idle shutdown).
        if (!preserveCarOwners) {
            if (!sessionCookiePending) {
                main.removeCallbacks(sessionResetTimeout)
                sessionGate.cancelIfSafe()
                sessionResetUrl = null
            }
            PhoneMirror.release()
        }
        // No renderer, no playback session: the performance engine stops with it (any release).
        PerformanceMonitor.reset()
        NativePlayback.release()
        NetworkMonitor.stop()
        main.removeCallbacks(networkResume)
        main.removeCallbacks(freezeSoon)
        main.removeCallbacks(backgroundPlaybackProbe)
        playbackWanted = false
        // Quit, idle shutdown and renderer replacement: no hold, listener, timed wake
        // lock or delayed resume may outlive (or act on the successor of) this renderer.
        cancelOtherMediaWork("WebView released")
        unregisterThermalWatch()
        resetResumeBudget()
        cancelTransportTrace()
        main.removeCallbacks(modeDeadline)
        modeHandoffStartedAt = 0L
        modeMeter.cancel()
        carAudioRetained.value = false
        retainedRendererDetached = false
        forcedCarRecreate = null
        carRebind = null
        carRebindOwner = null
        main.removeCallbacks(carRenderVerify)
        carVerifyEpoch++
        finishDisplayTrial("renderer-released")
        carTrialTimeout?.let(main::removeCallbacks)
        carTrialTimeout = null
        exitFullscreen()
        val w = web
        web = null
        webGeneration++ // Ignore already-queued bridge reports from the disposed renderer.
        perfElapsed = 0L
        perfCpu = 0L
        recentWebCpu.clear()
        previewPressure.reset()
        webResizeCount = 0
        lastWebResizeLogAt = 0L
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
        if (w != null) PerformanceMonitor.diagnostics.rendererReleases++
        wrapper = null
        webDisplayId = null
        carWindowId = null
        carBoundDisplayId = null
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
        AppLog.add("LINK", "Media service disconnected playing=${snapshot.value.playing} wanted=$playbackWanted " +
            "shown=$shown frozen=$frozen")
        link = null
        linkEpoch = -1
        if (!shown && !snapshot.value.playing) scheduleFreeze()
    }

    // ---------------------------------------------------------------- commands from the media process

    fun onCommand(m: Message) {
        val d = m.data ?: Bundle.EMPTY
        AppLog.add("LINK", "From media process: what=${m.what} keys=${d.keySet().sorted().joinToString()}")
        if (sessionClearing && m.what in setOf(Msg.OPEN, Msg.SEARCH, Msg.PLAY, Msg.SEEK, Msg.NEXT, Msg.PREV, Msg.RATE)) return
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
            Msg.OPEN -> {
                traceTransport("OPEN", d.getString("id").orEmpty())
                open(d.getString("id").orEmpty(), d.getLong("sec"))
            }
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
                cancelTransportTrace()
                userPausedAt = SystemClock.elapsedRealtime()
                cmd("pause")
                send(Msg.TRANSPORT_ACK, Bundle().apply {
                    putInt("command", Msg.PAUSE)
                    putLong("terminalId", d.getLong("terminalId", -1L))
                })
            }
            Msg.SEEK -> seekTo(d.getLong("ms"))
            Msg.NEXT -> {
                traceTransport("NEXT")
                cmd("next")
            }
            Msg.PREV -> {
                traceTransport("PREVIOUS")
                cmd("prev")
            }
            Msg.RATE -> cmd("rate", d.getFloat("rate", 1f).toString())
            Msg.STOP -> {
                cancelTransportTrace()
                cmd("pause")
                if (!shown) release()
                send(Msg.TRANSPORT_ACK, Bundle().apply {
                    putInt("command", Msg.STOP)
                    putLong("terminalId", d.getLong("terminalId", -1L))
                })
            }
            Msg.QUIT -> {
                cancelTransportTrace()
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
            if (!snapshot.value.playing) autoPlay()
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
        if (sessionClearing) return
        if (!isSafePageUrl(url)) {
            AppLog.add("NAV", "Ignored unsafe direct WebView load")
            return
        }
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
            val watch = Uri.parse(HOME_MOBILE).buildUpon().path("/watch")
                .appendQueryParameter("v", u.lastPathSegment.orEmpty())
            for (key in listOf("t", "start", "list", "index", "aay")) {
                u.getQueryParameter(key)?.let { watch.appendQueryParameter(key, it) }
            }
            return watch.build().toString()
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
        var webNativeMarker = false
        val s = runCatching {
            val o = JSONObject(json)
            webNativeMarker = o.optBoolean("native")
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
                    PlaybackReportPolicy.stale(s.id, expectedWatchVideoId, staleReportId))) {
                AppLog.add("MEDIA", "Ignored stale video report after watch navigation")
                return@post
            }
            // Native audio (experimental): the web's metadata with the native clock while it owns.
            publish(NativePlayback.mergeWebState(s, webNativeMarker))
        }
    }

    /** Native engine state for the media session; merged onto the web's metadata. */
    internal fun publishFromNative(update: (Snap) -> Snap) {
        main.post { if (!sessionClearing && NativePlayback.nativeOwns) publish(update(snapshot.value)) }
    }

    /**
     * Native audio ownership in the page: on = pause the web video and stop its loading
     * (no duplicate audio, no video download/decoding); off = continue at [positionMs]
     * (-1 keeps position) and play only when [resume].
     */
    internal fun nativeAudioOwner(on: Boolean, positionMs: Long, resume: Boolean) {
        val sec = if (positionMs < 0L) -1.0 else positionMs / 1000.0
        js("window.__aay&&window.__aay.nativeAudio&&window.__aay.nativeAudio($on,$sec,$resume)")
    }

    /** Read a minimal authorized session descriptor directly from the current document.
     * Never log the returned body or store it on disk.
     */
    internal fun requestLocalAudio(videoId: String, done: (String?) -> Unit) {
        val w = web ?: run { done(null); return }
        val gen = webGeneration
        if (sessionClearing || shown || inPip.value || carProjectionActive() ||
            !Features.current.nativeAudio || !Features.current.localSabr ||
            !isSafePageUrl(w.url.orEmpty())) { done(null); return }
        val id = JSONObject.quote(videoId)
        w.evaluateJavascript("window.__aay&&window.__aay.localAudioDescriptor&&window.__aay.localAudioDescriptor($id)") { value ->
            done(value?.takeIf { web === w && webGeneration == gen && !sessionClearing &&
                !shown && !inPip.value && !carProjectionActive() &&
                Features.current.nativeAudio && Features.current.localSabr &&
                snapshot.value.id == videoId && it.length in 2..131072 && it != "null" })
        }
    }

    internal fun confirmNativeSilence(videoId: String, confirmed: (Boolean) -> Unit) {
        val w = web ?: run { confirmed(false); return }
        val gen = webGeneration
        val id = JSONObject.quote(videoId)
        w.evaluateJavascript("!!(window.__aay&&window.__aay.confirmNativeSilence&&window.__aay.confirmNativeSilence($id))") { value ->
            confirmed(web === w && webGeneration == gen && !sessionClearing && value == "true")
        }
    }

    internal fun carProjectionActive(): Boolean = carShown || carAudioRetained.value

    // JavaScript timers can be delayed after screen-off. Native probes keep
    // recovery checks scheduled while playback is wanted; JS respects Pause.
    private val backgroundPlaybackProbe = object : Runnable {
        override fun run() {
            if (!playbackWanted || (!shown && !carShown && !inPip.value &&
                    !Features.current.backgroundPlay && !carAudioRetained.value) || web == null) return
            js("window.__aay&&window.__aay.checkBackgroundPlayback&&window.__aay.checkBackgroundPlayback()")
            main.postDelayed(this, 15_000L)
        }
    }

    private fun updateBackgroundPlaybackProbe() {
        main.removeCallbacks(backgroundPlaybackProbe)
        if (playbackWanted && web != null &&
            (shown || carShown || carAudioRetained.value || inPip.value || Features.current.backgroundPlay)) {
            main.postDelayed(backgroundPlaybackProbe, 15_000L)
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

    private fun resetResumeBudget() {
        resumeTries = 0
        resumeWindowAt = 0L
        unexpectedPauseRecoveryUntil = 0L
    }

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
            new.id != old.id || new.id.isBlank() || otherMediaHeldSince != 0L) return
        val now = SystemClock.elapsedRealtime()
        val background = !shown && !inPip.value && !carShown
        // Background-play OFF is respected when fully hidden. Unexpected pauses
        // in visible phone/car, mini-player or PiP still deserve recovery.
        if (background && !Features.current.backgroundPlay && !carAudioRetained.value) return
        val mini = mode.value == PlayerMode.MINI && !inPip.value
        if (new.userPaused || !playbackWanted || now - userPausedAt <= 5_000L) return
        unexpectedPauseRecoveryUntil = now + 120_000L
        // The connection dropped: retrying now fails and would use up the budget, and our own
        // residual output would pass for another app's audio. Resume when it returns.
        if (!NetworkMonitor.online) {
            AppLog.add("PLAY", "Pause while offline: resume deferred until the connection returns")
            return
        }
        // During the bounded car handoff the page owns fast recovery. Avoid
        // competing native Play commands while the decoder is changing surfaces.
        if (modeHandoffStartedAt != 0L && now - modeHandoffStartedAt < 4_000L) {
            AppLog.add("PLAY", "Native recovery deferred to car mode handoff; fallback armed")
            main.removeCallbacks(droppedResume)
            main.postDelayed(droppedResume, (4_000L - (now - modeHandoffStartedAt)).coerceAtLeast(250L))
            return
        }
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
        // A focus handoff pauses us before the other app's audio starts: settle first.
        main.removeCallbacks(droppedResume)
        main.postDelayed(droppedResume, OtherMediaHoldPolicy.SETTLE_MS)
    }

    private fun cancelOtherMediaWork(reason: String) {
        main.removeCallbacks(droppedResume)
        main.removeCallbacks(droppedResumeJudge)
        endOtherMediaHold(reason)
        unregisterOtherMediaWatch() // idempotent: also clears a stray timed wake lock
    }

    // Device heat: above SEVERE the page caps quality at 1080p until it cools.
    private var thermalListener: Any? = null
    private fun registerThermalWatch() {
        if (thermalListener != null || android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val pm = app.getSystemService(android.os.PowerManager::class.java)
            val listener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                if (thermalListener != null && web != null) {
                    AppLog.add("VIDEO", "Thermal status=$status")
                    js("window.__aay&&window.__aay.thermal&&window.__aay.thermal($status)")
                    PerformanceMonitor.onThermalChanged()
                }
            }
            pm.addThermalStatusListener(app.mainExecutor, listener)
            thermalListener = listener
        }
    }

    private fun unregisterThermalWatch() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val listener = thermalListener as? android.os.PowerManager.OnThermalStatusChangedListener ?: return
        thermalListener = null
        runCatching {
            app.getSystemService(android.os.PowerManager::class.java).removeThermalStatusListener(listener)
        }
    }
    private fun thermalStatus(): Int = if (android.os.Build.VERSION.SDK_INT < 29) 0 else
        runCatching { app.getSystemService(android.os.PowerManager::class.java).currentThermalStatus }.getOrDefault(0)

    internal fun postOtherMediaHold(generation: Int) {
        main.post {
            if (generation != webGeneration) return@post
            val current = snapshot.value
            if (!current.playing && !current.buffering && !current.userPaused &&
                !current.ended && current.id.isNotBlank() && playbackWanted &&
                !NetworkMonitor.recentlyDisrupted() && otherMediaActive()) {
                startOtherMediaHold("page watchdog")
            } else if (otherMediaHeldSince == 0L) {
                // The bridge returned a provisional hold to JS. Release it if
                // playback changed or the external output stopped before validation.
                js("window.__aay&&window.__aay.mediaHold&&window.__aay.mediaHold(false)")
            }
        }
    }

    private val droppedResume = Runnable {
        val s = snapshot.value
        if (s.playing || s.buffering || !playbackWanted || s.userPaused || s.ended || s.id.isBlank()) return@Runnable
        // Allow native playback state to settle before checking other media.
        main.removeCallbacks(droppedResumeJudge)
        main.postDelayed(droppedResumeJudge, OtherMediaHoldPolicy.SELF_SILENCE_MS)
    }

    private val droppedResumeJudge = Runnable {
        val s = snapshot.value
        if (s.playing || s.buffering || !playbackWanted || s.userPaused || s.ended || s.id.isBlank()) return@Runnable
        // A pause right after a connection loss or network switch is the network's: recover the
        // stream (the player may have reset after its error) instead of holding for "other media".
        if (NetworkMonitor.recentlyDisrupted()) {
            AppLog.add("FOCUS", "Native recovery decision: network disruption, no other-media hold online=${NetworkMonitor.online}")
            if (NetworkMonitor.online) recoverAfterNetwork("recent disruption")
            return@Runnable
        }
        val active = otherMediaActive()
        AppLog.add("FOCUS", "Native recovery decision: external audio or residual output=$active")
        if (active) startOtherMediaHold("native resume") else autoPlay()
    }

    // ------------------------------------------------- other app's media (audio focus)
    private var otherMediaHeldSince = 0L
    private var otherMediaQuietSince = 0L

    internal fun otherMediaActive(): Boolean = runCatching {
        val am = app.getSystemService(android.media.AudioManager::class.java)
        OtherMediaHoldPolicy.othersActive(am.isMusicActive, am.mode)
    }.getOrDefault(false)

    // ---------------------------------------------------------------- connection loss
    private var lastGoodId = ""
    private var lastGoodPosMs = 0L

    /** Called from the connectivity thread. */
    internal fun onNetworkLost() = main.post {
        if (web == null) return@post
        val s = snapshot.value
        AppLog.add("NET", "Connection lost wanted=$playbackWanted playing=${s.playing} posMs=${s.posMs}")
        js("window.__aay&&window.__aay.network&&window.__aay.network(false)")
    }

    /** Called from the connectivity thread when a validated connection returns or the network switches. */
    internal fun onNetworkRestored(outageMs: Long) = main.post {
        if (web == null) return@post
        js("window.__aay&&window.__aay.network&&window.__aay.network(true)")
        val s = snapshot.value
        val resume = NetworkRecoveryPolicy.shouldResume(s, playbackWanted, otherMediaHeldSince != 0L)
        AppLog.add("NET", "Connection restored outageMs=$outageMs resume=$resume wanted=$playbackWanted " +
            "playing=${s.playing} buffering=${s.buffering} userPaused=${s.userPaused}")
        if (!resume) return@post
        resetResumeBudget() // failures while offline do not count
        unfreeze()
        main.removeCallbacks(networkResume)
        main.postDelayed(networkResume, NetworkRecoveryPolicy.SETTLE_MS)
    }

    private val networkResume = Runnable { recoverAfterNetwork("connection restored") }

    /** Resume the same video at its last good position (YouTube may have reset it after the error). */
    private fun recoverAfterNetwork(reason: String) {
        val s = snapshot.value
        if (!NetworkRecoveryPolicy.shouldResume(s, playbackWanted, otherMediaHeldSince != 0L)) return
        if (NativePlayback.nativeOwns) { autoPlay(); return }
        val posMs = NetworkRecoveryPolicy.resumePositionMs(s, lastGoodId, lastGoodPosMs)
        AppLog.add("PLAY", "Network recovery ($reason) id=${s.id} posMs=$posMs")
        js("window.__aay&&window.__aay.recoverNetwork&&window.__aay.recoverNetwork(${posMs / 1000.0})")
    }

    internal fun startOtherMediaHold(reason: String) {
        if (otherMediaHeldSince != 0L || !playbackWanted) return
        // Native audio is the media heard now (Media3 handles focus itself): not "other media".
        if (NativePlayback.nativeOwns) return
        otherMediaHeldSince = SystemClock.elapsedRealtime()
        otherMediaQuietSince = 0L
        main.removeCallbacks(droppedResume)
        main.removeCallbacks(droppedResumeJudge)
        AppLog.add("FOCUS", "Another app is playing media; paused until it stops ($reason)")
        js("window.__aay&&window.__aay.mediaHold&&window.__aay.mediaHold(true)")
        registerOtherMediaWatch()
        main.removeCallbacks(otherMediaPoll)
        main.postDelayed(otherMediaPoll, 1_000L)
        // Yield foreground playback immediately, even if Chromium has not yet
        // delivered its asynchronous pause event. Preserve playbackWanted so
        // the other-media watcher can resume after the focus handoff.
        publish(snapshot.value.copy(playing = false, buffering = false, userPaused = false))
    }

    // No CPU wake lock while held: Android wakes us when the other app's player
    // or the call state changes; a short timed wake lock covers the quiet check.
    private var playbackWatch: android.media.AudioManager.AudioPlaybackCallback? = null
    private var modeWatch: Any? = null
    private val holdWake by lazy {
        app.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AAYoutube:media-hold")
            .apply { setReferenceCounted(false) }
    }
    private fun pokeOtherMediaPoll() {
        if (otherMediaHeldSince == 0L) return
        runCatching { holdWake.acquire(OtherMediaHoldPolicy.WAKE_CHECK_MS) }
        main.removeCallbacks(otherMediaPoll)
        main.post(otherMediaPoll)
    }
    private fun registerOtherMediaWatch() {
        val am = runCatching { app.getSystemService(android.media.AudioManager::class.java) }.getOrNull() ?: return
        if (playbackWatch == null) {
            val cb = object : android.media.AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                    pokeOtherMediaPoll()
                }
            }
            runCatching { am.registerAudioPlaybackCallback(cb, main) }.onSuccess { playbackWatch = cb }
        }
        if (modeWatch == null && android.os.Build.VERSION.SDK_INT >= 31) {
            val l = android.media.AudioManager.OnModeChangedListener { main.post { pokeOtherMediaPoll() } }
            runCatching { am.addOnModeChangedListener(app.mainExecutor, l) }.onSuccess { modeWatch = l }
        }
    }
    private fun unregisterOtherMediaWatch() {
        val am = runCatching { app.getSystemService(android.media.AudioManager::class.java) }.getOrNull()
        playbackWatch?.let { cb -> runCatching { am?.unregisterAudioPlaybackCallback(cb) } }
        playbackWatch = null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (modeWatch as? android.media.AudioManager.OnModeChangedListener)?.let { l ->
                runCatching { am?.removeOnModeChangedListener(l) }
            }
        }
        modeWatch = null
        runCatching { if (holdWake.isHeld) holdWake.release() }
    }

    private fun endOtherMediaHold(reason: String, giveUp: Boolean = false) {
        if (otherMediaHeldSince == 0L) return
        otherMediaHeldSince = 0L
        otherMediaQuietSince = 0L
        main.removeCallbacks(otherMediaPoll)
        unregisterOtherMediaWatch()
        js("window.__aay&&window.__aay.mediaHold&&window.__aay.mediaHold(false,$giveUp)")
        AppLog.add("FOCUS", "Media hold ended: $reason")
    }

    // Runs while held; the media service keeps a CPU wake lock because the
    // snapshot stays `recovering` (playback still wanted), so this works screen-off.
    private val otherMediaPoll = object : Runnable {
        override fun run() {
            if (otherMediaHeldSince == 0L) return
            if (!playbackWanted) { endOtherMediaHold("playback no longer wanted"); return }
            val now = SystemClock.elapsedRealtime()
            val active = otherMediaActive()
            otherMediaQuietSince = OtherMediaHoldPolicy.quietSince(otherMediaQuietSince, active, now)
            when (OtherMediaHoldPolicy.step(active, otherMediaHeldSince, otherMediaQuietSince, now)) {
                // Screen off, this delay may stretch while the CPU sleeps; the
                // playback/mode callbacks wake it on the change that matters.
                OtherMediaHoldPolicy.Step.KEEP_HOLDING -> main.postDelayed(this,
                    OtherMediaHoldPolicy.pollDelay(active, otherMediaQuietSince, now))
                OtherMediaHoldPolicy.Step.RESUME -> {
                    endOtherMediaHold("other media stopped")
                    if (keepRecovering()) autoPlay()
                }
                OtherMediaHoldPolicy.Step.GIVE_UP -> {
                    endOtherMediaHold("hold expired", giveUp = true)
                    playbackWanted = false
                    publish(snapshot.value) // drop `recovering`: release wake lock/foreground
                }
            }
        }
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
        if (sessionClearing) return
        confirmTransport(incoming)
        reportModeHandoff(incoming)
        val previous = snapshot.value
        if (incoming.id != previous.id) resetResumeBudget()
        if (incoming.userPaused && !incoming.playing) {
            playbackWanted = false
            userPausedAt = SystemClock.elapsedRealtime()
        } else if (incoming.playing) playbackWanted = true
        if (incoming.ended || incoming.id.isBlank()) playbackWanted = false
        if (incoming.playing || incoming.buffering || incoming.id != previous.id || !playbackWanted) {
            // A decision for an older video or playback state is obsolete.
            main.removeCallbacks(droppedResume)
            main.removeCallbacks(droppedResumeJudge)
        }
        if (otherMediaHeldSince != 0L && (incoming.playing || incoming.id != previous.id || !playbackWanted))
            endOtherMediaHold(if (incoming.playing) "playing again" else "selection changed, paused or ended")
        // Keep foreground media priority while the active watchdog retries a
        // spontaneous pause. Never preserve this for explicit Pause or ending.
        val s = incoming.copy(recovering = !incoming.playing && !incoming.userPaused &&
            !incoming.ended && incoming.id.isNotBlank() && keepRecovering(),
            held = otherMediaHeldSince != 0L && !incoming.playing)
        resumeIfDropped(previous, s)
        ensurePlaybackService(previous, s)
        val key = "${s.id}|${s.playing}|${s.buffering}|${s.ad}|${s.ended}|${s.live}"
        if (key != lastLogged) {
            lastLogged = key
            AppLog.add("PLAY", "id=${s.id} \"${s.title}\" playing=${s.playing} buffering=${s.buffering} ad=${s.ad} ended=${s.ended} live=${s.live} pos=${s.posMs / 1000}s/${s.durMs / 1000}s rate=${s.rate} next=${s.canNext} prev=${s.canPrev}")
        }
        if (s.id.isNotBlank() && s.posMs > 0L && !s.ad) { lastGoodId = s.id; lastGoodPosMs = s.posMs }
        snapshot.value = s
        updateBackgroundPlaybackProbe()
        PhoneMirror.onEngineState(previous, s)
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
            if (sessionClearing && request.isForMainFrame && uri.toString() != sessionResetUrl) return true
            AppLog.add("NAV", "Navigate host=${uri.host.orEmpty()} main=${request.isForMainFrame} gesture=${request.hasGesture()} redirect=${request.isRedirect}")
            val scheme = uri.scheme.orEmpty()
            if (scheme != "https") return true
            val host = uri.host.orEmpty()
            if (host.equals("youtu.be", true)) {
                view.loadUrl(normalise(uri.toString()))
                return true
            }
            if (!isSafePageUrl(uri.toString())) {
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
            staleReportId = previous.id.takeIf { it.isNotBlank() && it != incomingId }
            expectedWatchVideoId = incomingId?.takeIf { route == "/watch" && it.isNotBlank() }
            selectedWatchVideoId.value = expectedWatchVideoId.orEmpty()
            if (PlaybackRoutePolicy.shouldClearPrevious(route, incomingId, previous.id)) {
                playbackWanted = false
                unexpectedPauseRecoveryUntil = 0L
                snapshot.value = Snap()
                send(Msg.STATE, Snap().toBundle())
                AppLog.add("PLAY", "Navigation cleared previous media session")
            }
            val explicitStart = runCatching { Uri.parse(url).let {
                TimestampParam.parseMs(it.getQueryParameter("t") ?: it.getQueryParameter("start"))
            } }.getOrNull()
            sameVideoReloadPosition = CarRendererReusePolicy.restorePosition(incomingId, outgoingId,
                previous.id, previous.posMs, previous.ended, explicitStart)?.let { previous.id to it }
            AppLog.add("PAGE", "Started host=${runCatching { Uri.parse(url).host }.getOrNull().orEmpty()}" +
                if (sameVideoReloadPosition != null) " (same-video position retained)" else "")
            lyricsClock.value = LyricsClock()
            youtubeAccount.value = YouTubeAccountState.CHECKING
            progress.value = 5
            offline.value = false
            // Older WebViews cannot run a script before the page: inject as early as possible instead.
            if (scriptHandler == null && isYouTubeHost(runCatching { Uri.parse(url).host }.getOrNull()))
                view.evaluateJavascript(script(), null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== web) return
            if (sessionHomePending && url != null && url == sessionResetUrl && view.url == url) finishSessionReset()
            if (sessionClearing) return
            if (mode.value == PlayerMode.MINI) view.evaluateJavascript("window.__aay&&window.__aay.mini(true)", null)
            if (inPip.value) view.evaluateJavascript("window.__aay&&window.__aay.pip(true)", null)
            view.evaluateJavascript("window.__aay&&window.__aay.setBg(${!shown && !inPip.value})", null)
            syncWebAudioMode()
            view.evaluateJavascript("window.__aay&&window.__aay.car(${carDisplay || carShown})", null)
            if (landscapeFill.value) view.evaluateJavascript("window.__aay&&window.__aay.land&&window.__aay.land(true)", null)
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
            AppLog.add("PAGE", "Finished host=${runCatching { Uri.parse(url).host }.getOrNull().orEmpty()}")
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
            val newVideo = parsed?.getQueryParameter("v")
            if (newVideo != expectedWatchVideoId)
                staleReportId = snapshot.value.id.takeIf { it.isNotBlank() && it != newVideo }
            expectedWatchVideoId = newVideo
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
            AppLog.add("NET", "Error ${error.errorCode} host=${request.url.host.orEmpty()} main=${request.isForMainFrame}")
            if (request.isForMainFrame) {
                offline.value = true
                if (view === web && sessionHomePending && request.url.toString() == sessionResetUrl) {
                    resetSessionPlayback()
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    finishSessionReset()
                }
            }
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
            val reopen = if (sessionClearing) home() else if (before.id.isNotBlank()) {
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
            if (shown || mayResume || sessionHomePending) {
                if (!shown) main.post { if (web == null) ensureWeb() }
                webVersion.update { it + 1 }
            }
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            val cleaned = WebConsolePolicy.clean(m.message()) ?: return true
            AppLog.add("JS", "${m.messageLevel()} $cleaned (script:${m.lineNumber()})")
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.value = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customCallback?.onCustomViewHidden()
            customCallback = callback
            transit()
            fullscreenView.value = view
            AppLog.add("VIEW", "Fullscreen player opened")
        }

        override fun onHideCustomView() {
            customCallback = null
            transit()
            fullscreenView.value = null
            onFullscreenClosed()
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
class WebBridge(private val generation: Int, private val bridgeToken: String) {
    private fun authorized(token: String?): Boolean = token == bridgeToken

    @JavascriptInterface
    fun state(json: String, token: String?) {
        if (!authorized(token)) return
        WebHost.onJsState(json, generation)
    }

    /** YouTube's fullscreen / minimise button where the app fills the screen itself. */
    @JavascriptInterface
    fun fsToggle(token: String?) {
        if (!authorized(token)) return
        WebHost.onJsFullscreenButton(generation)
    }

    @JavascriptInterface
    fun userPause(token: String?) {
        if (!authorized(token)) return
        WebHost.onJsUserPause(generation)
    }

    @JavascriptInterface
    fun account(status: String, token: String?) {
        if (!authorized(token)) return
        WebHost.reportAccountStatus(status, generation)
    }

    /** Real HTMLVideoElement position, sampled only while timed lyrics are displayed. */
    @JavascriptInterface
    fun lyricsTime(id: String, seconds: Double, playing: Boolean, buffering: Boolean, rate: Double, token: String?) {
        if (!authorized(token)) return
        WebHost.onJsLyricsTime(id, seconds, playing, buffering, rate, generation)
    }

    /** Aggregated metrics; never URLs, tokens or video IDs. */
    @JavascriptInterface
    fun perf(payload: String, token: String?) {
        if (!authorized(token)) return
        WebHost.onJsPerformance(payload, generation)
    }

    /** Persist only explicit quality menu selections (not YouTube's adaptive downgrades). */
    @JavascriptInterface
    fun quality(value: String, token: String?) {
        if (!authorized(token)) return
        WebHost.requestQualityFromBridge(value, generation)
    }

    /** Persist only speeds picked by the user in YouTube's menu. */
    @JavascriptInterface
    fun speed(rate: Double, token: String?) {
        if (!authorized(token)) return
        WebHost.requestSpeedFromBridge(rate, generation)
    }

    /** True when another app is playing media; also starts the native hold that resumes us later. */
    @JavascriptInterface
    fun otherMediaHold(token: String?): Boolean {
        if (!authorized(token)) return false
        val active = WebHost.otherMediaActive()
        if (active) WebHost.postOtherMediaHold(generation)
        return active
    }

    /** Swipe down on the playing video. */
    @JavascriptInterface
    fun mini(token: String?) {
        if (!authorized(token)) return
        WebHost.requestMiniFromBridge(generation)
    }
}
