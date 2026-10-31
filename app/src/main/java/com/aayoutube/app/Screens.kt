@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.aayoutube.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.graphics.TransformOrigin
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
    Logs("Logs", Icons.Rounded.Article),
    Update("Update", Icons.Filled.SystemUpdate),
    Info("About", Icons.Filled.Info),
    Settings("Settings", Icons.Filled.Settings),
}

/** One host-owned header, matching the mRadio icon/title + app icon/name layout. */
@Composable
private fun AAYoutubePageHeader(
    tab: Tab,
    lyricsAvailable: Boolean,
    lyricsShown: Boolean,
    onLyrics: () -> Unit,
    onSettings: () -> Unit,
) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
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
                // Keep the entire "New" label inside a reserved header area. A badge
                // attached directly to a compact icon can be clipped by the IconButton.
                Box(modifier = Modifier.size(width = 56.dp, height = 48.dp)) {
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.align(Alignment.CenterStart).size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    dev.n2k02.updater.N2K02UpdateBadge(
                        updateStatus,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
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
    val headerAllowed = showAAYoutubeHeader(watchTab, onCar, inPip, fullscreen != null)
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
    val lyricsKey = LyricsCache.key(snap)
    var foundLyrics by remember { mutableStateOf(LyricsContent()) }

    // Lookup begins for the current video regardless of overlay visibility. A hidden
    // overlay must never leave the top Lyrics button permanently disabled.
    LaunchedEffect(lyricsKey, lyricsEnabled, providerVersion, snap.ad, snap.live, snap.title, snap.artist) {
        foundLyrics = LyricsContent()
        if (!lyricsEnabled || snap.id.isBlank() || snap.title.isBlank() || snap.live || snap.ad)
            return@LaunchedEffect
        val cached = LyricsCache.get(lyricsKey, snap)
        val result = cached ?: LyricsProviders.fetch(context, snap).also {
            LyricsCache.put(lyricsKey, snap, it)
        }
        foundLyrics = LyricsContent(lyricsKey, result.first, result.second)
        AppLog.add("LYRICS", "Result for video ${snap.id}: timed=${result.first.size} plain=${!result.second.isNullOrBlank()}")
    }
    val lyricsAvailable = lyricsEnabled && !snap.live && !snap.ad &&
        snap.id.isNotBlank() && foundLyrics.foundFor(lyricsKey)
    val lyricsOnScreen = lyricsAvailable && lyricsShown &&
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
    val goBack: () -> Unit = {
        when {
            fullscreen != null -> WebHost.exitFullscreen()
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
            // Watching: back shrinks the video into the mini player, like the YouTube app.
            snap.playing && WebHost.isOnWatchPage() -> WebHost.enterMini()
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
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // Non-video pages keep the same single header. The watch
                // header is an overlay so auto-hide never resizes the WebView
                // or interrupts its hardware-decoded video surface.
                if (!watchTab && headerAllowed) {
                    AAYoutubePageHeader(
                        Tab.entries[tab],
                        lyricsAvailable = false,
                        lyricsShown = false,
                        onLyrics = {},
                        onSettings = { tab = Tab.Watch.ordinal },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                // The page stays in place under the other tabs, so playback carries on while you look at them.
                WebPane(Modifier.fillMaxSize())
                if (tab != 0 && !inPip) {
                    Surface(
                        Modifier.fillMaxSize().pointerInput(Unit) { },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when (Tab.entries[tab]) {
                            Tab.Watch -> Unit
                            Tab.Features -> FeaturesPage()
                            Tab.Lyrics -> LyricsSettingsPage()
                            Tab.Logs -> LogsPage()
                            Tab.Update -> UpdatePage()
                            Tab.Info -> InfoPage()
                            Tab.Settings -> AAYoutubeSettingsMenu(
                                onSelect = { tab = it.ordinal },
                                onHome = {
                                    tab = Tab.Watch.ordinal
                                    if (WebHost.mode.value == WebHost.PlayerMode.FULL && WebHost.isOnWatchPage())
                                        WebHost.enterMini()
                                    else if (WebHost.mode.value == WebHost.PlayerMode.MINI)
                                        BrowseHost.navigate(WebHost.home())
                                    else WebHost.openUrl(WebHost.home())
                                },
                                onBack = goBack,
                                onQuit = { activity.quit() },
                            )
                        }
                    }
                }
            }
        }
        // App-owned watch header overlays the WebView without relayout.
        // Android Auto's separate native host bars remain host-controlled.
        if (watchTab && headerAllowed) {
            if (!topMenuVisible) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .statusBarsPadding().height(28.dp)
                        .revealTopMenuOnSwipeDown {
                            topMenuVisible = true
                            topMenuEpoch++
                        }
                )
            }
            AnimatedVisibility(
                visible = topMenuVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                AAYoutubePageHeader(
                    Tab.entries[tab],
                    lyricsAvailable = lyricsAvailable && playerMode == WebHost.PlayerMode.FULL,
                    lyricsShown = lyricsShown,
                    onLyrics = {
                        if (lyricsAvailable) LyricsPrefs.set(context, "show", !lyricsShown)
                        topMenuEpoch++
                    },
                    onSettings = { tab = Tab.Settings.ordinal },
                )
            }
        }
        if (lyricsOnScreen) {
            // Reduce overlap while the menu is hidden; no second app header.
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = if (topMenuVisible && headerAllowed) 76.dp else 30.dp)) {
                LyricsOverlay(snap, foundLyrics) {
                    LyricsPrefs.set(context, "show", false)
                }
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

    Box(modifier) {
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
            val miniW = minOf(224.dp, maxWidth * 0.72f)
            val miniH = miniW * (9f / 16f)
            val edge = with(density) { 12.dp.toPx() }
            val ytMenu = with(density) { 64.dp.toPx() } // YouTube's bottom menu (Home, Subscriptions, You)
            val wPx = with(density) { miniW.toPx() }
            val hPx = with(density) { miniH.toPx() }
            val minX = edge
            val maxX = (constraints.maxWidth - wPx - edge).coerceAtLeast(minX)
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

            // Mini player controls (like the YouTube app): play/pause top left, close top right,
            // red progress line at the bottom. Tap to open it again, drag to move it.
            if (small) {
                // Progress: the page reports now and then; move it along in between.
                var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(snap) {
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
    onHome: () -> Unit,
    onBack: () -> Unit,
    onQuit: () -> Unit,
) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    Column(Modifier.fillMaxSize()) {
        // Only Settings owns this fixed navigation bar; its submenu content scrolls above it.
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
            listOf(Tab.Features, Tab.Lyrics, Tab.Info, Tab.Logs, Tab.Update).forEach { destination ->
                if (destination == Tab.Logs) HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(if (destination == Tab.Update) "About & updates" else destination.label)
                    },
                    supportingContent = {
                        Text(when (destination) {
                            Tab.Features -> "Playback, video and display preferences"
                            Tab.Lyrics -> "Lyrics provider and overlay preferences"
                            Tab.Logs -> "Diagnostics and session events"
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
        HorizontalDivider()
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf(
                Triple("Quit", Icons.Rounded.PowerSettingsNew, onQuit),
                Triple("Home", Icons.Filled.PlayArrow, onHome),
                Triple("Back", Icons.AutoMirrored.Filled.ArrowBack, onBack),
            ).forEach { (label, icon, action) ->
                NavigationBarItem(
                    selected = false,
                    onClick = action,
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                    alwaysShowLabel = true,
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card() {
            Toggle("Block ads", "Strips ads from video data and skips what remains.", f.blockAds) { s, v -> s.copy(blockAds = v) }
            Toggle("Hide Shorts", "Removes Shorts shelves; Shorts links open as normal videos.", f.hideShorts) { s, v -> s.copy(hideShorts = v) }
            Toggle("Hide promos", "Removes banners that ask you to install or upgrade.", f.hidePromos) { s, v -> s.copy(hidePromos = v) }
            Toggle("Background play", "Prevents YouTube pausing when the screen is hidden. Android Auto playback also uses the media service.", f.backgroundPlay) { s, v -> s.copy(backgroundPlay = v) }
            Toggle("Low quality in background", "Lower video resolution while playing with the screen hidden. Saves data and battery.", f.lowQualityInBackground, enabled = f.backgroundPlay) { s, v -> s.copy(lowQualityInBackground = v) }
            Toggle("Picture in picture", "Shrink to a floating player when you leave the app.", f.autoPip) { s, v -> s.copy(autoPip = v) }
            Toggle("Keep watching", "Attempts to dismiss YouTube's inactivity prompts.", f.autoContinue) { s, v -> s.copy(autoContinue = v) }
        }
        Card() {
            Toggle("SponsorBlock", "Skips sponsors, self-promotion and interaction reminders.", f.sponsorBlock) { s, v -> s.copy(sponsorBlock = v) }
            Toggle("Also skip intros and outros", "Adds introductions and end segments to SponsorBlock.", f.sponsorExtra, enabled = f.sponsorBlock) { s, v -> s.copy(sponsorExtra = v) }
            Toggle("Return YouTube Dislike", "Shows the dislike count again.", f.returnDislike) { s, v -> s.copy(returnDislike = v) }
            Toggle("Desktop site", "Use desktop YouTube. Switching returns to Home and interrupts the current video.", f.desktopSite) { s, v -> s.copy(desktopSite = v) }
        }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Card() {
            Toggle("Resume where you left off", "Reopens your last page when starting the app; playback does not automatically resume.", f.resumeLast) { s, v -> s.copy(resumeLast = v) }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp, top = 8.dp)) {
                Text("Top menu auto-hide", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Hide the video top menu on your phone and Android Auto. Swipe down from the top border to show it again.",
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

// ---------------------------------------------------------------------------- logs

/** Splits log entries into screen rows of [cols] characters, so long lines wrap. */
private fun wrapRows(entries: List<String>, cols: Int): List<String> {
    val rows = ArrayList<String>(entries.size + 16)
    for (entry in entries) {
        for (part in entry.split('\n')) {
            if (part.length <= cols) {
                rows.add(part)
                continue
            }
            rows.add(part.substring(0, cols))
            var at = cols
            val room = (cols - 2).coerceAtLeast(1)
            while (at < part.length) {
                rows.add("  " + part.substring(at, minOf(at + room, part.length)))
                at += room
            }
        }
    }
    return rows
}

@Composable
fun LogsPage() {
    val context = LocalContext.current
    val enabled by AppLog.enabled.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            AppLog.refreshFromDisk()
            delay(3_000L)
        }
    }
    val version by AppLog.version.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var pages by remember { mutableIntStateOf(1) }
    var current by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable logging", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { AppLog.setEnabled(it) })
        }
        if (!enabled && AppLog.size == 0) {
            Text("Detailed logging is off. Fatal crash reports are still saved locally.")
            return@Column
        }
        if (!enabled) Text("Detailed logging is off. Saved crash reports remain available.")

        val lineStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
        )
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
                val density = LocalDensity.current
                val rowDp = with(density) { 14.sp.toDp() }
                // A monospace character is about 0.6 of the font size wide.
                val charDp = with(density) { (11.sp * 0.6f).toDp() }
                val cols = ((maxWidth / charDp).toInt() - 1).coerceAtLeast(10)
                val perPage = (maxHeight / rowDp).toInt().coerceAtLeast(1)

                val rows = remember(version, cols) { wrapRows(AppLog.slice(0, Int.MAX_VALUE), cols) }
                val count = (rows.size + perPage - 1) / perPage
                val total = count.coerceAtLeast(1)
                val shown = if (follow) total - 1 else page.coerceIn(0, total - 1)
                SideEffect { pages = total; current = shown }

                Column(Modifier.fillMaxSize()) {
                    rows.drop(shown * perPage).take(perPage).forEach {
                        Text(it, style = lineStyle, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Left, centre, right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            AppButton("Prev", Icons.AutoMirrored.Filled.KeyboardArrowLeft, enabled = current > 0) { page = current - 1; follow = false }
            Text("${current + 1} / $pages", style = MaterialTheme.typography.labelLarge)
            AppButton("Next", Icons.AutoMirrored.Filled.KeyboardArrowRight, enabled = current < pages - 1) {
                page = current + 1; follow = page >= pages - 1
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppButton("Copy", Icons.Filled.ContentCopy) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("AAYoutube logs", AppLog.all()))
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            }
            AppButton("Clear all", Icons.Filled.DeleteSweep) { AppLog.clear(); follow = true }
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
