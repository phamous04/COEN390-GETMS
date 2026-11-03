package com.example.app.ui.screens

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.app.ble.BleDevice
import com.example.app.ui.components.PrimaryButton
import com.example.app.ui.components.ScreenTitle
import com.example.app.ui.components.WearableStatusCard
import com.example.app.ui.theme.Orange
import com.example.app.ui.theme.Teal
import com.example.app.ui.theme.TealLight

/** Runtime permissions needed to scan and connect: Android 12+ uses the Bluetooth ones, older versions need location. */
private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
}

@Composable
fun DeviceScanScreen(viewModel: WearableViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    var hasScanned by rememberSaveable { mutableStateOf(false) }
    var connectRequested by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun hasPermissions() = blePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun scan() {
        hasScanned = true
        viewModel.startScan(allDevices = true)
    }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) scan()
    }

    fun scanOrEnable() {
        if (state.status == WearableStatus.BluetoothOff) {
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            scan()
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionDenied = !result.values.all { it }
        if (!permissionDenied) scanOrEnable()
    }

    fun onScanClick() {
        if (hasPermissions()) scanOrEnable() else requestPermissions.launch(blePermissions)
    }

    // Return to the previous screen once the wearable the user picked is validated.
    LaunchedEffect(state.status, connectRequested) {
        if (connectRequested && state.status == WearableStatus.Connected) onBack()
        if (state.status == WearableStatus.Failed || state.status == WearableStatus.Lost) connectRequested = false
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    val shown = if (showAll) state.devices else state.devices.filter { it.isGetms }
    val rebandCount = state.devices.count { it.isGetms }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
                Text("‹ Back", color = Teal, style = MaterialTheme.typography.titleMedium)
            }
            ScreenTitle("Connect Wearable")
            Text(
                "Turn on your ReBand wearable and keep it close to your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { WearableStatusCard(state) }

        if (!viewModel.isBluetoothSupported) {
            item { Notice("This phone does not support Bluetooth Low Energy.") }
        }
        if (permissionDenied) {
            item {
                Notice("Bluetooth permission was denied. ReBand needs it to find and connect to your wearable.") {
                    TextButton(onClick = { requestPermissions.launch(blePermissions) }) {
                        Text("Grant permission", color = Teal)
                    }
                }
            }
        }
        if (state.status == WearableStatus.BluetoothOff) {
            item {
                Notice("Bluetooth is turned off.") {
                    TextButton(onClick = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("Turn on Bluetooth", color = Teal)
                    }
                }
            }
        }

        item {
            if (state.isScanning) {
                OutlinedButton(
                    onClick = viewModel::stopScan,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, Orange),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Orange, strokeWidth = 2.dp)
                    Text("  Stop Scanning", color = Orange, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                PrimaryButton(
                    "Scan for Devices",
                    onClick = ::onScanClick,
                    enabled = viewModel.isBluetoothSupported && state.status != WearableStatus.Connecting,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showAll,
                    onClick = { showAll = false },
                    label = { Text("ReBand Devices ($rebandCount)") },
                    colors = chipColors(),
                )
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = true },
                    label = { Text("All Devices (${state.devices.size})") },
                    colors = chipColors(),
                )
            }
        }

        if (shown.isEmpty() && hasScanned && !state.isScanning && state.status != WearableStatus.BluetoothOff) {
            item {
                Text(
                    if (showAll) "No Bluetooth devices found nearby." else
                        "No ReBand wearable found. Make sure it is powered on and nearby, then scan again." +
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " Location must also be turned on." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(shown, key = { it.address }) { device ->
            DeviceRow(
                device = device,
                connecting = state.status == WearableStatus.Connecting,
                onConnect = {
                    connectRequested = true
                    viewModel.connect(device)
                },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, connecting: Boolean, onConnect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isGetms) TealLight else MaterialTheme.colorScheme.surface,
        ),
        border = if (device.isGetms) BorderStroke(1.5.dp, Teal) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    if (device.isGetms) {
                        Text(
                            "ReBand",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Teal)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    "Signal: ${device.rssi} dBm (${signalLabel(device.rssi)}) · ${device.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (device.isGetms) {
                Button(onClick = onConnect, enabled = !connecting, shape = RoundedCornerShape(12.dp)) { Text("Connect") }
            } else {
                Text("Not compatible", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Notice(message: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f)).padding(14.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        action()
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = TealLight,
    selectedLabelColor = Teal,
)

private fun signalLabel(rssi: Int) = when {
    rssi >= -65 -> "Strong"
    rssi >= -80 -> "Fair"
    else -> "Weak, move closer"
}
