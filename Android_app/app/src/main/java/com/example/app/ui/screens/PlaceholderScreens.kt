package com.example.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import com.example.app.ui.components.ComingSoon
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.components.ScreenTitle
import com.example.app.ui.theme.Teal
import com.example.app.data.local.UploadStatus

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Teal)
    }
}

@Composable
fun ExercisesScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        ScreenTitle("Exercises")
        ComingSoon("Exercise library", ReBandIcons.Exercises, "Your prescribed exercises will be listed here.")
    }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val recordings by viewModel.recordings.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        ScreenTitle("History")
        if (recordings.isEmpty()) {
            Text("Your saved recordings will appear here, including sessions waiting for upload.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(recordings, key = { it.id }) { recording ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${recording.exercise} • ${recording.arm} arm", style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance().format(Date(recording.startedAtMillis)))
                        val uploadLabel = when (recording.uploadStatus) {
                            UploadStatus.PENDING -> "Waiting to upload"
                            UploadStatus.UPLOADING -> "Uploading"
                            UploadStatus.UPLOADED -> "Uploaded"
                            UploadStatus.FAILED -> "Upload failed"
                        }
                        val status = recording.recordingStatus.name.lowercase().replaceFirstChar { it.uppercase() }
                        Text("${recording.elapsedDurationMillis / 1000}s • $status • $uploadLabel")
                        if (recording.retryCount > 0) Text("Upload attempts: ${recording.retryCount}")
                        recording.recordingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        recording.lastUploadError?.takeIf { it != recording.recordingError }?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
