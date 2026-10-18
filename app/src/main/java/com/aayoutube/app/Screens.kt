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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
    Update("Update & Version", Icons.Filled.SystemUpdate),
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

    BackHandler(enabled = fullscreen != null || (tab == 0 && canGoBack) || tab != 0) {
        when {
            fullscreen != null -> WebHost.exitFullscreen()
            tab != 0 -> tab = 0
            else -> WebHost.goBack()
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
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = fullscreen == null && !inPip && barShown,
                    enter = expandVertically(expandFrom = Alignment.Top),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
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
                            Tab.Logs -> LogsPage()
                            Tab.Update -> UpdatePage()
                        }
                    }
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

@Composable
fun WebPane(modifier: Modifier = Modifier) {
    val version by WebHost.webVersion.collectAsState()
    val progress by WebHost.progress.collectAsState()
    val offline by WebHost.offline.collectAsState()

    Box(modifier) {
        key(version) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val activity = ctx.findActivity()!!
                    FrameLayout(ctx).also { frame ->
                        frame.addView(WebHost.attach(activity), FrameLayout.LayoutParams(-1, -1))
                    }
                },
                onRelease = { WebHost.detach(it) },
            )
        }
        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }
        if (offline) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(Modifier.padding(24.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Can't reach YouTube", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("Check your connection and try again.")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { WebHost.reload() }) { Text("Retry") }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { WebHost.reload() }) { Text("Reload") }
            OutlinedButton(onClick = { WebHost.clearCache() }) { Text("Clear cache") }
            OutlinedButton(onClick = { WebHost.clearSession() }) { Text("Sign out") }
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
        Text("Update & Version", style = MaterialTheme.typography.headlineMedium)

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
                        Button(onClick = { AppUpdater.check(manual = true) }) { Text("Check for updates") }
                    }
                    is AppUpdater.State.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoadingIndicator(Modifier.size(48.dp))
                            Text("Checking…")
                        }
                    }
                    is AppUpdater.State.UpToDate -> {
                        Text("You're on the latest version.", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { AppUpdater.check(manual = true) }) { Text("Check again") }
                    }
                    is AppUpdater.State.Available -> {
                        Text("v${s.release.versionName} is available", style = MaterialTheme.typography.titleMedium)
                        Notes(s.release)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AppUpdater.update(s.release) }) { Text("Download & install") }
                            TextButton(onClick = { AppUpdater.skip(s.release) }) { Text("Skip") }
                        }
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
                        Button(onClick = { activity?.let { AppUpdater.openInstallPermission(it) } }) { Text("Open settings") }
                    }
                    is AppUpdater.State.Ready -> {
                        Text("v${s.release.versionName} is ready", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { AppUpdater.install(s.release) }) { Text("Install") }
                    }
                    is AppUpdater.State.Failed -> {
                        Text(s.message, style = MaterialTheme.typography.titleMedium)
                        if (s.diagnostics.isNotBlank()) {
                            Text(s.diagnostics, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { AppUpdater.check(manual = true) }) { Text("Try again") }
                            if (activity != null) {
                                OutlinedButton(onClick = { AppUpdater.openReleasePage(activity, s.release) }) { Text("Release page") }
                            }
                            TextButton(onClick = { AppUpdater.dismiss() }) { Text("Dismiss") }
                        }
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

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = current > 0,
                onClick = { page = current - 1; follow = false },
            ) { Text("Prev") }
            Text("${current + 1} / $pages", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                enabled = current < pages - 1,
                onClick = { page = current + 1; follow = page >= pages - 1 },
            ) { Text("Next") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("AAYoutube logs", AppLog.all()))
                Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
            OutlinedButton(onClick = { AppLog.clear(); follow = true }) { Text("Clear all") }
        }
    }
}
