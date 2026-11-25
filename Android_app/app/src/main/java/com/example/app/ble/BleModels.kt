package com.example.app.ble

import kotlin.math.abs
import kotlin.math.asin

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** True when the device advertises the GETMS service UUID, i.e. it is a ReBand wearable. */
    val isGetms: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class EmgSample(
    /** MyoWare 2.0 ENV output in millivolts (0-3300). */
    val channelA: Int,
    val timestampUs: Long
)

data class EmgPacket(
    val sequence: Int,
    val t0Us: Long,
    val samples: List<EmgSample>
)

data class Quaternion(
    val qw: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float
) {
    /**
     * Calculates joint pitch angle in degrees (sagittal plane shoulder flexion).
     */
    fun toPitchDegrees(): Float {
        val sinp = 2.0f * (qw * qy - qz * qx)
        val pitchRad = if (abs(sinp) >= 1.0f) {
            Math.copySign(Math.PI / 2.0, sinp.toDouble()).toFloat()
        } else {
            asin(sinp)
        }
        return Math.toDegrees(pitchRad.toDouble()).toFloat()
    }
}

data class ImuSample(
    val quaternion: Quaternion,
    val timestampUs: Long
)

data class ImuPacket(
    val sequence: Int,
    val t0Us: Long,
    val samples: List<ImuSample>
)

/** Firmware session state (GETMS_STATE_*). */
enum class DeviceState(val code: Int) {
    IDLE(0), CALIBRATING(1), READY(2), RECORDING(3), STOPPED(4), ERROR(5), UNKNOWN(-1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Status characteristic (18 bytes in firmware v0.2, see BLE_PROTOCOL.md). Fields after
 * [fwVersion] are zero if the device sent the shorter v0.1 status.
 */
data class DeviceStatus(
    val state: Byte,
    val errorFlags: Byte,
    val fwVersion: Int,
    /** GETMS_ERROR_*: why the device is in ERROR, 0 otherwise. */
    val errorCode: Int = 0,
    /** Calibration step being captured right now, 0 = none. */
    val calActive: Int = 0,
    /** Last calibration step completed, 0..3. */
    val calDone: Int = 0,
    /** Exercise ends by itself after this many reps; 0 = unlimited. */
    val targetReps: Int = 0,
    val romRefX10: Int = 0,
    val mvcMv: Int = 0,
    val baselineMv: Int = 0,
    val repCount: Int = 0,
    val cleanCount: Int = 0,
    /** False for a short (pre-v0.2) status that has no calibration or rep fields. */
    val extended: Boolean = false,
) {
    val deviceState: DeviceState get() = DeviceState.of(state.toInt() and 0xFF)
    val flags: Int get() = errorFlags.toInt() and 0xFF
}

/** One counted repetition, from the Rep Event characteristic (19 bytes, type_version 0x11). */
data class RepEvent(
    val repIndex: Int,
    val startMs: Long,
    val durationMs: Int,
    val romX10: Int,
    val peakActivationX10: Int,
    val meanActivationX10: Int,
    val twistDeg: Int,
    val swingDeg: Int,
    val planeDeg: Int,
    val flags: Int,
) {
    val isClean get() = flags and RepFlags.CLEAN != 0
}

/** GETMS_REP_F_* bits. */
object RepFlags {
    const val CLEAN = 1 shl 0
    const val LOW_CONFIDENCE = 1 shl 1
    const val MISSING_SAMPLES = 1 shl 2
    const val SATURATED = 1 shl 3
    const val NOT_CLEAN_TWIST = 1 shl 4
    const val NOT_CLEAN_SWING = 1 shl 5
    const val NOT_CLEAN_PLANE = 1 shl 6
    const val REPLAYED = 1 shl 7

    /** Flags that describe the rep itself (REPLAYED only says how it reached the phone). */
    const val PERSISTED_MASK = 0x7F
}

/** GETMS_ERR_* bits of [DeviceStatus.errorFlags]. */
object DeviceErrorFlags {
    const val ADC_UNCALIBRATED = 1 shl 0
    const val MTU_TOO_SMALL = 1 shl 1
    const val IMU1_MISSING = 1 shl 2
    const val IMU2_MISSING = 1 shl 3
}

/** Plain-language text for GETMS_ERROR_* codes. */
object DeviceErrors {
    fun message(code: Int): String = when (code) {
        0 -> "No error."
        1 -> "The wearable rejected a command."
        2 -> "The wearable was not ready for that step."
        3 -> "Upper-arm motion sensor not found. Check the sensor cable."
        4 -> "Forearm motion sensor not found. Check the sensor cable."
        5 -> "A motion sensor stopped responding. Calibrate again."
        6 -> "Your arm moved during the hold. Keep it still and try again."
        7 -> "The curl was too small. Curl your arm fully and hold it."
        8 -> "Muscle signal was too low. Squeeze as hard as you can."
        9 -> "Bluetooth link too slow for recording (MTU too small). Reconnect the wearable."
        else -> "Wearable error $code."
    }
}

data class StreamMetrics(
    val emgPacketsReceived: Long = 0,
    val imuPacketsReceived: Long = 0,
    val emgSequenceGaps: Long = 0,
    val imuSequenceGaps: Long = 0,
    val packetsPerSecond: Float = 0f,
    val packetLossPercentage: Float = 0f
)
