package com.example.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
private fun ScreenScaffold(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
fun HomeScreen(onOpenHistory: () -> Unit, onOpenProfile: () -> Unit) {
    ScreenScaffold("Home") {
        Button(onClick = onOpenHistory) { Text("History") }
        Button(onClick = onOpenProfile) { Text("Profile") }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val recordings by viewModel.recordings.collectAsState()
    ScreenScaffold("History") {
        Text("Local recordings: ${recordings.size}")
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    ScreenScaffold("Profile") {
        Button(onClick = onBack) { Text("Back") }
    }
}
