package com.example.app.data.remote

import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account using this phone. [authenticated] is false while its saved session could not be
 * refreshed yet (no network, or a server error): local data is available, server calls are not.
 */
data class SignedInUser(val id: String, val email: String?, val authenticated: Boolean)

class AccountRepository {
    val sessionStatus get() = SupabaseProvider.auth.sessionStatus
    val userId get() = SupabaseProvider.auth.currentUserOrNull()?.id
    val email get() = SupabaseProvider.auth.currentUserOrNull()?.email

    /**
     * Who is signed in for [status]; null only when nobody is. The client reports a session it
     * cannot refresh (offline at start-up with an old token) as "initializing" or "refresh
     * failure" with no current user, although the account is still saved on the phone. A session
     * the server rejected is cleared by the client and arrives as NotAuthenticated instead.
     */
    suspend fun signedInUser(status: SessionStatus): SignedInUser? = resolveSignedInUser(status) { storedUser() }

    private suspend fun storedUser(): SignedInUser? = try {
        SupabaseProvider.auth.sessionManager.loadSession()?.user
            ?.let { SignedInUser(it.id, it.email, authenticated = false) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

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

/** The rule behind [AccountRepository.signedInUser]; [saved] reads the session stored on the phone. */
internal suspend fun resolveSignedInUser(status: SessionStatus, saved: suspend () -> SignedInUser?): SignedInUser? =
    when (status) {
        is SessionStatus.Authenticated ->
            status.session.user?.let { SignedInUser(it.id, it.email, authenticated = true) }
        is SessionStatus.NotAuthenticated -> null
        // Initializing or a refresh failure: the client has no current user, the phone still does.
        else -> saved()
    }
