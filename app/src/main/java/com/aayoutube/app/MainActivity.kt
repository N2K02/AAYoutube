package com.aayoutube.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    companion object {
        // The phone and the car display can each have their own screen (singleTop, like AABrowser),
        // sharing one page. Only the last of them to hide may pause or hide the page.
        private val started = mutableSetOf<MainActivity>()
        private val resumed = mutableSetOf<MainActivity>()
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var carRoot: FrameLayout? = null
    private var carPage: FrameLayout? = null
    private var carFullscreen: FrameLayout? = null

    /** Like AABrowser, the car's WebView is hosted by the activity's native view tree. */
    private fun createNativeCarScreen() {
        val root = FrameLayout(this)
        val page = FrameLayout(this)
        val fullscreen = FrameLayout(this)
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        fullscreen.visibility = View.GONE
        carRoot = root
        carPage = page
        carFullscreen = fullscreen
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(root)
        page.addView(WebHost.attach(this), FrameLayout.LayoutParams(-1, -1))
        lifecycleScope.launch {
            WebHost.fullscreenView.collect { full ->
                if (carRoot !== root) return@collect
                fullscreen.removeAllViews()
                if (full != null) {
                    (full.parent as? ViewGroup)?.removeView(full)
                    fullscreen.addView(full, FrameLayout.LayoutParams(-1, -1))
                    fullscreen.visibility = View.VISIBLE
                } else {
                    fullscreen.visibility = View.GONE
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    WebHost.fullscreenView.value != null -> WebHost.exitFullscreen()
                    WebHost.isOnWatchPage() -> WebHost.enterMini()
                    !WebHost.goBack() -> WebHost.openUrl("https://m.youtube.com/")
                }
            }
        })
        AppLog.add("CAR", "Native activity WebView initialized (AABrowser-style)")
    }

    private fun releaseNativeCarScreen() {
        val page = carPage ?: return
        WebHost.detach(page)
        carFullscreen?.removeAllViews()
        carRoot = null
        carPage = null
        carFullscreen = null
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.init(this, "N2K02/AAYoutube", "AAYoutube")
        CarMode.resetToCarScreen(this)
        // On the car display a reopened video plays by itself; on the phone it waits for you.
        WebHost.setCarScreen(!onPhoneScreen())
        // Android Auto owns the car's usable viewport. Phone keeps edge-to-edge styling;
        // on a car display let WindowManager reserve the system/decor insets normally.
        if (onPhoneScreen()) enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Opened from the icon (not a link, not after rotation): start on YouTube Home.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) WebHost.goHomeIfIdle()
        handleIntent(intent)
        WebHost.onQuit = { runOnUiThread { closeEverything() } }
        if (onPhoneScreen()) setContent { AAYoutubeTheme { AppRoot(this) } }
        else createNativeCarScreen()
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
        started += this
        WebHost.requestLink = { runOnUiThread { sayHello() } }
        AppLog.add("VIEW", if (onPhoneScreen()) "Screen on the phone" else "Screen on the car display")
        WebHost.setCarScreen(!onPhoneScreen())
        sayHello()
        WebHost.onActivityVisible(true)
        if (WebHost.mode.value != WebHost.PlayerMode.FULL) BrowseHost.resume()
    }

    override fun onPause() {
        super.onPause()
        resumed -= this
        if (resumed.isEmpty()) WebHost.setHold(true)
    }

    override fun onResume() {
        super.onResume()
        resumed += this
        if (!isInPictureInPictureMode) WebHost.setHold(false)
        AppUpdater.onResume(this)
        updatePip()
    }

    override fun onDestroy() {
        super.onDestroy()
        started -= this
        resumed -= this
        releaseNativeCarScreen()
        if (isFinishing && started.isEmpty()) WebHost.onQuit = null
    }

    override fun onStop() {
        super.onStop()
        started -= this
        releaseController()
        val other = started.lastOrNull()
        if (other != null) {
            // Another screen is still showing the page: it keeps the link, the page stays visible.
            WebHost.requestLink = { other.runOnUiThread { other.sayHello() } }
            return
        }
        WebHost.requestLink = null
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
        val car = !onPhoneScreen()
        WebHost.setCarScreen(car)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, car)
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
