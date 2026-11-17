package com.example.app.ui.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.history.SessionDetailPage
import com.example.app.ui.history.SessionHistoryList
import com.example.app.ui.theme.Teal

/** Assigned patient's sessions as the server returns them for this clinician (read-only). */
@Composable
fun ClinicianPatientHistory(viewModel: ClinicianHistoryViewModel, onOpenSession: (String) -> Unit, onRetry: () -> Unit) {
    val state by viewModel.sessions.collectAsState()
    when (val s = state) {
        PatientSessionsState.Loading -> LoadingBlock("Loading exercise history…")
        is PatientSessionsState.Failed -> ErrorBlock(s.message, onRetry)
        PatientSessionsState.NoAccess -> EmptyState(
            ReBandIcons.People,
            "No access",
            "This patient's exercise records are not shared with you.",
        )
        is PatientSessionsState.Ok -> SessionHistoryList(
            s.sessions,
            onOpenSession,
            emptyTitle = "No sessions shared yet",
            emptyMessage = "Sessions appear here after the patient finishes one and their phone uploads it.",
        )
    }
}

@Composable
fun ClinicianSessionDetail(viewModel: ClinicianHistoryViewModel, patientId: String, sessionId: String, onBack: () -> Unit) {
    val state by viewModel.sessions.collectAsState()
    when (val s = state) {
        PatientSessionsState.NoAccess -> PairingPage("Session details", onBack = onBack) {
            EmptyState(ReBandIcons.People, "No access", "This patient is no longer connected to you, so their records can't be shown.") {
                OutlineAction("Back", Teal, onBack)
            }
        }
        is PatientSessionsState.Failed -> PairingPage("Session details", onBack = onBack) {
            ErrorBlock(s.message) { viewModel.loadPatient(patientId) }
        }
        PatientSessionsState.Loading -> SessionDetailPage(null, loaded = false, onBack = onBack)
        is PatientSessionsState.Ok -> SessionDetailPage(
            data = if (s.patientId == patientId) s.sessions.firstOrNull { it.session.id == sessionId } else null,
            loaded = s.patientId == patientId,
            onBack = onBack,
        )
    }
}
