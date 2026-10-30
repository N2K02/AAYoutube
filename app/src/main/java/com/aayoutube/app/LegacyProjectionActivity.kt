package com.aayoutube.app

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.apps.auto.sdk.CarActivity
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * AABrowser-style CarActivity with Compose view-tree owners, adapted to AAYouTube's
 * single WebView, media session and shared lifecycle. The host supplies the car window.
 * No extra Spotify, local player, weather or multi-tab browser is instantiated.
 */
class LegacyProjectionActivity : CarActivity(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    private var mediaController: ListenableFuture<MediaController>? = null
    private var playerContainer: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val relink: () -> Unit = { handler.post { connectMedia() } }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        CarMode.resetToCarScreen(this)
        savedController.performRestore(bundle)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        AppLog.add("CAR", "Legacy AABrowser projection activity created")

        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LegacyProjectionActivity)
            setViewTreeViewModelStoreOwner(this@LegacyProjectionActivity)
            setViewTreeSavedStateRegistryOwner(this@LegacyProjectionActivity)
            setContent { AAYoutubeTheme { LegacyProjectionScreen() } }
        }
        setContentView(compose)
    }

    /** One shared player, matching AABrowser's projected Compose screen technique. */
    @Composable
    private fun LegacyProjectionScreen() {
        val version by WebHost.webVersion.collectAsState()
        val full by WebHost.fullscreenView.collectAsState()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            key(version) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).also { frame ->
                            playerContainer = frame
                            frame.addView(WebHost.attach(context), FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    update = { frame ->
                        if (frame.childCount == 0 && WebHost.fullscreenView.value == null) {
                            frame.addView(WebHost.attach(frame.context), FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { frame ->
                        WebHost.detach(frame)
                        if (playerContainer === frame) playerContainer = null
                    },
                )
            }
            val fullscreenView = full
            if (fullscreenView != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).also { frame ->
                            (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                            frame.addView(fullscreenView, FrameLayout.LayoutParams(-1, -1))
                        }
                    },
                    onRelease = { it.removeAllViews() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        WebHost.showOnCar(true, "legacy-projection")
        WebHost.requestLink = relink
        connectMedia()
        attachIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        WebHost.setHold(false)
        attachIfNeeded()
    }

    private fun attachIfNeeded() {
        playerContainer?.let { frame ->
            if (frame.childCount == 0 && WebHost.fullscreenView.value == null) {
                frame.addView(WebHost.attach(frame.context), FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    /** The native media process remains the sole owner of Android Auto media commands. */
    private fun connectMedia() {
        if (mediaController != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        mediaController = future
        future.addListener({
            runCatching {
                if (mediaController === future) {
                    future.get().sendCustomCommand(Commands.HELLO, Bundle.EMPTY)
                }
            }.onFailure {
                AppLog.add("CAR", "Legacy media connection failed: " + it.javaClass.simpleName)
                if (mediaController === future) {
                    mediaController = null
                    MediaController.releaseFuture(future)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // Do not pause YouTube: a temporary car window focus loss must preserve audio.
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        playerContainer?.let { WebHost.detach(it) }
        WebHost.showOnCar(false, "legacy-projection")
        if (WebHost.requestLink === relink) WebHost.requestLink = null
        mediaController?.let { MediaController.releaseFuture(it) }
        mediaController = null
        // A still-running phone Compose window can reclaim the existing WebView.
        WebHost.requestReattachment()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        playerContainer?.let { WebHost.detach(it) }
        playerContainer = null
        mediaController?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onDestroy()
    }
}
