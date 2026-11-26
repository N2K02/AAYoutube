package com.aayoutube.app

/**
 * Adaptive performance engine (pure policy, no Android types).
 *
 * One central assessment of how hard the device is working, used by every component
 * that can give way: the phone's secondary (mirror) player, the primary page script's
 * nonessential work and quality ceiling, diagnostics sampling and hidden-renderer release.
 *
 *  - NORMAL:   smooth playback at the appropriate quality.
 *  - ELEVATED: secondary video at 240p, nonessential page work slowed down.
 *  - HIGH:     secondary video suspended, primary playback preserved (1080p ceiling).
 *  - SEVERE:   audio first; primary video workload reduced where safe (720p ceiling).
 *
 * Escalation is immediate (heat and memory do not wait). Recovery is gradual: one level
 * at a time, each step only after [AdaptivePerformancePolicy.CALM_STEP_MS] of calm and
 * never sooner than [AdaptivePerformancePolicy.MIN_DWELL_MS] after the previous change,
 * so a signal hovering around a threshold cannot make playback switch back and forth.
 */
internal enum class PerfLevel {
    NORMAL, ELEVATED, HIGH, SEVERE;

    fun atLeast(other: PerfLevel) = ordinal >= other.ordinal
}

/** One sample of the signals the engine watches. Unknown values never raise the level. */
internal data class PerfSignals(
    /** PowerManager thermal status (0 none ... 6 shutdown); 0 when unavailable. */
    val thermalStatus: Int = 0,
    /** Battery temperature in tenths of a degree Celsius, [UNKNOWN_TEMP] when unknown. */
    val batteryTempDeciC: Int = UNKNOWN_TEMP,
    val charging: Boolean = false,
    val memory: MemoryLevel = MemoryLevel.OK,
    /** Sustained (not momentary) main-process CPU pressure. */
    val cpuPressure: Boolean = false,
    /** Sustained dropped-frame ratio of the primary player, [PerfLevel.NORMAL] when fine. */
    val droppedFrames: PerfLevel = PerfLevel.NORMAL,
    /** How long the primary (audible) player has been buffering while it should play; 0 if not. */
    val primaryBufferingMs: Long = 0L,
) {
    companion object { const val UNKNOWN_TEMP = Int.MIN_VALUE }
}

internal data class PerfAssessment(val level: PerfLevel, val reason: String)

internal data class PerfTransition(
    val from: PerfLevel,
    val to: PerfLevel,
    val reason: String,
    /** How long the previous level lasted. */
    val heldMs: Long,
)

internal object AdaptivePerformancePolicy {
    // android.os.PowerManager.THERMAL_STATUS_* values.
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    // Battery temperature (tenths of °C). Charging adds its own heat: thresholds come earlier.
    const val BATTERY_ELEVATED_DECI_C = 400
    const val BATTERY_HIGH_DECI_C = 430
    const val BATTERY_SEVERE_DECI_C = 460
    const val CHARGING_OFFSET_DECI_C = 15

    // Brief buffering after a seek is normal; only sustained buffering counts.
    const val BUFFERING_ELEVATED_MS = 4_000L
    const val BUFFERING_HIGH_MS = 15_000L

    // Dropped frames per diagnostics window, needing [DROPPED_SAMPLES] windows in a row.
    const val DROPPED_ELEVATED_PCT = 10
    const val DROPPED_HIGH_PCT = 25
    const val DROPPED_SAMPLES = 2

    // Hysteresis.
    const val CALM_STEP_MS = 20_000L
    const val MIN_DWELL_MS = 10_000L

    /** The level the signals call for right now, with the strongest reason. */
    fun assess(s: PerfSignals): PerfAssessment {
        val battery = if (s.batteryTempDeciC == PerfSignals.UNKNOWN_TEMP) null
            else s.batteryTempDeciC + if (s.charging) CHARGING_OFFSET_DECI_C else 0
        return when {
            s.thermalStatus >= THERMAL_SEVERE -> PerfAssessment(PerfLevel.SEVERE, "thermal=${s.thermalStatus}")
            s.memory == MemoryLevel.CRITICAL -> PerfAssessment(PerfLevel.SEVERE, "memory=critical")
            battery != null && battery >= BATTERY_SEVERE_DECI_C -> PerfAssessment(PerfLevel.SEVERE, batteryReason(s))
            s.thermalStatus >= THERMAL_MODERATE -> PerfAssessment(PerfLevel.HIGH, "thermal=${s.thermalStatus}")
            battery != null && battery >= BATTERY_HIGH_DECI_C -> PerfAssessment(PerfLevel.HIGH, batteryReason(s))
            s.primaryBufferingMs >= BUFFERING_HIGH_MS -> PerfAssessment(PerfLevel.HIGH, "primaryBuffering=${s.primaryBufferingMs}ms")
            s.droppedFrames.atLeast(PerfLevel.HIGH) -> PerfAssessment(PerfLevel.HIGH, "droppedFrames=high")
            s.thermalStatus >= THERMAL_LIGHT -> PerfAssessment(PerfLevel.ELEVATED, "thermal=${s.thermalStatus}")
            s.memory == MemoryLevel.LOW -> PerfAssessment(PerfLevel.ELEVATED, "memory=low")
            battery != null && battery >= BATTERY_ELEVATED_DECI_C -> PerfAssessment(PerfLevel.ELEVATED, batteryReason(s))
            s.cpuPressure -> PerfAssessment(PerfLevel.ELEVATED, "cpu=sustained")
            s.primaryBufferingMs >= BUFFERING_ELEVATED_MS -> PerfAssessment(PerfLevel.ELEVATED, "primaryBuffering=${s.primaryBufferingMs}ms")
            s.droppedFrames.atLeast(PerfLevel.ELEVATED) -> PerfAssessment(PerfLevel.ELEVATED, "droppedFrames=elevated")
            else -> PerfAssessment(PerfLevel.NORMAL, "calm")
        }
    }

    private fun batteryReason(s: PerfSignals) =
        "batteryTempC=${s.batteryTempDeciC / 10}.${Math.floorMod(s.batteryTempDeciC, 10)}" + if (s.charging) " charging" else ""

    /** Dropped-frame severity of one diagnostics window; -1 (unknown) never counts. */
    fun droppedSeverity(droppedPct: Int): PerfLevel = when {
        droppedPct >= DROPPED_HIGH_PCT -> PerfLevel.HIGH
        droppedPct >= DROPPED_ELEVATED_PCT -> PerfLevel.ELEVATED
        else -> PerfLevel.NORMAL
    }

    /**
     * Secondary (phone mirror) quality ceiling: [base] is the display-appropriate ceiling,
     * null means the secondary video is suspended.
     */
    fun secondaryCap(level: PerfLevel, base: String): String? = when (level) {
        PerfLevel.NORMAL -> base
        PerfLevel.ELEVATED -> "small"
        PerfLevel.HIGH, PerfLevel.SEVERE -> null
    }

    /** Primary player quality ceiling in pixels of height; 0 = no extra ceiling. Auto stays adaptive below it. */
    fun primaryCapHeight(level: PerfLevel): Int = when (level) {
        PerfLevel.NORMAL, PerfLevel.ELEVATED -> 0
        PerfLevel.HIGH -> 1080
        PerfLevel.SEVERE -> 720
    }

    /** A hidden secondary renderer is released sooner when resources are short. */
    fun hiddenSecondaryReleaseMs(level: PerfLevel): Long = when (level) {
        PerfLevel.NORMAL -> PhoneMirrorPolicy.HIDDEN_RELEASE_MS
        PerfLevel.ELEVATED -> 30_000L
        PerfLevel.HIGH, PerfLevel.SEVERE -> 10_000L
    }

    /** Multiplier for the page script's coalesced, nonessential DOM work. */
    fun pageWorkScale(level: PerfLevel): Int = when (level) {
        PerfLevel.NORMAL -> 1
        PerfLevel.ELEVATED, PerfLevel.HIGH -> 2
        PerfLevel.SEVERE -> 3
    }

    /** Page diagnostics window: rarer with the screen off or when every cycle matters. */
    fun diagnosticsIntervalMs(level: PerfLevel, screenOn: Boolean): Long = when {
        !screenOn -> 120_000L
        level.atLeast(PerfLevel.HIGH) -> 60_000L
        else -> 30_000L
    }

    /** Media-service health sampling. */
    fun healthIntervalMs(screenOn: Boolean): Long = if (screenOn) 60_000L else 180_000L
}

/** True only after [required] consecutive positive samples; any negative sample resets it. */
internal class SustainedSignal(private val required: Int = AdaptivePerformancePolicy.DROPPED_SAMPLES) {
    private var streak = 0
    fun update(value: Boolean): Boolean {
        streak = if (value) (streak + 1).coerceAtMost(required) else 0
        return active
    }
    val active: Boolean get() = streak >= required
    fun reset() { streak = 0 }
}

/** Sustained dropped frames: each level needs its own run of windows. */
internal class DroppedFrameTracker {
    private val elevated = SustainedSignal()
    private val high = SustainedSignal()
    fun update(droppedPct: Int): PerfLevel {
        val sev = AdaptivePerformancePolicy.droppedSeverity(droppedPct)
        elevated.update(sev.atLeast(PerfLevel.ELEVATED))
        high.update(sev.atLeast(PerfLevel.HIGH))
        return level
    }
    val level: PerfLevel get() = when {
        high.active -> PerfLevel.HIGH
        elevated.active -> PerfLevel.ELEVATED
        else -> PerfLevel.NORMAL
    }
    fun reset() { elevated.reset(); high.reset() }
}

/** The level with hysteresis. Returns a transition only when the level actually changes. */
internal class AdaptivePerformanceGovernor(
    private val calmStepMs: Long = AdaptivePerformancePolicy.CALM_STEP_MS,
    private val minDwellMs: Long = AdaptivePerformancePolicy.MIN_DWELL_MS,
) {
    private val never = Long.MIN_VALUE / 2
    var level = PerfLevel.NORMAL
        private set
    var reason = "calm"
        private set
    private var changedAt = never
    private var calmSince = never
    var transitions = 0
        private set

    fun update(signals: PerfSignals, nowMs: Long): PerfTransition? {
        val target = AdaptivePerformancePolicy.assess(signals)
        return when {
            target.level.ordinal > level.ordinal -> {
                calmSince = never
                change(target.level, target.reason, nowMs)
            }
            target.level.ordinal < level.ordinal -> {
                if (calmSince == never) calmSince = nowMs
                if (nowMs - calmSince >= calmStepMs && nowMs - changedAt >= minDwellMs) {
                    // One step at a time; the next step needs a fresh calm period.
                    calmSince = nowMs
                    change(PerfLevel.entries[level.ordinal - 1], "recovered(${target.reason})", nowMs)
                } else null
            }
            else -> {
                calmSince = never
                reason = target.reason
                null
            }
        }
    }

    private fun change(to: PerfLevel, why: String, nowMs: Long): PerfTransition {
        val t = PerfTransition(level, to, why, if (changedAt == never) 0L else nowMs - changedAt)
        level = to
        reason = why
        changedAt = nowMs
        transitions++
        return t
    }

    fun reset() {
        level = PerfLevel.NORMAL
        reason = "calm"
        changedAt = never
        calmSince = never
        transitions = 0
    }
}

/**
 * CPU wake lock bounds. A WebView is not a native player: the partial wake lock is needed
 * while it plays, but it is never held unbounded:
 *  - every acquisition carries a timeout and is refreshed while still wanted;
 *  - recovery of a stalled stream keeps it only for [STALL_LIMIT_MS]; after that the
 *    phone may sleep (the media session and explicit Play still work).
 */
internal object WakeLockPolicy {
    const val TIMEOUT_MS = 10 * 60_000L
    const val REFRESH_MS = 5 * 60_000L
    const val STALL_LIMIT_MS = 3 * 60_000L

    fun hold(wanted: Boolean, playing: Boolean, stalledSince: Long, nowMs: Long): Boolean = when {
        !wanted -> false
        playing -> true
        stalledSince <= 0L -> true
        else -> nowMs - stalledSince < STALL_LIMIT_MS
    }

    fun needsRefresh(acquiredAt: Long, nowMs: Long): Boolean = acquiredAt <= 0L || nowMs - acquiredAt >= REFRESH_MS
}

/**
 * Last-position persistence: Pause, Stop, Quit, end and video changes save at once;
 * while playing, at most every [PLAYING_INTERVAL_MS] ([SCREEN_OFF_INTERVAL_MS] with the
 * screen off) and only when the position actually moved.
 */
internal object PositionPersistPolicy {
    const val PLAYING_INTERVAL_MS = 15_000L
    const val SCREEN_OFF_INTERVAL_MS = 30_000L
    const val MIN_MOVE_MS = 5_000L

    /** [screenOn] is asked only when the interval decides (it is a binder call). */
    fun shouldSave(savedId: String, savedPosMs: Long, savedAtMs: Long, s: Snap, nowMs: Long, screenOn: () -> Boolean): Boolean = when {
        s.id.isBlank() -> false
        savedId != s.id || savedPosMs < 0L -> true
        !s.playing || s.ended || s.userPaused -> savedPosMs != s.posMs
        kotlin.math.abs(s.posMs - savedPosMs) < MIN_MOVE_MS -> false
        nowMs - savedAtMs >= SCREEN_OFF_INTERVAL_MS -> true
        nowMs - savedAtMs < PLAYING_INTERVAL_MS -> false
        else -> screenOn()
    }
}

/** Counters for structured diagnostics (renderer ownership, wake lock and level history). */
internal class PerfDiagnostics {
    var rendererAllocations = 0
    var rendererReleases = 0
    var secondaryAllocations = 0
    var secondaryReleases = 0
    var levelTransitions = 0
    var thermalChanges = 0
    var lastThermal = -1
    var batteryTempDeciC = PerfSignals.UNKNOWN_TEMP
    var wakeHeldMs = 0L
    var wakeAcquisitions = 0
    var wakeStallReleases = 0

    fun noteThermal(status: Int) {
        if (lastThermal >= 0 && status != lastThermal) thermalChanges++
        lastThermal = status
    }

    /** Renderers alive now; never negative. */
    fun liveRenderers(): Int = (rendererAllocations - rendererReleases).coerceAtLeast(0) +
        (secondaryAllocations - secondaryReleases).coerceAtLeast(0)

    fun summary(): String = "renderers alloc=$rendererAllocations release=$rendererReleases " +
        "secondary alloc=$secondaryAllocations release=$secondaryReleases live=${liveRenderers()} " +
        "levelTransitions=$levelTransitions thermalChanges=$thermalChanges " +
        "batteryTempC=${if (batteryTempDeciC == PerfSignals.UNKNOWN_TEMP) "unknown" else (batteryTempDeciC / 10.0).toString()}"
}
