package com.example.app.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.app.data.remote.ClinicianConnection
import com.example.app.data.remote.ConnectionKind
import com.example.app.data.remote.PatientConnectionStatus
import com.example.app.data.remote.AssignmentStatus
import com.example.app.data.remote.status
import com.example.app.ui.components.IconBadge
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.StatusGrey
import com.example.app.ui.theme.Teal

const val CONSENT_TEXT =
    "By connecting to this clinician, you agree to allow them to view your rehabilitation exercise history, session summaries, " +
        "and available recorded measurements while your connection remains active. You can remove this access at any time."

// Screen A (+ D) — My clinician and connection status
@Composable
fun MyClinicianScreen(viewModel: PatientClinicianViewModel, onBack: () -> Unit, onLinkClinician: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var removing by remember { mutableStateOf<ClinicianConnection?>(null) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    PairingPage("My clinician", onBack = onBack) {
        state.outcome?.let { outcome ->
            val text = when (outcome) {
                is ConnectionOutcome.Connected -> "Connected successfully. ${outcome.clinicianName} can now see your rehabilitation progress."
                is ConnectionOutcome.Pending -> "Request sent. ${outcome.clinicianName} must approve it before they can see your progress."
                is ConnectionOutcome.AlreadyConnected -> "You are already connected to ${outcome.clinicianName}."
            }
            MessageBanner(text, isError = false, onDismiss = viewModel::clearMessages)
        }
        state.actionMessage?.let { MessageBanner(it, isError = false, onDismiss = viewModel::clearMessages) }
        state.actionError?.let { MessageBanner(it, isError = true, onDismiss = viewModel::clearMessages) }

        val clinicians = state.clinicians
        when {
            state.loading && clinicians == null -> LoadingBlock("Loading your connection…")
            state.loadError != null && clinicians == null -> ErrorBlock(state.loadError!!, viewModel::refresh)
            clinicians != null -> {
                state.loadError?.let { ErrorBlock(it, viewModel::refresh) }
                when (val status = clinicians.status()) {
                    PatientConnectionStatus.None -> NotConnected(onLinkClinician)
                    is PatientConnectionStatus.Revoked -> {
                        val ended = status.ended
                        StatusCard(
                            title = ended.clinicianName,
                            pill = if (ended.status == AssignmentStatus.Rejected) "Request declined" else "Connection removed",
                            pillColor = MaterialTheme.colorScheme.error,
                            body = if (ended.status == AssignmentStatus.Rejected) {
                                "${ended.clinicianName} declined your reconnection request on ${formatDate(ended.endedAt)}. They cannot see your progress."
                            } else {
                                "${ended.clinicianName} ended your connection on ${formatDate(ended.endedAt)}. They can no longer see your progress. Your exercise data is still in your account."
                            },
                        )
                        NotConnected(onLinkClinician)
                    }
                    is PatientConnectionStatus.PendingApproval -> {
                        val c = status.connection
                        StatusCard(
                            title = c.clinicianName,
                            subtitle = c.clinicianEmailHint,
                            pill = "Pending clinician approval",
                            pillColor = Orange,
                            body = "You asked on ${formatDate(c.consentedAt)} to share your progress with ${c.clinicianName}. " +
                                "Nothing is shared until they approve.",
                        )
                        OutlineAction("Cancel request", MaterialTheme.colorScheme.error, { removing = c }, Modifier.fillMaxWidth(), loading = state.removing)
                    }
                    is PatientConnectionStatus.Connected -> {
                        val c = status.connection
                        StatusCard(
                            title = c.clinicianName,
                            subtitle = c.clinicianEmailHint,
                            pill = "Connected",
                            pillColor = Teal,
                            body = "${c.clinicianName} can view your rehabilitation progress while you stay connected.",
                        )
                        OutlineAction(
                            if (showDetails) "Hide connection information" else "View connection information",
                            Teal,
                            { showDetails = !showDetails },
                            Modifier.fillMaxWidth(),
                        )
                        if (showDetails) ConnectionDetails(c)
                        OutlineAction("Remove clinician", MaterialTheme.colorScheme.error, { removing = c }, Modifier.fillMaxWidth(), loading = state.removing)
                        Text(
                            "To switch to a different clinician, enter their code. You'll be asked to confirm the change.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlineAction("Link a different clinician", Teal, onLinkClinician, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    removing?.let { c ->
        val pending = c.status == AssignmentStatus.Pending
        ConfirmDialog(
            title = if (pending) "Cancel your request?" else "Remove ${c.clinicianName}?",
            text = if (pending) {
                "${c.clinicianName} will not receive your request. You can send a new one later with their current code."
            } else {
                "${c.clinicianName} will immediately lose access to your rehabilitation progress. Your exercise data stays in your account. " +
                    "You can reconnect later with their current code."
            },
            confirmLabel = if (pending) "Cancel request" else "Remove clinician",
            dismissLabel = if (pending) "Keep request" else "Cancel",
            destructive = true,
            onConfirm = {
                removing = null
                viewModel.removeClinician(c.clinicianId, c.clinicianName, wasPending = pending)
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun NotConnected(onLinkClinician: () -> Unit) {
    EmptyState(
        ReBandIcons.Clinician,
        "No clinician connected",
        "You are not currently connected to a clinician. Enter your clinician's code to share your rehabilitation progress.",
    )
    PrimaryButton("Link a clinician", onClick = onLinkClinician)
}

@Composable
private fun StatusCard(title: String, pill: String, pillColor: androidx.compose.ui.graphics.Color, body: String, subtitle: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (pillColor == Teal) BorderStroke(1.5.dp, Teal) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ReBandIcons.Clinician)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            StatusPill(pill, pillColor)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectionDetails(c: ClinicianConnection) {
    ReBandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRow("Consent given", formatDate(c.consentedAt))
            c.approvedAt?.let { DetailRow("Approved by clinician", formatDate(it)) }
            Text("What is shared", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your exercise history, session summaries and available recorded measurements, read-only. " +
                    "Your clinician cannot change or delete your recordings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// Screen B — Enter clinician code
@Composable
fun EnterClinicianCodeScreen(viewModel: PatientClinicianViewModel, onBack: () -> Unit, onFound: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.navigateToConfirm) {
        if (state.navigateToConfirm) {
            viewModel.onNavigatedToConfirm()
            onFound()
        }
    }

    PairingPage("Enter clinician code", onBack = onBack) {
        Text(
            "Enter the 8-character code your clinician gave you. You'll see who it belongs to before anything is shared.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = viewModel::onCodeChange,
            label = { Text("Clinician code") },
            placeholder = { Text("ABCD-EF23") },
            singleLine = true,
            isError = state.codeError != null,
            supportingText = { Text(state.codeError ?: "Letters and numbers; the dash is optional.") },
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { viewModel.lookup() }),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Teal,
                focusedLabelColor = Teal,
                cursorColor = Teal,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Continue", onClick = viewModel::lookup, loading = state.lookingUp)
    }
}

// Screen C — Confirm clinician connection (explicit consent)
@Composable
fun ConfirmClinicianScreen(viewModel: PatientClinicianViewModel, onCancel: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val lookup = state.pending
    val cancel = {
        viewModel.cancelConfirmation()
        onCancel()
    }
    BackHandler(enabled = !state.confirming) { cancel() }
    LaunchedEffect(state.finished) {
        if (state.finished) {
            viewModel.onFinishedHandled()
            onDone()
        }
    }

    PairingPage("Confirm connection", onBack = { if (!state.confirming) cancel() }) {
        if (lookup == null) {
            // Process death or a stale route: start over rather than guessing.
            if (!state.finished) {
                EmptyState(ReBandIcons.Clinician, "Nothing to confirm", "Enter your clinician's code to start linking.") {
                    OutlineAction("Enter a code", Teal, onCancel)
                }
            }
            return@PairingPage
        }
        StatusPill("Waiting for your confirmation", Orange)
        ReBandCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ReBandIcons.Clinician)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text("This code belongs to", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(lookup.clinicianName, style = MaterialTheme.typography.titleLarge)
                    lookup.clinicianEmailHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text(
            "Make sure this is your clinician. If you don't recognise them, cancel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (lookup.kind) {
            ConnectionKind.AlreadyConnected -> MessageBanner("You are already connected to ${lookup.clinicianName}.", isError = false)
            ConnectionKind.AlreadyPending -> MessageBanner("You already asked to reconnect with ${lookup.clinicianName}. Your request is waiting for their approval.", isError = false)
            ConnectionKind.NeedsApproval -> MessageBanner(
                "${lookup.clinicianName} removed you from their patients earlier, so they must approve this request before they can see your progress.",
                isError = false,
            )
            ConnectionKind.Direct -> Unit
        }
        lookup.currentClinicianName?.let { current ->
            MessageBanner(
                "You are currently connected to $current. Continuing will remove $current's access and connect you to ${lookup.clinicianName} instead.",
                isError = false,
            )
        }

        Text("What you're agreeing to", style = MaterialTheme.typography.titleMedium)
        ReBandCard { Text(CONSENT_TEXT, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) }

        state.confirmError?.let { MessageBanner(it, isError = true) }

        val label = when {
            lookup.kind == ConnectionKind.AlreadyConnected || lookup.kind == ConnectionKind.AlreadyPending -> "Done"
            lookup.kind == ConnectionKind.NeedsApproval -> "I agree, send request"
            else -> "I agree, connect"
        }
        PrimaryButton(label, onClick = { viewModel.confirm() }, loading = state.confirming)
        OutlineAction("Cancel", StatusGrey, { cancel() }, Modifier.fillMaxWidth(), enabled = !state.confirming)
    }

    state.switchFrom?.let { current ->
        val target = lookup?.clinicianName ?: "the new clinician"
        val pendingSwitch = lookup?.kind == ConnectionKind.NeedsApproval
        ConfirmDialog(
            title = "Switch clinicians?",
            text = "You can only be connected to one clinician at a time. $current will immediately lose access to your progress. " +
                if (pendingSwitch) "$target must approve your request before they can see anything." else "$target will be able to see your progress.",
            confirmLabel = "Switch",
            destructive = true,
            onConfirm = { viewModel.confirm(replaceCurrent = true) },
            onDismiss = viewModel::dismissSwitch,
            dismissLabel = "Keep $current",
        )
    }
}
