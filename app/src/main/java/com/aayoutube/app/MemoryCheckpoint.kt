package com.aayoutube.app

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Memory at lifecycle points (projection end, car disconnect, PiP, Quit, idle mirror release),
 * so repeated cycles can be compared before calling anything a leak. Logged after a short
 * settle delay so released WebViews and decoders have a chance to go. Reports this app process
 * only: Chromium renderer processes are separate (see the PERF line for renderer CPU).
 */
internal object MemoryCheckpoint {
    private const val SETTLE_MS = 2_500L
    private const val MIN_GAP_MS = 1_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var lastAt = 0L
    private val counts = HashMap<String, Int>()

    /** Reading PSS walks the process memory maps (slow while swapping): never on the UI thread. */
    private val worker by lazy {
        android.os.HandlerThread("aay-mem", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }
    private val background by lazy { Handler(worker.looper) }

    fun record(point: String, settleMs: Long = SETTLE_MS) {
        main.postDelayed({ log(point) }, settleMs)
    }

    private fun log(point: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP_MS) return
        lastAt = now
        val cycle = (counts[point] ?: 0) + 1
        counts[point] = cycle
        // Renderer state is read on the main thread (where it changes), the rest in the background.
        val mirror = PhoneMirror.rendererAllocated
        val engine = WebHost.rendererAllocated
        background.post {
            val rt = Runtime.getRuntime()
            val pssMb = runCatching { Debug.getPss() / 1024 }.getOrDefault(-1L)
            AppLog.add("MEM", MemoryPolicy.line(point, cycle, pssMb, (rt.totalMemory() - rt.freeMemory()) shr 20,
                Debug.getNativeHeapAllocatedSize() shr 20, mirror, engine))
        }
    }
}

internal object MemoryPolicy {
    fun line(point: String, cycle: Int, pssMb: Long, javaMb: Long, nativeMb: Long, mirror: Boolean, engine: Boolean) =
        "checkpoint=$point cycle=$cycle appPssMb=$pssMb javaHeapMb=$javaMb nativeHeapMb=$nativeMb " +
            "phoneRenderer=${if (mirror) "allocated" else "released"} engineRenderer=${if (engine) "allocated" else "released"}"
}
