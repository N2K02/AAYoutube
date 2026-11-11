@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.aayoutube.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OndemandVideo
import dev.n2k02.updater.hasPendingUpdate
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import kotlinx.coroutines.delay
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Context.findActivity(): MainActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is MainActivity) return c
        c = c.baseContext
    }
    return null
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Watch("Home", Icons.Filled.PlayCircle),
    Features("Features", Icons.Filled.Tune),
    Lyrics("Lyrics", Icons.Filled.Info),
    Update("Update", Icons.Filled.SystemUpdate),
    Info("About", Icons.Filled.Info),
    Settings("Settings", Icons.Filled.Settings),
    // Append to preserve previously saved tab ordinals when updating the app.
    ExperimentalVideo("Experimental video", Icons.Filled.OndemandVideo),
}

/** One host-owned header, matching the mRadio icon/title + app icon/name layout. */
@Composable
private fun AAYoutubePageHeader(
    tab: Tab,
    lyricsAvailable: Boolean,
    lyricsShown: Boolean,
    onLyrics: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    if (tab == Tab.Update) "About & updates" else tab.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tab == Tab.Watch) {
                    IconButton(
                        onClick = onLyrics,
                        enabled = lyricsAvailable,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = if (lyricsAvailable) {
                                if (lyricsShown) "Hide lyrics" else "Show lyrics"
                            } else "Lyrics unavailable",
                            tint = if (lyricsAvailable && lyricsShown) MaterialTheme.colorScheme.primary
                                else if (lyricsAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                dev.n2k02.updater.N2K02AppIcon(Modifier.size(28.dp))
                Text(
                    "AAYoutube",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun AppRoot(activity: MainActivity) {
    val context = LocalContext.current
    // Debug-only entry point for repeatable phone screenshots of the car-layout preview.
    // A normal launch and all release builds still start on YouTube Home.
    val openCarPreview = BuildConfig.DEBUG &&
        activity.intent?.getBooleanExtra("com.aayoutube.app.EXTRA_CAR_PREVIEW", false) == true
    var tab by rememberSaveable {
        mutableIntStateOf(if (openCarPreview) Tab.ExperimentalVideo.ordinal else 0)
    }
    // Keep the departing settings panel mounted until its fade-out completes.
    var lastPanel by remember { mutableStateOf(Tab.Settings) }
    var equalizerOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { if (tab != Tab.Watch.ordinal) lastPanel = Tab.entries[tab] }
    val lyricsEnabled by LyricsPrefs.enabledState.collectAsState()
    val lyricsShown by LyricsPrefs.shownState.collectAsState()
    val providerVersion by LyricsPrefs.providersVersion.collectAsState()
    val fullscreenView by WebHost.fullscreenView.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    val onCar by WebHost.carScreen.collectAsState()
    val projectedToCar by WebHost.carProjectionActive.collectAsState()
    // While Android Auto projects the video, YouTube's fullscreen view belongs
    // to the car window. A still-composed phone screen must not reparent it
    // (car turns black) or rotate the phone for it.
    val fullscreen = fullscreenView.takeIf { !projectedToCar || onCar }
    val snap by WebHost.snapshot.collectAsState()
    val playerMode by WebHost.mode.collectAsState()
    val flags by Features.state.collectAsState()
    val watchTab = tab == Tab.Watch.ordinal
    // Rotating sideways while watching fills the screen with the video (like the YouTube app);
    // rotating back restores the page. Chromium's own fullscreen needs a tap, so this uses the
    // page layout instead and keeps YouTube's controls.
    val orientation = LocalConfiguration.current.orientation
    val watchPage by WebHost.onWatchPage.collectAsState()
    val landDismissed by WebHost.landscapeDismissed.collectAsState()
    LaunchedEffect(orientation) {
        if (orientation != Configuration.ORIENTATION_LANDSCAPE) WebHost.landscapeDismissed.value = false
    }
    val landFill = LandscapeFillPolicy.active(
        landscape = orientation == Configuration.ORIENTATION_LANDSCAPE,
        watchTab = watchTab, watchPage = watchPage,
        fullPlayer = playerMode == WebHost.PlayerMode.FULL,
        inPip = inPip, fullscreen = fullscreen != null, car = onCar || projectedToCar,
        dismissed = landDismissed,
    )
    LaunchedEffect(landFill) { WebHost.setLandscapeFill(landFill) }
    DisposableEffect(landFill) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (landFill) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (landFill) controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val headerAllowed = showAAYoutubeHeader(watchTab, onCar, inPip, fullscreen != null || landFill)
    // Battery/GPU: a <=60 fps video does not need a 120 Hz screen. While it plays in the
    // full player (or PiP), ask Android for 60 Hz, as the YouTube app does. Browsing,
    // the mini player and Settings keep the normal rate so scrolling stays smooth.
    val lowRefresh = RefreshRatePolicy.capTo60(
        playing = snap.playing, watchTab = watchTab,
        fullPlayer = playerMode == WebHost.PlayerMode.FULL, inPip = inPip,
        car = onCar || projectedToCar,
    )
    DisposableEffect(lowRefresh) {
        val w = activity.window
        val before = w.attributes.preferredRefreshRate
        if (lowRefresh) w.attributes = w.attributes.also { it.preferredRefreshRate = 60f }
        onDispose {
            if (lowRefresh) w.attributes = w.attributes.also { it.preferredRefreshRate = before }
        }
    }
    // Entering fullscreen must not flash the menu; it appears on the next touch.
    var topMenuVisible by rememberSaveable { mutableStateOf(true) }
    var topMenuEpoch by remember { mutableIntStateOf(0) }
    // Every return to the video opens the menu briefly; Settings and all
    // subpages keep their normal, permanent single app header.
    LaunchedEffect(watchTab, headerAllowed) {
        if (watchTab && headerAllowed) {
            topMenuVisible = true
            topMenuEpoch++
        }
    }
    LaunchedEffect(watchTab, headerAllowed, topMenuVisible, topMenuEpoch, flags.topMenuTimeoutSeconds) {
        val timeout = TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)
        if (watchTab && headerAllowed && topMenuVisible && timeout != null) {
            delay(timeout)
            topMenuVisible = false
        }
    }
    val selectedWatchVideoId by WebHost.selectedWatchVideoId.collectAsState()
    val lyricsRequest = LyricsRequestIdentity.forSelection(
        snap, selectedWatchVideoId, lyricsEnabled, providerVersion
    )
    // Video-keyed state is empty *during the first recomposition* of a new
    // selection. Resetting ordinary state inside LaunchedEffect was one frame
    // too late, briefly displaying lyrics from the previous video.
    var foundLyrics by remember(lyricsRequest) { mutableStateOf(LyricsContent()) }
    // A saved visibility preference must never auto-open lyrics during navigation.
    // Each video requires an explicit lyrics-button tap.
    var lyricsOpenedForVideo by remember(selectedWatchVideoId) { mutableStateOf(false) }

    // Search only after the selected Watch video has valid metadata. Searching
    // is independent of overlay visibility, so the lyrics button can still
    // enable when a user has hidden the panel.
    LaunchedEffect(lyricsRequest) {
        if (lyricsRequest == null) return@LaunchedEffect
        val requestSnap = snap
        val cached = LyricsCache.get(lyricsRequest.videoId, requestSnap)
        val result = cached ?: LyricsProviders.fetch(context, requestSnap).also {
            LyricsCache.put(lyricsRequest.videoId, requestSnap, it)
        }
        foundLyrics = LyricsContent(lyricsRequest.videoId, result.first, result.second)
        AppLog.add("LYRICS", "Result for selected video ${lyricsRequest.videoId}: timed=${result.first.size} plain=${!result.second.isNullOrBlank()}")
    }
    val lyricsAvailable = lyricsRequest != null &&
        foundLyrics.foundFor(lyricsRequest.videoId)
    val lyricsOnScreen = lyricsAvailable && lyricsShown && lyricsOpenedForVideo &&
        tab == Tab.Watch.ordinal && !inPip && fullscreen == null &&
        playerMode == WebHost.PlayerMode.FULL

    // Subscribe to the actual HTML video clock only while timed lines are visible.
    // A hidden/plain-text lyrics panel must not leave a 250 ms polling loop running.
    DisposableEffect(lyricsOnScreen, snap.id, foundLyrics.lines.isNotEmpty()) {
        WebHost.setLyricsTracking(lyricsOnScreen && foundLyrics.lines.isNotEmpty())
        onDispose { WebHost.setLyricsTracking(false) }
    }

    // Respond to PiP setting changes and avoid entering PiP from a settings screen.
    LaunchedEffect(snap.playing, flags.autoPip, playerMode, tab, inPip) {
        activity.updatePip(tab == Tab.Watch.ordinal)
    }
    // Picture in picture always shows the video: go back to the YouTube tab.
    LaunchedEffect(inPip) { if (inPip) tab = Tab.Watch.ordinal }
    LaunchedEffect(tab) { AppLog.add("UI", "Tab ${Tab.entries[tab].label}") }

    // Fullscreen video: landscape, no system bars.
    DisposableEffect(fullscreen != null) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Back (system or Settings action): close fullscreen, then other tabs, then the
    // page's own history (asked live from the WebView, because YouTube changes pages without
    // reloading), then YouTube home. On the home page the app goes to the background (it keeps playing).
    // YouTube Home: a playing video shrinks to the mini player instead of stopping.
    val goYouTubeHome: () -> Unit = {
        tab = Tab.Watch.ordinal
        if (WebHost.mode.value == WebHost.PlayerMode.FULL && WebHost.isOnWatchPage())
            WebHost.enterMini()
        else if (WebHost.mode.value == WebHost.PlayerMode.MINI)
            BrowseHost.navigate(WebHost.home())
        else WebHost.openUrl(WebHost.home())
    }
    val goBack: () -> Unit = {
        when {
            equalizerOpen -> equalizerOpen = false
            fullscreen != null -> WebHost.exitFullscreen()
            // Landscape video: Back first shows the watch page (player + playlist).
            WebHost.landscapeFill.value -> WebHost.dismissLandscapeFill()
            tab == Tab.Settings.ordinal -> tab = Tab.Watch.ordinal
            tab != Tab.Watch.ordinal -> tab = Tab.Settings.ordinal
            // Browsing under the mini player (or after closing it): back moves through the browse page.
            WebHost.mode.value != WebHost.PlayerMode.FULL && BrowseHost.goBack() -> Unit
            // Car screen: already on Home; stay on the car screen instead of disappearing from it.
            WebHost.carDisplay && WebHost.mode.value != WebHost.PlayerMode.FULL -> AppLog.add("UI", "Back on Home (car screen): nothing to go back to")
            WebHost.mode.value != WebHost.PlayerMode.FULL -> activity.moveTaskToBack(true)
            // Car screen: back from a video always shrinks it into the mini player with YouTube
            // Home underneath, like the YouTube app (playing or paused).
            WebHost.carDisplay && WebHost.isOnWatchPage() -> WebHost.enterMini()
            // Even a paused video goes to mini-player. A stale MediaSession snapshot or
            // YouTube SPA history must never make Back navigate the main player to Home.
            WebHost.isOnWatchPage() -> WebHost.enterMini()
            WebHost.goBack() -> Unit
            WebHost.carDisplay -> AppLog.add("UI", "Back on YouTube home (car screen): nothing to go back to")
            else -> {
                AppLog.add("UI", "Back on YouTube home: app to background")
                activity.moveTaskToBack(true)
            }
        }
    }
    BackHandler(enabled = true) { goBack() }

    // One fixed layout for every mode (normal, fullscreen, picture in picture): the page is never
    // moved between parents, because detaching a WebView from its window pauses the video.
    Box(
        Modifier.fillMaxSize().then(
            if (watchTab && headerAllowed) Modifier.revealControlsOnTouch {
                topMenuVisible = true
                topMenuEpoch++
            } else Modifier
        )
    ) {
        // Keep the video WebView at a constant native size while opening a menu.
        // Changing Scaffold.topBar padding used to resize the decoder and cause stalls.
        Box(Modifier.fillMaxSize()) {
            // mRadio's status-bar safe area, applied to YouTube content on the
            // phone only. Never inset native fullscreen, PiP or car projection.
            // The app header already has its own statusBarsPadding().
            WebPane(Modifier.fillMaxSize())
            val panel = if (tab != Tab.Watch.ordinal) Tab.entries[tab] else lastPanel
            AnimatedVisibility(
                visible = tab != Tab.Watch.ordinal && !inPip,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) { },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Single header belongs to the overlay, not to the WebView's layout.
                        AAYoutubePageHeader(
                            panel, lyricsAvailable = false, lyricsShown = false, onLyrics = {},
                        )
                        Crossfade(
                            targetState = panel,
                            animationSpec = tween(160),
                            modifier = Modifier.weight(1f),
                            label = "Settings navigation",
                        ) { page ->
                            when (page) {
                                Tab.Watch -> Unit
                                Tab.Features -> FeaturesPage()
                                Tab.ExperimentalVideo -> ExperimentalVideoPage()
                                Tab.Lyrics -> LyricsSettingsPage()
                                Tab.Update -> UpdatePage()
                                Tab.Info -> InfoPage()
                                Tab.Settings -> AAYoutubeSettingsMenu(
                                    onSelect = { tab = it.ordinal },
                                    onEqualizer = { equalizerOpen = true },
                                )
                            }
                        }
                    }
                }
            }
        }
        // App-owned watch header overlays the WebView without relayout.
        // Android Auto's separate native host bars remain host-controlled.
        val phoneLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val videoOnScreen = VideoRefreshPolicy.capped(
            watchTab = watchTab, playing = snap.playing, playerMode = playerMode,
        )
        LaunchedEffect(videoOnScreen) { activity.setVideoRefreshCap(videoOnScreen) }
        if (watchTab && headerAllowed) {
            // Same small floating side rail as Android Auto (one shared side
            // setting, Right by default). Any touch on the page shows it.
            AnimatedVisibility(
                visible = topMenuVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(ControlsSidePolicy.alignment(flags.controlsOnLeft, phoneLandscape))
                    .then(
                        if (phoneLandscape) Modifier.statusBarsPadding().padding(top = 8.dp)
                        else Modifier.padding(horizontal = 8.dp)
                    ).zIndex(20f),
            ) {
                val canLyrics = lyricsAvailable && playerMode == WebHost.PlayerMode.FULL
                val updateStatus by LiveUpdater.controller.status.collectAsState()
                FloatingVideoBar(
                    playing = snap.playing,
                    playbackId = snap.id,
                    transportReady = OverlayTransportPolicy.ready(
                        snap.id, selectedWatchVideoId, snap.buffering, snap.ended
                    ) && playerMode == WebHost.PlayerMode.FULL,
                    onBack = { goBack(); topMenuEpoch++ },
                    onHome = { goYouTubeHome(); topMenuEpoch++ },
                    onPlayPause = {
                        WebHost.cmd(if (snap.playing) "pause" else "play")
                        topMenuEpoch++
                    },
                    onPrevious = { WebHost.cmd("prev"); topMenuEpoch++ },
                    onNext = { WebHost.cmd("next"); topMenuEpoch++ },
                    vertical = ControlsSidePolicy.vertical(phoneLandscape),
                ) {
                    BarIcon(
                        Icons.Filled.MusicNote,
                        if (canLyrics) { if (lyricsShown) "Hide lyrics" else "Show lyrics" } else "Lyrics unavailable",
                        BarSize.Phone,
                        onClick = {
                            if (lyricsAvailable) {
                                lyricsOpenedForVideo = !lyricsOpenedForVideo
                                LyricsPrefs.set(context, "show", lyricsOpenedForVideo)
                            }
                            topMenuEpoch++
                        },
                        enabled = canLyrics,
                        tint = if (canLyrics && lyricsShown) MaterialTheme.colorScheme.primary else null,
                    )
                    Box {
                        BarIcon(Icons.Filled.Settings, "Settings", BarSize.Phone,
                            onClick = { tab = Tab.Settings.ordinal })
                        dev.n2k02.updater.N2K02UpdateBadge(
                            updateStatus,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    // Quit stops playback, services and the WebViews, then closes AAYoutube.
                    BarIcon(Icons.Rounded.PowerSettingsNew, "Quit AAYoutube", BarSize.Phone,
                        onClick = { activity.quit() })
                }
            }
        }
        if (equalizerOpen && !inPip && fullscreen == null) {
            EqualizerSheet(onDismiss = { equalizerOpen = false })
        }
        if (lyricsOnScreen) {
            // Reduce overlap while the menu is hidden; no second app header.
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = 30.dp)) {
                key(lyricsRequest) { LyricsOverlay(snap, foundLyrics) {
                    lyricsOpenedForVideo = false
                    LyricsPrefs.set(context, "show", false)
                } }
            }
        }
        val full = fullscreen
        if (full != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).also { frame ->
                        (full.parent as? ViewGroup)?.removeView(full)
                        frame.addView(full, FrameLayout.LayoutParams(-1, -1))
                    }
                },
                onRelease = { it.removeAllViews() },
            )
        }
    }
}

// ---------------------------------------------------------------------------- web

/**
 * Swipe down on the video (car screen): mini player with YouTube underneath, like on the phone.
 * Only watches the touches (Initial pass, nothing consumed), so taps and YouTube's controls still work.
 */
private val carSwipeDown = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        var startX = 0f
        var startY = 0f
        var startAt = 0L
        var tracking = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull() ?: continue
            when (e.type) {
                PointerEventType.Press -> {
                    tracking = e.changes.size == 1
                    startX = c.position.x
                    startY = c.position.y
                    startAt = c.uptimeMillis
                }
                PointerEventType.Release -> if (tracking) {
                    tracking = false
                    val dx = c.position.x - startX
                    val dy = c.position.y - startY
                    if (dy > 72.dp.toPx() && kotlin.math.abs(dx) < dy / 2 && c.uptimeMillis - startAt < 900 &&
                        WebHost.mode.value == WebHost.PlayerMode.FULL && WebHost.isOnWatchPage()
                    ) {
                        AppLog.add("VIEW", "Native swipe down on video: mini player")
                        WebHost.enterMini()
                    }
                }
                else -> if (e.changes.size > 1) tracking = false
            }
        }
    }
}

@Composable
fun WebPane(modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val version by WebHost.webVersion.collectAsState()
    val progress by WebHost.progress.collectAsState()
    val offline by WebHost.offline.collectAsState()
    val mode by WebHost.mode.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    val snap by WebHost.snapshot.collectAsState()
    val browseOn by BrowseHost.created.collectAsState()
    val browseRevision by BrowseHost.revision.collectAsState()
    val onCar by WebHost.carScreen.collectAsState()
    val projectedToCar by WebHost.carProjectionActive.collectAsState()
    // Picture in picture always shows the video full size, whatever the mode.
    val small = mode == WebHost.PlayerMode.MINI && !inPip
    val hidden = mode == WebHost.PlayerMode.HIDDEN && !inPip

    // The browse page only works while you can see it.
    LaunchedEffect(mode, inPip) {
        if (mode == WebHost.PlayerMode.FULL || inPip) BrowseHost.pause() else BrowseHost.resume()
    }

    // Keep the WebPane host's dimensions stable across Settings transitions.
    // Inset only the phone WebView content, never Android Auto, PiP or native fullscreen.
    val landFill by WebHost.landscapeFill.collectAsState()
    val phoneSafeArea = !onCar && !projectedToCar && !inPip && !landFill &&
        WebHost.fullscreenView.collectAsState().value == null
    Box(modifier.then(if (phoneSafeArea) Modifier.statusBarsPadding() else Modifier)) {
        // Browse page (behind): made the first time the mini player opens.
        if (browseOn && !inPip) {
            key(browseRevision) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val activity = ctx.findActivity()!!
                        FrameLayout(ctx).also { frame ->
                            frame.addView(BrowseHost.attach(activity), FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { BrowseHost.detach(it) },
                )
            }
        }

        // Player page: never moved to another parent (that would pause the video); only its size
        // and place change. Mini player: a floating window like the YouTube app's, draggable,
        // starting above YouTube's own bottom menu so it covers nothing important.
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("aay_ui", Context.MODE_PRIVATE) }
        val density = LocalDensity.current
        var mx by remember { mutableStateOf(prefs.getFloat("miniX", 1f)) }
        var my by remember { mutableStateOf(prefs.getFloat("miniY", 1f)) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val miniW = minOf(224.dp, (maxWidth - 24.dp) * 0.8f)
            val miniH = miniW * (9f / 16f)
            val edge = with(density) { 12.dp.toPx() }
            val ytMenu = with(density) { 64.dp.toPx() } // YouTube's bottom menu (Home, Subscriptions, You)
            val wPx = with(density) { miniW.toPx() }
            val hPx = with(density) { miniH.toPx() }
            // Let the mini-player reach both screen edges, including behind the menu.
            // The menu itself has the higher z-index and remains fully clickable.
            val minX = edge
            val maxX = (constraints.maxWidth.toFloat() - wPx - edge).coerceAtLeast(minX)
            val minY = edge
            val maxY = (constraints.maxHeight - hPx - edge - ytMenu).coerceAtLeast(minY)
            val px = minX + (maxX - minX) * mx
            val py = minY + (maxY - minY) * my
            val miniMod = Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.size(miniW, miniH)

            // Resize the Android WebView itself. Scaling a graphicsLayer changes
            // the Compose container, but can leave Chromium's video Surface
            // at the old fullscreen dimensions (grey frames/letterboxing).
            val playerMod = when {
                inPip -> Modifier.fillMaxSize()
                hidden -> Modifier.size(1.dp)
                small -> Modifier.offset {
                    IntOffset(px.roundToInt(), py.roundToInt())
                }.size(miniW, miniH).clip(RoundedCornerShape(12.dp))
                else -> Modifier.fillMaxSize()
            }.then(if (!small && !inPip && onCar) carSwipeDown else Modifier)
            // Smooth mode changes (mini, expand, landscape fill): a scrim over the video
            // fades out. The WebView itself is never made translucent (that drops Chromium
            // off its video overlay path: more GPU, possible black frames) and is resized
            // once, never per frame (that stalls the decoder).
            val scrim = remember { Animatable(0f) }
            var fadeArmed by remember { mutableStateOf(false) }
            LaunchedEffect(small, hidden, landFill) {
                if (!fadeArmed) { fadeArmed = true; return@LaunchedEffect }
                if (inPip || onCar || projectedToCar) return@LaunchedEffect
                scrim.snapTo(0.7f)
                scrim.animateTo(0f, tween(240))
            }
            // Do not let a still-resumed phone screen steal the car's player.
            if (!projectedToCar || onCar) key(version) {
                AndroidView(
                    modifier = playerMod,
                    factory = { ctx ->
                        val activity = ctx.findActivity()!!
                        FrameLayout(ctx).also { frame ->
                            frame.addView(WebHost.attach(activity), FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { WebHost.detach(it) },
                )
            }

            if (scrim.value > 0f && !onCar) Box(
                playerMod.graphicsLayer { alpha = scrim.value }.background(Color.Black),
            )

            // Mini player controls (like the YouTube app): play/pause top left, close top right,
            // red progress line at the bottom. Tap to open it again, drag to move it.
            if (small) {
                // Progress: the page reports now and then; move it along in between.
                var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(snap) {
                    if (!snap.playing) return@LaunchedEffect
                    while (true) {
                        now = android.os.SystemClock.elapsedRealtime()
                        delay(500)
                    }
                }
                val seenAt = remember(snap) { android.os.SystemClock.elapsedRealtime() }
                val pos = if (snap.playing) snap.posMs + ((now - seenAt) * snap.rate).toLong() else snap.posMs
                val fraction = if (snap.durMs > 0) (pos.toFloat() / snap.durMs).coerceIn(0f, 1f) else 0f

                Box(
                    miniMod
                        .pointerInput(maxX, maxY) {
                            detectDragGestures(
                                onDragEnd = { prefs.edit().putFloat("miniX", mx).putFloat("miniY", my).apply() },
                            ) { change, drag ->
                                change.consume()
                                mx = (mx + drag.x / (maxX - minX).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                my = (my + drag.y / (maxY - minY).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onTap = { WebHost.expandPlayer() }) },
                ) {
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = { WebHost.cmd(if (snap.playing) "pause" else "play") },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(36.dp),
                    ) {
                        Icon(
                            if (snap.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (snap.playing) "Pause" else "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    androidx.compose.material3.FilledTonalIconButton(
                        onClick = { WebHost.closePlayer() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(20.dp)) }
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp)
                            .background(Color(0xFFFF0033)),
                    )
                }
            }
        }

        if (progress in 0..99 && mode == WebHost.PlayerMode.FULL) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
        if (offline && mode == WebHost.PlayerMode.FULL) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(Modifier.padding(24.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Can't reach YouTube", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("Check your connection and try again.")
                        Spacer(Modifier.height(16.dp))
                        AppButton("Retry", Icons.Filled.Refresh) { WebHost.reload() }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings navigation

/** Secondary pages live under Settings, like mRadio; the WebView keeps running underneath. */
@Composable
private fun AAYoutubeSettingsMenu(
    onSelect: (Tab) -> Unit,
    onEqualizer: () -> Unit,
) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    Column(Modifier.fillMaxSize()) {
        // No bottom bar: system Back returns to the video, Quit is on the floating rail.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Make watching your own",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
            )
            ListItem(
                headlineContent = { Text("Equalizer") },
                supportingContent = {
                    Text("5-band sound, presets, bass and experimental 3D sound")
                },
                leadingContent = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEqualizer),
            )
            listOf(Tab.Features, Tab.ExperimentalVideo, Tab.Lyrics, Tab.Info, Tab.Update).forEach { destination ->
                if (destination == Tab.Update) HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(if (destination == Tab.Update) "About & updates" else destination.label)
                    },
                    supportingContent = {
                        Text(when (destination) {
                            Tab.Features -> "Playback, video and display preferences"
                            Tab.ExperimentalVideo -> "Optional Android Auto video recovery tests"
                            Tab.Lyrics -> "Lyrics provider and overlay preferences"
                            Tab.Info -> "App information"
                            Tab.Update -> "Version, experimental releases and update checks"
                            else -> ""
                        })
                    },
                    leadingContent = { Icon(destination.icon, contentDescription = null) },
                    trailingContent = {
                        if (destination == Tab.Update)
                            dev.n2k02.updater.N2K02UpdateBadge(updateStatus)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(destination) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- features

@Composable
fun FeaturesPage() {
    val f by Features.state.collectAsState()
    val account by WebHost.youtubeAccount.collectAsState()

    @Composable
    fun Toggle(title: String, text: String, value: Boolean, enabled: Boolean = true, change: (FeatureFlags, Boolean) -> FeatureFlags) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(text) },
            trailingContent = {
                Switch(checked = value, enabled = enabled, onCheckedChange = { on -> Features.update { change(it, on) } })
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }

    @Composable
    fun <T> Choice(title: String, text: String, options: List<T>, selected: T, label: (T) -> String, pick: (T) -> Unit) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    if (option == selected) Button(onClick = { pick(option) }) { Text(label(option)) }
                    else OutlinedButton(onClick = { pick(option) }) { Text(label(option)) }
                }
            }
        }
    }

    @Composable
    fun CodecPicker(selected: String, pick: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Box {
            ListItem(
                headlineContent = { Text("Video format") },
                supportingContent = {
                    Text("AV1 uses the least data (less battery on Wi-Fi and mobile data). " +
                        "H.264 works best everywhere. Applies from the next video.")
                },
                trailingContent = {
                    OutlinedButton(onClick = { open = true }) { Text(VideoCodecPolicy.label(selected) + "  ▾") }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                VideoCodecPolicy.options.forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(VideoCodecPolicy.label(option)) },
                        onClick = { open = false; pick(option) },
                        trailingIcon = if (option == selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card() {
            Toggle("Block ads", "Strips ads from video data and skips what remains.", f.blockAds) { s, v -> s.copy(blockAds = v) }
            Toggle("Hide Shorts", "Removes Shorts shelves; Shorts links open as normal videos.", f.hideShorts) { s, v -> s.copy(hideShorts = v) }
            Toggle("Hide promos", "Removes banners that ask you to install or upgrade.", f.hidePromos) { s, v -> s.copy(hidePromos = v) }
            Toggle("Background play", "Prevents YouTube pausing when the screen is hidden. Android Auto playback also uses the media service.", f.backgroundPlay) { s, v -> s.copy(backgroundPlay = v) }
            Toggle("Save battery in background", "With the app hidden for a minute, the unseen video drops to 360p while the audio keeps playing. Your quality returns as soon as you open the app. Never in PiP, Android Auto, ads or live streams; turns itself off if audio stalls.", f.lowQualityInBackground, enabled = f.backgroundPlay) { s, v -> s.copy(lowQualityInBackground = v) }
            Toggle("Audio only in background", "When the app leaves the screen (not PiP or Android Auto), the unseen video stops decoding and only the audio plays. The picture comes back when you open the app. Turns itself off if the audio drops.", f.audioOnlyBackground, enabled = f.backgroundPlay) { s, v -> s.copy(audioOnlyBackground = v) }
            ListItem(headlineContent = { Text("Video quality") },
                supportingContent = { Text("Starts at 720p; choosing another resolution in YouTube’s quality menu saves it for future videos. Current: " + f.preferredQuality) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            Toggle("Picture in picture", "Shrink to a floating player when you leave the app.", f.autoPip) { s, v -> s.copy(autoPip = v) }
            Toggle("Keep watching", "Attempts to dismiss YouTube's inactivity prompts.", f.autoContinue) { s, v -> s.copy(autoContinue = v) }
        }
        Card() {
            Toggle("SponsorBlock", "Skips sponsors, self-promotion and interaction reminders.", f.sponsorBlock) { s, v -> s.copy(sponsorBlock = v) }
            Toggle("Also skip intros and outros", "Adds introductions and end segments to SponsorBlock.", f.sponsorExtra, enabled = f.sponsorBlock) { s, v -> s.copy(sponsorExtra = v) }
            Toggle("Return YouTube Dislike", "Shows the dislike count again.", f.returnDislike) { s, v -> s.copy(returnDislike = v) }
            Toggle("Desktop site", "Use desktop YouTube. Switching returns to Home and interrupts the current video.", f.desktopSite) { s, v -> s.copy(desktopSite = v) }
        }
        Text("Player", style = MaterialTheme.typography.titleMedium)
        Card() {
            Choice("Playback speed", "Every video starts at this speed.",
                PlayerPrefsPolicy.speeds, f.playbackSpeed, PlayerPrefsPolicy::speedLabel) { v ->
                Features.update { it.copy(playbackSpeed = v) }
            }
            Toggle("Remember speed", "A speed picked in YouTube's menu becomes the new default.", f.rememberSpeed) { s, v -> s.copy(rememberSpeed = v) }
            Choice("Double-tap seek", "How far a double tap on the video jumps. 10s is YouTube's own gesture.",
                PlayerPrefsPolicy.seekSeconds, f.seekSeconds, { "${it}s" }) { v ->
                Features.update { it.copy(seekSeconds = v) }
            }
            Choice("Captions", "Default leaves YouTube's own choice alone.",
                PlayerPrefsPolicy.captions, f.captions, { o -> o.replaceFirstChar { c -> c.uppercase() } }) { v ->
                Features.update { it.copy(captions = v) }
            }
            CodecPicker(f.videoCodec) { v -> Features.update { it.copy(videoCodec = v) } }
            Toggle("Loop video", "Repeats the current video instead of playing the next one.", f.loopVideo) { s, v -> s.copy(loopVideo = v) }
            Toggle("Hide comments", "Removes the comments section from video pages.", f.hideComments) { s, v -> s.copy(hideComments = v) }
            Toggle("Hide end-screen cards", "Removes suggested-video cards over the end of a video.", f.hideEndCards) { s, v -> s.copy(hideEndCards = v) }
        }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Card() {
            Toggle("Resume where you left off", "Reopens your last page when starting the app; playback does not automatically resume.", f.resumeLast) { s, v -> s.copy(resumeLast = v) }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 8.dp)) {
                Text("Video controls auto-hide", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Hide the floating video controls on your phone and Android Auto. Touch the screen to show them again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TopMenuPolicy.options.forEach { seconds ->
                        val label = if (seconds == 0) "Never" else "${seconds}s"
                        if (f.topMenuTimeoutSeconds == seconds) {
                            Button(onClick = { Features.update { it.copy(topMenuTimeoutSeconds = seconds) } }) {
                                Text(label)
                            }
                        } else {
                            OutlinedButton(onClick = { Features.update { it.copy(topMenuTimeoutSeconds = seconds) } }) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 4.dp)) {
                val sideEnabled = ControlsSidePolicy.sideSettingEnabled(
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                )
                Text("Video controls side", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (sideEnabled) "Where the floating video controls sit in portrait, on your phone and Android Auto."
                    else "In landscape the video controls are always at the top centre. Rotate to portrait to change the side.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val sides = listOf(true, false)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sides.forEachIndexed { i, left ->
                        SegmentedButton(
                            selected = f.controlsOnLeft == left,
                            onClick = { Features.update { it.copy(controlsOnLeft = left) } },
                            enabled = sideEnabled,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = sides.size),
                        ) { Text(ControlsSidePolicy.label(left)) }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp)) {
                Text("Text size", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Bigger text is easier to read on a car screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val zooms = listOf(80, 90, 100, 115, 130)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    zooms.forEachIndexed { i, z ->
                        SegmentedButton(
                            selected = f.pageZoom == z,
                            onClick = { Features.update { it.copy(pageZoom = z) } },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = zooms.size),
                        ) { Text("$z%") }
                    }
                }
            }
        }
        // Account action is shown only after YouTube's own configuration confirms login.
        // Wrap the controls on narrow screens rather than clipping a third button.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Reload", Icons.Filled.Refresh) { WebHost.reload() }
            AppButton("Clear cache", Icons.Filled.DeleteSweep) { WebHost.clearCache() }
            if (account == YouTubeAccountState.SIGNED_IN) {
                AppButton("Sign out", Icons.AutoMirrored.Filled.Logout) { WebHost.clearSession() }
            }
        }
        Text(
            "Ad blocking, dislikes and background play rely on how YouTube's website works today, and may need an update when it changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------- experimental video

/**
 * This switch is deliberately separate from Updater's experimental APK channel.
 * It changes only the legacy Android Auto video window, never phone playback
 * or the native audio/media session. It takes effect on the next car connection.
 */
@Composable
fun ExperimentalVideoPage() {
    val flags by Features.state.collectAsState()
    val diagnostics by ExperimentalCarVideoMonitor.state.collectAsState()
    val virtualProbe by CarVirtualDisplayProbe.state.collectAsState()
    val carWindowProbe by CarWindowPixelProbe.state.collectAsState()
    val carPattern by CarVideoPatternProbe.state.collectAsState()
    val directSurface by CarDirectSurfaceProbe.state.collectAsState()
    val videoSnapshot by WebHost.snapshot.collectAsState()
    val context = LocalContext.current
    val renderEvidence = CarRenderEvidence(
        active = diagnostics.active,
        experimental = diagnostics.enabled,
        windowBuffer = if (carPattern.owner == diagnostics.owner)
            carPattern.windowPatternDetected else null,
        windowSeen = if (carPattern.owner == diagnostics.owner)
            carPattern.observedVisible else null,
        surfaceBuffer = if (directSurface.owner == diagnostics.owner)
            directSurface.surfacePatternDetected else null,
        surfaceSeen = if (directSurface.owner == diagnostics.owner)
            directSurface.observedVisible else null,
        surfaceFrames = if (directSurface.owner == diagnostics.owner)
            directSurface.frameCount else 0,
        frameHealth = diagnostics.frameHealth,
        checks = diagnostics.checks,
        repairs = diagnostics.repairs,
        frameSize = diagnostics.frameSize,
        playerSize = diagnostics.playerSize,
    )
    val renderAssessment = CarRenderCorrelationPolicy.assess(renderEvidence)
    DisposableEffect(Unit) {
        onDispose { CarVirtualDisplayProbe.cancel() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            ListItem(
                headlineContent = { Text("Experimental video") },
                supportingContent = {
                    Text(
                        "Android Auto only. Tries bounded, non-destructive surface recovery " +
                            "when the projected video view is missing or has invalid geometry. " +
                            "Reconnect Android Auto after changing this switch."
                    )
                },
                leadingContent = { Icon(Icons.Filled.OndemandVideo, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = flags.experimentalVideo,
                        onCheckedChange = { enabled ->
                            Features.update { it.copy(experimentalVideo = enabled) }
                            AppLog.add("CAR", "Experimental video preference=" + enabled +
                                "; takes effect on next car video connection")
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        CarVideoViewportPreview()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Synthetic virtual-display test",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "Checks whether Android can draw a test pattern into a private " +
                        "virtual display, without launching YouTube or using Shizuku. " +
                        "This is not Android Auto video rendering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(virtualProbe.message, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { CarVirtualDisplayProbe.start(context) },
                    enabled = flags.experimentalVideo && !diagnostics.active && !virtualProbe.running,
                ) { Text(if (virtualProbe.running) "Checking…" else "Test virtual display") }
                if (diagnostics.active) Text(
                    "Disconnect Android Auto video before running this isolated test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Car video diagnostics", style = MaterialTheme.typography.titleMedium)
                Text(
                    diagnostics.state,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Connection: " + if (diagnostics.active) "active" else "inactive",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Mode: " + if (diagnostics.enabled) "experimental" else "standard",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Checks: ${diagnostics.checks}  ·  Repairs: ${diagnostics.repairs}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Display ID: ${diagnostics.displayId}  ·  Frame: ${diagnostics.frameSize}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "WebView: ${diagnostics.playerSize}  ·  Visible: ${diagnostics.visibleArea}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Expected 16:9 region: ${diagnostics.referenceVideoArea}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Decoder: ${diagnostics.decoderSize}  ·  Decoded: ${diagnostics.decodedFrames}  ·  Dropped: ${diagnostics.droppedFrames}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Frame health: ${diagnostics.frameHealth}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Decoder paused: ${diagnostics.decoderPaused ?: "unknown"}. " +
                        "Frame health is diagnostic only and does not prove a visible car image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnostics.active) {
                    OutlinedButton(onClick = {
                        WebHost.logCarRenderProbe()
                        AppLog.add("CAR", "User requested manual render probe")
                    }, enabled = diagnostics.enabled) {
                        Text("Check decoder health")
                    }
                    Text(
                        "Car display visual test",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        carPattern.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { CarVideoPatternProbe.request() },
                        enabled = diagnostics.enabled && carPattern.available &&
                            !carPattern.visible && !videoSnapshot.playing &&
                            !carWindowProbe.running && !directSurface.visible,
                    ) { Text(if (carPattern.visible) "Showing test bars…" else "Show test bars on car") }
                    Text(
                        "Window readback: " + carPattern.windowReadback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (videoSnapshot.playing) Text(
                        "Pause playback before showing the short test image.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (carPattern.awaitingFeedback) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { CarVideoPatternProbe.reportSeen(true) }) {
                                Text("Visible")
                            }
                            OutlinedButton(onClick = { CarVideoPatternProbe.reportSeen(false) }) {
                                Text("Not visible")
                            }
                        }
                    }
                    Text(
                        "Test bars appear for 3 seconds in the existing car window. " +
                            "A tiny in-memory window readback compares known colors only. " +
                            "Neither test proves what the physical head unit displays.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Direct Surface rendering test",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Renders an animated synthetic pattern through a separate " +
                            "SurfaceView layer for five seconds, without creating " +
                            "another video player or changing audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        directSurface.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { CarDirectSurfaceProbe.request() },
                        enabled = diagnostics.enabled && directSurface.available &&
                            !directSurface.visible && !videoSnapshot.playing &&
                            !carPattern.visible && !carWindowProbe.running,
                    ) {
                        Text(if (directSurface.visible)
                            "Rendering moving pattern…" else "Test direct surface")
                    }
                    Text(
                        "Submitted: ${directSurface.frameCount} frames · " +
                            "~${directSurface.estimatedFps} fps · " +
                            "max gap ${directSurface.maxFrameGapMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        directSurface.readback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (directSurface.awaitingFeedback) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                CarDirectSurfaceProbe.reportSeen(true)
                            }) { Text("Moving image visible") }
                            OutlinedButton(onClick = {
                                CarDirectSurfaceProbe.reportSeen(false)
                            }) { Text("Not visible") }
                        }
                    }
                    Text(
                        "This checks SurfaceView composition, not YouTube video " +
                            "or physical-screen frames. Test only while parked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Car-window pixels: " + carWindowProbe.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (carWindowProbe.darkPercent != null) {
                        Text(
                            "Dark pixels: ${carWindowProbe.darkPercent}% · " +
                                "Brightness range: ${carWindowProbe.brightnessRange ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        onClick = { CarWindowPixelProbe.request() },
                        enabled = diagnostics.enabled && carWindowProbe.available &&
                            !carWindowProbe.running && !carPattern.visible &&
                            !directSurface.visible,
                    ) {
                        Text(if (carWindowProbe.running) "Checking…" else "Check car-window pixels")
                    }
                    Text(
                        "This manual test reads only a tiny, temporary car-window sample. " +
                            "No image is saved. Dark pixels may be normal video, and a " +
                            "successful sample does not prove the head unit displays it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (diagnostics.active && diagnostics.enabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Rendering-path assessment",
                        style = MaterialTheme.typography.titleMedium)
                    Text(renderAssessment.summary,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(renderAssessment.nextStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(
                            "AAYouTube car rendering diagnostics",
                            CarRenderCorrelationPolicy.report(renderEvidence),
                        ))
                        AppLog.add("CAR", "User copied sanitized renderer correlation report")
                    }) { Text("Copy rendering report") }
                    Text(
                        "Report contains aggregate diagnostic signals only—" +
                            "no URLs, video identifiers or screenshots.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "Standard projection is always the fallback. This experiment adds " +
                "bounded surface recovery and diagnostics, not AnyAuto's virtual-display " +
                "or Shizuku backend. Music and steering-wheel controls are unchanged. " +
                "Detailed CAR messages are available in Android logcat (AAYouTube-CAR).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------- update

@Composable
fun UpdatePage() {
    val experimental by LiveUpdater.experimentalEnabled.collectAsState()
    // The single shared app header is owned by AppRoot; the library renders only page content.
    dev.n2k02.updater.N2K02UpdatePage(
        controller = LiveUpdater.controller,
        installGuard = LiveUpdater.installGuard,
        modifier = Modifier.fillMaxSize(),
        experimentalEnabled = experimental,
        onExperimentalEnabledChange = LiveUpdater::setExperimentalEnabled,
    )
}

// ---------------------------------------------------------------------------- info

@Composable
fun InfoPage() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("About", style = MaterialTheme.typography.labelLarge)
                Text("AAYoutube v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(), style = MaterialTheme.typography.titleLarge)
                Text(
                    "A personal YouTube player for the phone and Android Auto, built on the YouTube website in a WebView. " +
                        "It blocks ads, skips sponsors (SponsorBlock), brings back dislikes, hides Shorts and keeps playing " +
                        "in the background and in picture in picture. In the car, on Android 15+, it shows the full screen with the video " +
                        "on the car display. The page runs in its own process, so a " +
                        "WebView problem can't freeze Android Auto. Updates come from GitHub releases (N2K02/AAYoutube).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }


        // What the app runs on: device, WebView and library versions.
        val groups = remember { Versions.collect(context) }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Under the hood", style = MaterialTheme.typography.titleMedium)
                groups.forEach { g ->
                    Spacer(Modifier.height(6.dp))
                    Text(g.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    g.rows.forEach { r ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                r.value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

    }
}

/** The one button style used everywhere in the app: filled, icon then label (like "Check again"). */
@Composable
fun AppButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(text, maxLines = 1, softWrap = false)
    }
}
