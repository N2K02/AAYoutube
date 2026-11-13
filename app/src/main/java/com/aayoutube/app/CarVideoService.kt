package com.aayoutube.app

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.MotionEvent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.SearchTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Experimental AAMaKer-style surface entry, using our source-built player UI. */
class CarVideoService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = CarVideoScreen(carContext)
    }
}

/** One GPU-backed display, one presentation and one existing WebHost player. */
internal class CarVideoScreen(context: CarContext) : Screen(context), SurfaceCallback,
    DefaultLifecycleObserver {
    private val appManager = context.getCarService(AppManager::class.java)
    private var display: VirtualDisplay? = null
    private var presentation: CarVideoPresentation? = null
    private var surface: android.view.Surface? = null
    private var width = 0
    private var height = 0
    private var destroyed = false

    init {
        lifecycle.addObserver(this)
        appManager.setSurfaceCallback(this)
    }

    override fun onGetTemplate(): Template = NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(
            Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(
                carContext, android.R.drawable.ic_menu_search)).build())
                .setOnClickListener { screenManager.push(CarVideoSearchScreen(carContext)) }.build()
        ).build())
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val output = container.surface ?: return
        if (destroyed) { output.release(); return }
        releaseSurface(keep = output)
        val geometry = CarSurfacePolicy.geometry(container.width, container.height, container.dpi)
        if (geometry == null || !output.isValid) {
            output.release()
            AppLog.add("CAR", "AndroidX surface rejected: invalid geometry or output")
            return
        }
        surface = output
        try {
            val manager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val created = manager.createVirtualDisplay(
                "AAYoutube car", geometry.width, geometry.height, geometry.dpi, output,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            ) ?: error("Virtual display unavailable")
            display = created
            surface = output
            width = geometry.width
            height = geometry.height
            presentation = CarVideoPresentation(carContext, created.display)
            presentation?.show()
            AppLog.add("CAR", "AndroidX surface ready display=${created.display.displayId}" +
                " size=${width}x${height} dpi=${geometry.dpi}")
        } catch (failure: Exception) {
            releaseSurface()
            AppLog.add("CAR", "AndroidX surface failed: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        // A delayed teardown for an old surface must not close its replacement.
        if (container.surface === surface) releaseSurface()
    }

    override fun onClick(x: Float, y: Float) {
        val point = CarSurfacePolicy.point(x, y, width, height) ?: return
        val now = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, point.first, point.second, now, now)
        touch(MotionEvent.ACTION_UP, point.first, point.second, now, now + 16)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!distanceX.isFinite() || !distanceY.isFinite()) return
        val end = CarSurfacePolicy.point(width / 2f - distanceX, height / 2f - distanceY,
            width, height) ?: return
        val now = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, width / 2f, height / 2f, now, now)
        touch(MotionEvent.ACTION_MOVE, end.first, end.second, now, now + 16)
        touch(MotionEvent.ACTION_UP, end.first, end.second, now, now + 32)
    }

    private fun touch(action: Int, x: Float, y: Float, down: Long, time: Long) {
        val target = presentation ?: return
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        appManager.setSurfaceCallback(null)
        releaseSurface()
        lifecycle.removeObserver(this)
    }

    private fun releaseSurface(keep: android.view.Surface? = null) {
        // Detach the WebView before retiring its display and our host-supplied Surface handle.
        try { presentation?.release() } finally {
            presentation = null
            display?.release()
            display = null
            surface?.takeUnless { it === keep }?.release()
            surface = null
            width = 0
            height = 0
        }
    }
}

/** Host-owned search keyboard replaces the removed proprietary InputManager protocol. */
internal class CarVideoSearchScreen(context: CarContext) : Screen(context) {
    override fun onGetTemplate(): Template = SearchTemplate.Builder(
        object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                val query = searchText.trim()
                if (query.isEmpty()) return
                WebHost.openUrl("https://m.youtube.com/results?search_query=" + android.net.Uri.encode(query))
                screenManager.pop()
            }
        }
    ).setHeaderAction(Action.BACK).setSearchHint("Search YouTube").build()
}
