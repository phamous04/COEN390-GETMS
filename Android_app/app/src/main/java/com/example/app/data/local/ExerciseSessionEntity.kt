package com.example.app.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation

/** Values of [ExerciseSessionEntity.status]. Only [COMPLETED] counts as an exercise day. */
object SessionStatus {
    /** Flow still running (reference captured, exercising arm not finished). Hidden from history. */
    const val IN_PROGRESS = "in_progress"
    /** The device reached STOPPED (Stop pressed or target reps) with at least one counted rep. */
    const val COMPLETED = "completed"
    /** Stopped normally but no exercising-arm rep was counted. */
    const val INCOMPLETE = "incomplete"
    /** Ended by a lost connection, a device error or the app closing; reps so far are kept. */
    const val INTERRUPTED = "interrupted"
}

object ExerciseType {
    const val BICEP_CURL = "bicep_curl"
    fun label(type: String) = if (type == BICEP_CURL) "Bicep Curl" else type
}

object SetType {
    /** The single healthy-arm reference curl. */
    const val REFERENCE = "reference"
    /** Reps of the exercising arm. */
    const val EXERCISE = "exercise"
}

/**
 * One Bicep Curl session: the healthy-arm reference (calibration + one rep) and the exercising-arm
 * recording. Every number comes from the firmware Status / Rep Event characteristics, in the
 * firmware's own units: `X10` fields are tenths (degrees x10, % MVC x10), `Mv` fields millivolts.
 * Times are phone wall-clock epoch milliseconds.
 */
@Entity(tableName = "exercise_sessions", indices = [Index("patientId", "createdAtMillis")])
data class ExerciseSessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val patientId: String,
    val exerciseType: String = ExerciseType.BICEP_CURL,
    /** "left" or "right". */
    val exercisingArm: String,
    val referenceArm: String,
    val status: String = SessionStatus.IN_PROGRESS,
    val createdAtMillis: Long,
    /** When the device confirmed RECORDING for the exercising arm. */
    val startedAtMillis: Long? = null,
    /** When the device reported STOPPED (or the session was ended as interrupted). */
    val endedAtMillis: Long? = null,
    val durationMs: Long? = null,
    /** Firmware rep_count / clean_count for the exercising arm. */
    val repCount: Int = 0,
    val cleanRepCount: Int = 0,
    /** Firmware target_reps (0 = unlimited). */
    val targetReps: Int = 0,

    // Healthy-arm reference: calibration values from the Status characteristic (state READY)
    val referenceCapturedAtMillis: Long? = null,
    val referenceRomRefX10: Int? = null,
    val referenceMvcMv: Int? = null,
    val referenceBaselineMv: Int? = null,

    // Exercising-arm calibration
    val exerciseRomRefX10: Int? = null,
    val exerciseMvcMv: Int? = null,
    val exerciseBaselineMv: Int? = null,

    // Recording quality
    val firmwareVersion: Int? = null,
    val deviceName: String? = null,
    /** EMG Data packets received / missing (sequence gaps) during the exercising-arm recording. */
    val emgPacketsReceived: Long = 0,
    val emgPacketsLost: Long = 0,
    /** GETMS_ERR_* flags seen during the session (e.g. ADC uncalibrated). */
    val deviceErrorFlags: Int = 0,
    /** Why an interrupted session ended (plain text), null otherwise. */
    val interruptionReason: String? = null,

    val synced: Boolean = false,
    val updatedAtMillis: Long,
)

/** One Rep Event as sent by the firmware, per set. Primary key de-duplicates GET_REPS replays. */
@Entity(
    tableName = "exercise_reps",
    primaryKeys = ["sessionId", "setType", "repIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ExerciseRepEntity(
    val sessionId: String,
    val setType: String,
    val repIndex: Int,
    /** ms since the firmware START of that set. */
    val startMs: Long,
    val durationMs: Int,
    val romX10: Int,
    val peakActivationX10: Int,
    val meanActivationX10: Int,
    val twistDeg: Int,
    val swingDeg: Int,
    val planeDeg: Int,
    /** GETMS_REP_F_* without the REPLAYED bit. */
    val flags: Int,
)

data class SessionWithReps(
    @Embedded val session: ExerciseSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val reps: List<ExerciseRepEntity>,
) {
    val referenceRep: ExerciseRepEntity? get() = reps.filter { it.setType == SetType.REFERENCE }.minByOrNull { it.repIndex }
    val exerciseReps: List<ExerciseRepEntity> get() = reps.filter { it.setType == SetType.EXERCISE }.sortedBy { it.repIndex }
}
