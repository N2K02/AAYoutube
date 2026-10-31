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

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveUpdater.init(this)
        CarMode.resetToCarScreen(this)
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
        WebHost.onQuit = { runOnUiThread { closeEverything() } }
        setContent { AAYoutubeTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A reused activity can move to another car display.
        WebHost.setCarScreen(!onPhoneScreen())
        handleIntent(intent)
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
        if (isFinishing && started.isEmpty()) WebHost.onQuit = null
    }

    override fun onStop() {
        super.onStop()
        started -= this
        releaseController()
        val other = started.lastOrNull()
        if (WebHost.requestLink === relinkPhone) {
            // Never remove a link now owned by the projection activity.
            WebHost.requestLink = null
            if (other != null) restorePhoneLink()
        }
        if (other != null) return
        WebHost.onActivityVisible(false)
        BrowseHost.pause()
    }

    private fun sayHello() {
        if (compatConnection != null) return
        compatConnection = CompatMediaConnection(this) {
            it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)
        }.also { it.connect() }
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
        if (WebHost.pipSourceRect(bounds)) builder.setSourceRectHint(bounds)
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
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ auto-enter is driven by PiP params; do NOT switch CSS
        // into PiP early if the launcher ultimately declines the transition.
        if (Build.VERSION.SDK_INT in 26..30 && Features.current.autoPip &&
            watchTabVisible && WebHost.snapshot.value.playing &&
            WebHost.isOnWatchPage() && onPhoneScreen()) {
            WebHost.pip(true)
            val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
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
