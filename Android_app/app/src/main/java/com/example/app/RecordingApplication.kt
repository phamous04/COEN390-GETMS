package com.example.app

import android.app.Application
import android.content.Context
import android.util.Log
import android.system.Os
import android.system.OsConstants
import com.example.app.data.RecordingManager
import com.example.app.data.RecordingRepository
import com.example.app.data.RecordingUploadWorker
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.RecordingFileStore
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import com.example.app.data.remote.SupabaseRecordingUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    }
}
