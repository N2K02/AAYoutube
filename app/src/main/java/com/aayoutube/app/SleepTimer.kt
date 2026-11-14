package com.aayoutube.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Monotonic deadline avoids wall-clock changes altering a running sleep timer. */
internal object SleepTimerPolicy {
    const val DEFAULT_MINUTES = 15L
    const val MAX_MINUTES = 24L * 60L
    /** One tap adjusts the selected unit without going below 1 or above 24 hours. */
    fun adjustAmount(current: Long?, delta: Int, hours: Boolean): Long {
        val maximum = if (hours) 24L else MAX_MINUTES
        val base = current?.coerceIn(1L, maximum) ?: if (hours) 1L else DEFAULT_MINUTES
        return (base + delta.toLong()).coerceIn(1L, maximum)
    }
    fun durationMillis(amount: Long, hours: Boolean): Long? {
        if (amount <= 0) return null
        if (hours && amount > 24L) return null
        val minutes = if (hours) amount * 60L else amount
        if (minutes > MAX_MINUTES) return null
        return minutes * 60_000L
    }
    fun remainingMillis(deadline: Long, now: Long): Long = (deadline - now).coerceAtLeast(0L)
    /** Rounded up so the notification never shows 0 minutes before expiry. */
    fun notificationMinutes(ms: Long?): Long? = ms?.takeIf { it > 0L }?.let { (it - 1L) / 60_000L + 1L }
    fun formatRemaining(ms: Long): String {
        val seconds = (ms + 999L) / 1000L
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val rest = seconds % 60L
        return if (hours > 0L) "%02d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, rest)
        else "%02d:%02d".format(java.util.Locale.ROOT, minutes, rest)
    }
}

/** Only duration preferences are persisted; starting a countdown always requires a tap. */
internal object SleepTimerSettings {
    private const val PREFS = "sleep_timer_settings"
    private const val AMOUNT = "duration_amount"
    private const val HOURS = "duration_hours"

    fun restoredAmount(saved: Long, hours: Boolean): Long =
        saved.takeIf { SleepTimerPolicy.durationMillis(it, hours) != null }
            ?: if (hours) 1L else SleepTimerPolicy.DEFAULT_MINUTES

    fun loadHours(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HOURS, false)

    fun loadAmount(context: Context, hours: Boolean): Long {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(AMOUNT, if (hours) 1L else SleepTimerPolicy.DEFAULT_MINUTES)
        return restoredAmount(saved, hours)
    }

    fun saveSelection(context: Context, amount: Long, hours: Boolean) {
        if (SleepTimerPolicy.durationMillis(amount, hours) == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(AMOUNT, amount).putBoolean(HOURS, hours).apply()
    }
}

/** Process-wide countdown: stays active while navigating between phone/car and Settings. */
internal object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    private val _remaining = MutableStateFlow<Long?>(null)
    val remaining: StateFlow<Long?> = _remaining
    private var deadline = 0L
    // Rounded minute changes only; Compose chip does not recompose every second.
    private val _chipMinutes = MutableStateFlow<Long?>(null)
    val chipMinutes: StateFlow<Long?> = _chipMinutes
    private fun updateChipMinutes() {
        _chipMinutes.value = SleepTimerPolicy.notificationMinutes(_remaining.value)
    }
    private val tick = object : Runnable {
        override fun run() {
            if (deadline == 0L) return
            val left = SleepTimerPolicy.remainingMillis(deadline, SystemClock.elapsedRealtime())
            if (left == 0L) {
                deadline = 0L
                _remaining.value = null
                updateChipMinutes()
                AppLog.add("TIMER", "Sleep timer elapsed; requesting full app shutdown")
                // Reuse Quit's media-service cleanup even when no phone Activity is visible.
                val context = appContext
                if (context != null) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, PlaybackService::class.java)
                                .setAction(PlaybackService.ACTION_QUIT),
                        )
                    }.onFailure {
                        AppLog.add("TIMER", "Service Quit delivery failed: " + it.javaClass.simpleName)
                    }
                }
                // Fallback if the media service is unavailable or its IPC is stale.
                handler.postDelayed({
                    val close = WebHost.onQuit ?: WebHost.onCarQuit
                    if (close != null) close()
                    else {
                        WebHost.cmd("pause")
                        BrowseHost.release()
                        WebHost.release()
                    }
                }, 1_800L)
            } else {
                _remaining.value = left
                updateChipMinutes()
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
        updateChipMinutes()
        AppLog.add("TIMER", "Sleep timer started for ${duration / 60000} minutes")
        handler.postDelayed(tick, minOf(duration, 1000L))
        return true
    }

    fun cancel() {
        handler.removeCallbacks(tick)
        deadline = 0L
        _remaining.value = null
        updateChipMinutes()
    }
}
