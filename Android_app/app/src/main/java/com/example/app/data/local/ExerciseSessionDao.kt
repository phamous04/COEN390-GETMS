package com.example.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseSessionDao {
    /** Saved sessions of one patient, newest first. In-progress flows are not history yet. */
    @Transaction
    @Query(
        "SELECT * FROM exercise_sessions WHERE patientId = :patientId AND status != 'in_progress' " +
            "ORDER BY COALESCE(startedAtMillis, createdAtMillis) DESC",
    )
    fun observeHistory(patientId: String): Flow<List<SessionWithReps>>

    @Transaction
    @Query("SELECT * FROM exercise_sessions WHERE id = :id AND patientId = :patientId")
    fun observeSession(id: String, patientId: String): Flow<SessionWithReps?>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id")
    suspend fun getSession(id: String): ExerciseSessionEntity?

    /** Insert or update in place (no delete, so the session's reps are kept). */
    @Upsert
    suspend fun upsertSession(session: ExerciseSessionEntity)

    /** Ignore: a rep that is already stored (e.g. replayed by GET_REPS) is never overwritten. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReps(reps: List<ExerciseRepEntity>)

    @Query("DELETE FROM exercise_reps WHERE sessionId = :sessionId AND setType = :setType")
    suspend fun deleteSet(sessionId: String, setType: String)

    /** Restoring from the server must never replace a local row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionIfAbsent(session: ExerciseSessionEntity): Long

    @Transaction
    @Query("SELECT * FROM exercise_sessions WHERE patientId = :patientId AND synced = 0 AND status != 'in_progress'")
    suspend fun unsynced(patientId: String): List<SessionWithReps>

    @Query("UPDATE exercise_sessions SET synced = 1 WHERE id = :id AND updatedAtMillis = :updatedAtMillis")
    suspend fun markSynced(id: String, updatedAtMillis: Long)

    /** Flows left open by an app that was closed are kept as interrupted sessions, never dropped. */
    @Query(
        "UPDATE exercise_sessions SET status = 'interrupted', synced = 0, updatedAtMillis = :now, " +
            "interruptionReason = 'The app was closed before the session finished.', " +
            "endedAtMillis = COALESCE(endedAtMillis, :now) " +
            "WHERE patientId = :patientId AND status = 'in_progress' AND id != :exceptId AND startedAtMillis IS NOT NULL",
    )
    suspend fun closeStaleInProgress(patientId: String, exceptId: String, now: Long)

    /** A flow abandoned before the exercising arm started recording has no exercise data: drop it. */
    @Query(
        "DELETE FROM exercise_sessions WHERE patientId = :patientId AND status = 'in_progress' " +
            "AND id != :exceptId AND startedAtMillis IS NULL",
    )
    suspend fun deleteAbandonedDrafts(patientId: String, exceptId: String)

    /** On sign-out: drop only rows already safely on the server. Unsynced sessions stay for upload. */
    @Query("DELETE FROM exercise_sessions WHERE synced = 1")
    suspend fun deleteSynced()
}
