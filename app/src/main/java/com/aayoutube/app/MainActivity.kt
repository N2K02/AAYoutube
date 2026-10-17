package com.aayoutube.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
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
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
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
        sayHello()
        WebHost.onActivityVisible(true)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.onResume(this)
        updatePip()
    }

    override fun onStop() {
        super.onStop()
        WebHost.requestLink = null
        releaseController()
        WebHost.onActivityVisible(false)
    }

    private fun sayHello() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                val controller = future.get()
                controller.sendCustomCommand(Commands.HELLO, Bundle.EMPTY)
            }
            // The link is made by the service; the controller is not needed any more.
            releaseController()
        }, MoreExecutors.directExecutor())
    }

    private fun releaseController() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    // ---------------------------------------------------------------- picture in picture

    fun updatePip() {
        if (Build.VERSION.SDK_INT < 26) return
        val playing = WebHost.snapshot.value.playing
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(Features.current.autoPip && playing)
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && Features.current.autoPip && WebHost.snapshot.value.playing) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        WebHost.inPip.value = isInPictureInPictureMode
        WebHost.pip(isInPictureInPictureMode)
    }
}
