package com.aayoutube.app

/**
 * Keep the app header and Settings control visible on phones and Android Auto.
 * Hide the header only for picture-in-picture and fullscreen video.
 */
internal fun showAAYoutubeHeader(
    onWatchPage: Boolean,
    onCarDisplay: Boolean,
    inPictureInPicture: Boolean,
    fullscreenVideo: Boolean,
): Boolean = !inPictureInPicture && !fullscreenVideo
