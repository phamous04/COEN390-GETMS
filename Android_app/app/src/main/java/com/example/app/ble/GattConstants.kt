package com.example.app.ble

import java.util.UUID

/** Mirrors firmware/Reband/ble/getms_ble_spec.h and BLE_PROTOCOL.md (firmware v0.2). */
object GattConstants {
    val GETMS_SERVICE_UUID: UUID = UUID.fromString("6e400000-0000-4000-8000-00805f9b34fb")
    val EMG_CHAR_UUID: UUID = UUID.fromString("6e400001-0000-4000-8000-00805f9b34fb")
    /** Reserved by the firmware, not exposed yet. */
    val IMU_CHAR_UUID: UUID = UUID.fromString("6e400002-0000-4000-8000-00805f9b34fb")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("6e400003-0000-4000-8000-00805f9b34fb")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("6e400004-0000-4000-8000-00805f9b34fb")
    val REP_CHAR_UUID: UUID = UUID.fromString("6e400005-0000-4000-8000-00805f9b34fb")

    // Standard Client Characteristic Configuration Descriptor
    val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Control opcodes (GETMS_CMD_*)
    const val CMD_CALIBRATE: Byte = 0x01 // + step u8
    const val CMD_START: Byte = 0x02
    const val CMD_STOP: Byte = 0x03
    const val CMD_GET_STATE: Byte = 0x04
    const val CMD_GET_REPS: Byte = 0x05 // optional from_index u16

    // Calibration steps (GETMS_CAL_STEP_*)
    const val CAL_STEP_RELAX = 1
    const val CAL_STEP_CURL = 2
    const val CAL_STEP_MVC = 3

    // Requested MTU; the firmware refuses START below EMG packet length + 3 (89).
    const val REQUESTED_MTU = 247
}
