package com.aayoutube.app

/**
 * Keep the app header and Settings control visible on phones and Android Auto.
 * Fullscreen video (YouTube's own or the landscape fill) shows it on touch, like
 * any watch page, and it auto-hides; only picture-in-picture never shows it.
 */
internal fun showAAYoutubeHeader(
    onWatchPage: Boolean,
    onCarDisplay: Boolean,
    inPictureInPicture: Boolean,
    fullscreenVideo: Boolean,
): Boolean = !inPictureInPicture
