package com.example.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun HomeScreen(onOpenSensor: () -> Unit, onOpenHistory: () -> Unit, onOpenProfile: () -> Unit) {
    ScreenScaffold("Home") {
        Button(onClick = onOpenSensor) { Text("Sensor") }
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
fun ProfileScreen(onBack: () -> Unit, viewModel: ProfileViewModel = viewModel()) {
    val message by viewModel.message.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    ScreenScaffold("Profile") {
        Text(message)
        if (signedIn) {
            Button(onClick = viewModel::signOut) { Text("Sign out") }
        } else {
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(value = password, onValueChange = { password = it },
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { viewModel.signIn(email, password); password = "" }) { Text("Sign in") }
            Button(onClick = { viewModel.signUp(email, password); password = "" }) { Text("Create account") }
        }
        Button(onClick = onBack) { Text("Back") }
    }
}
