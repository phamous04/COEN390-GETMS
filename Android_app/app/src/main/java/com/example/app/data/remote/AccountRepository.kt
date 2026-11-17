package com.example.app.data.remote

import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AccountRepository {
    val sessionStatus get() = SupabaseProvider.auth.sessionStatus
    val userId get() = SupabaseProvider.auth.currentUserOrNull()?.id
    val email get() = SupabaseProvider.auth.currentUserOrNull()?.email

    suspend fun signIn(email: String, password: String) {
        SupabaseProvider.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /**
     * [requestClinician] creates a clinician account (verified automatically by the server). It still
     * has no patient access until a patient enters the clinician's code and consents.
     */
    suspend fun signUp(email: String, password: String, fullName: String, requestClinician: Boolean) {
        SupabaseProvider.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName.trim())
                put("account_type", if (requestClinician) "clinician" else "patient")
            }
        }
    }

    suspend fun signOut() = SupabaseProvider.auth.signOut()
}
