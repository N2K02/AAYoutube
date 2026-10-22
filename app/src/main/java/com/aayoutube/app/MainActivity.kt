package com.aayoutube.app

import android.Manifest
import android.app.PictureInPictureParams
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * The screen. Runs in the ":web" process, next to the WebView. It also tells the media service
 * (other process) that it is there, so the service links up with this process.
 */
class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.init(this, "N2K02/AAYoutube", "AAYoutube")
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
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { WebHost.openUrl(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        WebHost.requestLink = { runOnUiThread { sayHello() } }
        AppLog.add("VIEW", if (onPhoneScreen()) "Screen on the phone" else "Screen on the car display")
        WebHost.setCarScreen(!onPhoneScreen())
        sayHello()
        WebHost.onActivityVisible(true)
        if (WebHost.mode.value != WebHost.PlayerMode.FULL) BrowseHost.resume()
    }

    override fun onPause() {
        super.onPause()
        WebHost.setHold(true)
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) WebHost.setHold(false)
        AppUpdater.onResume(this)
        updatePip()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) WebHost.onQuit = null
    }

    override fun onStop() {
        super.onStop()
        WebHost.requestLink = null
        releaseController()
        WebHost.onActivityVisible(false)
        BrowseHost.pause()
    }

    private fun sayHello() {
        if (controllerFuture != null) return
        val token = SessionToken(this, CarMode.serviceComponent(this))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                val controller = future.get()
                controller.sendCustomCommand(Commands.HELLO, Bundle.EMPTY)
            }
            // Kept until the screen stops: if it were released now, the service would shut down
            // again straight away and take the link to the web process with it.
        }, MoreExecutors.directExecutor())
    }

    /** Quit (menu): stop playback and the media service, then close the screen and the page. */
    fun quit() {
        AppLog.add("UI", "Quit")
        val future = controllerFuture
        if (future != null && future.isDone) {
            runCatching { future.get().sendCustomCommand(Commands.EXIT, Bundle.EMPTY) }
        } else {
            // Not connected yet: connect just to send Exit.
            val token = SessionToken(this, CarMode.serviceComponent(this))
            val f = MediaController.Builder(applicationContext, token).buildAsync()
            f.addListener({
                runCatching { f.get().sendCustomCommand(Commands.EXIT, Bundle.EMPTY) }
                MediaController.releaseFuture(f)
            }, MoreExecutors.directExecutor())
        }
        closeEverything()
    }

    private fun closeEverything() {
        WebHost.onQuit = null
        WebHost.cmd("pause")
        finishAndRemoveTask()
        // Give Exit a moment to reach the media service, then end this process (the WebView with it).
        android.os.Handler(mainLooper).postDelayed({
            WebHost.release()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun releaseController() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    // ---------------------------------------------------------------- picture in picture

    /** False when Android Auto is showing this screen on the car display (no picture in picture there). */
    private fun onPhoneScreen(): Boolean {
        val id = if (Build.VERSION.SDK_INT >= 30) display?.displayId else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        return id == null || id == android.view.Display.DEFAULT_DISPLAY
    }

    fun updatePip() {
        if (Build.VERSION.SDK_INT < 26) return
        val playing = WebHost.snapshot.value.playing
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(Features.current.autoPip && playing && WebHost.isOnWatchPage() && onPhoneScreen())
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && Features.current.autoPip && WebHost.snapshot.value.playing && WebHost.isOnWatchPage() && onPhoneScreen()) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
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
    }
}
