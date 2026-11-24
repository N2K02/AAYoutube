package com.aayoutube.app

/**
 * Keep the app header and Settings control visible on phones and Android Auto.
 * Fullscreen video (YouTube's own or the landscape fill) shows it on touch, like
 * any watch page, and it auto-hides; only picture-in-picture and phone-while-
 * projecting-to-car never show it (the WebView is on the car screen, not here).
 */
internal fun showAAYoutubeHeader(
    onWatchPage: Boolean,
    onCarDisplay: Boolean,
    inPictureInPicture: Boolean,
    fullscreenVideo: Boolean,
    projectedToCar: Boolean = false,
): Boolean = !inPictureInPicture && !(projectedToCar && !onCarDisplay)
