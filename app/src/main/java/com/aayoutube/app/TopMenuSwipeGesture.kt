package com.aayoutube.app

import androidx.compose.ui.Alignment
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

/** Which side the vertical controls rail sits on (Features > Video controls side). */
internal object ControlsSidePolicy {
    fun alignment(left: Boolean): Alignment = if (left) Alignment.CenterStart else Alignment.CenterEnd
    fun label(left: Boolean): String = if (left) "Left" else "Right"
}

/**
 * Horizontal limits of the draggable phone mini player. The controls rail
 * (≈44dp buttons + margins) keeps a reserved strip on its side.
 */
internal object MiniPlayerBounds {
    const val RAIL_RESERVE_DP = 64

    fun minX(edge: Float, rail: Float, railOnLeft: Boolean): Float =
        if (railOnLeft) edge + rail else edge

    fun maxX(width: Float, miniWidth: Float, edge: Float, rail: Float, railOnLeft: Boolean): Float {
        val right = width - miniWidth - edge - (if (railOnLeft) 0f else rail)
        return right.coerceAtLeast(minX(edge, rail, railOnLeft))
    }
}
