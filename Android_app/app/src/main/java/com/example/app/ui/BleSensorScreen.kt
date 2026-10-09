package com.example.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.app.ble.BleDevice
import com.example.app.ble.ConnectionState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleSensorScreen(
    viewModel: SensorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Required Bluetooth permissions according to Android version
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    var hasPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { resultMap ->
        hasPermissions = resultMap.values.all { it }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "GETMS Rehabilitation Tracker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "Upper Limb Sensor Unit (ESP32)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Banner
            if (!hasPermissions) {
                PermissionRequestCard(
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
                )
                return@Scaffold
            }

            // Bluetooth Disabled Banner
            if (!uiState.isBluetoothEnabled) {
                BluetoothDisabledCard()
                return@Scaffold
            }

            // Connection Status Bar
            ConnectionStatusBar(
                connectionState = uiState.connectionState,
                deviceName = uiState.selectedDeviceName
            )

            // Main Content Area
            if (uiState.connectionState == ConnectionState.CONNECTED) {
                // Connected Dashboard
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        DeviceControlCard(
                            deviceName = uiState.selectedDeviceName ?: "GETMS Device",
                            deviceAddress = uiState.selectedDeviceAddress ?: "",
                            isStreaming = uiState.isStreaming,
                            fwVersion = uiState.deviceStatus?.fwVersion,
                            onStartStream = { viewModel.startStreaming() },
                            onStopStream = { viewModel.stopStreaming() },
                            onDisconnect = { viewModel.disconnect() }
                        )
                    }

                    item {
                        StreamMetricsCard(metrics = uiState.metrics)
                    }

                    item {
                        EmgDataCard(
                            channelA = uiState.latestEmgChannelA,
                            channelB = uiState.latestEmgChannelB,
                            historyA = uiState.emgTraceHistoryA,
                            historyB = uiState.emgTraceHistoryB
                        )
                    }

                    item {
                        ImuDataCard(
                            pitchDegrees = uiState.latestPitchDegrees,
                            quaternion = uiState.latestQuaternion,
                            angleHistory = uiState.angleTraceHistory
                        )
                    }
                }
            } else {
                // Scanner View
                ScannerCard(
                    isScanning = uiState.isScanning,
                    scannedDevices = uiState.scannedDevices,
                    onStartScan = { viewModel.startScan() },
                    onStopScan = { viewModel.stopScan() },
                    onConnect = { device -> viewModel.connect(device) }
                )
            }
        }
    }
}

@Composable
fun PermissionRequestCard(onRequestPermissions: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bluetooth Permissions Required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "GETMS requires Bluetooth scan and connect permissions to stream sensor data from your rehabilitation device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
fun BluetoothDisabledCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.warningContainer()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Bluetooth is Turned Off",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onWarningContainer()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Please turn on Bluetooth in your system settings to connect to the GETMS sensor unit.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun ConnectionStatusBar(
    connectionState: ConnectionState,
    deviceName: String?
) {
    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFF9800)
        ConnectionState.DISCONNECTING -> Color(0xFFFF5722)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    }

    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "Connected to ${deviceName ?: "GETMS"}"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.DISCONNECTING -> "Disconnecting..."
        ConnectionState.DISCONNECTED -> "Disconnected"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ScannerCard(
    isScanning: Boolean,
    scannedDevices: List<BleDevice>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (BleDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby Sensor Units",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (isScanning) {
                    OutlinedButton(onClick = onStopScan) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Scan")
                    }
                } else {
                    Button(onClick = onStartScan) {
                        Text("Scan Devices")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (scannedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isScanning) "Searching for GETMS devices..." else "Tap 'Scan Devices' to discover sensor unit.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(300.dp)
                ) {
                    items(scannedDevices) { device ->
                        DeviceItemRow(device = device, onConnect = { onConnect(device) })
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItemRow(
    device: BleDevice,
    onConnect: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = device.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "${device.address}  |  RSSI: ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onConnect) {
                Text("Connect")
            }
        }
    }
}

@Composable
fun DeviceControlCard(
    deviceName: String,
    deviceAddress: String,
    isStreaming: Boolean,
    fwVersion: Int?,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "MAC: $deviceAddress" + (if (fwVersion != null) " | FW v$fwVersion" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                OutlinedButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onStartStream,
                    enabled = !isStreaming,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Start Stream")
                }

                Button(
                    onClick = onStopStream,
                    enabled = isStreaming,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) {
                    Text("Stop Stream")
                }
            }
        }
    }
}

@Composable
fun StreamMetricsCard(metrics: com.example.app.ble.StreamMetrics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            MetricStatItem(label = "Throughput", value = String.format(Locale.US, "%.1f pkt/s", metrics.packetsPerSecond))
            MetricStatItem(label = "EMG Packets", value = "${metrics.emgPacketsReceived}")
            MetricStatItem(label = "IMU Packets", value = "${metrics.imuPacketsReceived}")
            MetricStatItem(label = "Packet Loss", value = String.format(Locale.US, "%.2f%%", metrics.packetLossPercentage))
        }
    }
}

@Composable
fun MetricStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmgDataCard(
    channelA: Int,
    channelB: Int,
    historyA: List<Int>,
    historyB: List<Int>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "EMG Sensors (2-Channel)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Channel A
            Text(
                text = "Channel A (Anterior Deltoid / Agonist): $channelA mV",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (channelA.coerceIn(0, 3300) / 3300f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF1E88E5)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Channel B
            Text(
                text = "Channel B (Posterior Deltoid / Antagonist): $channelB mV",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (channelB.coerceIn(0, 3300) / 3300f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFFD81B60)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Dual trace chart
            Text(
                text = "Live EMG Signal Waveform",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            DualTraceCanvas(
                dataA = historyA,
                dataB = historyB,
                colorA = Color(0xFF1E88E5),
                colorB = Color(0xFFD81B60),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
    }
}

@Composable
fun ImuDataCard(
    pitchDegrees: Float,
    quaternion: com.example.app.ble.Quaternion?,
    angleHistory: List<Float>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "IMU Orientation & Shoulder Flexion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Shoulder Flexion Angle",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f°", pitchDegrees),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (quaternion != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Quaternion (qw, qx, qy, qz)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "[%.2f, %.2f, %.2f, %.2f]",
                                quaternion.qw, quaternion.qx, quaternion.qy, quaternion.qz
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Live Joint Angle Trace",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleTraceCanvas(
                data = angleHistory,
                lineColor = MaterialTheme.colorScheme.primary,
                minY = -180f,
                maxY = 180f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
    }
}

@Composable
fun DualTraceCanvas(
    dataA: List<Int>,
    dataB: List<Int>,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        val width = size.width
        val height = size.height

        // Draw Channel A path
        if (dataA.size >= 2) {
            val pathA = Path()
            val stepX = width / (dataA.size - 1).coerceAtLeast(1)
            dataA.forEachIndexed { i, value ->
                val x = i * stepX
                val normY = (value.coerceIn(0, 3300) / 3300f)
                val y = height - (normY * height)
                if (i == 0) pathA.moveTo(x, y) else pathA.lineTo(x, y)
            }
            drawPath(path = pathA, color = colorA, style = Stroke(width = 3f))
        }

        // Draw Channel B path
        if (dataB.size >= 2) {
            val pathB = Path()
            val stepX = width / (dataB.size - 1).coerceAtLeast(1)
            dataB.forEachIndexed { i, value ->
                val x = i * stepX
                val normY = (value.coerceIn(0, 3300) / 3300f)
                val y = height - (normY * height)
                if (i == 0) pathB.moveTo(x, y) else pathB.lineTo(x, y)
            }
            drawPath(path = pathB, color = colorB, style = Stroke(width = 3f))
        }
    }
}

@Composable
fun SingleTraceCanvas(
    data: List<Float>,
    lineColor: Color,
    minY: Float,
    maxY: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        val width = size.width
        val height = size.height

        // Draw zero baseline
        val zeroY = height - (((0f - minY) / (maxY - minY)) * height)
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(0f, zeroY),
            end = Offset(width, zeroY),
            strokeWidth = 1f
        )

        if (data.size >= 2) {
            val path = Path()
            val stepX = width / (data.size - 1).coerceAtLeast(1)
            data.forEachIndexed { i, angle ->
                val x = i * stepX
                val normY = (angle.coerceIn(minY, maxY) - minY) / (maxY - minY)
                val y = height - (normY * height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
        }
    }
}

// Helpers for warning container color styling
@Composable
private fun MaterialTheme.colorSchemeWarningContainer(): Color = Color(0xFFFFF3CD)

@Composable
private fun MaterialTheme.colorSchemeOnWarningContainer(): Color = Color(0xFF856404)

@Composable
private fun androidx.compose.material3.ColorScheme.warningContainer(): Color = Color(0xFFFFF3CD)

@Composable
private fun androidx.compose.material3.ColorScheme.onWarningContainer(): Color = Color(0xFF856404)
