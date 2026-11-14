package com.aayoutube.app

import android.Manifest
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The screen. Runs in the ":web" process, next to the WebView. It also tells the media service
 * (other process) that it is there, so the service links up with this process.
 */
class MainActivity : ComponentActivity() {

    companion object {
        // The phone and the car display can each have their own screen (singleTop, like AABrowser),
        // sharing one page. Only the last of them to hide may pause or hide the page.
        private val started = mutableSetOf<MainActivity>()
        private val resumed = mutableSetOf<MainActivity>()
        /** Restore ownership when the projected car activity relinquishes the media link. */
        fun restorePhoneLink() {
            val phone = started.lastOrNull() ?: return
            WebHost.requestLink = { phone.runOnUiThread { phone.sayHello() } }
        }
    }

    private var compatConnection: CompatMediaConnection? = null
    private var watchTabVisible = true
    private val relinkPhone: () -> Unit = { runOnUiThread { sayHello() } }
    private val quitPhone: () -> Unit = { runOnUiThread { closeEverything() } }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveUpdater.init(this)
        CarMode.resetToCarScreen(this)
        // Track real car-display launches without logging searches, URLs or personal data.
        logCarActivityEntry("create", intent)
        // On the car display a reopened video plays by itself; on the phone it waits for you.
        WebHost.setCarScreen(!onPhoneScreen())
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Opened from the icon (not a link, not after rotation): start on YouTube Home.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) WebHost.goHomeIfIdle()
        handleIntent(intent)
        WebHost.onQuit = quitPhone
        setContent { AAYoutubeTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logCarActivityEntry("newIntent", intent)
        // A reused activity can move to another car display.
        WebHost.setCarScreen(!onPhoneScreen())
        handleIntent(intent)
    }

    private fun logCarActivityEntry(event: String, launch: Intent?) {
        val displayId = display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        val carDisplay = displayId != android.view.Display.DEFAULT_DISPLAY
        val fromCarLauncher = launch?.categories?.contains("android.intent.category.CAR_LAUNCHER") == true
        val fromMaps = launch?.categories?.contains("android.intent.category.APP_MAPS") == true
        // Intent categories are finite platform constants. Never record URI or search text.
        AppLog.add("CAR", "Video activity $event: display=$displayId" +
            " carDisplay=$carDisplay launcherCategory=$fromCarLauncher mapsCategory=$fromMaps")
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { WebHost.openUrl(it) }
            "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
                AppLog.add("CAR", "Assistant media search received")
                if (query.isNotBlank()) WebHost.voiceSearch(query)
                else WebHost.cmd("play")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started += this
        WebHost.onQuit = quitPhone
        if (!WebHost.carShown) WebHost.requestLink = relinkPhone
        AppLog.add("VIEW", if (onPhoneScreen()) "Screen on the phone" else "Screen on the car display")
        WebHost.setCarScreen(!onPhoneScreen())
        sayHello()
        WebHost.onActivityVisible(true)
        if (WebHost.mode.value != WebHost.PlayerMode.FULL) BrowseHost.resume()
    }

    override fun onPause() {
        super.onPause()
        resumed -= this
        if (resumed.isEmpty() && !WebHost.carShown) WebHost.setHold(true)
    }

    override fun onResume() {
        super.onResume()
        resumed += this
        if (!isInPictureInPictureMode) {
            WebHost.setHold(false)
            WebHost.pip(false) // Clear an interrupted PiP entry without reloading.
        }
        LiveUpdater.onResume(this)
        updatePip()
    }

    override fun onDestroy() {
        super.onDestroy()
        started -= this
        resumed -= this
        if (WebHost.onQuit === quitPhone)
            WebHost.onQuit = started.lastOrNull()?.quitPhone
    }

    override fun onStop() {
        super.onStop()
        started -= this
        releaseController()
        val other = started.lastOrNull()
        if (other != null && WebHost.onQuit === quitPhone)
            WebHost.onQuit = other.quitPhone
        if (WebHost.requestLink === relinkPhone) {
            // Never remove a link now owned by the projection activity.
            WebHost.requestLink = null
            if (other != null) restorePhoneLink()
        }
        if (other != null) return
        WebHost.onActivityVisible(false)
        // The phone can stop while a car-only mini player is still browsing.
        // Pausing the shared browse WebView would blank that car screen.
        if (!WebHost.carShown) BrowseHost.pause()
    }

    private fun sayHello() {
        if (compatConnection != null) return
        compatConnection = CompatMediaConnection(this) {
            it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)
        }.also { it.connect() }
    }

    /** Video on screen: ask for 60 Hz instead of the panel's 120 Hz. YouTube video is
     * 24-60 fps, so the extra refreshes only cost compositor/GPU work (surfaceflinger
     * measured ~50% CPU during 720p playback). 0f gives the choice back to Android. */
    fun setVideoRefreshCap(on: Boolean) {
        val rate = VideoRefreshPolicy.preferredRate(on)
        val lp = window.attributes
        if (lp.preferredRefreshRate == rate) return
        lp.preferredRefreshRate = rate
        window.attributes = lp
    }

    fun quit() {
        AppLog.add("UI", "Quit")
        compatConnection?.command(Commands.EXIT)
        // Deliver Quit even when MediaBrowser is disconnected or still reconnecting.
        // The service deduplicates this with the MediaSession custom action.
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                this, Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_QUIT)
            )
        }.onFailure {
            AppLog.add("UI", "Media shutdown intent failed: ${it.javaClass.simpleName}")
        }
        // Give the media process time to acknowledge the request before tearing down :web.
        // The local fallback still quits when the media service is unavailable.
        android.os.Handler(mainLooper).postDelayed({
            if (!isFinishing && !isDestroyed) closeEverything()
        }, 1_600L)
    }

    private fun closeEverything() {
        WebHost.onQuit = null
        WebHost.requestLink = null
        releaseController()
        WebHost.cmd("pause")
        BrowseHost.release()
        finishAndRemoveTask()
        android.os.Handler(mainLooper).postDelayed({
            WebHost.release()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun releaseController() {
        compatConnection?.disconnect()
        compatConnection = null
    }
    // ---------------------------------------------------------------- picture in picture

    /** False when Android Auto is showing this screen on the car display (no picture in picture there). */
    private fun onPhoneScreen(): Boolean {
        val id = if (Build.VERSION.SDK_INT >= 30) display?.displayId else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        return id == null || id == android.view.Display.DEFAULT_DISPLAY
    }

    /** Android chooses floating PiP dimensions. Use the real video source
     * rectangle and disable WebView-unsafe seamless surface resizing. */
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        val bounds = Rect()
        // SourceRectHint controls the transition animation, not the desired
        // floating window size. Never feed it a rect measured *inside* PiP:
        // that can keep shrinking the hint across state updates.
        if (!isInPictureInPictureMode && WebHost.pipSourceRect(bounds))
            builder.setSourceRectHint(bounds)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(
                Features.current.autoPip && watchTabVisible && WebHost.snapshot.value.playing &&
                    WebHost.isOnWatchPage() && onPhoneScreen() && !isInPictureInPictureMode
            )
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    fun updatePip(isWatchTab: Boolean = watchTabVisible) {
        if (Build.VERSION.SDK_INT < 26 || !onPhoneScreen()) return
        watchTabVisible = isWatchTab
        // Once inside PiP, keep the initial 16:9 size request unchanged.
        // Repeated updates from the now-small window can poison the hint.
        if (isInPictureInPictureMode) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ auto-enter is driven by PiP params; do NOT switch CSS
        // into PiP early if the launcher ultimately declines the transition.
        if (Build.VERSION.SDK_INT in 26..30 && Features.current.autoPip &&
            watchTabVisible && WebHost.snapshot.value.playing &&
            WebHost.isOnWatchPage() && onPhoneScreen()) {
            // Capture full-player bounds BEFORE PiP CSS mutates the page.
            val params = pipParams()
            WebHost.pip(true)
            val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
            if (!entered) WebHost.pip(false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The screen can move between the phone and the car display without being recreated.
        WebHost.setCarScreen(!onPhoneScreen())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        WebHost.noteVisibilityChange()
        WebHost.inPip.value = isInPictureInPictureMode
        AppLog.add("VIEW", "Picture in picture=$isInPictureInPictureMode")
        if (!isInPictureInPictureMode && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            WebHost.setHold(false)
        }
        WebHost.pip(isInPictureInPictureMode)
        WebHost.refreshBackgroundQuality()
    }
}
