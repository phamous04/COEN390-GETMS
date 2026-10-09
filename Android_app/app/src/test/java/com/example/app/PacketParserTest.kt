package com.example.app

import com.example.app.ble.PacketParser
import com.example.app.ble.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketParserTest {

    @Test
    fun testParseEmgPacket() {
        val buffer = ByteBuffer.allocate(86).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(101.toShort()) // seq = 101
        buffer.putInt(500000)          // t0_us = 500000

        for (i in 0 until 20) {
            buffer.putShort((100 + i).toShort()) // chA
            buffer.putShort((200 + i).toShort()) // chB
        }

        val packet = PacketParser.parseEmgPacket(buffer.array())
        assertNotNull(packet)
        assertEquals(101, packet!!.sequence)
        assertEquals(500000L, packet.t0Us)
        assertEquals(20, packet.samples.size)
        assertEquals(100, packet.samples[0].channelA)
        assertEquals(200, packet.samples[0].channelB)
        assertEquals(500000L, packet.samples[0].timestampUs)
        assertEquals(501000L, packet.samples[1].timestampUs)
    }

    @Test
    fun testParseImuPacket() {
        val buffer = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(42.toShort()) // seq = 42
        buffer.putInt(1000000)        // t0_us = 1000000

        // 1.0 in q14 format is 16384
        for (i in 0 until 5) {
            buffer.putShort(16384.toShort()) // qw = 1.0
            buffer.putShort(0.toShort())     // qx = 0
            buffer.putShort(0.toShort())     // qy = 0
            buffer.putShort(0.toShort())     // qz = 0
        }

        val packet = PacketParser.parseImuPacket(buffer.array())
        assertNotNull(packet)
        assertEquals(42, packet!!.sequence)
        assertEquals(1000000L, packet.t0Us)
        assertEquals(5, packet.samples.size)
        assertEquals(1.0f, packet.samples[0].quaternion.qw, 0.001f)
        assertEquals(0.0f, packet.samples[0].quaternion.toPitchDegrees(), 0.001f)
    }

    @Test
    fun testParseStatusPacket() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x01.toByte())       // state
        buffer.put(0x00.toByte())       // errorFlags
        buffer.putShort(0x0103.toShort()) // fwVersion 1.3

        val status = PacketParser.parseStatusPacket(buffer.array())
        assertNotNull(status)
        assertEquals(0x01.toByte(), status!!.state)
        assertEquals(0x00.toByte(), status.errorFlags)
        assertEquals(0x0103, status.fwVersion)
    }
}
