package com.aayoutube.app

import android.media.AudioDeviceInfo

/** Where native audio is heard; decides offload eligibility. */
internal enum class AudioRoute { SPEAKER, WIRED, BLUETOOTH_A2DP, BLUETOOTH_LE, USB, CAR_PROJECTION, OTHER }

internal data class OffloadInputs(
    val route: AudioRoute,
    val codecMime: String,
    /** AudioManager's answer for this format on the current route. */
    val deviceSupportsOffload: Boolean,
    val speed: Float = 1f,
    /** Experimental offload switch (part of native audio mode). */
    val allowed: Boolean = true,
    /** A recent offload failure for this codec and route. */
    val blockedByFailure: Boolean = false,
)

internal data class OffloadDecision(val enabled: Boolean, val reason: String)

/**
 * Hardware audio offload only when everything agrees. Offload hands compressed audio to the
 * DSP and lets the CPU sleep, but it bypasses parts of the mixer:
 *  - Android Auto projection captures media audio for the car: never offloaded;
 *  - speed changes are not supported by offloaded tracks on most devices;
 *  - codecs the route's audio HAL does not accept are refused by AudioManager itself;
 *  - a codec/route pair that failed recently stays off ([OffloadFailureBreaker]).
 */
internal object AudioOffloadPolicy {
    private val OFFLOAD_CODECS = setOf(StreamSelection.AAC, StreamSelection.OPUS, StreamSelection.MP3,
        StreamSelection.AC3, StreamSelection.EAC3)

    fun decide(i: OffloadInputs): OffloadDecision = when {
        !i.allowed -> OffloadDecision(false, "disabled")
        i.route == AudioRoute.CAR_PROJECTION -> OffloadDecision(false, "route=car-projection")
        i.route == AudioRoute.OTHER -> OffloadDecision(false, "route=unknown")
        i.codecMime.lowercase() !in OFFLOAD_CODECS -> OffloadDecision(false, "codec=${i.codecMime}")
        kotlin.math.abs(i.speed - 1f) > 0.001f -> OffloadDecision(false, "speed=${i.speed}")
        i.blockedByFailure -> OffloadDecision(false, "recent-failure")
        !i.deviceSupportsOffload -> OffloadDecision(false, "device-unsupported")
        else -> OffloadDecision(true, "compatible route=${i.route} codec=${i.codecMime}")
    }

    /** Route from the output devices the media attributes resolve to (first = active). */
    fun routeOf(deviceTypes: List<Int>, carProjection: Boolean): AudioRoute {
        if (carProjection) return AudioRoute.CAR_PROJECTION
        val t = deviceTypes.firstOrNull() ?: return AudioRoute.OTHER
        return when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioRoute.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioRoute.BLUETOOTH_LE
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRoute.USB
            else -> AudioRoute.OTHER
        }
    }

    fun failureKey(codecMime: String, route: AudioRoute) = "${codecMime.lowercase()}|$route"
}

/** Minimal persistent store so the breaker survives restarts (SharedPreferences in the app). */
internal interface LongStore {
    fun get(key: String): Long
    fun put(key: String, value: Long)
    fun remove(key: String)
}

/**
 * Turns offload off automatically after it causes a playback failure: the codec/route pair is
 * blocked for [BLOCK_MS]. A further failure while blocked extends the block.
 */
internal class OffloadFailureBreaker(private val store: LongStore) {
    fun recordFailure(key: String, nowMs: Long) = store.put(PREFIX + key, nowMs)
    fun isBlocked(key: String, nowMs: Long): Boolean {
        val at = store.get(PREFIX + key)
        if (at <= 0L) return false
        if (nowMs - at >= BLOCK_MS || nowMs < at) { store.remove(PREFIX + key); return false }
        return true
    }

    companion object {
        const val BLOCK_MS = 24 * 60 * 60_000L
        private const val PREFIX = "offloadFail:"
    }
}
