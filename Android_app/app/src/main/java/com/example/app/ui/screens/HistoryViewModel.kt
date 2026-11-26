package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.RecordingApplication
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.RestoreState
import com.example.app.data.SessionSyncStatus
import com.example.app.data.WeeklyActivity
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingEntity
import com.example.app.data.local.SessionWithReps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The signed-in patient's saved exercise sessions (Room), newest first, the weekly dashboard
 * derived from them, and the account's raw sensor recordings with their upload state. Room emits
 * after every save, so Home and History refresh as soon as a session or recording ends.
 *
 * Restoring from the server and uploading are not started here: the app-scoped
 * [com.example.app.data.SessionSyncCoordinator] does both on every sign-in, because this
 * ViewModel outlives a sign-out. [refresh] asks it for another pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ExerciseSessionRepository.get(app)
    private val sync = (app as RecordingApplication).recordings.sessionSync
    private val recordingDao = AppDatabase.get(app).recordingDao()
    private val patientId = MutableStateFlow<String?>(null)

    val history: StateFlow<List<SessionWithReps>?> = patientId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeHistory(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether older sessions have been loaded from the server since sign-in. */
    val restoreState: StateFlow<RestoreState> = repo.restoreState

    /** Upload outcome of the latest sync pass, for Settings. */
    val syncStatus: StateFlow<SessionSyncStatus> = repo.syncStatus

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Raw sensor recordings owned by this account only (legacy unowned rows are never shown). */
    val recordings: StateFlow<List<RecordingEntity>> = patientId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else recordingDao.observeForUser(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-evaluated every minute so the week rolls over at local midnight. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(ZoneId.systemDefault()))
            delay(60_000)
        }
    }

    val weekly: StateFlow<WeeklyActivity?> = combine(history, today) { sessions, date ->
        sessions?.let {
            WeeklyActivity.compute(it, date, ZoneId.systemDefault(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun bind(patientId: String) {
        this.patientId.value = patientId
    }

    /** Pull-to-refresh, Retry and "Sync now": load older sessions again, then upload what is waiting. */
    fun refresh() {
        val id = patientId.value ?: return
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                coroutineScope {
                    // The pull-to-refresh indicator only hides once it has seen "refreshing" start.
                    launch { delay(MIN_REFRESH_MS) }
                    sync.refresh(id)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Safe to call before [bind] (e.g. a detail route restored after process death). */
    fun session(sessionId: String): Flow<SessionWithReps?> =
        sessionOnceBound(patientId) { repo.observeSession(sessionId, it) }

    private companion object {
        const val MIN_REFRESH_MS = 600L
    }
}

/**
 * Waits for the patient id instead of answering "no such session" while it is still unknown,
 * then follows that patient's copy of the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun sessionOnceBound(
    patientId: Flow<String?>,
    observe: (patientId: String) -> Flow<SessionWithReps?>,
): Flow<SessionWithReps?> = patientId.filterNotNull().flatMapLatest { observe(it) }
