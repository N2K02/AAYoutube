package com.aayoutube.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient

/** Fullscreen view is owned by the activity and renderer that accepted it. */
internal class MirrorFullscreen {
    private var view: View? = null
    private var owner: Activity? = null
    private var closing = false
    private var callback: WebChromeClient.CustomViewCallback? = null

    fun show(activity: Activity?, next: View, cb: WebChromeClient.CustomViewCallback, current: Boolean) {
        val host = activity?.window?.decorView as? ViewGroup
        if (!current || view != null || closing || activity == null || host == null || activity.isFinishing || activity.isDestroyed) {
            cb.onCustomViewHidden()
            return
        }
        view = next
        owner = activity
        callback = cb
        host.addView(next, ViewGroup.LayoutParams(-1, -1))
    }

    fun detachUnless(activity: Activity?) { if (owner != null && owner !== activity) hide() }
    fun exit(activity: Activity): Boolean {
        if (owner !== activity || view == null) return false
        hide()
        return true
    }
    fun hide() {
        val v = view ?: return
        val cb = callback
        view = null; owner = null; callback = null
        closing = cb != null
        (v.parent as? ViewGroup)?.removeView(v)
        cb?.onCustomViewHidden()
    }
    fun onHidden() {
        if (closing) { closing = false; return }
        val v = view ?: return
        view = null; owner = null; callback = null
        (v.parent as? ViewGroup)?.removeView(v)
    }
    fun reset() { hide(); closing = false }
}
