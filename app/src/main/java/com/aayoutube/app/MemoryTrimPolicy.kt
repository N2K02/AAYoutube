package com.aayoutube.app

import android.content.ComponentCallbacks2

internal object MemoryTrimPolicy {
    /** App UI hidden (or memory running low) and the browse page is not what the user sees. */
    fun releaseBrowse(level: Int, browseInUse: Boolean): Boolean =
        !browseInUse && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
}
