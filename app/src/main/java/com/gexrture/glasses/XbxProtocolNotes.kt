package com.gexrture.glasses

/**
 * XBX protocol notes — documentation only. Executable form: [XbxProtocol] / [XbxSession].
 *
 * ## Provenance (UPSTREAM CLAIMS — none validated on the owner's a01+ yet)
 *
 * - XRLinuxDriver v2.11.3 (GPL-3.0) adds XBX a01/a01+ head tracking. We do not copy its code.
 * - The device protocol lives in the xrealInterfaceLibrary submodule, which is PUBLIC and
 *   MIT-licensed: https://gitlab.com/wheaney/nrealAirLinuxDriver
 *   Inspected commit abeed62 "Add support XBX devices" (HEAD at inspection: 61ecd4c).
 *   Files: interface_lib/src/{hid_ids.c, mcu_hid.c, imu_hid.c, crc32.c}.
 *   The upstream comment credits the bootstrap sequence to taowen/ar-glass-lib.
 * - Our Kotlin is an original re-implementation of the published layout.
 *
 * ## What upstream says
 *
 * - USB VID 0x3318; PID 0x0440 (a01), 0x0442 (a01+).
 * - HID interface 0 = MCU, interface 1 = IMU; max IMU payload 64 bytes.
 * - MCU frame (little endian): 0xFD | crc32(4) | length(2) | timestamp(8) | msgid(2) |
 *   reserved(5) | data. length = 17 + data size; CRC-32 (zlib/IEEE) over [length .. end].
 *   Written whole through the HID write path (0xFD is the report ID byte).
 * - Bootstrap, each command awaiting any reply starting with 0xFD (8 tries x 250 ms):
 *   msg 0x26, 0x57, 0x12 [01 00 00 00], 0x02 [01 00 00 00], 0x34, 0x35; then
 *   msg 0x31 with ASCII "3.1.1" (the SDK-identification gate); then two acked heartbeats
 *   msg 0x1A with an 8-byte monotonic-ns timestamp; then a write-only heartbeat every 100 ms.
 *   The MCU endpoint is not read while streaming.
 * - IMU report (64 B): sig 01 02 = sample, sig AA 53 = init report. Fields: temp(2) @2,
 *   timestamp u64 ns @4, gyro mul i16 @12, div i32 @14, xyz i24 @18/21/24; accel mul @27,
 *   div @29, xyz i24 @33/36/39; magnetometer after that (ignored by us). Value =
 *   raw * mul / div. Temperature = raw / 132.48 + 25.
 * - Upstream reports ~1000 Hz and accelerometer ~1 g at rest on a real a01+.
 *
 * ## Unknown until tested on hardware
 *
 * - Whether Android exposes interface ids 0/1 with interrupt endpoints, and whether the MCU
 *   interface has an OUT endpoint (XbxSession falls back to HID SET_REPORT and logs which).
 * - Whether any gate exists that upstream does not mention (e.g. start-IMU message 0x19 on the
 *   IMU interface; upstream XBX path does not appear to call it).
 * - Gyro units, axis conventions and handedness; tracking drift.
 * - Whether Samsung's display stack contends for these interfaces.
 */
object XbxProtocolNotes
