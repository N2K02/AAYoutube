package com.aayoutube.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Compact mRadio-style controls: switch, 3D modes, presets, bands, bass. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerSheet(onDismiss: () -> Unit) {
    val s by AAYSoundFx.settings.collectAsState()
    val webAudio by AAYSoundFx.webAudio.collectAsState()
    // Native fallback can only approximate some 3D modes.
    val fallback = webAudio == WebAudioEqState.UNSUPPORTED
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Equalizer", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { AAYSoundFx.reset() }) { Text("Reset") }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = { enabled ->
                        AAYSoundFx.update { it.copy(enabled = enabled) }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Equalizer"
                    },
                )
            }
            Spacer(Modifier.size(12.dp))

            EqSection("3D sound")
            EqChipRow {
                // All mRadio modes run in the in-player DSP. The native fallback
                // can only approximate those with mode.systemApproximation.
                SoundSpaceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.mode == mode,
                        enabled = !fallback || mode.systemApproximation,
                        onClick = { AAYSoundFx.update { it.copy(mode = mode) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (s.mode != SoundSpaceMode.OFF) {
                Text(
                    s.mode.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EqSlider(
                    label = "Strength",
                    value = s.intensity,
                    range = 0f..1f,
                    valueText = "${(s.intensity * 100).roundToInt()}%",
                    onChange = { v ->
                        AAYSoundFx.update { it.copy(intensity = v) }
                    },
                )
            }

            Spacer(Modifier.size(12.dp))
            EqSection("Presets")
            EqChipRow {
                SoundEqPolicy.presets.forEach { (name, _) ->
                    FilterChip(
                        selected = s.preset == name,
                        onClick = { AAYSoundFx.applyPreset(name) },
                        label = { Text(name) },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            SoundEqPolicy.bandHz.forEachIndexed { index, hz ->
                val db = s.bands.getOrElse(index) { 0f }
                EqSlider(
                    label = when (hz) {
                        3600 -> "3.6 kHz"
                        14000 -> "14 kHz"
                        else -> "$hz Hz"
                    },
                    value = db,
                    range = -12f..12f,
                    steps = 23,
                    valueText = if (db.roundToInt() > 0) "+${db.roundToInt()} dB"
                        else "${db.roundToInt()} dB",
                    onChange = { v -> AAYSoundFx.setBand(index, v) },
                )
            }
            EqSlider(
                label = "Bass boost",
                value = s.bass,
                range = 0f..1f,
                valueText = "${(s.bass * 100).roundToInt()}%",
                onChange = { v ->
                    AAYSoundFx.update { it.copy(bass = v, preset = "Custom") }
                },
            )
        }
    }
}

@Composable
private fun EqSection(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun EqChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) { content() }
}

@Composable
private fun EqSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(76.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
        Text(
            valueText, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
            maxLines = 1,
        )
    }
}
