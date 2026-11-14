package com.aayoutube.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Phone-only in-app chip; requires no system overlay permission or notification. */
@Composable
internal fun SleepTimerChip(onClick: () -> Unit) {
    val minutes by SleepTimer.chipMinutes.collectAsState()
    val shown = minutes ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boundsX = with(density) { (maxWidth - 148.dp).coerceAtLeast(0.dp).toPx() }
        val boundsY = with(density) { (maxHeight - 58.dp).coerceAtLeast(0.dp).toPx() }
        val defaultY = with(density) { 80.dp.toPx() }.coerceAtMost(boundsY)
        var x by remember(boundsX) { mutableFloatStateOf(boundsX) }
        var y by remember(boundsY) { mutableFloatStateOf(defaultY) }
        Surface(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .pointerInput(boundsX, boundsY) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, boundsX)
                        y = (y + drag.y).coerceIn(0f, boundsY)
                    }
                }
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Sleep in: ${shown} min", fontSize = 12.sp,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
