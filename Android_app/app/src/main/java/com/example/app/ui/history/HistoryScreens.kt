package com.example.app.ui.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.app.data.local.SessionWithReps
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.pairing.EmptyState
import com.example.app.ui.pairing.LoadingBlock
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
            sessions.forEach { s -> SessionListItem(s) { onOpenSession(s.session.id) } }
        }
    }
}

@Composable
fun PatientHistoryScreen(
    history: List<SessionWithReps>?,
    onOpenSession: (String) -> Unit,
    onStartExercise: () -> Unit,
) {
    PairingPage("History") {
        SessionHistoryList(
            history,
            onOpenSession,
            emptyTitle = "No sessions yet",
            emptyMessage = "Your saved Bicep Curl sessions will appear here.",
            emptyAction = { OutlineAction("Start Exercise", Teal, onStartExercise) },
        )
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
