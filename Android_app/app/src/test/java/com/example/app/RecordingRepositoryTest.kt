package com.example.app

import com.example.app.ble.EmgPacket
import com.example.app.ble.EmgSample
import com.example.app.data.RecordingRepository
import com.example.app.data.RecordingUploader
import com.example.app.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class RecordingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = MemoryDao()
    private val queued = mutableListOf<String>()
    private val files get() = RecordingFileStore(temporary.root)
    private var attempts = 0
    private var failUpload = false
    private var cancelUpload = false
    private val uploadedKeys = mutableSetOf<String>()
    private fun repository() = RecordingRepository(dao, files, RecordingUploader { recording, file ->
        assertTrue(file.exists())
        assertEquals(UploadStatus.UPLOADING, dao.get(recording.id)!!.uploadStatus)
        attempts++
        if (cancelUpload) throw CancellationException("Worker stopped")
        if (failUpload) throw IOException("Network unavailable")
        uploadedKeys += "${recording.userId}/${recording.id}"
    }, queued::add, { _, _ -> }, { 2000 })

    private suspend fun completed(repository: RecordingRepository): String {
        val session = repository.start("owner", "Shoulder flexion", "Left")
        session.writer.append(EmgPacket(1, 10, listOf(EmgSample(300, 10))), 2100, 100)
        repository.finish(session, 2300, 300, null)
        return session.recording.id
    }

    @Test fun metadataAndFileShareIdBeforeCollectionAndSurviveNewRepository() = runBlocking {
        val repository = repository()
        val session = repository.start("owner", "Shoulder flexion", "Left")
        val saved = dao.get(session.recording.id)!!
        assertEquals("owner", saved.userId)
        assertEquals(saved.id, saved.sessionId)
        assertEquals(RecordingStatus.RECORDING, saved.recordingStatus)
        assertEquals(UploadStatus.PENDING, saved.uploadStatus)
        assertTrue(files.linkedFile(saved).exists())
        session.writer.close() // Simulate process termination; no footer or Room finalization.
        repository().recover()
        assertEquals(RecordingStatus.INTERRUPTED, dao.get(saved.id)!!.recordingStatus)
        repository().enqueuePending("owner")
        assertTrue(saved.id in queued)
    }

    @Test fun networkFailurePersistsErrorAndRetriesWithoutDeletingFile() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        failUpload = true
        assertEquals(RecordingRepository.UploadResult.RETRY, repository.upload(id, "owner"))
        val failed = dao.get(id)!!
        assertEquals(UploadStatus.FAILED, failed.uploadStatus)
        assertEquals(1, failed.retryCount)
        assertEquals("Network unavailable", failed.lastUploadError)
        assertTrue(files.linkedFile(failed).exists())
        failUpload = false
        assertEquals(RecordingRepository.UploadResult.SUCCESS, repository.upload(id, "owner"))
        val uploaded = dao.get(id)!!
        assertEquals(UploadStatus.UPLOADED, uploaded.uploadStatus)
        assertEquals(2, uploaded.retryCount)
        assertNull(uploaded.lastUploadError)
        assertFalse(files.fileFor(id).exists())
        repository.upload(id, "owner")
        assertEquals(2, attempts)
        assertEquals(1, uploadedKeys.size)
    }

    @Test fun missingFileFailsPermanentlyWithoutCallingServer() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        assertTrue(files.fileFor(id).delete())
        assertEquals(RecordingRepository.UploadResult.INVALID_FILE, repository.upload(id, "owner"))
        assertEquals(RecordingStatus.CORRUPTED, dao.get(id)!!.recordingStatus)
        assertEquals(UploadStatus.FAILED, dao.get(id)!!.uploadStatus)
        assertTrue(dao.get(id)!!.lastUploadError!!.contains("missing"))
        assertEquals(0, attempts)
    }

    @Test fun differentOrSignedOutAccountCannotUploadOrRemoveRecording() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        repository.upload(id, "someone-else")
        repository.upload(id, null)
        assertEquals(0, attempts)
        assertEquals(UploadStatus.PENDING, dao.get(id)!!.uploadStatus)
        assertTrue(files.fileFor(id).exists())
    }

    @Test fun uploadingAfterProcessDeathIsResetAndRetried() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        dao.claimUpload(id, 2400)
        repository().recover()
        val recovered = dao.get(id)!!
        assertEquals(UploadStatus.FAILED, recovered.uploadStatus)
        assertEquals(1, recovered.retryCount)
        assertTrue(recovered.lastUploadError!!.contains("interrupted"))
        repository.upload(id, "owner")
        assertEquals(UploadStatus.UPLOADED, dao.get(id)!!.uploadStatus)
    }

    @Test fun confirmedUploadWithLeftoverFileIsCleanedOnStartup() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        dao.update(dao.get(id)!!.copy(uploadStatus = UploadStatus.UPLOADED))
        repository().recover()
        assertFalse(files.fileFor(id).exists())
        assertEquals(UploadStatus.UPLOADED, dao.get(id)!!.uploadStatus)
    }

    @Test fun cancellationPersistsRetryableStateAndKeepsFile() = runBlocking {
        val repository = repository()
        val id = completed(repository)
        cancelUpload = true
        try { repository.upload(id, "owner"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(UploadStatus.FAILED, dao.get(id)!!.uploadStatus)
        assertTrue(files.fileFor(id).exists())
    }

    @Test fun corruptFileIsKeptForDiagnosisAndNotRescheduled() = runBlocking {
        val repository = repository()
        val session = repository.start("owner", "Shoulder flexion", "Right")
        session.writer.close()
        files.fileFor(session.recording.id).writeBytes(byteArrayOf(1, 2, 3))
        repository().recover()
        assertEquals(RecordingStatus.CORRUPTED, dao.get(session.recording.id)!!.recordingStatus)
        queued.clear()
        repository.enqueuePending("owner")
        assertTrue(queued.isEmpty())
        assertTrue(files.fileFor(session.recording.id).exists())
    }

    private class MemoryDao : RecordingDao {
        private val records = linkedMapOf<String, RecordingEntity>()
        override fun observeForUser(userId: String): Flow<List<RecordingEntity>> =
            flowOf(records.values.filter { it.userId == userId })
        override suspend fun insert(recording: RecordingEntity) { check(recording.id !in records); records[recording.id] = recording }
        override suspend fun update(recording: RecordingEntity) { check(recording.id in records); records[recording.id] = recording }
        override suspend fun get(id: String) = records[id]
        override suspend fun getAll() = records.values.toList()
        override suspend fun uploadableIds(userId: String) = records.values.filter {
            it.userId == userId && it.recordingStatus in setOf(RecordingStatus.COMPLETED, RecordingStatus.INTERRUPTED) &&
                it.uploadStatus != UploadStatus.UPLOADED
        }.map { it.id }
        override suspend fun claimUpload(id: String, now: Long): Int {
            val record = records[id] ?: return 0
            if (record.uploadStatus !in setOf(UploadStatus.PENDING, UploadStatus.FAILED) ||
                record.recordingStatus !in setOf(RecordingStatus.COMPLETED, RecordingStatus.INTERRUPTED)) return 0
            records[id] = record.copy(uploadStatus = UploadStatus.UPLOADING, retryCount = record.retryCount + 1, updatedAtMillis = now)
            return 1
        }
        override suspend fun finishUpload(id: String, status: UploadStatus, error: String?, now: Long) {
            val record = records[id]!!
            if (record.uploadStatus == UploadStatus.UPLOADING) records[id] = record.copy(uploadStatus = status,
                lastUploadError = error, updatedAtMillis = now)
        }
    }
}
