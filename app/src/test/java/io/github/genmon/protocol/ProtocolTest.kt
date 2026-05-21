package io.github.genmon.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {

    @Test
    fun `BB18 read request matches captured bytes`() {
        // Captured: WRITE_REQ handle=0x001e value=[01 42 42 31 38 30 30 30 39 04]
        val frame = Frame.readByte(group = 'B', dataNo = "18")
        val expected = byteArrayOf(0x01, 0x42, 0x42, 0x31, 0x38, 0x30, 0x30, 0x30, 0x39, 0x04)
        assertArrayEquals(expected, frame.encode())
    }

    @Test
    fun `BA04 read request matches captured bytes`() {
        // Captured: WRITE_REQ value=[01 42 41 30 34 30 30 30 37 04]
        val frame = Frame.readByte(group = 'A', dataNo = "04")
        val expected = byteArrayOf(0x01, 0x42, 0x41, 0x30, 0x34, 0x30, 0x30, 0x30, 0x37, 0x04)
        assertArrayEquals(expected, frame.encode())
    }

    @Test
    fun `BD11 read request matches captured bytes`() {
        // Captured: WRITE_REQ value=[01 42 44 31 31 30 30 30 36 04]
        val frame = Frame.readByte(group = 'D', dataNo = "11")
        val expected = byteArrayOf(0x01, 0x42, 0x44, 0x31, 0x31, 0x30, 0x30, 0x30, 0x36, 0x04)
        assertArrayEquals(expected, frame.encode())
    }

    @Test
    fun `decode response BB18=81 (1290 W reading)`() {
        // Captured indication: handle=0x0020 value=[80 01 42 42 31 38 38 31 30 30 04]
        // After stripping the 0x80 direction byte, decode the 10-byte frame.
        val wire = byteArrayOf(0x01, 0x42, 0x42, 0x31, 0x38, 0x38, 0x31, 0x30, 0x30, 0x04)
        val frame = Frame.decode(wire)
        assertNotNull(frame)
        assertEquals(Frame.Function.READ_1, frame!!.function)
        assertEquals('B', frame.group)
        assertEquals("18", frame.dataNo)
        assertEquals("81", frame.data)
    }

    @Test
    fun `OUTPUT_POWER 1290 W decodes from BB17=00 BB18=81`() {
        // Combined assembly: high byte = BB17 = 0x00, low byte = BB18 = 0x81 -> 0x0081 = 129
        // Then scale by 10 (decawatts -> watts) = 1290 W.
        val raw = Z44Profile.DataItem.OUTPUT_POWER.assemble(listOf(0x00, 0x81))
        assertEquals(129, raw)
        assertEquals(1290f, Z44Profile.DataItem.OUTPUT_POWER.toDisplay(raw), 0.001f)
    }

    @Test
    fun `OUTPUT_POWER reports small values verbatim`() {
        // 40 W (raw = 4) — no idle-noise clamp; show what the generator reports.
        val raw = 4
        assertEquals(40f, Z44Profile.DataItem.OUTPUT_POWER.toDisplay(raw), 0.001f)
    }

    @Test
    fun `unlock payload matches captured bytes`() {
        // Captured: WRITE_REQ handle=0x0019 value=[01 30 30 30 30 30 30 30 30]
        val payload = Z44Profile.unlockPayload(BleSpec.DEFAULT_PASSWORD)
        val expected = byteArrayOf(0x01, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30)
        assertArrayEquals(expected, payload)
    }

    @Test
    fun `unlock reset is nine zero bytes`() {
        assertArrayEquals(ByteArray(9), Z44Profile.unlockReset)
    }

    @Test
    fun `engine stop is a single zero byte`() {
        assertArrayEquals(byteArrayOf(0x00), Z44Profile.engineStopByte)
    }

    @Test
    fun `serial number parsed from FRAME_NUMBER read`() {
        // Captured: handle=0x0017 value=[45 41 4d 54 2d 32 34 36 37 30 32 31 20 00 00 00 00]
        val bytes = byteArrayOf(
            0x45, 0x41, 0x4d, 0x54, 0x2d, 0x32, 0x34, 0x36, 0x37, 0x30, 0x32, 0x31,
            0x20, 0x00, 0x00, 0x00, 0x00,
        )
        assertEquals("EAMT-2467021", Z44Profile.parseSerial(bytes))
    }

    @Test
    fun `frame with bad checksum fails to decode`() {
        val wire = byteArrayOf(0x01, 0x42, 0x42, 0x31, 0x38, 0x38, 0x31, 0x46, 0x46, 0x04)
        assertNull(Frame.decode(wire))
    }

    @Test
    fun `frame missing STX fails to decode`() {
        val wire = byteArrayOf(0x00, 0x42, 0x42, 0x31, 0x38, 0x30, 0x30, 0x30, 0x39, 0x04)
        assertNull(Frame.decode(wire))
    }

    @Test
    fun `frame missing EOT fails to decode`() {
        val wire = byteArrayOf(0x01, 0x42, 0x42, 0x31, 0x38, 0x30, 0x30, 0x30, 0x39, 0x00)
        assertNull(Frame.decode(wire))
    }

    @Test
    fun `checksum is XOR of the 6 body bytes`() {
        // BB18 read: body chars are 'B','B','1','8','0','0' -> 0x42^0x42^0x31^0x38^0x30^0x30 = 0x09
        val body = byteArrayOf(0x42, 0x42, 0x31, 0x38, 0x30, 0x30)
        assertEquals(0x09.toByte(), Checksum.xor(body))
    }
}
