package com.example.app

import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.RestoreState
import com.example.app.data.SESSIONS_NOT_ON_SERVER
import com.example.app.data.SessionSyncCoordinator
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionDao
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionStatus
import com.example.app.data.local.SessionWithReps
import com.example.app.data.local.SetType
import com.example.app.data.remote.ExerciseSessionRemote
import com.example.app.data.remote.PairingException
import com.example.app.data.remote.PatientActivity
import com.example.app.data.remote.RemoteSessions
import com.example.app.data.remote.SignedInUser
import com.example.app.data.remote.resolveSignedInUser
import com.example.app.ui.screens.sessionOnceBound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import io.github.jan.supabase.auth.status.SessionStatus as AuthSessionStatus

/**
 * History must survive sign-out/sign-in, a failed restore, and uploads that fail while offline.
 * Room and the server are replaced by in-memory fakes that follow the same rules (insert-ignore
 * locally, known ids ignored by the server).
 */
class SessionSyncTest {
    private val dao = MemoryDao()
    private val server = MemoryServer()
    private val users = MutableStateFlow<SignedInUser?>(SignedInUser(PATIENT, null, authenticated = true))
    private val repository = ExerciseSessionRepository(dao, server, configured = { true }, currentUserId = { users.value?.id })
    private var scheduled = 0

    private fun coordinator(scope: CoroutineScope) = SessionSyncCoordinator(repository, scope) { scheduled++ }

    private fun session(id: String) = ExerciseSessionEntity(
        id = id,
        patientId = PATIENT,
        exercisingArm = "right",
        referenceArm = "left",
        status = SessionStatus.COMPLETED,
        createdAtMillis = 1_000,
        startedAtMillis = 1_000,
        repCount = 2,
        updatedAtMillis = 2_000,
    )

    private fun rep(sessionId: String, index: Int, rom: Int = 900) =
        ExerciseRepEntity(sessionId, SetType.EXERCISE, index, 0, 1_500, rom, 500, 250, 5, 3, 2, 0)

    @Test
    fun signingInAgainAsTheSameUserRestoresSyncedSessions() = runBlocking {
        users.value = null
        dao.upsertSession(session("s1"))
        dao.insertReps(listOf(rep("s1", 1), rep("s1", 2)))
        val coordinator = coordinator(this)
        val following = launch { coordinator.follow(users) }
        try {
            users.value = SignedInUser(PATIENT, null, authenticated = true)
            withTimeout(TIMEOUT_MS) { dao.sessions.first { it["s1"]?.synced == true } }

            // Sign-out as AuthViewModel does it: upload, end the session, drop the synced copies.
            repository.syncPending(PATIENT)
            users.value = null
            repository.clearSyncedLocalCopies()
            assertTrue(dao.sessions.value.isEmpty())
            withTimeout(TIMEOUT_MS) { repository.restoreState.first { it is RestoreState.Loading } }

            // Same account, same process: nothing is recreated, yet the history must come back.
            users.value = SignedInUser(PATIENT, null, authenticated = true)
            withTimeout(TIMEOUT_MS) { repository.restoreState.first { it is RestoreState.Done } }
            val restored = dao.stored("s1")
            assertNotNull(restored)
            assertTrue(restored!!.session.synced)
            assertEquals(listOf(1, 2), restored.reps.map { it.repIndex }.sorted())
        } finally {
            following.cancel()
        }
    }

    @Test
    fun restoreAddsRepsThePhoneIsMissingWithoutReplacingLocalRows() = runBlocking {
        server.sessions["s1"] = SessionWithReps(
            session("s1").copy(deviceName = "server copy"),
            listOf(rep("s1", 1, rom = 900), rep("s1", 2, rom = 950)),
        )
        // The session row is already on the phone, but not all of its reps.
        dao.upsertSession(session("s1").copy(deviceName = "phone copy", synced = true))
        dao.insertReps(listOf(rep("s1", 1, rom = 700)))

        assertTrue(repository.restoreFromServer(PATIENT))

        val local = dao.stored("s1")!!
        assertEquals("phone copy", local.session.deviceName)
        assertEquals(listOf(700, 950), local.reps.sortedBy { it.repIndex }.map { it.romX10 })
        assertEquals(RestoreState.Done, repository.restoreState.value)
    }

    @Test
    fun failedUploadIsScheduledAndRetriedByTheBackgroundJob() = runBlocking {
        val coordinator = coordinator(this)
        dao.upsertSession(session("s1"))
        server.failure = IOException("Network unavailable")

        assertEquals(1, coordinator.upload(PATIENT))
        assertEquals(1, scheduled)
        assertFalse(dao.stored("s1")!!.session.synced)
        assertNotNull(repository.syncStatus.value.lastError)

        // What SessionSyncWorker runs: false is Result.retry(), true is Result.success().
        assertFalse(coordinator.retry(PATIENT))
        server.failure = null
        assertTrue(coordinator.retry(PATIENT))
        assertTrue(dao.stored("s1")!!.session.synced)
        assertTrue("s1" in server.sessions)
        assertNull(repository.syncStatus.value.lastError)
    }

    @Test
    fun failedRestoreIsReportedAndRepeatedOnRefresh() = runBlocking {
        val coordinator = coordinator(this)
        server.sessions["s1"] = SessionWithReps(session("s1"), listOf(rep("s1", 1)))
        // What a server without the exercise-session migration answers.
        server.failure = PairingException(PairingException.Kind.ServerNotReady)

        assertFalse(coordinator.refresh(PATIENT))
        assertEquals(RestoreState.Failed(SESSIONS_NOT_ON_SERVER), repository.restoreState.value)
        assertEquals(1, scheduled)
        assertNull(dao.stored("s1"))

        server.failure = null
        assertTrue(coordinator.refresh(PATIENT))
        assertEquals(RestoreState.Done, repository.restoreState.value)
        assertNotNull(dao.stored("s1"))
    }

    @Test
    fun sessionRequestedBeforeBindIsEmittedOnceBound() = runBlocking {
        val patientId = MutableStateFlow<String?>(null)
        val flow = sessionOnceBound(patientId) { id ->
            flowOf<SessionWithReps?>(SessionWithReps(session("s1").copy(patientId = id), emptyList()))
        }

        val first = async(Dispatchers.Unconfined) { flow.first() }
        // Nothing yet, rather than a "session not found" answer that never updates.
        assertFalse(first.isCompleted)

        patientId.value = PATIENT
        assertEquals(PATIENT, withTimeout(TIMEOUT_MS) { first.await() }?.session?.patientId)
    }

    @Test
    fun savedAccountStaysSignedInWhileTheSessionCannotBeRefreshed() = runBlocking {
        val saved = SignedInUser(PATIENT, "patient@example.com", authenticated = false)
        // Offline start-up: the client is still "initializing" because the token can't be refreshed.
        assertEquals(saved, resolveSignedInUser(AuthSessionStatus.Initializing) { saved })
        // A real sign-out, or a session the server rejected, never falls back to the saved account.
        assertNull(resolveSignedInUser(AuthSessionStatus.NotAuthenticated()) { saved })
    }

    private class MemoryDao : ExerciseSessionDao {
        val sessions = MutableStateFlow<Map<String, ExerciseSessionEntity>>(emptyMap())
        private val reps = MutableStateFlow<List<ExerciseRepEntity>>(emptyList())

        private fun withReps(session: ExerciseSessionEntity) =
            SessionWithReps(session, reps.value.filter { it.sessionId == session.id })

        fun stored(id: String): SessionWithReps? = sessions.value[id]?.let(::withReps)

        override fun observeHistory(patientId: String): Flow<List<SessionWithReps>> = sessions.map { all ->
            all.values.filter { it.patientId == patientId && it.status != SessionStatus.IN_PROGRESS }.map(::withReps)
        }

        override fun observeSession(id: String, patientId: String): Flow<SessionWithReps?> = sessions.map { all ->
            all[id]?.takeIf { it.patientId == patientId }?.let(::withReps)
        }

        override suspend fun getSession(id: String): ExerciseSessionEntity? = sessions.value[id]

        override suspend fun upsertSession(session: ExerciseSessionEntity) {
            sessions.update { it + (session.id to session) }
        }

        override suspend fun insertReps(reps: List<ExerciseRepEntity>) {
            this.reps.update { stored ->
                stored + reps.filter { new ->
                    stored.none { it.sessionId == new.sessionId && it.setType == new.setType && it.repIndex == new.repIndex }
                }
            }
        }

        override suspend fun deleteSet(sessionId: String, setType: String) {
            reps.update { all -> all.filterNot { it.sessionId == sessionId && it.setType == setType } }
        }

        override suspend fun insertSessionIfAbsent(session: ExerciseSessionEntity): Long {
            if (session.id in sessions.value) return -1
            sessions.update { it + (session.id to session) }
            return 1
        }

        override suspend fun unsynced(patientId: String): List<SessionWithReps> = sessions.value.values
            .filter { it.patientId == patientId && !it.synced && it.status != SessionStatus.IN_PROGRESS }
            .map(::withReps)

        override suspend fun markSynced(id: String, updatedAtMillis: Long) {
            sessions.update { all ->
                val session = all[id]
                if (session != null && session.updatedAtMillis == updatedAtMillis) all + (id to session.copy(synced = true)) else all
            }
        }

        override suspend fun closeStaleInProgress(patientId: String, exceptId: String, now: Long) = Unit

        override suspend fun deleteAbandonedDrafts(patientId: String, exceptId: String) = Unit

        override suspend fun deleteSynced() {
            val gone = sessions.value.values.filter { it.synced }.map { it.id }.toSet()
            sessions.update { all -> all.filterKeys { it !in gone } }
            reps.update { all -> all.filterNot { it.sessionId in gone } }
        }
    }

    private class MemoryServer : ExerciseSessionRemote {
        val sessions = linkedMapOf<String, SessionWithReps>()
        var failure: Exception? = null

        override suspend fun save(session: SessionWithReps) {
            failure?.let { throw it }
            // save_exercise_session(): an id the server already has is ignored.
            if (session.session.id !in sessions) sessions[session.session.id] = session
        }

        override suspend fun sessions(patientId: String): RemoteSessions {
            failure?.let { throw it }
            // get_exercise_sessions(): the patient's own rows; the app reads them back as synced.
            return RemoteSessions.Ok(
                sessions.values.filter { it.session.patientId == patientId }
                    .map { it.copy(session = it.session.copy(synced = true)) },
            )
        }

        override suspend fun assignedPatientsActivity(): List<PatientActivity> = emptyList()
    }

    private companion object {
        const val PATIENT = "p1"
        const val TIMEOUT_MS = 5_000L
    }
}
