package com.example.app.data.remote

import com.example.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest

/** The publishable key is client-visible. Database and Storage access still require user RLS. */
object SupabaseProvider {
    val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(configured) { "Supabase is not configured in local.properties" }
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            install(Auth) {
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            // RPC access to the pairing functions; RLS and server-side checks still apply.
            install(Postgrest)
        }
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest
}
