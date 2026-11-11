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

/**
 * Where the floating controls sit. Portrait: a vertical rail on the left or
 * right (Features > Video controls side). Landscape (phone or Android Auto
 * video): always a horizontal pill at the top centre, and the side setting
 * is ignored and shown disabled. The saved side is kept for portrait.
 */
internal object ControlsSidePolicy {
    fun alignment(left: Boolean, landscape: Boolean = false): Alignment = when {
        landscape -> Alignment.TopCenter
        left -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    fun vertical(landscape: Boolean): Boolean = !landscape
    fun sideSettingEnabled(landscape: Boolean): Boolean = !landscape
    fun isLandscape(width: Float, height: Float): Boolean = width > height
    fun label(left: Boolean): String = if (left) "Left" else "Right"
}

