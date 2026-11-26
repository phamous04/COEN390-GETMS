package com.example.app.ui.screens

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.RecordingApplication
import com.example.app.SESSION_INITIALIZATION_WAIT_MS
import com.example.app.data.ExerciseSessionRepository
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which side of the login toggle is selected. The backend still decides the real account type. */
enum class LoginRole { Patient, Clinician }

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    /**
     * [authenticated] is false while the session saved on this phone can't be refreshed (e.g.
     * offline): the user and their local data are known, server calls don't work yet.
     */
    data class SignedIn(val email: String?, val userId: String? = null, val authenticated: Boolean = true) : AuthState
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val account = AccountRepository()
    val configured = SupabaseProvider.configured

    private val _state = MutableStateFlow<AuthState>(if (configured) AuthState.Loading else AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info
    private val _role = MutableStateFlow(LoginRole.Patient)
    val role: StateFlow<LoginRole> = _role

    /**
     * Set by a fresh login: the account type the user picked on the toggle. Once the backend
     * account type is known, it is compared with this (see [confirmRole] / [rejectRole]).
     * Null for restored sessions and sign-ups.
     */
    private val _expectedRole = MutableStateFlow<LoginRole?>(null)
    val expectedRole: StateFlow<LoginRole?> = _expectedRole

    init {
        // The Supabase client restores a saved session from storage, so users stay logged in.
        if (configured) viewModelScope.launch {
            // Offline, the client can't refresh an old token: it stays "initializing" while the
            // token is close to expiry and reports a refresh failure once it has expired. The
            // account is still saved on this phone, so its local history must stay reachable.
            withTimeoutOrNull(SESSION_INITIALIZATION_WAIT_MS) { SupabaseProvider.auth.awaitInitialization() }
            account.sessionStatus.collect { status ->
                val user = account.signedInUser(status)
                _state.value = when {
                    user != null -> AuthState.SignedIn(user.email, user.id, user.authenticated)
                    status is SessionStatus.Initializing -> AuthState.Loading
                    else -> AuthState.SignedOut
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        val problem = validate(email, password, null)
        if (problem != null) return showError(problem)
        val chosen = _role.value
        submit {
            _expectedRole.value = chosen
            try {
                account.signIn(email, password)
            } catch (e: Exception) {
                _expectedRole.value = null
                throw e
            }
        }
    }

    fun selectRole(role: LoginRole) {
        if (_role.value == role) return
        _role.value = role
        clearMessages()
    }

    fun confirmRole() {
        _expectedRole.value = null
    }

    /** The account isn't the type picked on the toggle: sign out and point to the right side. */
    fun rejectRole(actual: LoginRole) {
        _expectedRole.value = null
        viewModelScope.launch {
            _busy.value = true
            try {
                // Only ends the session; local data is kept because it is the same person.
                account.signOut()
            } catch (_: Exception) {
            } finally {
                _busy.value = false
            }
            _role.value = actual
            _info.value = null
            _error.value = if (actual == LoginRole.Clinician) {
                "This is a clinician account. We switched to Clinician login, so log in again here."
            } else {
                "This is a patient account. We switched to Patient login, so log in again here."
            }
        }
    }

    fun signUp(email: String, password: String, confirm: String, fullName: String, requestClinician: Boolean) {
        val problem = when {
            configured && fullName.isBlank() -> "Please enter your name."
            configured && fullName.trim().length > MAX_NAME -> "Name must be $MAX_NAME characters or fewer."
            else -> validate(email, password, confirm)
        }
        if (problem != null) return showError(problem)
        submit {
            account.signUp(email, password, fullName, requestClinician)
            if (account.userId == null) _info.value = "Account created. Check your email to confirm it, then log in."
        }
    }

    fun signOut() {
        // Signed in from the saved session only (offline, it couldn't be refreshed): the server
        // can't be told, exactly as when logging out offline with a session that is still valid.
        if (configured && _state.value is AuthState.SignedIn && account.userId == null) {
            return showError("Can't reach the server. Check your internet connection.")
        }
        submit {
            // Sensor capture is closed and kept on this device; it uploads when the owner signs in again.
            (getApplication<Application>() as RecordingApplication).recordings.manager.stop("Signed out; recording saved on this device")
            val sessions = ExerciseSessionRepository.get(getApplication())
            // Upload finished sessions while the session JWT is still valid; failures stay queued locally.
            account.userId?.let { sessions.syncPending(it) }
            account.signOut()
            // Exercise rows already on the server are removed from this phone; unsynced ones are kept
            // (filtered by patient id) so they upload on the owner's next login. Sensor recordings are
            // kept per owner as well (they are never reassigned to another account).
            sessions.clearSyncedLocalCopies()
        }
    }

    fun clearMessages() {
        _error.value = null
        _info.value = null
    }

    private fun validate(email: String, password: String, confirm: String?): String? = when {
        !configured -> "Supabase is not configured. Add the project URL and key to local.properties."
        email.isBlank() -> "Please enter your email."
        !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Please enter a valid email address."
        password.isEmpty() -> "Please enter your password."
        confirm != null && password.length < MIN_PASSWORD -> "Password must be at least $MIN_PASSWORD characters."
        confirm != null && password != confirm -> "Passwords do not match."
        else -> null
    }

    private fun showError(message: String) {
        _info.value = null
        _error.value = message
    }

    private fun submit(action: suspend () -> Unit) {
        if (!configured || _busy.value) return
        clearMessages()
        viewModelScope.launch {
            _busy.value = true
            try {
                action()
            } catch (e: Exception) {
                _error.value = friendlyMessage(e)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun friendlyMessage(e: Exception): String {
        if (e is HttpRequestException) return "Can't reach the server. Check your internet connection."
        val text = if (e is RestException) "${e.error} ${e.description}".lowercase() else ""
        return when {
            "invalid login credentials" in text || "invalid_credentials" in text -> "Incorrect email or password."
            "not confirmed" in text -> "Please confirm your email before logging in."
            "already registered" in text || "already exists" in text -> "An account with this email already exists."
            "password" in text -> "Password is too weak. Use at least $MIN_PASSWORD characters."
            "rate limit" in text -> "Too many attempts. Please wait a moment and try again."
            else -> "Something went wrong. Please try again."
        }
    }

    private companion object {
        const val MIN_PASSWORD = 8
        const val MAX_NAME = 80
    }
}
