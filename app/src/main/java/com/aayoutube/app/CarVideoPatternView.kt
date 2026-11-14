package com.aayoutube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Static synthetic image directly drawn by the current car Compose view.
 * No WebView swap, SurfaceView, virtual display, pixel capture or media change.
 * It disappears after the probe's 3-second main-thread timeout.
 */
@Composable
internal fun CarVideoPatternView() {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            val bars = listOf(
                Color.White,
                Color.Yellow,
                Color.Cyan,
                Color.Green,
                Color.Magenta,
                Color.Red,
                Color.Blue,
            )
            bars.forEach { color ->
                Box(Modifier.weight(1f).fillMaxHeight().background(color))
            }
        }
        Box(Modifier.fillMaxWidth().height(76.dp).background(Color.Black)) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "AAYouTube — CAR DISPLAY TEST",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Temporary 3-second test image. Video playback is unchanged.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
