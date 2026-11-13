package com.aayoutube.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Shared phone / projected car behaviour for the floating video controls:
 * any touch on the screen shows them (and restarts the auto-hide timer).
 * The touch is only observed (Initial pass, nothing consumed), so it still
 * reaches YouTube's page, its controls and the WebView underneath.
 */
internal fun Modifier.revealControlsOnTouch(onTouch: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) onTouch()
            }
        }
    }

/**
 * Where the floating controls sit. Portrait: a vertical rail on the left or
 * right (Features > Video controls side). Landscape (phone or Android Auto
 * video): always a horizontal pill at the top centre, and the side setting
 * is ignored and shown disabled. The saved side is kept for portrait.
 */
internal object ControlsSidePolicy {
    const val CENTER = 0.5f

    /** [pos] 0..1 along the edge: top..bottom in portrait, left..right in landscape. */
    fun alignment(left: Boolean, landscape: Boolean = false, pos: Float = CENTER): Alignment {
        val along = bias(pos)
        return if (landscape) BiasAlignment(along, -1f)
        else BiasAlignment(if (left) -1f else 1f, along)
    }
    fun bias(pos: Float): Float = normalize(pos) * 2f - 1f
    fun normalize(pos: Float): Float = if (pos.isNaN()) CENTER else pos.coerceIn(0f, 1f)
    /** Move by a finger delta; [travelPx] is the free length of the edge. Never leaves it. */
    fun drag(pos: Float, deltaPx: Float, travelPx: Float): Float =
        if (travelPx <= 0f) normalize(pos) else normalize(pos + deltaPx / travelPx)
    fun saved(f: FeatureFlags, landscape: Boolean): Float =
        normalize(if (landscape) f.controlsPosLandscape else f.controlsPosPortrait)
    fun save(f: FeatureFlags, landscape: Boolean, pos: Float): FeatureFlags =
        if (landscape) f.copy(controlsPosLandscape = normalize(pos))
        else f.copy(controlsPosPortrait = normalize(pos))
    fun vertical(landscape: Boolean): Boolean = !landscape
    fun sideSettingEnabled(landscape: Boolean): Boolean = !landscape
    fun isLandscape(width: Float, height: Float): Boolean = width > height
    fun label(left: Boolean): String = if (left) "Left" else "Right"
}
