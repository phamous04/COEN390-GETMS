package com.example.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.ble.BleDevice
import com.example.app.ble.BleManager
import com.example.app.ble.ConnectionState
import com.example.app.ble.DeviceStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class WearableStatus { BluetoothOff, NotConnected, Scanning, Connecting, Connected, Failed, Lost }

data class WearableUiState(
    val status: WearableStatus = WearableStatus.NotConnected,
    val deviceName: String? = null,
    val error: String? = null,
    val devices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val deviceStatus: DeviceStatus? = null,
) {
    /** Start Exercise needs a connected wearable whose GETMS service has been validated. */
    val canStartExercise: Boolean get() = status == WearableStatus.Connected
}

/** UI view of the shared [BleManager]; every instance reflects the same single connection. */
class WearableViewModel(app: Application) : AndroidViewModel(app) {
    private val ble = BleManager.get(app)

    private val connection = combine(
        ble.connectionState, ble.servicesReady, ble.connectionLost, ble.bluetoothEnabled, ble.isScanning,
    ) { state, ready, lost, enabled, scanning ->
        Connection(state, ready, lost, enabled, scanning)
    }

    val uiState: StateFlow<WearableUiState> = combine(
        connection, ble.connectedDeviceName, ble.lastError, ble.scannedDevices, ble.deviceStatus,
    ) { c, name, error, devices, deviceStatus ->
        val status = when {
            !c.bluetoothEnabled -> WearableStatus.BluetoothOff
            c.state == ConnectionState.CONNECTED && c.servicesReady -> WearableStatus.Connected
            // GATT link is up but the ReBand service is still being validated
            c.state == ConnectionState.CONNECTING || c.state == ConnectionState.CONNECTED -> WearableStatus.Connecting
            c.scanning -> WearableStatus.Scanning
            c.lost -> WearableStatus.Lost
            error != null -> WearableStatus.Failed
            else -> WearableStatus.NotConnected
        }
        WearableUiState(
            status = status,
            deviceName = name,
            error = error,
            devices = devices,
            isScanning = c.scanning,
            deviceStatus = if (status == WearableStatus.Connected) deviceStatus else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearableUiState())

    val isBluetoothSupported get() = ble.isBluetoothSupported

    fun startScan(allDevices: Boolean) = ble.startScan(allDevices)

    fun stopScan() = ble.stopScan()

    fun connect(device: BleDevice) {
        if (device.isGetms) ble.connect(device.address, device.name)
    }

    fun disconnect() = ble.disconnect()

    override fun onCleared() {
        // Scanning belongs to the screen that started it; the connection stays app-wide.
        ble.stopScan()
    }

    private data class Connection(
        val state: ConnectionState,
        val servicesReady: Boolean,
        val lost: Boolean,
        val bluetoothEnabled: Boolean,
        val scanning: Boolean,
    )
}
