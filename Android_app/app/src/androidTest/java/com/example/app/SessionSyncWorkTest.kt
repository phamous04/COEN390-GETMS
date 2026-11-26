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
import com.example.app.data.SessionSyncWorker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionSyncWorkTest {
    @Test fun sessionSyncIsOneJobThatWaitsForConnectivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ran = CountDownLatch(1)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != SessionSyncWorker::class.java.name) return null
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
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
            // Every saved session and every failed pass asks for the job; only one may be queued.
            SessionSyncWorker.enqueue(context)
            SessionSyncWorker.enqueue(context)
            val requests = manager.getWorkInfosForUniqueWork(SessionSyncWorker.UNIQUE_NAME).get(10, TimeUnit.SECONDS)
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
