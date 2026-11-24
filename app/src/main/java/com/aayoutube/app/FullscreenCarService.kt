package com.aayoutube.app

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
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

        // Experimental watchdog state
        private var experimentalVideoEnabled = false
        private var experimentalChecks = 0
        private var experimentalRepairs = 0
        /** One retry budget per screen instance, never reset by rebinds. */
        private var experimentalRecoveryStarted = false

        private val carToken =
            "fullscreen-car@" + Integer.toHexString(System.identityHashCode(this))

        private val relink: () -> Unit = { handler.post { connectMedia() } }
        private var mediaController: CompatMediaConnection? = null
        private val carQuitCallback: () -> Unit = { shutdownCarSession() }
        private var carQuitInProgress = false
        private var screenStarted = false
        private var inactiveLogged = false
        private val refit = Runnable { WebHost.refitCarViewport() }

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

        /**
         * After becoming active host: re-claim WebHost, register probes, re-prepare projection.
         * Mirrors LegacyProjectionActivity.rebindWhenActive.
         */
        private val rebindWhenActive = Runnable {
            if (!screenStarted || !WebHost.isActiveCarHost(carToken)) return@Runnable
            WebHost.showOnCar(true, carToken)
            ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
            if (experimentalVideoEnabled) {
                CarVideoPatternProbe.register(carToken)
                CarDirectSurfaceProbe.register(carToken)
            }
            WebHost.onCarQuit = carQuitCallback
            if (WebHost.mode.value != WebHost.PlayerMode.MINI) WebHost.prepareForCarProjection()
            else BrowseHost.resume()
            WebHost.setHold(false)
            WebHost.requestLink = relink
            connectMedia()
            rebindToPresentation()
        }

        private val onBecameActive: () -> Unit = {
            handler.removeCallbacks(rebindWhenActive)
            handler.postDelayed(rebindWhenActive, 1_200L)
        }

        /** Render-frame probe at 2 s / 6 s / 12 s after a bind. */
        private val renderProbe = Runnable {
            if (screenStarted && WebHost.isActiveCarHost(carToken)) WebHost.logCarRenderProbe()
        }
        private fun scheduleRenderProbes() {
            handler.removeCallbacks(renderProbe)
            longArrayOf(2_000L, 6_000L, 12_000L).forEach { handler.postDelayed(renderProbe, it) }
        }

        /**
         * Bounded watchdog: checks WebView geometry against VirtualDisplay dimensions and
         * triggers rebinds when mismatched. Mirrors LegacyProjectionActivity.experimentalRecovery.
         */
        private val experimentalRecovery = object : Runnable {
            override fun run() {
                if (!experimentalVideoEnabled || !screenStarted ||
                    !WebHost.isActiveCarHost(carToken) ||
                    WebHost.fullscreenView.value != null) return
                val p = presentation ?: return
                val frame = p.playerFrame()
                val player = frame?.getChildAt(0) as? android.webkit.WebView
                val repair = frame != null && ExperimentalCarVideoPolicy.needsRebind(
                    enabled = experimentalVideoEnabled,
                    activeCarHost = WebHost.isActiveCarHost(carToken),
                    frameAttached = frame.isAttachedToWindow,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    frameDisplayId = frame.display?.displayId ?: -1,
                    playerAttached = player?.isAttachedToWindow == true,
                    playerWidth = player?.width ?: 0,
                    playerHeight = player?.height ?: 0,
                    playerDisplayId = player?.display?.displayId ?: -1,
                )
                experimentalChecks++
                var status = when {
                    frame == null || !frame.isAttachedToWindow -> "Waiting for car surface"
                    frame.width <= 0 || frame.height <= 0 -> "Waiting for usable viewport"
                    !repair -> "Healthy WebView geometry"
                    else -> "WebView rebind requested"
                }
                if (repair && frame != null) {
                    if (experimentalRepairs >= ExperimentalCarVideoPolicy.MAX_REBINDS) {
                        status = "Fallback: rebind budget exhausted"
                        experimentalVideoEnabled = false
                        CarVideoPatternProbe.unregister(carToken)
                        CarDirectSurfaceProbe.unregister(carToken)
                        ExperimentalCarVideoMonitor.fallback(carToken, "rebind budget exhausted")
                    } else {
                        experimentalRepairs++
                        runCatching {
                            rebindToPresentation()
                            WebHost.refitCarViewport()
                        }.onFailure {
                            status = "Fallback: rebind failed"
                            experimentalVideoEnabled = false
                            CarVideoPatternProbe.unregister(carToken)
                            CarDirectSurfaceProbe.unregister(carToken)
                            ExperimentalCarVideoMonitor.fallback(carToken,
                                "rebind failed (" + it.javaClass.simpleName + ")")
                        }
                    }
                }
                val visible = android.graphics.Rect()
                if (frame != null && !frame.getLocalVisibleRect(visible)) visible.setEmpty()
                ExperimentalCarVideoMonitor.check(
                    owner = carToken,
                    state = status,
                    checks = experimentalChecks,
                    repairs = experimentalRepairs,
                    displayId = frame?.display?.displayId ?: -1,
                    frameWidth = frame?.width ?: 0,
                    frameHeight = frame?.height ?: 0,
                    playerWidth = player?.width ?: 0,
                    playerHeight = player?.height ?: 0,
                    visibleArea = CarVideoBounds(
                        visible.left, visible.top, visible.width(), visible.height()
                    ),
                )
                if (experimentalVideoEnabled &&
                    experimentalChecks < ExperimentalCarVideoPolicy.MAX_CHECKS) {
                    handler.postDelayed(this, ExperimentalCarVideoPolicy.CHECK_DELAY_MS)
                } else {
                    AppLog.add("CAR-FS", "Experimental video: checks finished; fullscreen car intact")
                }
            }
        }

        private fun scheduleExperimentalRecovery() {
            if (!experimentalVideoEnabled || !screenStarted ||
                !WebHost.isActiveCarHost(carToken) || experimentalRecoveryStarted) return
            val frame = presentation?.playerFrame()
            if (frame?.isAttachedToWindow != true || frame.display == null) return
            experimentalRecoveryStarted = true
            handler.removeCallbacks(experimentalRecovery)
            handler.postDelayed(experimentalRecovery, ExperimentalCarVideoPolicy.CHECK_DELAY_MS)
        }

        // ---- Init -----------------------------------------------------------

        init {
            getCarContext().getCarService(AppManager::class.java).setSurfaceCallback(this)

            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    AppLog.add("CAR-FS", "screen onCreate token=$carToken")
                    experimentalVideoEnabled = Features.current.experimentalVideo
                    CarVirtualDisplayProbe.cancel()
                    ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
                    WebHost.onCarQuit = carQuitCallback
                }

                override fun onStart(owner: LifecycleOwner) {
                    AppLog.add("CAR-FS", "screen onStart token=$carToken")
                    screenStarted = true
                    val wasAlreadyProjected = WebHost.carShown
                    WebHost.claimCarHost(carToken, onBecameActive)
                    ExperimentalCarVideoMonitor.begin(carToken, experimentalVideoEnabled)
                    if (experimentalVideoEnabled) {
                        CarVideoPatternProbe.register(carToken)
                        CarDirectSurfaceProbe.register(carToken)
                    }
                    WebHost.showOnCar(true, carToken)
                    if (!wasAlreadyProjected || WebHost.mode.value != WebHost.PlayerMode.MINI)
                        WebHost.prepareForCarProjection()
                    else BrowseHost.resume()
                    WebHost.setHold(false)
                    WebHost.requestLink = relink
                    WebHost.onCarQuit = carQuitCallback
                    PlaybackService.carAttached(getCarContext(), true)
                    connectMedia()
                    rebindToPresentation()
                    scheduleExperimentalRecovery()
                }

                override fun onResume(owner: LifecycleOwner) {
                    AppLog.add("CAR-FS", "screen onResume token=$carToken")
                    if (WebHost.isActiveCarHost(carToken)) WebHost.setHold(false)
                    rebindToPresentation()
                    handler.removeCallbacks(refit)
                    handler.postDelayed(refit, 180L)
                    scheduleRenderProbes()
                    scheduleExperimentalRecovery()
                }

                override fun onStop(owner: LifecycleOwner) {
                    AppLog.add("CAR-FS", "screen onStop token=$carToken playing=${presentation?.isMediaPlaying()}")
                    screenStarted = false
                    handler.removeCallbacksAndMessages(null)
                    CarVideoPatternProbe.unregister(carToken)
                    CarDirectSurfaceProbe.unregister(carToken)
                    ExperimentalCarVideoMonitor.end(carToken)
                    WebHost.releaseCarHost(carToken)
                    if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
                    WebHost.showOnCar(false, carToken)
                    if (WebHost.requestLink === relink) {
                        WebHost.requestLink = null
                        MainActivity.restorePhoneLink()
                    }
                    PlaybackService.carAttached(getCarContext(), false)
                    mediaController?.disconnect()
                    mediaController = null
                    WebHost.requestReattachment()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    AppLog.add("CAR-FS", "screen onDestroy token=$carToken")
                    handler.removeCallbacksAndMessages(null)
                    releaseDisplay()
                    CarVideoPatternProbe.unregister(carToken)
                    CarDirectSurfaceProbe.unregister(carToken)
                    ExperimentalCarVideoMonitor.end(carToken)
                    WebHost.releaseCarHost(carToken)
                    if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
                    WebHost.showOnCar(false, carToken)
                    if (WebHost.requestLink === relink) {
                        WebHost.requestLink = null
                        MainActivity.restorePhoneLink()
                    }
                    mediaController?.disconnect()
                    mediaController = null
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
            AppLog.add("CAR-FS",
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
                carToken = carToken,
                onSwipeDown = ::handleSwipeDown,
                onVideoTap = ::handleVideoTap,
                onCarVideoToMini = { carVideoToMini("Compose") },
                onCarBackToMini = { carBackToMini() },
                onCarHomeToMini = { carHomeToMini() },
                onQuit = { quitFromCar() },
            ).also {
                it.show()
                it.updateVisibleArea(surfaceWidth, surfaceHeight, visibleArea)
            }
            resetPointer()
            rebindToPresentation()
            scheduleRenderProbes()
            scheduleExperimentalRecovery()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            AppLog.add("CAR-FS", "onVisibleAreaChanged $visibleArea")
            this.visibleArea = visibleArea.takeUnless(Rect::isEmpty)?.let(::Rect)
            presentation?.updateVisibleArea(surfaceWidth, surfaceHeight, this.visibleArea)
        }

        /**
         * Park the VirtualDisplay (drop the surface) while media is playing so the
         * WebView keeps decoding audio/video even when Android Auto hides us.
         */
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            AppLog.add("CAR-FS", "onSurfaceDestroyed playing=${presentation?.isMediaPlaying()}")
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

        // ---- Navigation (mirrors LegacyProjectionActivity) ------------------

        private fun handleSwipeDown() {
            if (WebHost.isActiveCarHost(carToken) &&
                WebHost.mode.value == WebHost.PlayerMode.FULL &&
                WebHost.isOnWatchPage()) {
                AppLog.add("CAR-FS", "Swipe down on video: mini player")
                carVideoToMini("Swipe")
            }
        }

        private fun handleVideoTap() {
            if (WebHost.isActiveCarHost(carToken) &&
                WebHost.mode.value == WebHost.PlayerMode.FULL &&
                WebHost.isOnWatchPage()) {
                WebHost.showCarControls()
            }
        }

        private fun carVideoToMini(action: String): Boolean {
            if (!WebHost.isActiveCarHost(carToken)) {
                AppLog.add("CAR-FS", "$action ignored: inactive projection host")
                return false
            }
            if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
                BrowseHost.navigate(WebHost.home())
                AppLog.add("CAR-FS", "$action: browse Home with mini player")
                return true
            }
            if (WebHost.fullscreenView.value != null) {
                AppLog.add("CAR-FS", "$action: exit native fullscreen before mini transition")
                WebHost.exitFullscreen()
                handler.post {
                    if (WebHost.isActiveCarHost(carToken)) finishCarMiniTransition(action)
                }
                return true
            }
            return finishCarMiniTransition(action)
        }

        private fun finishCarMiniTransition(action: String): Boolean {
            if (!WebHost.isActiveCarHost(carToken)) return false
            if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
                BrowseHost.navigate(WebHost.home())
                return true
            }
            if (!WebHost.isOnWatchPage()) {
                AppLog.add("CAR-FS", "$action: no watch page; navigate Home")
                WebHost.openUrl(WebHost.home())
                return true
            }
            WebHost.enterMini()
            val succeeded = WebHost.mode.value == WebHost.PlayerMode.MINI
            AppLog.add("CAR-FS", "$action: mini transition success=$succeeded")
            if (succeeded) {
                BrowseHost.resume()
                BrowseHost.navigate(WebHost.home())
            }
            return succeeded
        }

        private fun carBackToMini(): Boolean {
            if (!WebHost.isActiveCarHost(carToken)) return false
            if (WebHost.mode.value == WebHost.PlayerMode.MINI) {
                if (!BrowseHost.goBack()) AppLog.add("CAR-FS", "Back: already on browse Home")
                return true
            }
            return carVideoToMini("Back")
        }

        private fun carHomeToMini() {
            carVideoToMini("Home")
        }

        // ---- Quit -----------------------------------------------------------

        private fun quitFromCar() {
            AppLog.add("CAR-FS", "Quit from car controls")
            mediaController?.command(Commands.EXIT)
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    getCarContext(),
                    Intent(getCarContext(), PlaybackService::class.java)
                        .setAction(PlaybackService.ACTION_QUIT)
                )
            }.onFailure { AppLog.add("CAR-FS", "Quit intent failed: " + it.javaClass.simpleName) }
            handler.postDelayed({
                if (screenStarted && WebHost.isActiveCarHost(carToken) && !carQuitInProgress)
                    shutdownCarSession()
            }, 1_800L)
        }

        private fun shutdownCarSession() {
            if (carQuitInProgress) return
            carQuitInProgress = true
            if (WebHost.onCarQuit === carQuitCallback) WebHost.onCarQuit = null
            if (WebHost.requestLink === relink) WebHost.requestLink = null
            mediaController?.disconnect()
            mediaController = null
            WebHost.cmd("pause")
            BrowseHost.release()
            WebHost.release()
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 500L)
        }

        // ---- Media ----------------------------------------------------------

        private fun connectMedia() {
            if (!screenStarted || mediaController != null) return
            mediaController = CompatMediaConnection(getCarContext()) {
                it.transportControls.sendCustomAction(Commands.HELLO, android.os.Bundle.EMPTY)
            }.also { it.connect() }
        }

        // ---- Helpers --------------------------------------------------------

        private fun rebindToPresentation() {
            val p = presentation ?: return
            if (!WebHost.isActiveCarHost(carToken)) {
                if (!inactiveLogged) AppLog.add("CAR-FS", "Inactive car host $carToken; skip rebind")
                inactiveLogged = true
                return
            }
            inactiveLogged = false
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
