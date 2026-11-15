package com.aayoutube.app

/** ACTION_AUDIO_BECOMING_NOISY: headphones or Bluetooth audio just disconnected. */
internal object NoisyAudioPolicy {
    const val ACTION = "android.media.AUDIO_BECOMING_NOISY"

    /** Pause only real playback; never while quitting. A car disconnect also pauses (as the YouTube app). */
    fun shouldPause(action: String?, playing: Boolean, quitting: Boolean): Boolean =
        action == ACTION && playing && !quitting
}
