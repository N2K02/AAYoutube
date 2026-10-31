package com.aayoutube.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The video controls on the phone and on Android Auto: one floating pill,
 * centred at the top, icons only (each with a content description). It overlays
 * the WebView, so showing or hiding it never resizes the video surface.
 * [large] uses car-sized touch targets.
 */
@Composable
internal fun FloatingVideoBar(
    playing: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val button = if (large) 60.dp else 48.dp
    val icon = if (large) 30.dp else 24.dp
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", button, icon, onBack)
            BarIcon(Icons.Filled.Home, "YouTube home", button, icon, onHome)
            BarIcon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Pause" else "Play", button, icon, onPlayPause,
            )
            BarIcon(Icons.Filled.SkipNext, "Next video", button, icon, onNext)
            extra()
        }
    }
}

@Composable
internal fun BarIcon(
    image: ImageVector,
    description: String,
    button: Dp,
    icon: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(button)) {
        Box(contentAlignment = Alignment.Center) {
            if (tint != null) Icon(image, contentDescription = description, tint = tint, modifier = Modifier.size(icon))
            else Icon(image, contentDescription = description, modifier = Modifier.size(icon))
        }
    }
}
