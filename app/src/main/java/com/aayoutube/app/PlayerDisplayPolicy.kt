package com.aayoutube.app

/** Recreate Chromium only when both real display IDs are known and differ. */
internal object PlayerDisplayPolicy {
    fun shouldRecreate(created: Int?, target: Int?): Boolean =
        created != null && target != null && created != target
}
