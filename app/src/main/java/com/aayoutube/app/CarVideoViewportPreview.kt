package com.aayoutube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Headless geometry preview of common Android Auto display aspect ratios.
 * Deliberately never creates a second WebView, virtual display or media output.
 */
@Composable
internal fun CarVideoViewportPreview() {
    var selected by remember { mutableIntStateOf(1) }
    val preset = CarVideoViewportPolicy.presets[selected]
    val video = CarVideoViewportPolicy.fit16by9(preset.width, preset.height)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Car display preview", style = MaterialTheme.typography.titleMedium)
            Text(
                "Simulate common screen sizes without connecting to Android Auto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CarVideoViewportPolicy.presets.withIndex().toList().chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (index, item) ->
                        if (index == selected) {
                            Button(onClick = { selected = index }, modifier = Modifier.weight(1f)) {
                                Text(item.label, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { selected = index }, modifier = Modifier.weight(1f)) {
                                Text(item.label, maxLines = 1)
                            }
                        }
                    }
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth()
                    .aspectRatio(preset.width.toFloat() / preset.height.toFloat())
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
            ) {
                val scale = minOf(maxWidth.value / preset.width, maxHeight.value / preset.height)
                Box(
                    Modifier.align(Alignment.Center)
                        .size((video.width * scale).dp, (video.height * scale).dp)
                        .background(Color.DarkGray)
                ) {
                    Text(
                        "16:9 video",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                Text(
                    "CAR ${preset.label}",
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
            Text(
                "Video area: ${video.width}×${video.height} at ${video.left},${video.top}. " +
                    "Geometry preview only — not an actual mirrored or decoded video frame.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
