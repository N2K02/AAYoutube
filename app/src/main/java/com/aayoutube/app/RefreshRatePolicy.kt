package com.aayoutube.app

/** When the phone screen may drop to 60 Hz to save GPU and display power. */
internal object RefreshRatePolicy {
    fun capTo60(playing: Boolean, watchTab: Boolean, fullPlayer: Boolean, inPip: Boolean, car: Boolean): Boolean =
        playing && !car && (inPip || (watchTab && fullPlayer))
}
