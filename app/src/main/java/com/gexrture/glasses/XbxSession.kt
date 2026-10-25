package com.gexrture.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the upstream-described XBX bootstrap, heartbeat and IMU read against real hardware.
 *
 * This WRITES to the glasses' MCU (see [XbxProtocol.BOOTSTRAP]). It is only started by an
 * explicit user action. All values come from the real device; nothing is simulated.
 *
 * Owner of the MCU interface while running: the heartbeat coroutine is the only writer after
 * the handshake, and a drain coroutine is the only reader of the MCU IN endpoint.
 *
 * OBSERVED ON DEVICE (2026-10-25): the MCU acks every heartbeat. Linux's hidraw driver reads
 * and discards these automatically; on Android nobody does unless we read them. Without the
 * drain, heartbeat writes timed out after ~4 s and the MCU stayed wedged until replugged.
 * Do not run concurrently with [XbxSensorReader].
 *
 * Lifecycle rule: call [stopAndJoin] before the [UsbDeviceConnection] is closed. Closing the
 * connection while a worker is inside bulkTransfer can crash natively.
 *
 * Android's bulkTransfer returns -1 both for "no data before timeout" and for real errors, so
 * -1 is counted as an empty read, not a failure. Disconnect is detected via the USB detach
 * broadcast instead.
 */
class XbxSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    /** Mirror of the session log that survives a process crash; truncated on start. */
    private val logFile: File? = null,
) {
    enum class Phase { IDLE, HANDSHAKE, STREAMING, FAILED, STOPPED }

    data class Stats(
        val phase: Phase = Phase.IDLE,
        val message: String = "Idle",
        val elapsedMs: Long = 0,
        val heartbeatsSent: Long = 0,
        val heartbeatWriteFailures: Long = 0,
        /** MCU input packets drained during streaming (mostly heartbeat acks). */
        val mcuReportsDrained: Long = 0,
        val imuReports: Long = 0,
        val imuSamples: Long = 0,
        val initReports: Long = 0,
        val unknownReports: Long = 0,
        val shortReports: Long = 0,
        /** Reads that returned no data (timeout, or -1 which Android also uses for timeout). */
        val emptyReads: Long = 0,
        /** Sample timestamps that went backwards or reset. */
        val timestampRegressions: Long = 0,
        val maxGapMs: Double = 0.0,
        /** Device-timestamp based rate over the whole run, Hz. */
        val sampleRateHz: Double = 0.0,
        /** Inter-sample gaps above 1.5 / 5 / 20 ms (on device the nominal spacing is ~1 ms). */
        val gapsOver1_5ms: Long = 0,
        val gapsOver5ms: Long = 0,
        val gapsOver20ms: Long = 0,
        /** Sum of (round(gap ms) - 1) over gaps > 1.5 ms, ASSUMING a 1 ms nominal period. */
        val estMissingSamples: Long = 0,
        /** Device-originated MCU messages 0x6c02/0x6c12 (upstream: START/END_HEARTBEAT, ignored). */
        val mcuEvents: Long = 0,
        /** MCU 0x6c05 messages (upstream: P_BUTTON_PRESSED); seen on device when buttons pressed. */
        val buttonEvents: Long = 0,
        val last: XbxProtocol.ImuPacket.Sample? = null,
        /** Diagnostic gyro integration since [requestZero]; no fusion, drifts. */
        val zeroState: ZeroState = ZeroState.NOT_ZEROED,
        val angleX: Double = 0.0,
        val angleY: Double = 0.0,
        val angleZ: Double = 0.0,
        val biasX: Double = 0.0,
        val biasY: Double = 0.0,
        val biasZ: Double = 0.0,
    )

    enum class ZeroState { NOT_ZEROED, CALIBRATING, INTEGRATING }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val logLines = ArrayDeque<String>()

    private val crashGuard = CoroutineExceptionHandler { _, e ->
        log("EXCEPTION in session worker: ${Log.getStackTraceString(e)}")
        update { it.copy(phase = Phase.FAILED, message = "Internal error: ${e.javaClass.simpleName}: ${e.message}") }
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + crashGuard)
    private var mainJob: Job? = null
    private var heartbeatJob: Job? = null
    private var mcuDrainJob: Job? = null

    @Volatile private var zeroRequested = false

    /**
     * Reset the integrated angles. The next 1 s of device time is averaged as gyro bias, so the
     * glasses must be held still; after that, (gyro - bias) is integrated over device time.
     */
    fun requestZero() {
        zeroRequested = true
        log("Zero requested (hold still for 1 s)")
    }

    private var mcuIface: UsbInterface? = null
    private var imuIface: UsbInterface? = null
    private var mcuOut: UsbEndpoint? = null
    private var mcuIn: UsbEndpoint? = null
    private var imuIn: UsbEndpoint? = null

    /** [appendLog]: keep the previous session's file log (e.g. the one cut short by an unplug). */
    fun start(why: String = "Started", appendLog: Boolean = false) {
        if (mainJob?.isActive == true) return
        try {
            if (appendLog) logFile?.appendText("──────── new session ────────\n") else logFile?.writeText("")
        } catch (_: Exception) { }
        log("Session start: $why")
        mainJob = scope.launch { run() }
    }

    /**
     * Cancels workers and waits for them to leave bulkTransfer (bounded by the 250 ms read
     * timeout and 1000 ms write timeout).
     */
    suspend fun stopAndJoin(reason: String = "Stopped by user") {
        val jobs = listOfNotNull(heartbeatJob, mcuDrainJob, mainJob)
        if (jobs.none { it.isActive } && _stats.value.phase != Phase.STREAMING) return
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        // Interfaces are intentionally not released: on device, releaseInterface hung the worker
        // after a failed write. Closing the connection releases them.
        update { it.copy(phase = Phase.STOPPED, message = reason) }
        log("Session stopped: $reason")
    }

    /** Only after [stopAndJoin]. */
    fun destroy() {
        scope.cancel()
    }

    fun getLogSnapshot(): String = synchronized(logLines) { logLines.joinToString("\n") }

    // ── Flow ──

    private suspend fun run() {
        update { Stats(phase = Phase.HANDSHAKE, message = "Locating interfaces…") }
        if (!locateInterfaces()) return fail("Interface 0 (MCU) / 1 (IMU) not usable — see log")

        update { it.copy(message = "Sending bootstrap (upstream sequence, unverified here)…") }
        if (!bootstrap()) {
            return fail(
                "MCU bootstrap failed. If the first write failed, the glasses MCU is likely still " +
                    "wedged from a previous session: unplug and replug the glasses, then retry",
            )
        }

        val started = System.nanoTime()
        mcuDrainJob = scope.launch { mcuDrainLoop() }
        heartbeatJob = scope.launch { heartbeatLoop() }
        update { it.copy(phase = Phase.STREAMING, message = "Streaming IMU (waiting for reports)") }
        readImu(started)
    }

    private fun locateInterfaces(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == XbxProtocol.MCU_INTERFACE_ID) mcuIface = iface
            if (iface.id == XbxProtocol.IMU_INTERFACE_ID) imuIface = iface
        }
        val mcu = mcuIface ?: return false.also { log("No interface with id=0") }
        val imu = imuIface ?: return false.also { log("No interface with id=1") }

        for (e in 0 until mcu.endpointCount) {
            val ep = mcu.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT) mcuOut = mcuOut ?: ep else mcuIn = mcuIn ?: ep
        }
        for (e in 0 until imu.endpointCount) {
            val ep = imu.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_IN) imuIn = imuIn ?: ep
        }
        log("MCU iface0: out=${mcuOut?.address?.hex()} in=${mcuIn?.address?.hex()} (no OUT ep → SET_REPORT fallback)")
        log("IMU iface1: in=${imuIn?.address?.hex()}")

        val mcuClaimed = connection.claimInterface(mcu, true)
        val imuClaimed = connection.claimInterface(imu, true)
        log("claim iface0=$mcuClaimed iface1=$imuClaimed")
        return mcuClaimed && imuClaimed && mcuIn != null && imuIn != null
    }

    private suspend fun bootstrap(): Boolean {
        drainMcu()
        for ((msg, data) in XbxProtocol.BOOTSTRAP) {
            if (!command(msg, data)) return false
        }
        if (!command(XbxProtocol.MSG_SDK_VERSION, XbxProtocol.SDK_VERSION.toByteArray())) return false
        repeat(2) {
            if (!command(XbxProtocol.MSG_HEARTBEAT, XbxProtocol.heartbeatPayload(System.nanoTime()))) return false
        }
        log("Bootstrap acknowledged")
        return true
    }

    /** Discard stale MCU input before the handshake, as upstream does. */
    private fun drainMcu() {
        val buf = ByteArray(XbxProtocol.MAX_PACKET)
        repeat(4) {
            val n = connection.bulkTransfer(mcuIn, buf, buf.size, 20)
            if (n <= 0) return
            log("Drained stale MCU input (${n}B): ${buf.hexHead(n)}")
        }
    }

    private suspend fun command(msg: Int, data: ByteArray): Boolean {
        val packet = XbxProtocol.buildMcuPacket(msg, data)
        if (!writeMcu(packet)) {
            log("TX msg=0x${msg.toString(16)} write FAILED: ${packet.hexHead(packet.size)}")
            return false
        }
        log("TX msg=0x${msg.toString(16)} (${packet.size}B)")
        val buf = ByteArray(XbxProtocol.MAX_PACKET)
        repeat(XbxProtocol.ACK_ATTEMPTS) {
            if (!currentCoroutineContext().isActive) return false
            val n = connection.bulkTransfer(mcuIn, buf, buf.size, XbxProtocol.ACK_TIMEOUT_MS)
            if (n > 0) {
                val replyId = XbxProtocol.mcuMsgId(buf, n)
                log("RX for msg=0x${msg.toString(16)} (${n}B, reply id=0x${replyId.toString(16)}): ${buf.hexHead(n, 64)}")
                // On device every reply echoes the command id at offset 15; a stale ack for a
                // different command (e.g. a leftover heartbeat ack) is skipped.
                if (XbxProtocol.isMcuAck(buf, n) && replyId == msg) {
                    if (msg == 0x26) log("  0x26 reply as text: '${XbxProtocol.mcuText(buf, n)}'")
                    return true
                }
            }
        }
        log("TX msg=0x${msg.toString(16)}: no framed ack after ${XbxProtocol.ACK_ATTEMPTS} reads")
        return false
    }

    private fun writeMcu(packet: ByteArray): Boolean {
        val out = mcuOut
        val n = if (out != null) {
            connection.bulkTransfer(out, packet, packet.size, 1000)
        } else {
            // HID SET_REPORT (output report), report ID = first byte, as Linux hidraw does.
            connection.controlTransfer(
                0x21, 0x09, (2 shl 8) or (packet[0].toInt() and 0xFF),
                XbxProtocol.MCU_INTERFACE_ID, packet, packet.size, 1000,
            )
        }
        return n == packet.size
    }

    /** Continuously consume MCU input so the device never blocks on a full queue. */
    private suspend fun mcuDrainLoop() {
        val buf = ByteArray(XbxProtocol.MAX_PACKET)
        var loggedOther = 0
        var loggedButtons = 0
        var loggedEvents = 0
        while (currentCoroutineContext().isActive) {
            val n = connection.bulkTransfer(mcuIn, buf, buf.size, 250)
            if (n <= 0) continue
            val id = XbxProtocol.mcuMsgId(buf, n)
            val isEvent = id == XbxProtocol.MSG_EVENT_START_HEARTBEAT || id == XbxProtocol.MSG_EVENT_END_HEARTBEAT
            val isButton = id == XbxProtocol.MSG_EVENT_BUTTON
            when {
                id == XbxProtocol.MSG_HEARTBEAT -> Unit
                // Payload bytes 22..29 differ per press; logged raw until their meaning is mapped.
                isButton -> if (loggedButtons++ < 50) log("MCU button event (upstream P_BUTTON_PRESSED): ${buf.hexHead(n, 32)}")
                // Periodic 0x6c02/0x6c12 pairs arrive every ~20 s; the first pair is enough.
                isEvent -> if (loggedEvents++ < 2) log("MCU report id=0x${id.toString(16)} [known event (upstream ignores); further ones only counted]: ${buf.hexHead(n, 32)}")
                loggedOther++ < 20 -> {
                    val label = if (isEvent) "known event (upstream ignores)" else "UNEXPECTED"
                    log("MCU report id=0x${id.toString(16)} [$label]: ${buf.hexHead(n, 64)}")
                }
            }
            update {
                it.copy(
                    mcuReportsDrained = it.mcuReportsDrained + 1,
                    mcuEvents = it.mcuEvents + if (isEvent) 1 else 0,
                    buttonEvents = it.buttonEvents + if (isButton) 1 else 0,
                )
            }
        }
    }

    private suspend fun heartbeatLoop() {
        var loggedFailures = 0
        var lastOkNs = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            val ok = writeMcu(
                XbxProtocol.buildMcuPacket(
                    XbxProtocol.MSG_HEARTBEAT, XbxProtocol.heartbeatPayload(System.nanoTime()),
                ),
            )
            if (ok) {
                lastOkNs = System.nanoTime()
            } else if (loggedFailures++ < 5) {
                log("Heartbeat write failed (${(System.nanoTime() - lastOkNs) / 1_000_000} ms since last success)")
            }
            update {
                if (ok) it.copy(heartbeatsSent = it.heartbeatsSent + 1)
                else it.copy(heartbeatWriteFailures = it.heartbeatWriteFailures + 1)
            }
            delay(XbxProtocol.HEARTBEAT_INTERVAL_MS)
        }
    }

    private suspend fun readImu(startedNs: Long) {
        val ep = imuIn ?: return
        val buf = ByteArray(maxOf(ep.maxPacketSize, XbxProtocol.IMU_PACKET_SIZE))
        var prevTs = -1L
        var firstTs = -1L
        var lastPublish = 0L
        var lastStatsLog = System.nanoTime()
        var s = _stats.value
        var loggedUnknown = 0
        // Zero/integration state (device-time based).
        var calibUntilTs = -1L
        var calibN = 0
        var sumX = 0.0; var sumY = 0.0; var sumZ = 0.0

        while (currentCoroutineContext().isActive) {
            val n = connection.bulkTransfer(ep, buf, buf.size, 250)
            if (n <= 0) {
                s = s.copy(emptyReads = s.emptyReads + 1)
            } else {
                s = s.copy(imuReports = s.imuReports + 1)
                when (val p = XbxProtocol.parseImu(buf, n)) {
                    is XbxProtocol.ImuPacket.Init -> {
                        s = s.copy(initReports = s.initReports + 1)
                        log("IMU init report ts=${p.timestampNs}")
                    }
                    is XbxProtocol.ImuPacket.Sample -> {
                        if (firstTs < 0) {
                            firstTs = p.timestampNs
                            log("First sample (raw ${n}B): ${buf.hexHead(n)}")
                            log("First sample decoded: $p")
                        }
                        var dtS = 0.0
                        if (prevTs >= 0) {
                            if (p.timestampNs < prevTs) {
                                s = s.copy(timestampRegressions = s.timestampRegressions + 1)
                            } else {
                                val gapMs = (p.timestampNs - prevTs) / 1e6
                                dtS = gapMs / 1000.0
                                s = s.copy(maxGapMs = maxOf(s.maxGapMs, gapMs))
                                if (gapMs > 1.5) {
                                    s = s.copy(
                                        gapsOver1_5ms = s.gapsOver1_5ms + 1,
                                        gapsOver5ms = s.gapsOver5ms + if (gapMs > 5) 1 else 0,
                                        gapsOver20ms = s.gapsOver20ms + if (gapMs > 20) 1 else 0,
                                        estMissingSamples = s.estMissingSamples + (Math.round(gapMs) - 1),
                                    )
                                }
                            }
                        }
                        prevTs = p.timestampNs
                        val spanS = (p.timestampNs - firstTs) / 1e9
                        s = s.copy(
                            imuSamples = s.imuSamples + 1, last = p,
                            sampleRateHz = if (spanS > 0) s.imuSamples / spanS else 0.0,
                        )

                        if (zeroRequested) {
                            zeroRequested = false
                            calibUntilTs = p.timestampNs + 1_000_000_000L
                            calibN = 0; sumX = 0.0; sumY = 0.0; sumZ = 0.0
                            s = s.copy(zeroState = ZeroState.CALIBRATING, angleX = 0.0, angleY = 0.0, angleZ = 0.0)
                        }
                        when (s.zeroState) {
                            ZeroState.CALIBRATING -> {
                                sumX += p.gx; sumY += p.gy; sumZ += p.gz; calibN++
                                if (p.timestampNs >= calibUntilTs && calibN > 0) {
                                    s = s.copy(
                                        zeroState = ZeroState.INTEGRATING,
                                        biasX = sumX / calibN, biasY = sumY / calibN, biasZ = sumZ / calibN,
                                    )
                                    log("Zeroed: bias from $calibN samples = (%.3f, %.3f, %.3f)".format(s.biasX, s.biasY, s.biasZ))
                                }
                            }
                            ZeroState.INTEGRATING -> if (dtS > 0 && dtS < 0.05) {
                                s = s.copy(
                                    angleX = s.angleX + (p.gx - s.biasX) * dtS,
                                    angleY = s.angleY + (p.gy - s.biasY) * dtS,
                                    angleZ = s.angleZ + (p.gz - s.biasZ) * dtS,
                                )
                            }
                            ZeroState.NOT_ZEROED -> Unit
                        }
                    }
                    is XbxProtocol.ImuPacket.Unknown -> {
                        s = s.copy(unknownReports = s.unknownReports + 1)
                        if (loggedUnknown++ < 5) log("Unknown IMU sig %02x %02x: %s".format(p.sig0, p.sig1, buf.hexHead(n)))
                    }
                    XbxProtocol.ImuPacket.Short -> {
                        s = s.copy(shortReports = s.shortReports + 1)
                        if (s.shortReports <= 5) log("Short IMU report ${n}B: ${buf.hexHead(n)}")
                    }
                }
            }
            val now = System.nanoTime()
            if (now - lastPublish > 100_000_000L) {
                lastPublish = now
                if (s.imuSamples == 0L && s.emptyReads > 0 && s.emptyReads % 40 == 0L) {
                    log("No IMU reports yet after ${s.emptyReads} empty reads (~${s.emptyReads / 4}s)")
                }
                publish(s, startedNs)
            }
            // Every 5 s for the first 30 s, then every 30 s: an 8-minute run at 5 s overflowed
            // the owner's chat paste limit.
            val statsIntervalNs = if (now - startedNs < 30_000_000_000L) 5_000_000_000L else 30_000_000_000L
            if (now - lastStatsLog > statsIntervalNs) {
                lastStatsLog = now
                // Goes to the crash-surviving log file, so a run that dies still leaves numbers.
                val st = _stats.value
                val l = st.last
                log(
                    "STATS t=${st.elapsedMs / 1000}s n=${st.imuSamples} ${"%.1f".format(st.sampleRateHz)}Hz " +
                        "maxGap=${"%.0f".format(st.maxGapMs)}ms gaps>1.5/5/20=${st.gapsOver1_5ms}/${st.gapsOver5ms}/${st.gapsOver20ms} " +
                        "miss≈${st.estMissingSamples} regress=${st.timestampRegressions} empty=${st.emptyReads} unk=${st.unknownReports} " +
                        "hb=${st.heartbeatsSent}/fail=${st.heartbeatWriteFailures} acks=${st.mcuReportsDrained} ev=${st.mcuEvents} " +
                        "btn=${st.buttonEvents} ang=${st.zeroState.name.take(5)}(%.1f,%.1f,%.1f)".format(st.angleX, st.angleY, st.angleZ) +
                        (if (l == null) "" else " g=(%.2f,%.2f,%.2f) a=(%.2f,%.2f,%.2f) T=%.1fC"
                            .format(l.gx, l.gy, l.gz, l.ax, l.ay, l.az, l.temperatureC)),
                )
            }
        }
    }

    /** Merge reader-side counters into shared state without clobbering heartbeat/drain counters. */
    private fun publish(s: Stats, startedNs: Long) = update {
        s.copy(
            phase = it.phase, message = if (s.imuSamples > 0) "Streaming IMU" else it.message,
            heartbeatsSent = it.heartbeatsSent, heartbeatWriteFailures = it.heartbeatWriteFailures,
            mcuReportsDrained = it.mcuReportsDrained, mcuEvents = it.mcuEvents, buttonEvents = it.buttonEvents,
            elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
        )
    }

    private suspend fun fail(msg: String) {
        log("FAILED: $msg")
        listOfNotNull(heartbeatJob, mcuDrainJob).forEach { it.cancel(); it.join() }
        update { it.copy(phase = Phase.FAILED, message = msg) }
    }

    private inline fun update(crossinline f: (Stats) -> Stats) = _stats.update { f(it) }

    private fun log(msg: String) {
        val line = "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}] $msg"
        Log.d("XbxSession", msg)
        synchronized(logLines) {
            logLines.addLast(line)
            if (logLines.size > 2000) logLines.removeFirst()
            try { logFile?.appendText(line + "\n") } catch (_: Exception) { }
        }
    }

    private fun Int.hex() = "0x" + toString(16)
    private fun ByteArray.hexHead(n: Int, max: Int = 32) = take(minOf(n, max)).joinToString(" ") { "%02x".format(it) }
}
