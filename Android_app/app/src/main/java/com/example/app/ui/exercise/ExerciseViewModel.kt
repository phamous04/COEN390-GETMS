package com.example.app.ui.exercise

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.RecordingApplication
import com.example.app.ble.BleManager
import com.example.app.ble.DeviceErrors
import com.example.app.ble.DeviceState
import com.example.app.ble.DeviceStatus
import com.example.app.ble.GattConstants
import com.example.app.ble.RepEvent
import com.example.app.ble.RepFlags
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionStatus
import com.example.app.data.local.SetType
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class Arm(val db: String, val label: String) {
    LEFT("left", "Left arm"), RIGHT("right", "Right arm");

    val other: Arm get() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(db: String) = entries.firstOrNull { it.db == db }
    }
}

enum class FlowStep {
    /** Pick the exercising arm (the healthy arm is the other one). */
    SETUP,
    /** How to wear the ReBand, shown for the healthy arm before its calibration. */
    PLACEMENT,
    /** Calibrate the wearable on the healthy arm. */
    REFERENCE_CALIBRATION,
    /** What one curl looks like, shown once the healthy arm is calibrated. */
    TUTORIAL,
    /** Record one controlled reference curl. */
    REFERENCE_RECORD,
    /** Show the captured reference rep: accept or redo. */
    REFERENCE_REVIEW,
    /** Move the wearable to the exercising arm (same placement guide, for that arm). */
    SWITCH_ARM,
    /** Calibrate again on the exercising arm. */
    EXERCISE_CALIBRATION,
    /** Calibrated; Start is available. */
    READY,
    RECORDING,
    /** Saving the session (final rep recovery). */
    FINISHING,
    SUMMARY,
}

/** Calibration progress of the arm being calibrated. [nextStep] is 1..3, or 4 when complete. */
data class CalibrationProgress(val nextStep: Int = 1, val capturing: Int = 0) {
    val complete get() = nextStep > GattConstants.CAL_STEP_MVC
}

data class ExerciseUiState(
    val step: FlowStep = FlowStep.SETUP,
    val exercisingArm: Arm? = null,
    val connected: Boolean = false,
    val supportsExercise: Boolean = false,
    val deviceName: String? = null,
    val deviceStatus: DeviceStatus? = null,
    val calibration: CalibrationProgress = CalibrationProgress(),
    /** A command was sent and the app is waiting for the device's answer. */
    val waiting: String? = null,
    val referenceRep: RepEvent? = null,
    val referenceCalibration: CalibrationValues? = null,
    val exerciseCalibration: CalibrationValues? = null,
    val repCount: Int = 0,
    val cleanCount: Int = 0,
    val targetReps: Int = 0,
    val elapsedMs: Long = 0,
    /** BLE dropped during recording; the device keeps recording. */
    val connectionLostWhileRecording: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val sessionId: String = "",
    val savedSessionId: String? = null,
) {
    val referenceArm: Arm? get() = exercisingArm?.other
    val deviceState: DeviceState? get() = if (connected) deviceStatus?.deviceState else null

    /** Every precondition of the spec: connection, saved reference, calibrated exercising arm, device READY. */
    val canStartRecording: Boolean
        get() = step == FlowStep.READY && connected && supportsExercise && waiting == null &&
            referenceRep != null && exerciseCalibration != null && deviceState == DeviceState.READY
}

data class CalibrationValues(val romRefX10: Int, val mvcMv: Int, val baselineMv: Int)

/**
 * Bicep Curl workflow over the real firmware protocol (firmware/Reband/BLE_PROTOCOL.md):
 * CALIBRATE 1-3 on the healthy arm -> START, first Rep Event, STOP (reference) -> CALIBRATE 1-3 on
 * the exercising arm -> START ... STOP (or the firmware's own target-reps stop).
 * The placement guide and the curl tutorial in between only change [FlowStep]; they send nothing.
 *
 * Only one command is in flight at a time ([pending]); each step waits for the Status notification
 * that confirms it, so no conflicting or repeated commands are sent. Reps are written to Room as
 * they arrive, so an interruption never loses recorded reps.
 */
class ExerciseViewModel(app: Application) : AndroidViewModel(app) {
    private val ble = BleManager.get(app)
    private val repo = ExerciseSessionRepository.get(app)
    /** Offline raw-sensor capture (teammate's RecordingManager); this flow owns the device commands. */
    private val sensorRecorder = (app as RecordingApplication).recordings.manager

    private val _state = MutableStateFlow(ExerciseUiState())
    val state: StateFlow<ExerciseUiState> = _state.asStateFlow()

    private var patientId: String? = null
    private var session: ExerciseSessionEntity? = null

    private sealed interface Pending {
        val sentAt: Long
        data class Calibrate(val step: Int, var seenCapture: Boolean = false, override val sentAt: Long) : Pending
        /** STOP sent because the device was still RECORDING (e.g. its standalone test) before CALIBRATE 1. */
        data class StopThenCalibrate(override val sentAt: Long) : Pending
        data class Start(override val sentAt: Long) : Pending
        data class Stop(override val sentAt: Long) : Pending
    }

    private var pending: Pending? = null
    private var timeoutJob: Job? = null
    private var tickerJob: Job? = null
    private var startElapsed: Long = 0
    private var lastExerciseRepIndex = 0
    private var exerciseRepsStored = mutableSetOf<Int>()
    private var lastEmgSeq = -1
    private var emgReceived = 0L
    private var emgLost = 0L
    private var errorFlagsSeen = 0

    /** Room writes run one at a time, in the order they were requested (session row before its reps). */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private fun db(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    init {
        viewModelScope.launch { for (w in writes) w() }
        viewModelScope.launch { ble.statusUpdates.collect(::onStatus) }
        viewModelScope.launch { ble.repEvents.collect(::onRep) }
        viewModelScope.launch {
            ble.servicesReady.collect { ready ->
                _state.update { it.copy(connected = ready, deviceStatus = if (ready) ble.deviceStatus.value else it.deviceStatus) }
                if (!ready) onConnectionLost()
            }
        }
        viewModelScope.launch { ble.supportsExercise.collect { s -> _state.update { it.copy(supportsExercise = s) } } }
        viewModelScope.launch { ble.connectedDeviceName.collect { n -> _state.update { it.copy(deviceName = n ?: it.deviceName) } } }
        viewModelScope.launch {
            ble.emgPackets.collect { packet ->
                if (_state.value.step != FlowStep.RECORDING) return@collect
                emgReceived++
                if (lastEmgSeq >= 0) emgLost += ((packet.sequence - lastEmgSeq - 1) and 0xFFFF)
                lastEmgSeq = packet.sequence
            }
        }
    }

    /** Binds the flow to the signed-in patient and closes flows an earlier app run left open. */
    fun bind(patientId: String) {
        if (this.patientId == patientId) return
        this.patientId = patientId
        resetState()
        viewModelScope.launch {
            repo.closeStale(patientId, currentSessionId = "")
            repo.syncPending(patientId)
        }
    }

    /** Called when Start Exercise is opened: a finished flow starts over, a running one resumes. */
    fun onOpened() {
        if (_state.value.step == FlowStep.SUMMARY) resetState()
    }

    // --- Setup ----------------------------------------------------------------------------------

    fun selectExercisingArm(arm: Arm) {
        if (_state.value.step != FlowStep.SETUP) return
        _state.update { it.copy(exercisingArm = arm) }
    }

    fun confirmSetup() {
        val s = _state.value
        val arm = s.exercisingArm ?: return
        val patient = patientId ?: return
        if (s.step != FlowStep.SETUP) return
        val now = System.currentTimeMillis()
        session = ExerciseSessionEntity(
            id = s.sessionId,
            patientId = patient,
            exercisingArm = arm.db,
            referenceArm = arm.other.db,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        _state.update { it.copy(step = FlowStep.PLACEMENT, error = null) }
    }

    // --- Placement guide and curl tutorial (no device commands) ---------------------------------

    /** Back from the healthy-arm placement guide. Nothing is calibrated or stored yet, so the arm can still be changed. */
    fun backToSetup() {
        if (_state.value.step != FlowStep.PLACEMENT) return
        session = null
        _state.update { it.copy(step = FlowStep.SETUP, error = null) }
    }

    /** "Done, calibrate": the wearable is on the healthy arm. */
    fun confirmPlacement() {
        if (_state.value.step != FlowStep.PLACEMENT) return
        _state.update { it.copy(step = FlowStep.REFERENCE_CALIBRATION, calibration = CalibrationProgress(), error = null) }
    }

    /** "I'm ready" on the curl tutorial. A device error raised meanwhile stays on screen. */
    fun confirmTutorial() {
        if (_state.value.step != FlowStep.TUTORIAL) return
        _state.update { it.copy(step = FlowStep.REFERENCE_RECORD) }
    }

    // --- Calibration (both arms) ----------------------------------------------------------------

    /** Sends the CALIBRATE step the screen is showing the cue for. */
    fun captureCalibrationStep() {
        val s = _state.value
        if (s.step != FlowStep.REFERENCE_CALIBRATION && s.step != FlowStep.EXERCISE_CALIBRATION) return
        if (!readyForCommand()) return
        val step = s.calibration.nextStep
        if (step > GattConstants.CAL_STEP_MVC) return
        if (step == GattConstants.CAL_STEP_RELAX && s.deviceState == DeviceState.RECORDING) {
            send(Pending.StopThenCalibrate(now()), "Stopping the wearable's previous recording…", 6_000) { ble.stopRecording() }
            return
        }
        send(Pending.Calibrate(step, sentAt = now()), "Capturing… hold the position", 12_000) { ble.calibrate(step) }
    }

    /** Starts the current arm's calibration again from step 1. */
    fun restartCalibration() {
        val s = _state.value
        if (pending != null) return
        when (s.step) {
            FlowStep.REFERENCE_CALIBRATION, FlowStep.EXERCISE_CALIBRATION ->
                _state.update { it.copy(calibration = CalibrationProgress(), error = null) }
            FlowStep.TUTORIAL, FlowStep.REFERENCE_RECORD, FlowStep.REFERENCE_REVIEW ->
                _state.update { it.copy(step = FlowStep.REFERENCE_CALIBRATION, calibration = CalibrationProgress(), referenceRep = null, error = null) }
            FlowStep.READY ->
                _state.update { it.copy(step = FlowStep.EXERCISE_CALIBRATION, calibration = CalibrationProgress(), exerciseCalibration = null, error = null) }
            else -> Unit
        }
    }

    private fun onCalibrationStepDone(step: Int, status: DeviceStatus) {
        val s = _state.value
        if (step < GattConstants.CAL_STEP_MVC) {
            _state.update { it.copy(calibration = CalibrationProgress(nextStep = step + 1), waiting = null, error = null) }
            return
        }
        // Step 3 done: device is READY and the Status carries ROM_ref, MVC and baseline.
        val values = CalibrationValues(status.romRefX10, status.mvcMv, status.baselineMv)
        val now = System.currentTimeMillis()
        if (s.step == FlowStep.REFERENCE_CALIBRATION) {
            session = session?.copy(
                referenceRomRefX10 = values.romRefX10,
                referenceMvcMv = values.mvcMv,
                referenceBaselineMv = values.baselineMv,
                firmwareVersion = status.fwVersion,
                deviceName = s.deviceName,
                updatedAtMillis = now,
            )
            persist()
            _state.update {
                it.copy(
                    step = FlowStep.TUTORIAL,
                    calibration = CalibrationProgress(nextStep = 4),
                    referenceCalibration = values,
                    waiting = null,
                    error = null,
                )
            }
        } else {
            session = session?.copy(
                exerciseRomRefX10 = values.romRefX10,
                exerciseMvcMv = values.mvcMv,
                exerciseBaselineMv = values.baselineMv,
                targetReps = status.targetReps,
                updatedAtMillis = now,
            )
            persist()
            _state.update {
                it.copy(
                    step = FlowStep.READY,
                    calibration = CalibrationProgress(nextStep = 4),
                    exerciseCalibration = values,
                    targetReps = status.targetReps,
                    waiting = null,
                    error = null,
                )
            }
        }
    }

    // --- Healthy-arm reference curl -------------------------------------------------------------

    /** START on the calibrated healthy arm; the first Rep Event is the reference, then STOP. */
    fun startReferenceCurl() {
        val s = _state.value
        if (s.step != FlowStep.REFERENCE_RECORD && s.step != FlowStep.REFERENCE_REVIEW) return
        if (!readyForCommand()) return
        val st = s.deviceState
        if (st != DeviceState.READY && st != DeviceState.STOPPED) {
            _state.update { it.copy(error = "The wearable is not calibrated for this arm any more. Calibrate again.") }
            return
        }
        val sessionId = s.sessionId
        db { repo.clearSet(sessionId, SetType.REFERENCE) }
        _state.update { it.copy(step = FlowStep.REFERENCE_RECORD, referenceRep = null) }
        send(Pending.Start(now()), "Starting…", 6_000) { ble.startRecording() }
    }

    /** Ends the reference recording without a rep (or cancels it). */
    fun stopReferenceCurl() {
        if (_state.value.step != FlowStep.REFERENCE_RECORD || pending != null) return
        if (_state.value.deviceState != DeviceState.RECORDING) return
        send(Pending.Stop(now()), "Stopping…", 6_000) { ble.stopRecording() }
    }

    fun acceptReference() {
        val s = _state.value
        if (s.step != FlowStep.REFERENCE_REVIEW || s.referenceRep == null) return
        session = session?.copy(referenceCapturedAtMillis = System.currentTimeMillis(), updatedAtMillis = System.currentTimeMillis())
        persist()
        _state.update { it.copy(step = FlowStep.SWITCH_ARM, error = null, notice = null) }
    }

    fun confirmArmSwitched() {
        if (_state.value.step != FlowStep.SWITCH_ARM) return
        _state.update { it.copy(step = FlowStep.EXERCISE_CALIBRATION, calibration = CalibrationProgress(), error = null) }
    }

    // --- Exercising-arm recording ---------------------------------------------------------------

    fun startRecording() {
        if (!_state.value.canStartRecording || !readyForCommand()) return
        send(Pending.Start(now()), "Starting…", 6_000) { ble.startRecording() }
    }

    fun stopRecording() {
        val s = _state.value
        if (s.step != FlowStep.RECORDING || pending != null) return
        if (!s.connected) return
        send(Pending.Stop(now()), "Stopping…", 6_000) { ble.stopRecording() }
    }

    /** While disconnected: save what was recorded so far as an interrupted session. */
    fun endInterruptedSession() {
        val s = _state.value
        if (s.step != FlowStep.RECORDING) return
        finish(SessionStatus.INTERRUPTED, "The wearable disconnected during recording.")
    }

    /** Leaves the flow before recording started. Nothing recorded on the exercising arm is lost. */
    fun cancelFlow() {
        val s = _state.value
        if (s.step == FlowStep.RECORDING || s.step == FlowStep.FINISHING) return
        if (s.deviceState == DeviceState.CALIBRATING && s.connected) ble.stopRecording() // STOP cancels a calibration
        if (s.deviceState == DeviceState.RECORDING && s.connected) ble.stopRecording()
        val patient = patientId
        resetState()
        if (patient != null) viewModelScope.launch { repo.closeStale(patient, currentSessionId = _state.value.sessionId) }
    }

    fun clearMessages() = _state.update { it.copy(error = null, notice = null) }

    // --- Device events --------------------------------------------------------------------------

    private fun onStatus(status: DeviceStatus) {
        errorFlagsSeen = errorFlagsSeen or status.flags
        _state.update { it.copy(deviceStatus = status) }
        val s = _state.value
        val p = pending

        if (status.deviceState == DeviceState.ERROR) {
            onDeviceError(status)
            return
        }

        when (p) {
            is Pending.Calibrate -> {
                if (status.calActive == p.step) {
                    p.seenCapture = true
                    _state.update { it.copy(calibration = it.calibration.copy(capturing = p.step)) }
                } else if (p.seenCapture && status.calActive == 0 && status.calDone == p.step &&
                    (status.deviceState == DeviceState.CALIBRATING || status.deviceState == DeviceState.READY)
                ) {
                    clearPending()
                    onCalibrationStepDone(p.step, status)
                }
            }
            is Pending.StopThenCalibrate -> if (status.deviceState == DeviceState.STOPPED) {
                clearPending()
                captureCalibrationStep()
            }
            is Pending.Start -> if (status.deviceState == DeviceState.RECORDING) {
                clearPending()
                _state.update { it.copy(waiting = null) }
                if (s.step == FlowStep.READY) onExerciseRecordingStarted(status)
            }
            is Pending.Stop -> if (status.deviceState == DeviceState.STOPPED) {
                clearPending()
                _state.update { it.copy(waiting = null) }
                if (s.step == FlowStep.REFERENCE_RECORD) onReferenceStopped()
                else if (s.step == FlowStep.RECORDING) onExerciseStopped(status)
            }
            null -> Unit
        }

        // Live recording updates. Skipped while a START/STOP/CALIBRATE answer is still awaited.
        if (_state.value.step == FlowStep.RECORDING && pending == null) {
            when (status.deviceState) {
                DeviceState.RECORDING -> {
                    _state.update { it.copy(repCount = status.repCount, cleanCount = status.cleanCount) }
                    if (_state.value.connectionLostWhileRecording) {
                        // Reconnected while the device kept recording: fetch the reps we missed.
                        _state.update { it.copy(connectionLostWhileRecording = false, notice = "Reconnected. Recording continued on the wearable.") }
                        ble.requestReps(lastExerciseRepIndex + 1)
                    }
                }
                // Target reps reached on the device, or STOP seen after a reconnect.
                DeviceState.STOPPED -> onExerciseStopped(status)
                DeviceState.IDLE, DeviceState.READY, DeviceState.CALIBRATING ->
                    finish(SessionStatus.INTERRUPTED, "The wearable stopped recording unexpectedly (state ${status.deviceState.name.lowercase()}).")
                else -> Unit
            }
        }
    }

    private fun onDeviceError(status: DeviceStatus) {
        val message = DeviceErrors.message(status.errorCode)
        val s = _state.value
        clearPending()
        when (s.step) {
            FlowStep.REFERENCE_CALIBRATION, FlowStep.EXERCISE_CALIBRATION ->
                // The firmware drops the calibration on a failed step: start this arm over at step 1.
                _state.update { it.copy(calibration = CalibrationProgress(), waiting = null, error = message) }
            FlowStep.TUTORIAL, FlowStep.REFERENCE_RECORD ->
                _state.update { it.copy(waiting = null, error = "$message Calibrate the healthy arm again.") }
            FlowStep.READY ->
                _state.update { it.copy(waiting = null, error = message) }
            FlowStep.RECORDING -> finish(SessionStatus.INTERRUPTED, "Wearable error: $message")
            else -> _state.update { it.copy(waiting = null, error = message) }
        }
    }

    private fun onRep(rep: RepEvent) {
        val s = _state.value
        val sessionId = s.sessionId
        when (s.step) {
            FlowStep.REFERENCE_RECORD -> {
                if (s.referenceRep != null || rep.repIndex != 1) return
                _state.update { it.copy(referenceRep = rep) }
                db { repo.addReps(listOf(rep.toEntity(sessionId, SetType.REFERENCE))) }
                // One reference curl is all we need: stop the device right away.
                if (pending == null) send(Pending.Stop(now()), "Reference curl captured. Stopping…", 6_000) { ble.stopRecording() }
            }
            FlowStep.RECORDING, FlowStep.FINISHING -> {
                if (rep.repIndex in exerciseRepsStored) return
                exerciseRepsStored += rep.repIndex
                lastExerciseRepIndex = maxOf(lastExerciseRepIndex, rep.repIndex)
                db { repo.addReps(listOf(rep.toEntity(sessionId, SetType.EXERCISE))) }
                _state.update { it.copy(repCount = maxOf(it.repCount, rep.repIndex)) }
            }
            else -> Unit
        }
    }

    private fun onReferenceStopped() {
        if (_state.value.referenceRep != null) {
            _state.update { it.copy(step = FlowStep.REFERENCE_REVIEW, error = null) }
        } else {
            _state.update { it.copy(error = "No curl was detected. Curl fully up and back down, slowly, then try again.") }
        }
    }

    private fun onExerciseRecordingStarted(status: DeviceStatus) {
        val now = System.currentTimeMillis()
        startElapsed = SystemClock.elapsedRealtime()
        lastExerciseRepIndex = 0
        exerciseRepsStored = mutableSetOf()
        lastEmgSeq = -1
        emgReceived = 0
        emgLost = 0
        session = session?.copy(startedAtMillis = now, targetReps = status.targetReps, updatedAtMillis = now)
        persist()
        _state.update { it.copy(step = FlowStep.RECORDING, repCount = 0, cleanCount = 0, elapsedMs = 0, error = null) }
        // Also keep the raw EMG stream of this set in a durable local file, linked to the session id
        // and uploaded to Storage in the background. Capture only; it never sends START/STOP.
        _state.value.exercisingArm?.let { arm ->
            sensorRecorder.start(
                exercise = "Bicep Curl",
                arm = if (arm == Arm.LEFT) "Left" else "Right",
                controlDevice = false,
                sessionId = _state.value.sessionId,
            )
        }
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive && _state.value.step == FlowStep.RECORDING) {
                _state.update { it.copy(elapsedMs = SystemClock.elapsedRealtime() - startElapsed) }
                delay(250)
            }
        }
    }

    private fun onExerciseStopped(status: DeviceStatus) {
        val count = status.repCount
        _state.update { it.copy(repCount = count, cleanCount = status.cleanCount) }
        finish(if (count > 0) SessionStatus.COMPLETED else SessionStatus.INCOMPLETE, null, status)
    }

    private fun onConnectionLost() {
        val s = _state.value
        val p = pending
        if (p != null) {
            clearPending()
            when (s.step) {
                FlowStep.REFERENCE_CALIBRATION, FlowStep.EXERCISE_CALIBRATION ->
                    _state.update { it.copy(calibration = CalibrationProgress(), waiting = null, error = "Connection lost during calibration. Reconnect and calibrate again.") }
                else -> _state.update { it.copy(waiting = null, error = "Connection lost. Reconnect the wearable to continue.") }
            }
        }
        if (s.step == FlowStep.RECORDING && !s.connectionLostWhileRecording) {
            _state.update { it.copy(connectionLostWhileRecording = true, notice = null) }
        }
    }

    // --- Finishing ------------------------------------------------------------------------------

    private fun finish(status: String, reason: String?, deviceStatus: DeviceStatus? = null) {
        val s = _state.value
        if (s.step != FlowStep.RECORDING) return
        tickerJob?.cancel()
        clearPending()
        sensorRecorder.stop(reason)
        val endedAt = System.currentTimeMillis()
        val duration = SystemClock.elapsedRealtime() - startElapsed
        _state.update { it.copy(step = FlowStep.FINISHING, elapsedMs = duration, waiting = "Saving session…") }
        viewModelScope.launch {
            // Rep Events can be lost over BLE; the device keeps the last 32. Recover any gap once.
            val expected = deviceStatus?.repCount ?: s.repCount
            val missing = (1..expected).any { it !in exerciseRepsStored }
            if (missing && _state.value.connected) {
                ble.requestReps(1)
                delay(1_500)
            }
            val final = _state.value
            val saved = session?.copy(
                status = status,
                endedAtMillis = endedAt,
                durationMs = duration,
                repCount = maxOf(final.repCount, deviceStatus?.repCount ?: 0),
                cleanRepCount = deviceStatus?.cleanCount ?: final.cleanCount,
                emgPacketsReceived = emgReceived,
                emgPacketsLost = emgLost,
                deviceErrorFlags = errorFlagsSeen,
                interruptionReason = reason,
                synced = false,
                updatedAtMillis = System.currentTimeMillis(),
            ) ?: return@launch
            session = saved
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            db { repo.save(saved); done.complete(Unit) }
            done.await()
            _state.update { it.copy(step = FlowStep.SUMMARY, waiting = null, savedSessionId = saved.id, connectionLostWhileRecording = false) }
            repo.syncPending(saved.patientId)
        }
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun readyForCommand(): Boolean {
        val s = _state.value
        val problem = when {
            pending != null -> return false
            !s.connected -> "Connect your ReBand wearable first."
            !s.supportsExercise -> "This wearable's firmware has no rep counting. Update it to firmware v0.2 or later."
            else -> null
        }
        if (problem != null) _state.update { it.copy(error = problem) }
        return problem == null
    }

    private fun send(p: Pending, waitingText: String, timeoutMs: Long, write: () -> Boolean) {
        if (!write()) {
            _state.update { it.copy(error = "Couldn't reach the wearable. Reconnect and try again.") }
            return
        }
        pending = p
        _state.update { it.copy(waiting = waitingText, error = null) }
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(timeoutMs)
            if (pending === p) {
                pending = null
                val calibrating = p is Pending.Calibrate
                _state.update {
                    it.copy(
                        waiting = null,
                        calibration = if (calibrating) CalibrationProgress() else it.calibration,
                        error = "The wearable didn't respond. Check that it is on and nearby, then try again.",
                    )
                }
                ble.requestState()
            }
        }
    }

    private fun clearPending() {
        pending = null
        timeoutJob?.cancel()
        _state.update { it.copy(calibration = it.calibration.copy(capturing = 0)) }
    }

    private fun persist() {
        val snapshot = session ?: return
        db { repo.save(snapshot) }
    }

    private fun resetState() {
        tickerJob?.cancel()
        clearPending()
        session = null
        errorFlagsSeen = 0
        _state.value = ExerciseUiState(
            sessionId = UUID.randomUUID().toString(),
            connected = ble.servicesReady.value,
            supportsExercise = ble.supportsExercise.value,
            deviceName = ble.connectedDeviceName.value,
            deviceStatus = ble.deviceStatus.value,
        )
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun RepEvent.toEntity(sessionId: String, setType: String) = ExerciseRepEntity(
        sessionId = sessionId,
        setType = setType,
        repIndex = repIndex,
        startMs = startMs,
        durationMs = durationMs,
        romX10 = romX10,
        peakActivationX10 = peakActivationX10,
        meanActivationX10 = meanActivationX10,
        twistDeg = twistDeg,
        swingDeg = swingDeg,
        planeDeg = planeDeg,
        flags = flags and RepFlags.PERSISTED_MASK,
    )
}
