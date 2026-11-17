package com.example.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.data.local.AppDatabase
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.RecordingStatus
import com.example.app.data.local.UploadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Two branches each shipped a "version 2" database. Every installed shape must reach v3 with its
 * rows intact: v1, offline-recordings v2, and exercise-sessions v2.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMergeMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val names = mutableListOf<String>()
    private fun name() = "merge-test-${UUID.randomUUID()}.db".also { names += it }

    @After fun cleanup() = names.forEach { context.deleteDatabase(it) }

    private fun open(name: String) =
        Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).build()

    @Test fun v1ReachesV3() = runBlocking {
        val name = name()
        helper.createDatabase(name, 1).use { it.execSQL("INSERT INTO recordings VALUES ('old', 'Flexion', 'Left', 1234, 1)") }
        helper.runMigrationsAndValidate(name, 3, true, *AppDatabase.ALL_MIGRATIONS).close()
        val db = open(name)
        try {
            assertEquals(UploadStatus.UPLOADED, db.recordingDao().get("old")!!.uploadStatus)
            assertNull(db.exerciseSessionDao().getSession("none"))
        } finally { db.close() }
    }

    @Test fun offlineRecordingsV2ReachesV3() = runBlocking {
        val name = name()
        helper.createDatabase(name, 2).use {
            it.execSQL(
                "INSERT INTO recordings VALUES ('r1', 'Bicep Curl', 'Right', 1000, 'owner', 'r1', 2000, 1000, " +
                    "'COMPLETED', 'PENDING', NULL, 0, NULL, NULL, 1000, 2000)",
            )
        }
        helper.runMigrationsAndValidate(name, 3, true, *AppDatabase.ALL_MIGRATIONS).close()
        val db = open(name)
        try {
            val r = db.recordingDao().get("r1")!!
            assertEquals("owner", r.userId)
            assertEquals(RecordingStatus.COMPLETED, r.recordingStatus)
            assertEquals(UploadStatus.PENDING, r.uploadStatus)
        } finally { db.close() }
    }

    /** The exercise-sessions v2 had the v1 `recordings` table plus the two exercise tables. */
    @Test fun exerciseSessionsV2ReachesV3() = runBlocking {
        val name = name()
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE `recordings` (`id` TEXT NOT NULL, `exercise` TEXT NOT NULL, `arm` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `synced` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO recordings VALUES ('legacy', 'Flexion', 'Left', 1234, 0)")
            db.execSQL(
                "CREATE TABLE `exercise_sessions` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, `exerciseType` TEXT NOT NULL, " +
                    "`exercisingArm` TEXT NOT NULL, `referenceArm` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                    "`startedAtMillis` INTEGER, `endedAtMillis` INTEGER, `durationMs` INTEGER, `repCount` INTEGER NOT NULL, " +
                    "`cleanRepCount` INTEGER NOT NULL, `targetReps` INTEGER NOT NULL, `referenceCapturedAtMillis` INTEGER, " +
                    "`referenceRomRefX10` INTEGER, `referenceMvcMv` INTEGER, `referenceBaselineMv` INTEGER, `exerciseRomRefX10` INTEGER, " +
                    "`exerciseMvcMv` INTEGER, `exerciseBaselineMv` INTEGER, `firmwareVersion` INTEGER, `deviceName` TEXT, " +
                    "`emgPacketsReceived` INTEGER NOT NULL, `emgPacketsLost` INTEGER NOT NULL, `deviceErrorFlags` INTEGER NOT NULL, " +
                    "`interruptionReason` TEXT, `synced` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX `index_exercise_sessions_patientId_createdAtMillis` ON `exercise_sessions` (`patientId`, `createdAtMillis`)")
            db.execSQL(
                "CREATE TABLE `exercise_reps` (`sessionId` TEXT NOT NULL, `setType` TEXT NOT NULL, `repIndex` INTEGER NOT NULL, " +
                    "`startMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `romX10` INTEGER NOT NULL, `peakActivationX10` INTEGER NOT NULL, " +
                    "`meanActivationX10` INTEGER NOT NULL, `twistDeg` INTEGER NOT NULL, `swingDeg` INTEGER NOT NULL, `planeDeg` INTEGER NOT NULL, " +
                    "`flags` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `setType`, `repIndex`), FOREIGN KEY(`sessionId`) " +
                    "REFERENCES `exercise_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX `index_exercise_reps_sessionId` ON `exercise_reps` (`sessionId`)")
            db.execSQL(
                "INSERT INTO exercise_sessions (id, patientId, exerciseType, exercisingArm, referenceArm, status, createdAtMillis, " +
                    "repCount, cleanRepCount, targetReps, emgPacketsReceived, emgPacketsLost, deviceErrorFlags, synced, updatedAtMillis) " +
                    "VALUES ('s1', 'p1', 'bicep_curl', 'right', 'left', 'completed', 5000, 4, 3, 5, 0, 0, 0, 0, 5000)",
            )
            db.execSQL("INSERT INTO exercise_reps VALUES ('s1', 'exercise', 1, 0, 1500, 900, 400, 200, 3, 2, 1, 1)")
            db.version = 2
        }
        helper.runMigrationsAndValidate(name, 3, true, *AppDatabase.ALL_MIGRATIONS).close()
        val db = open(name)
        try {
            val session: ExerciseSessionEntity = db.exerciseSessionDao().getSession("s1")!!
            assertEquals(4, session.repCount)
            val legacy = db.recordingDao().get("legacy")!!
            assertNull(legacy.userId)
            assertEquals(RecordingStatus.CORRUPTED, legacy.recordingStatus)
        } finally { db.close() }
    }
}
