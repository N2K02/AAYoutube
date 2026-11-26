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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    // (Ordinal 6 was the removed Experimental video page; a saved tab there opens this.)
    NativeAudio("Native audio engine", Icons.Filled.GraphicEq),
    InstallationHelp("Android Auto installation", Icons.Filled.InstallMobile),
    SleepTimer("Sleep timer", Icons.Filled.Timer),
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sharedLinkOpened by activity.sharedLinkOpened
    LaunchedEffect(sharedLinkOpened) {
        if (sharedLinkOpened) {
            tab = Tab.Watch.ordinal
            activity.sharedLinkOpened.value = false
        }
    }
    // Keep the departing settings panel mounted until its fade-out completes.
    var lastPanel by remember { mutableStateOf(Tab.Settings) }
    val phoneDisplay = (activity.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY) ==
        android.view.Display.DEFAULT_DISPLAY
    var showInstallHelp by remember {
        mutableStateOf(phoneDisplay && AndroidAutoInstallHelp.shouldPrompt(context, BuildConfig.VERSION_CODE))
    }
    if (showInstallHelp) {
        AndroidAutoFirstLaunchHelp(
            onDismiss = { hide ->
                AndroidAutoInstallHelp.acknowledge(context, BuildConfig.VERSION_CODE, hide)
                showInstallHelp = false
            },
            onOpenGuide = { hide ->
                AndroidAutoInstallHelp.acknowledge(context, BuildConfig.VERSION_CODE, hide)
                showInstallHelp = false
                tab = Tab.InstallationHelp.ordinal
            },
        )
    }
    LaunchedEffect(tab) { if (tab != Tab.Watch.ordinal) lastPanel = Tab.entries[tab] }
    val lyricsEnabled by LyricsPrefs.enabledState.collectAsState()
    val lyricsShown by LyricsPrefs.shownState.collectAsState()
    val providerVersion by LyricsPrefs.providersVersion.collectAsState()
    val fullscreenView by WebHost.fullscreenView.collectAsState()
    val rendererPip by WebHost.inPip.collectAsState()
    val inPip = rendererPip || activity.previewPip.value
    val onCar by WebHost.carScreen.collectAsState()
    val projectedToCar by WebHost.carProjectionActive.collectAsState()
    val audioRetained by WebHost.carAudioRetained.collectAsState()
    // While Android Auto projects the video, YouTube's fullscreen view belongs
    // to the car window. A still-composed phone screen must not reparent it
    // (car turns black) or rotate the phone for it.
    val fullscreen = fullscreenView.takeIf { !projectedToCar || onCar }
    // Position reports arrive every ~1.4 s while playing: the app screen recomposes only when
    // something else changes (play state, video, buffering...). Position readers collect it themselves.
    val snapFlow = remember { WebHost.snapshot.map { it.withoutPosition() }.distinctUntilChanged() }
    val snap by snapFlow.collectAsState(WebHost.snapshot.value.withoutPosition())
    val sharedPlayerMode by WebHost.mode.collectAsState()
    val localPhoneMode by BrowseHost.phoneMode.collectAsState()
    val playerMode = if ((projectedToCar || audioRetained) && !onCar) localPhoneMode else sharedPlayerMode
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
    val headerAllowed = showAAYoutubeHeader(watchTab, onCar, inPip, fullscreen != null || landFill, false)
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
    val homeHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val goYouTubeHome: () -> Unit = {
        tab = Tab.Watch.ordinal
        if ((projectedToCar || audioRetained) && !onCar) {
            BrowseHost.phoneHome()
        } else when (PlayerNavigationPolicy.home(
            fullPlayer = WebHost.mode.value == WebHost.PlayerMode.FULL,
            miniPlayer = WebHost.mode.value == WebHost.PlayerMode.MINI,
            onWatchPage = WebHost.isOnWatchPage(),
            fullscreen = WebHost.fullscreenView.value != null,
        )) {
            // The mini player cannot open over Chromium's fullscreen view: leave it first.
            PlayerNavigationPolicy.Home.ExitFullscreenThenMini -> {
                WebHost.exitFullscreen()
                homeHandler.post { WebHost.enterMini() }
            }
            // On the phone while AA is active, the WebView is on the car screen — go to background.
            PlayerNavigationPolicy.Home.Mini ->
                if (projectedToCar && !onCar) activity.moveTaskToBack(true)
                else WebHost.enterMini()
            PlayerNavigationPolicy.Home.BrowseHome -> BrowseHost.navigate(WebHost.home())
            PlayerNavigationPolicy.Home.OpenHome -> WebHost.openUrl(WebHost.home())
        }
    }
    val goBack: () -> Unit = {
        if ((projectedToCar || audioRetained) && !onCar && tab == Tab.Watch.ordinal) {
            if (!PhoneMirror.exitFullscreen(activity)) when (ProjectedPhoneNav.back(BrowseHost.phoneMode.value, BrowseHost.phone.canGoBack())) {
                ProjectedPhoneNav.Back.Minimize ->
                    if (snap.id.isNotBlank()) BrowseHost.phoneMini() else BrowseHost.phoneHome()
                ProjectedPhoneNav.Back.BrowseHistory -> BrowseHost.phone.goBack()
                // At YouTube Home already: leave the app (the car keeps playing).
                ProjectedPhoneNav.Back.Home ->
                    if (!BrowseHost.phone.isAt(WebHost.home())) BrowseHost.phoneHome()
                    else activity.moveTaskToBack(true)
            }
        } else when (PlayerNavigationPolicy.back(
            fullscreen = fullscreen != null,
            landscapeFill = WebHost.landscapeFill.value,
            onSettingsTab = tab == Tab.Settings.ordinal,
            onWatchTab = tab == Tab.Watch.ordinal,
            fullPlayer = WebHost.mode.value == WebHost.PlayerMode.FULL,
            onWatchPage = WebHost.isOnWatchPage(),
        )) {
            PlayerNavigationPolicy.Back.ExitFullscreen -> WebHost.exitFullscreen()
            // Landscape video: Back first shows the watch page (player + playlist).
            PlayerNavigationPolicy.Back.ShowWatchPage -> WebHost.dismissLandscapeFill()
            PlayerNavigationPolicy.Back.ToWatchTab -> tab = Tab.Watch.ordinal
            PlayerNavigationPolicy.Back.ToSettingsTab -> tab = Tab.Settings.ordinal
            // Browsing under the mini player (or after closing it). On the car screen stay put.
            PlayerNavigationPolicy.Back.BrowseBack ->
                if (!BrowseHost.goBack() && !WebHost.carDisplay) activity.moveTaskToBack(true)
            // Playing or paused on the phone: shrink to mini. But when the WebView is on
            // the car's VirtualDisplay the phone has nothing to mini-ize — go to background.
            PlayerNavigationPolicy.Back.Mini ->
                if (projectedToCar && !onCar) activity.moveTaskToBack(true)
                else WebHost.enterMini()
            PlayerNavigationPolicy.Back.PageBack ->
                if (!WebHost.goBack() && !WebHost.carDisplay) activity.moveTaskToBack(true)
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
            WebPane(Modifier.fillMaxSize(), previewVisible = watchTab)
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
                        ) { page -> SettingsPageContent(page) { tab = it.ordinal } }
                    }
                }
            }
        }
        // App-owned watch header overlays the WebView without relayout.
        // Android Auto's separate native host bars remain host-controlled.
        val phoneLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val screenCfg = LocalConfiguration.current
        val screenAlongPx = with(LocalDensity.current) {
            (if (phoneLandscape) screenCfg.screenWidthDp else screenCfg.screenHeightDp).dp.toPx()
        }
        val (railAlign, railDrag) = rememberEdgeDrag(
            left = flags.controlsOnLeft, landscape = phoneLandscape,
            containerPx = screenAlongPx, onActivity = { topMenuEpoch++ },
        )
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
                modifier = Modifier.align(railAlign)
                    .then(
                        if (phoneLandscape) Modifier.statusBarsPadding().padding(top = 8.dp)
                        else Modifier.padding(horizontal = 8.dp)
                    ).wrapContentSize().zIndex(20f),
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
                    modifier = railDrag,
                    availableWidth = if (phoneLandscape) screenCfg.screenWidthDp.dp else null,
                    extraCount = if (VideoFitPolicy.buttonShown(filled = landFill, playing = snap.playing)) 4 else 3,
                ) { size ->
                    BarIcon(
                        Icons.Filled.MusicNote,
                        if (canLyrics) { if (lyricsShown) "Hide lyrics" else "Show lyrics" } else "Lyrics unavailable",
                        size,
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
                    if (VideoFitPolicy.buttonShown(filled = landFill, playing = snap.playing))
                    BarIcon(Icons.Filled.AspectRatio, VideoFitPolicy.label(flags.videoFit), size,
                        onClick = {
                            Features.update { it.copy(videoFit = VideoFitPolicy.next(it.videoFit)) }
                            topMenuEpoch++
                        },
                        tint = if (VideoFitPolicy.normalize(flags.videoFit) != "fit") MaterialTheme.colorScheme.primary else null)
                    Box(Modifier.size(size.button), contentAlignment = Alignment.Center) {
                        BarIcon(Icons.Filled.Settings, "Settings", size,
                            onClick = { tab = Tab.Settings.ordinal; topMenuEpoch++ })
                        // A text badge can cover a compact gear. Keep its indicator
                        // inside the fixed Settings slot without obscuring the icon.
                        if (phoneLandscape) {
                            if (updateStatus.hasPendingUpdate()) {
                                androidx.compose.material3.Badge(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                )
                            }
                        } else {
                            dev.n2k02.updater.N2K02UpdateBadge(
                                updateStatus,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                    // Quit stops playback, services and the WebViews, then closes AAYoutube.
                    BarIcon(Icons.Rounded.PowerSettingsNew, "Quit AAYoutube", size,
                        onClick = { activity.quit() })
                }
            }
        }
        if (lyricsOnScreen) {
            // Reduce overlap while the menu is hidden; no second app header.
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = 30.dp)) {
                key(lyricsRequest) { LiveLyricsOverlay(foundLyrics) {
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
        // In-app only; no notification action or system overlay permission.
        if (phoneDisplay && !inPip && fullscreen == null) {
            SleepTimerChip(onClick = { tab = Tab.SleepTimer.ordinal })
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
fun WebPane(modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp, previewVisible: Boolean = true) {
    val version by WebHost.webVersion.collectAsState()
    val progress by WebHost.progress.collectAsState()
    val offline by WebHost.offline.collectAsState()
    val mode by WebHost.mode.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    // Position-free: play state, video and buffering recompose the pane; position reports do not
    // (only LiveProgressLine reads them).
    val snapFlow = remember { WebHost.snapshot.map { it.withoutPosition() }.distinctUntilChanged() }
    val snap by snapFlow.collectAsState(WebHost.snapshot.value.withoutPosition())
    val browseOn by BrowseHost.created.collectAsState()
    val browseRevision by BrowseHost.revision.collectAsState()
    val onCar by WebHost.carScreen.collectAsState()
    val projectedToCar by WebHost.carProjectionActive.collectAsState()
    val audioRetained by WebHost.carAudioRetained.collectAsState()
    if ((projectedToCar || audioRetained) && !onCar) {
        ProjectedPhonePane(snap, previewVisible, modifier)
        return
    }
    // Picture in picture always shows the video full size, whatever the mode.
    val small = mode == WebHost.PlayerMode.MINI && !inPip
    val hidden = mode == WebHost.PlayerMode.HIDDEN && !inPip

    // The browse page only works while you can see it.
    LaunchedEffect(mode, inPip, projectedToCar, onCar) {
        if (projectedToCar && !onCar) return@LaunchedEffect
        if (mode == WebHost.PlayerMode.FULL || inPip) BrowseHost.pause() else BrowseHost.resume()
    }

    // Keep the WebPane host's dimensions stable across Settings transitions.
    // Inset only the phone WebView content, never Android Auto, PiP or native fullscreen.
    val landFill by WebHost.landscapeFill.collectAsState()
    val phoneSafeArea = !onCar && !projectedToCar && !inPip && !landFill &&
        WebHost.fullscreenView.collectAsState().value == null
    Box(modifier.then(if (phoneSafeArea) Modifier.statusBarsPadding() else Modifier)) {
        // Browse page (behind): made the first time the mini player opens.
        if (browseOn && !inPip && (!projectedToCar || onCar)) {
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
        // Window position of this box, for capturing the video frame during transitions.
        var hostOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
            hostOrigin = it.boundsInWindow().topLeft
        }) {
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

            // Full <-> mini transition: the WebView follows `layoutSmall` (resized once);
            // a captured frame animates between the old and new bounds above it.
            var layoutSmall by remember { mutableStateOf(small) }
            var ghost by remember { mutableStateOf<Triple<androidx.compose.ui.graphics.ImageBitmap?, PxRect, PxRect>?>(null) }
            var ghostToMini by remember { mutableStateOf(false) }
            val ghostMove = remember { Animatable(1f) }
            val ghostAlpha = remember { Animatable(0f) }
            // Only the video area travels (portrait watch page: the 16:9 band at the top),
            // not the whole page: smaller capture, and it looks like the YouTube app.
            val fullRect = PlayerTransitionPolicy.videoBand(
                constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val miniRect = PxRect(px, py, wPx, hPx)
            LaunchedEffect(small) {
                if (small == layoutSmall) return@LaunchedEffect
                if (!PlayerTransitionPolicy.animates(inPip, onCar, projectedToCar, hidden)) {
                    layoutSmall = small
                    return@LaunchedEffect
                }
                val from = if (layoutSmall) miniRect else fullRect
                val to = if (small) miniRect else fullRect
                val window = context.findActivity()?.window
                val frame = window?.let {
                    captureWindowRegion(it, android.graphics.Rect(
                        (hostOrigin.x + from.left).roundToInt(), (hostOrigin.y + from.top).roundToInt(),
                        (hostOrigin.x + from.left + from.width).roundToInt(),
                        (hostOrigin.y + from.top + from.height).roundToInt()))
                }
                if (frame == null) {
                    // Capture failed (protected video, timeout): never animate a black box;
                    // the plain scrim fade below handles this transition instead.
                    layoutSmall = small
                    return@LaunchedEffect
                }
                ghostToMini = small
                ghost = Triple(frame.asImageBitmap(), from, to)
                ghostMove.snapTo(0f)
                ghostAlpha.snapTo(1f)
                layoutSmall = small // the single WebView resize happens under the ghost
                try {
                    ghostMove.animateTo(1f, tween(PlayerTransitionPolicy.MOVE_MS,
                        easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    ghostAlpha.animateTo(0f, tween(PlayerTransitionPolicy.FADE_MS))
                } finally {
                    ghost = null
                    // Compose may still draw the old frame during state disposal.
                    // Recycle after the transition has left the render pipeline.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!frame.isRecycled) frame.recycle()
                    }, 250L)
                }
            }

            // Resize the Android WebView itself. Scaling a graphicsLayer changes
            // the Compose container, but can leave Chromium's video Surface
            // at the old fullscreen dimensions (grey frames/letterboxing).
            val playerMod = when {
                inPip -> Modifier.fillMaxSize()
                hidden -> Modifier.size(1.dp)
                layoutSmall -> Modifier.offset {
                    IntOffset(px.roundToInt(), py.roundToInt())
                }.size(miniW, miniH) // Do not clip a decoder-backed SurfaceView: some devices paint black.
                else -> Modifier.fillMaxSize()
            }.then(if (!layoutSmall && !inPip && onCar) carSwipeDown else Modifier)
            // Smooth mode changes (mini, expand, landscape fill): a scrim over the video
            // fades out. The WebView itself is never made translucent (that drops Chromium
            // off its video overlay path: more GPU, possible black frames) and is resized
            // once, never per frame (that stalls the decoder).
            val scrim = remember { Animatable(0f) }
            var fadeArmed by remember { mutableStateOf(false) }
            LaunchedEffect(layoutSmall, hidden, landFill) {
                if (!fadeArmed) { fadeArmed = true; return@LaunchedEffect }
                if (inPip || onCar || projectedToCar) return@LaunchedEffect
                if (ghost != null) return@LaunchedEffect // the frame animation covers it
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
            } else {
                // A projection may begin between compositions: never steal the car's player.
                Box(Modifier.fillMaxSize().background(Color.Black))
            }

            if (scrim.value > 0f && !onCar) Box(
                playerMod.graphicsLayer { alpha = scrim.value }.background(Color.Black),
            )

            ghost?.let { (frame, from, to) ->
                val r = PlayerTransitionPolicy.lerp(from, to, ghostMove.value)
                val corner = PlayerTransitionPolicy.cornerDp(ghostToMini, ghostMove.value).dp
                Box(
                    Modifier
                        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                        .graphicsLayer { alpha = ghostAlpha.value }
                        .clip(RoundedCornerShape(corner))
                        .background(Color.Black),
                ) {
                    if (frame != null) androidx.compose.foundation.Image(
                        frame, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }

            // Mini player controls (like the YouTube app): play/pause top left, close top right,
            // red progress line at the bottom. Tap to open it again, drag to move it.
            if (layoutSmall) {
                // Progress: the page reports now and then; LiveProgressLine moves it along in between.
                // Tick only while the screen is shown: no wake-ups behind a stopped activity.
                val paneLifecycle = context.findActivity()?.lifecycle?.currentStateFlow?.collectAsState()?.value
                val paneShown = paneLifecycle?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true

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
                    LiveProgressLine(paneShown, Modifier.align(Alignment.BottomStart))
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

/** One Settings page, shared by the phone overlay and the Android Auto panel. */
@Composable
private fun SettingsPageContent(page: Tab, onSelect: (Tab) -> Unit) {
    when (page) {
        Tab.Watch -> Unit
        Tab.Features -> FeaturesPage()
        Tab.NativeAudio -> NativeAudioPage()
        Tab.InstallationHelp -> AndroidAutoInstallationPage()
        Tab.SleepTimer -> SleepTimerPage()
        Tab.Lyrics -> LyricsSettingsPage()
        Tab.Update -> UpdatePage()
        Tab.Info -> InfoPage()
        Tab.Settings -> AAYoutubeSettingsMenu(onSelect = onSelect)
    }
}

/** Settings on the Android Auto screen; Back walks subpage -> menu -> closed. */
internal class SettingsPanelState {
    var open by mutableStateOf(false)
        private set
    internal var page by mutableIntStateOf(Tab.Settings.ordinal)
    fun openMenu() { page = Tab.Settings.ordinal; open = true }
    fun close() { open = false; page = Tab.Settings.ordinal }
    /** True when Back was consumed by the panel. */
    fun back(): Boolean {
        if (!open) return false
        if (page != Tab.Settings.ordinal) page = Tab.Settings.ordinal else open = false
        return true
    }
}

/** The phone's Settings menu and pages, on the car screen (same layout, no second header). */
@Composable
internal fun CarSettingsPanel(state: SettingsPanelState, modifier: Modifier = Modifier) {
    val page = Tab.entries.getOrElse(state.page) { Tab.Settings }
    Surface(modifier.fillMaxSize().pointerInput(Unit) { }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (page == Tab.Settings) "Close settings" else "Back to settings")
                }
                Box(Modifier.weight(1f)) {
                    AAYoutubePageHeader(page, lyricsAvailable = false, lyricsShown = false, onLyrics = {})
                }
            }
            Crossfade(targetState = page, animationSpec = tween(160), modifier = Modifier.weight(1f),
                label = "Car settings navigation") { current ->
                SettingsPageContent(current) { state.page = it.ordinal }
            }
        }
    }
}

/** Secondary pages live under Settings, like mRadio; the WebView keeps running underneath. */
@Composable
private fun AAYoutubeSettingsMenu(
    onSelect: (Tab) -> Unit,
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
            listOf(Tab.Features, Tab.SleepTimer, Tab.NativeAudio, Tab.InstallationHelp, Tab.Lyrics, Tab.Info, Tab.Update).forEach { destination ->
                if (destination == Tab.Update) HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(if (destination == Tab.Update) "About & updates" else destination.label)
                    },
                    supportingContent = {
                        Text(when (destination) {
                            Tab.Features -> "Playback, video and display preferences"
                            Tab.NativeAudio -> "Experimental Media3 audio with hardware offload"
                            Tab.InstallationHelp -> "Video not showing? Installation methods and updates"
                            Tab.SleepTimer -> "Pause playback after a set number of minutes or hours"
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


    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card() {
            Toggle("Block ads", "Strips ads from video data and skips what remains.", f.blockAds) { s, v -> s.copy(blockAds = v) }
            Toggle("Hide Shorts", "Removes Shorts shelves; Shorts links open as normal videos.", f.hideShorts) { s, v -> s.copy(hideShorts = v) }
            Toggle("Hide promos", "Removes banners that ask you to install or upgrade.", f.hidePromos) { s, v -> s.copy(hidePromos = v) }
            Toggle("Background play", "Prevents YouTube pausing when the screen is hidden. Android Auto playback also uses the media service.", f.backgroundPlay) { s, v -> s.copy(backgroundPlay = v) }
            Toggle("Save battery in background", "With the app hidden for a minute, the unseen video drops to 144p while the audio keeps playing. Your quality returns as soon as you open the app. Never in PiP, Android Auto, ads or live streams; turns itself off if audio stalls.", f.lowQualityInBackground, enabled = f.backgroundPlay) { s, v -> s.copy(lowQualityInBackground = v) }
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
            Toggle("Loop video", "Repeats the current video instead of playing the next one.", f.loopVideo) { s, v -> s.copy(loopVideo = v) }
            Toggle("Skip buttons seek 10 seconds", "Next and Previous on Android Auto's player, the notification and headsets jump 10 seconds inside the video instead of changing video.", f.skipSeeks) { s, v -> s.copy(skipSeeks = v) }
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

// ---------------------------------------------------------------------------- native audio

/** Settings → Native audio engine: the experimental Media3 engine, off by default. */
@Composable
private fun NativeAudioPage() {
    val flags by Features.state.collectAsState()
    var endpoint by rememberSaveable(flags.nativeAudioProviderUrl) { mutableStateOf(flags.nativeAudioProviderUrl) }
    val validEndpoint = AuthorizedAudioProvider.normalizeEndpoint(endpoint)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            ListItem(
                headlineContent = { Text("Native audio engine") },
                supportingContent = {
                    Text(
                        "Experimental. Plays audio with Android's media player instead of the web " +
                            "player, without downloading video, and uses hardware audio offload where " +
                            "supported. Needs an authorized stream source; without one the web player " +
                            "keeps playing."
                    )
                },
                leadingContent = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = flags.nativeAudio,
                        onCheckedChange = { enabled ->
                            Features.update { it.copy(nativeAudio = enabled) }
                            if (!enabled) NativePlayback.returnToWeb("feature-off")
                            AppLog.add("NATIVE-AUDIO", "preference=$enabled provider=${StreamProviders.current.id}")
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        Card {
            ListItem(
                headlineContent = { Text("Stream source") },
                supportingContent = {
                    Text(
                        if (flags.nativeAudioProviderUrl.isBlank()) "No source configured. Web playback remains available."
                        else "Authorized HTTPS resolver configured."
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("Authorized HTTPS resolver URL") }, singleLine = true, isError = endpoint.isNotBlank() && validEndpoint == null, modifier = Modifier.fillMaxWidth())
                Text("Use only a trusted source authorized for direct playback. The resolver receives the video ID, never YouTube login cookies.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = validEndpoint != null && validEndpoint != flags.nativeAudioProviderUrl, onClick = { Features.update { it.copy(nativeAudioProviderUrl = validEndpoint.orEmpty()) }; NativePlayback.onVisibilityChanged() }) { Text("Save") }
                    OutlinedButton(onClick = { endpoint = ""; Features.update { it.copy(nativeAudioProviderUrl = "") }; NativePlayback.onVisibilityChanged() }) { Text("Clear") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- update

@Composable
fun UpdatePage() {
    val experimental by LiveUpdater.experimentalEnabled.collectAsState()
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

/**
 * The phone while Android Auto owns (or retains) the single audible engine:
 * the original YouTube player (PhoneMirror: muted, clock-synced, decodes only
 * while shown) with the same Full / mini-player / PiP modes as without the car.
 * Browsing is independent; every control drives the one shared engine.
 *  - FULL: YouTube's own watch page and player.
 *  - MINI: the browse page with the floating, draggable mini-player above it.
 *  - HIDDEN: browse page only (the mirror stops decoding).
 */
@Composable
private fun ProjectedPhonePane(snap: Snap, visible: Boolean, modifier: Modifier) {
    val phoneMode by BrowseHost.phoneMode.collectAsState()
    val revision by BrowseHost.phone.revision.collectAsState()
    val mirrorRevision by PhoneMirror.revision.collectAsState()
    val playerFailed by PhoneMirror.failed.collectAsState()
    val mirrorSuspended by PhoneMirror.suspended.collectAsState()
    // One fallback for every state in which the phone cannot show the copy (unsupported, crashed, suspended).
    val muteUnavailable by PhoneMirror.muteUnavailable.collectAsState()
    // A runtime mute failure may be temporary, so it is a retryable failure, not "unsupported".
    val mirrorCrashed = playerFailed || muteUnavailable
    val mirrorSupported = PhoneMirror.supported
    val fallback = PhoneMirrorPolicy.fallback(mirrorSupported, mirrorCrashed, mirrorSuspended)
    val mirrorBlocked = fallback != PhoneMirrorPolicy.Fallback.NONE
    val activity = LocalContext.current.findActivity()
    val pip = activity?.previewPip?.value == true
    val lifecycle = activity?.lifecycle?.currentStateFlow?.collectAsState()?.value
    val started = lifecycle?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true
    val resumed = lifecycle == androidx.lifecycle.Lifecycle.State.RESUMED
    val hasVideo = snap.id.isNotBlank()
    // Without the copy the phone keeps browsing (never a blank player) and shows the fallback controls.
    val browsing = (phoneMode != WebHost.PlayerMode.FULL || mirrorBlocked) && !pip
    val mirrorShown = hasVideo && !mirrorBlocked && (pip || phoneMode != WebHost.PlayerMode.HIDDEN)
    // Allocation follows visibility: nothing is composed (and so nothing is recreated) while hidden.
    val mirrorWanted = (pip && started) || (resumed && visible)
    val small = phoneMode == WebHost.PlayerMode.MINI && !pip
    LaunchedEffect(hasVideo) {
        val next = ProjectedPhoneNav.onVideoChanged(BrowseHost.phoneMode.value, hasVideo)
        if (next != BrowseHost.phoneMode.value) {
            if (next == WebHost.PlayerMode.HIDDEN) BrowseHost.phoneHome() else BrowseHost.phoneMini()
        }
    }
    LaunchedEffect(browsing, visible, resumed) {
        if (browsing && visible && resumed) BrowseHost.phone.resume() else BrowseHost.phone.pause()
    }
    // Decode on the phone only while its video is actually on screen (PiP counts).
    LaunchedEffect(hasVideo, mirrorCrashed, phoneMode, visible, started, resumed, pip, mirrorRevision, mirrorSupported) {
        PhoneMirror.setVisible(hasVideo && !mirrorCrashed && mirrorSupported &&
            (pip || phoneMode != WebHost.PlayerMode.HIDDEN) && mirrorWanted)
    }
    LaunchedEffect(small, pip, mirrorRevision) {
        PhoneMirror.setPip(pip)
        PhoneMirror.setVideoOnly(small || pip)
    }
    DisposableEffect(Unit) {
        onDispose {
            BrowseHost.phone.pause()
            // Projection ended: free the phone decoder and its renderer.
            PhoneMirror.release()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        if (browsing) {
            key(revision) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    factory = { ctx ->
                        FrameLayout(ctx).also { frame ->
                            frame.addView(BrowseHost.phone.attach(ctx), FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { BrowseHost.phone.detach(it) },
                )
            }
        }
        val showFallback = mirrorBlocked && hasVideo && (pip || phoneMode != WebHost.PlayerMode.HIDDEN)
        if (showFallback) MirrorFallback(snap, fallback, large = pip, Modifier.align(
            if (pip) Alignment.Center else Alignment.BottomCenter))
        if (mirrorShown && mirrorWanted) ProjectedMirrorPlayer(snap, small = small, pip = pip, visible = mirrorWanted,
            revision = mirrorRevision)
    }
}

/** The mirror WebView, resized (never re-parented) between full and mini. */
@Composable
private fun ProjectedMirrorPlayer(snap: Snap, small: Boolean, pip: Boolean, visible: Boolean, revision: Int) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aay_ui", Context.MODE_PRIVATE) }
    val density = LocalDensity.current
    var mx by remember { mutableStateOf(prefs.getFloat("miniX", 1f)) }
    var my by remember { mutableStateOf(prefs.getFloat("miniY", 1f)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val miniW = minOf(224.dp, (maxWidth - 24.dp) * 0.8f)
        val miniH = miniW * (9f / 16f)
        val edge = with(density) { 12.dp.toPx() }
        val ytMenu = with(density) { 64.dp.toPx() }
        val wPx = with(density) { miniW.toPx() }
        val hPx = with(density) { miniH.toPx() }
        val maxX = (constraints.maxWidth - wPx - edge).coerceAtLeast(edge)
        val maxY = (constraints.maxHeight - hPx - edge - ytMenu).coerceAtLeast(edge)
        val px = edge + (maxX - edge) * mx
        val py = edge + (maxY - edge) * my
        val frameMod = when {
            pip -> Modifier.fillMaxSize()
            small -> Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.size(miniW, miniH).zIndex(10f)
            else -> Modifier.fillMaxSize().statusBarsPadding()
        }
        key(revision) {
            AndroidView(
                modifier = frameMod,
                factory = { ctx ->
                    FrameLayout(ctx).also { frame ->
                        frame.addView(PhoneMirror.attach(ctx), FrameLayout.LayoutParams(-1, -1))
                    }
                },
                onRelease = { PhoneMirror.detach(it) },
            )
        }
        if (small) {
            // Touch layer like the YouTube app's mini-player: drag to move, tap to expand.
            Box(
                Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                    .size(miniW, miniH)
                    .zIndex(11f)
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDragEnd = { prefs.edit().putFloat("miniX", mx).putFloat("miniY", my).apply() },
                        ) { change, drag ->
                            change.consume()
                            mx = (mx + drag.x / (maxX - edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            my = (my + drag.y / (maxY - edge).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { BrowseHost.phoneMode.value = WebHost.PlayerMode.FULL })
                    },
            ) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { WebHost.cmd(if (snap.playing) "pause" else "play") },
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(36.dp),
                ) {
                    Icon(if (snap.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (snap.playing) "Pause" else "Play", modifier = Modifier.size(20.dp))
                }
                // Close hides the phone's mini-player only; the car keeps playing.
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { BrowseHost.phoneMode.value = WebHost.PlayerMode.HIDDEN },
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp),
                ) { Icon(Icons.Filled.Close, contentDescription = "Close phone mini player", modifier = Modifier.size(20.dp)) }
                LiveProgressLine(visible, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/**
 * Lightweight stand-in for the phone copy (unsupported WebView, crashed or heat-suspended renderer):
 * artwork, title and the transport controls of the one engine. Android Auto is untouched.
 */
@Composable
private fun MirrorFallback(snap: Snap, reason: PhoneMirrorPolicy.Fallback, large: Boolean, modifier: Modifier) {
    var tick by remember { mutableStateOf(0) }
    val loader = remember { MediaArtworkLoader { tick++ } }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    val url = MediaArtworkLoader.urlFor(snap.art, snap.id)
    LaunchedEffect(url) { loader.request(url) }
    val art = if (tick >= 0) loader.bitmapFor(url) else null
    val note = when (reason) {
        PhoneMirrorPolicy.Fallback.UNSUPPORTED -> "Phone video isn't available on this device"
        PhoneMirrorPolicy.Fallback.FAILED -> "Phone video stopped"
        PhoneMirrorPolicy.Fallback.SUSPENDED -> "Phone video paused to keep playback smooth"
        PhoneMirrorPolicy.Fallback.NONE -> ""
    }
    androidx.compose.material3.Surface(
        modifier.padding(12.dp).zIndex(12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (art != null) {
                androidx.compose.foundation.Image(art.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(if (large) 96.dp else 48.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(snap.title.ifBlank { "Playing on Android Auto" }, maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium)
                Text(note, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.IconButton(onClick = { WebHost.cmd("prev") }, enabled = snap.canPrev) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
            }
            androidx.compose.material3.IconButton(onClick = { WebHost.cmd(if (snap.playing) "pause" else "play") }) {
                Icon(if (snap.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (snap.playing) "Pause" else "Play")
            }
            androidx.compose.material3.IconButton(onClick = { WebHost.cmd("next") }, enabled = snap.canNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
            if (PhoneMirrorPolicy.canRetry(reason)) {
                androidx.compose.material3.TextButton(onClick = { PhoneMirror.retry() }) { Text("Retry") }
            }
        }
    }
}

/** Snapshot position advanced locally between reports; ticks only while visible. */
/**
 * Red progress line with its own 500 ms clock. The clock's state is read only inside this
 * small scope, so ticks never recompose the surrounding player or screen.
 */
@Composable
private fun LiveProgressLine(visible: Boolean, modifier: Modifier) {
    // The only reader of position reports on the player: its own collection, its own scope.
    val snap by WebHost.snapshot.collectAsState()
    val pos = rememberLivePosition(snap, visible)
    val fraction = if (snap.durMs > 0) (pos.toFloat() / snap.durMs).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth(fraction).height(3.dp).background(Color(0xFFFF0033)))
}

/** The lyrics overlay alone follows position reports; the screen around it does not. */
@Composable
private fun LiveLyricsOverlay(content: LyricsContent, onToggle: () -> Unit) {
    val live by WebHost.snapshot.collectAsState()
    LyricsOverlay(live, content, onToggle)
}

@Composable
private fun rememberLivePosition(snap: Snap, visible: Boolean): Long {
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    val seenAt = remember(snap) { android.os.SystemClock.elapsedRealtime() }
    // No clock while buffering: the position is not moving.
    LaunchedEffect(snap.playing, snap.buffering, snap.id, visible) {
        if (!snap.playing || snap.buffering || !visible) return@LaunchedEffect
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val pos = if (snap.playing && !snap.buffering) snap.posMs + ((now - seenAt).coerceAtLeast(0) * snap.rate).toLong()
        else snap.posMs
    return if (snap.durMs > 0) pos.coerceAtMost(snap.durMs) else pos
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
