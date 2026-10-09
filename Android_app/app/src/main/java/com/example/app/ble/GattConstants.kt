package com.example.app.ble

import java.util.UUID

object GattConstants {
    // GETMS Service & Characteristic UUIDs (§5.1)
    val GETMS_SERVICE_UUID: UUID = UUID.fromString("6e400000-0000-4000-8000-00805f9b34fb")
    val EMG_CHAR_UUID: UUID = UUID.fromString("6e400001-0000-4000-8000-00805f9b34fb")
    val IMU_CHAR_UUID: UUID = UUID.fromString("6e400002-0000-4000-8000-00805f9b34fb")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("6e400003-0000-4000-8000-00805f9b34fb")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("6e400004-0000-4000-8000-00805f9b34fb")

    // Standard Client Characteristic Configuration Descriptor
    val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Control Commands (§5.1)
    const val CMD_START: Byte = 0x01
    const val CMD_STOP: Byte = 0x02

    // Requested MTU (§5.2)
    const val REQUESTED_MTU = 247
}
