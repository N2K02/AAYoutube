package com.aayoutube.app

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.View
import android.view.MotionEvent
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
import com.google.android.apps.auto.sdk.CarActivity

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

    private var mediaController: CompatMediaConnection? = null
    private var playerContainer: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var carStarted = false
    private var carInput: LegacyCarInput? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private val refit = Runnable { WebHost.refitCarViewport() }

    // Android Auto recreates this CarActivity (new Surface, restored state)
    // while the previous instance is still alive. Each instance has its own
    // owner token so the old one's onStop/onDestroy cannot clear the new
    // one's projection, switch the page to background quality or steal the
    // player back into a window that is no longer on the car screen.
    private val carToken = "legacy-projection@" + Integer.toHexString(System.identityHashCode(this))
    private var inactiveLogged = false
    private val rebindWhenActive = Runnable {
        val frame = playerContainer ?: return@Runnable
        if (carStarted && WebHost.isActiveCarHost(carToken) && WebHost.fullscreenView.value == null)
            bindVideoToCarFrame(frame)
    }
    private val onBecameActive: () -> Unit = {
        handler.removeCallbacks(rebindWhenActive)
        // A host shutting down several instances stops this one shortly after.
        handler.postDelayed(rebindWhenActive, 1_200L)
    }
    private val renderProbe = Runnable {
        if (carStarted && WebHost.isActiveCarHost(carToken)) WebHost.logCarRenderProbe()
    }

    /** Frames decoded vs. painted, over the first seconds of a car bind. */
    private fun scheduleRenderProbes() {
        handler.removeCallbacks(renderProbe)
        longArrayOf(2_000L, 6_000L, 12_000L).forEach { handler.postDelayed(renderProbe, it) }
    }

    // CarActivity uses a virtual display; Activity/application context can
    // still resolve default display 0 even when its ViewRoot uses display 323.
    private fun projectionContext(fallback: android.content.Context, frame: View? = null):
        android.content.Context {
        val display = frame?.display ?: carWindow()?.decorView?.display ?: return fallback
        return runCatching {
            android.view.ContextThemeWrapper(
                fallback.createDisplayContext(display), R.style.Theme_AAYoutube
            )
        }.getOrElse {
            AppLog.add("CAR", "Projection display context failed: " + it.javaClass.simpleName)
            fallback
        }
    }

    private fun bindVideoToCarFrame(frame: FrameLayout) {
        // AndroidView.factory runs before the ViewRoot is attached. Do not
        // create Chromium on phone display 0 and then immediately transfer its
        // GPU surface to Android Auto display 420.
        if (!frame.isAttachedToWindow || frame.display == null) return
        if (!WebHost.isActiveCarHost(carToken)) {
            if (!inactiveLogged) AppLog.add("CAR", "Inactive car host " + carToken +
                " keeps its hands off the player owned by " + (WebHost.activeCarHost.value ?: "none"))
            inactiveLogged = true
            return
        }
        inactiveLogged = false
        // Never move a live renderer into a replacement car window: it decodes
        // and plays audio but paints black. A different window gets a new one.
        val player = WebHost.attachToCarWindow(projectionContext(frame.context, frame), frame)
        if (frame.childCount != 1 || frame.getChildAt(0) !== player) {
            frame.removeAllViews()
            frame.addView(player, FrameLayout.LayoutParams(-1, -1))
            // Never grant focus to Chromium as a side effect of opening
            // the video. WebView focus was opening Android Auto's keyboard
            // immediately, followed by the host stopping the projection.
            frame.isFocusableInTouchMode = true
            frame.requestFocus()
            scheduleRenderProbes()
        }
        val display = frame.display?.displayId ?: -1
        val childDisplay = player.display?.displayId ?: -1
        AppLog.add("CAR", "Projection root display=" + display +
            " player display=" + childDisplay +
            " size=" + frame.width + "x" + frame.height)
        carInput?.bind(player)
    }
    private val relink: () -> Unit = { handler.post { if (carStarted) connectMedia() } }

    override fun onCreate(bundle: Bundle?) {
        // Upstream AABrowser applies the window theme before CarActivity
        // initialization. Log BEFORE super to diagnose failing car launches.
        AppLog.add("CAR", "Video CarActivity launching")
        setTheme(R.style.Theme_AAYoutube)
        super.onCreate(bundle)
        CarMode.resetToCarScreen(this)
        savedController.performRestore(bundle)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        AppLog.add("CAR", "Legacy AABrowser projection activity created")
        // Projection must own the page before Compose creates/loads its WebView.
        // Otherwise ensureWeb() can briefly initialize in phone/background mode.
        // The newest car activity instance is the one Android Auto shows.
        WebHost.claimCarHost(carToken, onBecameActive)
        WebHost.showOnCar(true, carToken)
        WebHost.prepareForCarProjection()
        // Immediately transfer the web-process callback; an old activity
        // can stop before this new instance reaches onStart.
        WebHost.requestLink = relink

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
        // As in upstream MainCarActivity, avoid destroying the projected WebView
        // for transient automotive display configuration changes.
        runCatching {
            javaClass.getMethod("setIgnoreConfigChanges", Int::class.javaPrimitiveType)
                .invoke(this, 0xFFFF)
        }
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
        // Some SDK revisions expose a named public getter rather than a typed
        // no-argument method in the superclass.
        val named = runCatching {
            javaClass.getMethod("getInputManager").invoke(this) as? InputManager
        }.getOrNull()
        if (named != null) return named
        return CarActivity::class.java.declaredMethods.firstNotNullOfOrNull { method ->
            if (method.parameterCount != 0 || !method.name.contains("Input", ignoreCase = true)) null
            else runCatching {
                method.isAccessible = true
                method.invoke(this)
            }.getOrNull()?.takeIf { it.javaClass.name.contains("InputManager") }
        }
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
        val activeHost by WebHost.activeCarHost.collectAsState()
        val playing by WebHost.snapshot.collectAsState()
        val flags by Features.state.collectAsState()
        var controlsVisible by remember { mutableStateOf(true) }
        var controlsVersion by remember { mutableIntStateOf(0) }
        LaunchedEffect(controlsVisible, controlsVersion, flags.topMenuTimeoutSeconds) {
            val timeout = TopMenuPolicy.timeoutMillis(flags.topMenuTimeoutSeconds)
            if (controlsVisible && timeout != null) {
                delay(timeout)
                controlsVisible = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            key(version) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CarFrameLayout(context).also { frame ->
                            playerContainer = frame
                            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    AppLog.add("CAR", "Car frame attached display=" +
                                        (frame.display?.displayId ?: -1))
                                    if (playerContainer === frame) bindVideoToCarFrame(frame)
                                }
                                override fun onViewDetachedFromWindow(v: View) {
                                    AppLog.add("CAR", "Car frame detached")
                                }
                            })
                            frame.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                val width = right - left
                                val height = bottom - top
                                if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                                    lastWidth = width
                                    lastHeight = height
                                    AppLog.add("CAR", "Projection viewport=" + width + "x" + height)
                                    handler.post {
                                        if (playerContainer === frame) bindVideoToCarFrame(frame)
                                    }
                                    handler.removeCallbacks(refit)
                                    handler.postDelayed(refit, 180L)
                                    scheduleRenderProbes()
                                }
                            }
                            // First WebView creation belongs in onViewAttachedToWindow,
                            // when Android Auto's virtual display is known.
                        }
                    },
                    update = { frame ->
                        if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)
                    },
                    onRelease = { frame ->
                        WebHost.detach(frame)
                        if (playerContainer === frame) playerContainer = null
                    },
                )
            }
            val fullscreenView = full
            // An inactive (being replaced) car window must not take the
            // fullscreen video view away from the window on the car screen.
            if (fullscreenView != null && activeHost == carToken) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CarFrameLayout(context).also { frame ->
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
                        .revealTopMenuOnSwipeDown { controlsVisible = true; controlsVersion++ }
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
        AppLog.add("CAR", "Video projection onStart")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        carStarted = true
        configureLegacyCarInput()
        val registered = WebHost.resumeCarHost(carToken, onBecameActive)
        if (!registered) return
        WebHost.showOnCar(true, carToken)
        if (!WebHost.isActiveCarHost(carToken)) return
        WebHost.prepareForCarProjection()
        WebHost.setHold(false)
        WebHost.requestLink = relink
        connectMedia()
        attachIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        AppLog.add("CAR", "Video projection onResume")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (WebHost.isActiveCarHost(carToken)) WebHost.setHold(false)
        updateCarWindow()
        attachIfNeeded()
        handler.removeCallbacks(refit)
        handler.postDelayed(refit, 180L)
        scheduleRenderProbes()
    }

    private fun attachIfNeeded() {
        playerContainer?.let { frame ->
            if (WebHost.fullscreenView.value == null) bindVideoToCarFrame(frame)
        }
    }

    /** The native media process remains the sole owner of Android Auto media commands. */
    private fun connectMedia() {
        if (!carStarted || mediaController != null) return
        mediaController = CompatMediaConnection(this) {
            it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)
        }.also { it.connect() }
    }
    override fun onPause() {
        AppLog.add("CAR", "Video projection onPause")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // Do not pause YouTube: a temporary car window focus loss must preserve audio.
        super.onPause()
    }

    override fun onStop() {
        AppLog.add("CAR", "Video projection onStop; frameAttached=" +
            (playerContainer?.isAttachedToWindow ?: false))
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        carStarted = false
        handler.removeCallbacksAndMessages(null)
        lastWidth = 0
        lastHeight = 0
        carInput?.stop()
        carInput = null
        // Only this instance's ownership ends here. A newer car window keeps
        // the player, its car layout and its foreground video quality.
        WebHost.releaseCarHost(carToken)
        playerContainer?.let { WebHost.detach(it) }
        WebHost.showOnCar(false, carToken)
        if (WebHost.requestLink === relink) {
            WebHost.requestLink = null
            MainActivity.restorePhoneLink()
        }
        mediaController?.disconnect()
        mediaController = null
        // A still-running phone Compose window can reclaim the existing WebView
        // (no-op while another car window is still projecting it).
        WebHost.requestReattachment()
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.add("CAR", "Video projection onDestroy")
        carStarted = false
        if (WebHost.requestLink === relink) {
            WebHost.requestLink = null
            MainActivity.restorePhoneLink()
        }
        handler.removeCallbacksAndMessages(null)
        WebHost.releaseCarHost(carToken)
        WebHost.showOnCar(false, carToken)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        carInput?.stop()
        carInput = null
        playerContainer?.let { WebHost.detach(it) }
        playerContainer = null
        mediaController?.disconnect()
        mediaController = null
        WebHost.requestReattachment()
        super.onDestroy()
    }
}

/**
 * Android Auto input is NOT the same as WebView/window focus.
 *
 * On the head unit a playback-only WebView receives focus when opened.
 * Calling InputManager.startInput on that event opens the car keyboard and
 * causes some projection hosts to stop the video activity. AABrowser only
 * starts car input after an explicit user selection. Do the same here:
 * the user must TAP an actual HTML text field before starting car input.
 */
private class LegacyCarInput(private val manager: InputManager) : CarEditable {
    private var target: WebView? = null
    private var active = false

    fun bind(web: WebView) {
        if (target === web && owners[web] === this) return
        stop()
        target = web
        owners[web] = this
        // Do not listen to WebView focus changes. Video player focus is not
        // a text-edit request. Return false to preserve swipe/playback touches.
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                web.postDelayed({
                    if (target === web && web.isAttachedToWindow) {
                        web.evaluateJavascript(EDITABLE_FOCUS_SCRIPT) { result ->
                            if (target !== web || !web.isAttachedToWindow) return@evaluateJavascript
                            if (result == "true") start()
                            else stopInputOnly()
                        }
                    }
                }, 220L)
            }
            false
        }
        AppLog.add("CAR", "Car input bound; keyboard idle until text field tapped")
    }

    private fun start() {
        if (active) return
        AppLog.add("CAR", "Explicit editable tap: requesting car keyboard")
        runCatching {
            manager.startInput(this)
            active = true
        }.onFailure { AppLog.add("CAR", "Car keyboard failed: " + it.javaClass.simpleName) }
    }

    private fun stopInputOnly() {
        if (!active) return
        active = false
        runCatching { manager.stopInput() }
    }

    fun stop() {
        stopInputOnly()
        // An old car instance stopping after a newer one bound the same
        // WebView must not remove the newer instance's touch listener.
        target?.let { web ->
            if (owners[web] === this) {
                web.setOnTouchListener(null)
                owners.remove(web)
            }
        }
        target = null
    }

    companion object {
        private val owners = java.util.WeakHashMap<WebView, LegacyCarInput>()
        // Traverse YouTube's shadow roots; autofocus on a video is excluded.
        // This is evaluated only after ACTION_UP, never on window/view focus.
        private val EDITABLE_FOCUS_SCRIPT = """
            (function () {
              var e = document.activeElement;
              while (e && e.shadowRoot && e.shadowRoot.activeElement)
                e = e.shadowRoot.activeElement;
              if (!e) return false;
              var tag = (e.tagName || '').toLowerCase();
              if (tag === 'textarea' || e.isContentEditable) return true;
              if (tag !== 'input') return false;
              var type = (e.type || 'text').toLowerCase();
              return !/^(button|submit|reset|checkbox|radio|hidden|file|range|color|image)$/.test(type);
            })()
        """.trimIndent()
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo?): InputConnection? {
        val web = target ?: return null
        web.onCheckIsTextEditor()
        var connection = web.onCreateInputConnection(editorInfo)
        if (connection == null) {
            web.requestFocus()
            connection = web.onCreateInputConnection(editorInfo)
        }
        editorInfo?.apply {
            if (inputType == EditorInfo.TYPE_NULL) inputType =
                EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        }
        return connection ?: BaseInputConnection(web, true)
    }

    override fun setCarEditableListener(carEditableListener: CarEditableListener?) = Unit
    override fun setInputEnabled(enabled: Boolean) = Unit
}
