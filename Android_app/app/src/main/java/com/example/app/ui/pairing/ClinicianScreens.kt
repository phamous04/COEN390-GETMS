package com.example.app.ui.pairing

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app.data.remote.AccountInfo
import com.example.app.data.remote.AssignedPatient
import com.example.app.data.remote.PatientActivity
import com.example.app.ui.history.formatSessionDate
import com.example.app.data.remote.ReconnectionRequest
import com.example.app.ui.components.IconBadge
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealDark
import kotlinx.coroutines.launch

// Screen A — Clinician dashboard
@Composable
fun ClinicianDashboardScreen(
    account: AccountInfo,
    viewModel: ClinicianViewModel,
    onOpenCode: () -> Unit,
    onOpenPatients: () -> Unit,
    onOpenRequests: () -> Unit,
    onOpenPatient: (String) -> Unit,
    onEditName: () -> Unit,
    activity: Map<String, PatientActivity> = emptyMap(),
) {
    val state by viewModel.state.collectAsState()
    var removing by remember { mutableStateOf<AssignedPatient?>(null) }

    PairingPage("Clinician dashboard") {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Teal, TealDark)))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(account.fullName ?: "Welcome", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            account.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f)) }
            Text("Verified clinician", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        if (account.fullName.isNullOrBlank()) {
            MessageBanner("Add your name so patients can recognise you before they share their progress.", isError = false)
            OutlineAction("Add your name", Teal, onEditName, Modifier.fillMaxWidth())
        }

        state.message?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessage) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Active patients", if (state.loading && state.patients.isEmpty()) "…" else state.patients.size.toString(), Modifier.weight(1f), onOpenPatients)
            StatTile("Pending requests", if (state.loading && state.requests.isEmpty()) "…" else state.requests.size.toString(), Modifier.weight(1f), onOpenRequests)
        }

        NavCard(
            icon = ReBandIcons.Key,
            title = "My clinician code",
            subtitle = state.code?.let { "Current code: ${it.display}" } ?: "Create a code to invite patients",
            onClick = onOpenCode,
        )
        if (state.requests.isNotEmpty()) {
            NavCard(
                icon = ReBandIcons.PersonAdd,
                title = "Reconnection requests",
                subtitle = "${state.requests.size} waiting for your approval",
                onClick = onOpenRequests,
                accent = Orange,
            )
        }

        Text("Connected patients", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.loading && state.patients.isEmpty() -> LoadingBlock("Loading patients…")
            state.error != null -> ErrorBlock(state.error!!, viewModel::refresh)
            state.patients.isEmpty() -> EmptyState(
                ReBandIcons.People,
                "No connected patients yet",
                "Share your clinician code with patients. They appear here once they confirm they want to share their progress with you.",
            ) { OutlineAction("Show my code", Teal, onOpenCode) }
            else -> state.patients.forEach { patient ->
                PatientCard(patient, activity[patient.patientId], busy = patient.patientId in state.busyIds, onView = { onOpenPatient(patient.patientId) }, onRemove = { removing = patient })
            }
        }
    }

    removing?.let { patient -> RemovePatientDialog(patient, onConfirm = { removing = null; viewModel.removePatient(patient) }, onDismiss = { removing = null }) }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    ReBandCard(modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = Teal, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NavCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, accent: Color = Teal) {
    ReBandCard(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (accent == Teal) MaterialTheme.colorScheme.onSurface else accent)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(ReBandIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PatientCard(patient: AssignedPatient, activity: PatientActivity?, busy: Boolean, onView: () -> Unit, onRemove: () -> Unit) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Initial(patient.name)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(patient.name, style = MaterialTheme.typography.titleMedium)
                    patient.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "Sharing since ${formatDate(patient.connectedSince)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Last completed exercise: " + (activity?.lastCompletedAtMillis?.let { formatSessionDate(it) } ?: "none yet"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineAction("View history", Teal, onView, Modifier.weight(1f), enabled = !busy)
                OutlineAction("Remove", MaterialTheme.colorScheme.error, onRemove, Modifier.weight(1f), loading = busy)
            }
        }
    }
}

@Composable
private fun Initial(name: String) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}

@Composable
private fun RemovePatientDialog(patient: AssignedPatient, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Remove ${patient.name}?",
        text = "You will immediately lose access to ${patient.name}'s rehabilitation progress. Their exercise data stays in their account. " +
            "If they ask to reconnect later, you will need to approve the request.",
        confirmLabel = "Remove patient",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// Screen B — My clinician code
@Composable
fun ClinicianCodeScreen(viewModel: ClinicianViewModel, onBack: (() -> Unit)?) {
    val state by viewModel.state.collectAsState()
    var confirmRegenerate by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    PairingPage("My clinician code", onBack = onBack) {
        Text(
            "Share this code with your patients. The same code works for as many patients as you need. " +
                "Entering it does not give you access: each patient must confirm that they want to share their progress with you.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessage) }
        state.codeError?.let { MessageBanner(it, isError = true) }

        val code = state.code
        when {
            state.loading && code == null -> LoadingBlock("Loading your code…")
            state.error != null && code == null -> ErrorBlock(state.error!!, viewModel::refresh)
            code == null -> EmptyState(
                ReBandIcons.Key,
                "You don't have a code yet",
                "Create your invitation code, then share it with your patients.",
            ) { PrimaryButton("Create my code", onClick = viewModel::generateCode, loading = state.codeBusy) }
            else -> {
                ReBandCard {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Your active code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            code.display,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp,
                            letterSpacing = 3.sp,
                            color = Teal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { contentDescription = "Code " + code.code.toList().joinToString(" ") },
                        )
                        Text("Created ${formatDate(code.updatedAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineAction(if (copied) "Copied" else "Copy code", Teal, {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ReBand clinician code", code.display)))
                            copied = true
                        }
                    }, Modifier.weight(1f), icon = ReBandIcons.Copy)
                    OutlineAction("Share", Teal, {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Connect with me on ReBand: open the app, go to Settings > My clinician and enter the code ${code.display}.",
                            )
                        }
                        context.startActivity(Intent.createChooser(send, "Share clinician code"))
                    }, Modifier.weight(1f), icon = ReBandIcons.Share)
                }
                OutlineAction(
                    "Generate a new code",
                    Orange,
                    { confirmRegenerate = true },
                    Modifier.fillMaxWidth(),
                    loading = state.codeBusy,
                )
                Text(
                    "Create a new code if this one was shared with someone it shouldn't have been. Connected patients stay connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    LaunchedEffect(state.code?.code) { copied = false }

    if (confirmRegenerate) {
        ConfirmDialog(
            title = "Generate a new code?",
            text = "Generating a new code will disable your previous code. Patients already connected to your account will remain connected.",
            confirmLabel = "Generate new code",
            destructive = false,
            onConfirm = { confirmRegenerate = false; viewModel.regenerateCode() },
            onDismiss = { confirmRegenerate = false },
        )
    }
}

// Screen C — Connected patients
@Composable
fun ConnectedPatientsScreen(
    viewModel: ClinicianViewModel,
    onOpenPatient: (String) -> Unit,
    onOpenCode: () -> Unit,
    activity: Map<String, PatientActivity> = emptyMap(),
) {
    val state by viewModel.state.collectAsState()
    var removing by remember { mutableStateOf<AssignedPatient?>(null) }

    PairingPage("Connected patients") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading && state.patients.isEmpty()) "Loading…" else "${state.patients.size} active " + if (state.patients.size == 1) "patient" else "patients",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlineAction("Refresh", Teal, viewModel::refresh, loading = state.loading)
        }
        state.message?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessage) }
        when {
            state.loading && state.patients.isEmpty() -> LoadingBlock("Loading patients…")
            state.error != null -> ErrorBlock(state.error!!, viewModel::refresh)
            state.patients.isEmpty() -> EmptyState(
                ReBandIcons.People,
                "No connected patients",
                "Patients appear here after they enter your code and confirm that they want to share their progress.",
            ) { OutlineAction("Show my code", Teal, onOpenCode) }
            else -> state.patients.forEach { patient ->
                PatientCard(patient, activity[patient.patientId], busy = patient.patientId in state.busyIds, onView = { onOpenPatient(patient.patientId) }, onRemove = { removing = patient })
            }
        }
    }

    removing?.let { patient -> RemovePatientDialog(patient, onConfirm = { removing = null; viewModel.removePatient(patient) }, onDismiss = { removing = null }) }
}

// Screen D — Pending reconnection requests
@Composable
fun ReconnectionRequestsScreen(viewModel: ClinicianViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var rejecting by remember { mutableStateOf<ReconnectionRequest?>(null) }
    var approving by remember { mutableStateOf<ReconnectionRequest?>(null) }

    PairingPage("Reconnection requests", onBack = onBack) {
        Text(
            "These patients were removed from your list earlier and have asked to share their progress with you again. " +
                "They gain nothing and you see nothing until you approve.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessage) }
        when {
            state.loading && state.requests.isEmpty() -> LoadingBlock("Loading requests…")
            state.error != null -> ErrorBlock(state.error!!, viewModel::refresh)
            state.requests.isEmpty() -> EmptyState(ReBandIcons.PersonAdd, "No pending requests", "Requests from previously removed patients will appear here.")
            else -> state.requests.forEach { request ->
                val busy = request.requestId in state.busyIds
                ReBandCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Initial(request.name)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(request.name, style = MaterialTheme.typography.titleMedium)
                                request.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("Requested ${formatDate(request.requestedAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        StatusPill("Waiting for your approval", Orange)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlineAction("Reject", MaterialTheme.colorScheme.error, { rejecting = request }, Modifier.weight(1f), enabled = !busy)
                            OutlineAction("Approve", Teal, { approving = request }, Modifier.weight(1f), loading = busy)
                        }
                    }
                }
            }
        }
    }

    approving?.let { request ->
        ConfirmDialog(
            title = "Approve ${request.name}?",
            text = "${request.name} has consented to share their rehabilitation progress with you. Approving reconnects them to your patient list.",
            confirmLabel = "Approve",
            destructive = false,
            onConfirm = { approving = null; viewModel.approve(request) },
            onDismiss = { approving = null },
        )
    }
    rejecting?.let { request ->
        ConfirmDialog(
            title = "Reject request?",
            text = "${request.name} will not be reconnected and you will not see their progress. They can send a new request later.",
            confirmLabel = "Reject",
            destructive = true,
            onConfirm = { rejecting = null; viewModel.reject(request) },
            onDismiss = { rejecting = null },
        )
    }
}

// Authorized progress for one patient. Access is re-checked with the server every time.
@Composable
fun PatientProgressScreen(
    patientId: String,
    viewModel: ClinicianViewModel,
    onBack: () -> Unit,
    history: @Composable () -> Unit = {},
    onRemoved: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var removing by remember { mutableStateOf<AssignedPatient?>(null) }
    LaunchedEffect(patientId) { viewModel.loadOverview(patientId) }

    PairingPage("Patient progress", onBack = onBack) {
        when (val overview = state.overview) {
            OverviewState.Loading -> LoadingBlock("Checking access…")
            is OverviewState.Failed -> ErrorBlock(overview.message) { viewModel.loadOverview(patientId) }
            OverviewState.NoAccess -> EmptyState(
                ReBandIcons.People,
                "No access to this patient",
                "This patient is no longer connected to you, so their progress can't be shown.",
            ) { OutlineAction("Back to patients", Teal, onBack) }
            is OverviewState.Ok -> {
                val patient = overview.patient
                ReBandCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Initial(patient.name)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(patient.name, style = MaterialTheme.typography.titleLarge)
                            patient.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("Sharing since ${formatDate(patient.connectedSince)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Exercise history (read-only)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                history()
                OutlineAction(
                    "Remove patient",
                    MaterialTheme.colorScheme.error,
                    { removing = patient },
                    Modifier.fillMaxWidth(),
                    loading = patient.patientId in state.busyIds,
                )
            }
        }
    }

    removing?.let { patient ->
        RemovePatientDialog(patient, onConfirm = {
            removing = null
            viewModel.removePatient(patient)
            onRemoved(patient.patientId)
            onBack()
        }, onDismiss = { removing = null })
    }
}

// Clinician account tab
@Composable
fun ClinicianAccountScreen(account: AccountInfo, busy: Boolean, onEditName: () -> Unit, onLogout: () -> Unit) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    PairingPage("Account") {
        ReBandCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Initial(account.fullName ?: account.email ?: "?")
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(account.fullName ?: "No name set", style = MaterialTheme.typography.titleMedium)
                    Text(account.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill("Verified", Teal)
            }
        }
        NavCard(ReBandIcons.Person, "Your name", account.fullName ?: "Add the name patients will see", onEditName)
        OutlineAction("Log out", MaterialTheme.colorScheme.error, { confirmLogout = true }, Modifier.fillMaxWidth(), loading = busy)
    }
    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            text = "You will need to log in again to use ReBand.",
            confirmLabel = "Log out",
            destructive = true,
            onConfirm = { confirmLogout = false; onLogout() },
            onDismiss = { confirmLogout = false },
        )
    }
}

/** Clinician account whose access an administrator turned off: no patient features. */
@Composable
fun PendingVerificationScreen(account: AccountInfo, busy: Boolean, onRefresh: () -> Unit, onEditName: () -> Unit, onLogout: () -> Unit) {
    PairingPage("Clinician access paused", topLevel = true) {
        EmptyState(
            ReBandIcons.Pending,
            "Clinician access is turned off",
            "A ReBand administrator has turned off clinician access for this account, so you can't use your invitation code or view patient progress. " +
                "Contact your ReBand administrator.",
        )
        ReBandCard {
            Column(Modifier.padding(16.dp)) {
                Text(account.fullName ?: "No name set", style = MaterialTheme.typography.titleMedium)
                Text(account.email ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StatusPill("Access paused", Orange)
        OutlineAction("Check again", Teal, onRefresh, Modifier.fillMaxWidth())
        OutlineAction("Edit your name", Teal, onEditName, Modifier.fillMaxWidth())
        OutlineAction("Log out", MaterialTheme.colorScheme.error, onLogout, Modifier.fillMaxWidth(), loading = busy)
    }
}

/** The backend account type could not be loaded (offline, or server not set up yet). */
@Composable
fun AccountLoadErrorScreen(message: String, onRetry: () -> Unit, onContinue: () -> Unit, onLogout: () -> Unit) {
    PairingPage("Couldn't load your account", topLevel = true) {
        MessageBanner(message, isError = true)
        Text(
            "You can still use your wearable and exercises. Clinician features need a connection to ReBand.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Try again", onClick = onRetry)
        OutlineAction("Continue to exercises", Teal, onContinue, Modifier.fillMaxWidth())
        OutlineAction("Log out", MaterialTheme.colorScheme.error, onLogout, Modifier.fillMaxWidth())
    }
}
