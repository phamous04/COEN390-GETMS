package com.example.app

import com.example.app.ble.DeviceState
import com.example.app.ble.GattConstants
import com.example.app.ble.PacketParser
import com.example.app.ble.RepFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte layouts from firmware/Reband/ble/getms_ble_spec.h (v0.2). */
class FirmwareProtocolTest {

    @Test
    fun parsesV2Status() {
        val b = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x02) // READY
        b.put(0x05) // ADC uncalibrated | IMU1 missing
        b.putShort(0x0002) // fw 0.2
        b.put(0) // error_code
        b.put(0) // cal_active
        b.put(3) // cal_done
        b.put(5) // target_reps
        b.putShort(1234) // rom_ref_x10
        b.putShort(1800) // mvc_mv
        b.putShort(150) // baseline_mv
        b.putShort(7) // rep_count
        b.putShort(6) // clean_count
        val s = PacketParser.parseStatusPacket(b.array())!!
        assertTrue(s.extended)
        assertEquals(DeviceState.READY, s.deviceState)
        assertEquals(0x05, s.flags)
        assertEquals(2, s.fwVersion)
        assertEquals(3, s.calDone)
        assertEquals(5, s.targetReps)
        assertEquals(1234, s.romRefX10)
        assertEquals(1800, s.mvcMv)
        assertEquals(150, s.baselineMv)
        assertEquals(7, s.repCount)
        assertEquals(6, s.cleanCount)
    }

    @Test
    fun shortStatusIsNotExtended() {
        val s = PacketParser.parseStatusPacket(byteArrayOf(0, 0, 1, 0))!!
        assertFalse(s.extended)
        assertEquals(0, s.repCount)
    }

    @Test
    fun parsesRepEvent() {
        val b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x11)
        b.putShort(3) // rep_index
        b.putInt(4_000_000) // start_ms (> u16)
        b.putShort(2100) // duration_ms
        b.putShort(1105) // rom_x10
        b.putShort(652) // peak %MVC x10
        b.putShort(301) // mean %MVC x10
        b.put(12) // twist
        b.put(200.toByte()) // swing (unsigned)
        b.put(9) // plane
        b.put((RepFlags.CLEAN or RepFlags.REPLAYED).toByte())
        val r = PacketParser.parseRepEvent(b.array())!!
        assertEquals(3, r.repIndex)
        assertEquals(4_000_000L, r.startMs)
        assertEquals(2100, r.durationMs)
        assertEquals(1105, r.romX10)
        assertEquals(652, r.peakActivationX10)
        assertEquals(301, r.meanActivationX10)
        assertEquals(12, r.twistDeg)
        assertEquals(200, r.swingDeg)
        assertEquals(9, r.planeDeg)
        assertTrue(r.isClean)
        assertEquals(RepFlags.CLEAN, r.flags and RepFlags.PERSISTED_MASK)
    }

    @Test
    fun rejectsWrongRepTypeOrLength() {
        assertNull(PacketParser.parseRepEvent(ByteArray(18)))
        val wrongType = ByteArray(19).also { it[0] = 0x21 }
        assertNull(PacketParser.parseRepEvent(wrongType))
    }

    /** The Android constants must match the firmware header, the source of truth. */
    @Test
    fun constantsMatchFirmwareHeader() {
        val header = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "firmware/Reband/ble/getms_ble_spec.h") }
            .firstOrNull { it.isFile }
        assertNotNull("firmware header not found", header)
        val text = header!!.readText()
        fun define(name: String) = Regex("#define\\s+$name\\s+(\\S+)").find(text)!!.groupValues[1]
        fun hex(name: String) = Integer.decode(define(name))
        assertEquals(hex("GETMS_CMD_CALIBRATE"), GattConstants.CMD_CALIBRATE.toInt())
        assertEquals(hex("GETMS_CMD_START"), GattConstants.CMD_START.toInt())
        assertEquals(hex("GETMS_CMD_STOP"), GattConstants.CMD_STOP.toInt())
        assertEquals(hex("GETMS_CMD_GET_STATE"), GattConstants.CMD_GET_STATE.toInt())
        assertEquals(hex("GETMS_CMD_GET_REPS"), GattConstants.CMD_GET_REPS.toInt())
        assertEquals(hex("GETMS_STATUS_LEN"), PacketParser.STATUS_PACKET_SIZE_V2)
        assertEquals(hex("GETMS_REP_EVENT_LEN"), PacketParser.REP_EVENT_SIZE)
        assertEquals(hex("GETMS_REP_TYPE_VERSION"), PacketParser.REP_TYPE_VERSION)
        assertEquals(define("GETMS_UUID_REP").trim('"'), GattConstants.REP_CHAR_UUID.toString())
        assertEquals(define("GETMS_UUID_CONTROL").trim('"'), GattConstants.CONTROL_CHAR_UUID.toString())
        assertEquals(define("GETMS_UUID_STATUS").trim('"'), GattConstants.STATUS_CHAR_UUID.toString())
        for ((state, name) in listOf(
            DeviceState.IDLE to "IDLE", DeviceState.CALIBRATING to "CALIBRATING", DeviceState.READY to "READY",
            DeviceState.RECORDING to "RECORDING", DeviceState.STOPPED to "STOPPED", DeviceState.ERROR to "ERROR",
        )) assertEquals(hex("GETMS_STATE_$name"), state.code)
    }
}
