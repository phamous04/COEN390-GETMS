package com.example.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RecordingEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE recordings_v2 (
                        id TEXT NOT NULL PRIMARY KEY, exercise TEXT NOT NULL, arm TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL, userId TEXT, sessionId TEXT NOT NULL,
                        endedAtMillis INTEGER, elapsedDurationMillis INTEGER NOT NULL,
                        recordingStatus TEXT NOT NULL, uploadStatus TEXT NOT NULL, localFilePath TEXT,
                        retryCount INTEGER NOT NULL, lastUploadError TEXT, recordingError TEXT,
                        createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                // v1 stored neither ownership nor files. Preserve history without guessing an owner.
                db.execSQL("""
                    INSERT INTO recordings_v2 SELECT id, exercise, arm, startedAtMillis, NULL, id,
                        NULL, 0, CASE WHEN synced = 1 THEN 'COMPLETED' ELSE 'CORRUPTED' END,
                        CASE WHEN synced = 1 THEN 'UPLOADED' ELSE 'FAILED' END, NULL, 0,
                        CASE WHEN synced = 0 THEN 'Legacy recording has no local sensor file or owner' ELSE NULL END,
                        NULL, startedAtMillis, startedAtMillis FROM recordings
                """.trimIndent())
                db.execSQL("DROP TABLE recordings")
                db.execSQL("ALTER TABLE recordings_v2 RENAME TO recordings")
            }
        }
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "getms.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
