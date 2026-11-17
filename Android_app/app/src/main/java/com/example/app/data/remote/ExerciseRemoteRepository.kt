package com.example.app.data.remote

import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionWithReps
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/** Last completed exercise of one assigned patient (clinician dashboard). */
data class PatientActivity(val patientId: String, val lastCompletedAtMillis: Long?, val completedSessions: Int)

sealed interface RemoteSessions {
    data class Ok(val sessions: List<SessionWithReps>) : RemoteSessions
    /** The caller is not the patient and has no active, verified clinician assignment. */
    data object NoAccess : RemoteSessions
}

/**
 * Supabase side of exercise sessions. See supabase/migrations/20261118000000_exercise_sessions.sql.
 * The server takes the patient id from the session JWT; the app never sends it for writes.
 */
class ExerciseRemoteRepository {
    private suspend fun call(function: String, params: JsonObject? = null): JsonElement {
        if (!SupabaseProvider.configured) throw PairingException(PairingException.Kind.ServerNotReady)
        try {
            val result = if (params == null) SupabaseProvider.postgrest.rpc(function)
            else SupabaseProvider.postgrest.rpc(function, params)
            return Json.parseToJsonElement(result.data.ifBlank { "null" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PairingException.from(e)
        }
    }

    /** Uploads one finished session; safe to repeat (the server ignores a known id). */
    suspend fun save(session: SessionWithReps) {
        call(
            "save_exercise_session",
            buildJsonObject {
                put("p_session", ExerciseJson.sessionToJson(session.session))
                put("p_reps", ExerciseJson.repsToJson(session.reps))
            },
        )
    }

    suspend fun sessions(patientId: String): RemoteSessions {
        val json = call("get_exercise_sessions", buildJsonObject { put("p_patient_id", patientId) })
        return ExerciseJson.sessions(json, patientId)
    }

    suspend fun assignedPatientsActivity(): List<PatientActivity> =
        ExerciseJson.activity(call("get_assigned_patients_activity"))
}

/** Pure mapping between Room rows and the RPC JSON, for unit tests. */
object ExerciseJson {
    fun sessionToJson(s: ExerciseSessionEntity): JsonObject = buildJsonObject {
        put("id", s.id)
        put("exercise_type", s.exerciseType)
        put("exercising_arm", s.exercisingArm)
        put("reference_arm", s.referenceArm)
        put("status", s.status)
        put("created_at", iso(s.createdAtMillis))
        put("started_at", s.startedAtMillis?.let(::iso))
        put("ended_at", s.endedAtMillis?.let(::iso))
        put("duration_ms", s.durationMs)
        put("rep_count", s.repCount)
        put("clean_rep_count", s.cleanRepCount)
        put("target_reps", s.targetReps)
        put("reference_captured_at", s.referenceCapturedAtMillis?.let(::iso))
        put("reference_rom_ref_deg_x10", s.referenceRomRefX10)
        put("reference_mvc_mv", s.referenceMvcMv)
        put("reference_baseline_mv", s.referenceBaselineMv)
        put("exercise_rom_ref_deg_x10", s.exerciseRomRefX10)
        put("exercise_mvc_mv", s.exerciseMvcMv)
        put("exercise_baseline_mv", s.exerciseBaselineMv)
        put("firmware_version", s.firmwareVersion)
        put("device_name", s.deviceName)
        put("emg_packets_received", s.emgPacketsReceived)
        put("emg_packets_lost", s.emgPacketsLost)
        put("device_error_flags", s.deviceErrorFlags)
        put("interruption_reason", s.interruptionReason)
    }

    fun repsToJson(reps: List<ExerciseRepEntity>): JsonArray = buildJsonArray {
        reps.forEach { r ->
            add(
                buildJsonObject {
                    put("set_type", r.setType)
                    put("rep_index", r.repIndex)
                    put("start_ms", r.startMs)
                    put("duration_ms", r.durationMs)
                    put("rom_deg_x10", r.romX10)
                    put("peak_activation_pct_mvc_x10", r.peakActivationX10)
                    put("mean_activation_pct_mvc_x10", r.meanActivationX10)
                    put("twist_deg", r.twistDeg)
                    put("swing_deg", r.swingDeg)
                    put("plane_deg", r.planeDeg)
                    put("flags", r.flags)
                },
            )
        }
    }

    fun sessions(json: JsonElement, patientId: String): RemoteSessions {
        val o = json as? JsonObject ?: error("Unexpected response from server")
        if (o.string("result") != "ok") return RemoteSessions.NoAccess
        val list = (o["sessions"] as? JsonArray).orEmpty().map { item ->
            val s = item as JsonObject
            val id = s.string("id") ?: error("session id missing")
            val session = ExerciseSessionEntity(
                id = id,
                patientId = patientId,
                exerciseType = s.string("exercise_type") ?: "bicep_curl",
                exercisingArm = s.string("exercising_arm") ?: "",
                referenceArm = s.string("reference_arm") ?: "",
                status = s.string("status") ?: "interrupted",
                createdAtMillis = millis(s.string("created_at")) ?: 0L,
                startedAtMillis = millis(s.string("started_at")),
                endedAtMillis = millis(s.string("ended_at")),
                durationMs = s.long("duration_ms"),
                repCount = s.int("rep_count") ?: 0,
                cleanRepCount = s.int("clean_rep_count") ?: 0,
                targetReps = s.int("target_reps") ?: 0,
                referenceCapturedAtMillis = millis(s.string("reference_captured_at")),
                referenceRomRefX10 = s.int("reference_rom_ref_deg_x10"),
                referenceMvcMv = s.int("reference_mvc_mv"),
                referenceBaselineMv = s.int("reference_baseline_mv"),
                exerciseRomRefX10 = s.int("exercise_rom_ref_deg_x10"),
                exerciseMvcMv = s.int("exercise_mvc_mv"),
                exerciseBaselineMv = s.int("exercise_baseline_mv"),
                firmwareVersion = s.int("firmware_version"),
                deviceName = s.string("device_name"),
                emgPacketsReceived = s.long("emg_packets_received") ?: 0,
                emgPacketsLost = s.long("emg_packets_lost") ?: 0,
                deviceErrorFlags = s.int("device_error_flags") ?: 0,
                interruptionReason = s.string("interruption_reason"),
                synced = true,
                updatedAtMillis = millis(s.string("created_at")) ?: 0L,
            )
            val reps = (s["reps"] as? JsonArray).orEmpty().map { rItem ->
                val r = rItem as JsonObject
                ExerciseRepEntity(
                    sessionId = id,
                    setType = r.string("set_type") ?: "exercise",
                    repIndex = r.int("rep_index") ?: 0,
                    startMs = r.long("start_ms") ?: 0,
                    durationMs = r.int("duration_ms") ?: 0,
                    romX10 = r.int("rom_deg_x10") ?: 0,
                    peakActivationX10 = r.int("peak_activation_pct_mvc_x10") ?: 0,
                    meanActivationX10 = r.int("mean_activation_pct_mvc_x10") ?: 0,
                    twistDeg = r.int("twist_deg") ?: 0,
                    swingDeg = r.int("swing_deg") ?: 0,
                    planeDeg = r.int("plane_deg") ?: 0,
                    flags = r.int("flags") ?: 0,
                )
            }
            SessionWithReps(session, reps)
        }
        return RemoteSessions.Ok(list)
    }

    fun activity(json: JsonElement): List<PatientActivity> = (json as? JsonArray).orEmpty().map { item ->
        val o = item as JsonObject
        PatientActivity(
            patientId = o.string("patient_id").orEmpty(),
            lastCompletedAtMillis = millis(o.string("last_completed_at")),
            completedSessions = o.int("completed_sessions") ?: 0,
        )
    }

    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()

    fun millis(iso: String?): Long? = iso?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            .recoverCatching { _ -> Instant.parse(it).toEpochMilli() }
            .getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }

    private fun JsonObject.long(key: String): Long? = string(key)?.toDoubleOrNull()?.toLong()
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
