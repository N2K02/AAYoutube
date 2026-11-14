package com.aayoutube.app

/**
 * YouTube's fullscreen / minimise button where AAYoutube fills the screen itself
 * (phone held sideways, Android Auto). As AABrowser's fullscreen and back: the
 * button switches between the filled video and the watch page with its playlist.
 */
internal object FilledVideoToggle {
    enum class Target { CarWatchPage, CarFilled, PhoneWatchPage, PhoneFilled, None }

    fun target(car: Boolean, landscape: Boolean, carPage: Boolean, landscapeFill: Boolean): Target = when {
        car -> if (carPage) Target.CarFilled else Target.CarWatchPage
        landscape -> if (landscapeFill) Target.PhoneWatchPage else Target.PhoneFilled
        // Portrait phone keeps YouTube's own fullscreen; the page never asks here.
        else -> Target.None
    }
}
