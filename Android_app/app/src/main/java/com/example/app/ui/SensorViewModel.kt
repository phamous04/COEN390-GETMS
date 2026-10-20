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
    val latestEmgChannelA: Int = 0,
    val latestEmgChannelB: Int = 0,
    val latestPitchDegrees: Float = 0f,
    val latestQuaternion: Quaternion? = null,
    val metrics: StreamMetrics = StreamMetrics(),
    val angleTraceHistory: List<Float> = emptyList(),
    val emgTraceHistoryA: List<Int> = emptyList(),
    val emgTraceHistoryB: List<Int> = emptyList(),
    val isBluetoothSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = true
)

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)

    private val _selectedDeviceAddress = MutableStateFlow<String?>(null)
    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    private val _isStreaming = MutableStateFlow(false)

    private val _latestEmgA = MutableStateFlow(0)
    private val _latestEmgB = MutableStateFlow(0)
    private val _latestPitch = MutableStateFlow(0f)
    private val _latestQuat = MutableStateFlow<Quaternion?>(null)

    private val _angleHistory = MutableStateFlow<List<Float>>(emptyList())
    private val _emgHistoryA = MutableStateFlow<List<Int>>(emptyList())
    private val _emgHistoryB = MutableStateFlow<List<Int>>(emptyList())

    val uiState: StateFlow<SensorUiState> = combine(
        bleManager.scannedDevices,
        bleManager.isScanning,
        bleManager.connectionState,
        _selectedDeviceAddress,
        _selectedDeviceName,
        bleManager.deviceStatus,
        _isStreaming,
        _latestEmgA,
        _latestEmgB,
        _latestPitch,
        _latestQuat,
        bleManager.streamMetrics,
        _angleHistory,
        _emgHistoryA,
        _emgHistoryB
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
            latestEmgChannelB = args[8] as Int,
            latestPitchDegrees = args[9] as Float,
            latestQuaternion = args[10] as Quaternion?,
            metrics = args[11] as StreamMetrics,
            angleTraceHistory = args[12] as List<Float>,
            emgTraceHistoryA = args[13] as List<Int>,
            emgTraceHistoryB = args[14] as List<Int>,
            isBluetoothSupported = bleManager.isBluetoothSupported,
            isBluetoothEnabled = bleManager.isBluetoothEnabled
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
        // Collect EMG packets and update latest live values & history
        viewModelScope.launch {
            bleManager.emgPackets.collect { packet ->
                if (packet.samples.isNotEmpty()) {
                    val lastSample = packet.samples.last()
                    _latestEmgA.value = lastSample.channelA
                    _latestEmgB.value = lastSample.channelB

                    // Append to rolling history (max 50 points)
                    val historyA = _emgHistoryA.value.toMutableList()
                    val historyB = _emgHistoryB.value.toMutableList()
                    packet.samples.forEach { sample ->
                        historyA.add(sample.channelA)
                        historyB.add(sample.channelB)
                    }
                    while (historyA.size > 50) historyA.removeAt(0)
                    while (historyB.size > 50) historyB.removeAt(0)
                    _emgHistoryA.value = historyA
                    _emgHistoryB.value = historyB
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
        bleManager.connect(device.address)
    }

    fun disconnect() {
        bleManager.disconnect()
        _isStreaming.value = false
        _selectedDeviceAddress.value = null
        _selectedDeviceName.value = null
        _angleHistory.value = emptyList()
        _emgHistoryA.value = emptyList()
        _emgHistoryB.value = emptyList()
    }

    fun startStreaming() {
        bleManager.startStreaming()
        _isStreaming.value = true
    }

    fun stopStreaming() {
        bleManager.stopStreaming()
        _isStreaming.value = false
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
