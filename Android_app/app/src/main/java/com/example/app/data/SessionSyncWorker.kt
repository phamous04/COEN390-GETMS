package com.example.app.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.app.RecordingApplication
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Uploads exercise sessions that could not be sent when they were saved (and repeats a failed
 * restore) once the phone is online again, also when the app is not open.
 */
class SessionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!SupabaseProvider.configured) return@withContext Result.success()
            SupabaseProvider.auth.awaitInitialization()
            val sync = (applicationContext as RecordingApplication).recordings.sessionSync
            if (sync.retry(AccountRepository().userId)) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ExerciseSessions", "Background session sync could not run", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "exercise-session-sync"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SessionSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
