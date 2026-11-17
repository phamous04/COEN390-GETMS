package com.example.app.data

import com.example.app.data.local.RecordingDao
import com.example.app.data.local.RecordingEntity
import com.example.app.data.local.RecordingFileStore
import com.example.app.data.local.RecordingStatus
import com.example.app.data.local.UploadStatus
import com.example.app.data.local.InvalidRecordingFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

fun interface RecordingUploader {
    /** Returns only after the server confirms storage of the immutable recording. */
    suspend fun upload(recording: RecordingEntity, file: File)
}

/** All file operations run on the caller's IO dispatcher. One application instance owns recovery. */
class RecordingRepository(
    val dao: RecordingDao,
    val files: RecordingFileStore,
    private val uploader: RecordingUploader,
    private val enqueue: (String) -> Unit,
    private val log: (String, Throwable) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Session(val recording: RecordingEntity, val writer: RecordingFileStore.Writer)
    enum class UploadResult { SUCCESS, RETRY, INVALID_FILE }

    /** [sessionId] links the file to an exercise session; a standalone recording uses its own id. */
    suspend fun start(userId: String, exercise: String, arm: String, sessionId: String? = null): Session {
        require(userId.isNotBlank() && exercise.isNotBlank() && arm in setOf("Left", "Right"))
        require(sessionId == null || sessionId.isNotBlank())
        val id = UUID.randomUUID().toString()
        val recording = RecordingEntity(id, exercise, arm, now(), userId = userId, sessionId = sessionId ?: id,
            localFilePath = files.fileFor(id).canonicalPath)
        // Persist the ID and intended file before collecting any sensor samples.
        dao.insert(recording)
        try {
            return Session(recording, files.create(recording))
        } catch (e: Exception) {
            markInvalid(recording, e)
            throw e
        }
    }

    suspend fun finish(session: Session, end: Long, elapsed: Long, error: String?) {
        val recording = session.recording
        try {
            session.writer.use {
                it.finish(RecordingFileStore.Footer(end, elapsed,
                    if (error == null) RecordingStatus.COMPLETED else RecordingStatus.INTERRUPTED, error))
            }
            val footer = files.inspect(recording).footer!!
            dao.update(recording.copy(endedAtMillis = footer.end, elapsedDurationMillis = footer.elapsed,
                recordingStatus = footer.status, recordingError = footer.error, updatedAtMillis = now()))
        } catch (e: Exception) {
            log("Could not finalize recording ${recording.id}; recovering durable packets", e)
            // A failed packet/footer write may leave a torn tail. Recover exactly as after process death.
            recoverRecording(recording)
        }
        enqueue(recording.id)
    }

    suspend fun recover() {
        dao.getAll().forEach { recording ->
            when {
                recording.uploadStatus == UploadStatus.UPLOADED -> cleanup(recording)
                recording.recordingStatus == RecordingStatus.RECORDING -> recoverRecording(recording)
                recording.uploadStatus == UploadStatus.UPLOADING -> dao.update(recording.copy(
                    uploadStatus = UploadStatus.FAILED, lastUploadError = "Upload interrupted; will retry",
                    updatedAtMillis = now()))
            }
        }
    }

    suspend fun recoverFailedWrite(session: Session) {
        try { session.writer.close() } catch (e: Exception) { log("Could not close sensor file", e) }
        recoverRecording(session.recording)
        enqueue(session.recording.id)
    }

    private suspend fun recoverRecording(recording: RecordingEntity) {
        try {
            val footer = files.recover(recording)
            dao.update(recording.copy(endedAtMillis = footer.end, elapsedDurationMillis = footer.elapsed,
                recordingStatus = footer.status, recordingError = footer.error,
                uploadStatus = UploadStatus.PENDING, updatedAtMillis = now()))
        } catch (e: Exception) { markInvalid(recording, e) }
    }

    suspend fun enqueuePending(userId: String) = dao.uploadableIds(userId).forEach(enqueue)

    suspend fun upload(id: String, currentUserId: String?): UploadResult {
        val recording = dao.get(id) ?: return UploadResult.SUCCESS
        // An account change never transfers ownership, and never deletes another account's pending files.
        if (currentUserId == null || recording.userId != currentUserId) return UploadResult.SUCCESS
        if (recording.uploadStatus == UploadStatus.UPLOADED) {
            cleanup(recording)
            return UploadResult.SUCCESS
        }
        try {
            if (dao.claimUpload(id, now()) == 0) return UploadResult.SUCCESS
            try { files.inspect(recording) } catch (e: InvalidRecordingFile) {
                markInvalid(dao.get(id)!!, e)
                return UploadResult.INVALID_FILE
            }
            uploader.upload(recording, files.linkedFile(recording))
            // Commit confirmation first. A crash before deletion only leaves a harmless local copy.
            dao.finishUpload(id, UploadStatus.UPLOADED, null, now())
            cleanup(dao.get(id)!!)
            return UploadResult.SUCCESS
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.finishUpload(id, UploadStatus.FAILED, "Upload interrupted; will retry", now())
            }
            throw e
        } catch (e: Exception) {
            dao.finishUpload(id, UploadStatus.FAILED, e.message?.take(1000) ?: "Upload failed", now())
            log("Upload failed for $id", e)
            return UploadResult.RETRY
        }
    }

    private suspend fun markInvalid(recording: RecordingEntity, error: Exception) {
        log("Local recording ${recording.id} needs attention", error)
        dao.update(recording.copy(recordingStatus = RecordingStatus.CORRUPTED,
            uploadStatus = UploadStatus.FAILED, lastUploadError = error.message?.take(1000),
            recordingError = error.message?.take(1000), updatedAtMillis = now()))
    }

    private fun cleanup(recording: RecordingEntity) {
        try { files.deleteUploaded(recording) } catch (e: Exception) {
            log("Uploaded recording ${recording.id} will be cleaned up on next startup", e)
        }
    }
}
