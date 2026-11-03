package com.example.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.ble.BleDevice
import com.example.app.ble.BleManager
import com.example.app.ble.ConnectionState
import com.example.app.ble.DeviceStatus
import com.example.app.ble.Quaternion
import com.example.app.ble.StreamMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SensorUiState(
    val scannedDevices: List<BleDevice> = emptyList(),
    val isScanning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val selectedDeviceAddress: String? = null,
    val selectedDeviceName: String? = null,
    val deviceStatus: DeviceStatus? = null,
    val isStreaming: Boolean = false,
    val isDeviceReady: Boolean = false,
    val latestEmgChannelA: Int = 0,
    val latestPitchDegrees: Float = 0f,
    val latestQuaternion: Quaternion? = null,
    val metrics: StreamMetrics = StreamMetrics(),
    val angleTraceHistory: List<Float> = emptyList(),
    val emgTraceHistoryA: List<Int> = emptyList(),
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = true
)

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    // Shared with the Home and Settings screens so there is only ever one connection.
    private val bleManager = BleManager.get(application)

    private val _selectedDeviceAddress = MutableStateFlow<String?>(null)
    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    private val _isStreaming = MutableStateFlow(false)

    private val _latestEmgA = MutableStateFlow(0)
    private val _latestPitch = MutableStateFlow(0f)
    private val _latestQuat = MutableStateFlow<Quaternion?>(null)

    private val _angleHistory = MutableStateFlow<List<Float>>(emptyList())
    private val _emgHistoryA = MutableStateFlow<List<Int>>(emptyList())

    val uiState: StateFlow<SensorUiState> = combine(
        bleManager.scannedDevices,
        bleManager.isScanning,
        bleManager.connectionState,
        _selectedDeviceAddress,
        _selectedDeviceName,
        bleManager.deviceStatus,
        _isStreaming,
        _latestEmgA,
        _latestPitch,
        _latestQuat,
        bleManager.streamMetrics,
        _angleHistory,
        _emgHistoryA,
        bleManager.bluetoothEnabled,
        bleManager.servicesReady
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        SensorUiState(
            scannedDevices = args[0] as List<BleDevice>,
            isScanning = args[1] as Boolean,
            connectionState = args[2] as ConnectionState,
            selectedDeviceAddress = args[3] as String?,
            selectedDeviceName = args[4] as String?,
            deviceStatus = args[5] as DeviceStatus?,
            isStreaming = args[6] as Boolean,
            latestEmgChannelA = args[7] as Int,
            latestPitchDegrees = args[8] as Float,
            latestQuaternion = args[9] as Quaternion?,
            metrics = args[10] as StreamMetrics,
            angleTraceHistory = args[11] as List<Float>,
            emgTraceHistoryA = args[12] as List<Int>,
            isBluetoothSupported = bleManager.isBluetoothSupported,
            isBluetoothEnabled = args[13] as Boolean,
            isDeviceReady = args[14] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SensorUiState(
            isBluetoothSupported = bleManager.isBluetoothSupported,
            isBluetoothEnabled = bleManager.isBluetoothEnabled
        )
    )

    init {
        // A device can drop the link on its own; streaming cannot continue without a connection
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.DISCONNECTED) _isStreaming.value = false
            }
        }

        // Collect EMG packets and update latest live values & history
        viewModelScope.launch {
            bleManager.emgPackets.collect { packet ->
                if (packet.samples.isNotEmpty()) {
                    val lastSample = packet.samples.last()
                    _latestEmgA.value = lastSample.channelA

                    // Append to rolling history (max 50 points)
                    val historyA = _emgHistoryA.value.toMutableList()
                    packet.samples.forEach { sample -> historyA.add(sample.channelA) }
                    while (historyA.size > 50) historyA.removeAt(0)
                    _emgHistoryA.value = historyA
                }
            }
        }

        // Collect IMU packets and update latest angle & history
        viewModelScope.launch {
            bleManager.imuPackets.collect { packet ->
                if (packet.samples.isNotEmpty()) {
                    val lastSample = packet.samples.last()
                    val pitch = lastSample.quaternion.toPitchDegrees()
                    _latestQuat.value = lastSample.quaternion
                    _latestPitch.value = pitch

                    val angleHist = _angleHistory.value.toMutableList()
                    packet.samples.forEach { sample ->
                        angleHist.add(sample.quaternion.toPitchDegrees())
                    }
                    while (angleHist.size > 50) angleHist.removeAt(0)
                    _angleHistory.value = angleHist
                }
            }
        }
    }

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connect(device: BleDevice) {
        _selectedDeviceAddress.value = device.address
        _selectedDeviceName.value = device.name
        bleManager.connect(device.address, device.name)
    }

    fun disconnect() {
        bleManager.disconnect()
        _isStreaming.value = false
        _selectedDeviceAddress.value = null
        _selectedDeviceName.value = null
        _angleHistory.value = emptyList()
        _emgHistoryA.value = emptyList()
    }

    fun startStreaming() {
        if (bleManager.startStreaming()) _isStreaming.value = true
    }

    fun stopStreaming() {
        if (bleManager.stopStreaming()) _isStreaming.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // Keep the shared connection open for the rest of the app; only stop the data stream.
        if (_isStreaming.value) bleManager.stopStreaming()
    }
}
