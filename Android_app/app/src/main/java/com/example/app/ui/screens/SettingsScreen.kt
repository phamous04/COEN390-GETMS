package com.example.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.app.ui.components.IconBadge
import com.example.app.ui.components.ReBandCard
import com.example.app.ui.components.ReBandIcons
import com.example.app.ui.components.ScreenTitle
import com.example.app.ui.components.WearableStatusCard
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.Teal

@Composable
fun SettingsScreen(
    email: String?,
    busy: Boolean,
    error: String?,
    wearable: WearableUiState,
    onConnectWearable: () -> Unit,
    onDisconnectWearable: () -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings")

        ReBandCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
                    Text(
                        email?.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Signed in as", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(email ?: "Unknown account", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Text("Bluetooth", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WearableStatusCard(wearable) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (wearable.status) {
                    WearableStatus.Connected -> {
                        SmallActionButton("Disconnect", MaterialTheme.colorScheme.error, Modifier.weight(1f), onDisconnectWearable)
                        SmallActionButton("Rescan", Teal, Modifier.weight(1f), onConnectWearable)
                    }
                    WearableStatus.Connecting -> SmallActionButton("Cancel", MaterialTheme.colorScheme.error, Modifier.weight(1f), onDisconnectWearable)
                    else -> SmallActionButton("Connect Wearable", Teal, Modifier.weight(1f), onConnectWearable)
                }
            }
        }

        ReBandCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ReBandIcons.Settings)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Device settings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Calibration and wearable options",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Coming soon", style = MaterialTheme.typography.labelMedium, color = Orange)
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        OutlinedButton(
            onClick = { confirmLogout = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("Log out", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You will need to log in again to use ReBand.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel", color = Teal) } },
        )
    }
}

@Composable
private fun SmallActionButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, color),
    ) { Text(text, color = color, style = MaterialTheme.typography.titleMedium) }
}
