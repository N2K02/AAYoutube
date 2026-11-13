package com.aayoutube.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.View
import android.view.KeyEvent
import android.webkit.WebView
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.app.Presentation
import android.content.Context
import android.view.Display

/** Owns the existing player UI on the AndroidX car surface's virtual display. */
internal class CarVideoPresentation(context: Context, display: Display) :
    Presentation(context, display, R.style.Theme_AAYoutube),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    private var mediaController: CompatMediaConnection? = null
    private var playerContainer: CarFrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var carStarted = false
    /** Snapshotted at activity creation; never change renderer behavior mid-playback. */
    private var experimentalVideoEnabled = false
    private var experimentalChecks = 0
    private var experimentalRepairs = 0
    /** One retry budget per presentation instance, never reset by onResume/rebind. */
    private var experimentalRecoveryStarted = false
    private var lastWidth = 0
    private var lastHeight = 0
    private val refit = Runnable { WebHost.refitCarViewport() }

    // Android Auto recreates this presentation (new Surface, restored state)
    // while the previous instance is still alive. Each instance has its own
    // owner token so the old one's onStop/onDestroy cannot clear the new
    // one's projection, switch the page to background quality or steal the
    // player back into a window that is no longer on the car screen.
    private val carToken = "car-surface@" + Integer.toHexString(System.identityHashCode(this))
    private var inactiveLogged = false
    private var carQuitInProgress = false
    // Car presentation is not an Android Activity; WebHost IPC arrives
    // on the :web main thread; the presentation has no Activity dispatcher.
    private val carQuitCallback: () -> Unit = { shutdownCarOnlySession() }
    private val rebindWhenActive = Runnable {
        if (!carStarted || !WebHost.isActiveCarHost(carToken)) return@Runnable
        // A fallback instance can have been started while a newer car window
        // owned the player. Restore every active-host responsibility, not only
        // the view: otherwise music/media callbacks remain attached to a
        // stopped instance until another lifecycle event happens.
        WebHost.showOnCar(true, carToken)
        ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
        if (experimentalVideoEnabled) {
            CarWindowPixelProbe.register(carToken) {
                CarWindowPixelProbe.capture(carToken, carWindow(), playerContainer)
            }
            CarVideoPatternProbe.register(carToken)
            CarDirectSurfaceProbe.register(carToken)
        }
        WebHost.onCarQuit = carQuitCallback
        if (WebHost.mode.value != WebHost.PlayerMode.MINI) WebHost.prepareForCarProjection()
        else BrowseHost.resume()
        WebHost.setHold(false)
        WebHost.requestLink = relink
        connectMedia()
        val frame = playerContainer ?: return@Runnable
        if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)
    }
    private val onBecameActive: () -> Unit = {
        handler.removeCallbacks(rebindWhenActive)
        // A host shutting down several instances stops this one shortly after.
        handler.postDelayed(rebindWhenActive, 1_200L)
    }
    private val renderProbe = Runnable {
        if (carStarted && WebHost.isActiveCarHost(carToken)) WebHost.logCarRenderProbe()
    }

    /**
     * AnyAuto-inspired surface resilience, not an AnyAuto/Shizuku renderer.
     * A bounded watchdog checks only the native View hierarchy and display ID.
     * The existing WebHost attachment remains the sole video/decoder owner.
     */
    private val experimentalRecovery = object : Runnable {
        override fun run() {
            if (!experimentalVideoEnabled || !carStarted ||
                !WebHost.isActiveCarHost(carToken) || WebHost.fullscreenView.value != null) return
            val frame = playerContainer
            val player = frame?.getChildAt(0) as? WebView
            val repair = frame != null && ExperimentalCarVideoPolicy.needsRebind(
                enabled = experimentalVideoEnabled,
                activeCarHost = WebHost.isActiveCarHost(carToken),
                frameAttached = frame.isAttachedToWindow,
                frameWidth = frame.width,
                frameHeight = frame.height,
                frameDisplayId = frame.display?.displayId ?: -1,
                playerAttached = player?.isAttachedToWindow == true,
                playerWidth = player?.width ?: 0,
                playerHeight = player?.height ?: 0,
                playerDisplayId = player?.display?.displayId ?: -1,
            )
            experimentalChecks++
            var status = when {
                frame == null || !frame.isAttachedToWindow -> "Waiting for car surface"
                frame.width <= 0 || frame.height <= 0 -> "Waiting for usable viewport"
                !repair -> "Healthy WebView geometry"
                else -> "WebView rebind requested"
            }
            if (repair && frame != null) {
                if (experimentalRepairs >= ExperimentalCarVideoPolicy.MAX_REBINDS) {
                    status = "Fallback: rebind budget exhausted"
                    experimentalVideoEnabled = false
                    CarWindowPixelProbe.unregister(carToken)
                    CarVideoPatternProbe.unregister(carToken)
                    CarDirectSurfaceProbe.unregister(carToken)
                    ExperimentalCarVideoMonitor.fallback(carToken, "rebind budget exhausted")
                } else {
                    experimentalRepairs++
                    runCatching {
                        // Reuses the existing WebHost/window, never makes a second player.
                        bindVideoToCarFrame(frame)
                        WebHost.refitCarViewport()
                    }.onFailure {
                        status = "Fallback: rebind failed"
                        experimentalVideoEnabled = false
                        CarWindowPixelProbe.unregister(carToken)
                        CarVideoPatternProbe.unregister(carToken)
                        CarDirectSurfaceProbe.unregister(carToken)
                        ExperimentalCarVideoMonitor.fallback(carToken,
                            "rebind failed (" + it.javaClass.simpleName + ")")
                    }
                }
            }
            val visible = android.graphics.Rect()
            // Host insets may clip the projected View. Local coordinates let
            // the 16:9 diagnostic fit use the same origin as frame.width/height.
            if (frame != null && !frame.getLocalVisibleRect(visible)) visible.setEmpty()
            ExperimentalCarVideoMonitor.check(
                owner = carToken,
                state = status,
                checks = experimentalChecks,
                repairs = experimentalRepairs,
                displayId = frame?.display?.displayId ?: -1,
                frameWidth = frame?.width ?: 0,
                frameHeight = frame?.height ?: 0,
                playerWidth = player?.width ?: 0,
                playerHeight = player?.height ?: 0,
                visibleArea = CarVideoBounds(
                    visible.left, visible.top, visible.width(), visible.height()
                ),
            )
            if (experimentalVideoEnabled &&
                experimentalChecks < ExperimentalCarVideoPolicy.MAX_CHECKS) {
                handler.postDelayed(this, ExperimentalCarVideoPolicy.CHECK_DELAY_MS)
            } else {
                AppLog.add("CAR", "Experimental video: checks finished; car surface intact")
            }
        }
    }

    private fun scheduleExperimentalRecovery() {
        if (!experimentalVideoEnabled || !carStarted ||
            !WebHost.isActiveCarHost(carToken) || experimentalRecoveryStarted) return
        val frame = playerContainer
        if (frame?.isAttachedToWindow != true || frame.display == null) return
        // Only a newly created car activity can start a fresh retry budget.
        // Repeated onResume, WebView rebinding and surface callbacks cannot.
        experimentalRecoveryStarted = true
        handler.removeCallbacks(experimentalRecovery)
        handler.postDelayed(experimentalRecovery, ExperimentalCarVideoPolicy.CHECK_DELAY_MS)
    }

    /** Frames decoded vs. painted, over the first seconds of a car bind. */
    private fun scheduleRenderProbes() {
        handler.removeCallbacks(renderProbe)
        longArrayOf(2_000L, 6_000L, 12_000L).forEach { handler.postDelayed(renderProbe, it) }
    }

    // presentation uses a virtual display; Activity/application context can
    // still resolve default display 0 even when its ViewRoot uses display 323.
    private fun projectionContext(fallback: android.content.Context, frame: View? = null):
        android.content.Context {
        val display = frame?.display ?: carWindow()?.decorView?.display ?: return fallback
        return runCatching {
            android.view.ContextThemeWrapper(
                fallback.createDisplayContext(display), R.style.Theme_AAYoutube
            )
        }.getOrElse {
            AppLog.add("CAR", "Projection display context failed: " + it.javaClass.simpleName)
            fallback
        }
    }

    private fun bindVideoToCarFrame(frame: CarFrameLayout) {
        // AndroidView.factory runs before the ViewRoot is attached. Do not
        // create Chromium on phone display 0 and then immediately transfer its
        // GPU surface to Android Auto display 420.
        if (!frame.isAttachedToWindow || frame.display == null) return
        if (!WebHost.isActiveCarHost(carToken)) {
            if (!inactiveLogged) AppLog.add("CAR", "Inactive car host " + carToken +
                " keeps its hands off the player owned by " + (WebHost.activeCarHost.value ?: "none"))
            inactiveLogged = true
            return
        }
        inactiveLogged = false
        // Never move a live renderer into a replacement car window: it decodes
        // and plays audio but paints black. A different window gets a new one.
        frame.onSwipeDown = {
            if (WebHost.isActiveCarHost(carToken) &&
                WebHost.mode.value == WebHost.PlayerMode.FULL && WebHost.isOnWatchPage()) {
                AppLog.add("CAR", "Swipe down on video: mini player")
                carVideoToMini("Swipe")
            }
        }
        frame.onVideoTap = {
            if (WebHost.isActiveCarHost(carToken) &&
                WebHost.mode.value == WebHost.PlayerMode.FULL && WebHost.isOnWatchPage()) {
                WebHost.showCarControls()
            }
        }
        frame.onObservedGesture = if (experimentalVideoEnabled)
            ({ gesture: CarVideoGesture ->
                AppLog.add("CAR", "Experimental passive car gesture=" + gesture.name)
            }) else null
        val player = WebHost.attachToCarWindow(projectionContext(frame.context, frame), frame)
        if (frame.childCount != 1 || frame.getChildAt(0) !== player) {
            frame.removeAllViews()
            frame.addView(player, FrameLayout.LayoutParams(-1, -1))
            // Never grant focus to Chromium as a side effect of opening
            // the video. WebView focus was opening Android Auto's keyboard
            // immediately, followed by the host stopping the projection.
            frame.isFocusableInTouchMode = true
            frame.requestFocus()
            scheduleRenderProbes()
        }
        val display = frame.display?.displayId ?: -1
        val childDisplay = player.display?.displayId ?: -1
        AppLog.add("CAR", "Projection root display=" + display +
            " player display=" + childDisplay +
            " size=" + frame.width + "x" + frame.height)
    }
    private val relink: () -> Unit = { handler.post { if (carStarted) connectMedia() } }

    override fun onCreate(bundle: Bundle?) {
        // Log before window creation to diagnose failing car launches.
        AppLog.add("CAR", "AndroidX car presentation launching")
        super.onCreate(bundle)
        // Dialog windows do not inherit Activity hardware acceleration flags.
        // Chromium video must have a GPU-backed root on the virtual display.
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        // The new car connection wins: stop any phone-only test display before
        // Android Auto claims the live video host.
        CarVirtualDisplayProbe.cancel()
        experimentalVideoEnabled = Features.current.experimentalVideo
        AppLog.add("CAR", "Experimental video at connection=" + experimentalVideoEnabled)
        CarMode.resetToCarScreen(context)
        savedController.performAttach()
        savedController.performRestore(bundle)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        AppLog.add("CAR", "AndroidX car presentation created")
        // Projection must own the page before Compose creates/loads its WebView.
        // Otherwise ensureWeb() can briefly initialize in phone/background mode.
        // The newest car activity instance is the one Android Auto shows.
        val wasAlreadyProjected = WebHost.carShown
        WebHost.claimCarHost(carToken, onBecameActive)
        ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
        WebHost.onCarQuit = carQuitCallback
        WebHost.showOnCar(true, carToken)
        // Preserve a car mini-player across host window replacement.
        if (!wasAlreadyProjected || WebHost.mode.value != WebHost.PlayerMode.MINI)
            WebHost.prepareForCarProjection()
        // Immediately transfer the web-process callback; an old activity
        // can stop before this new instance reaches onStart.
        WebHost.requestLink = relink

        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@CarVideoPresentation)
            setViewTreeViewModelStoreOwner(this@CarVideoPresentation)
            setViewTreeSavedStateRegistryOwner(this@CarVideoPresentation)
            setContent { AAYoutubeTheme { CarVideoScreen() } }
        }
        setContentView(compose)
        // Car presentation owns a separate window; configure its actual decor tree.
        carWindow()?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        updateCarWindow()
    }

    private fun carWindow(): Window? = window

    private fun updateCarWindow() {
        runCatching {
            val window = carWindow() ?: return
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }.onFailure { AppLog.add("CAR", "Car window insets unavailable: " + it.javaClass.simpleName) }
    }

    /** One shared player, matching surface host's projected Compose screen technique. */
    @Composable
    private fun CarVideoScreen() {
        val version by WebHost.webVersion.collectAsState()
        val mode by WebHost.mode.collectAsState()
        val browsing by BrowseHost.created.collectAsState()
        val browseRevision by BrowseHost.revision.collectAsState()
        val mini = mode == WebHost.PlayerMode.MINI
        val full by WebHost.fullscreenView.collectAsState()
        val activeHost by WebHost.activeCarHost.collectAsState()
        val carTestPattern by CarVideoPatternProbe.state.collectAsState()
        val directSurface by CarDirectSurfaceProbe.state.collectAsState()
        val playing by WebHost.snapshot.collectAsState()
        val selectedWatchVideoId by WebHost.selectedWatchVideoId.collectAsState()
        val flags by Features.state.collectAsState()
        LaunchedEffect(carTestPattern.visible, playing.playing, activeHost) {
            if (carTestPattern.visible && playing.playing && activeHost == carToken) {
                CarVideoPatternProbe.stopForPlayback(carToken)
            }
        }
        LaunchedEffect(directSurface.visible, playing.playing, activeHost) {
            if (directSurface.visible && playing.playing && activeHost == carToken) {
                CarDirectSurfaceProbe.stopForPlayback(carToken)
            }
        }
        // The static bars must be drawn before PixelCopy reads the same window.
        // This samples ONLY the synthetic test, and never runs while playing.
        LaunchedEffect(carTestPattern.visible, carTestPattern.testId, activeHost) {
            if (carTestPattern.visible && activeHost == carToken &&
                experimentalVideoEnabled) {
                delay(450L)
                if (CarVideoPatternProbe.state.value.visible &&
                    CarVideoPatternProbe.state.value.testId == carTestPattern.testId &&
                    WebHost.isActiveCarHost(carToken) && !WebHost.snapshot.value.playing) {
                    CarVideoPatternReadback.capture(
                        carToken, carTestPattern.testId, carWindow())
                }
            }
        }
        var controlsVisible by remember { mutableStateOf(true) }
        var controlsVersion by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlsVisible, controlsVersion, flags.topMenuTimeoutSeconds) {
            val timeout = TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)
            if (controlsVisible && timeout != null) {
                delay(timeout)
                controlsVisible = false
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .revealControlsOnTouch { controlsVisible = true; controlsVersion++ }
        ) {
            // Allow the mini player behind either side rail. The rail is always on
            // top (z-index 20), regardless of car controls side or playback mode.
            val density = LocalDensity.current
            val miniPrefs = remember { context.getSharedPreferences("car_mini", android.content.Context.MODE_PRIVATE) }
            var miniX by remember { mutableStateOf(miniPrefs.getFloat("x", 1f).coerceIn(0f, 1f)) }
            var miniY by remember { mutableStateOf(miniPrefs.getFloat("y", 1f).coerceIn(0f, 1f)) }
            // Landscape car screens (the usual Android Auto shape) get the top-centre pill.
            val carLandscape = ControlsSidePolicy.isLandscape(maxWidth.value, maxHeight.value)
            val (railAlign, railDrag) = rememberEdgeDrag(
                left = flags.controlsOnLeft, landscape = carLandscape,
                containerPx = with(LocalDensity.current) { (if (carLandscape) maxWidth else maxHeight).toPx() },
                onActivity = { controlsVisible = true; controlsVersion++ },
            )
            val miniWidth = minOf(240.dp, (maxWidth - 24.dp) * 0.8f)
            val miniHeight = miniWidth * (9f / 16f)
            // Only the actual controls rail intercepts touches. The video can be
            // dragged to every screen edge, including behind either rail.
            val minX = 0f
            val minY = 0f
            val maxX = with(density) { (maxWidth - miniWidth).toPx() }.coerceAtLeast(minX)
            val maxY = with(density) { (maxHeight - miniHeight).toPx() }.coerceAtLeast(minY)
            val playerX = minX + (maxX - minX) * miniX
            val playerY = minY + (maxY - minY) * miniY
            val miniVideoModifier = Modifier.offset {
                IntOffset(playerX.roundToInt(), playerY.roundToInt())
            }.size(miniWidth, miniHeight).pointerInput(maxX, maxY) {
                detectDragGestures(onDragEnd = {
                    miniPrefs.edit().putFloat("x", miniX).putFloat("y", miniY).apply()
                }) { change, drag ->
                    change.consume()
                    miniX = (miniX + drag.x / (maxX - minX).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    miniY = (miniY + drag.y / (maxY - minY).coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            }
            // Show YouTube Home in a second, muted WebView while keeping the active player.
            if (mini && browsing) key(browseRevision) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).also { frame ->
                            frame.addView(BrowseHost.attach(projectionContext(ctx)),
                                FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { BrowseHost.detach(it) },
                )
            }
            key(version) {
                AndroidView(
                    modifier = if (mini) miniVideoModifier else Modifier.fillMaxSize(),
                    factory = { context ->
                        CarFrameLayout(context).also { frame ->
                            playerContainer = frame
                            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    AppLog.add("CAR", "Car frame attached display=" +
                                        (frame.display?.displayId ?: -1))
                                    if (playerContainer === frame) {
                                        bindVideoToCarFrame(frame)
                                        scheduleExperimentalRecovery()
                                    }
                                }
                                override fun onViewDetachedFromWindow(v: View) {
                                    AppLog.add("CAR", "Car frame detached")
                                }
                            })
                            frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                val width = right - left
                                val height = bottom - top
                                if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                                    lastWidth = width
                                    lastHeight = height
                                    AppLog.add("CAR", "Projection viewport=" + width + "x" + height)
                                    handler.post {
                                        if (playerContainer === frame) bindVideoToCarFrame(frame)
                                    }
                                    handler.removeCallbacks(refit)
                                    handler.postDelayed(refit, 180L)
                                    scheduleRenderProbes()
                                }
                            }
                            // First WebView creation belongs in onViewAttachedToWindow,
                            // when Android Auto's virtual display is known.
                        }
                    },
                    update = { frame ->
                        if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)
                    },
                    onRelease = { frame ->
                        frame.onSwipeDown = null
                        frame.onVideoTap = null
                        frame.onObservedGesture = null
                        WebHost.detach(frame)
                        if (playerContainer === frame) playerContainer = null
                    },
                )
            }
            if (mini) {
                Text(
                    "Expand video",
                    Modifier.offset {
                        val labelY = if (playerY > with(density) { 48.dp.toPx() })
                            playerY - with(density) { 42.dp.toPx() }
                        else playerY + with(density) { miniHeight.toPx() } + with(density) { 4.dp.toPx() }
                        IntOffset(playerX.roundToInt(), labelY.roundToInt())
                    }.clickable { WebHost.expandPlayer(); BrowseHost.pause() }
                        .background(Color(0xCC111111), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val fullscreenView = full
            // An inactive (being replaced) car window must not take the
            // fullscreen video view away from the window on the car screen.
            if (fullscreenView != null && activeHost == carToken) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CarFrameLayout(context).also { frame ->
                            (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                            frame.addView(fullscreenView, FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { it.removeAllViews() },
                )
            }
            // Same small floating rail as the phone, on the configured side.
            // Android Auto is landscape: a side rail never covers YouTube's
            // own top or bottom menus. Any touch shows it again.
            // A paused-only, explicit diagnostic: the temporary bars are
            // drawn by the same Compose car window. Chromium is never moved.
            if (carTestPattern.visible && carTestPattern.owner == carToken &&
                activeHost == carToken && experimentalVideoEnabled) {
                Box(Modifier.fillMaxSize().zIndex(40f)) {
                    CarVideoPatternView()
                }
            }
            // A distinct, short-lived SurfaceView draws synthetic motion, not
            // duplicated Chromium video. onRelease reliably retires its thread.
            if (directSurface.visible && directSurface.owner == carToken &&
                activeHost == carToken && experimentalVideoEnabled &&
                !playing.playing) {
                key(directSurface.runId) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().zIndex(45f),
                        factory = { ctx ->
                            CarDirectSurfaceView(
                                projectionContext(ctx, playerContainer),
                                carToken,
                                directSurface.runId,
                            )
                        },
                        onRelease = { it.stopRendering() },
                    )
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(railAlign)
                    .padding(
                        horizontal = if (carLandscape) 0.dp else 12.dp,
                        vertical = if (carLandscape) 12.dp else 0.dp,
                    ).then(railDrag).zIndex(20f),
            ) {
                FloatingVideoBar(
                    playing = playing.playing,
                    playbackId = playing.id,
                    transportReady = OverlayTransportPolicy.ready(
                        playing.id, selectedWatchVideoId, playing.buffering, playing.ended
                    ) && mode == WebHost.PlayerMode.FULL,
                    car = true,
                    vertical = ControlsSidePolicy.vertical(carLandscape),
                    onBack = { carBackToMini(); controlsVersion++ },
                    onHome = { carHomeToMini(); controlsVersion++ },
                    onPlayPause = {
                        WebHost.cmd(if (playing.playing) "pause" else "play")
                        controlsVersion++
                    },
                    onPrevious = { WebHost.cmd("prev"); controlsVersion++ },
                    onNext = { WebHost.cmd("next"); controlsVersion++ },
                ) {
                    BarIcon(
                        Icons.Rounded.PowerSettingsNew,
                        "Quit AAYoutube", BarSize.Car,
                        onClick = { quitFromCar() },
                    )
                }
            }
        }
    }

    /** Car-only navigation: preserve the playing WebView while showing YouTube Home. */
    private fun carVideoToMini(action: String): Boolean {
        if (!WebHost.isActiveCarHost(carToken)) {
            AppLog.add("CAR", "$action ignored: inactive projection host")
            return false
        }
        if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
            BrowseHost.navigate(WebHost.home())
            AppLog.add("CAR", "$action: browse Home with mini player")
            return true
        }
        if (WebHost.fullscreenView.value != null) {
            AppLog.add("CAR", "$action: exit native fullscreen before mini transition")
            WebHost.exitFullscreen()
            // Chromium's custom-view callback must finish before shrinking the renderer.
            handler.post {
                if (WebHost.isActiveCarHost(carToken)) finishCarMiniTransition(action)
            }
            return true
        }
        return finishCarMiniTransition(action)
    }

    private fun finishCarMiniTransition(action: String): Boolean {
        if (!WebHost.isActiveCarHost(carToken)) return false
        if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
            BrowseHost.navigate(WebHost.home())
            return true
        }
        if (!WebHost.isOnWatchPage()) {
            AppLog.add("CAR", "$action: no watch page; navigate Home")
            WebHost.openUrl(WebHost.home())
            return true
        }
        WebHost.enterMini()
        val succeeded = WebHost.mode.value == WebHost.PlayerMode.MINI
        AppLog.add("CAR", "$action: mini transition success=$succeeded")
        if (succeeded) {
            BrowseHost.resume()
            BrowseHost.navigate(WebHost.home())
        }
        return succeeded
    }

    private fun carBackToMini(): Boolean {
        if (!WebHost.isActiveCarHost(carToken)) return false
        if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
            if (!BrowseHost.goBack()) AppLog.add("CAR", "Back: already on browse Home")
            return true
        }
        return carVideoToMini("Back")
    }

    private fun carHomeToMini() {
        carVideoToMini("Home")
    }

    @Deprecated("Use car host Back dispatcher when available")
    override fun onBackPressed() {
        carBackToMini()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && carBackToMini()) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        AppLog.add("CAR", "Video projection onStart")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        carStarted = true
        val registered = WebHost.resumeCarHost(carToken, onBecameActive)
        if (!registered || !WebHost.isActiveCarHost(carToken)) return
        ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
        if (experimentalVideoEnabled) {
            CarWindowPixelProbe.register(carToken) {
                CarWindowPixelProbe.capture(carToken, carWindow(), playerContainer)
            }
            CarVideoPatternProbe.register(carToken)
            CarDirectSurfaceProbe.register(carToken)
        }
        WebHost.showOnCar(true, carToken)
        if (WebHost.mode.value != WebHost.PlayerMode.MINI) WebHost.prepareForCarProjection()
        else BrowseHost.resume()
        WebHost.setHold(false)
        WebHost.requestLink = relink
        connectMedia()
        attachIfNeeded()
        scheduleExperimentalRecovery()
        resumeProjection()
    }

    private fun resumeProjection() {
        AppLog.add("CAR", "Video projection onResume")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (WebHost.isActiveCarHost(carToken)) WebHost.setHold(false)
        updateCarWindow()
        attachIfNeeded()
        handler.removeCallbacks(refit)
        handler.postDelayed(refit, 180L)
        scheduleRenderProbes()
        scheduleExperimentalRecovery()
    }

    private fun attachIfNeeded() {
        playerContainer?.let { frame ->
            if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)
        }
    }

    /**
     * Same Quit as the phone: the media process stops playback, its session and
     * notification, then tells the :web process to pause and release the
     * WebViews. The intent is a fallback while MediaBrowser is reconnecting.
     */
    private fun quitFromCar() {
        AppLog.add("CAR", "Quit from car controls")
        mediaController?.command(Commands.EXIT)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context, android.content.Intent(context, PlaybackService::class.java)
                    .setAction(PlaybackService.ACTION_QUIT)
            )
        }.onFailure { AppLog.add("CAR", "Quit intent failed: " + it.javaClass.simpleName) }
        // As on the phone, Quit must still succeed if the media process
        // cannot connect, e.g. car-only launch without an existing session.
        handler.postDelayed({
            if (carStarted && WebHost.isActiveCarHost(carToken) &&
                !carQuitInProgress) shutdownCarOnlySession()
        }, 1_800L)
    }

    private fun shutdownCarOnlySession() {
        if (carQuitInProgress) return
        carQuitInProgress = true
        if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
        if (WebHost.requestLink === relink) WebHost.requestLink = null
        mediaController?.disconnect()
        mediaController = null
        WebHost.cmd("pause")
        BrowseHost.release()
        WebHost.release()
        // The :web process owns the car presentation
        // window, so its explicit shutdown ends the car-only session.
        // onStop cancels the car handler, so use a separate shutdown handler.
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500L)
    }

    /** The native media process remains the sole owner of Android Auto media commands. */
    private fun connectMedia() {
        if (!carStarted || mediaController != null) return
        mediaController = CompatMediaConnection(context) {
            it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)
        }.also { it.connect() }
    }
    private fun pauseProjection() {
        AppLog.add("CAR", "Video projection onPause")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // Do not pause YouTube: a temporary car window focus loss must preserve audio.
    }

    override fun onStop() {
        pauseProjection()
        AppLog.add("CAR", "Video projection onStop; frameAttached=" +
            (playerContainer?.isAttachedToWindow ?: false))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        carStarted = false
        handler.removeCallbacksAndMessages(null)
        CarWindowPixelProbe.unregister(carToken)
        CarVideoPatternProbe.unregister(carToken)
        CarDirectSurfaceProbe.unregister(carToken)
        ExperimentalCarVideoMonitor.end(carToken)
        lastWidth = 0
        lastHeight = 0
        // Only this instance's ownership ends here. A newer car window keeps
        // the player, its car layout and its foreground video quality.
        WebHost.releaseCarHost(carToken)
        if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
        playerContainer?.let { WebHost.detach(it) }
        WebHost.showOnCar(false, carToken)
        if (WebHost.requestLink === relink) {
            WebHost.requestLink = null
            MainActivity.restorePhoneLink()
        }
        mediaController?.disconnect()
        mediaController = null
        // A still-running phone Compose window can reclaim the existing WebView
        // (no-op while another car window is still projecting it).
        WebHost.requestReattachment()
        super.onStop()
    }

    fun release() {
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        dismiss()
        if (lifecycle.currentState == Lifecycle.State.INITIALIZED) {
            handler.removeCallbacksAndMessages(null)
            store.clear()
            return
        }
        AppLog.add("CAR", "Video projection onDestroy")
        CarWindowPixelProbe.unregister(carToken)
        CarVideoPatternProbe.unregister(carToken)
        CarDirectSurfaceProbe.unregister(carToken)
        ExperimentalCarVideoMonitor.end(carToken)
        carStarted = false
        if (WebHost.requestLink === relink) {
            WebHost.requestLink = null
            MainActivity.restorePhoneLink()
        }
        handler.removeCallbacksAndMessages(null)
        WebHost.releaseCarHost(carToken)
        if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
        WebHost.showOnCar(false, carToken)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        playerContainer?.let { WebHost.detach(it) }
        playerContainer = null
        mediaController?.disconnect()
        mediaController = null
        WebHost.requestReattachment()
    }
}

