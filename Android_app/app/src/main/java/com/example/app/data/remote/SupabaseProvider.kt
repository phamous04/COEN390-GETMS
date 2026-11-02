package com.example.app.data.remote

import com.example.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient

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
        }
    }

    val auth get() = client.auth
}
