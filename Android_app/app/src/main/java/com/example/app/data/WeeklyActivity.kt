package com.example.app.data

import com.example.app.data.local.SessionStatus
import com.example.app.data.local.SessionWithReps
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DayActivity(val date: LocalDate, val completedSessions: Int, val isToday: Boolean, val isFuture: Boolean) {
    val exercised: Boolean get() = completedSessions > 0
}

/**
 * The current calendar week from saved sessions. Rules: a day counts when at least one
 * [SessionStatus.COMPLETED] session started on it (patient's local time zone); several sessions
 * on one day are still one active day; incomplete or interrupted sessions never count.
 */
data class WeeklyActivity(
    val days: List<DayActivity>,
    val completedSessions: Int,
    val activeDays: Int,
    val mostRecentCompleted: SessionWithReps?,
) {
    companion object {
        fun localDate(session: SessionWithReps, zone: ZoneId): LocalDate =
            Instant.ofEpochMilli(session.session.startedAtMillis ?: session.session.createdAtMillis).atZone(zone).toLocalDate()

        fun compute(
            sessions: List<SessionWithReps>,
            today: LocalDate,
            zone: ZoneId,
            firstDayOfWeek: DayOfWeek,
        ): WeeklyActivity {
            val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val completed = sessions.filter { it.session.status == SessionStatus.COMPLETED }
            val perDay = completed.groupingBy { localDate(it, zone) }.eachCount()
            val days = (0L until 7L).map { offset ->
                val date = start.plusDays(offset)
                DayActivity(date, perDay[date] ?: 0, isToday = date == today, isFuture = date.isAfter(today))
            }
            return WeeklyActivity(
                days = days,
                completedSessions = days.sumOf { it.completedSessions },
                activeDays = days.count { it.exercised },
                mostRecentCompleted = completed.maxByOrNull { it.session.startedAtMillis ?: it.session.createdAtMillis },
            )
        }
    }
}
