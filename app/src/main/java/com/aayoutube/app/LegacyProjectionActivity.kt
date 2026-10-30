package com.aayoutube.app

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.BaseInputConnection
import android.webkit.WebView
import com.google.android.gms.car.input.CarEditable
import com.google.android.gms.car.input.CarEditableListener
import com.google.android.gms.car.input.InputManager
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    private var carStarted = false
    private var carInput: LegacyCarInput? = null
    private val relink: () -> Unit = { handler.post { if (carStarted) connectMedia() } }

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
        // Legacy CarActivity owns a separate window; configure its actual decor tree.
        carWindow()?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        updateCarWindow()
        configureLegacyCarInput()
    }

    /**
     * AABrowser's legacy SDK exposes its car input manager under different method
     * names in different archived builds. Resolve it without binding to an
     * unstable obfuscated API; native WebView touch remains the fallback.
     */
    private fun findCarInputManager(): Any? {
        var type: Class<*>? = javaClass
        while (type != null && type != Any::class.java) {
            val found = type.declaredMethods.firstOrNull { method ->
                method.parameterCount == 0 &&
                    method.returnType.name.endsWith(".input.InputManager")
            }
            if (found != null) {
                val value = runCatching {
                    found.isAccessible = true
                    found.invoke(this)
                }.getOrNull()
                if (value != null) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun configureLegacyCarInput() {
        val input = findCarInputManager() as? InputManager
        carInput?.stop()
        carInput = input?.let { LegacyCarInput(it) }
        AppLog.add("CAR", if (input == null) {
            "Legacy input manager unavailable; retaining WebView touch input"
        } else {
            "Legacy car input manager detected: " + input.javaClass.simpleName
        })
    }

    /** Match AABrowser's legacy CarActivity window handling, not the phone window. */
    private fun carWindow(): Window? = runCatching {
        javaClass.getMethod("c").invoke(this) as? Window
    }.getOrNull()

    private fun updateCarWindow() {
        runCatching {
            val window = carWindow() ?: return
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }.onFailure { AppLog.add("CAR", "Car window insets unavailable: " + it.javaClass.simpleName) }
    }

    /** One shared player, matching AABrowser's projected Compose screen technique. */
    @Composable
    private fun LegacyProjectionScreen() {
        val version by WebHost.webVersion.collectAsState()
        val full by WebHost.fullscreenView.collectAsState()
        val playing by WebHost.snapshot.collectAsState()
        var controlsVisible by remember { mutableStateOf(true) }
        var controlsVersion by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlsVisible, controlsVersion) {
            if (controlsVisible) {
                delay(3_000)
                controlsVisible = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            key(version) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).also { frame ->
                            playerContainer = frame
                            val web = WebHost.attach(context)
                            frame.addView(web, FrameLayout.LayoutParams(-1, -1))
                            carInput?.bind(web)
                        }
                    },
                    update = { frame ->
                        if (frame.childCount == 0 && WebHost.fullscreenView.value == null) {
                            val web = WebHost.attach(frame.context)
                            frame.addView(web, FrameLayout.LayoutParams(-1, -1))
                            carInput?.bind(web)
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
            if (!controlsVisible) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(28.dp)
                        .clickable { controlsVisible = true; controlsVersion++ }
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                        .background(Color(0xCC252525)).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarControl("‹ Back") { WebHost.goBack(); controlsVersion++ }
                    Spacer(Modifier.width(8.dp))
                    CarControl("⌂ Home") { WebHost.openUrl("https://m.youtube.com/"); controlsVersion++ }
                    Spacer(Modifier.width(8.dp))
                    CarControl(if (playing.playing) "Ⅱ Pause" else "▶ Play") {
                        WebHost.cmd(if (playing.playing) "pause" else "play")
                        controlsVersion++
                    }
                    Spacer(Modifier.width(8.dp))
                    CarControl("⏭ Next") { WebHost.cmd("next"); controlsVersion++ }
                }
            }
        }
    }

    @Composable
    private fun CarControl(label: String, action: () -> Unit) {
        Box(
            modifier = Modifier.background(Color(0xFF383838), RoundedCornerShape(24.dp))
                .clickable(onClick = action)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        carStarted = true
        configureLegacyCarInput()
        WebHost.showOnCar(true, "legacy-projection")
        WebHost.requestLink = relink
        connectMedia()
        attachIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        WebHost.setHold(false)
        updateCarWindow()
        attachIfNeeded()
    }

    private fun attachIfNeeded() {
        playerContainer?.let { frame ->
            if (frame.childCount == 0 && WebHost.fullscreenView.value == null) {
                val web = WebHost.attach(frame.context)
                frame.addView(web, FrameLayout.LayoutParams(-1, -1))
                carInput?.bind(web)
            }
        }
    }

    /** The native media process remains the sole owner of Android Auto media commands. */
    private fun connectMedia() {
        if (!carStarted || mediaController != null) return
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
        carStarted = false
        handler.removeCallbacksAndMessages(null)
        carInput?.stop()
        carInput = null
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
        carStarted = false
        if (WebHost.requestLink === relink) WebHost.requestLink = null
        WebHost.showOnCar(false, "legacy-projection")
        handler.removeCallbacksAndMessages(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        carInput?.stop()
        carInput = null
        playerContainer?.let { WebHost.detach(it) }
        playerContainer = null
        mediaController?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onDestroy()
    }
}

/** Legacy Android Auto IME bridge, based on AABrowser's CarInputManager contract. */
private class LegacyCarInput(private val manager: InputManager) : CarEditable {
    private var target: WebView? = null

    fun bind(web: WebView) {
        if (target === web) return
        stop()
        target = web
        // The car IME is requested only for a focused WebView, not for video touches.
        web.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused) web.post { if (target === web) start() } else stopInputOnly()
        }
        if (web.hasFocus()) web.post { if (target === web) start() }
    }

    private fun start() {
        runCatching { manager.startInput(this) }
    }

    private fun stopInputOnly() {
        runCatching { manager.stopInput() }
    }

    fun stop() {
        stopInputOnly()
        target?.onFocusChangeListener = null
        target = null
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo?): InputConnection? {
        val web = target ?: return null
        val connection = web.onCreateInputConnection(editorInfo)
        editorInfo?.imeOptions = (editorInfo?.imeOptions ?: 0) or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return connection ?: BaseInputConnection(web, true)
    }

    override fun setCarEditableListener(carEditableListener: CarEditableListener?) = Unit
    override fun setInputEnabled(enabled: Boolean) = Unit
}
