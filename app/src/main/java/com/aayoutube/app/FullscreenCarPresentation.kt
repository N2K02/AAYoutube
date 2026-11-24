package com.aayoutube.app

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A [Presentation] that runs inside a [android.hardware.display.VirtualDisplay] created by
 * [FullscreenCarService]. It renders the YouTube WebView (via [WebHost]) directly onto
 * the Android Auto head-unit's display surface.
 *
 * Also hosts the Compose overlay UI: FloatingVideoBar, draggable mini-player, browse view
 * and experimental test probes — matching LegacyProjectionActivity's LegacyProjectionScreen.
 */
internal class FullscreenCarPresentation(
    private val context: Context,
    display: Display,
    private val carToken: String,
    private val onSwipeDown: () -> Unit = {},
    private val onVideoTap: () -> Unit = {},
    private val onCarVideoToMini: () -> Unit = {},
    private val onCarBackToMini: () -> Unit = {},
    private val onCarHomeToMini: () -> Unit = {},
    private val onQuit: () -> Unit = {},
) : Presentation(context, display, R.style.Theme_AAYoutube),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private var carFrame: CarFrameLayout? = null
    private var contentRoot: View? = null
    private var isDestroyed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = hostViewModelStore

    /** Expose for watchdog. */
    fun playerFrame(): CarFrameLayout? = carFrame

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
        }

        // Root FrameLayout: WebView frame at bottom, Compose overlay on top.
        val root = FrameLayout(context).apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
        }

        // The CarFrameLayout is the WebView host — same as LegacyProjectionActivity.
        val frame = CarFrameLayout(context).apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    AppLog.add("CAR-FS", "Presentation frame attached display=" +
                        (display?.displayId ?: -1))
                    if (carFrame === v) bindWebView()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    AppLog.add("CAR-FS", "Presentation frame detached")
                }
            })
            onSwipeDown = this@FullscreenCarPresentation.onSwipeDown
            onVideoTap = this@FullscreenCarPresentation.onVideoTap
        }
        carFrame = frame
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))

        // Compose overlay — mirrors LegacyProjectionActivity.LegacyProjectionScreen
        val overlay = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
            setContent { AAYoutubeTheme { FullscreenCarOverlay() } }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        contentRoot = root
    }

    /** Compose overlay — FloatingVideoBar, mini-player, browse, probes. */
    @Composable
    private fun FullscreenCarOverlay() {
        val version by WebHost.webVersion.collectAsState()
        val mode by WebHost.mode.collectAsState()
        val browsing by BrowseHost.created.collectAsState()
        val browseRevision by BrowseHost.revision.collectAsState()
        val mini = mode == WebHost.PlayerMode.MINI
        val carWatchPage by WebHost.carPage.collectAsState()
        val full by WebHost.fullscreenView.collectAsState()
        val activeHost by WebHost.activeCarHost.collectAsState()
        val carTestPattern by CarVideoPatternProbe.state.collectAsState()
        val directSurface by CarDirectSurfaceProbe.state.collectAsState()
        val playing by WebHost.snapshot.collectAsState()
        val selectedWatchVideoId by WebHost.selectedWatchVideoId.collectAsState()
        val flags by Features.state.collectAsState()
        val experimentalVideoEnabled = Features.current.experimentalVideo

        // Stop probes once real playback starts
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
            Modifier.fillMaxSize().background(Color.Transparent)
                .revealControlsOnTouch { controlsVisible = true; controlsVersion++ }
        ) {
            val density = LocalDensity.current
            val miniPrefs = remember {
                context.getSharedPreferences("car_mini", Context.MODE_PRIVATE)
            }
            var miniX by remember { mutableStateOf(miniPrefs.getFloat("x", 1f).coerceIn(0f, 1f)) }
            var miniY by remember { mutableStateOf(miniPrefs.getFloat("y", 1f).coerceIn(0f, 1f)) }
            val carLandscape = ControlsSidePolicy.isLandscape(maxWidth.value, maxHeight.value)
            val (railAlign, railDrag) = rememberEdgeDrag(
                left = flags.controlsOnLeft, landscape = carLandscape,
                containerPx = with(LocalDensity.current) {
                    (if (carLandscape) maxWidth else maxHeight).toPx()
                },
                onActivity = { controlsVisible = true; controlsVersion++ },
            )
            val miniWidth = minOf(240.dp, (maxWidth - 24.dp) * 0.8f)
            val miniHeight = miniWidth * (9f / 16f)
            val minX = 0f
            val minY = 0f
            val maxX = with(density) { (maxWidth - miniWidth).toPx() }.coerceAtLeast(minX)
            val maxY = with(density) { (maxHeight - miniHeight).toPx() }.coerceAtLeast(minY)
            val playerX = minX + (maxX - minX) * miniX
            val playerY = minY + (maxY - minY) * miniY
            val miniVideoModifier = Modifier
                .offset { IntOffset(playerX.roundToInt(), playerY.roundToInt()) }
                .size(miniWidth, miniHeight)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(onDragEnd = {
                        miniPrefs.edit().putFloat("x", miniX).putFloat("y", miniY).apply()
                    }) { change, drag ->
                        change.consume()
                        miniX = (miniX + drag.x / (maxX - minX).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        miniY = (miniY + drag.y / (maxY - minY).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                }

            // Browse view behind mini-player
            if (mini && browsing) key(browseRevision) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).also { frame ->
                            frame.addView(
                                BrowseHost.attach(projectionContext(frame)),
                                FrameLayout.LayoutParams(-1, -1)
                            )
                        }
                    },
                    onRelease = { BrowseHost.detach(it) },
                )
            }

            // The WebView slot: fullsize or mini floating
            key(version) {
                // In mini mode show a clip + controls overlay over the mini slot
                if (mini) {
                    Box(
                        miniVideoModifier
                            .background(Color.Black, RoundedCornerShape(8.dp))
                            .zIndex(10f)
                    ) {
                        val fraction = playing.posMs.toFloat() /
                            playing.durMs.toFloat().coerceAtLeast(1f)
                        // play/pause
                        FilledTonalIconButton(
                            onClick = { WebHost.cmd(if (playing.playing) "pause" else "play") },
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(40.dp),
                        ) {
                            Icon(
                                if (playing.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing.playing) "Pause" else "Play",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // close
                        FilledTonalIconButton(
                            onClick = { WebHost.closePlayer() },
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(40.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close",
                                modifier = Modifier.size(22.dp))
                        }
                        // progress bar
                        Box(
                            Modifier.align(Alignment.BottomStart)
                                .fillMaxWidth(fraction).height(3.dp)
                                .background(Color(0xFFFF0033)),
                        )
                    }
                }
            }

            // Native fullscreen view slot
            val fullscreenView = full
            if (fullscreenView != null && activeHost == carToken) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        CarFrameLayout(ctx).also { f ->
                            (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                            f.addView(fullscreenView, FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { it.removeAllViews() },
                )
            }

            // Test-pattern probe overlay
            if (carTestPattern.visible && carTestPattern.owner == carToken &&
                activeHost == carToken && experimentalVideoEnabled) {
                Box(Modifier.fillMaxSize().zIndex(40f)) {
                    CarVideoPatternView()
                }
            }

            // Direct-surface probe overlay
            if (directSurface.visible && directSurface.owner == carToken &&
                activeHost == carToken && experimentalVideoEnabled && !playing.playing) {
                key(directSurface.runId) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().zIndex(45f),
                        factory = { _ ->
                            CarDirectSurfaceView(
                                projectionContext(),
                                carToken,
                                directSurface.runId,
                            )
                        },
                        onRelease = { it.stopRendering() },
                    )
                }
            }

            // FloatingVideoBar
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
                    onBack = { onCarBackToMini(); controlsVersion++ },
                    onHome = { onCarHomeToMini(); controlsVersion++ },
                    onPlayPause = {
                        WebHost.cmd(if (playing.playing) "pause" else "play")
                        controlsVersion++
                    },
                    onPrevious = { WebHost.cmd("prev"); controlsVersion++ },
                    onNext = { WebHost.cmd("next"); controlsVersion++ },
                ) {
                    if (VideoFitPolicy.buttonShown(
                            filled = !mini && !carWatchPage,
                            playing = playing.playing
                        )
                    ) BarIcon(
                        Icons.Filled.AspectRatio,
                        VideoFitPolicy.label(flags.videoFit),
                        BarSize.Car,
                        onClick = {
                            Features.update { it.copy(videoFit = VideoFitPolicy.next(it.videoFit)) }
                            controlsVersion++
                        })
                    BarIcon(
                        Icons.Rounded.PowerSettingsNew,
                        "Quit AAYoutube", BarSize.Car,
                        onClick = { onQuit() },
                    )
                }
            }
        }
    }

    /** Attach the shared YouTube WebView into the car presentation frame. */
    fun bindWebView() {
        val frame = carFrame ?: return
        if (!frame.isAttachedToWindow || frame.display == null) return
        if (!WebHost.isActiveCarHost(carToken)) return

        val displayCtx = projectionContext(frame)

        val player = WebHost.attachToCarWindow(displayCtx, frame)
        if (frame.childCount != 1 || frame.getChildAt(0) !== player) {
            frame.removeAllViews()
            frame.addView(player, FrameLayout.LayoutParams(-1, -1))
            frame.isFocusableInTouchMode = true
            frame.requestFocus()
            AppLog.add(
                "CAR-FS",
                "WebView bound to presentation display=${frame.display?.displayId ?: -1}" +
                    " size=${frame.width}x${frame.height}"
            )
        }
    }

    fun isMediaPlaying(): Boolean = WebHost.snapshot.value.playing

    fun updateVisibleArea(surfaceWidth: Int, surfaceHeight: Int, visibleArea: Rect?) {
        val root = contentRoot ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || visibleArea == null) {
            root.setPadding(0, 0, 0, 0)
            return
        }
        val hThreshold = (surfaceWidth * MIN_OCCLUSION_RATIO).toInt()
        val vThreshold = (surfaceHeight * MIN_OCCLUSION_RATIO).toInt()
        val left = visibleArea.left.coerceIn(0, surfaceWidth)
        val top = visibleArea.top.coerceIn(0, surfaceHeight)
        val right = (surfaceWidth - visibleArea.right).coerceIn(0, surfaceWidth)
        val bottom = (surfaceHeight - visibleArea.bottom).coerceIn(0, surfaceHeight)
        root.setPadding(
            left.takeIf { it > hThreshold } ?: 0,
            top.takeIf { it > vThreshold } ?: 0,
            right.takeIf { it > hThreshold } ?: 0,
            bottom.takeIf { it > vThreshold } ?: 0
        )
    }

    private fun projectionContext(frame: View? = null): Context {
        val d = frame?.display ?: window?.decorView?.display ?: return context
        return runCatching {
            android.view.ContextThemeWrapper(
                context.createDisplayContext(d), R.style.Theme_AAYoutube
            )
        }.getOrElse {
            AppLog.add("CAR-FS", "Projection display context failed: " + it.javaClass.simpleName)
            context
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        if (!isDestroyed) lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val out = super.onSaveInstanceState()
        savedStateController.performSave(out)
        return out
    }

    override fun dismiss() {
        if (isDestroyed) return
        isDestroyed = true
        carFrame = null
        contentRoot = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        hostViewModelStore.clear()
        super.dismiss()
    }

    private companion object {
        const val MIN_OCCLUSION_RATIO = 0.05f
    }
}
