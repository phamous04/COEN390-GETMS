package com.example.app.ui.exercise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.app.ble.DeviceState
import com.example.app.ble.RepEvent
import com.example.app.data.SessionMetrics
import com.example.app.data.SessionMetrics.MetricUnit
import com.example.app.data.local.SessionWithReps
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.history.SessionDetailContent
import com.example.app.ui.pairing.ConfirmDialog
import com.example.app.ui.pairing.LoadingBlock
import com.example.app.ui.pairing.MessageBanner
import com.example.app.ui.pairing.OutlineAction
import com.example.app.ui.pairing.PairingPage
import com.example.app.ui.theme.Teal
import kotlinx.coroutines.flow.Flow

/**
 * Bicep Curl session: setup → healthy-arm calibration → reference curl → switch arm →
 * exercising-arm calibration → set 1 → rest → set 2 → summary. One route; the ViewModel owns the step.
 */
@Composable
fun ExerciseFlowScreen(
    viewModel: ExerciseViewModel,
    savedSession: (String) -> Flow<SessionWithReps?>,
    onConnectWearable: () -> Unit,
    onOpenHistory: () -> Unit,
    onExit: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.onOpened() }
    DisposableEffect(viewModel) {
        viewModel.onScreenVisible(true)
        onDispose { viewModel.onScreenVisible(false) }
    }

    val leave = {
        when {
            state.step == FlowStep.RECORDING || state.step == FlowStep.FINISHING -> Unit // must Stop first (or end while disconnected)
            state.step == FlowStep.SETUP || state.step == FlowStep.SUMMARY -> { viewModel.cancelFlow(); onExit() }
            state.betweenSets -> confirmEnd = true // a set is already recorded: end and save, never discard
            else -> confirmLeave = true
        }
    }
    BackHandler(enabled = state.step != FlowStep.SUMMARY) { leave() }

    val title = when (state.step) {
        FlowStep.SETUP -> "Bicep Curl"
        FlowStep.REFERENCE_CALIBRATION, FlowStep.REFERENCE_RECORD, FlowStep.REFERENCE_REVIEW -> "Healthy Arm Reference"
        FlowStep.SWITCH_ARM -> "Switch Arms"
        FlowStep.EXERCISE_CALIBRATION, FlowStep.READY -> "Prepare Exercising Arm"
        FlowStep.RECORDING, FlowStep.FINISHING -> "Bicep Curl"
        FlowStep.REST_BETWEEN_SETS -> "Rest"
        FlowStep.SUMMARY -> "Session Summary"
    }
    val canGoBack = state.step != FlowStep.RECORDING && state.step != FlowStep.FINISHING

    PairingPage(title, onBack = if (canGoBack) leave else null) {
        if (state.step != FlowStep.SUMMARY) DeviceConnectionCard(state)
        if (!state.connected && state.step != FlowStep.SUMMARY && state.step != FlowStep.RECORDING && state.step != FlowStep.FINISHING) {
            OutlineAction("Connect wearable", Teal, onConnectWearable, Modifier.fillMaxWidth())
        }
        state.error?.let { MessageBanner(it, isError = true, onDismiss = viewModel::clearMessages) }
        state.notice?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessages) }

        when (state.step) {
            FlowStep.SETUP -> SetupStep(state, viewModel)
            FlowStep.REFERENCE_CALIBRATION -> CalibrationStep(
                state, viewModel,
                intro = "First, the wearable learns your healthy arm (${state.referenceArm?.label?.lowercase()}). " +
                    "Put the ReBand on your healthy arm, then follow the three steps.",
                armLabel = "healthy ${state.referenceArm?.label?.lowercase()}",
            )
            FlowStep.REFERENCE_RECORD -> ReferenceRecordStep(state, viewModel)
            FlowStep.REFERENCE_REVIEW -> ReferenceReviewStep(state, viewModel)
            FlowStep.SWITCH_ARM -> SwitchArmStep(state, viewModel)
            FlowStep.EXERCISE_CALIBRATION -> CalibrationStep(
                state, viewModel,
                intro = if (state.currentSet > 1) {
                    "Calibrate the wearable on your exercising arm (${state.exercisingArm?.label?.lowercase()}) again, " +
                        "then continue with set ${state.currentSet}. Your earlier reps are saved."
                } else {
                    "Now calibrate the wearable on your exercising arm (${state.exercisingArm?.label?.lowercase()})."
                },
                armLabel = "exercising ${state.exercisingArm?.label?.lowercase()}",
            )
            FlowStep.READY -> ReadyStep(state, viewModel)
            FlowStep.RECORDING, FlowStep.FINISHING -> RecordingStep(state, viewModel, onConnectWearable)
            FlowStep.REST_BETWEEN_SETS -> RestStep(state, viewModel, onEndSession = { confirmEnd = true })
            FlowStep.SUMMARY -> SummaryStep(state, savedSession, onOpenHistory, onDone = { viewModel.cancelFlow(); onExit() })
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave this session?",
            text = "The reference and calibration done so far will be discarded. Nothing has been recorded on your exercising arm yet.",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = { confirmLeave = false; viewModel.cancelFlow(); onExit() },
            onDismiss = { confirmLeave = false },
        )
    }
    if (state.confirmNextSet) {
        // Stop was pressed before the set's target. The patient has to choose: no dismissing by tapping outside.
        ConfirmDialog(
            title = "Set ${state.currentSet - 1} stopped early",
            text = "You did ${state.repCount} of ${state.targetReps} reps, and they are saved. " +
                "Continue to set ${state.currentSet} after a short rest, or end the session here?",
            confirmLabel = "Continue to set ${state.currentSet}",
            destructive = false,
            onConfirm = viewModel::continueToNextSet,
            onDismiss = viewModel::endSession,
            dismissLabel = "End session",
            onDismissRequest = {},
        )
    } else if (confirmEnd && state.betweenSets) {
        ConfirmDialog(
            title = "End this session?",
            text = "Your reps so far are saved. The session will end without set ${state.currentSet}.",
            confirmLabel = "End session",
            destructive = true,
            onConfirm = { confirmEnd = false; viewModel.endSession() },
            onDismiss = { confirmEnd = false },
            dismissLabel = "Keep going",
        )
    }
}

@Composable
private fun Waiting(state: ExerciseUiState) {
    state.waiting?.let { LoadingBlock(it) }
}

@Composable
private fun Instruction(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SetupStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exercise", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Bicep Curl", style = MaterialTheme.typography.titleLarge)
            Text(
                "You'll do one reference curl with your healthy arm, then $SETS_PLANNED sets with the other arm, " +
                    "with a short rest in between.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text("Which arm are you exercising today?", style = MaterialTheme.typography.titleMedium)
    Arm.entries.forEach { arm ->
        val selected = state.exercisingArm == arm
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { vm.selectExercisingArm(arm) },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Teal else MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(arm.label, style = MaterialTheme.typography.titleMedium)
                    Text("Healthy reference: ${arm.other.label.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    PrimaryButton(
        "Continue",
        onClick = vm::confirmSetup,
        enabled = state.exercisingArm != null && state.connected && state.supportsExercise,
    )
}

@Composable
private fun CalibrationStep(state: ExerciseUiState, vm: ExerciseViewModel, intro: String, armLabel: String) {
    Instruction(intro)
    CalibrationProgressCard(state.calibration, armLabel)
    Waiting(state)
    val step = state.calibration.nextStep
    PrimaryButton(
        when (step) {
            1 -> "I'm relaxed — capture"
            2 -> "I'm holding the curl — capture"
            else -> "I'm squeezing — capture"
        },
        onClick = vm::captureCalibrationStep,
        enabled = state.connected && state.waiting == null && !state.calibration.complete,
        loading = state.waiting != null,
    )
    if (step > 1 && state.waiting == null) {
        OutlineAction("Restart calibration", Teal, vm::restartCalibration, Modifier.fillMaxWidth())
    }
    Text(
        "Each capture takes about 3 seconds. The wearable checks that you stayed still and that the curl and squeeze were strong enough.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReferenceRecordStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    val recording = state.deviceState == DeviceState.RECORDING
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bicep Curl · ${state.referenceArm?.label} (healthy)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Do ONE slow, controlled curl: start with your arm straight, curl all the way up, then lower it fully. Keep your elbow by your side.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (recording) "Recording… do your curl now." else "Calibrated. Ready to record the reference curl.",
                style = MaterialTheme.typography.titleSmall,
                color = Teal,
            )
        }
    }
    Waiting(state)
    if (!recording) {
        PrimaryButton("Record reference curl", onClick = vm::startReferenceCurl, enabled = state.connected && state.waiting == null)
        OutlineAction("Calibrate healthy arm again", Teal, vm::restartCalibration, Modifier.fillMaxWidth())
    } else {
        OutlineAction("Cancel recording", MaterialTheme.colorScheme.error, vm::stopReferenceCurl, Modifier.fillMaxWidth(), enabled = state.waiting == null)
    }
}

@Composable
private fun ReferenceReviewStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    val rep = state.referenceRep ?: return
    val warnings = SessionMetrics.flagNotes(rep.flags)
    MessageBanner("Reference curl captured.", isError = false)
    ReferenceMeasurements(rep, state.referenceCalibration)
    if (warnings.isNotEmpty()) {
        MessageBanner("The wearable flagged this curl: ${warnings.joinToString(", ")}. Consider recording it again.", isError = true)
    }
    Waiting(state)
    PrimaryButton("Save reference and continue", onClick = vm::acceptReference, enabled = state.waiting == null)
    OutlineAction("Record the reference curl again", Teal, vm::startReferenceCurl, Modifier.fillMaxWidth(), enabled = state.connected && state.waiting == null)
}

@Composable
private fun ReferenceMeasurements(rep: RepEvent, cal: CalibrationValues?) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Healthy-arm reference", style = MaterialTheme.typography.titleMedium)
            Metric("Range of motion", SessionMetrics.format(rep.romX10 / 10.0, MetricUnit.DEGREES))
            Metric("Movement duration", SessionMetrics.format(rep.durationMs / 1000.0, MetricUnit.SECONDS))
            Metric("Peak muscle activation", SessionMetrics.format(rep.peakActivationX10 / 10.0, MetricUnit.PERCENT_MVC))
            Metric("Mean muscle activation", SessionMetrics.format(rep.meanActivationX10 / 10.0, MetricUnit.PERCENT_MVC))
            Metric("Forearm twist / upper-arm swing / out of plane", "${rep.twistDeg}° / ${rep.swingDeg}° / ${rep.planeDeg}°")
            Metric("Form check", if (rep.isClean) "Clean" else "Flagged")
            cal?.let {
                Metric("Calibrated range of motion", SessionMetrics.format(it.romRefX10 / 10.0, MetricUnit.DEGREES))
                Metric("Maximum squeeze / resting signal", "${it.mvcMv} mV / ${it.baselineMv} mV")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchArmStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    MessageBanner("Healthy-arm reference saved.", isError = false)
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Move the wearable to your ${state.exercisingArm?.label?.lowercase()}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Take the ReBand off your healthy arm and strap it to your exercising arm the same way round: " +
                    "upper-arm sensor above the elbow, forearm sensor below it, EMG sensor on the biceps. " +
                    "The wearable is calibrated again for this arm in the next step.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    PrimaryButton("The wearable is on my exercising arm", onClick = vm::confirmArmSwitched)
}

@Composable
private fun ReadyStep(state: ExerciseUiState, vm: ExerciseViewModel) {
    MessageBanner("Calibration complete. You're ready to exercise.", isError = false)
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Bicep Curl · ${state.exercisingArm?.label} · Set ${state.currentSet} of ${state.setsPlanned}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Curl at a steady pace with full range. Press Stop when you're done.", style = MaterialTheme.typography.bodyLarge)
            if (state.targetReps > 0) {
                Text("The wearable ends the set by itself after ${state.targetReps} reps.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Waiting(state)
    PrimaryButton("Start", onClick = vm::startRecording, enabled = state.canStartRecording, loading = state.waiting != null, height = 64.dp)
    if (!state.canStartRecording && state.waiting == null) {
        Text(
            when {
                !state.connected -> "Reconnect the wearable to start."
                state.deviceState != DeviceState.READY -> "The wearable isn't ready. Calibrate again."
                else -> "Waiting for the wearable…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlineAction("Calibrate again", Teal, vm::restartCalibration, Modifier.fillMaxWidth(), enabled = state.waiting == null)
}

@Composable
private fun RecordingStep(state: ExerciseUiState, vm: ExerciseViewModel, onConnectWearable: () -> Unit) {
    ReBandCard {
        Column(Modifier.padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Bicep Curl", style = MaterialTheme.typography.headlineSmall)
            SetLabel(state.currentSet, state.setsPlanned)
            RepetitionCounter(state.repCount, state.targetReps)
            ExerciseTimer(state.elapsedMs)
        }
    }
    if (state.step == FlowStep.FINISHING) {
        LoadingBlock(state.waiting ?: "Saving session…")
        return
    }
    if (state.connectionLostWhileRecording) {
        MessageBanner(
            "Connection lost. The wearable keeps recording; reconnect to recover your reps. " +
                "If you end the session now, reps done after the disconnect are not saved.",
            isError = true,
        )
        PrimaryButton("Reconnect wearable", onClick = onConnectWearable)
        OutlineAction("End session now", MaterialTheme.colorScheme.error, vm::endInterruptedSession, Modifier.fillMaxWidth())
    } else {
        Waiting(state)
        PrimaryButton("Stop", onClick = vm::stopRecording, enabled = state.connected && state.waiting == null, height = 64.dp)
    }
}

/** Between two sets: what was just done, the countdown, and what comes next. */
@Composable
private fun RestStep(state: ExerciseUiState, vm: ExerciseViewModel, onEndSession: () -> Unit) {
    val resting = state.restSecondsLeft > 0
    ReBandCard {
        Column(Modifier.padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Set ${state.currentSet - 1} done · ${state.repCount} ${if (state.repCount == 1) "rep" else "reps"}",
                style = MaterialTheme.typography.headlineSmall,
            )
            RestCountdown(state.restSecondsLeft)
            Text(
                "Next: Set ${state.currentSet}" + if (state.targetReps > 0) " · ${state.targetReps} reps" else "",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    Waiting(state)
    PrimaryButton(
        if (resting) "Skip rest" else "Start set ${state.currentSet}",
        onClick = vm::startNextSet,
        enabled = state.connected && state.waiting == null && !state.confirmNextSet,
        loading = state.waiting != null,
        height = 64.dp,
    )
    Text(
        when {
            !state.connected -> "Reconnect the wearable to start set ${state.currentSet}."
            resting -> "Set ${state.currentSet} starts by itself when the rest is over. Keep the wearable on."
            else -> "Start set ${state.currentSet} when you're ready."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlineAction("End session", MaterialTheme.colorScheme.error, onEndSession, Modifier.fillMaxWidth(), enabled = state.waiting == null)
}

@Composable
private fun SummaryStep(
    state: ExerciseUiState,
    savedSession: (String) -> Flow<SessionWithReps?>,
    onOpenHistory: () -> Unit,
    onDone: () -> Unit,
) {
    val id = state.savedSessionId ?: return
    val flow = remember(id) { savedSession(id) }
    val data by flow.collectAsState(initial = null)
    val session = data
    if (session == null) {
        LoadingBlock("Loading saved session…")
    } else {
        MessageBanner("Session saved.", isError = false)
        SessionDetailContent(session)
    }
    Spacer(Modifier.height(4.dp))
    PrimaryButton("Done", onClick = onDone)
    OutlineAction("View history", Teal, onOpenHistory, Modifier.fillMaxWidth())
}
