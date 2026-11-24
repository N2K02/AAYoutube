package com.aayoutube.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Size of the small floating video-controls rail; [car] uses bigger touch targets. */
internal data class BarSize(val button: Dp, val icon: Dp) {
    companion object {
        val Phone = BarSize(button = 44.dp, icon = 24.dp)
        /** Compact variant for the horizontal pill so all icons fit without scrolling. */
        val PhoneCompact = BarSize(button = 36.dp, icon = 20.dp)
        val Car = BarSize(button = 52.dp, icon = 28.dp)
        fun of(car: Boolean) = if (car) Car else Phone
    }
}

/** Pure completion rule for the three transport buttons.
 * In-flight actions cannot be repeated until playback acknowledges them or
 * a bounded timeout makes controls usable again. */
internal object OverlayTransportPolicy {
    fun ready(playbackId: String, selectedVideoId: String, buffering: Boolean, ended: Boolean): Boolean =
        playbackId.isNotBlank() && selectedVideoId == playbackId && !buffering && !ended
}

internal data class OverlayTransportLock(
    val command: String,
    val sourceId: String,
    val wasPlaying: Boolean,
) {
    fun completed(videoId: String, playing: Boolean): Boolean = when (command) {
        "play" -> playing && videoId.isNotBlank()
        "pause" -> !playing
        "next", "prev" -> videoId.isNotBlank() && videoId != sourceId
        else -> true
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
    playbackId: String = "",
    transportReady: Boolean = false,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    car: Boolean = false,
    vertical: Boolean = true,
    extra: @Composable (size: BarSize) -> Unit = {},
) {
    // In landscape (horizontal pill) use compact icons so all fit without scrolling.
    val size = when {
        car -> BarSize.Car
        !vertical -> BarSize.PhoneCompact
        else -> BarSize.Phone
    }
    var pending by remember { mutableStateOf<OverlayTransportLock?>(null) }
    // Ignore duplicate taps for all three transport buttons while the page
    // catches up. Back/Home/settings are deliberately not blocked.
    LaunchedEffect(playbackId, playing) {
        if (pending?.completed(playbackId, playing) == true) pending = null
    }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(5_000L)
            pending = null // no permanent disabled controls on stalled playback
        }
    }
    val transportEnabled = transportReady && pending == null
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
            // Play/Pause uses a filled tonal container to stand out as the primary action.
            FilledTonalIconButton(
                onClick = {
                    if (transportEnabled) {
                        pending = OverlayTransportLock(if (playing) "pause" else "play", playbackId, playing)
                        onPlayPause()
                    }
                },
                enabled = transportEnabled,
                modifier = Modifier.size(size.button),
                colors = IconButtonDefaults.filledTonalIconButtonColors(),
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(size.icon),
                )
            }
            BarIcon(Icons.Filled.SkipPrevious, "Previous video", size, onClick = {
                if (transportEnabled) {
                    pending = OverlayTransportLock("prev", playbackId, playing)
                    onPrevious()
                }
            }, enabled = transportEnabled)
            BarIcon(Icons.Filled.SkipNext, "Next video", size, onClick = {
                if (transportEnabled) {
                    pending = OverlayTransportLock("next", playbackId, playing)
                    onNext()
                }
            }, enabled = transportEnabled)
            extra(size)
        }
        if (vertical) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { icons() }
        } else {
            // Horizontal rail (landscape). Compact icon size ensures all buttons
            // fit in one row without scrolling (9 × 36dp = 324dp ≤ 360dp min width).
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
    val resolvedTint = when {
        !enabled -> (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size.button)) {
        Icon(image, contentDescription = description, tint = resolvedTint, modifier = Modifier.size(size.icon))
    }
}
