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

