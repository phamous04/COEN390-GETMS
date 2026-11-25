package com.example.app.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketParser {

    const val EMG_PACKET_SIZE = 86
    const val IMU_PACKET_SIZE = 46
    /** v0.1 status length; v0.2 sends [STATUS_PACKET_SIZE_V2]. */
    const val STATUS_PACKET_SIZE = 4
    const val STATUS_PACKET_SIZE_V2 = 18
    const val REP_EVENT_SIZE = 19
    const val REP_TYPE_VERSION = 0x11

    private const val EMG_SAMPLE_PERIOD_US = 1000L  // 1000 Hz
    private const val IMU_SAMPLE_PERIOD_US = 10000L // 100 Hz
    private const val QUAT_SCALE = 16384.0f         // 2^14 scale factor

    /**
     * Parses 86-byte EMG Packet:
     * seq u16 | t0_us u32 | 20 x (chA i16, chB i16)
     * Only channel A is used: we have one EMG sensor and the firmware always sends chB = 0.
     */
    fun parseEmgPacket(data: ByteArray): EmgPacket? {
        if (data.size < EMG_PACKET_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.short.toInt() and 0xFFFF
        val t0Us = buffer.int.toLong() and 0xFFFFFFFFL

        val samples = ArrayList<EmgSample>(20)
        for (i in 0 until 20) {
            val chA = buffer.short.toInt()
            buffer.short // chB, unused
            val timestamp = t0Us + (i * EMG_SAMPLE_PERIOD_US)
            samples.add(EmgSample(channelA = chA, timestampUs = timestamp))
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
     * Parses the Status characteristic:
     * state u8 | error_flags u8 | fw_version u16 | error_code u8 | cal_active u8 | cal_done u8 |
     * target_reps u8 | rom_ref_x10 u16 | mvc_mv u16 | baseline_mv u16 | rep_count u16 | clean_count u16
     */
    fun parseStatusPacket(data: ByteArray): DeviceStatus? {
        if (data.size < STATUS_PACKET_SIZE) return null

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val state = buffer.get()
        val errorFlags = buffer.get()
        val fwVersion = buffer.short.toInt() and 0xFFFF
        if (data.size < STATUS_PACKET_SIZE_V2) {
            return DeviceStatus(state = state, errorFlags = errorFlags, fwVersion = fwVersion)
        }
        return DeviceStatus(
            state = state,
            errorFlags = errorFlags,
            fwVersion = fwVersion,
            errorCode = buffer.u8(),
            calActive = buffer.u8(),
            calDone = buffer.u8(),
            targetReps = buffer.u8(),
            romRefX10 = buffer.u16(),
            mvcMv = buffer.u16(),
            baselineMv = buffer.u16(),
            repCount = buffer.u16(),
            cleanCount = buffer.u16(),
            extended = true,
        )
    }

    /**
     * Parses a 19-byte Rep Event:
     * type_version u8 | rep_index u16 | start_ms u32 | duration_ms u16 | rom_x10 u16 |
     * peak_act_x10 u16 | mean_act_x10 u16 | twist u8 | swing u8 | plane u8 | flags u8
     */
    fun parseRepEvent(data: ByteArray): RepEvent? {
        if (data.size < REP_EVENT_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.u8() != REP_TYPE_VERSION) return null
        return RepEvent(
            repIndex = buffer.u16(),
            startMs = buffer.int.toLong() and 0xFFFFFFFFL,
            durationMs = buffer.u16(),
            romX10 = buffer.u16(),
            peakActivationX10 = buffer.u16(),
            meanActivationX10 = buffer.u16(),
            twistDeg = buffer.u8(),
            swingDeg = buffer.u8(),
            planeDeg = buffer.u8(),
            flags = buffer.u8(),
        )
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
}
