package com.aayoutube.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun SleepTimerPage() {
    var amount by rememberSaveable { mutableStateOf(SleepTimerPolicy.DEFAULT_MINUTES.toString()) }
    var hours by rememberSaveable { mutableStateOf(false) }
    val remaining by SleepTimer.remaining.collectAsState()
    val duration = SleepTimerPolicy.durationMillis(amount.toLongOrNull() ?: 0L, hours)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
        Text("Automatically quit AAYouTube when the countdown ends, stopping playback and releasing app resources. Works while the app is in the background or on Android Auto as long as the app process is running.")
        OutlinedTextField(
            value = amount,
            onValueChange = { value -> if (value.length <= 5 && value.all(Char::isDigit)) amount = value },
            label = { Text("Duration") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            isError = duration == null,
            supportingText = { Text("Choose 1 minute to 24 hours. Default: 15 minutes.") },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("Minutes", "Hours").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = hours == (index == 1),
                    onClick = { hours = index == 1 },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    label = { Text(label) },
                )
            }
        }
        Button(
            onClick = { SleepTimer.start(amount.toLongOrNull() ?: 0L, hours) },
            enabled = duration != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (remaining == null) "Start timer" else "Restart timer") }
        HorizontalDivider()
        if (remaining != null) {
            Text("Time remaining", style = MaterialTheme.typography.titleMedium)
            Text(SleepTimerPolicy.formatRemaining(remaining!!), style = MaterialTheme.typography.headlineLarge)
            OutlinedButton(onClick = { SleepTimer.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel timer")
            }
        } else {
            Text("No active sleep timer", style = MaterialTheme.typography.bodyLarge)
        }
        Text("When time expires, AAYouTube quits and stops video, music and its background services. Changing the duration and starting again replaces the countdown.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
