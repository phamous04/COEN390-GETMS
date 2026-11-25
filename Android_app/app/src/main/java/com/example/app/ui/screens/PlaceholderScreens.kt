package com.example.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.app.ui.components.IconBadge
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.pairing.OutlineAction
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.components.ScreenTitle
import com.example.app.ui.theme.Teal

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Teal)
    }
}

/** Exercise selection. Bicep Curl is the only exercise the firmware supports. */
@Composable
fun ExercisesScreen(canStart: Boolean, onStartBicepCurl: () -> Unit, onConnectWearable: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Exercises")
        ReBandCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(ReBandIcons.Exercises)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Bicep Curl", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Healthy-arm reference, calibration, then your exercise set. Reps are counted by the wearable.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PrimaryButton("Start Bicep Curl", onClick = onStartBicepCurl, enabled = canStart)
                if (!canStart) {
                    Text("Connect your ReBand wearable first.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlineAction("Connect Wearable", Teal, onConnectWearable, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
