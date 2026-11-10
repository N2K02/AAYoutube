package com.aayoutube.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A separate SurfaceView layer in the *existing* legacy car window.
 *
 * Renders synthetic moving shapes at approximately 30 fps. It does NOT
 * mirror, decode, capture, reload, or manipulate the YouTube WebView.
 * A short one-time PixelCopy checks the actual SurfaceView buffer.
 * Every bitmap is discarded; only frame counts and color match survive.
 */
internal class CarDirectSurfaceView(
    context: Context,
    private val owner: String,
    private val runId: Int,
) : SurfaceView(context), SurfaceHolder.Callback {
    companion object {
        // PixelCopy can fail to invoke a callback on some host disconnects.
        // At most one tiny buffer can stay in flight across all view instances.
        private val pixelReadbackInFlight = AtomicBoolean(false)
    }

    private val main = Handler(Looper.getMainLooper())
    private val surfaceLock = Any()
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(
        Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
        Color.MAGENTA, Color.RED, Color.BLUE,
    )
    private var drawThread: HandlerThread? = null
    private var drawHandler: Handler? = null
    private var stopped = true
    private var frames = 0
    private var startedAt = 0L
    private var previousFrame = 0L
    private var maxGapMs = 0L
    private var readbackRequested = false

    init {
        // This deliberately exercises SurfaceFlinger/compositor behavior
        // distinct from the Compose color-bar overlay and Chromium layers.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
        isClickable = false
        isFocusable = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) {
            if (!stopped) return
            stopped = false
            frames = 0
            startedAt = SystemClock.elapsedRealtime()
            previousFrame = startedAt
            maxGapMs = 0L
            readbackRequested = false
            val thread = HandlerThread("aay-car-direct-surface").also { it.start() }
            drawThread = thread
            drawHandler = Handler(thread.looper).also { it.post(drawLoop) }
        }
        AppLog.add("CAR", "Direct SurfaceView created display=" +
            (display?.displayId ?: -1) + " run=" + runId)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.add("CAR", "Direct SurfaceView size=${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRendering()
    }

    override fun onDetachedFromWindow() {
        stopRendering()
        super.onDetachedFromWindow()
    }

    fun stopRendering() {
        synchronized(surfaceLock) {
            if (stopped) return
            stopped = true
            drawHandler?.removeCallbacksAndMessages(null)
            drawHandler = null
            drawThread?.quitSafely()
            drawThread = null
        }
        AppLog.add("CAR", "Direct SurfaceView released run=" + runId +
            " submittedFrames=" + frames)
    }

    private val drawLoop = object : Runnable {
        override fun run() {
            if (stopped) return
            if (WebHost.snapshot.value.playing) {
                main.post { CarDirectSurfaceProbe.stopForPlayback(owner) }
                return
            }
            val now = SystemClock.elapsedRealtime()
            var submitted = false
            synchronized(surfaceLock) {
                if (stopped || !holder.surface.isValid) return
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        drawPattern(canvas, now - startedAt)
                        holder.unlockCanvasAndPost(canvas)
                        canvas = null
                        submitted = true
                    }
                } catch (e: Exception) {
                    AppLog.add("CAR", "Direct SurfaceView frame unavailable: " +
                        e.javaClass.simpleName)
                } finally {
                    canvas?.let { last -> runCatching { holder.unlockCanvasAndPost(last) } }
                }
                if (submitted) {
                    val gap = now - previousFrame
                    if (frames > 0) maxGapMs = maxOf(maxGapMs, gap)
                    previousFrame = now
                    frames++
                }
            }

            if (submitted && (frames == 1 || frames % 15 == 0)) {
                val count = frames
                val elapsed = (now - startedAt).coerceAtLeast(1L)
                val worstGap = maxGapMs
                main.post { CarDirectSurfaceProbe.drawn(owner, runId, count, elapsed, worstGap) }
            }
            if (submitted && frames >= 12 && !readbackRequested) {
                readbackRequested = true
                main.post { checkSurfacePixels() }
            }
            drawHandler?.postDelayed(this, CarDirectSurfacePolicy.TARGET_FRAME_INTERVAL_MS)
        }
    }

    private fun drawPattern(canvas: Canvas, elapsedMs: Long) {
        val width = canvas.width.toFloat().coerceAtLeast(1f)
        val height = canvas.height.toFloat().coerceAtLeast(1f)
        canvas.drawColor(Color.BLACK)
        val upper = height * 0.7f
        colors.forEachIndexed { bar, color ->
            brush.color = color
            canvas.drawRect(width * bar / 7f, 0f,
                width * (bar + 1) / 7f, upper, brush)
        }
        val progress = ((elapsedMs % 2_000L) / 2_000f)
        val markerX = progress * (width - width * 0.12f)
        brush.color = Color.WHITE
        canvas.drawRoundRect(markerX, height * 0.80f,
            markerX + width * 0.12f, height * 0.89f, 8f, 8f, brush)
        brush.color = Color.LTGRAY
        brush.textSize = (height * 0.048f).coerceIn(12f, 32f)
        canvas.drawText("AAYouTube direct surface test — synthetic / parked only",
            width * 0.03f, height * 0.97f, brush)
    }

    /** PixelCopy of this SurfaceView, NOT YouTube video or the phone screen. */
    private fun checkSurfacePixels() {
        if (stopped || !isAttachedToWindow) return
        val host = ExperimentalCarVideoMonitor.state.value
        val probe = CarDirectSurfaceProbe.state.value
        if (host.owner != owner || !host.active || !host.enabled ||
            probe.owner != owner || probe.runId != runId || !probe.visible) return
        if (!pixelReadbackInFlight.compareAndSet(false, true)) {
            CarDirectSurfaceProbe.readback(owner, runId,
                "Previous direct-surface readback still pending")
            return
        }
        val image = runCatching {
            Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            pixelReadbackInFlight.set(false)
            CarDirectSurfaceProbe.readback(owner, runId,
                "Surface buffer test unavailable: " + it.javaClass.simpleName)
            return
        }
        try {
            PixelCopy.request(
                this,
                image,
                PixelCopy.OnPixelCopyFinishedListener { code ->
                    try {
                        if (code == PixelCopy.SUCCESS) {
                            val pixels = IntArray(96 * 54)
                            image.getPixels(pixels, 0, 96, 0, 0, 96, 54)
                            val result = CarVideoPatternPixelPolicy.analyze(pixels, 96, 54)
                            CarDirectSurfaceProbe.readback(owner, runId,
                                "Direct surface buffer: ${result.matched}/${result.samples} " +
                                    "synthetic color samples matched (not head-unit proof)",
                                detected = result.matched >= 17)
                        } else {
                            CarDirectSurfaceProbe.readback(owner, runId,
                                "Direct surface PixelCopy result=$code (inconclusive)")
                        }
                    } catch (e: Exception) {
                        CarDirectSurfaceProbe.readback(owner, runId,
                            "Direct surface readback unavailable: " + e.javaClass.simpleName)
                    } finally {
                        image.recycle()
                        pixelReadbackInFlight.set(false)
                    }
                },
                main,
            )
        } catch (e: Exception) {
            image.recycle()
            pixelReadbackInFlight.set(false)
            CarDirectSurfaceProbe.readback(owner, runId,
                "Direct surface PixelCopy unavailable: " + e.javaClass.simpleName)
        }
    }
}
