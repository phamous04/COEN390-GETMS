package com.example.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.app.data.RecordingUploadWorker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecordingWorkTest {
    @Test fun uniqueUploadWaitsForConnectivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ran = CountDownLatch(1)
        val id = UUID.randomUUID().toString()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != RecordingUploadWorker::class.java.name) return null
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        assertEquals(id, inputData.getString(RecordingUploadWorker.RECORDING_ID))
                        ran.countDown()
                        return Result.success()
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        try {
            RecordingUploadWorker.enqueue(context, id)
            RecordingUploadWorker.enqueue(context, id)
            val requests = manager.getWorkInfosForUniqueWork("recording-upload-$id").get(10, TimeUnit.SECONDS)
            assertEquals(1, requests.size)
            assertEquals(NetworkType.CONNECTED, requests.single().constraints.requiredNetworkType)
            assertEquals(1L, ran.count)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            driver.setAllConstraintsMet(requests.single().id)
            assertTrue(ran.await(10, TimeUnit.SECONDS))
        } finally {
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
