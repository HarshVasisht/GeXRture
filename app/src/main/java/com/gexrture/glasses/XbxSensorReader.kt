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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Reads raw sensor data from an XREAL XBX device over USB HID.
 *
 * ⚠️ UNVERIFIED: This is a diagnostic/exploratory reader. The exact HID
 * report format, handshake sequence, and heartbeat mechanism have NOT been
 * validated on the owner's hardware. This reader:
 * - Scans all interfaces for HID interrupt-IN endpoints
 * - Attempts to claim them and read raw bytes
 * - Logs everything for analysis
 * - Does NOT send any handshake or heartbeat commands yet
 *
 * Without the handshake, the device may not produce IMU data.
 * This is intentional for M1 — we want to safely observe what happens
 * before blindly sending commands to the MCU.
 */
class XbxSensorReader(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    companion object {
        private const val TAG = "XbxSensorReader"
        private const val READ_TIMEOUT_MS = 250
        private const val MAX_PACKET_SIZE = 64
    }

    /** Diagnostic state for the sensor reader. */
    data class SensorState(
        val isReading: Boolean = false,
        val packetsReceived: Long = 0,
        val bytesReceived: Long = 0,
        /** Reads with no data. Android returns -1 for a timeout too, so this is not an error count. */
        val emptyReads: Long = 0,
        val lastPacketHex: String = "",
        val lastPacketTimestamp: Long = 0,
        val claimedInterfaces: List<InterfaceClaim> = emptyList(),
        val statusMessage: String = "Idle",
    )

    data class InterfaceClaim(
        val index: Int,
        val interfaceClass: Int,
        val interfaceSubclass: Int,
        val interfaceProtocol: Int,
        val endpointCount: Int,
        val claimed: Boolean,
        val endpointDetails: List<String>,
    )

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var readJob: Job? = null
    private val crashGuard = CoroutineExceptionHandler { _, e ->
        appendLog("EXCEPTION in raw reader: ${Log.getStackTraceString(e)}")
        _state.value = _state.value.copy(isReading = false, statusMessage = "Internal error: ${e.message}")
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + crashGuard)

    /**
     * Scan interfaces, claim HID ones, and start reading from interrupt-IN endpoints.
     * All data is logged; no interpretation is attempted in M1.
     */
    fun startReading() {
        if (readJob?.isActive == true) {
            appendLog("Already reading")
            return
        }

        readJob = scope.launch {
            _state.value = _state.value.copy(
                isReading = true,
                statusMessage = "Scanning interfaces…",
                packetsReceived = 0,
                bytesReceived = 0,
                emptyReads = 0,
            )

            val claims = mutableListOf<InterfaceClaim>()
            val readableEndpoints = mutableListOf<Pair<UsbInterface, UsbEndpoint>>()

            // Scan all interfaces
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val endpointDetails = mutableListOf<String>()
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    endpointDetails.add(
                        "EP${e}: addr=0x${ep.address.toString(16)} " +
                        "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                        "type=${epTypeName(ep.type)} maxPkt=${ep.maxPacketSize}"
                    )
                }

                // Attempt to claim
                val claimed = connection.claimInterface(iface, true)
                appendLog("Interface $i (class=${iface.interfaceClass}): claim=${if (claimed) "OK" else "FAILED"}")

                claims.add(
                    InterfaceClaim(
                        index = i,
                        interfaceClass = iface.interfaceClass,
                        interfaceSubclass = iface.interfaceSubclass,
                        interfaceProtocol = iface.interfaceProtocol,
                        endpointCount = iface.endpointCount,
                        claimed = claimed,
                        endpointDetails = endpointDetails,
                    )
                )

                // Collect interrupt-IN endpoints from claimed HID interfaces
                if (claimed) {
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN &&
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                        ) {
                            readableEndpoints.add(iface to ep)
                            appendLog("  → Will read from EP addr=0x${ep.address.toString(16)}")
                        }
                    }
                }
            }

            _state.value = _state.value.copy(claimedInterfaces = claims)

            if (readableEndpoints.isEmpty()) {
                _state.value = _state.value.copy(
                    isReading = false,
                    statusMessage = "No readable interrupt-IN endpoints found",
                )
                appendLog("No readable endpoints — device may need handshake first (see XbxProtocolNotes)")
                return@launch
            }

            // Read from the first available endpoint (typically the IMU one)
            val (_, endpoint) = readableEndpoints.first()
            _state.value = _state.value.copy(
                statusMessage = "Reading from EP 0x${endpoint.address.toString(16)}… " +
                    "(no handshake sent — data may not flow)"
            )

            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (isActive) {
                val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
                if (bytesRead > 0) {
                    val hex = buffer.take(bytesRead).joinToString(" ") { "%02x".format(it) }
                    val current = _state.value
                    _state.value = current.copy(
                        packetsReceived = current.packetsReceived + 1,
                        bytesReceived = current.bytesReceived + bytesRead,
                        lastPacketHex = hex,
                        lastPacketTimestamp = System.currentTimeMillis(),
                        statusMessage = "Receiving data (${current.packetsReceived + 1} packets)",
                    )
                    // Log first 20 packets, then every 100th
                    if (current.packetsReceived < 20 || current.packetsReceived % 100 == 0L) {
                        appendLog("PKT #${current.packetsReceived + 1} (${bytesRead}B): $hex")
                    }
                } else {
                    // 0 or -1: no data within the timeout (Android does not distinguish -1
                    // timeout from error; disconnect is detected via the detach broadcast).
                    val current = _state.value
                    if (current.packetsReceived == 0L && current.emptyReads % 40 == 0L) {
                        appendLog("No data yet (${current.emptyReads} empty reads, code=$bytesRead) — expected before handshake")
                    }
                    _state.value = current.copy(
                        emptyReads = current.emptyReads + 1,
                        statusMessage = if (current.packetsReceived == 0L) "Listening — no data (expected before handshake)" else current.statusMessage,
                    )
                }

                // Small yield to avoid tight-looping on errors
                if (_state.value.packetsReceived == 0L) {
                    delay(20)
                }
            }
        }
    }

    fun stopReading() {
        readJob?.cancel()
        readJob = null
        _state.value = _state.value.copy(
            isReading = false,
            statusMessage = "Stopped",
        )
        appendLog("Reading stopped")
    }

    /** Cancel and wait until the worker has left bulkTransfer (bounded by READ_TIMEOUT_MS). */
    suspend fun stopAndJoin() {
        val job = readJob ?: return
        stopReading()
        job.join()
        // The loop may have published once more after cancel; restate the final status.
        _state.value = _state.value.copy(isReading = false, statusMessage = "Stopped")
    }

    fun getLogSnapshot(): String = _log.value.joinToString("\n")

    /** Only after [stopAndJoin]. */
    fun destroy() {
        scope.cancel()
    }

    private fun epTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
        else -> "UNKNOWN($type)"
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        Log.d(TAG, msg)
        _log.value = (_log.value + line).takeLast(2000)
    }
}
