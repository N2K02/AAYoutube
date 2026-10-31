package com.aayoutube.app

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Shared phone / projected car gesture. The caller must attach this only to
 * the 28dp top edge while its app-owned header is hidden.
 *
 * A click or an upward/horizontal swipe does not show the header. The pointer
 * must begin inside the edge and drag down by at least 24dp.
 */
internal fun Modifier.revealTopMenuOnSwipeDown(onReveal: () -> Unit): Modifier =
    pointerInput(onReveal) {
        var down = 0f
        var didReveal = false
        detectVerticalDragGestures(
            onDragStart = { down = 0f; didReveal = false },
            onVerticalDrag = { change, delta ->
                down = (down + delta).coerceAtLeast(0f)
                if (!didReveal && TopMenuSwipePolicy.shouldReveal(down, 24.dp.toPx())) {
                    didReveal = true
                    change.consume()
                    onReveal()
                }
            },
        )
    }

/** Unit-testable downward-swipe threshold; no click-to-reveal fallback. */
internal object TopMenuSwipePolicy {
    fun shouldReveal(downwardPx: Float, thresholdPx: Float): Boolean =
        downwardPx.isFinite() && thresholdPx > 0f && downwardPx >= thresholdPx
}
