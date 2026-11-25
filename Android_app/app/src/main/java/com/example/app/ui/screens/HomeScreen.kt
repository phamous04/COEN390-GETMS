package com.example.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.app.data.WeeklyActivity
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.history.SessionListItem
import com.example.app.ui.history.WeeklyActivityChart
import com.example.app.ui.pairing.LoadingBlock
import com.example.app.ui.pairing.OutlineAction
import com.example.app.ui.components.ReBandLogo
import com.example.app.ui.components.WearableStatusCard
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealDark

@Composable
fun HomeScreen(
    email: String?,
    wearable: WearableUiState,
    weekly: WeeklyActivity?,
    onConnectWearable: () -> Unit,
    onStartExercise: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ReBandLogo()

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Teal, TealDark)))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Welcome back!", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            if (email != null) {
                Text(email, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }

        WearableStatusCard(wearable) {
            when (wearable.status) {
                WearableStatus.Connected, WearableStatus.Connecting -> Unit
                else -> OutlinedButton(
                    onClick = onConnectWearable,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, Teal),
                ) { Text("Connect Wearable", color = Teal, style = MaterialTheme.typography.titleMedium) }
            }
        }

        // Weekly activity from saved sessions (Room); refreshes as soon as a session is saved.
        if (weekly == null) {
            LoadingBlock("Loading your week…")
        } else {
            WeeklyActivityChart(weekly)
            val recent = weekly.mostRecentCompleted
            if (recent != null) {
                Text("Most recent completed exercise", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SessionListItem(recent) { onOpenSession(recent.session.id) }
            } else {
                Text(
                    "No completed sessions yet. Your first Bicep Curl session will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlineAction("View history", Teal, onOpenHistory, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Ready for today's exercise?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "Put on your ReBand wearable and follow the session at your own pace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // Enabled only once the shared BLE connection is up and the ReBand service is validated.
        PrimaryButton(
            "Start Exercise",
            onClick = onStartExercise,
            enabled = wearable.canStartExercise,
            height = 64.dp,
        )
        if (!wearable.canStartExercise) {
            Text(
                "Connect your ReBand wearable to start an exercise.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
