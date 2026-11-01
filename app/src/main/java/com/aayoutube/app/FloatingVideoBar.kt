package com.aayoutube.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Size of the small floating video-controls rail; [car] uses bigger touch targets. */
internal data class BarSize(val button: Dp, val icon: Dp) {
    companion object {
        val Phone = BarSize(button = 44.dp, icon = 22.dp)
        val Car = BarSize(button = 52.dp, icon = 26.dp)
        fun of(car: Boolean) = if (car) Car else Phone
    }
}

/**
 * The video controls on the phone and on Android Auto: a small floating rail,
 * icons only (each with a content description), vertical on the left or right
 * side (Features > Video controls side). It overlays the WebView, so showing
 * or hiding it never resizes the video or YouTube's own menus.
 */
@Composable
internal fun FloatingVideoBar(
    playing: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    car: Boolean = false,
    vertical: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    val size = BarSize.of(car)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        val icons: @Composable () -> Unit = {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", size, onBack)
            BarIcon(Icons.Filled.Home, "YouTube home", size, onHome)
            BarIcon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "Pause" else "Play", size, onPlayPause,
            )
            BarIcon(Icons.Filled.SkipPrevious, "Previous video", size, onPrevious)
            BarIcon(Icons.Filled.SkipNext, "Next video", size, onNext)
            extra()
        }
        if (vertical) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { icons() }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { icons() }
        }
    }
}

@Composable
internal fun BarIcon(
    image: ImageVector,
    description: String,
    size: BarSize,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.button)) {
        Box(contentAlignment = Alignment.Center) {
            if (tint != null) Icon(image, contentDescription = description, tint = tint, modifier = Modifier.size(size.icon))
            else Icon(image, contentDescription = description, modifier = Modifier.size(size.icon))
        }
    }
}
