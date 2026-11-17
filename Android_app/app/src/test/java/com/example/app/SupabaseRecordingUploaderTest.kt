package com.example.app

import com.example.app.data.remote.RecordingUploadCredentials
import com.example.app.data.remote.SupabaseRecordingUploader
import com.example.app.data.local.RecordingEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

class SupabaseRecordingUploaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val credentials = RecordingUploadCredentials("https://example.test", "publishable", "token", "owner")
    private val recording = RecordingEntity(UUID.randomUUID().toString(), "Flexion", "Left", 1000, userId = "owner")

    @Test fun lostAcknowledgmentRetriesSameObjectWithSameFileAndUpsert() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val file = temporary.newFile().apply { writeText("immutable archive") }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            bodies += buffer.readUtf8()
            if (requests.size == 1) throw IOException("Server stored file but response was lost")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        val uploader = SupabaseRecordingUploader(client) { credentials }
        try { uploader.upload(recording, file); fail("Expected response loss") } catch (_: IOException) { }
        assertTrue(file.exists())
        uploader.upload(recording, file)
        assertEquals(2, requests.size)
        assertEquals(requests[0].url, requests[1].url)
        assertEquals("/storage/v1/object/recordings/owner/${recording.id}.getms", requests[0].url.encodedPath)
        assertTrue(requests.all { it.header("x-upsert") == "true" && it.header("Authorization") == "Bearer token" })
        assertEquals(listOf("immutable archive", "immutable archive"), bodies)
        assertTrue(file.exists()) // Only the repository commits confirmation and deletes.
    }

    @Test fun serverErrorNeverCountsAsConfirmedUpload() = runBlocking {
        val file = temporary.newFile()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("unavailable".toResponseBody()).build()
        }.build()
        val uploader = SupabaseRecordingUploader(client) { credentials }
        try { uploader.upload(recording, file); fail("Expected server error") } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
        assertTrue(file.exists())
    }

    @Test fun accountChangeBeforeRequestIsRejected() = runBlocking {
        val file = temporary.newFile()
        val client = OkHttpClient.Builder().addInterceptor { error("Network must not be used") }.build()
        val uploader = SupabaseRecordingUploader(client) { credentials.copy(userId = "other-owner") }
        try { uploader.upload(recording, file); fail("Expected owner check") } catch (_: IllegalStateException) { }
        assertTrue(file.exists())
    }
}
