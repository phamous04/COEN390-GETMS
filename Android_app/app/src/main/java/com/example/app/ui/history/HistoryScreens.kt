package com.example.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.example.app.data.RestoreState
import com.example.app.data.local.RecordingEntity
import com.example.app.data.local.UploadStatus
import com.example.app.ui.components.ReBandCard
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.app.data.local.SessionWithReps
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.pairing.EmptyState
import com.example.app.ui.pairing.LoadingBlock
import com.example.app.ui.pairing.MessageBanner
import com.example.app.ui.pairing.OutlineAction
import com.example.app.ui.pairing.PairingPage
import com.example.app.ui.theme.Teal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Reverse-chronological session list. Shared by the patient History tab and the clinician view. */
@Composable
fun SessionHistoryList(
    sessions: List<SessionWithReps>?,
    onOpenSession: (String) -> Unit,
    emptyTitle: String,
    emptyMessage: String,
    emptyAction: (@Composable () -> Unit)? = null,
    showSyncState: Boolean = false,
) {
    when {
        sessions == null -> LoadingBlock("Loading sessions…")
        sessions.isEmpty() -> EmptyState(ReBandIcons.History, emptyTitle, emptyMessage, emptyAction)
        else -> {
            Text(
                "${sessions.size} saved " + if (sessions.size == 1) "session" else "sessions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sessions.forEach { s -> SessionListItem(s, showSyncState) { onOpenSession(s.session.id) } }
        }
    }
}

/**
 * The patient's own history. Sessions come from Room, so they show at once and offline; [restore]
 * tells whether the older ones kept on the server (after a sign-in or reinstall) are in yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientHistoryScreen(
    history: List<SessionWithReps>?,
    recordings: List<RecordingEntity>,
    restore: RestoreState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSession: (String) -> Unit,
    onStartExercise: () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        PairingPage("History") {
            if (restore is RestoreState.Failed) {
                MessageBanner("Couldn't load older sessions. ${restore.message}", isError = true)
                OutlineAction("Retry", Teal, onRefresh, Modifier.fillMaxWidth(), loading = refreshing)
            }
            SessionHistoryList(
                // An empty phone is not "no sessions" until the server has been asked.
                if (history?.isEmpty() == true && restore is RestoreState.Loading) null else history,
                onOpenSession,
                emptyTitle = "No sessions yet",
                emptyMessage = "Your saved Bicep Curl sessions will appear here.",
                emptyAction = { OutlineAction("Start Exercise", Teal, onStartExercise) },
                showSyncState = true,
            )
            if (!history.isNullOrEmpty() && restore is RestoreState.Loading) {
                Text(
                    "Checking for older sessions…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recordings.isNotEmpty()) {
                Text("Sensor recordings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Raw EMG data saved on this phone, including recordings waiting for upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recordings.forEach { SensorRecordingCard(it) }
            }
            // Unlike sessions, raw recordings have no server list to restore from (see OFFLINE_RECORDINGS.md).
            Text(
                "Raw sensor recordings are listed only on the phone that recorded them. Recordings already " +
                    "uploaded stay stored with your account, but are not listed again after the app is reinstalled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One offline sensor recording (RecordingRepository) with its recording and upload state. */
@Composable
fun SensorRecordingCard(recording: RecordingEntity) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${recording.exercise} • ${recording.arm} arm", style = MaterialTheme.typography.titleMedium)
            Text(DateFormat.getDateTimeInstance().format(Date(recording.startedAtMillis)), style = MaterialTheme.typography.bodyMedium)
            val uploadLabel = when (recording.uploadStatus) {
                UploadStatus.PENDING -> "Waiting to upload"
                UploadStatus.UPLOADING -> "Uploading"
                UploadStatus.UPLOADED -> "Uploaded"
                UploadStatus.FAILED -> "Upload failed"
            }
            val status = recording.recordingStatus.name.lowercase().replaceFirstChar { it.uppercase() }
            Text("${recording.elapsedDurationMillis / 1000}s • $status • $uploadLabel", style = MaterialTheme.typography.bodyMedium)
            if (recording.retryCount > 0) Text("Upload attempts: ${recording.retryCount}", style = MaterialTheme.typography.bodySmall)
            recording.recordingError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            recording.lastUploadError?.takeIf { it != recording.recordingError }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One saved session; read-only. Used by the patient (Room) and the clinician (server). */
@Composable
fun SessionDetailScreen(session: Flow<SessionWithReps?>, onBack: () -> Unit) {
    val flow = remember(session) { session.map { it to true } }
    val result by flow.collectAsState(initial = Pair<SessionWithReps?, Boolean>(null, false))
    SessionDetailPage(result.first, loaded = result.second, onBack = onBack)
}

@Composable
fun SessionDetailPage(data: SessionWithReps?, loaded: Boolean, onBack: () -> Unit) {
    PairingPage("Session details", onBack = onBack) {
        when {
            data != null -> SessionDetailContent(data)
            !loaded -> LoadingBlock("Loading session…")
            else -> EmptyState(ReBandIcons.History, "Session not found", "This session is not available.") {
                OutlineAction("Back", Teal, onBack, Modifier.fillMaxWidth())
            }
        }
    }
}
