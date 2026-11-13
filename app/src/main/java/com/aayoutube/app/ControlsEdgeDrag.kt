package com.aayoutube.app

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/**
 * Drag the floating controls along their edge only: up/down on the chosen side
 * in portrait, left/right along the top in landscape. The spot is saved (one
 * value per orientation, shared by phone and car) when the finger lifts. Taps
 * on the buttons still work: a drag starts only past the touch slop.
 */
@Composable
internal fun rememberEdgeDrag(
    left: Boolean,
    landscape: Boolean,
    containerPx: Float,
    onActivity: () -> Unit,
): Pair<Alignment, Modifier> {
    val flags by Features.state.collectAsState()
    var pos by remember(landscape) { mutableFloatStateOf(ControlsSidePolicy.saved(flags, landscape)) }
    var barPx by remember(landscape) { mutableFloatStateOf(0f) }
    val travel by rememberUpdatedState((containerPx - barPx).coerceAtLeast(0f))
    val activity by rememberUpdatedState(onActivity)
    val modifier = Modifier
        .onSizeChanged { barPx = (if (landscape) it.width else it.height).toFloat() }
        .pointerInput(landscape) {
            val commit = { Features.update { ControlsSidePolicy.save(it, landscape, pos) } }
            detectDragGestures(onDragEnd = { commit() }, onDragCancel = { commit() }) { change, d ->
                change.consume()
                pos = ControlsSidePolicy.drag(pos, if (landscape) d.x else d.y, travel)
                activity()
            }
        }
    return ControlsSidePolicy.alignment(left, landscape, pos) to modifier
}
