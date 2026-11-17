package com.example.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE userId = :userId ORDER BY startedAtMillis DESC")
    fun observeForUser(userId: String): Flow<List<RecordingEntity>>

    @Insert
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings ORDER BY startedAtMillis")
    suspend fun getAll(): List<RecordingEntity>

    @Query("SELECT id FROM recordings WHERE userId = :userId AND recordingStatus IN ('COMPLETED', 'INTERRUPTED') AND uploadStatus IN ('PENDING', 'FAILED', 'UPLOADING')")
    suspend fun uploadableIds(userId: String): List<String>

    @Query("UPDATE recordings SET uploadStatus = 'UPLOADING', retryCount = retryCount + 1, updatedAtMillis = :now WHERE id = :id AND uploadStatus IN ('PENDING', 'FAILED') AND recordingStatus IN ('COMPLETED', 'INTERRUPTED')")
    suspend fun claimUpload(id: String, now: Long): Int

    @Query("UPDATE recordings SET uploadStatus = :status, lastUploadError = :error, updatedAtMillis = :now WHERE id = :id AND uploadStatus = 'UPLOADING'")
    suspend fun finishUpload(id: String, status: UploadStatus, error: String?, now: Long)
}
