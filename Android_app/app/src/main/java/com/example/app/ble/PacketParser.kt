package com.example.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketParser {

    const val EMG_PACKET_SIZE = 86
    const val IMU_PACKET_SIZE = 46
    const val STATUS_PACKET_SIZE = 4

    private const val EMG_SAMPLE_PERIOD_US = 1000L  // 1000 Hz
    private const val IMU_SAMPLE_PERIOD_US = 10000L // 100 Hz
    private const val QUAT_SCALE = 16384.0f         // 2^14 scale factor

    /**
     * Parses 86-byte EMG Packet:
     * seq u16 | t0_us u32 | 20 x (chA i16, chB i16)
     */
    fun parseEmgPacket(data: ByteArray): EmgPacket? {
        if (data.size < EMG_PACKET_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.short.toInt() and 0xFFFF
        val t0Us = buffer.int.toLong() and 0xFFFFFFFFL

        val samples = ArrayList<EmgSample>(20)
        for (i in 0 until 20) {
            val chA = buffer.short.toInt()
            val chB = buffer.short.toInt()
            val timestamp = t0Us + (i * EMG_SAMPLE_PERIOD_US)
            samples.add(EmgSample(channelA = chA, channelB = chB, timestampUs = timestamp))
        }

        return EmgPacket(sequence = seq, t0Us = t0Us, samples = samples)
    }

    /**
     * Parses 46-byte IMU Packet:
     * seq u16 | t0_us u32 | 5 x (qw, qx, qy, qz as i16, scale 2^14)
     */
    fun parseImuPacket(data: ByteArray): ImuPacket? {
        if (data.size < IMU_PACKET_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.short.toInt() and 0xFFFF
        val t0Us = buffer.int.toLong() and 0xFFFFFFFFL

        val samples = ArrayList<ImuSample>(5)
        for (i in 0 until 5) {
            val qwRaw = buffer.short
            val qxRaw = buffer.short
            val qyRaw = buffer.short
            val qzRaw = buffer.short

            val qw = qwRaw / QUAT_SCALE
            val qx = qxRaw / QUAT_SCALE
            val qy = qyRaw / QUAT_SCALE
            val qz = qzRaw / QUAT_SCALE

            val quaternion = Quaternion(qw = qw, qx = qx, qy = qy, qz = qz)
            val timestamp = t0Us + (i * IMU_SAMPLE_PERIOD_US)
            samples.add(ImuSample(quaternion = quaternion, timestampUs = timestamp))
        }

        return ImuPacket(sequence = seq, t0Us = t0Us, samples = samples)
    }

    /**
     * Parses 4-byte Status Packet:
     * state u8 | error_flags u8 | fw_version u16
     */
    fun parseStatusPacket(data: ByteArray): DeviceStatus? {
        if (data.size < STATUS_PACKET_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val state = buffer.get()
        val errorFlags = buffer.get()
        val fwVersion = buffer.short.toInt() and 0xFFFF

        return DeviceStatus(state = state, errorFlags = errorFlags, fwVersion = fwVersion)
    }
}
