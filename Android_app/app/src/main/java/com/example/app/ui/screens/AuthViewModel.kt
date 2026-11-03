package com.example.app.ui.screens

import android.app.Application
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.local.AppDatabase
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val email: String?) : AuthState
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

    init {
        // The Supabase client restores a saved session from storage, so users stay logged in.
        if (configured) viewModelScope.launch {
            SupabaseProvider.auth.awaitInitialization()
            account.sessionStatus.collect {
                _state.value = if (account.userId != null) AuthState.SignedIn(account.email) else AuthState.SignedOut
            }
        }
    }

    fun signIn(email: String, password: String) {
        val problem = validate(email, password, null)
        if (problem != null) return showError(problem)
        submit { account.signIn(email, password) }
    }

    fun signUp(email: String, password: String, confirm: String) {
        val problem = validate(email, password, confirm)
        if (problem != null) return showError(problem)
        submit {
            account.signUp(email, password)
            if (account.userId == null) _info.value = "Account created. Check your email to confirm it, then log in."
        }
    }

    fun signOut() = submit {
        account.signOut()
        AppDatabase.get(getApplication()).recordingDao().clearAll()
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
    }
}
