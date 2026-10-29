package com.aayoutube.app

import android.app.Presentation
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // Match AABrowser's trusted-host model: debug permits the desktop head-unit emulator;
    // release accepts only Android Auto / car-system hosts, not arbitrary apps.
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = VideoScreen(carContext)
    }
}

private class VideoScreen(carContext: CarContext) : Screen(carContext) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var display: VirtualDisplay? = null
    private var activeSurface: Surface? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var container: FrameLayout? = null
    private var fullscreenJob: Job? = null
    private var mediaController: ListenableFuture<MediaController>? = null
    private var density = 1f
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var contentArea = CarArea(0, 0, 1, 1)
    private val viewportUpdate = Runnable { applyViewport() }
    private val viewportSettled = Runnable { applyViewport() }

    private fun scheduleViewport() {
        main.removeCallbacks(viewportUpdate)
        main.removeCallbacks(viewportSettled)
        // Host overlays animate their reported bounds; debounce the transition, then
        // settle again after Android Auto's action strip finishes moving.
        main.postDelayed(viewportUpdate, 50)
        main.postDelayed(viewportSettled, 400)
    }

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = start(surfaceContainer)
        // Do not destroy or detach the WebView when the AA host replaces its Surface.
        // AABrowser keeps its virtual display alive for this exact reason.
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            if (activeSurface === surfaceContainer.surface) {
                runCatching { display?.surface = null }
                activeSurface = null
                AppLog.add("CAR", "Surface detached; player preserved")
            }
        }
        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = Rect(area)
            scheduleViewport()
        }
        override fun onStableAreaChanged(area: Rect) {
            stableArea = Rect(area)
            scheduleViewport()
        }
        override fun onClick(x: Float, y: Float) = tap(x, y)
        override fun onScroll(distanceX: Float, distanceY: Float) {
            if (WebHost.fullscreenView.value == null) {
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
                stop()
                main.removeCallbacks(viewportUpdate)
                main.removeCallbacks(viewportSettled)
                mediaController?.let { MediaController.releaseFuture(it) }
                mediaController = null
                scope.cancel()
            }
        })
    }

    /** The legacy CarAppService can start without any phone Activity. Keep the media
     * library linked to this WebView so Android Auto can control what is playing. */
    private fun connectMedia() {
        if (mediaController != null) return
        val token = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext.applicationContext, token).buildAsync()
        mediaController = future
        future.addListener({
            runCatching {
                if (mediaController === future)
                    future.get().sendCustomCommand(Commands.HELLO, Bundle.EMPTY)
            }.onFailure { AppLog.add("CAR", "Media controller connection failed") }
        }, MoreExecutors.directExecutor())
    }

    private fun Rect.toCarArea(): CarArea = CarArea(left, top, right, bottom)

    /** Layout changes are deliberately independent of Surface dimensions and WebView lifetime. */
    private fun applyViewport() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val area = CarViewport.contentArea(
            surfaceWidth, surfaceHeight,
            visibleArea?.toCarArea(), stableArea?.toCarArea(),
        )
        if (area == contentArea && container?.layoutParams != null) return
        contentArea = area
        container?.let { frame ->
            frame.layoutParams = FrameLayout.LayoutParams(area.width, area.height).apply {
                leftMargin = area.left
                topMargin = area.top
            }
            frame.requestLayout()
        }
        AppLog.add("CAR", "Viewport ${area.width}x${area.height} @ ${area.left},${area.top}")
    }

    private fun start(c: SurfaceContainer) {
        val surface = c.surface ?: return
        connectMedia()
        val width = c.width.coerceAtLeast(1)
        val height = c.height.coerceAtLeast(1)
        val dpi = c.dpi.coerceAtLeast(1)
        AppLog.add("CAR", "Surface ${width}x${height} dpi=$dpi")
        density = dpi / 160f

        // The Android Auto host replaces and resizes Surfaces, especially in split-screen.
        // Rebind the Surface while keeping the original Presentation and video decoder.
        val existing = display
        if (existing != null && presentation != null && container != null) {
            val reused = runCatching {
                existing.surface = surface
                if (surfaceWidth != width || surfaceHeight != height || surfaceDpi != dpi)
                    existing.resize(width, height, dpi)
            }.isSuccess
            if (reused) {
                surfaceWidth = width
                surfaceHeight = height
                surfaceDpi = dpi
                activeSurface = surface
                applyViewport()
                AppLog.add("CAR", "Surface rebound without detaching player")
                return
            }
        }

        stop()
        surfaceWidth = width
        surfaceHeight = height
        surfaceDpi = dpi
        activeSurface = surface
        val manager = carContext.getSystemService(DisplayManager::class.java)
        val vd = manager.createVirtualDisplay(
            "aay-car", width, height, dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        ) ?: return
        display = vd
        val p = Presentation(carContext, vd.display)
        val outer = FrameLayout(p.context)
        val frame = FrameLayout(p.context)
        outer.addView(frame, FrameLayout.LayoutParams(-1, -1))
        p.setContentView(outer, ViewGroup.LayoutParams(-1, -1))
        p.show()
        presentation = p
        root = outer
        container = frame
        applyViewport()
        WebHost.showOnCar(true)
        show(p, frame)
        fullscreenJob?.cancel()
        fullscreenJob = scope.launch {
            WebHost.fullscreenView.collect {
                if (presentation === p && container === frame) show(p, frame)
            }
        }
    }

    private fun show(p: Presentation, frame: FrameLayout) {
        frame.removeAllViews()
        val full = WebHost.fullscreenView.value
        if (full != null) {
            (full.parent as? ViewGroup)?.removeView(full)
            frame.addView(full, FrameLayout.LayoutParams(-1, -1))
        } else {
            frame.addView(WebHost.attach(p.context), FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun stop() {
        fullscreenJob?.cancel()
        fullscreenJob = null
        AppLog.add("CAR", "Display released")
        val frame = container
        container = null
        root = null
        activeSurface = null
        contentArea = CarArea(0, 0, 1, 1)
        if (frame != null) {
            WebHost.detach(frame)
            WebHost.showOnCar(false)
        }
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { display?.release() }
        display = null
    }

    private fun tap(x: Float, y: Float) {
        val target = container ?: return
        val (localX, localY) = CarViewport.localTouch(x, y, contentArea) ?: return
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, localX, localY, 0).let {
            target.dispatchTouchEvent(it)
            it.recycle()
        }
        main.postDelayed({
            if (target !== container) return@postDelayed
            val up = SystemClock.uptimeMillis()
            MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, localX, localY, 0).let {
                target.dispatchTouchEvent(it)
                it.recycle()
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
