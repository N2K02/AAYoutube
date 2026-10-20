package com.aayoutube.app

/**
 * The media service with Android Auto's media player switched on (Features → Android Auto).
 * Same code as [PlaybackService]; only its manifest entry differs (it carries the
 * MediaBrowserService filter Android Auto looks for). Only one of the two is ever enabled,
 * see [CarMode].
 */
class AutoPlaybackService : PlaybackService()
