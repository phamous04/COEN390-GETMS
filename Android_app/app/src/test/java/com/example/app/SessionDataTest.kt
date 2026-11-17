package com.example.app

import com.example.app.ble.RepFlags
import com.example.app.data.SessionMetrics
import com.example.app.data.WeeklyActivity
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseSessionEntity
import com.example.app.data.local.SessionStatus
import com.example.app.data.local.SessionWithReps
import com.example.app.data.local.SetType
import com.example.app.data.remote.ExerciseJson
import com.example.app.data.remote.RemoteSessions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure logic tests. The rep values below are test inputs for the aggregation and mapping code;
 * nothing here is shown in the app.
 */
class SessionDataTest {
    private val zone = ZoneId.of("America/Toronto")

    private fun session(id: String, status: String, start: LocalDateTime) = ExerciseSessionEntity(
        id = id,
        patientId = "p1",
        exercisingArm = "right",
        referenceArm = "left",
        status = status,
        createdAtMillis = start.atZone(zone).toInstant().toEpochMilli(),
        startedAtMillis = start.atZone(zone).toInstant().toEpochMilli(),
        repCount = 2,
        referenceRomRefX10 = 1300,
        exerciseRomRefX10 = 1000,
        updatedAtMillis = 0,
    )

    private fun rep(set: String, i: Int, rom: Int, dur: Int, flags: Int = RepFlags.CLEAN) =
        ExerciseRepEntity("s1", set, i, 0, dur, rom, 500, 250, 5, 3, 2, flags)

    @Test
    fun comparisonUsesMeanOfAllCountedReps() {
        val data = SessionWithReps(
            session("s1", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 16, 10, 0)),
            listOf(
                rep(SetType.REFERENCE, 1, rom = 1200, dur = 2000),
                rep(SetType.EXERCISE, 1, rom = 800, dur = 1500),
                rep(SetType.EXERCISE, 2, rom = 1000, dur = 2500, flags = RepFlags.NOT_CLEAN_SWING),
            ),
        )
        val rows = SessionMetrics.comparison(data).associateBy { it.label }
        val rom = rows.getValue("Range of motion per rep")
        assertEquals(120.0, rom.reference!!, 1e-9)
        assertEquals(90.0, rom.exercising!!, 1e-9)
        assertEquals(-30.0, rom.difference!!, 1e-9)
        assertEquals(75.0, rom.percentOfReference!!, 1e-9)
        assertEquals(100.0, rows.getValue("Best range of motion").exercising!!, 1e-9)
        assertEquals(2.0, rows.getValue("Rep duration").exercising!!, 1e-9)
        assertEquals(130.0, rows.getValue("Calibrated range of motion").reference!!, 1e-9)
        // Activation is not shown as a % of the other arm (each arm has its own MVC).
        assertNull(rows.getValue("Peak muscle activation").percentOfReference)
        assertEquals(1, SessionMetrics.summarize(data.exerciseReps).cleanCount)
    }

    @Test
    fun comparisonWithoutRepsHasNoValues() {
        val data = SessionWithReps(session("s1", SessionStatus.INCOMPLETE, LocalDateTime.of(2026, 11, 16, 10, 0)), emptyList())
        val rom = SessionMetrics.comparison(data).first { it.label == "Range of motion per rep" }
        assertNull(rom.reference)
        assertNull(rom.exercising)
        assertNull(rom.difference)
    }

    @Test
    fun weeklyCountsCompletedDaysOnceInLocalTime() {
        val sessions = listOf(
            session("a", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 16, 9, 0)), // Mon
            session("b", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 16, 23, 30)), // Mon, late evening
            session("c", SessionStatus.INTERRUPTED, LocalDateTime.of(2026, 11, 17, 8, 0)), // Tue, not counted
            session("d", SessionStatus.INCOMPLETE, LocalDateTime.of(2026, 11, 17, 9, 0)), // Tue, not counted
            session("e", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 18, 0, 10)), // Wed just after midnight
            session("f", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 15, 12, 0)), // previous week (Sun)
        ).map { SessionWithReps(it, emptyList()) }
        val week = WeeklyActivity.compute(sessions, LocalDate.of(2026, 11, 18), zone, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 16), week.days.first().date)
        assertEquals(7, week.days.size)
        assertEquals(3, week.completedSessions)
        assertEquals(2, week.activeDays)
        assertEquals(listOf(true, false, true, false, false, false, false), week.days.map { it.exercised })
        assertEquals(listOf(false, false, false, true, true, true, true), week.days.map { it.isFuture })
        assertEquals("e", week.mostRecentCompleted!!.session.id)
    }

    @Test
    fun sessionJsonRoundTrip() {
        val s = session("6f1c2d3e-0000-4000-8000-000000000001", SessionStatus.COMPLETED, LocalDateTime.of(2026, 11, 16, 10, 0))
            .copy(endedAtMillis = 1_763_300_000_000, durationMs = 61_000, cleanRepCount = 1, targetReps = 5)
        val reps = listOf(rep(SetType.REFERENCE, 1, 1200, 2000), rep(SetType.EXERCISE, 1, 900, 1800))
        val sessionJson = ExerciseJson.sessionToJson(s)
        val repsJson = ExerciseJson.repsToJson(reps)
        // Shape returned by get_exercise_sessions(): the session columns plus nested reps.
        val response = buildJsonObject {
            put("result", "ok")
            put("sessions", JsonArray(listOf(JsonObjectPlus(sessionJson, "reps", repsJson))))
        }
        val parsed = ExerciseJson.sessions(Json.parseToJsonElement(response.toString()), "p1") as RemoteSessions.Ok
        val back = parsed.sessions.single()
        assertEquals(s.copy(synced = true, updatedAtMillis = s.createdAtMillis), back.session)
        assertEquals(reps.map { it.copy(sessionId = s.id) }, back.reps)
    }

    @Test
    fun noAccessResponse() {
        val r = ExerciseJson.sessions(Json.parseToJsonElement("""{"result":"no_access"}"""), "p1")
        assertTrue(r is RemoteSessions.NoAccess)
    }

    @Test
    fun parsesPostgresTimestamps() {
        assertEquals(1_763_287_200_123L, ExerciseJson.millis("2025-11-16T10:00:00.123+00:00"))
        assertEquals(1_763_287_200_000L, ExerciseJson.millis("2025-11-16T10:00:00Z"))
        assertNull(ExerciseJson.millis(null))
    }

    @Test
    fun clockFormat() {
        assertEquals("00:00", SessionMetrics.clock(0))
        assertEquals("01:05", SessionMetrics.clock(65_400))
        assertEquals("1:00:01", SessionMetrics.clock(3_601_000))
    }

    @Suppress("FunctionName")
    private fun JsonObjectPlus(o: kotlinx.serialization.json.JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) =
        kotlinx.serialization.json.JsonObject(o + (key to value))
}
