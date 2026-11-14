package com.aayoutube.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Monotonic deadline avoids wall-clock changes altering a running sleep timer. */
internal object SleepTimerPolicy {
    const val DEFAULT_MINUTES = 15L
    const val MAX_MINUTES = 24L * 60L
    fun durationMillis(amount: Long, hours: Boolean): Long? {
        if (amount <= 0) return null
        val minutes = if (hours) amount * 60L else amount
        if (hours && amount > 24 || minutes > MAX_MINUTES) return null
        return minutes * 60_000L
    }
    fun remainingMillis(deadline: Long, now: Long): Long = (deadline - now).coerceAtLeast(0L)
    fun formatRemaining(ms: Long): String {
        val seconds = (ms + 999L) / 1000L
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val rest = seconds % 60L
        return if (hours > 0L) "%02d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, rest)
        else "%02d:%02d".format(java.util.Locale.ROOT, minutes, rest)
    }
}

/** Process-wide countdown: stays active while navigating between phone/car and Settings. */
internal object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val _remaining = MutableStateFlow<Long?>(null)
    val remaining: StateFlow<Long?> = _remaining
    private var deadline = 0L
    private var serial = 0
    private val tick = object : Runnable {
        override fun run() {
            if (deadline == 0L) return
            val left = SleepTimerPolicy.remainingMillis(deadline, SystemClock.elapsedRealtime())
            if (left == 0L) {
                deadline = 0L
                _remaining.value = null
                serial++
                AppLog.add("TIMER", "Sleep timer elapsed; pausing playback")
                WebHost.cmd("pause")
            } else {
                _remaining.value = left
                handler.postDelayed(this, minOf(left, 1000L))
            }
        }
    }

    fun start(amount: Long, hours: Boolean): Boolean {
        val duration = SleepTimerPolicy.durationMillis(amount, hours) ?: return false
        // Cancel old callback before replacing an active timer.
        handler.removeCallbacks(tick)
        deadline = SystemClock.elapsedRealtime() + duration
        _remaining.value = duration
        serial++
        AppLog.add("TIMER", "Sleep timer started for ${duration / 60000} minutes")
        handler.postDelayed(tick, minOf(duration, 1000L))
        return true
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        deadline = 0L
        _remaining.value = null
        serial++
    }
}
