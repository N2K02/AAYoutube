package com.aayoutube.app

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** AABrowser's root: transient car-surface invisibility must not pause video decoding. */
class CarFrameLayout(context: Context) : FrameLayout(context) {
    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) super.dispatchWindowVisibilityChanged(visibility)
    }
    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (visibility == View.VISIBLE) super.dispatchVisibilityChanged(changedView, visibility)
    }
}
