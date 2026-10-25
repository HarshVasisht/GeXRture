package com.gexrture.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NOTE: these use synthetic buffers built from the upstream layout description. They check our
 * code against itself and against the standard CRC32 vector. They are NOT real-device captures
 * and do not validate the protocol against hardware.
 */
class XbxProtocolTest {

    @Test
    fun crc32_matches_standard_check_value() {
        val crc = CRC32().apply { update("123456789".toByteArray()) }.value
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun heartbeat_packet_layout() {
        val p = XbxProtocol.buildMcuPacket(XbxProtocol.MSG_HEARTBEAT, XbxProtocol.heartbeatPayload(0x0102030405060708L))
        assertEquals(5 + 17 + 8, p.size)
        assertEquals(0xFD, p[0].toInt() and 0xFF)
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(25, bb.getShort(5).toInt())
        assertEquals(0x1A, bb.getShort(15).toInt())
        assertEquals(0x0102030405060708L, bb.getLong(22))
        val crc = CRC32().apply { update(p, 5, 25) }.value
        assertEquals(crc, bb.getInt(1).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun empty_command_is_22_bytes() {
        assertEquals(22, XbxProtocol.buildMcuPacket(0x26).size)
    }

    @Test
    fun imu_sample_decodes_signed_24bit_and_scaling() {
        val b = ByteArray(64)
        b[0] = 0x01; b[1] = 0x02
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(4, 123456789L)
        bb.putShort(12, 2); bb.putInt(14, 4)           // gyro scale 2/4
        put24(b, 18, -1000); put24(b, 21, 0); put24(b, 24, 2000)
        bb.putShort(27, 1); bb.putInt(29, 1000)         // accel scale 1/1000
        put24(b, 33, 0); put24(b, 36, 0); put24(b, 39, 1000)

        val s = XbxProtocol.parseImu(b, 64) as XbxProtocol.ImuPacket.Sample
        assertEquals(123456789L, s.timestampNs)
        assertEquals(-500f, s.gx, 1e-3f)
        assertEquals(1000f, s.gz, 1e-3f)
        assertEquals(1f, s.az, 1e-4f)
    }

    @Test
    fun imu_init_unknown_and_short() {
        val b = ByteArray(64)
        b[0] = 0xAA.toByte(); b[1] = 0x53
        assertTrue(XbxProtocol.parseImu(b, 64) is XbxProtocol.ImuPacket.Init)
        b[0] = 0x7F
        assertTrue(XbxProtocol.parseImu(b, 64) is XbxProtocol.ImuPacket.Unknown)
        assertTrue(XbxProtocol.parseImu(b, 10) is XbxProtocol.ImuPacket.Short)
    }

    // ── Real captures: owner's a01+ on S25 Ultra, 2026-10-25 18:03:34 (first 32 bytes logged) ──

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun real_capture_mcu_replies_echo_command_id() {
        val reply57 = hex("fd 87 80 0d fb 13 00 00 00 00 00 c8 10 92 01 57 00 00 00 00 00 00 00 23 00 00 00 00 00 00 00 00")
        val replyHb = hex("fd af 48 6d 6c 2a 00 00 00 00 00 00 00 00 00 1a 00 00 00 00 00 00 00 05 dd b4 fa e9 f8 01 00 28")
        assertEquals(0x57, XbxProtocol.mcuMsgId(reply57, reply57.size))
        assertEquals(XbxProtocol.MSG_HEARTBEAT, XbxProtocol.mcuMsgId(replyHb, replyHb.size))
        assertTrue(XbxProtocol.isMcuAck(reply57, reply57.size))
    }

    @Test
    fun real_capture_0x26_reply_contains_version_text() {
        val reply26 = hex("fd 38 35 2d 91 26 00 00 00 00 00 3c fd 91 01 26 00 00 00 00 00 00 00 31 39 2e 31 2e 30 31 2e 31")
        assertEquals(0x26, XbxProtocol.mcuMsgId(reply26, reply26.size))
        assertTrue(XbxProtocol.mcuText(reply26, reply26.size).startsWith("19.1.01.1"))
    }

    @Test
    fun real_capture_first_imu_sample_header_and_gyro() {
        // Only 32 of 64 bytes were logged, so pad; accel fields are not in this capture.
        val b = hex("01 02 70 02 18 cd 36 24 06 00 00 00 40 1f 00 00 00 01 c0 fa ff 60 ce ff d0 8a ff 40 00 00 00 00")
            .copyOf(64)
        b[29] = 1 // non-zero accel divisor to avoid NaN; accel not asserted
        val s = XbxProtocol.parseImu(b, 64) as XbxProtocol.ImuPacket.Sample
        // Matches the on-device decode logged at the same time.
        assertEquals(26377375000L, s.timestampNs)
        assertEquals(-0.64086914f, s.gx, 1e-6f)
        assertEquals(-6.0577393f, s.gy, 1e-6f)
        assertEquals(-14.305115f, s.gz, 1e-6f)
        assertEquals(29.710144f, s.temperatureC, 1e-4f)
    }

    private fun put24(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte()
    }
}
