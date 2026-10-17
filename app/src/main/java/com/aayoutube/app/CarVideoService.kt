package com.aayoutube.app

import android.app.Presentation
import android.content.Intent
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
    // Personal sideloaded app: accept any host (Android Auto).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

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
    private var density = 1f

    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) = start(surfaceContainer)
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = stop()
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
                scope.cancel()
            }
        })
    }

    private fun start(c: SurfaceContainer) {
        stop()
        val surface = c.surface ?: return
        density = c.dpi / 160f
        val manager = carContext.getSystemService(DisplayManager::class.java)
        val vd = manager.createVirtualDisplay(
            "aay-car", c.width, c.height, c.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        ) ?: return
        display = vd
        val p = Presentation(carContext, vd.display)
        val frame = FrameLayout(p.context)
        p.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        p.show()
        presentation = p
        container = frame
        WebHost.showOnCar(true)
        show(p, frame)
        // YouTube's fullscreen player is a separate view: show it instead of the page while it is open.
        scope.launch { WebHost.fullscreenView.collect { show(p, frame) } }
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
        val frame = container
        container = null
        if (frame != null) {
            WebHost.detach(frame)
            WebHost.showOnCar(false)
        }
        runCatching { presentation?.dismiss() }
        presentation = null
        display?.release()
        display = null
    }

    private fun tap(x: Float, y: Float) {
        val target = container ?: return
        val down = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        main.postDelayed({
            val up = SystemClock.uptimeMillis()
            target.dispatchTouchEvent(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0))
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
