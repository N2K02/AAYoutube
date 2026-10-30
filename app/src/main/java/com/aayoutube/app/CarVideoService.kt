package com.aayoutube.app

import android.app.Presentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebView
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows the YouTube page on the car screen (personal use, sideloaded).
 *
 * Android Auto gives navigation apps a plain drawing surface. The page is drawn onto it through a
 * virtual display. Runs in the ":web" process, so it uses the same WebView as the phone screen
 * (only one of the two shows it at a time). Audio and the media session are unchanged.
 */
class CarVideoService : CarAppService() {
    // Follow AABrowser: DHU/debug hosts unrestricted; release trusts known car hosts.
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VideoScreen(carContext)
    }
}

private class VideoScreen(carContext: CarContext) : Screen(carContext) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var container: FrameLayout? = null
    private var fullscreenJob: Job? = null
    private var rendererJob: Job? = null
    private var density = 1f
    private var rawWidth = 800
    private var rawHeight = 480
    private var rawDpi = 160
    private var visibleArea: CarSurfaceBounds? = null
    private var stableArea: CarSurfaceBounds? = null
    private var activeSurface = false
    private var mediaController: CompatMediaConnection? = null
    private val carOwner = "wide-surface"
    private val releaseAfterDisconnect = Runnable { if (!activeSurface) stop() }
    private val relink: () -> Unit = { main.post { if (display != null) connectMedia() } }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = start(surfaceContainer)
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            activeSurface = false
            runCatching { display?.surface = null }
            main.removeCallbacks(releaseAfterDisconnect)
            main.postDelayed(releaseAfterDisconnect, 5_000L)
            AppLog.add("CAR", "Wide car surface disconnected; retaining video for reattachment")
        }
        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = CarSurfaceBounds(area.left, area.top, area.right, area.bottom)
            updateViewport()
        }
        override fun onStableAreaChanged(area: Rect) {
            stableArea = CarSurfaceBounds(area.left, area.top, area.right, area.bottom)
            updateViewport()
        }
        override fun onClick(x: Float, y: Float) = tap(x, y)
        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (WebHost.fullscreenView.value == null && !WebHost.isOnWatchPage()) {
                WebHost.scrollBy((distanceX / density).toDouble(), (distanceY / density).toDouble())
            }
        }
    }

    init {
        carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(callback)
        scope.launch {
            WebHost.snapshot.map { it.playing }.distinctUntilChanged().collect { invalidate() }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                main.removeCallbacksAndMessages(null)
                stop()
                scope.cancel()
            }
        })
    }

    private fun connectMedia() {
        if (display == null || mediaController != null) return
        mediaController = CompatMediaConnection(carContext) {
            it.transportControls.sendCustomAction(Commands.HELLO, Bundle.EMPTY)
        }.also { it.connect() }
    }

    private fun start(c: SurfaceContainer) {
        val surface = c.surface?.takeIf { it.isValid } ?: return
        main.removeCallbacks(releaseAfterDisconnect)
        rawWidth = c.width.coerceAtLeast(1)
        rawHeight = c.height.coerceAtLeast(1)
        rawDpi = c.dpi.coerceAtLeast(1)
        density = rawDpi / 160f
        activeSurface = true
        // Preserve the decoder/WebView across Surface replacements, as AABrowser does.
        val old = display
        if (old != null && presentation != null) {
            if (runCatching {
                old.surface = surface
                old.resize(rawWidth, rawHeight, rawDpi)
                updateViewport()
                container?.let { frame -> presentation?.let { p -> show(p, frame, forceRefresh = true) } }
            }.isSuccess) {
                AppLog.add("CAR", "Wide surface replaced without video reload")
                return
            }
            stop()
            activeSurface = true
        }
        val manager = carContext.getSystemService(DisplayManager::class.java)
        val vd = runCatching {
            manager.createVirtualDisplay("aay-car", rawWidth, rawHeight, rawDpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        }.getOrNull() ?: run { activeSurface = false; return }
        val p = Presentation(carContext, vd.display)
        val root = CarFrameLayout(p.context)
        val frame = FrameLayout(p.context)
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        if (runCatching { p.setContentView(root, ViewGroup.LayoutParams(-1, -1)); p.show() }.isFailure) {
            runCatching { p.dismiss() }
            vd.release()
            activeSurface = false
            return
        }
        display = vd
        presentation = p
        container = frame
        WebHost.showOnCar(true, carOwner)
        WebHost.setHold(false)
        WebHost.requestLink = relink
        updateViewport()
        show(p, frame)
        connectMedia()
        fullscreenJob?.cancel()
        fullscreenJob = scope.launch {
            WebHost.fullscreenView.collect {
                if (presentation === p && container === frame) show(p, frame)
            }
        }
        // A renderer crash disposes the old WebView even if Android Auto keeps
        // the same Surface. Reattach on WebHost generation changes, not just
        // on Surface recreation or fullscreen changes.
        rendererJob?.cancel()
        rendererJob = scope.launch {
            WebHost.webVersion.drop(1).collect {
                if (presentation === p && container === frame) show(p, frame, forceRefresh = true)
            }
        }
        AppLog.add("CAR", "Wide video screen attached")
    }

    private fun updateViewport() {
        val frame = container ?: return
        val area = CarSurfacePolicy.viewport(rawWidth, rawHeight, visibleArea, stableArea)
        frame.layoutParams = FrameLayout.LayoutParams(area.width, area.height,
            Gravity.TOP or Gravity.START).apply { leftMargin = area.left; topMargin = area.top }
    }

    private fun show(p: Presentation, frame: FrameLayout, forceRefresh: Boolean = false) {
        val full = WebHost.fullscreenView.value
        if (full != null) {
            if (frame.childCount == 1 && frame.getChildAt(0) === full) return
            (full.parent as? ViewGroup)?.removeView(full)
            frame.removeAllViews()
            frame.addView(full, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val existing = frame.getChildAt(0) as? WebView
        if (!forceRefresh && existing != null) return
        val current = WebHost.attach(p.context)
        // A redundant host callback must not detach and recreate video frames.
        if (existing === current) return
        frame.removeAllViews()
        frame.addView(current, FrameLayout.LayoutParams(-1, -1))
    }

    private fun stop() {
        main.removeCallbacks(releaseAfterDisconnect)
        activeSurface = false
        fullscreenJob?.cancel()
        fullscreenJob = null
        rendererJob?.cancel()
        rendererJob = null
        AppLog.add("CAR", "Surface released")
        val frame = container
        container = null
        if (frame != null) {
            WebHost.detach(frame)
            WebHost.showOnCar(false, carOwner)
            WebHost.requestReattachment()
        }
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
        mediaController?.disconnect()
        mediaController = null
        if (WebHost.requestLink === relink) {
            WebHost.requestLink = null
            MainActivity.restorePhoneLink()
        }
    }

    private fun tap(x: Float, y: Float) {
        if (!activeSurface) return
        val area = CarSurfacePolicy.viewport(rawWidth, rawHeight, visibleArea, stableArea)
        if (!area.contains(x, y)) return
        val target = container ?: return
        val down = SystemClock.uptimeMillis()
        val start = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x - area.left, y - area.top, 0)
        try { target.dispatchTouchEvent(start) } finally { start.recycle() }
        main.postDelayed({
            val up = SystemClock.uptimeMillis()
            if (activeSurface && container === target) {
                val finish = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x - area.left, y - area.top, 0)
                try { target.dispatchTouchEvent(finish) } finally { finish.recycle() }
            }
        }, 60)
    }

    private fun icon(res: Int) =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun action(res: Int, title: String, run: () -> Unit) =
        Action.Builder().setIcon(icon(res)).setTitle(title).setOnClickListener { run() }.build()

    override fun onGetTemplate(): Template {
        val playing = WebHost.snapshot.value.playing
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_car_back, "Back") { WebHost.goBack() })
            .addAction(action(R.drawable.ic_car_home, "Home") { WebHost.openUrl("https://m.youtube.com/") })
            .addAction(
                if (playing) action(R.drawable.ic_car_pause, "Pause") { WebHost.cmd("pause") }
                else action(R.drawable.ic_car_play, "Play") { WebHost.cmd("play") },
            )
            .addAction(action(R.drawable.ic_car_next, "Next") { WebHost.cmd("next") })
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
