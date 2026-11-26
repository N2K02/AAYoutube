package com.aayoutube.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
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
 *
 * Every asynchronous step is tied to a generation (one per loaded page / renderer)
 * and the engine's video id, so a late callback can never touch a newer video.
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
    private var pageId: String? = null
    /** What the UI wants; survives a renderer crash so the new renderer resumes syncing. */
    private var wantVisible = false
    private var visible = false
    private var videoOnly = false
    private var layoutSentTo: WebView? = null
    private var tapDownAt = 0L
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var lastLoadAt = 0L
    private var lastPokeAt = 0L
    private var lastStatsAt = 0L
    private var lastDriftMs: Long? = null
    @Volatile private var generation = 0
    private var syncSeq = 0
    private var syncInFlight = false
    @Volatile private var hiddenFlag = true
    private var transportAt = 0L
    private var adHeld = false
    private var syncDueAt = Long.MAX_VALUE
    private var missingMediaCount = 0
    private var recoveryAttempts = 0
    @Volatile private var startupCap = ""
    private var lastReloadRejectedAt = Long.MIN_VALUE / 2
    private var signingOut = false
    private var suspendedAt = 0L
    private var loggedCrashes = 0
    private val gate = MirrorLoadGate()
    private val nav = NavAuthorizer()
    private val transport = TransportGuard()
    private var syncStartedAt = 0L
    private var customView: View? = null
    private var fullscreenOwner: Activity? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val crashGuard = RateGuard(PhoneMirrorPolicy.MAX_CRASHES, PhoneMirrorPolicy.CRASH_WINDOW_MS)
    private val reloadGuard = RateGuard(PhoneMirrorPolicy.MAX_RELOADS, PhoneMirrorPolicy.RELOAD_WINDOW_MS)
    val stats = MirrorSyncStats()
    @Volatile private var pageHost: String? = "youtube.com"
    val revision = MutableStateFlow(0)

    /** Without document-start injection the mute lock could come too late: no secondary player then. */
    val supported: Boolean by lazy {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO) }.getOrDefault(false)
    }
    /** Too many renderer crashes: the phone shows its browse page instead of looping. */
    val failed = MutableStateFlow(false)
    /** Thermal or sustained-buffering suspension: the phone shows the lightweight fallback meanwhile. */
    val suspended = MutableStateFlow(false)

    fun init(application: Application) { app = application }

    private fun script(): String {
        val js = source ?: app.assets.open("mirror.js").bufferedReader().use { it.readText() }.also { source = it }
        return "if(window.top===window.self)window.__AAYM_TOKEN=" + JSONObject.quote(token) + ";\n" + js
    }

    fun attach(context: Context): WebView {
        val w = web ?: create(context)
        if (fullscreenOwner != null && fullscreenOwner !== activityOf(context)) hideCustomView()
        wrapper?.setBaseContext(context)
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(container: ViewGroup) {
        val mine = web?.parent === container
        container.removeAllViews()
        if (mine) {
            hideCustomView()
            wrapper?.setBaseContext(app)
        }
    }

    /** Shown (full, mini or PiP) or not. Hidden: no decoding, no sync, nothing restarts. */
    fun setVisible(on: Boolean) {
        wantVisible = on
        applyVisible()
    }

    private fun applyVisible() {
        val on = wantVisible && !failed.value
        val w = web
        if (w == null) {
            visible = on
            refreshHidden()
            main.removeCallbacks(syncTick)
            syncDueAt = Long.MAX_VALUE
            if (on && suspended.value) scheduleSync(0L)
            return
        }
        if (visible == on) return
        visible = on
        refreshHidden()                  // new documents (and frames) start in this state
        main.removeCallbacks(syncTick)
        syncDueAt = Long.MAX_VALUE
        main.removeCallbacks(idleRelease)
        if (on) {
            stats.resumes++
            w.onResume()
            w.resumeTimers()
            scheduleSync(0L)
        } else {
            stats.pauses++
            generation++            // late callbacks of the visible period are now stale
            syncInFlight = false
            hideCustomView()
            w.evaluateJavascript("window.__aayMirror&&window.__aayMirror.stop()", null)
            w.onPause()
            nav.reset()
            transportAt = 0L
            // Pausing a WebView does not promise the hardware decoder is freed: after a bounded
            // hidden period the whole secondary renderer is released.
            main.postDelayed(idleRelease, PhoneMirrorPolicy.HIDDEN_RELEASE_MS)
        }
    }

    private val idleRelease = Runnable {
        if (!visible && web != null) {
            // No revision bump: nothing is composed while hidden, so nothing recreates the renderer.
            // The next time the phone player is shown, attach() allocates a fresh one.
            AppLog.add("MIRROR", "Phone player released after ${PhoneMirrorPolicy.HIDDEN_RELEASE_MS / 1000}s hidden")
            teardown()
        }
    }

    private fun refreshHidden() { hiddenFlag = !visible || suspended.value || signingOut || adHeld }

    /** After the crash limit the user may try the phone player again. */
    fun retry() {
        teardown()
        crashGuard.reset()
        reloadGuard.reset()
        failed.value = false
        revision.value++
    }

    /** Mini-player and PiP show only the video; full shows YouTube's watch page. */
    fun setVideoOnly(on: Boolean) {
        val w = web
        if (videoOnly == on && layoutSentTo === w) return
        videoOnly = on
        layoutSentTo = w
        w?.evaluateJavascript("window.__aayMirror&&window.__aayMirror.layout($on)", null)
    }

    /** Keep the earliest deadline; updates cannot postpone a pending tick. */
    private fun scheduleSync(delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val due = now + delayMs.coerceAtLeast(0L)
        if (due >= syncDueAt) return
        syncDueAt = due
        main.removeCallbacks(syncTick)
        main.postDelayed(syncTick, (due - now).coerceAtLeast(0L))
    }

    /** The engine's state changed (play/pause/seek/new video): follow now, not at the next interval. */
    fun onEngineState(previous: Snap, next: Snap) {
        if (!PhoneMirrorPolicy.pokeNeeded(previous, next)) return
        main.post {
            if (!visible) return@post
            if (next.id != previous.id) { missingMediaCount = 0; recoveryAttempts = 0 }
            // The engine's intent changed: any answer still on its way carries obsolete state.
            syncSeq++
            syncInFlight = false
            val now = SystemClock.elapsedRealtime()
            scheduleSync((PhoneMirrorPolicy.MIN_POKE_MS - (now - lastPokeAt)).coerceAtLeast(0L))
        }
    }

    fun onExplicitTransport() {
        if (!visible) return
        syncSeq++
        syncInFlight = false
        scheduleSync(PhoneMirrorPolicy.MIN_POKE_MS)
    }

    fun noteDuplicateSelection() { stats.duplicates++ }

    /** Sign-out begins: stop the secondary player and drop every pending request before data is cleared. */
    fun beginSignOut() {
        signingOut = true
        refreshHidden()
        syncSeq++
        generation++
        syncInFlight = false
        nav.reset()
        transport.reset()
        transportAt = 0L
        WebHost.resetSelectionGuards()
        BrowseHost.resetSelectionGuards()
        main.removeCallbacks(syncTick)
        syncDueAt = Long.MAX_VALUE
        web?.evaluateJavascript("window.__aayMirror&&window.__aayMirror.stop()", null)
    }

    /** Account clearing finished: the mirror's page and cache belong to the old account. */
    fun onSessionCleared() {
        signingOut = false
        refreshHidden()
        nav.reset()
        WebHost.resetSelectionGuards()
        BrowseHost.resetSelectionGuards()
        val w = web ?: return
        generation++
        syncSeq++
        syncInFlight = false
        loadedId = ""
        pageId = null
        w.stopLoading()
        w.clearCache(true)
        w.clearHistory()
        w.loadUrl("about:blank")
        if (visible) scheduleSync(0L)
    }

    /** Feature settings that also apply to the phone player. */
    fun applyFeatures(f: FeatureFlags, desktopChanged: Boolean) {
        val w = web ?: return
        w.settings.textZoom = f.pageZoom
        if (desktopChanged) {
            w.settings.userAgentString = WebHost.userAgent(f.desktopSite)
            loadedId = ""        // the next sync reloads the video with the new page type
            generation++
            if (visible) scheduleSync(0L)
        }
    }

    /** Full teardown (projection ended or Quit): no WebView, no timers, no crash history. */
    fun release() {
        teardown()
        crashGuard.reset()
        reloadGuard.reset()
        wantVisible = false
        failed.value = false
        signingOut = false
        WebHost.resetSelectionGuards()
        BrowseHost.resetSelectionGuards()
        main.removeCallbacksAndMessages(null)
        logStats(force = true)
        stats.reset()
        loggedCrashes = 0
        lastStatsAt = 0L
        revision.value++
    }

    private fun teardown() {
        main.removeCallbacks(syncTick)
        syncDueAt = Long.MAX_VALUE
        main.removeCallbacks(idleRelease)
        hiddenFlag = true
        syncSeq++
        // Session-specific state must not leak into the next renderer.
        gate.reset()
        adHeld = false
        transport.reset()
        lastReloadRejectedAt = Long.MIN_VALUE / 2
        suspended.value = false
        suspendedAt = 0L
        nav.reset()
        transportAt = 0L
        lastLoadAt = 0L
        lastPokeAt = 0L
        tapDownAt = 0L
        missingMediaCount = 0
        recoveryAttempts = 0
        startupCap = ""
        disposeRenderer()
        visible = false
    }

    /** Release decoder without losing thermal history or visibility intent. */
    private fun disposeRenderer() {
        hideCustomView()
        val w = web
        web = null
        generation++
        syncInFlight = false
        runCatching { handler?.remove() }
        handler = null
        (w?.parent as? ViewGroup)?.removeView(w)
        w?.stopLoading()
        w?.destroy()
        wrapper = null
        layoutSentTo = null
        loadedId = ""
        pageId = null
        lastDriftMs = null
    }

    /** Load the engine's video, then keep the clock in step. */
    private val syncTick: Runnable = object : Runnable {
        override fun run() {
            syncDueAt = Long.MAX_VALUE
            if (!visible || !wantVisible || failed.value) return
            main.removeCallbacks(this)
            val s = WebHost.snapshot.value
            val now = SystemClock.elapsedRealtime()
            lastPokeAt = now
            logStats(force = false)
            if (signingOut) {
                scheduleSync(PhoneMirrorPolicy.IDLE_SYNC_MS)
                return
            }
            // Load first? No: heat and primary health decide before anything is loaded or synced.
            val thermal = thermalStatus()
            val state = gate.update(thermal, s.buffering, now)
            if (state == MirrorLoadGate.State.SUSPENDED) {
                if (!suspended.value) enterSuspension(thermal, s.buffering, now)
                if (web != null && now - suspendedAt >= PhoneMirrorPolicy.SUSPENDED_RELEASE_MS) disposeRenderer()
                scheduleSync(PhoneMirrorPolicy.IDLE_SYNC_MS)
                return
            }
            val cap = if (state == MirrorLoadGate.State.DEGRADED) "small" else ""
            startupCap = cap
            if (suspended.value) exitSuspension(now)
            val w = web ?: run { visible = false; refreshHidden(); revision.value++; return }
            if (s.id.isBlank() || s.ad) {
                holdForAd(w)
                if (s.ad) stats.ads++
                scheduleSync(PhoneMirrorPolicy.IDLE_SYNC_MS)
                return
            }
            if (adHeld) { adHeld = false; refreshHidden() }
            if (PhoneMirrorPolicy.needsLoad(loadedId, pageId, s.id, now - lastLoadAt)) {
                if (now - lastLoadAt >= PhoneMirrorPolicy.MIN_RELOAD_MS) load(w, s, now)
                scheduleSync(PhoneMirrorPolicy.SYNC_MS)
                return
            }
            if (syncInFlight && now - syncStartedAt < PhoneMirrorPolicy.SYNC_TIMEOUT_MS) {
                stats.overlapSkipped++
                scheduleSync(PhoneMirrorPolicy.SYNC_MS)
                return
            }
            if (syncInFlight) stats.timeouts++
            syncInFlight = true
            syncStartedAt = now
            val seq = ++syncSeq          // a later request supersedes every earlier answer
            val gen = generation
            val id = s.id
            WebHost.mediaClock { clock ->
                val received = SystemClock.elapsedRealtime()
                val stale = seq != syncSeq || web !== w || !visible || suspended.value || signingOut ||
                    !PhoneMirrorPolicy.current(gen, generation, id, clock.id) || WebHost.snapshot.value.id != id
                // The sample was taken somewhere inside [now, received]: use its midpoint, drop old ones.
                val busy = clock.seeking || !clock.ready || WebHost.snapshot.value.buffering
                val advancing = clock.playing && !busy && !WebHost.snapshot.value.userPaused
                val pos = PhoneMirrorPolicy.compensatedPosMs(clock.posMs, advancing, clock.rate, now, received)
                if (stale || pos == null) {
                    stats.stale++
                    if (seq == syncSeq) syncInFlight = false
                    return@mediaClock
                }
                if (clock.ad || WebHost.snapshot.value.ad) {
                    stats.ads++
                    holdForAd(w)
                    return@mediaClock
                }
                w.evaluateJavascript("window.__aayMirror&&window.__aayMirror.sync(" +
                    "$pos,$advancing,${clock.rate},${JSONObject.quote(id)},$busy,${JSONObject.quote(cap)})") { raw ->
                    if (seq != syncSeq || gen != generation || web !== w || suspended.value) { stats.stale++; return@evaluateJavascript }
                    syncInFlight = false
                    val result = SyncResult.parse(raw)
                    stats.record(result)
                    if (result.action in setOf("novideo", "mismatch", "none")) {
                        if (++missingMediaCount >= PhoneMirrorPolicy.MISSING_MEDIA_LIMIT) {
                            missingMediaCount = 0
                            if (++recoveryAttempts > PhoneMirrorPolicy.MAX_MEDIA_RECOVERIES) {
                                failed.value = true
                                teardown()
                            } else {
                                loadedId = ""
                                pageId = null
                                scheduleSync(PhoneMirrorPolicy.MIN_RELOAD_MS)
                            }
                        }
                    } else if (result.driftMs != null) {
                        missingMediaCount = 0
                        recoveryAttempts = 0
                        lastDriftMs = result.driftMs
                    }
                }
            }
            scheduleSync(PhoneMirrorPolicy.nextSyncMs(lastDriftMs))
        }
    }

    private fun holdForAd(w: WebView) {
        if (adHeld) return
        adHeld = true
        nav.reset()
        transport.reset()
        generation++
        refreshHidden()
        syncSeq++
        syncInFlight = false
        w.evaluateJavascript("window.__aayMirror&&window.__aayMirror.stop()", null)
    }

    private fun enterSuspension(thermal: Int, buffering: Boolean, now: Long) {
        suspended.value = true
        suspendedAt = now
        stats.suspensions++
        refreshHidden()
        syncSeq++                       // answers requested before the suspension must not restart playback
        syncInFlight = false
        nav.reset()
        transport.reset()
        generation++
        hideCustomView()
        web?.evaluateJavascript("window.__aayMirror&&window.__aayMirror.stop()", null)
        AppLog.add("MIRROR", "Phone player suspended: thermal=$thermal primaryBuffering=$buffering")
    }

    private fun exitSuspension(now: Long) {
        suspended.value = false
        refreshHidden()
        syncSeq++
        stats.suspendedMs += now - suspendedAt
        AppLog.add("MIRROR", "Phone player resumed after ${(now - suspendedAt) / 1000}s suspended")
        suspendedAt = 0L
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrDefault(0) else 0

    private fun load(w: WebView, s: Snap, now: Long) {
        if (!reloadGuard.record(now)) {
            if (now - lastReloadRejectedAt >= PhoneMirrorPolicy.RELOAD_WINDOW_MS) {
                lastReloadRejectedAt = now
                AppLog.add("MIRROR", "Phone player reloads paused: cooldown")
            }
            lastLoadAt = maxOf(now, reloadGuard.nextAllowedAt(now) - PhoneMirrorPolicy.MIN_RELOAD_MS)
            return
        }
        val url = PhoneMirrorPolicy.watchUrl(WebHost.home(), s.id, WebHost.currentPlaylist(), s.posMs)
        if (url == null || !WebHost.isSafePageUrl(url)) return
        stats.reloads++
        generation++               // sync state of the previous page is void
        syncSeq++
        syncInFlight = false
        lastDriftMs = null
        loadedId = s.id
        pageId = s.id
        nav.reset()
        transportAt = 0L
        lastLoadAt = now
        AppLog.add("MIRROR", "Phone player follows id=${s.id}")
        w.loadUrl(url)
    }

    private fun logStats(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatsAt < PhoneMirrorPolicy.STATS_MS) return
        lastStatsAt = now
        if (stats.isEmpty() && stats.crashes == loggedCrashes) return
        val rt = Runtime.getRuntime()
        // Cumulative crash totals only when they changed since the last line.
        val totals = stats.crashes != loggedCrashes
        loggedCrashes = stats.crashes
        AppLog.add("MIRROR", stats.summary(totals) + " visible=$visible suspended=${suspended.value} " +
            "heapMb=${(rt.totalMemory() - rt.freeMemory()) shr 20}")
        stats.roll()
    }

    /** From the mirror's own YouTube controls (validated: current document, visible, the engine's video). */
    private fun onControl(action: String, value: Long) {
        val now = SystemClock.elapsedRealtime()
        AppLog.add("MIRROR", "Phone player control=$action")
        when (action) {
            "play" -> WebHost.cmd("play")
            "pause" -> WebHost.cmd("pause")
            "seek" -> WebHost.seekTo(value)
            // Explicit transport, once: the engine moves, the mirror follows (its own navigation is ignored).
            "next", "prev" -> if (transport.accept(action, now)) {
                transportAt = now
                WebHost.cmd(action)
            }
            // Phone-only: the car player and its layout are untouched.
            "mini" -> if (BrowseHost.phoneMode.value == WebHost.PlayerMode.FULL) BrowseHost.phoneMini()
        }
    }

    /**
     * The mirror page moved. Returns true to block a navigation that has not happened yet.
     * Only a navigation that matches what the user just chose counts, once; YouTube's own
     * autoplay, and anything from a hidden, suspended or obsolete document, never does.
     */
    private fun onNavigatedTo(uri: Uri, alreadyShown: Boolean): Boolean {
        if (!visible || suspended.value || signingOut || adHeld || WebHost.sessionClearing) return !alreadyShown
        val now = SystemClock.elapsedRealtime()
        return when (PhoneMirrorPolicy.classify(uri.path)) {
            PhoneMirrorPolicy.Page.WATCH -> {
                val id = PhoneMirrorPolicy.videoId(uri.path, uri.getQueryParameter("v")) ?: return false
                pageId = id
                val chosen = now - transportAt > PhoneMirrorPolicy.TRANSPORT_SUPPRESS_MS && nav.authorize(PhoneMirrorPolicy.navigationKey(uri.toString()), now)
                if (!chosen) {
                    if (id == loadedId || id == WebHost.snapshot.value.id) return false
                    if (alreadyShown) { loadedId = ""; pageId = null }
                    return !alreadyShown
                }
                AppLog.add("MIRROR", "Phone player selected a video")
                if (alreadyShown) loadedId = id
                WebHost.openUrl(uri.toString())
                !alreadyShown
            }
            // YouTube's own Home / Search / channel links: browse on the phone, keep the engine's video.
            else -> {
                if (alreadyShown) { loadedId = ""; pageId = null }
                if (nav.authorize(PhoneMirrorPolicy.navigationKey(uri.toString()), now)) {
                    AppLog.add("MIRROR", "Phone player opened ${uri.path} in browse")
                    BrowseHost.phoneBrowse(uri.toString())
                }
                !alreadyShown
            }
        }
    }

    private fun current(w: WebView, t: String) =
        web === w && token == t && visible && !suspended.value && !signingOut && !adHeld && !WebHost.sessionClearing

    fun exitFullscreen(owner: Activity): Boolean {
        if (fullscreenOwner !== owner || customView == null) return false
        hideCustomView()
        return true
    }

    private fun hideCustomView() {
        val v = customView ?: return
        val callback = customCallback
        customView = null
        customCallback = null
        fullscreenOwner = null
        (v.parent as? ViewGroup)?.removeView(v)
        callback?.onCustomViewHidden()
    }

    private fun activityOf(c: Context?): Activity? {
        var x = c
        while (x is ContextWrapper) {
            if (x is Activity) return x
            x = x.baseContext
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(context: Context): WebView {
        val c = MutableContextWrapper(context).also { wrapper = it }
        val w = WebView(c)
        // Native muting covers every frame and audio path before any navigation.
        if (!runCatching {
                WebViewCompat.setAudioMuted(w, true)
                WebViewCompat.isAudioMuted(w)
            }.getOrDefault(false)) {
            failed.value = true
            wantVisible = false
            web = w
            main.post { if (web === w && failed.value) teardown() }
            AppLog.add("MIRROR", "Native mute unavailable; secondary playback disabled")
            return w
        }
        token = java.util.UUID.randomUUID().toString()
        generation++
        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        w.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownAt = SystemClock.elapsedRealtime()
                    tapDownX = e.x
                    tapDownY = e.y
                    if (!v.hasFocus()) v.requestFocus()
                }
                // A tap (not a scroll or drag) is what may select a video.
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.elapsedRealtime()
                    if (PhoneMirrorPolicy.isTap(e.x - tapDownX, e.y - tapDownY, now - tapDownAt, slop)) nav.onTap(now)
                }
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
        w.addJavascriptInterface(MirrorBridge(t, { hiddenFlag },
            onControl = { action, value, vid ->
                // Current renderer, visible, not suspended, and still the engine's video.
                if (current(w, t) && vid == loadedId && loadedId == WebHost.snapshot.value.id) onControl(action, value)
            },
            onIntent = { url ->
                if (current(w, t) && WebHost.isSafePageUrl(url)) {
                    // An explicit new link selection is distinct from a preceding Next/Previous command.
                    transportAt = 0L
                    nav.onIntent(PhoneMirrorPolicy.navigationKey(url), SystemClock.elapsedRealtime())
                    onNavigatedTo(Uri.parse(url), alreadyShown = false)
                }
            },
            dispatch = { task -> val epoch = generation; main.post { if (generation == epoch) task() } },
            initialQuality = { startupCap },
        ), "AAYMirror")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(w, script(), setOf("https://*.youtube.com"))
        }
        // YouTube's own fullscreen button works inside the phone player.
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (!current(w, t) || customView != null) { callback.onCustomViewHidden(); return }
                val owner = activityOf(wrapper?.baseContext)
                val host = owner?.window?.decorView as? ViewGroup
                if (owner == null || host == null || owner.isFinishing || owner.isDestroyed) { callback.onCustomViewHidden(); return }
                fullscreenOwner = owner
                customView = view
                customCallback = callback
                host.addView(view, ViewGroup.LayoutParams(-1, -1))
            }

            override fun onHideCustomView() {
                if (web !== w) return
                val v = customView ?: return
                customView = null
                customCallback = null
                fullscreenOwner = null
                (v.parent as? ViewGroup)?.removeView(v)
            }

            override fun getDefaultVideoPoster() =
                android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (web !== view || uri.scheme != "https" || !WebHost.isSafePageUrl(uri.toString())) return true
                if (!request.isForMainFrame) return false
                return onNavigatedTo(uri, alreadyShown = false)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                if (uri.scheme != "https") return
                pageHost = uri.host
                if (web === view) onNavigatedTo(uri, alreadyShown = true)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageHost = runCatching { Uri.parse(url).host }.getOrNull()
                if (web !== view) return
                // A new document invalidates the previous page's navigation and transport authorisation.
                generation++
                syncSeq++
                syncInFlight = false
                nav.reset()
                transportAt = 0L
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (web !== view) return
                // A new document starts with the default layout: send the current one.
                view.evaluateJavascript("window.__aayMirror&&window.__aayMirror.setCap(${JSONObject.quote(startupCap)})", null)
                view.evaluateJavascript("window.__aayMirror&&window.__aayMirror.layout($videoOnly)", null)
                layoutSentTo = view
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Only YouTube's own frames may exist here: the mute lock is injected for those origins.
                if (!request.isForMainFrame && PhoneMirrorPolicy.isDocumentRequest(request.requestHeaders?.get("Accept")) &&
                    !PhoneMirrorPolicy.allowFrameHost(request.url.host)) {
                    main.post { if (web === view) stats.blockedFrames++ }
                    return WebHost.emptyResponse
                }
                if (!Features.current.blockAds) return null
                val host = request.url.host.orEmpty().lowercase()
                return if (WebHost.isAdRequest(host, pageHost)) WebHost.emptyResponse else null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web !== view) return true
                stats.crashes++
                AppLog.add("CRASH", "Phone player renderer gone: crashed=${detail.didCrash()}")
                teardown()
                if (crashGuard.record(SystemClock.elapsedRealtime())) {
                    stats.recoveries++
                    revision.value++      // the UI attaches a fresh renderer; syncing resumes with it
                } else {
                    AppLog.add("CRASH", "Phone player stopped after repeated renderer crashes")
                    failed.value = true
                }
                return true
            }
        }
        web = w
        loadedId = ""
        pageId = null
        visible = false
        layoutSentTo = null
        // A recreated renderer picks up where the UI wants it (visible) without waiting for a UI change.
        main.post { applyVisible() }
        return w
    }
}

/**
 * What the mirror page script can call. Every command names the document that sent it (a new
 * document invalidates older ones), carries an increasing sequence number and the video it saw.
 */
internal class MirrorBridge(
    private val token: String,
    private val isHidden: () -> Boolean,
    private val onControl: (action: String, value: Long, videoId: String) -> Unit,
    private val onIntent: (url: String) -> Unit = {},
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val initialQuality: () -> String = { "" },
) {
    private var currentDoc = 0
    private var lastSeq = 0L

    /** A new document asks first: while hidden or suspended it starts stopped and cannot autoplay. */
    @JavascriptInterface
    fun hidden(): Boolean = isHidden()

    @JavascriptInterface
    fun initialCap(): String = initialQuality()

    /** A new document registers itself; commands from any earlier document are rejected from now on. */
    @JavascriptInterface
    @Synchronized
    fun open(providedToken: String?): Int {
        if (providedToken != token) return 0
        lastSeq = 0L
        return ++currentDoc
    }

    @JavascriptInterface
    @Synchronized
    fun control(action: String, value: Double, providedToken: String?, doc: Int, seq: Double, videoId: String?) {
        if (providedToken != token || action !in PhoneMirrorPolicy.ACTIONS) return
        if (doc == 0 || doc != currentDoc) return
        if (!seq.isFinite() || seq.toLong() <= lastSeq) return
        if (!value.isFinite() || value < 0.0 || value > PhoneMirrorPolicy.MAX_POSITION_MS) return
        lastSeq = seq.toLong()
        dispatch { synchronized(this) {
            if (doc == currentDoc) onControl(action, value.toLong(), videoId.orEmpty())
        } }
    }

    /** The user clicked a link inside the player page: what they chose, not just that they tapped. */
    @JavascriptInterface
    @Synchronized
    fun intent(url: String?, providedToken: String?, doc: Int): Boolean {
        if (providedToken != token || doc == 0 || doc != currentDoc || url == null || url.length > 2_048) return false
        if (PhoneMirrorPolicy.navigationKey(url) == null) return false
        dispatch { synchronized(this) { if (doc == currentDoc) onIntent(url) } }
        return true
    }
}

/** At most [max] events inside a sliding window. */
internal class RateGuard(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    /** Records an event; false once more than [max] fall inside the window. */
    fun record(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }

    fun nextAllowedAt(nowMs: Long): Long =
        if (times.size < max) nowMs else maxOf(nowMs, times.first() + windowMs + 1L)

    fun reset() = times.clear()
}

/** Ignores the same transport command repeated inside a short window (touch + click, double delivery). */
internal class TransportGuard(private val windowMs: Long = 500L) {
    private var lastAction = ""
    private var lastAt = Long.MIN_VALUE / 2

    fun accept(action: String, nowMs: Long): Boolean {
        if (action == lastAction && nowMs - lastAt in 0 until windowMs) return false
        lastAction = action
        lastAt = nowMs
        return true
    }

    fun reset() { lastAction = ""; lastAt = Long.MIN_VALUE / 2 }
}

/**
 * Navigation on the mirror page counts as the user's choice only when it matches what they just
 * tapped, and only once. A link click names its destination; a bare tap is a short fallback.
 */
internal class NavAuthorizer {
    private var intentAt = Long.MIN_VALUE / 2
    private var intentId: String? = null
    private var hasIntent = false

    fun onTap(nowMs: Long) { reset() }

    fun onIntent(destinationId: String?, nowMs: Long) { hasIntent = true; intentId = destinationId; intentAt = nowMs }

    /** [destinationId] is the video ID, or null for a non-video page (Home, Search). Consumed on success. */
    fun authorize(destinationId: String?, nowMs: Long): Boolean {
        val ok = hasIntent && destinationId != null && nowMs - intentAt in 0..INTENT_MS && intentId == destinationId
        if (ok) reset()
        return ok
    }

    fun reset() { hasIntent = false; intentId = null; intentAt = Long.MIN_VALUE / 2 }

    companion object {
        const val INTENT_MS = 3_000L
    }
}

/**
 * Whether the phone copy may run. The primary player always comes first: heat or sustained
 * primary buffering suspend the copy, mild pressure lowers its quality, and everything comes
 * back step by step only after conditions have been calm for [stableMs].
 */
internal class MirrorLoadGate(
    private val stableMs: Long = PhoneMirrorPolicy.STABLE_BEFORE_RESTORE_MS,
    private val bufferingSuspendMs: Long = PhoneMirrorPolicy.BUFFERING_SUSPEND_MS,
) {
    enum class State { NORMAL, DEGRADED, SUSPENDED }

    private val never = Long.MIN_VALUE / 2
    private var bufferingSince = 0L
    private var lastPressureAt = never
    private var lastSevereAt = never
    private var state = State.NORMAL

    fun update(thermal: Int, primaryBuffering: Boolean, nowMs: Long): State {
        if (primaryBuffering) { if (bufferingSince == 0L) bufferingSince = nowMs } else bufferingSince = 0L
        val sustained = bufferingSince != 0L && nowMs - bufferingSince >= bufferingSuspendMs
        val severe = PhoneMirrorPolicy.suspendForHeat(thermal) || sustained
        val pressure = primaryBuffering || PhoneMirrorPolicy.degradeForHeat(thermal)
        if (severe) lastSevereAt = nowMs
        if (pressure) lastPressureAt = nowMs
        state = when {
            severe || nowMs - lastSevereAt < stableMs -> State.SUSPENDED
            // Back from a suspension quality returns gradually: a second calm period at reduced quality.
            pressure || nowMs - lastPressureAt < stableMs || nowMs - lastSevereAt < stableMs * 2 -> State.DEGRADED
            else -> State.NORMAL
        }
        return state
    }

    fun reset() {
        bufferingSince = 0L
        lastPressureAt = never
        lastSevereAt = never
        state = State.NORMAL
    }
}

/** The page script's answer to one sync: "<driftMs>|<action>[|<decodedHeightPx>]" or a bare reason. */
internal data class SyncResult(val driftMs: Long?, val action: String, val heightPx: Int? = null) {
    companion object {
        fun parse(raw: String?): SyncResult {
            val text = raw?.trim()?.trim('"').orEmpty()
            if (text.isEmpty() || text == "null") return SyncResult(null, "none")
            val parts = text.split('|', limit = 3)
            val drift = parts[0].toLongOrNull()
            return if (drift != null && parts.size >= 2) SyncResult(drift, parts[1], parts.getOrNull(2)?.toIntOrNull())
            else SyncResult(null, text)
        }
    }
}

/** Aggregated phone-mirror diagnostics (one log line per interval, not per sync). */
internal class MirrorSyncStats {
    var syncs = 0; var nudges = 0; var seeks = 0; var ads = 0; var userHolds = 0
    var mismatches = 0; var overlapSkipped = 0; var stale = 0; var duplicates = 0
    var pauses = 0; var resumes = 0; var crashes = 0; var recoveries = 0; var reloads = 0; var timeouts = 0
    var suspensions = 0; var suspendedMs = 0L; var blockedFrames = 0
    var heightPx = 0
    private var driftSum = 0L
    var driftMax = 0L
        private set

    fun record(r: SyncResult) {
        when (r.action) {
            "nudge" -> nudges++
            "seek" -> seeks++
            "ad" -> ads++
            "user" -> userHolds++
            "mismatch" -> mismatches++
        }
        r.heightPx?.let { if (it > 0) heightPx = it }
        val d = r.driftMs ?: return
        syncs++
        val a = kotlin.math.abs(d)
        driftSum += a
        if (a > driftMax) driftMax = a
    }

    /** Nothing worth a log line happened this interval (timeouts, mismatches and stale answers count). */
    fun isEmpty() = syncs == 0 && reloads == 0 && pauses == 0 && resumes == 0 &&
        duplicates == 0 && timeouts == 0 && mismatches == 0 && stale == 0 && suspensions == 0 &&
        blockedFrames == 0 && overlapSkipped == 0 && ads == 0 && userHolds == 0 && suspendedMs == 0L
    fun avgDriftMs(): Long = if (syncs == 0) 0L else driftSum / syncs

    /** Interval statistics are logged then cleared; crashes and recoveries stay cumulative. */
    fun roll() {
        val c = crashes; val r = recoveries
        reset()
        crashes = c; recoveries = r
    }

    fun summary(includeTotals: Boolean = true) = "interval stats syncs=$syncs driftAvgMs=${avgDriftMs()} " +
        "driftMaxMs=$driftMax nudges=$nudges seeks=$seeks ads=$ads userHolds=$userHolds mismatches=$mismatches " +
        "overlapSkipped=$overlapSkipped timeouts=$timeouts stale=$stale duplicateNavBlocked=$duplicates " +
        "pauses=$pauses resumes=$resumes reloads=$reloads suspensions=$suspensions suspendedMs=$suspendedMs " +
        "blockedFrames=$blockedFrames decodedHeightPx=$heightPx" +
        if (includeTotals) " crashes(total)=$crashes recoveries(total)=$recoveries" else ""

    fun reset() {
        syncs = 0; nudges = 0; seeks = 0; ads = 0; userHolds = 0; mismatches = 0; overlapSkipped = 0
        stale = 0; timeouts = 0; duplicates = 0; pauses = 0; resumes = 0; crashes = 0; recoveries = 0; reloads = 0
        suspensions = 0; suspendedMs = 0L; blockedFrames = 0; heightPx = 0
        driftSum = 0L; driftMax = 0L
    }
}

/** The primary engine's clock and health at one instant. */
internal data class EngineClock(
    val id: String,
    val posMs: Long,
    val playing: Boolean,
    val rate: Double,
    val seeking: Boolean,
    val ready: Boolean,
    val ad: Boolean,
)

/** Pure decisions for the phone mirror (unit-tested). */
internal object PhoneMirrorPolicy {
    const val SYNC_MS = 1_500L
    const val STABLE_SYNC_MS = 4_000L
    const val IN_STEP_MS = 300L
    const val IDLE_SYNC_MS = 3_000L
    const val MIN_RELOAD_MS = 1_500L
    const val LOAD_SETTLE_MS = 4_000L
    const val SYNC_TIMEOUT_MS = 3_000L
    const val MAX_CLOCK_AGE_MS = 1_500L
    const val MAX_POSITION_MS = 7.0 * 24 * 3_600_000
    const val MIN_POKE_MS = 250L
    const val SEEK_POKE_MS = 2_500L
    const val STATS_MS = 30_000L
    const val HIDDEN_RELEASE_MS = 60_000L
    const val SUSPENDED_RELEASE_MS = 12_000L
    const val MISSING_MEDIA_LIMIT = 4
    const val MAX_MEDIA_RECOVERIES = 2
    const val TRANSPORT_SUPPRESS_MS = 2_500L
    const val THERMAL_DEGRADE = 2   // PowerManager.THERMAL_STATUS_MODERATE
    const val THERMAL_SUSPEND = 3   // PowerManager.THERMAL_STATUS_SEVERE
    const val STABLE_BEFORE_RESTORE_MS = 10_000L
    const val BUFFERING_SUSPEND_MS = 8_000L
    const val MAX_CRASHES = 3
    const val CRASH_WINDOW_MS = 60_000L
    const val MAX_RELOADS = 6
    const val RELOAD_WINDOW_MS = 60_000L
    val ACTIONS = setOf("play", "pause", "seek", "mini", "next", "prev")

    enum class Page { WATCH, HOME, SEARCH, OTHER }
    enum class Fallback { NONE, UNSUPPORTED, FAILED, SUSPENDED }

    fun degradeForHeat(thermal: Int) = thermal >= THERMAL_DEGRADE
    fun suspendForHeat(thermal: Int) = thermal >= THERMAL_SUSPEND

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

    fun classify(path: String?): Page {
        val p = path.orEmpty()
        return when {
            p.startsWith("/watch") || p.startsWith("/live/") || p.startsWith("/shorts/") -> Page.WATCH
            p.isEmpty() || p == "/" -> Page.HOME
            p.startsWith("/results") -> Page.SEARCH
            else -> Page.OTHER
        }
    }

    /** One identity rule for watch, live and Shorts routes, shared with the page scripts. */
    fun videoId(path: String?, queryV: String?): String? {
        safeToken(queryV, 11..11)?.let { return it }
        val p = path.orEmpty()
        for (prefix in listOf("/live/", "/shorts/")) {
            if (p.startsWith(prefix)) return safeToken(p.removePrefix(prefix).substringBefore('/'), 11..11)
        }
        return null
    }

    /** The video a clicked link points at (null for Home, Search, channels, malformed links). */
    fun videoIdFromUrl(url: String): String? {
        val noFragment = url.substringBefore('#')
        val path = noFragment.substringAfter("://", noFragment).substringAfter('/', "").substringBefore('?')
        val query = noFragment.substringAfter('?', "")
        val v = query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        return videoId("/$path", v)
    }

    fun navigationKey(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val host = u.host?.lowercase() ?: return@runCatching null
        if (u.scheme != "https" || !allowFrameHost(host)) return@runCatching null
        val query = u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.sorted().joinToString("&")
        val path = u.rawPath.orEmpty().ifBlank { "/" }
        "$host$path?$query"
    }.getOrNull()

    /** In step: check rarely (less renderer work); drifting or unknown: check soon. */
    fun nextSyncMs(driftMs: Long?): Long =
        if (driftMs != null && kotlin.math.abs(driftMs) <= IN_STEP_MS) STABLE_SYNC_MS else SYNC_MS

    /** A touch is a tap (and so may select something) only if it barely moved and was short. */
    fun isTap(dx: Float, dy: Float, durationMs: Long, slopPx: Float): Boolean =
        durationMs in 0..500 && dx * dx + dy * dy <= slopPx * slopPx

    /**
     * The clock sample was taken somewhere between request and answer: assume the middle, advance
     * it to "now" while playing, and drop samples that took too long (they describe the past).
     */
    fun compensatedPosMs(posMs: Long, playing: Boolean, rate: Double, requestedAt: Long, receivedAt: Long): Long? {
        val latency = receivedAt - requestedAt
        if (latency < 0 || latency > MAX_CLOCK_AGE_MS) return null
        return if (playing) posMs + (latency / 2.0 * rate).toLong() else posMs
    }

    /** The page must show the engine's video; a stuck or foreign page is reloaded after it settled. */
    fun needsLoad(loadedId: String, pageId: String?, engineId: String, sinceLoadMs: Long): Boolean =
        engineId.isNotBlank() && (loadedId != engineId || (pageId != engineId && sinceLoadMs >= LOAD_SETTLE_MS))

    /** A late callback may only act if its page generation and video are still the current ones. */
    fun current(ticketGeneration: Int, generation: Int, ticketId: String, engineId: String): Boolean =
        ticketGeneration == generation && ticketId == engineId

    /** Engine changes the phone should follow immediately instead of at the next interval. */
    fun pokeNeeded(previous: Snap, next: Snap): Boolean = when {
        next.id != previous.id -> true
        next.playing != previous.playing || next.userPaused != previous.userPaused ||
            next.buffering != previous.buffering || next.ad != previous.ad || next.rate != previous.rate -> true
        else -> kotlin.math.abs(next.posMs - previous.posMs) > SEEK_POKE_MS
    }

    /** Subframes are YouTube's own only: the mute lock is injected for exactly those origins. */
    fun allowFrameHost(host: String?): Boolean {
        val h = host.orEmpty().lowercase()
        return h == "youtube.com" || h.endsWith(".youtube.com")
    }

    fun isDocumentRequest(accept: String?): Boolean = accept?.contains("text/html", ignoreCase = true) == true

    /** One fallback for every state where the phone cannot show the copy; Retry only if it can help. */
    fun fallback(supported: Boolean, crashed: Boolean, suspended: Boolean): Fallback = when {
        !supported -> Fallback.UNSUPPORTED
        crashed -> Fallback.FAILED
        suspended -> Fallback.SUSPENDED
        else -> Fallback.NONE
    }

    fun canRetry(f: Fallback) = f == Fallback.FAILED
}
