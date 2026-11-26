package com.example.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history
 *  v1  `recordings` (id, exercise, arm, startedAtMillis, synced)
 *  v2  two parallel branches both shipped a "version 2":
 *      - offline recordings: `recordings` rebuilt with owner, file and upload state ([MIGRATION_1_2])
 *      - exercise sessions: v1 `recordings` unchanged + `exercise_sessions` / `exercise_reps`
 *  v3  both: offline-recording `recordings` + exercise session tables.
 *  v4  exercise sets: `exercise_reps.setIndex` joins the primary key, `exercise_sessions` gets
 *      `setsPlanned` / `setsCompleted` ([MIGRATION_3_4]).
 * [MIGRATION_2_3] checks which v2 it is looking at, so every installed copy reaches the same v3
 * without losing rows.
 */
@Database(
    entities = [RecordingEntity::class, ExerciseSessionEntity::class, ExerciseRepEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun exerciseSessionDao(): ExerciseSessionDao

    companion object {
        /** Rebuilds a v1-shaped `recordings` table into the offline-recording shape. */
        private fun rebuildLegacyRecordings(db: SupportSQLiteDatabase) {
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

        /** The v3 shape of the exercise tables; [MIGRATION_3_4] takes them to v4. */
        private fun createExerciseTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_sessions` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, " +
                    "`exerciseType` TEXT NOT NULL, `exercisingArm` TEXT NOT NULL, `referenceArm` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `startedAtMillis` INTEGER, " +
                    "`endedAtMillis` INTEGER, `durationMs` INTEGER, `repCount` INTEGER NOT NULL, " +
                    "`cleanRepCount` INTEGER NOT NULL, `targetReps` INTEGER NOT NULL, `referenceCapturedAtMillis` INTEGER, " +
                    "`referenceRomRefX10` INTEGER, `referenceMvcMv` INTEGER, `referenceBaselineMv` INTEGER, " +
                    "`exerciseRomRefX10` INTEGER, `exerciseMvcMv` INTEGER, `exerciseBaselineMv` INTEGER, " +
                    "`firmwareVersion` INTEGER, `deviceName` TEXT, `emgPacketsReceived` INTEGER NOT NULL, " +
                    "`emgPacketsLost` INTEGER NOT NULL, `deviceErrorFlags` INTEGER NOT NULL, `interruptionReason` TEXT, " +
                    "`synced` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_exercise_sessions_patientId_createdAtMillis` " +
                    "ON `exercise_sessions` (`patientId`, `createdAtMillis`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_reps` (`sessionId` TEXT NOT NULL, `setType` TEXT NOT NULL, " +
                    "`repIndex` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                    "`romX10` INTEGER NOT NULL, `peakActivationX10` INTEGER NOT NULL, `meanActivationX10` INTEGER NOT NULL, " +
                    "`twistDeg` INTEGER NOT NULL, `swingDeg` INTEGER NOT NULL, `planeDeg` INTEGER NOT NULL, " +
                    "`flags` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `setType`, `repIndex`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `exercise_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_reps_sessionId` ON `exercise_reps` (`sessionId`)")
        }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = rebuildLegacyRecordings(db)
        }

        /** Accepts either v2 (see class doc); idempotent for the parts that already exist. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "recordings", "userId")) rebuildLegacyRecordings(db)
                createExerciseTables(db)
            }
        }

        /**
         * Exercise sets. The firmware restarts rep_index at every START, so `setIndex` becomes part
         * of the `exercise_reps` primary key. SQLite cannot change a primary key in place: the table
         * is rebuilt and every stored rep is copied as set 1. Sessions saved before sets existed
         * were a single set, completed when the session was.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `exercise_sessions` ADD COLUMN `setsPlanned` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `exercise_sessions` ADD COLUMN `setsCompleted` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `exercise_sessions` SET `setsCompleted` = 1 WHERE `status` = 'completed'")
                db.execSQL(
                    "CREATE TABLE `exercise_reps_v4` (`sessionId` TEXT NOT NULL, `setType` TEXT NOT NULL, " +
                        "`setIndex` INTEGER NOT NULL DEFAULT 1, `repIndex` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `romX10` INTEGER NOT NULL, `peakActivationX10` INTEGER NOT NULL, " +
                        "`meanActivationX10` INTEGER NOT NULL, `twistDeg` INTEGER NOT NULL, `swingDeg` INTEGER NOT NULL, " +
                        "`planeDeg` INTEGER NOT NULL, `flags` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`sessionId`, `setType`, `setIndex`, `repIndex`), " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `exercise_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO `exercise_reps_v4` (`sessionId`, `setType`, `setIndex`, `repIndex`, `startMs`, `durationMs`, " +
                        "`romX10`, `peakActivationX10`, `meanActivationX10`, `twistDeg`, `swingDeg`, `planeDeg`, `flags`) " +
                        "SELECT `sessionId`, `setType`, 1, `repIndex`, `startMs`, `durationMs`, `romX10`, `peakActivationX10`, " +
                        "`meanActivationX10`, `twistDeg`, `swingDeg`, `planeDeg`, `flags` FROM `exercise_reps`",
                )
                db.execSQL("DROP TABLE `exercise_reps`")
                db.execSQL("ALTER TABLE `exercise_reps_v4` RENAME TO `exercise_reps`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_reps_sessionId` ON `exercise_reps` (`sessionId`)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "getms.db",
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
    }
}
