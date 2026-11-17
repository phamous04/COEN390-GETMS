package com.example.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.app.RecordingApplication
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RecordingUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val services = (applicationContext as RecordingApplication).recordings
            services.ready.await()
            if (!SupabaseProvider.configured) return@withContext Result.retry()
            SupabaseProvider.auth.awaitInitialization()
            val userId = AccountRepository().userId
            val id = inputData.getString(RECORDING_ID)
            if (id == null) {
                if (userId != null) services.repository.enqueuePending(userId)
                return@withContext Result.success()
            }
            when (services.repository.upload(id, userId)) {
                RecordingRepository.UploadResult.SUCCESS -> Result.success()
                RecordingRepository.UploadResult.RETRY -> Result.retry()
                RecordingRepository.UploadResult.INVALID_FILE -> Result.failure()
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            android.util.Log.e("RecordingUpload", "Background upload could not run", e)
            Result.retry()
        }
    }

    companion object {
        const val RECORDING_ID = "recording_id"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context, id: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("recording-upload-$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordingUploadWorker>()
                    .setInputData(workDataOf(RECORDING_ID to id))
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }

        fun ensureReconciliation(context: Context) {
            // Closes the Room-commit / WorkManager-enqueue crash window, also after a device reboot.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("recording-reconciliation",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecordingUploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints).build())
        }
    }
}
