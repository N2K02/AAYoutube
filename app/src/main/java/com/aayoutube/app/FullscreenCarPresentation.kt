package com.aayoutube.app

import android.app.Presentation
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
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

/**
 * A [Presentation] that runs inside a [android.hardware.display.VirtualDisplay] created by
 * [FullscreenCarService]. It renders the YouTube WebView (via [WebHost]) directly onto
 * the Android Auto head-unit's display surface.
 *
 * Mirrors AABrowser-Motion's BrowserPresentation, adapted for AAYoutube's single-WebView
 * model: no tabs, no browser shell — just the player attached to the car frame.
 */
internal class FullscreenCarPresentation(
    private val context: Context,
    display: Display,
    private val carToken: String
) : Presentation(context, display, R.style.Theme_AAYoutube),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val hostViewModelStore = ViewModelStore()
    private val handler = Handler(Looper.getMainLooper())

    private var playerFrame: CarFrameLayout? = null
    private var contentRoot: View? = null
    private var isDestroyed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = hostViewModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
        }

        val frame = CarFrameLayout(context).apply {
            setViewTreeLifecycleOwner(this@FullscreenCarPresentation)
            setViewTreeSavedStateRegistryOwner(this@FullscreenCarPresentation)
            setViewTreeViewModelStoreOwner(this@FullscreenCarPresentation)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    AppLog.add("CAR-FS", "Presentation frame attached display=" +
                        (display?.displayId ?: -1))
                    if (playerFrame === v) bindWebView()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    AppLog.add("CAR-FS", "Presentation frame detached")
                }
            })
        }
        playerFrame = frame
        setContentView(frame)
        contentRoot = frame
    }

    /** Attach the shared YouTube WebView into the car presentation frame. */
    fun bindWebView() {
        val frame = playerFrame ?: return
        if (!frame.isAttachedToWindow || frame.display == null) return
        if (!WebHost.isActiveCarHost(carToken)) return

        val displayCtx = runCatching {
            android.view.ContextThemeWrapper(
                context.createDisplayContext(frame.display!!),
                R.style.Theme_AAYoutube
            )
        }.getOrElse {
            AppLog.add("CAR-FS", "Display context failed: " + it.javaClass.simpleName)
            context
        }

        val player = WebHost.attachToCarWindow(displayCtx, frame)
        if (frame.childCount != 1 || frame.getChildAt(0) !== player) {
            frame.removeAllViews()
            frame.addView(player, FrameLayout.LayoutParams(-1, -1))
            frame.isFocusableInTouchMode = true
            frame.requestFocus()
            AppLog.add(
                "CAR-FS",
                "WebView bound to presentation display=${frame.display?.displayId ?: -1}" +
                    " size=${frame.width}x${frame.height}"
            )
        }
    }

    fun isMediaPlaying(): Boolean = WebHost.snapshot.value.playing

    fun updateVisibleArea(surfaceWidth: Int, surfaceHeight: Int, visibleArea: Rect?) {
        val root = contentRoot ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || visibleArea == null) {
            root.setPadding(0, 0, 0, 0)
            return
        }
        val hThreshold = (surfaceWidth * MIN_OCCLUSION_RATIO).toInt()
        val vThreshold = (surfaceHeight * MIN_OCCLUSION_RATIO).toInt()
        val left = visibleArea.left.coerceIn(0, surfaceWidth)
        val top = visibleArea.top.coerceIn(0, surfaceHeight)
        val right = (surfaceWidth - visibleArea.right).coerceIn(0, surfaceWidth)
        val bottom = (surfaceHeight - visibleArea.bottom).coerceIn(0, surfaceHeight)
        root.setPadding(
            left.takeIf { it > hThreshold } ?: 0,
            top.takeIf { it > vThreshold } ?: 0,
            right.takeIf { it > hThreshold } ?: 0,
            bottom.takeIf { it > vThreshold } ?: 0
        )
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        if (!isDestroyed) lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val out = super.onSaveInstanceState()
        savedStateController.performSave(out)
        return out
    }

    override fun dismiss() {
        if (isDestroyed) return
        isDestroyed = true
        playerFrame = null
        contentRoot = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        hostViewModelStore.clear()
        super.dismiss()
    }

    private companion object {
        const val MIN_OCCLUSION_RATIO = 0.05f
    }
}
