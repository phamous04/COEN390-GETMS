package com.example.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    // Flow State
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _emgPackets = MutableSharedFlow<EmgPacket>(extraBufferCapacity = 64)
    val emgPackets: SharedFlow<EmgPacket> = _emgPackets.asSharedFlow()

    private val _imuPackets = MutableSharedFlow<ImuPacket>(extraBufferCapacity = 64)
    val imuPackets: SharedFlow<ImuPacket> = _imuPackets.asSharedFlow()

    private val _streamMetrics = MutableStateFlow(StreamMetrics())
    val streamMetrics: StateFlow<StreamMetrics> = _streamMetrics.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null

    // Sequence tracking for gap/loss calculation
    private var lastEmgSeq: Int = -1
    private var emgPacketsCount: Long = 0
    private var emgGapsCount: Long = 0

    private var lastImuSeq: Int = -1
    private var imuPacketsCount: Long = 0
    private var imuGapsCount: Long = 0

    private var startTimeMs: Long = 0

    val isBluetoothSupported: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    // --- Scanning ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown GETMS"
            val address = device.address ?: return
            val rssi = result.rssi

            // Keep scanned list unique by MAC address
            val current = _scannedDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }
            val updated = BleDevice(name = name, address = address, rssi = rssi)

            if (existingIndex >= 0) {
                current[existingIndex] = updated
            } else {
                current.add(updated)
            }
            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || !isBluetoothEnabled) {
            Log.w(TAG, "Cannot start scan: Bluetooth disabled or scanner unavailable")
            return
        }

        _scannedDevices.value = emptyList()
        _isScanning.value = true

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(GattConstants.GETMS_SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            // Fallback scan without filters if service UUID filter fails
            scanner.startScan(null, settings, scanCallback)
        }
    }

    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && _isScanning.value) {
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE scan", e)
            }
        }
        _isScanning.value = false
    }

    // --- Connection & GATT ---

    fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.e(TAG, "Device not found for address: $address")
            return
        }

        stopScan()
        disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        resetMetrics()

        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        controlCharacteristic = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // --- Streaming Control Commands ---

    fun startStreaming() {
        writeControlCommand(GattConstants.CMD_START)
    }

    fun stopStreaming() {
        writeControlCommand(GattConstants.CMD_STOP)
    }

    private fun writeControlCommand(command: Byte) {
        val gatt = bluetoothGatt ?: return
        val char = controlCharacteristic ?: return

        val payload = byteArrayOf(command)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    private fun resetMetrics() {
        lastEmgSeq = -1
        emgPacketsCount = 0
        emgGapsCount = 0

        lastImuSeq = -1
        imuPacketsCount = 0
        imuGapsCount = 0

        startTimeMs = System.currentTimeMillis()
        _streamMetrics.value = StreamMetrics()
    }

    // --- GATT Callbacks ---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to ${gatt.device.address}")
                _connectionState.value = ConnectionState.CONNECTED

                // Request high priority & MTU 247 (§5.2)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(GattConstants.REQUESTED_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected from ${gatt.device.address}")
                _connectionState.value = ConnectionState.DISCONNECTED
                controlCharacteristic = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status=$status")
            // Discover services after MTU configuration
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                return
            }

            val service = gatt.getService(GattConstants.GETMS_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "GETMS Service not found on device")
                return
            }

            Log.i(TAG, "GETMS Service discovered!")
            controlCharacteristic = service.getCharacteristic(GattConstants.CONTROL_CHAR_UUID)

            // Enable Notifications on EMG, IMU, and Status characteristics
            enableNotification(gatt, service, GattConstants.EMG_CHAR_UUID)
            enableNotification(gatt, service, GattConstants.IMU_CHAR_UUID)
            enableNotification(gatt, service, GattConstants.STATUS_CHAR_UUID)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleCharacteristicData(characteristic.uuid, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(characteristic.uuid, value)
        }
    }

    private fun enableNotification(
        gatt: BluetoothGatt,
        service: BluetoothGattService,
        charUuid: UUID
    ) {
        val characteristic = service.getCharacteristic(charUuid) ?: return
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(GattConstants.CCCD_DESCRIPTOR_UUID) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            GattConstants.EMG_CHAR_UUID -> {
                val packet = PacketParser.parseEmgPacket(data) ?: return
                updateEmgMetrics(packet.sequence)
                scope.launch { _emgPackets.emit(packet) }
            }
            GattConstants.IMU_CHAR_UUID -> {
                val packet = PacketParser.parseImuPacket(data) ?: return
                updateImuMetrics(packet.sequence)
                scope.launch { _imuPackets.emit(packet) }
            }
            GattConstants.STATUS_CHAR_UUID -> {
                val status = PacketParser.parseStatusPacket(data) ?: return
                _deviceStatus.value = status
            }
        }
    }

    private fun updateEmgMetrics(seq: Int) {
        emgPacketsCount++
        if (lastEmgSeq >= 0) {
            val expected = (lastEmgSeq + 1) and 0xFFFF
            if (seq != expected) {
                val gap = (seq - expected) and 0xFFFF
                emgGapsCount += gap
            }
        }
        lastEmgSeq = seq
        publishMetrics()
    }

    private fun updateImuMetrics(seq: Int) {
        imuPacketsCount++
        if (lastImuSeq >= 0) {
            val expected = (lastImuSeq + 1) and 0xFFFF
            if (seq != expected) {
                val gap = (seq - expected) and 0xFFFF
                imuGapsCount += gap
            }
        }
        lastImuSeq = seq
        publishMetrics()
    }

    private fun publishMetrics() {
        val totalPackets = emgPacketsCount + imuPacketsCount
        val totalGaps = emgGapsCount + imuGapsCount
        val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000f).coerceAtLeast(0.1f)

        val pps = totalPackets / elapsedSec
        val lossPct = if (totalPackets + totalGaps > 0) {
            (totalGaps.toFloat() / (totalPackets + totalGaps).toFloat()) * 100f
        } else 0f

        _streamMetrics.value = StreamMetrics(
            emgPacketsReceived = emgPacketsCount,
            imuPacketsReceived = imuPacketsCount,
            emgSequenceGaps = emgGapsCount,
            imuSequenceGaps = imuGapsCount,
            packetsPerSecond = pps,
            packetLossPercentage = lossPct
        )
    }
}
