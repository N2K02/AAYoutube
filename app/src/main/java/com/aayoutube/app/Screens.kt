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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.animation.AnimatedVisibility
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
    Logs("Logs", Icons.Filled.BugReport),
    Update("Update", Icons.Filled.SystemUpdate),
    Info("About", Icons.Filled.Info),
    Settings("Settings", Icons.Filled.Settings),
}

/** One host-owned header, matching the mRadio icon/title + app icon/name layout. */
@Composable
private fun AAYoutubePageHeader(tab: Tab) {
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val features by Features.state.collectAsState()
    // The round menu button fades after a few seconds without a touch, and comes back on the next one.
    var touches by remember { mutableIntStateOf(0) }
    var faded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val fullscreen by WebHost.fullscreenView.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    val onCar by WebHost.carScreen.collectAsState()
    val snap by WebHost.snapshot.collectAsState()
    val sharedUpdateStatus by LiveUpdater.controller.status.collectAsState()
    val htmlMenuTap by WebHost.htmlMenuTap.collectAsState()
    LaunchedEffect(htmlMenuTap) { if (htmlMenuTap > 0L) menuOpen = true }

    LaunchedEffect(snap.playing) { activity.updatePip() }
    LaunchedEffect(touches, tab, menuOpen, features.autoHideMenu, features.autoHideSecs) {
        faded = false
        if (features.autoHideMenu && !menuOpen) {
            delay(features.autoHideSecs * 1000L)
            faded = true
        }
    }
    // Picture in picture always shows the video: go back to the YouTube tab.
    LaunchedEffect(inPip) { if (inPip) { tab = 0; menuOpen = false } }
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

    // Back (gesture, button, or Back in the menu): close fullscreen, then other tabs, then the
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
    BackHandler(enabled = true) {
        if (menuOpen) menuOpen = false else goBack()
    }

    // One fixed layout for every mode (normal, fullscreen, picture in picture): the page is never
    // moved between parents, because detaching a WebView from its window pauses the video.
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Initial pass: seen before the page or any button handles the touch.
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press) touches++
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (showAAYoutubeHeader(tab == Tab.Watch.ordinal, onCar, inPip, fullscreen != null)) {
                    AAYoutubePageHeader(Tab.entries[tab])
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
                            Tab.Settings -> AAYoutubeSettingsMenu(onSelect = { tab = it.ordinal })
                        }
                    }
                }
            }
        }
        // Native Settings shortcut: remains reachable even if YouTube replaces its bottom DOM.
        if (tab == Tab.Watch.ordinal && !inPip && fullscreen == null && !onCar) {
            Surface(
                onClick = { menuOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "AAyoutube Settings",
                        modifier = Modifier.size(24.dp))
                    Text("Settings", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (tab == Tab.Watch.ordinal && !inPip && fullscreen == null && snap.id.isNotBlank() && WebHost.mode.value == WebHost.PlayerMode.FULL) {
            val ctx = LocalContext.current
            var lyricsShown by remember { mutableStateOf(LyricsPrefs.show(ctx)) }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 90.dp)) {
                LyricsFloatingButton(lyricsShown) {
                    lyricsShown = !lyricsShown
                    LyricsPrefs.set(ctx, "show", lyricsShown)
                }
            }
            Box(Modifier.align(Alignment.TopCenter)) {
                LyricsOverlay(snap, lyricsShown) {
                    lyricsShown = false
                    LyricsPrefs.set(ctx, "show", false)
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
        if (full == null && !inPip) {
            // Car screen: the round button is only a Back button (a video shrinks to the mini player
            // with Home underneath, like the YouTube app). On the phone it is the menu.
            val backButton = onCar
            if (onCar) {
                MenuButton(
                    backButton = true,
                    looseTaps = true,
                    selected = tab,
                    open = menuOpen,
                    faded = faded,
                    updateAvailable = sharedUpdateStatus.hasPendingUpdate(),
                    onOpenChange = { menuOpen = it },
                    onSelect = { tab = it; menuOpen = false },
                    onBack = { menuOpen = false; goBack() },
                    onQuit = { menuOpen = false; activity.quit() },
                )
            } else {
                if (menuOpen) {
                    ModalBottomSheet(
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    ) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars)) {
                        Text(
                            "AAYoutube",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                        listOf(Tab.Watch, Tab.Settings).forEach { item ->
                            val index = item.ordinal
                            Surface(
                                onClick = { tab = index; menuOpen = false },
                                shape = RoundedCornerShape(22.dp),
                                color = if (tab == index) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 3.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Icon(item.icon, contentDescription = null)
                                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                    if (item == Tab.Settings) {
                                        // Settings links to About & updates on the phone.
                                        dev.n2k02.updater.N2K02UpdateBadge(sharedUpdateStatus)
                                    }
                                }
                            }
                        }
                        Surface(
                            onClick = { menuOpen = false; goBack() },
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                Text("Back")
                            }
                        }
                        Surface(
                            onClick = { menuOpen = false; activity.quit() },
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                                Text("Quit")
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        }

                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- menu button

/** Where the mini player is on screen (root coordinates), so the round button can keep clear of it. */
private object MiniPlace {
    val rect = kotlinx.coroutines.flow.MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)
}

/**
 * The round menu button: floats over everything, can be dragged anywhere (its place is
 * remembered), and opens the menu (pages, Back, Quit) next to itself.
 */
@Composable
private fun MenuButton(
    backButton: Boolean,
    looseTaps: Boolean,
    selected: Int,
    open: Boolean,
    faded: Boolean,
    updateAvailable: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onQuit: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aay_ui", Context.MODE_PRIVATE) }
    var originRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { originRoot = it.positionInRoot() },
    ) {
        val density = LocalDensity.current
        val fab = 56.dp
        val gap = 12.dp
        val fabPx = with(density) { fab.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val maxX = (constraints.maxWidth - fabPx - gapPx).coerceAtLeast(gapPx)
        val maxY = (constraints.maxHeight - fabPx - gapPx).coerceAtLeast(gapPx)
        // Saved as fractions, so the place survives rotation and different screens (phone / car).
        var fx by remember { mutableStateOf(prefs.getFloat("fabX", 1f)) }
        var fy by remember { mutableStateOf(prefs.getFloat("fabY", 0.72f)) }
        val homeX = gapPx + (maxX - gapPx) * fx.coerceIn(0f, 1f)
        val homeY = gapPx + (maxY - gapPx) * fy.coerceIn(0f, 1f)

        // Keep clear of the mini player: if they would overlap, step to the nearest free spot
        // (above, below, left or right of it). The saved place is unchanged; the button returns
        // there when the mini player closes.
        val mini by MiniPlace.rect.collectAsState()
        val origin = originRoot
        val clear = run {
            val r = mini ?: return@run homeX to homeY
            val left = r.left - origin.x - gapPx
            val top = r.top - origin.y - gapPx
            val right = r.right - origin.x + gapPx
            val bottom = r.bottom - origin.y + gapPx
            val overlaps = homeX < right && homeX + fabPx > left && homeY < bottom && homeY + fabPx > top
            if (!overlaps) return@run homeX to homeY
            listOf(
                homeX to (top - fabPx),
                homeX to bottom,
                (left - fabPx) to homeY,
                right to homeY,
            ).filter { (cx, cy) -> cx in gapPx..maxX && cy in gapPx..maxY }
                .minByOrNull { (cx, cy) -> kotlin.math.abs(cx - homeX) + kotlin.math.abs(cy - homeY) }
                ?: (homeX to homeY)
        }
        val x by androidx.compose.animation.core.animateFloatAsState(clear.first, label = "menuX")
        val y by androidx.compose.animation.core.animateFloatAsState(clear.second, label = "menuY")

        if (open) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(Unit) { detectTapGestures { onOpenChange(false) } },
            )
            val right = x + fabPx / 2 > constraints.maxWidth / 2
            val up = y + fabPx / 2 > constraints.maxHeight / 2
            val wDp = with(density) { constraints.maxWidth.toDp() }
            val hDp = with(density) { constraints.maxHeight.toDp() }
            val xDp = with(density) { x.toDp() }
            val yDp = with(density) { y.toDp() }
            val place = Modifier
                .align(
                    when {
                        up && right -> Alignment.BottomEnd
                        up -> Alignment.BottomStart
                        right -> Alignment.TopEnd
                        else -> Alignment.TopStart
                    },
                )
                .padding(
                    start = if (right) 0.dp else xDp,
                    end = if (right) (wDp - xDp - fab).coerceAtLeast(0.dp) else 0.dp,
                    top = if (up) 0.dp else yDp + fab + 8.dp,
                    bottom = if (up) (hDp - yDp + 8.dp).coerceAtLeast(0.dp) else 0.dp,
                )
            val items: List<@Composable () -> Unit> = listOf(Tab.Watch, Tab.Settings).map { t ->
                @Composable { MenuItem(t.label, t.icon, right, selected == t.ordinal, badge = t == Tab.Settings && updateAvailable) { onSelect(t.ordinal) } }
            } + listOf(
                @Composable { MenuItem("Back", Icons.AutoMirrored.Filled.ArrowBack, right, false) { onBack() } },
                @Composable { MenuItem("Quit", Icons.Rounded.PowerSettingsNew, right, false) { onQuit() } },
            )
            // One column if it fits next to the button, otherwise two (short, wide car screens).
            val room = if (up) yDp - 8.dp else hDp - yDp - fab - 8.dp
            val perColumn = if (room >= 56.dp * items.size) items.size else (items.size + 1) / 2
            Row(place, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val columns = items.chunked(perColumn).let { if (right) it.reversed() else it }
                columns.forEach { col ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = if (right) Alignment.End else Alignment.Start,
                    ) { col.forEach { it() } }
                }
            }
        }

        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(fab)
                .graphicsLayer { alpha = if (faded && !open) 0.35f else 1f }
                .pointerInput(maxX, maxY, looseTaps, backButton, open) {
                    // Tap: menu (or Back). Drag: move the button. A car touchscreen jitters a few
                    // pixels during a tap, so there a tap may move further before it counts as a drag.
                    val slop = if (looseTaps) maxOf(28.dp.toPx(), viewConfiguration.touchSlop) else viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var dragging = false
                        var moved = androidx.compose.ui.geometry.Offset.Zero
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                c.consume()
                                when {
                                    dragging -> prefs.edit().putFloat("fabX", fx).putFloat("fabY", fy).apply()
                                    open -> onOpenChange(false)
                                    backButton -> {
                                        AppLog.add("UI", "Back button")
                                        onBack()
                                    }
                                    else -> onOpenChange(true)
                                }
                                break
                            }
                            val d = c.position - c.previousPosition
                            moved += d
                            if (!dragging && moved.getDistance() > slop) dragging = true
                            if (dragging) {
                                val span = (maxX - gapPx).coerceAtLeast(1f)
                                val spanY = (maxY - gapPx).coerceAtLeast(1f)
                                fx = (fx + d.x / span).coerceIn(0f, 1f)
                                fy = (fy + d.y / spanY).coerceIn(0f, 1f)
                            }
                            c.consume()
                        }
                    }
                },
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        backButton && !open -> Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        updateAvailable && !open -> dev.n2k02.updater.N2K02UpdateBadgedIcon(LiveUpdater.controller.status.collectAsState().value) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                        else -> Icon(if (open) Icons.Filled.Close else Icons.Filled.Menu, contentDescription = if (open) "Close menu" else "Menu")
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, iconRight: Boolean, selected: Boolean, badge: Boolean = false, onClick: () -> Unit) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = container,
        contentColor = content,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val ic: @Composable () -> Unit = {
                if (badge) dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) { Icon(icon, "Update", Modifier.size(22.dp)) }
                else Icon(icon, null, Modifier.size(22.dp))
            }
            if (!iconRight) ic()
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (iconRight) ic()
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
                        AppLog.add("CAR", "Swipe down on the video: mini player")
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
    val onCar by WebHost.carScreen.collectAsState()
    // Picture in picture always shows the video full size, whatever the mode.
    val small = mode == WebHost.PlayerMode.MINI && !inPip
    val hidden = mode == WebHost.PlayerMode.HIDDEN && !inPip

    // The browse page only works while you can see it.
    LaunchedEffect(mode) {
        if (mode == WebHost.PlayerMode.FULL) BrowseHost.pause() else BrowseHost.resume()
    }

    Box(modifier) {
        // Browse page (behind): made the first time the mini player opens.
        if (browseOn) {
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

        // Player page: never moved to another parent (that would pause the video); only its size
        // and place change. Mini player: a floating window like the YouTube app's, draggable,
        // starting above YouTube's own bottom menu so it covers nothing important.
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("aay_ui", Context.MODE_PRIVATE) }
        val density = LocalDensity.current
        var mx by remember { mutableStateOf(prefs.getFloat("miniX", 1f)) }
        var my by remember { mutableStateOf(prefs.getFloat("miniY", 1f)) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val miniW = 224.dp
            val miniH = 126.dp
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

            val playerMod = when {
                small -> miniMod.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                hidden -> Modifier.size(1.dp).graphicsLayer { alpha = 0f }
                // Car screen: swipe down on the video shrinks it into the mini player, as on the phone.
                // Detected here rather than in the page, whatever kind of touch Android Auto sends.
                onCar -> Modifier.fillMaxSize().then(carSwipeDown)
                else -> Modifier.fillMaxSize()
            }
            key(version) {
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

                DisposableEffect(Unit) { onDispose { MiniPlace.rect.value = null } }
                Box(
                    miniMod
                        .onGloballyPositioned { MiniPlace.rect.value = it.boundsInRoot() }
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
private fun AAYoutubeSettingsMenu(onSelect: (Tab) -> Unit) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
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
}

// ---------------------------------------------------------------------------- features

@Composable
fun FeaturesPage() {
    val f by Features.state.collectAsState()

    @Composable
    fun Toggle(title: String, text: String, value: Boolean, change: (FeatureFlags, Boolean) -> FeatureFlags) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(text) },
            trailingContent = {
                Switch(checked = value, onCheckedChange = { on -> Features.update { change(it, on) } })
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
            Toggle("Background play", "Keeps playing with the screen off and in Android Auto.", f.backgroundPlay) { s, v -> s.copy(backgroundPlay = v) }
            Toggle("Low quality in background", "Lowest video quality while the screen is hidden. Saves battery.", f.lowQualityInBackground) { s, v -> s.copy(lowQualityInBackground = v) }
            Toggle("Picture in picture", "Shrink to a floating player when you leave the app.", f.autoPip) { s, v -> s.copy(autoPip = v) }
            Toggle("Keep watching", "Confirms “Are you still watching?” for you.", f.autoContinue) { s, v -> s.copy(autoContinue = v) }
        }
        Card() {
            Toggle("SponsorBlock", "Skips sponsor segments.", f.sponsorBlock) { s, v -> s.copy(sponsorBlock = v) }
            Toggle("Also skip intros and outros", "Self-promo, intro, outro and reminders.", f.sponsorExtra) { s, v -> s.copy(sponsorExtra = v) }
            Toggle("Return YouTube Dislike", "Shows the dislike count again.", f.returnDislike) { s, v -> s.copy(returnDislike = v) }
            Toggle("Desktop site", "Use www.youtube.com instead of the mobile site.", f.desktopSite) { s, v -> s.copy(desktopSite = v) }
        }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Card() {
            Toggle("Resume where you left off", "Off: the app always starts on YouTube Home. On: it reopens the last page.", f.resumeLast) { s, v -> s.copy(resumeLast = v) }
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
        Text("Menu button", style = MaterialTheme.typography.titleMedium)
        Card() {
            Toggle(
                "Fade the menu button",
                "The round menu button turns see-through when you don't touch the screen. Touch anywhere to bring it back. Drag it to move it.",
                f.autoHideMenu,
            ) { s, v -> s.copy(autoHideMenu = v) }
            if (f.autoHideMenu) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    Text("Fade after", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        (1..5).forEach { secs ->
                            SegmentedButton(
                                selected = f.autoHideSecs == secs,
                                onClick = { Features.update { it.copy(autoHideSecs = secs) } },
                                shape = SegmentedButtonDefaults.itemShape(index = secs - 1, count = 5),
                            ) { Text("${secs}s") }
                        }
                    }
                }
            }
        }
        // Left, centre, right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AppButton("Reload", Icons.Filled.Refresh) { WebHost.reload() }
            AppButton("Clear cache", Icons.Filled.DeleteSweep) { WebHost.clearCache() }
            AppButton("Sign out", Icons.AutoMirrored.Filled.Logout) { WebHost.clearSession() }
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
        if (!enabled) return@Column

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
