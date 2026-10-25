package com.aayoutube.app

/**
 * Keep the phone's app identity visible on every page, while leaving video unobstructed
 * in picture-in-picture, fullscreen, and the car's immersive YouTube screen.
 */
internal fun showAAYoutubeHeader(
    onWatchPage: Boolean,
    onCarDisplay: Boolean,
    inPictureInPicture: Boolean,
    fullscreenVideo: Boolean,
): Boolean = !inPictureInPicture && !fullscreenVideo && !(onCarDisplay && onWatchPage)
