package com.example.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.ui.components.ComingSoon
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.components.ScreenTitle
import com.example.app.ui.theme.Teal

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
fun HistoryScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        ScreenTitle("History")
        ComingSoon("Session history", ReBandIcons.History, "Your past exercise sessions will appear here.")
    }
}
