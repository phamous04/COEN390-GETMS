package com.example.app

import android.app.Application
import android.content.Context
import android.util.Log
import android.system.Os
import android.system.OsConstants
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.RecordingManager
import com.example.app.data.RecordingRepository
import com.example.app.data.RecordingUploadWorker
import com.example.app.data.SessionSyncCoordinator
import com.example.app.data.SessionSyncWorker
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingFileStore
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import com.example.app.data.remote.SupabaseRecordingUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class RecordingApplication : Application() {
    lateinit var recordings: RecordingServices
        private set

    override fun onCreate() {
        super.onCreate()
        recordings = RecordingServices(this)
    }
}

class RecordingServices(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = RecordingRepository(AppDatabase.get(context).recordingDao(),
        RecordingFileStore(File(context.filesDir, "recordings"), syncDirectory = { directory ->
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }), SupabaseRecordingUploader(),
        enqueue = { RecordingUploadWorker.enqueue(context, it) },
        log = { message, error -> Log.e("Recording", message, error) })
    // Workers and recording creation both wait for this one recovery pass, avoiding live-writer races.
    val ready = scope.async(Dispatchers.IO) { repository.recover() }
    val manager = RecordingManager(context, scope, ready, repository)
    val sessionSync = SessionSyncCoordinator(ExerciseSessionRepository.get(context), scope + Dispatchers.IO) {
        SessionSyncWorker.enqueue(context)
    }

    init {
        RecordingUploadWorker.ensureReconciliation(context)
        scope.launch(Dispatchers.IO) {
            try {
                ready.await()
                if (SupabaseProvider.configured) {
                    SupabaseProvider.auth.awaitInitialization()
                    AccountRepository().sessionStatus.collect {
                        AccountRepository().userId?.let { repository.enqueuePending(it) }
                    }
                }
            } catch (e: Exception) { Log.e("Recording", "Recording recovery or upload scheduling failed", e) }
        }
        // Exercise sessions are restored and uploaded on every sign-in, not when a screen happens
        // to be created: ViewModels outlive a sign-out, so the same account signing in again
        // would otherwise find its synced sessions gone until the process restarts.
        if (SupabaseProvider.configured) scope.launch(Dispatchers.IO) {
            try {
                val account = AccountRepository()
                withTimeoutOrNull(SESSION_INITIALIZATION_WAIT_MS) { SupabaseProvider.auth.awaitInitialization() }
                sessionSync.follow(account.sessionStatus.map { account.signedInUser(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ExerciseSessions", "Session sync stopped", e)
            }
        }
    }
}

/**
 * How long start-up waits for the saved session before using it as it is. Offline with a token
 * close to expiry, the client keeps retrying the refresh and stays "initializing".
 */
const val SESSION_INITIALIZATION_WAIT_MS = 5_000L
