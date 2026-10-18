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
import androidx.compose.material3.Badge
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
    Watch("YouTube", Icons.Filled.PlayCircle),
    Features("Features", Icons.Filled.Tune),
    Logs("Logs", Icons.Filled.BugReport),
    Update("Info", Icons.Filled.Info),
}

@Composable
fun AppRoot(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val features by Features.state.collectAsState()
    // Auto-hide: the menu bar slides away after a few seconds without a touch, and comes back on the next one.
    var touches by remember { mutableIntStateOf(0) }
    var barShown by remember { mutableStateOf(true) }
    val fullscreen by WebHost.fullscreenView.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    val canGoBack by WebHost.canGoBack.collectAsState()
    val snap by WebHost.snapshot.collectAsState()
    val updater by AppUpdater.state.collectAsState()

    LaunchedEffect(snap.playing) { activity.updatePip() }
    LaunchedEffect(touches, tab, features.autoHideMenu, features.autoHideSecs) {
        barShown = true
        if (features.autoHideMenu) {
            delay(features.autoHideSecs * 1000L)
            barShown = false
        }
    }
    // Picture in picture always shows the video: go back to the YouTube tab.
    LaunchedEffect(inPip) { if (inPip) tab = 0 }
    LaunchedEffect(updater) { AppLog.add("UPDATE", updater::class.simpleName.orEmpty()) }
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

    // Back: close fullscreen, then other tabs, then the page's own history (asked live from the
    // WebView, because YouTube changes pages without reloading), then YouTube home. On the home
    // page, back sends the app to the background (it keeps playing) instead of closing it.
    BackHandler(enabled = true) {
        when {
            fullscreen != null -> WebHost.exitFullscreen()
            tab != 0 -> tab = 0
            // Browsing under the mini player (or after closing it): back moves through the browse page.
            WebHost.mode.value != WebHost.PlayerMode.FULL && BrowseHost.goBack() -> Unit
            WebHost.mode.value != WebHost.PlayerMode.FULL -> activity.moveTaskToBack(true)
            // Watching: back shrinks the video into the mini player, like the YouTube app.
            snap.playing && WebHost.isOnWatchPage() -> WebHost.enterMini()
            WebHost.goBack() -> Unit
            else -> {
                AppLog.add("UI", "Back on YouTube home: app to background")
                activity.moveTaskToBack(true)
            }
        }
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
        val barAllowed = fullscreen == null && !inPip
        val bar: @Composable () -> Unit = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            if (t == Tab.Update && updater is AppUpdater.State.Available) {
                                BadgedBox(badge = { Badge() }) { Icon(t.icon, null) }
                            } else {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                    )
                }
                // Quit: same power icon as MRadio. Stops playback and closes the app.
                NavigationBarItem(
                    selected = false,
                    onClick = { activity.quit() },
                    icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit") },
                    label = { Text("Quit") },
                )
            }
        }
        Scaffold(
            bottomBar = {
                // Auto-hide off: the bar is part of the layout as usual.
                if (!features.autoHideMenu && barAllowed) bar()
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                // The page stays in place under the other tabs, so playback carries on while you look at them.
                WebPane(Modifier.fillMaxSize(), bottomInset = if (features.autoHideMenu) 80.dp else 0.dp)
                if (tab != 0 && !inPip) {
                    Surface(
                        Modifier.fillMaxSize().pointerInput(Unit) { },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when (Tab.entries[tab]) {
                            Tab.Watch -> Unit
                            Tab.Features -> FeaturesPage()
                            Tab.Logs -> LogsPage()
                            Tab.Update -> UpdatePage()
                        }
                    }
                }
            }
        }
        // Auto-hide on: the bar floats over the bottom of the page, so showing or hiding it never
        // resizes the page (resizing in the middle of a swipe broke YouTube's swipe-down gesture).
        if (features.autoHideMenu) {
            AnimatedVisibility(
                visible = barAllowed && barShown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) { bar() }
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

@Composable
fun WebPane(modifier: Modifier = Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val version by WebHost.webVersion.collectAsState()
    val progress by WebHost.progress.collectAsState()
    val offline by WebHost.offline.collectAsState()
    val mode by WebHost.mode.collectAsState()
    val inPip by WebHost.inPip.collectAsState()
    val snap by WebHost.snapshot.collectAsState()
    val browseOn by BrowseHost.created.collectAsState()
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

        // Player page: never moved to another parent (that would pause the video); only its size changes.
        val miniBox = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 12.dp + bottomInset)
            .width(224.dp)
            .aspectRatio(16f / 9f)
        val playerMod = when {
            small -> miniBox.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            hidden -> Modifier.size(1.dp).graphicsLayer { alpha = 0f }
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

        // Mini player controls: tap to open it again, pause/play, close.
        if (small) {
            Box(
                miniBox.pointerInput(Unit) {
                    detectTapGestures(onTap = { WebHost.expandPlayer() })
                },
            ) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { WebHost.closePlayer() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp),
                ) { Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(18.dp)) }
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { WebHost.cmd(if (snap.playing) "pause" else "play") },
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(32.dp),
                ) {
                    Icon(
                        if (snap.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (snap.playing) "Pause" else "Play",
                        modifier = Modifier.size(18.dp),
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
        Text("Features", style = MaterialTheme.typography.headlineMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Toggle("Block ads", "Strips ads from video data and skips what remains.", f.blockAds) { s, v -> s.copy(blockAds = v) }
            Toggle("Hide Shorts", "Removes Shorts shelves; Shorts links open as normal videos.", f.hideShorts) { s, v -> s.copy(hideShorts = v) }
            Toggle("Hide promos", "Removes banners that ask you to install or upgrade.", f.hidePromos) { s, v -> s.copy(hidePromos = v) }
            Toggle("Background play", "Keeps playing with the screen off and in Android Auto.", f.backgroundPlay) { s, v -> s.copy(backgroundPlay = v) }
            Toggle("Low quality in background", "Lowest video quality while the screen is hidden. Saves battery.", f.lowQualityInBackground) { s, v -> s.copy(lowQualityInBackground = v) }
            Toggle("Picture in picture", "Shrink to a floating player when you leave the app.", f.autoPip) { s, v -> s.copy(autoPip = v) }
            Toggle("Keep watching", "Confirms “Are you still watching?” for you.", f.autoContinue) { s, v -> s.copy(autoContinue = v) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Toggle("SponsorBlock", "Skips sponsor segments.", f.sponsorBlock) { s, v -> s.copy(sponsorBlock = v) }
            Toggle("Also skip intros and outros", "Self-promo, intro, outro and reminders.", f.sponsorExtra) { s, v -> s.copy(sponsorExtra = v) }
            Toggle("Return YouTube Dislike", "Shows the dislike count again.", f.returnDislike) { s, v -> s.copy(returnDislike = v) }
            Toggle("Desktop site", "Use www.youtube.com instead of the mobile site.", f.desktopSite) { s, v -> s.copy(desktopSite = v) }
        }
        Text("Display", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Toggle("Resume where you left off", "Opens the last page when the app starts.", f.resumeLast) { s, v -> s.copy(resumeLast = v) }
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
        Text("Menu bar", style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Toggle(
                "Auto-hide menu bar",
                "Hides the bottom menu when you don't touch the screen. Touch anywhere to bring it back.",
                f.autoHideMenu,
            ) { s, v -> s.copy(autoHideMenu = v) }
            if (f.autoHideMenu) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    Text("Hide after", style = MaterialTheme.typography.labelLarge)
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
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val activity = context.findActivity()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Info", style = MaterialTheme.typography.headlineMedium)

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

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Installed version", style = MaterialTheme.typography.labelLarge)
                Text("AAYoutube v" + AppUpdater.installedVersionName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "A personal YouTube player for the phone and Android Auto, built on the YouTube website in a WebView. " +
                        "It blocks ads, skips sponsors (SponsorBlock), brings back dislikes, hides Shorts and keeps playing " +
                        "in the background and in picture in picture. In the car it plays through the media player and, " +
                        "on Android 15+, shows the full screen on the car display. The page runs in its own process, so a " +
                        "WebView problem can't freeze Android Auto. Updates come from GitHub releases (N2K02/AAYoutube).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = state) {
                    is AppUpdater.State.Idle -> {
                        Text("Check GitHub for a new version.")
                        AppButton("Check for updates", Icons.Filled.SystemUpdate, Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
                    }
                    is AppUpdater.State.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoadingIndicator(Modifier.size(48.dp))
                            Text("Checking…")
                        }
                    }
                    is AppUpdater.State.UpToDate -> {
                        Text("You're on the latest version.", style = MaterialTheme.typography.titleMedium)
                        AppButton("Check again", Icons.Filled.SystemUpdate, Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
                    }
                    is AppUpdater.State.Available -> {
                        Text("v${s.release.versionName} is available", style = MaterialTheme.typography.titleMedium)
                        Notes(s.release)
                        AppButton("Download & install", Icons.Filled.Download, Modifier.fillMaxWidth()) { AppUpdater.update(s.release) }
                        AppButton("Skip this version", Icons.Filled.SkipNext, Modifier.fillMaxWidth()) { AppUpdater.skip(s.release) }
                    }
                    is AppUpdater.State.Downloading -> {
                        Text("Downloading v${s.release.versionName}", style = MaterialTheme.typography.titleMedium)
                        val p = s.progress
                        if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                    is AppUpdater.State.NeedsPermission -> {
                        Text("Allow installs from AAYoutube", style = MaterialTheme.typography.titleMedium)
                        Text("Android needs “install unknown apps” turned on for this app. The install continues when you come back.")
                        AppButton("Open settings", Icons.Filled.Settings, Modifier.fillMaxWidth()) { activity?.let { AppUpdater.openInstallPermission(it) } }
                    }
                    is AppUpdater.State.Ready -> {
                        Text("v${s.release.versionName} is ready", style = MaterialTheme.typography.titleMedium)
                        AppButton("Install", Icons.Filled.InstallMobile, Modifier.fillMaxWidth()) { AppUpdater.install(s.release) }
                    }
                    is AppUpdater.State.Failed -> {
                        Text(s.message, style = MaterialTheme.typography.titleMedium)
                        if (s.diagnostics.isNotBlank()) {
                            Text(s.diagnostics, style = MaterialTheme.typography.bodySmall)
                        }
                        AppButton("Try again", Icons.Filled.Refresh, Modifier.fillMaxWidth()) { AppUpdater.check(manual = true) }
                        if (activity != null) {
                            AppButton("Release page", Icons.AutoMirrored.Filled.OpenInNew, Modifier.fillMaxWidth()) { AppUpdater.openReleasePage(activity, s.release) }
                        }
                        AppButton("Dismiss", Icons.Filled.Close, Modifier.fillMaxWidth()) { AppUpdater.dismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notes(release: AppUpdater.Release) {
    if (release.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What's new", style = MaterialTheme.typography.labelLarge)
        release.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
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
            Text("Logs", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
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
