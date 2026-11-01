package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.remote.AccountRepository
import com.example.app.data.remote.SupabaseProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val account = AccountRepository()
    private val _message = MutableStateFlow(if (SupabaseProvider.configured) "Restoring session…" else "Supabase is not configured")
    val message: StateFlow<String> = _message
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn

    init {
        if (SupabaseProvider.configured) viewModelScope.launch {
            SupabaseProvider.auth.awaitInitialization()
            account.sessionStatus.collect {
                _signedIn.value = account.userId != null
                _message.value = if (_signedIn.value) "Signed in" else "Sign in to sync recordings"
            }
        }
    }

    fun signIn(email: String, password: String) = submit { account.signIn(email, password) }

    fun signUp(email: String, password: String) = submit {
        account.signUp(email, password)
        if (account.userId == null) _message.value = "Check your email to confirm your account"
    }

    fun signOut() = submit { account.signOut() }

    private fun submit(action: suspend () -> Unit) {
        if (!SupabaseProvider.configured) return
        viewModelScope.launch {
            try {
                action()
            } catch (_: Exception) {
                _message.value = "Authentication failed. Check your details and connection."
            }
        }
    }
}
