package com.example.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.ble.DeviceErrorFlags
import com.example.app.data.DayActivity
import com.example.app.data.SessionMetrics
import com.example.app.data.WeeklyActivity
import com.example.app.data.local.ExerciseRepEntity
import com.example.app.data.local.ExerciseType
import com.example.app.data.local.SessionStatus
import com.example.app.data.local.SessionWithReps
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.exercise.Arm
import com.example.app.ui.pairing.StatusPill
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.StatusGrey
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// --- Formatting ------------------------------------------------------------------------------

private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

fun formatSessionDate(millis: Long?): String =
    millis?.let { zoned(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) } ?: "—"

fun formatSessionTime(millis: Long?): String =
    millis?.let { zoned(it).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) } ?: "—"

fun armLabel(db: String) = Arm.of(db)?.label ?: db

fun statusLabel(status: String) = when (status) {
    SessionStatus.COMPLETED -> "Completed"
    SessionStatus.INCOMPLETE -> "No reps counted"
    SessionStatus.INTERRUPTED -> "Interrupted"
    SessionStatus.IN_PROGRESS -> "In progress"
    else -> status
}

@Composable
fun statusColor(status: String): Color = when (status) {
    SessionStatus.COMPLETED -> Teal
    SessionStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
    else -> Orange
}

@Composable
private fun currentLocale(): Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

private val SessionWithReps.startMillis get() = session.startedAtMillis ?: session.createdAtMillis

// --- History list ----------------------------------------------------------------------------

@Composable
fun SessionListItem(data: SessionWithReps, onClick: () -> Unit) {
    val s = data.session
    ReBandCard(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ExerciseType.label(s.exerciseType), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.size(8.dp))
                    StatusPill(statusLabel(s.status), statusColor(s.status))
                }
                Text(
                    "${formatSessionDate(data.startMillis)} · ${formatSessionTime(data.startMillis)} · ${armLabel(s.exercisingArm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${s.repCount} ${if (s.repCount == 1) "rep" else "reps"} · ${SessionMetrics.clock(s.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(ReBandIcons.ChevronRight, contentDescription = "Open session", tint = Teal)
        }
    }
}

// --- Session details (summary, history detail, clinician view) ---------------------------------

@Composable
fun SessionDetailContent(data: SessionWithReps) {
    val s = data.session
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ExerciseType.label(s.exerciseType), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusPill(statusLabel(s.status), statusColor(s.status))
            }
            InfoRow("Date", formatSessionDate(data.startMillis))
            InfoRow("Start time", formatSessionTime(s.startedAtMillis))
            InfoRow("Duration", SessionMetrics.clock(s.durationMs))
            InfoRow("Exercising arm", armLabel(s.exercisingArm))
            InfoRow("Healthy (reference) arm", armLabel(s.referenceArm))
            InfoRow("Repetitions", "${s.repCount}")
            InfoRow("Clean repetitions (passed form check)", "${s.cleanRepCount}")
            if (s.targetReps > 0) InfoRow("Wearable set length", "${s.targetReps} reps")
            s.interruptionReason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    ComparisonSection(data)
    RepetitionSection("Healthy-arm reference curl", listOfNotNull(data.referenceRep), emptyText = "No reference curl was saved.")
    RepetitionSection("Exercising-arm repetitions", data.exerciseReps, emptyText = "No repetitions were counted.")
    QualitySection(data)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ComparisonSection(data: SessionWithReps) {
    val rows = SessionMetrics.comparison(data)
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Healthy Arm vs Exercising Arm", style = MaterialTheme.typography.titleMedium)
            Text(
                "Healthy arm: the single reference curl. Exercising arm: average of all counted reps unless noted. " +
                    "These are measurements only, not a recovery score or diagnosis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Text("Measure", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1.4f))
                Text("Healthy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("Exercising", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("Difference", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            rows.forEach { row ->
                val diff = row.difference?.let { d ->
                    SessionMetrics.formatSigned(d, row.unit) + (row.percentOfReference?.let { " (${String.format(Locale.getDefault(), "%.0f", it)}%)" } ?: "")
                } ?: "—"
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${row.label}: healthy ${SessionMetrics.format(row.reference, row.unit)}, " +
                            "exercising ${SessionMetrics.format(row.exercising, row.unit)}, difference $diff"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.4f))
                        Text(SessionMetrics.format(row.reference, row.unit), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(SessionMetrics.format(row.exercising, row.unit), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(diff, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                    row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text(
                "Difference = exercising − healthy; the percentage is the exercising value as a share of the healthy value.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RepetitionSection(title: String, reps: List<ExerciseRepEntity>, emptyText: String) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (reps.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            reps.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider()
                RepRow(r)
            }
        }
    }
}

@Composable
private fun RepRow(r: ExerciseRepEntity) {
    val clean = r.flags and com.example.app.ble.RepFlags.CLEAN != 0
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rep ${r.repIndex}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(if (clean) "Clean" else "Form flagged", if (clean) Teal else Orange)
        }
        Text(
            "Range ${SessionMetrics.format(r.romX10 / 10.0, SessionMetrics.MetricUnit.DEGREES)} · ${SessionMetrics.format(r.durationMs / 1000.0, SessionMetrics.MetricUnit.SECONDS)} · " +
                "starts at ${SessionMetrics.format(r.startMs / 1000.0, SessionMetrics.MetricUnit.SECONDS)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Activation peak ${SessionMetrics.format(r.peakActivationX10 / 10.0, SessionMetrics.MetricUnit.PERCENT_MVC)}, mean ${SessionMetrics.format(r.meanActivationX10 / 10.0, SessionMetrics.MetricUnit.PERCENT_MVC)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Twist ${r.twistDeg}° · swing ${r.swingDeg}° · out of plane ${r.planeDeg}°",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SessionMetrics.flagNotes(r.flags).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Orange)
        }
    }
}

@Composable
private fun QualitySection(data: SessionWithReps) {
    val s = data.session
    val ex = SessionMetrics.summarize(data.exerciseReps)
    val total = s.emgPacketsReceived + s.emgPacketsLost
    val locale = currentLocale()
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recording quality", style = MaterialTheme.typography.titleMedium)
            InfoRow("Rep details received", "${data.exerciseReps.size} of ${s.repCount}")
            InfoRow(
                "EMG packets received",
                if (total > 0) "${s.emgPacketsReceived} (${String.format(locale, "%.1f", s.emgPacketsLost * 100.0 / total)}% missing)" else "—",
            )
            InfoRow("Low-confidence reps", "${ex.lowConfidenceReps}")
            InfoRow("Reps with late motion samples", "${ex.missingSampleReps}")
            InfoRow("Reps with sensor saturation", "${ex.saturatedReps}")
            s.firmwareVersion?.let { InfoRow("Firmware", "${it shr 8}.${it and 0xFF}") }
            if (s.deviceErrorFlags and DeviceErrorFlags.ADC_UNCALIBRATED != 0) {
                Text("The wearable's ADC calibration data is missing, so mV values are approximate.", style = MaterialTheme.typography.bodySmall, color = Orange)
            }
            if (data.exerciseReps.size < s.repCount) {
                Text(
                    "Some rep details were not received over Bluetooth; the repetition count comes from the wearable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Orange,
                )
            }
        }
    }
}

// --- Weekly activity chart -------------------------------------------------------------------

@Composable
fun WeeklyActivityChart(week: WeeklyActivity) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                week.days.forEach { DayCell(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatBlock("Completed sessions", "${week.completedSessions}", Modifier.weight(1f))
                StatBlock("Active days", "${week.activeDays} / 7", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayCell(day: DayActivity) {
    val locale = currentLocale()
    val name = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    val full = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    val description = when {
        day.isFuture -> "$full: upcoming"
        day.exercised -> "$full: exercised, ${day.completedSessions} completed " + if (day.completedSessions == 1) "session" else "sessions"
        else -> "$full: no completed session"
    }
    Column(
        Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = if (day.isToday) Teal else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    when {
                        day.exercised -> Teal
                        day.isFuture -> Color.Transparent
                        else -> TealLight
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (day.exercised) "✓" else "${day.date.dayOfMonth}",
                color = when {
                    day.exercised -> Color.White
                    day.isFuture -> StatusGrey
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Box(Modifier.height(4.dp).size(width = 16.dp, height = 4.dp).clip(CircleShape).background(if (day.isToday) Orange else Color.Transparent))
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(TealLight).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = Teal)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
