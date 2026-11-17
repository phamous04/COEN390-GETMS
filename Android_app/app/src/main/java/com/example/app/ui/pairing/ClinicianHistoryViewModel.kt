package com.example.app.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.local.SessionWithReps
import com.example.app.data.remote.PatientActivity
import com.example.app.data.remote.RemoteSessions
import com.example.app.data.remote.pairingMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PatientSessionsState {
    data object Loading : PatientSessionsState
    data class Ok(val patientId: String, val sessions: List<SessionWithReps>) : PatientSessionsState
    /** The server refused: not assigned, assignment revoked, or clinician not verified. */
    data object NoAccess : PatientSessionsState
    data class Failed(val message: String) : PatientSessionsState
}

/**
 * Read-only clinician view of assigned patients' exercise sessions. Data is never cached on the
 * clinician's phone beyond this ViewModel, and every screen open re-asks the server, which checks
 * the live assignment (so a removed patient's records disappear on the next read).
 */
class ClinicianHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ExerciseSessionRepository.get(app)

    private val _activity = MutableStateFlow<Map<String, PatientActivity>>(emptyMap())
    val activity: StateFlow<Map<String, PatientActivity>> = _activity.asStateFlow()

    private val _sessions = MutableStateFlow<PatientSessionsState>(PatientSessionsState.Loading)
    val sessions: StateFlow<PatientSessionsState> = _sessions.asStateFlow()

    private var loadJob: Job? = null

    fun refreshActivity() {
        viewModelScope.launch {
            runCatching { repo.assignedPatientsActivity() }
                .onSuccess { list -> _activity.value = list.associateBy { it.patientId } }
                .onFailure { _activity.value = emptyMap() }
        }
    }

    fun loadPatient(patientId: String) {
        loadJob?.cancel()
        val current = _sessions.value
        if (current !is PatientSessionsState.Ok || current.patientId != patientId) _sessions.value = PatientSessionsState.Loading
        loadJob = viewModelScope.launch {
            _sessions.value = try {
                when (val r = repo.patientSessions(patientId)) {
                    is RemoteSessions.Ok -> PatientSessionsState.Ok(patientId, r.sessions)
                    RemoteSessions.NoAccess -> PatientSessionsState.NoAccess
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                PatientSessionsState.Failed(e.pairingMessage)
            }
        }
    }

    /** Drops a patient's data from memory, e.g. right after the clinician removed them. */
    fun forget(patientId: String) {
        _activity.update { it - patientId }
        val current = _sessions.value
        if (current is PatientSessionsState.Ok && current.patientId == patientId) _sessions.value = PatientSessionsState.NoAccess
    }
}
