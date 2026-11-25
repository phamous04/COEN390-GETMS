package com.example.app.data.remote

import com.example.app.BuildConfig
import com.example.app.data.RecordingUploader
import com.example.app.data.local.RecordingEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the existing private recordings bucket and Supabase auth. File bodies stream from disk. */
internal data class RecordingUploadCredentials(val serverUrl: String, val key: String, val token: String, val userId: String)

class SupabaseRecordingUploader internal constructor(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).build(),
    private val credentials: suspend (String?) -> RecordingUploadCredentials = ::currentCredentials,
) : RecordingUploader {
    override suspend fun upload(recording: RecordingEntity, file: File) {
        val current = credentials(recording.userId)
        check(current.userId == recording.userId) { "Recording account changed" }
        val request = Request.Builder()
            .url("${current.serverUrl.trimEnd('/')}/storage/v1/object/recordings/${recording.userId}/${recording.id}.getms")
            .header("apikey", current.key)
            .header("Authorization", "Bearer ${current.token}")
            .header("x-upsert", "true")
            .post(file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException("Recording upload failed (HTTP ${it.code})"))
                    }
                }
            })
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private suspend fun currentCredentials(owner: String?): RecordingUploadCredentials {
    check(SupabaseProvider.configured) { "Supabase is not configured" }
    SupabaseProvider.auth.awaitInitialization()
    val session = SupabaseProvider.auth.currentSessionOrNull()
    check(session != null && session.user?.id == owner) { "Sign in as the recording owner to upload" }
    if (session.expiresAt.epochSeconds <= java.time.Instant.now().epochSecond + 60) {
        SupabaseProvider.auth.refreshCurrentSession()
    }
    val current = SupabaseProvider.auth.currentSessionOrNull()
    check(current != null && current.user?.id == owner && owner != null) { "Recording account changed" }
    return RecordingUploadCredentials(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        current.accessToken, owner)
}
