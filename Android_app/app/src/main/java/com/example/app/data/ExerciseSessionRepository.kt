package com.example.app.data

import android.content.Context
import android.util.Log
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionWithReps
import com.example.app.data.remote.ExerciseRemoteRepository
import com.example.app.data.remote.PatientActivity
import com.example.app.data.remote.RemoteSessions
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exercise sessions: Room is the patient's source of truth (works offline, survives restarts),
 * Supabase is the shared copy that assigned clinicians read. Finished sessions are uploaded with
 * [syncPending]; the server ignores ids it already has, so retries never duplicate a session.
 */
class ExerciseSessionRepository private constructor(context: Context) {
    private val dao = AppDatabase.get(context).exerciseSessionDao()
    private val remote = ExerciseRemoteRepository()
    private val syncLock = Mutex()

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

    /** Uploads every finished, not yet uploaded session of [patientId]. Returns how many failed. */
    suspend fun syncPending(patientId: String): Int = syncLock.withLock {
        if (!SupabaseProvider.configured) return 0
        var failed = 0
        for (session in dao.unsynced(patientId)) {
            try {
                remote.save(session)
                dao.markSynced(session.session.id, session.session.updatedAtMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Session ${session.session.id} not uploaded yet", e)
                failed++
            }
        }
        failed
    }

    /** Copies the patient's server sessions into Room (e.g. after reinstall). Never replaces local rows. */
    suspend fun restoreFromServer(patientId: String) {
        if (!SupabaseProvider.configured) return
        try {
            val result = remote.sessions(patientId) as? RemoteSessions.Ok ?: return
            for (s in result.sessions) {
                if (dao.insertSessionIfAbsent(s.session) != -1L) dao.insertReps(s.reps)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore sessions from the server", e)
        }
    }

    /** Clinician read-only view; access is decided by the server on every call. */
    suspend fun patientSessions(patientId: String): RemoteSessions = remote.sessions(patientId)

    suspend fun assignedPatientsActivity(): List<PatientActivity> = remote.assignedPatientsActivity()

    /** On sign-out: only rows already on the server are removed; pending uploads are kept. */
    suspend fun clearSyncedLocalCopies() = dao.deleteSynced()

    companion object {
        private const val TAG = "ExerciseSessions"

        @Volatile
        private var instance: ExerciseSessionRepository? = null

        fun get(context: Context): ExerciseSessionRepository = instance ?: synchronized(this) {
            instance ?: ExerciseSessionRepository(context.applicationContext).also { instance = it }
        }
    }
}
