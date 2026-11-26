package com.example.app.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.ble.DeviceState
import com.example.app.data.SessionMetrics
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.StatusGrey
import com.example.app.ui.theme.Teal

/** BLE link plus the firmware-reported device state, as one compact card. */
@Composable
fun DeviceConnectionCard(state: ExerciseUiState, modifier: Modifier = Modifier) {
    val (label, color) = when {
        !state.connected -> "Not connected" to MaterialTheme.colorScheme.error
        !state.supportsExercise -> "Connected · firmware without rep counting" to Orange
        else -> when (state.deviceState) {
            DeviceState.IDLE -> "Connected · idle" to Teal
            DeviceState.CALIBRATING -> "Connected · calibrating" to Orange
            DeviceState.READY -> "Connected · ready" to Teal
            DeviceState.RECORDING -> "Connected · recording" to Teal
            DeviceState.STOPPED -> "Connected · stopped" to Teal
            DeviceState.ERROR -> "Connected · error" to MaterialTheme.colorScheme.error
            else -> "Connected" to Teal
        }
    }
    ReBandCard(modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Wearable: $label", style = MaterialTheme.typography.titleSmall)
                state.deviceName?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val calibrationCues = listOf(
    "Relax" to "Let your arm hang straight down, relaxed. Keep it completely still.",
    "Curl and hold" to "Curl your arm all the way up and hold it there, still.",
    "Squeeze" to "Keep the curl and squeeze your biceps as hard as you can.",
)

/** Three-step calibration progress with the cue for the next step. */
@Composable
fun CalibrationProgressCard(progress: CalibrationProgress, armLabel: String) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Calibration · $armLabel", style = MaterialTheme.typography.titleMedium)
            calibrationCues.forEachIndexed { i, (title, cue) ->
                val step = i + 1
                val done = progress.nextStep > step
                val current = progress.nextStep == step
                val capturing = progress.capturing == step
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(
                            when {
                                done -> Teal
                                current -> Orange
                                else -> StatusGrey
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(if (done) "✓" else "$step", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            title + when {
                                done -> " · done"
                                capturing -> " · capturing…"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (current) {
                            Text(cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** "Set 1 of 2", shown above the repetition counter. */
@Composable
fun SetLabel(currentSet: Int, setsPlanned: Int, modifier: Modifier = Modifier) {
    Text(
        "Set $currentSet of $setsPlanned",
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = Teal,
    )
}

/** Large rest countdown between two sets. */
@Composable
fun RestCountdown(secondsLeft: Int, modifier: Modifier = Modifier) {
    val over = secondsLeft <= 0
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = if (over) "Rest over" else "Rest: $secondsLeft seconds left"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (over) "Rest over" else "Rest", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(SessionMetrics.clock(secondsLeft * 1000L), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Teal, textAlign = TextAlign.Center)
    }
}

/** Large, screen-reader friendly repetition count. */
@Composable
fun RepetitionCounter(count: Int, target: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = "Repetitions: $count"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Repetitions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Teal, textAlign = TextAlign.Center)
        if (target > 0) {
            Text("The wearable finishes the set at $target reps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ExerciseTimer(elapsedMs: Long, modifier: Modifier = Modifier) {
    val text = SessionMetrics.clock(elapsedMs)
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Elapsed time $text" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Elapsed Time", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
    }
}
