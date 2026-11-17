package com.example.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RecordingStatus { RECORDING, COMPLETED, INTERRUPTED, CORRUPTED }
enum class UploadStatus { PENDING, UPLOADING, UPLOADED, FAILED }

/** Ownership is immutable. Null owners only occur on legacy v1 rows and are never uploaded. */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val exercise: String,
    val arm: String,
    val startedAtMillis: Long,
    val userId: String? = null,
    val sessionId: String = id,
    val endedAtMillis: Long? = null,
    val elapsedDurationMillis: Long = 0,
    val recordingStatus: RecordingStatus = RecordingStatus.RECORDING,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val localFilePath: String? = null,
    val retryCount: Int = 0,
    val lastUploadError: String? = null,
    val recordingError: String? = null,
    val createdAtMillis: Long = startedAtMillis,
    val updatedAtMillis: Long = startedAtMillis,
)
