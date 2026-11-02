package com.example.app.data.remote

import io.github.jan.supabase.auth.providers.builtin.Email

class AccountRepository {
    val sessionStatus get() = SupabaseProvider.auth.sessionStatus
    val userId get() = SupabaseProvider.auth.currentUserOrNull()?.id

    suspend fun signIn(email: String, password: String) {
        SupabaseProvider.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        SupabaseProvider.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() = SupabaseProvider.auth.signOut()
}
