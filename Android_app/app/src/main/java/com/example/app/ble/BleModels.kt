package com.example.app.ble

import kotlin.math.abs
import kotlin.math.asin

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class EmgSample(
    val channelA: Int,
    val channelB: Int,
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

data class DeviceStatus(
    val state: Byte,
    val errorFlags: Byte,
    val fwVersion: Int
)

data class StreamMetrics(
    val emgPacketsReceived: Long = 0,
    val imuPacketsReceived: Long = 0,
    val emgSequenceGaps: Long = 0,
    val imuSequenceGaps: Long = 0,
    val packetsPerSecond: Float = 0f,
    val packetLossPercentage: Float = 0f
)
