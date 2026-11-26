package com.example.app.data

import com.example.app.data.remote.SignedInUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps Room and the server in step for whoever is signed in, independently of any screen or
 * ViewModel: every sign-in (also as the same account, in the same process) restores the server
 * copy and uploads what is still only on the phone. [schedule] enqueues [SessionSyncWorker] for
 * whatever could not be done right away.
 */
class SessionSyncCoordinator(
    private val repository: ExerciseSessionRepository,
    private val scope: CoroutineScope,
    private val schedule: () -> Unit,
) {
    /** Runs until cancelled. [users] emits the account using the phone, or null when signed out. */
    suspend fun follow(users: Flow<SignedInUser?>) {
        users.distinctUntilChanged().collectLatest { user ->
            if (user == null) {
                repository.resetStatus()
            } else {
                refresh(user.id)
            }
        }
    }

    /**
     * Restores from the server, then uploads. Also behind pull-to-refresh, Retry and "Sync now".
     * The work runs in the app [scope], so leaving the screen never abandons it half-way.
     */
    suspend fun refresh(patientId: String): Boolean = scope.async {
        val restored = repository.restoreFromServer(patientId)
        val waiting = repository.syncPending(patientId)
        val complete = restored && waiting == 0
        if (!complete) schedule()
        complete
    }.await()

    /**
     * Uploads a session that was just saved. The background job is scheduled first, so the session
     * still uploads if the app is closed before or during the attempt.
     */
    suspend fun upload(patientId: String): Int {
        schedule()
        return repository.syncPending(patientId)
    }

    /** One background pass (see [SessionSyncWorker]). False asks WorkManager to try again later. */
    suspend fun retry(patientId: String?): Boolean {
        // Signed out: nothing can be uploaded for anyone, and the next sign-in runs [follow].
        if (patientId == null) return true
        val restored = repository.restoreState.value !is RestoreState.Failed || repository.restoreFromServer(patientId)
        val waiting = repository.syncPending(patientId)
        return restored && waiting == 0
    }
}
