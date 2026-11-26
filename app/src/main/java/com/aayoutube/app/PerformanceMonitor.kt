package com.aayoutube.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android side of the adaptive performance engine: samples the signals, feeds
 * [AdaptivePerformanceGovernor] and publishes the level. Everything here runs on the
 * main thread and is cheap: thermal status is a binder read, battery comes from the
 * sticky broadcast at most every [BATTERY_READ_MS], memory reuses PhoneMirror's throttled check.
 *
 * Evaluations happen when something already runs (phone mirror sync, page diagnostics,
 * thermal callback, trim request). While the level is above NORMAL a bounded recovery
 * tick keeps evaluating so the device can return to NORMAL even when nothing else runs.
 *
 * Lifecycle ([PerformanceSession]): monitoring runs only between [start] (a primary renderer
 * exists) and [reset] (Quit / renderer released). Outside a session thermal, memory and frame
 * callbacks are ignored and never restart the recovery tick.
 */
internal object PerformanceMonitor {
    private const val MIN_EVALUATE_MS = 2_000L
    private const val BATTERY_READ_MS = 30_000L
    private const val RECOVERY_TICK_MS = 10_000L

    private var app: Application? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val session = PerformanceSession()
    private val governor get() = session.governor
    val diagnostics = PerfDiagnostics()
    private val _level = MutableStateFlow(PerfLevel.NORMAL)
    val level: StateFlow<PerfLevel> = _level

    private var evaluatedAt = 0L
    private var batteryReadAt = 0L
    private var batteryTemp = PerfSignals.UNKNOWN_TEMP
    private var charging = false
    private var pushedLevel = PerfLevel.NORMAL
    private var pushedDiagMs = 30_000L

    fun init(application: Application) { app = application }

    val active: Boolean get() = session.active

    /** A primary renderer was created: a session runs, and the old renderer's measurements are dropped. */
    fun onPrimaryRendererCreated() {
        session.start()
        session.rendererChanged()
    }

    /** Page diagnostics window: sustained dropped frames of the primary player. */
    fun onDroppedFrames(droppedPct: Int) {
        if (!session.active) return
        session.onDroppedFrames(droppedPct)
        evaluate(force = true)
    }

    fun onThermalChanged() { if (session.active) evaluate(force = true) }

    fun onTrimMemory() { if (session.active) evaluate(force = true) }

    /** Current level, re-evaluated at most every [MIN_EVALUATE_MS] unless [force]d. Main thread only. */
    fun evaluate(force: Boolean = false): PerfLevel {
        if (!session.active) {
            main.removeCallbacks(recoveryTick)
            return PerfLevel.NORMAL
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && evaluatedAt != 0L && now - evaluatedAt < MIN_EVALUATE_MS) return governor.level
        evaluatedAt = now
        val snap = WebHost.snapshot.value
        val bufferingMs = session.primaryBufferingMs(
            snap.playing && snap.buffering && !snap.userPaused && !snap.held, now)
        val thermal = thermalStatus()
        diagnostics.noteThermal(thermal)
        readBattery(now)
        val signals = PerfSignals(
            thermalStatus = thermal,
            batteryTempDeciC = batteryTemp,
            charging = charging,
            memory = PhoneMirror.currentMemoryLevel(),
            cpuPressure = WebHost.underProcessPressure(),
            droppedFrames = session.droppedLevel,
            primaryBufferingMs = bufferingMs,
        )
        val result = session.evaluate(signals, now)
        result.transition?.let { t ->
            diagnostics.levelTransitions++
            AppLog.add("PERF-STATE", "level ${t.from} -> ${t.to} reason=${t.reason} heldMs=${t.heldMs} " +
                "thermal=$thermal batteryTempDeciC=${if (batteryTemp == PerfSignals.UNKNOWN_TEMP) -1 else batteryTemp} " +
                "charging=$charging memory=${signals.memory} cpuPressure=${signals.cpuPressure} " +
                "dropped=${signals.droppedFrames} bufferingMs=${signals.primaryBufferingMs}")
            _level.value = t.to
            PhoneMirror.onPerformanceLevel(t.to)
        }
        // The page follows level changes and screen on/off (diagnostics window) changes alike.
        val diagMs = diagnosticsIntervalMs()
        if (pushedLevel != governor.level || pushedDiagMs != diagMs) {
            pushedLevel = governor.level
            pushedDiagMs = diagMs
            WebHost.applyPerformanceLevel(governor.level)
        }
        main.removeCallbacks(recoveryTick)
        if (result.needsRecoveryTick) main.postDelayed(recoveryTick, RECOVERY_TICK_MS)
        return result.level
    }

    private val recoveryTick = Runnable { if (session.active) evaluate(force = true) }

    fun screenOn(): Boolean = runCatching {
        (app?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
    }.getOrNull() ?: true

    /** Page diagnostics window for the current level and screen state. */
    fun diagnosticsIntervalMs(): Long = AdaptivePerformancePolicy.diagnosticsIntervalMs(governor.level, screenOn())

    /** Quit / renderer released: the session ends, nothing keeps evaluating, the next starts from NORMAL. */
    fun reset() {
        main.removeCallbacks(recoveryTick)
        session.stop()
        evaluatedAt = 0L
        pushedLevel = PerfLevel.NORMAL
        pushedDiagMs = 30_000L
        _level.value = PerfLevel.NORMAL
    }

    fun summary(): String = "level=${governor.level} reason=${governor.reason} " + diagnostics.summary()

    private fun thermalStatus(): Int = if (Build.VERSION.SDK_INT < 29) 0 else runCatching {
        (app?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: 0
    }.getOrDefault(0)

    private fun readBattery(now: Long) {
        if (batteryReadAt != 0L && now - batteryReadAt < BATTERY_READ_MS) return
        batteryReadAt = now
        val a = app ?: return
        // Sticky broadcast: no receiver is registered and nothing stays subscribed.
        val intent: Intent? = runCatching { a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        if (intent == null) return
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // Plausible range only (-20 °C .. 80 °C); anything else is unknown.
        batteryTemp = if (t in -200..800) t else PerfSignals.UNKNOWN_TEMP
        diagnostics.batteryTempDeciC = batteryTemp
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
