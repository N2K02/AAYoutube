package com.aayoutube.app

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Modern AndroidX Car App Library entry for fullscreen YouTube projection.
 *
 * Uses NavigationTemplate (the only Car App template that exposes a raw surface)
 * with SurfaceCallback to receive the head-unit's display surface. A VirtualDisplay
 * is created on that surface, and a [FullscreenCarPresentation] renders the YouTube
 * WebView inside it — identical in concept to AABrowser-Motion's FullscreenCarService.
 *
 * This runs alongside the legacy [LegacyProjectionService] (CATEGORY_PROJECTION);
 * Android Auto picks whichever entry it supports on the connected head unit.
 */
class FullscreenCarService : CarAppService() {

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            YouTubeSurfaceScreen(carContext)
    }

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    // -------------------------------------------------------------------------

    private class YouTubeSurfaceScreen(
        carContext: CarContext
    ) : Screen(carContext), SurfaceCallback {

        private val handler = Handler(Looper.getMainLooper())
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: FullscreenCarPresentation? = null
        private var hostSurface: Surface? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var surfaceDpi = 0
        private var visibleArea: Rect? = null

        // Scroll state
        private var pointerX = 0f
        private var pointerY = 0f
        private var scrollDownTime = 0L
        private var lastScrollTime = 0L
        private var scrollInProgress = false

        private val carToken =
            "fullscreen-car@" + Integer.toHexString(System.identityHashCode(this))

        private val finishScroll = object : Runnable {
            override fun run() {
                if (!scrollInProgress) return
                val now = SystemClock.uptimeMillis()
                val remaining = lastScrollTime + SCROLL_END_DELAY_MS - now
                if (remaining > 0) {
                    handler.postDelayed(this, remaining)
                    return
                }
                dispatchMotion(scrollDownTime, now, MotionEvent.ACTION_UP, pointerX, pointerY)
                scrollInProgress = false
            }
        }

        init {
            getCarContext().getCarService(AppManager::class.java).setSurfaceCallback(this)

            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    Log.i(TAG, "screen onStart token=$carToken")
                    WebHost.claimCarHost(carToken) {
                        handler.post { rebindToPresentation() }
                    }
                    WebHost.showOnCar(true, carToken)
                    WebHost.prepareForCarProjection()
                    WebHost.setHold(false)
                    PlaybackService.carAttached(getCarContext(), true)
                    rebindToPresentation()
                }

                override fun onStop(owner: LifecycleOwner) {
                    Log.i(TAG, "screen onStop token=$carToken playing=${presentation?.isMediaPlaying()}")
                    WebHost.releaseCarHost(carToken)
                    WebHost.showOnCar(false, carToken)
                    PlaybackService.carAttached(getCarContext(), false)
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    Log.i(TAG, "screen onDestroy token=$carToken")
                    handler.removeCallbacksAndMessages(null)
                    releaseDisplay()
                }
            })
        }

        override fun onGetTemplate(): Template =
            NavigationTemplate.Builder()
                .setActionStrip(emptyActionStrip())
                .setMapActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.PAN)
                        .build()
                )
                .build()

        // ---- SurfaceCallback ------------------------------------------------

        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            Log.i(
                TAG,
                "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height}" +
                    "@${surfaceContainer.dpi} parked=${virtualDisplay != null && hostSurface == null}"
            )
            val surface = surfaceContainer.surface?.takeIf { it.isValid } ?: return
            val dpi = surfaceContainer.dpi.coerceAtLeast(160)

            // Returning from background: re-attach existing display to new host surface.
            val parked = virtualDisplay
            if (parked != null && presentation != null &&
                surfaceContainer.width == surfaceWidth &&
                surfaceContainer.height == surfaceHeight &&
                dpi == surfaceDpi
            ) {
                hostSurface?.release()
                hostSurface = surface
                parked.setSurface(surface)
                presentation?.updateVisibleArea(surfaceWidth, surfaceHeight, visibleArea)
                resetPointer()
                return
            }

            releaseDisplay()
            surfaceWidth = surfaceContainer.width
            surfaceHeight = surfaceContainer.height
            surfaceDpi = dpi
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            hostSurface = surface

            val dm = getCarContext().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            virtualDisplay = dm.createVirtualDisplay(
                "AAYoutube-Car",
                surfaceWidth, surfaceHeight, surfaceDpi,
                surface, flags
            )
            val display = virtualDisplay?.display ?: run { releaseDisplay(); return }

            presentation = FullscreenCarPresentation(
                context = getCarContext(),
                display = display,
                carToken = carToken
            ).also {
                it.show()
                it.updateVisibleArea(surfaceWidth, surfaceHeight, visibleArea)
            }
            resetPointer()
            rebindToPresentation()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            Log.i(TAG, "onVisibleAreaChanged $visibleArea")
            this.visibleArea = visibleArea.takeUnless(Rect::isEmpty)?.let(::Rect)
            presentation?.updateVisibleArea(surfaceWidth, surfaceHeight, this.visibleArea)
        }

        /**
         * Park the VirtualDisplay (drop the surface) while media is playing so the
         * WebView keeps decoding audio/video even when Android Auto hides us.
         * Identical to AABrowser-Motion's onSurfaceDestroyed strategy.
         */
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "onSurfaceDestroyed playing=${presentation?.isMediaPlaying()}")
            if (virtualDisplay == null || presentation?.isMediaPlaying() != true) {
                releaseDisplay()
                return
            }
            detachHostSurface()
        }

        // ---- Touch forwarding -----------------------------------------------

        override fun onClick(x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            dispatchMotion(now, now, MotionEvent.ACTION_DOWN, x, y)
            dispatchMotion(now, now + CLICK_DURATION_MS, MotionEvent.ACTION_UP, x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            val now = SystemClock.uptimeMillis()
            lastScrollTime = now
            if (!scrollInProgress) {
                scrollInProgress = true
                scrollDownTime = now
                resetPointer()
                dispatchMotion(now, now, MotionEvent.ACTION_DOWN, pointerX, pointerY)
            }
            pointerX = (pointerX - distanceX)
                .coerceIn(0f, (surfaceWidth - 1).coerceAtLeast(0).toFloat())
            pointerY = (pointerY - distanceY)
                .coerceIn(0f, (surfaceHeight - 1).coerceAtLeast(0).toFloat())
            dispatchMotion(scrollDownTime, now, MotionEvent.ACTION_MOVE, pointerX, pointerY)
            handler.removeCallbacks(finishScroll)
            handler.postDelayed(finishScroll, SCROLL_END_DELAY_MS)
        }

        // ---- Helpers --------------------------------------------------------

        private fun rebindToPresentation() {
            val p = presentation ?: return
            if (!WebHost.isActiveCarHost(carToken)) return
            p.bindWebView()
        }

        private fun dispatchMotion(
            downTime: Long, eventTime: Long, action: Int, x: Float, y: Float
        ) {
            val p = presentation ?: return
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { ev ->
                try { p.dispatchTouchEvent(ev) } finally { ev.recycle() }
            }
        }

        private fun resetPointer() {
            pointerX = surfaceWidth / 2f
            pointerY = surfaceHeight / 2f
        }

        private fun detachHostSurface() {
            handler.removeCallbacks(finishScroll)
            scrollInProgress = false
            virtualDisplay?.setSurface(null)
            hostSurface?.release()
            hostSurface = null
        }

        private fun releaseDisplay() {
            detachHostSurface()
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
            surfaceWidth = 0
            surfaceHeight = 0
            surfaceDpi = 0
        }

        private companion object {
            const val TAG = "FullscreenCarService"
            const val CLICK_DURATION_MS = 10L
            const val SCROLL_END_DELAY_MS = 120L

            /** NavigationTemplate requires at least one action in the ActionStrip. */
            fun emptyActionStrip(): ActionStrip {
                val ctor = ActionStrip::class.java.getDeclaredConstructor()
                ctor.isAccessible = true
                return ctor.newInstance()
            }
        }
    }
}
