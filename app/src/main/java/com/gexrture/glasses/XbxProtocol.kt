package com.gexrture.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Pure (Android-free) encoding/decoding for the XBX MCU + IMU HID protocol.
 *
 * Source: xrealInterfaceLibrary commit abeed62 ("Add support XBX devices"), MIT license,
 * https://gitlab.com/wheaney/nrealAirLinuxDriver (bootstrap sequence credited upstream to
 * taowen/ar-glass-lib). This is an original re-implementation of the published layout.
 *
 * UPSTREAM CLAIM, NOT VALIDATED ON OUR HARDWARE: every constant here comes from reading that
 * source. Nothing has been confirmed against the owner's a01+ yet.
 */
object XbxProtocol {
    const val MCU_INTERFACE_ID = 0
    const val IMU_INTERFACE_ID = 1
    const val MAX_PACKET = 64

    const val MCU_HEAD: Int = 0xFD
    const val MSG_HEARTBEAT = 0x1A
    /** Device-originated; seen on device in pairs every ~20 s. Upstream names them, ignores them. */
    const val MSG_EVENT_START_HEARTBEAT = 0x6C02
    const val MSG_EVENT_END_HEARTBEAT = 0x6C12
    /** Upstream P_BUTTON_PRESSED. Seen on device as bursts (payload 03 .. 10/11/01). */
    const val MSG_EVENT_BUTTON = 0x6C05
    const val MSG_SDK_VERSION = 0x31
    const val SDK_VERSION = "3.1.1"
    const val HEARTBEAT_INTERVAL_MS = 100L
    const val ACK_TIMEOUT_MS = 250
    const val ACK_ATTEMPTS = 8

    private val ONE = byteArrayOf(1, 0, 0, 0)

    /** Bootstrap commands sent in order before the SDK-version message. */
    val BOOTSTRAP: List<Pair<Int, ByteArray>> = listOf(
        0x26 to ByteArray(0),
        0x57 to ByteArray(0),
        0x12 to ONE,
        0x02 to ONE,
        0x34 to ByteArray(0),
        0x35 to ByteArray(0),
    )

    /**
     * MCU frame: head(1) crc32(4) length(2) timestamp(8) msgid(2) reserved(5) data(n), all little
     * endian. length = 17 + n; crc32 covers [length .. end of data].
     */
    fun buildMcuPacket(msgId: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val length = 17 + data.size
        val buf = ByteBuffer.allocate(5 + length).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MCU_HEAD.toByte())
        buf.putInt(0) // checksum placeholder
        buf.putShort(length.toShort())
        buf.putLong(0L)
        buf.putShort(msgId.toShort())
        buf.put(ByteArray(5))
        buf.put(data)
        val bytes = buf.array()
        val crc = CRC32().apply { update(bytes, 5, length) }.value
        ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        return bytes
    }

    fun heartbeatPayload(nowNs: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nowNs).array()

    fun isMcuAck(buf: ByteArray, len: Int): Boolean =
        len > 0 && (buf[0].toInt() and 0xFF) == MCU_HEAD

    /** Message id of an MCU frame (offset 15, LE u16), or -1 if too short. */
    fun mcuMsgId(buf: ByteArray, len: Int): Int =
        if (len < 17) -1 else (buf[15].toInt() and 0xFF) or ((buf[16].toInt() and 0xFF) shl 8)

    /**
     * Printable ASCII of an MCU reply's payload, e.g. the firmware string. In replies, offset 22
     * is a status byte (00 in every on-device capture) and the payload starts at 23.
     */
    fun mcuText(buf: ByteArray, len: Int): String =
        buf.copyOfRange(minOf(23, len), minOf(len, buf.size)).takeWhile { it != 0.toByte() }
            .map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }
            .joinToString("")

    // ── IMU ──

    /** 64-byte IMU report offsets (upstream imu_hid_packet_t). */
    private const val OFF_SIG = 0
    private const val OFF_TEMP = 2
    private const val OFF_TS = 4
    private const val OFF_GYRO_MUL = 12
    private const val OFF_GYRO_DIV = 14
    private const val OFF_GYRO = 18
    private const val OFF_ACC_MUL = 27
    private const val OFF_ACC_DIV = 29
    private const val OFF_ACC = 33
    const val IMU_PACKET_SIZE = 64

    sealed class ImuPacket {
        /** Signature 0xAA 0x53. */
        data class Init(val timestampNs: Long) : ImuPacket()

        /**
         * Scaled values = raw * multiplier / divisor. Units are NOT established by us: upstream
         * reports accel reading 1 g at rest; the gyro unit is unconfirmed.
         */
        data class Sample(
            val timestampNs: Long,
            val gx: Float, val gy: Float, val gz: Float,
            val ax: Float, val ay: Float, val az: Float,
            val temperatureC: Float,
        ) : ImuPacket()

        data class Unknown(val sig0: Int, val sig1: Int) : ImuPacket()
        data object Short : ImuPacket()
    }

    fun parseImu(buf: ByteArray, len: Int): ImuPacket {
        if (len < IMU_PACKET_SIZE) return ImuPacket.Short
        val s0 = buf[OFF_SIG].toInt() and 0xFF
        val s1 = buf[OFF_SIG + 1].toInt() and 0xFF
        val ts = ByteBuffer.wrap(buf, OFF_TS, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (s0 == 0xAA && s1 == 0x53) return ImuPacket.Init(ts)
        if (s0 != 0x01 || s1 != 0x02) return ImuPacket.Unknown(s0, s1)

        val gm = i16(buf, OFF_GYRO_MUL).toFloat()
        val gd = i32(buf, OFF_GYRO_DIV).toFloat()
        val am = i16(buf, OFF_ACC_MUL).toFloat()
        val ad = i32(buf, OFF_ACC_DIV).toFloat()
        return ImuPacket.Sample(
            timestampNs = ts,
            gx = i24(buf, OFF_GYRO) * gm / gd,
            gy = i24(buf, OFF_GYRO + 3) * gm / gd,
            gz = i24(buf, OFF_GYRO + 6) * gm / gd,
            ax = i24(buf, OFF_ACC) * am / ad,
            ay = i24(buf, OFF_ACC + 3) * am / ad,
            az = i24(buf, OFF_ACC + 6) * am / ad,
            temperatureC = i16(buf, OFF_TEMP) / 132.48f + 25.0f,
        )
    }

    private fun b(buf: ByteArray, i: Int) = buf[i].toInt() and 0xFF
    private fun i16(buf: ByteArray, o: Int): Int = ((b(buf, o) or (b(buf, o + 1) shl 8)) shl 16) shr 16
    private fun i24(buf: ByteArray, o: Int): Int =
        ((b(buf, o) or (b(buf, o + 1) shl 8) or (b(buf, o + 2) shl 16)) shl 8) shr 8
    private fun i32(buf: ByteArray, o: Int): Int =
        b(buf, o) or (b(buf, o + 1) shl 8) or (b(buf, o + 2) shl 16) or (b(buf, o + 3) shl 24)
}
