package com.example.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.remote.AccountInfo
import com.example.app.data.remote.ApproveResult
import com.example.app.data.remote.AssignedPatient
import com.example.app.data.remote.ClinicianCode
import com.example.app.data.remote.ConfirmResult
import com.example.app.data.remote.InviteCodeFormat
import com.example.app.data.remote.LookupResult
import com.example.app.data.remote.MyClinicians
import com.example.app.data.remote.PairingRepository
import com.example.app.data.remote.ReconnectionRequest
import com.example.app.data.remote.SupabasePairingRepository
import com.example.app.data.remote.pairingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// All state shown here comes from the backend: after every action the lists are re-fetched,
// so a local update is never treated as proof that the server accepted it.

sealed interface AccountState {
    data object Loading : AccountState
    data class Ready(val account: AccountInfo) : AccountState
    data class Failed(val message: String) : AccountState
}

/** Loads the backend-verified account type that decides which screens the user gets. */
class AccountViewModel(private val repo: PairingRepository = SupabasePairingRepository()) : ViewModel() {
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state
    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError
    private val _savingName = MutableStateFlow(false)
    val savingName: StateFlow<Boolean> = _savingName

    init {
        load()
    }

    fun load() {
        _state.value = AccountState.Loading
        viewModelScope.launch {
            _state.value = try {
                AccountState.Ready(repo.getMyAccount())
            } catch (e: Exception) {
                AccountState.Failed(e.pairingMessage)
            }
        }
    }

    /** Returns through [onSaved] only after the server stored the name. */
    fun saveName(name: String, onSaved: () -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 80) {
            _nameError.value = "Please enter a name between 1 and 80 characters."
            return
        }
        viewModelScope.launch {
            _savingName.value = true
            _nameError.value = null
            try {
                repo.setMyFullName(clean)
                _state.value = AccountState.Ready(repo.getMyAccount())
                onSaved()
            } catch (e: Exception) {
                _nameError.value = e.pairingMessage
            } finally {
                _savingName.value = false
            }
        }
    }

    fun clearNameError() {
        _nameError.value = null
    }
}

sealed interface OverviewState {
    data object Loading : OverviewState
    data class Ok(val patient: AssignedPatient) : OverviewState
    data object NoAccess : OverviewState
    data class Failed(val message: String) : OverviewState
}

data class ClinicianUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val patients: List<AssignedPatient> = emptyList(),
    val requests: List<ReconnectionRequest> = emptyList(),
    val code: ClinicianCode? = null,
    val codeBusy: Boolean = false,
    val codeError: String? = null,
    /** Patient or request ids with an action in flight. */
    val busyIds: Set<String> = emptySet(),
    val message: String? = null,
    val overview: OverviewState = OverviewState.Loading,
)

/** Screens call [refresh] when they open, so lists are re-read from the server on every visit. */
class ClinicianViewModel(private val repo: PairingRepository = SupabasePairingRepository()) : ViewModel() {
    private val _state = MutableStateFlow(ClinicianUiState())
    val state: StateFlow<ClinicianUiState> = _state

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // coroutineScope rethrows a failed child here instead of crashing the scope.
                coroutineScope {
                    val patients = async { repo.getMyAssignedPatients() }
                    val requests = async { repo.getPendingReconnectionRequests() }
                    val code = async { repo.getMyClinicianCode() }
                    val loaded = Triple(patients.await(), requests.await(), code.await())
                    _state.update {
                        it.copy(loading = false, patients = loaded.first, requests = loaded.second, code = loaded.third)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.pairingMessage) }
            }
        }
    }

    fun generateCode() = codeAction { repo.generateClinicianCode() }

    fun regenerateCode() = codeAction(successMessage = "New code created. Your previous code no longer works.") {
        repo.regenerateClinicianCode()
    }

    private fun codeAction(successMessage: String? = null, action: suspend () -> ClinicianCode) {
        if (_state.value.codeBusy) return
        _state.update { it.copy(codeBusy = true, codeError = null) }
        viewModelScope.launch {
            try {
                val code = action()
                _state.update { it.copy(codeBusy = false, code = code, message = successMessage) }
            } catch (e: Exception) {
                _state.update { it.copy(codeBusy = false, codeError = e.pairingMessage) }
            }
        }
    }

    fun removePatient(patient: AssignedPatient) = action(patient.patientId) {
        if (repo.removePatient(patient.patientId)) {
            "${patient.name} was removed. They no longer share their progress with you."
        } else {
            "${patient.name} was already disconnected."
        }
    }

    fun approve(request: ReconnectionRequest) = action(request.requestId) {
        when (repo.approveReconnection(request.requestId)) {
            ApproveResult.Approved -> "${request.name} is connected again."
            ApproveResult.PatientHasActiveClinician -> "${request.name} is connected to another clinician, so this request can't be approved."
            ApproveResult.NotFound -> "This request is no longer pending."
        }
    }

    fun reject(request: ReconnectionRequest) = action(request.requestId) {
        if (repo.rejectReconnection(request.requestId)) {
            "Request from ${request.name} declined. They have no access to share with you."
        } else {
            "This request is no longer pending."
        }
    }

    private fun action(id: String, block: suspend () -> String) {
        if (id in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + id, error = null) }
        viewModelScope.launch {
            try {
                val message = block()
                _state.update { it.copy(message = message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.pairingMessage) }
            } finally {
                _state.update { it.copy(busyIds = it.busyIds - id) }
                refresh()
            }
        }
    }

    /** Re-checked with the server on every open, so stale routes to removed patients fail. */
    fun loadOverview(patientId: String) {
        _state.update { it.copy(overview = OverviewState.Loading) }
        viewModelScope.launch {
            val result = try {
                repo.getPatientOverview(patientId)?.let { OverviewState.Ok(it) } ?: OverviewState.NoAccess
            } catch (e: Exception) {
                OverviewState.Failed(e.pairingMessage)
            }
            _state.update { it.copy(overview = result) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** Shown on "My Clinician" after the consent flow finishes. */
sealed interface ConnectionOutcome {
    data class Connected(val clinicianName: String) : ConnectionOutcome
    data class Pending(val clinicianName: String) : ConnectionOutcome
    data class AlreadyConnected(val clinicianName: String) : ConnectionOutcome
}

data class PatientClinicianUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val clinicians: MyClinicians? = null,
    val codeInput: String = "",
    val codeError: String? = null,
    val lookingUp: Boolean = false,
    /** Lookup succeeded: waiting for the patient's explicit confirmation. */
    val pending: LookupResult.Found? = null,
    val navigateToConfirm: Boolean = false,
    val confirming: Boolean = false,
    val confirmError: String? = null,
    /** Name of the current clinician when the server asks the patient to confirm a switch. */
    val switchFrom: String? = null,
    val finished: Boolean = false,
    val outcome: ConnectionOutcome? = null,
    val removing: Boolean = false,
    val actionMessage: String? = null,
    val actionError: String? = null,
)

/** Shared by the My Clinician, Enter Code and Confirm routes; refreshed when My Clinician opens. */
class PatientClinicianViewModel(private val repo: PairingRepository = SupabasePairingRepository()) : ViewModel() {
    private val _state = MutableStateFlow(PatientClinicianUiState())
    val state: StateFlow<PatientClinicianUiState> = _state

    fun refresh() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val clinicians = repo.getMyClinicians()
                _state.update { it.copy(loading = false, clinicians = clinicians) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.pairingMessage) }
            }
        }
    }

    fun startLinking() = _state.update {
        it.copy(codeInput = "", codeError = null, pending = null, confirmError = null, switchFrom = null, outcome = null, actionMessage = null, actionError = null)
    }

    fun onCodeChange(value: String) = _state.update {
        // Allow a dash or spaces as typed, but not more than one code's worth of characters.
        val tooLong = InviteCodeFormat.normalize(value).length > InviteCodeFormat.LENGTH || value.length > InviteCodeFormat.LENGTH + 4
        if (tooLong) it else it.copy(codeInput = value.uppercase(), codeError = null)
    }

    /** Validates the code with the backend. Creates no relationship. */
    fun lookup() {
        val s = _state.value
        if (s.lookingUp) return
        InviteCodeFormat.validationError(s.codeInput)?.let { error ->
            _state.update { it.copy(codeError = error) }
            return
        }
        _state.update { it.copy(lookingUp = true, codeError = null) }
        viewModelScope.launch {
            try {
                when (val result = repo.lookupClinicianCode(s.codeInput)) {
                    is LookupResult.Found -> _state.update {
                        it.copy(lookingUp = false, pending = result, navigateToConfirm = true, confirmError = null, switchFrom = null)
                    }
                    LookupResult.InvalidCode -> _state.update {
                        it.copy(lookingUp = false, codeError = "We couldn't find a clinician with this code. Check it with your clinician; codes change when they create a new one.")
                    }
                    LookupResult.RateLimited -> _state.update {
                        it.copy(lookingUp = false, codeError = "Too many attempts. Please wait 15 minutes and try again.")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(lookingUp = false, codeError = e.pairingMessage) }
            }
        }
    }

    fun onNavigatedToConfirm() = _state.update { it.copy(navigateToConfirm = false) }

    /** The patient pressed Cancel: nothing was sent, so no relationship exists. */
    fun cancelConfirmation() = _state.update { it.copy(pending = null, confirmError = null, switchFrom = null) }

    fun dismissSwitch() = _state.update { it.copy(switchFrom = null) }

    /** Explicit consent. [replaceCurrent] is true only after the switch dialog was accepted. */
    fun confirm(replaceCurrent: Boolean = false) {
        val lookup = _state.value.pending ?: return
        if (_state.value.confirming) return
        _state.update { it.copy(confirming = true, confirmError = null, switchFrom = null) }
        viewModelScope.launch {
            try {
                when (val result = repo.confirmClinicianConnection(lookup.code, replaceCurrent)) {
                    is ConfirmResult.Connected -> finish(ConnectionOutcome.Connected(result.clinicianName))
                    is ConfirmResult.PendingApproval -> finish(ConnectionOutcome.Pending(result.clinicianName))
                    is ConfirmResult.AlreadyConnected -> finish(ConnectionOutcome.AlreadyConnected(result.clinicianName))
                    is ConfirmResult.HasActiveClinician -> _state.update {
                        it.copy(confirming = false, switchFrom = result.currentClinicianName)
                    }
                    ConfirmResult.InvalidCode -> _state.update {
                        it.copy(confirming = false, confirmError = "This code is no longer valid. Your clinician may have created a new code; ask them for their current one.")
                    }
                    ConfirmResult.RateLimited -> _state.update {
                        it.copy(confirming = false, confirmError = "Too many attempts. Please wait 15 minutes and try again.")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(confirming = false, confirmError = "Connection failed. ${e.pairingMessage}") }
            }
        }
    }

    private fun finish(outcome: ConnectionOutcome) {
        _state.update { it.copy(confirming = false, pending = null, outcome = outcome, finished = true, codeInput = "") }
        refresh()
    }

    fun onFinishedHandled() = _state.update { it.copy(finished = false) }

    fun removeClinician(clinicianId: String, clinicianName: String, wasPending: Boolean) {
        if (_state.value.removing) return
        _state.update { it.copy(removing = true, actionError = null, actionMessage = null, outcome = null) }
        viewModelScope.launch {
            try {
                repo.removeClinician(clinicianId)
                _state.update {
                    it.copy(
                        actionMessage = if (wasPending) {
                            "Your request to $clinicianName was cancelled."
                        } else {
                            "$clinicianName no longer has access to your progress. Your exercise data is still in your account."
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(actionError = e.pairingMessage) }
            } finally {
                _state.update { it.copy(removing = false) }
                refresh()
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(actionMessage = null, actionError = null, outcome = null) }
}
