package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.WeeklyActivity
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingEntity
import com.example.app.data.local.SessionWithReps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ExerciseSessionRepository.get(app)
    private val recordingDao = AppDatabase.get(app).recordingDao()
    private val patientId = MutableStateFlow<String?>(null)

    val history: StateFlow<List<SessionWithReps>?> = patientId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeHistory(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        if (this.patientId.value == patientId) return
        this.patientId.value = patientId
        viewModelScope.launch {
            repo.restoreFromServer(patientId)
            repo.syncPending(patientId)
        }
    }

    fun session(sessionId: String): Flow<SessionWithReps?> =
        patientId.value?.let { repo.observeSession(sessionId, it) } ?: flowOf(null)
}
