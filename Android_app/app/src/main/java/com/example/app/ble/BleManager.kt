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
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SCAN_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: BleManager? = null

        /** One app-wide manager, so every screen shares the same scan and connection. */
        fun get(context: Context): BleManager = instance ?: synchronized(this) {
            instance ?: BleManager(context.applicationContext).also { instance = it }
        }
    }

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

    // tryEmit from the (serial) GATT callback keeps packet order; DROP_OLDEST never blocks the binder thread
    private val _emgPackets = MutableSharedFlow<EmgPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val emgPackets: SharedFlow<EmgPacket> = _emgPackets.asSharedFlow()

    private val _imuPackets = MutableSharedFlow<ImuPacket>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val imuPackets: SharedFlow<ImuPacket> = _imuPackets.asSharedFlow()

    private val _streamMetrics = MutableStateFlow(StreamMetrics())
    val streamMetrics: StateFlow<StreamMetrics> = _streamMetrics.asStateFlow()

    /** True once the GETMS service is discovered and notifications are being enabled. */
    private val _servicesReady = MutableStateFlow(false)
    val servicesReady: StateFlow<Boolean> = _servicesReady.asStateFlow()

    /** Name of the device being connected or connected to; null when idle. */
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    /** Last scan or connection problem in plain words; cleared when a new scan or connection starts. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** True after a validated connection dropped without the user asking. */
    private val _connectionLost = MutableStateFlow(false)
    val connectionLost: StateFlow<Boolean> = _connectionLost.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { stopScan() }

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var controlCharacteristic: BluetoothGattCharacteristic? = null

    // Android allows only one outstanding GATT operation per connection, so writes are queued.
    private val gattLock = Any()
    private val gattOps = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var gattOpInFlight = false

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

    private val _bluetoothEnabled = MutableStateFlow(isBluetoothEnabled)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            _bluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                stopScan()
                disconnect()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Releases the connection and receiver. The manager must not be reused afterwards. */
    fun close() {
        stopScan()
        disconnect()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bluetooth state receiver already unregistered", e)
        }
    }

    // --- Scanning ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val rssi = result.rssi
            // The firmware puts the GETMS service UUID in its advertising packet (getms_ble_spec.h).
            val advertisesGetms = result.scanRecord?.serviceUuids
                ?.contains(ParcelUuid(GattConstants.GETMS_SERVICE_UUID)) == true

            // Keep scanned list unique by MAC address
            val current = _scannedDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }
            val existing = current.getOrNull(existingIndex)
            val name = device.name ?: result.scanRecord?.deviceName ?: existing?.name ?: "Unknown Device"
            val updated = BleDevice(
                name = name,
                address = address,
                rssi = rssi,
                isGetms = advertisesGetms || existing?.isGetms == true
            )

            if (existingIndex >= 0) {
                current[existingIndex] = updated
            } else {
                current.add(updated)
            }
            _scannedDevices.value = current
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
            mainHandler.removeCallbacks(scanTimeout)
            _isScanning.value = false
            _lastError.value = "Scan failed (error $errorCode). Please try again."
        }
    }

    /**
     * Scans for [SCAN_TIMEOUT_MS]. By default only devices advertising the GETMS service are
     * reported; [allDevices] reports every nearby BLE device (marked with [BleDevice.isGetms]).
     */
    fun startScan(allDevices: Boolean = false) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || !isBluetoothEnabled) {
            Log.w(TAG, "Cannot start scan: Bluetooth disabled or scanner unavailable")
            _lastError.value = "Bluetooth is off. Turn it on to scan for your wearable."
            return
        }
        if (_isScanning.value) stopScan()

        _lastError.value = null
        _scannedDevices.value = emptyList()
        _isScanning.value = true
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)

        val filters = if (allDevices) null else listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(GattConstants.GETMS_SERVICE_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth scan permission", e)
            mainHandler.removeCallbacks(scanTimeout)
            _isScanning.value = false
            _lastError.value = "Bluetooth permission is required to scan for devices."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            // Fallback scan without filters if service UUID filter fails
            scanner.startScan(null, settings, scanCallback)
        }
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
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

    fun connect(address: String, name: String? = null) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.e(TAG, "Device not found for address: $address")
            _lastError.value = "Device not found. Scan again and pick your wearable."
            return
        }
        // Ignore repeated taps while the same device is already connecting
        if (_connectionState.value == ConnectionState.CONNECTING && bluetoothGatt?.device?.address == address) return

        stopScan()
        disconnect()

        _lastError.value = null
        _connectedDeviceName.value = name ?: device.name
        _connectionState.value = ConnectionState.CONNECTING
        resetMetrics()

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (bluetoothGatt == null) fail("Couldn't start the connection. Please try again.")
    }

    fun disconnect() {
        _connectionLost.value = false
        _connectionState.value = ConnectionState.DISCONNECTING
        clearGattOps()
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        controlCharacteristic = null
        _servicesReady.value = false
        _connectedDeviceName.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Drops the connection and records why, so the UI can show it and offer a retry. */
    private fun fail(message: String) {
        disconnect()
        _lastError.value = message
    }

    // --- Streaming Control Commands ---

    /** Returns true if the command was queued; false if the device is not ready yet. */
    fun startStreaming(): Boolean = writeControlCommand(GattConstants.CMD_START)

    fun stopStreaming(): Boolean = writeControlCommand(GattConstants.CMD_STOP)

    private fun writeControlCommand(command: Byte): Boolean {
        val char = controlCharacteristic ?: return false
        val payload = byteArrayOf(command)

        enqueueGattOp { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
        return true
    }

    private fun enqueueGattOp(op: (BluetoothGatt) -> Boolean) {
        synchronized(gattLock) {
            gattOps.addLast(op)
            if (!gattOpInFlight) runNextGattOpLocked()
        }
    }

    private fun onGattOpComplete() {
        synchronized(gattLock) {
            gattOpInFlight = false
            runNextGattOpLocked()
        }
    }

    private fun runNextGattOpLocked() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            gattOps.clear()
            return
        }
        while (gattOps.isNotEmpty()) {
            if (gattOps.removeFirst()(gatt)) {
                gattOpInFlight = true
                return
            }
            Log.w(TAG, "GATT operation could not be started; skipping")
        }
    }

    private fun clearGattOps() {
        synchronized(gattLock) {
            gattOps.clear()
            gattOpInFlight = false
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
            if (gatt != bluetoothGatt) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to ${gatt.device.address}")
                _connectionState.value = ConnectionState.CONNECTED

                // Request high priority & MTU 247 (§5.2)
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.requestMtu(GattConstants.REQUESTED_MTU)) {
                    Log.w(TAG, "MTU request could not be started; discovering services directly")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected from ${gatt.device.address}, status=$status")
                // A manual disconnect() clears bluetoothGatt first, so reaching here was not requested.
                _connectionLost.value = _servicesReady.value
                _lastError.value = if (_servicesReady.value) {
                    "The wearable disconnected unexpectedly. Make sure it is on and nearby."
                } else {
                    "Couldn't connect to the wearable. Make sure it is on and nearby."
                }
                clearGattOps()
                gatt.close()
                bluetoothGatt = null
                controlCharacteristic = null
                _servicesReady.value = false
                _connectedDeviceName.value = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status=$status")
            // Discover services after MTU configuration
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                fail("Couldn't read the wearable's services. Please try again.")
                return
            }

            val service = gatt.getService(GattConstants.GETMS_SERVICE_UUID)
            val control = service?.getCharacteristic(GattConstants.CONTROL_CHAR_UUID)
            val statusChar = service?.getCharacteristic(GattConstants.STATUS_CHAR_UUID)
            val emg = service?.getCharacteristic(GattConstants.EMG_CHAR_UUID)
            if (service == null || control == null || statusChar == null || emg == null) {
                Log.e(TAG, "GETMS Service or a required characteristic not found on device")
                fail("This device is not a compatible ReBand wearable.")
                return
            }

            Log.i(TAG, "GETMS Service discovered!")
            controlCharacteristic = control

            // Enable Notifications on EMG, IMU, and Status characteristics
            enableNotification(service, GattConstants.EMG_CHAR_UUID)
            enableNotification(service, GattConstants.IMU_CHAR_UUID)
            enableNotification(service, GattConstants.STATUS_CHAR_UUID)
            // Queued after the notification setup, so ready means notifications are configured
            // and the device has answered with its status (the GET_STATE equivalent).
            enqueueGattOp { it.readCharacteristic(statusChar) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(characteristic.uuid, value, status)
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

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed for ${descriptor.characteristic.uuid}, status=$status")
            }
            onGattOpComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed for ${characteristic.uuid}, status=$status")
            }
            onGattOpComplete()
        }
    }

    private fun handleCharacteristicRead(uuid: UUID, value: ByteArray, status: Int) {
        if (uuid == GattConstants.STATUS_CHAR_UUID) {
            val parsed = if (status == BluetoothGatt.GATT_SUCCESS) PacketParser.parseStatusPacket(value) else null
            if (parsed == null) {
                Log.e(TAG, "Status read failed, status=$status")
                fail("The wearable did not report its status. Please try again.")
                return
            }
            _deviceStatus.value = parsed
            _servicesReady.value = true
        }
        onGattOpComplete()
    }

    private fun enableNotification(
        service: BluetoothGattService,
        charUuid: UUID
    ) {
        val characteristic = service.getCharacteristic(charUuid) ?: return
        val descriptor = characteristic.getDescriptor(GattConstants.CCCD_DESCRIPTOR_UUID) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        enqueueGattOp { gatt ->
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun handleCharacteristicData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            GattConstants.EMG_CHAR_UUID -> {
                val packet = PacketParser.parseEmgPacket(data) ?: return
                updateEmgMetrics(packet.sequence)
                _emgPackets.tryEmit(packet)
            }
            GattConstants.IMU_CHAR_UUID -> {
                val packet = PacketParser.parseImuPacket(data) ?: return
                updateImuMetrics(packet.sequence)
                _imuPackets.tryEmit(packet)
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
