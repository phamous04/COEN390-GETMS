package com.example.app.data

import android.content.Context
import android.util.Log
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionDao
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionWithReps
import com.example.app.data.remote.ExerciseRemoteRepository
import com.example.app.data.remote.ExerciseSessionRemote
import com.example.app.data.remote.PairingException
import com.example.app.data.remote.PatientActivity
import com.example.app.data.remote.RemoteSessions
import com.example.app.data.remote.SupabaseProvider
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Whether the signed-in patient's server sessions have been copied into Room. */
sealed interface RestoreState {
    data object Loading : RestoreState
    data object Done : RestoreState
    data class Failed(val message: String) : RestoreState
}

/** Upload side of the sync. [lastError] is why the latest pass left sessions on the phone, if it did. */
data class SessionSyncStatus(val running: Boolean = false, val lastError: String? = null)

/**
 * Exercise sessions: Room is the patient's source of truth (works offline, survives restarts),
 * Supabase is the shared copy that assigned clinicians read. Finished sessions are uploaded with
 * [syncPending]; the server ignores ids it already has, so retries never duplicate a session.
 * [restoreFromServer] brings the server copy back after a sign-in or reinstall.
 *
 * [currentUserId] is the account whose JWT the server calls would use right now. It is only read
 * when [configured] is true.
 */
class ExerciseSessionRepository(
    private val dao: ExerciseSessionDao,
    private val remote: ExerciseSessionRemote,
    private val configured: () -> Boolean,
    private val currentUserId: () -> String?,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val syncLock = Mutex()

    /** Restore writes and the sign-out cleanup take turns, so a late restore can't put rows back. */
    private val localCopyLock = Mutex()

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Loading)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _syncStatus = MutableStateFlow(SessionSyncStatus())
    val syncStatus: StateFlow<SessionSyncStatus> = _syncStatus.asStateFlow()

    fun observeHistory(patientId: String): Flow<List<SessionWithReps>> = dao.observeHistory(patientId)

    fun observeSession(sessionId: String, patientId: String): Flow<SessionWithReps?> =
        dao.observeSession(sessionId, patientId)

    suspend fun save(session: ExerciseSessionEntity) = dao.upsertSession(session)

    suspend fun addReps(reps: List<ExerciseRepEntity>) {
        if (reps.isNotEmpty()) dao.insertReps(reps)
    }

    /** Replaces the stored reps of one set (used when the reference curl is re-recorded). */
    suspend fun clearSet(sessionId: String, setType: String) = dao.deleteSet(sessionId, setType)

    /** Closes flows that an earlier app run left open, keeping any recorded data. */
    suspend fun closeStale(patientId: String, currentSessionId: String) {
        dao.closeStaleInProgress(patientId, currentSessionId, System.currentTimeMillis())
        dao.deleteAbandonedDrafts(patientId, currentSessionId)
    }

    /**
     * Uploads every finished, not yet uploaded session of [patientId]. Returns how many are still
     * on the phone only; the reason is kept in [syncStatus].
     */
    suspend fun syncPending(patientId: String): Int = syncLock.withLock {
        if (!configured()) return 0
        _syncStatus.update { it.copy(running = true) }
        try {
            val (waiting, error) = uploadAll(patientId)
            _syncStatus.update { if (replacedBy(patientId)) it.copy(running = false) else SessionSyncStatus(lastError = error) }
            waiting
        } catch (e: CancellationException) {
            _syncStatus.update { it.copy(running = false) }
            throw e
        }
    }

    /** A different account has signed in: its own passes own [restoreState] and [syncStatus] now. */
    private fun replacedBy(patientId: String): Boolean = currentUserId().let { it != null && it != patientId }

    private suspend fun uploadAll(patientId: String): Pair<Int, String?> {
        var waiting = 0
        var error: String? = null
        try {
            for (session in dao.unsynced(patientId)) {
                // The server files an upload under the JWT's account, so one account's session is
                // never sent while another account (or none) is signed in.
                if (currentUserId() != patientId) {
                    waiting++
                    error = NOT_CONNECTED
                    continue
                }
                try {
                    remote.save(session)
                    dao.markSynced(session.session.id, session.session.updatedAtMillis)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Session ${session.session.id} not uploaded yet", e)
                    waiting++
                    error = sessionSyncMessage(e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not read the sessions waiting for upload", e)
            waiting++
            error = sessionSyncMessage(e)
        }
        return waiting to error
    }

    /**
     * Copies the patient's server sessions into Room (after a sign-in, a reinstall, or an earlier
     * attempt that failed). Never replaces a local row; reps the phone is missing are added even
     * when the session row is already here. The outcome is kept in [restoreState].
     */
    suspend fun restoreFromServer(patientId: String): Boolean {
        if (!configured()) {
            _restoreState.value = RestoreState.Done
            return true
        }
        if (replacedBy(patientId)) return false
        _restoreState.value = RestoreState.Loading
        val failure: String? = try {
            // No usable session for this account (e.g. offline with an expired token): nothing to ask.
            if (currentUserId() != patientId) {
                NOT_CONNECTED
            } else {
                when (val result = remote.sessions(patientId)) {
                    is RemoteSessions.Ok -> if (store(patientId, result.sessions)) null else NOT_CONNECTED
                    RemoteSessions.NoAccess -> "The server did not share this account's sessions."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not restore sessions from the server", e)
            sessionSyncMessage(e)
        }
        if (replacedBy(patientId)) return false
        _restoreState.value = if (failure == null) RestoreState.Done else RestoreState.Failed(failure)
        return failure == null
    }

    /** False when the account signed out meanwhile: sign-out has already removed its synced copies. */
    private suspend fun store(patientId: String, sessions: List<SessionWithReps>): Boolean = localCopyLock.withLock {
        if (currentUserId() != patientId) return false
        for (s in sessions) {
            dao.insertSessionIfAbsent(s.session)
            if (s.reps.isNotEmpty()) dao.insertReps(s.reps)
        }
        true
    }

    /** Clinician read-only view; access is decided by the server on every call. */
    suspend fun patientSessions(patientId: String): RemoteSessions = remote.sessions(patientId)

    suspend fun assignedPatientsActivity(): List<PatientActivity> = remote.assignedPatientsActivity()

    /** On sign-out: only rows already on the server are removed; pending uploads are kept. */
    suspend fun clearSyncedLocalCopies() = localCopyLock.withLock { dao.deleteSynced() }

    /** After sign-out, so the next account does not see the previous one's sync outcome. */
    fun resetStatus() {
        _restoreState.value = RestoreState.Loading
        _syncStatus.value = SessionSyncStatus()
    }

    companion object {
        private const val TAG = "ExerciseSessions"
        private val NOT_CONNECTED = PairingException(PairingException.Kind.Network).userMessage

        @Volatile
        private var instance: ExerciseSessionRepository? = null

        fun get(context: Context): ExerciseSessionRepository = instance ?: synchronized(this) {
            instance ?: ExerciseSessionRepository(
                dao = AppDatabase.get(context).exerciseSessionDao(),
                remote = ExerciseRemoteRepository(),
                configured = { SupabaseProvider.configured },
                currentUserId = { SupabaseProvider.auth.currentUserOrNull()?.id },
                log = { message, error -> Log.w(TAG, message, error) },
            ).also { instance = it }
        }
    }
}

const val SESSIONS_NOT_ON_SERVER =
    "Exercise history isn't set up on the server yet. Ask your team to apply every file in supabase/migrations, in order."

/**
 * Why a restore or upload failed, in words for the patient. A server that is missing the session
 * functions gets its own message instead of the pairing one.
 */
fun sessionSyncMessage(e: Throwable): String {
    val error = PairingException.from(e)
    val cause = error.cause
    return when {
        error.kind == PairingException.Kind.ServerNotReady -> SESSIONS_NOT_ON_SERVER
        error.kind != PairingException.Kind.Unknown -> error.userMessage
        // Timeouts and dropped connections the client did not wrap itself.
        cause is IOException -> PairingException(PairingException.Kind.Network).userMessage
        cause is RestException && cause.error.isNotBlank() -> "The server refused the request: ${cause.error.take(120)}"
        else -> error.userMessage
    }
}
