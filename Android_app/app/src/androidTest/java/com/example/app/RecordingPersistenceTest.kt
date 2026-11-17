package com.example.app

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.app.data.RecordingRepository
import com.example.app.data.RecordingUploader
import com.example.app.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingPersistenceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val names = mutableListOf<String>()
    private val directories = mutableListOf<File>()
    private fun name() = "recording-test-${UUID.randomUUID()}.db".also { names += it }
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()

    @After fun cleanup() {
        names.forEach(context::deleteDatabase)
        directories.forEach { it.deleteRecursively() }
    }

    @Test fun metadataFilesAndRecoverySurviveDatabaseReopen() = runBlocking {
        val name = name()
        val directory = File(context.filesDir, "test-recordings-${UUID.randomUUID()}").also { directories += it }
        val files = RecordingFileStore(directory)
        val first = open(name)
        val repo = RecordingRepository(first.recordingDao(), files, RecordingUploader { _, _ -> }, {}, { _, _ -> })
        val session = repo.start("owner", "Shoulder flexion", "Left")
        val id = session.recording.id
        session.writer.close()
        first.close()
        val second = open(name)
        try {
            val recovered = RecordingRepository(second.recordingDao(), RecordingFileStore(directory),
                RecordingUploader { _, _ -> }, {}, { _, _ -> })
            recovered.recover()
            val saved = second.recordingDao().get(id)!!
            assertEquals("owner", saved.userId)
            assertEquals("Left", saved.arm)
            assertEquals("Shoulder flexion", saved.exercise)
            assertEquals(id, saved.sessionId)
            assertEquals(RecordingStatus.INTERRUPTED, saved.recordingStatus)
            assertEquals(UploadStatus.PENDING, saved.uploadStatus)
            assertNotNull(saved.endedAtMillis)
            assertTrue(saved.updatedAtMillis >= saved.createdAtMillis)
            assertEquals(id, second.recordingDao().observeForUser("owner").first().single().id)
            assertTrue(second.recordingDao().observeForUser("another-owner").first().isEmpty())
            assertEquals(saved.id, files.linkedFile(saved).nameWithoutExtension)
            assertNotNull(files.inspect(saved).footer)
        } finally { second.close() }
    }

    @Test fun migrationPreservesLegacyRowsWithoutInventingFilesOrOwners() = runBlocking {
        val name = name()
        migration.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO recordings VALUES ('pending', 'Flexion', 'Left', 1234, 0)")
            db.execSQL("INSERT INTO recordings VALUES ('synced', 'Extension', 'Right', 2345, 1)")
        }
        migration.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2).close()
        val db = open(name)
        try {
            val pending = db.recordingDao().get("pending")!!
            assertEquals("Flexion", pending.exercise)
            assertEquals(1234L, pending.createdAtMillis)
            assertEquals(1234L, pending.updatedAtMillis)
            assertNull(pending.userId)
            assertNull(pending.localFilePath)
            assertEquals(RecordingStatus.CORRUPTED, pending.recordingStatus)
            assertEquals(UploadStatus.FAILED, pending.uploadStatus)
            assertNotNull(pending.lastUploadError)
            assertEquals(UploadStatus.UPLOADED, db.recordingDao().get("synced")!!.uploadStatus)
            assertEquals(2, db.recordingDao().getAll().size)
            assertTrue(db.recordingDao().uploadableIds("owner").isEmpty())
        } finally { db.close() }
    }

    @Test fun roomUploadClaimIsExclusiveAndRecoveryPersistsRetryCount() = runBlocking {
        val name = name()
        val db = open(name)
        try {
            val dao = db.recordingDao()
            val id = UUID.randomUUID().toString()
            dao.insert(RecordingEntity(id, "Flexion", "Right", 1000, userId = "owner",
                recordingStatus = RecordingStatus.COMPLETED))
            assertEquals(1, dao.claimUpload(id, 1100))
            assertEquals(0, dao.claimUpload(id, 1200))
            val repo = RecordingRepository(dao, RecordingFileStore(context.cacheDir),
                RecordingUploader { _, _ -> }, {}, { _, _ -> })
            repo.recover()
            val saved = dao.get(id)!!
            assertEquals(1, saved.retryCount)
            assertEquals(UploadStatus.FAILED, saved.uploadStatus)
            assertTrue(saved.lastUploadError!!.contains("interrupted"))
        } finally { db.close() }
    }
}
