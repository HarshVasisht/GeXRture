package com.gexrture.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gexrture.GeXRtureApp
import com.gexrture.databinding.ActivityDiagnosticBinding
import com.gexrture.display.DisplayEnumerator
import com.gexrture.glasses.UsbGlassesManager
import com.gexrture.glasses.XbxSensorReader
import com.gexrture.glasses.XbxSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M1 Diagnostic Activity — the primary UI for hardware validation.
 *
 * Shows:
 * - Device info (Android version, model, build)
 * - USB device scan results with XREAL detection status
 * - USB permission state and connection details
 * - External display enumeration
 * - Raw sensor data stream (if device responds without handshake)
 * - Full diagnostic log exportable as text
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticBinding
    private lateinit var usbManager: UsbGlassesManager
    private lateinit var displayEnumerator: DisplayEnumerator
    private var sensorReader: XbxSensorReader? = null
    private var imuSession: XbxSession? = null
    private var sessionObserver: Job? = null

    /**
     * Set when the glasses detach while a handshake session is streaming. The owner already
     * chose to run the handshake, so it is restarted once the device is reconnected.
     */
    private var resumeAfterReconnect = false

    /** Crash file + last session log from a previous process, if any; shown and exported. */
    private var previousRunReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = UsbGlassesManager(this)
        displayEnumerator = DisplayEnumerator(this)

        loadPreviousRunReport()
        usbManager.beforeClose = {
            // Runs on the main thread from the detach broadcast; workers exit within ~1 s
            // (250 ms read timeout, 1000 ms write timeout).
            resumeAfterReconnect = imuSession?.stats?.value?.phase == XbxSession.Phase.STREAMING
            runBlocking { withTimeoutOrNull(2500) { stopWorkers("USB disconnected") } }
            releaseWorkers()
        }

        setupDeviceInfoSection()
        setupButtons()
        observeState()

        // Auto-scan on launch
        usbManager.register()
        displayEnumerator.register()
        usbManager.scanForGlasses()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle USB device attached intent
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            usbManager.scanForGlasses()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // unregister() disconnects, which runs beforeClose → workers stopped before close.
        usbManager.unregister()
        releaseWorkers()
        displayEnumerator.unregister()
    }

    /** Stop every USB worker and wait until none is inside bulkTransfer. */
    private suspend fun stopWorkers(reason: String) {
        sensorReader?.stopAndJoin()
        imuSession?.stopAndJoin(reason)
    }

    private fun startImuSession(
        state: UsbGlassesManager.ConnectionState.Connected,
        why: String,
        appendLog: Boolean = false,
    ) {
        binding.btnStartImu.isEnabled = false
        lifecycleScope.launch {
            // The raw reader claims every interface and reads the same endpoints: wait until
            // it has fully stopped before the session claims interfaces 0/1.
            stopWorkers("Replaced by a new session")
            releaseWorkers()
            val session = XbxSession(
                state.device, state.connection, GeXRtureApp.sessionLogFile(application),
            )
            imuSession = session
            observeSession(session)
            session.start(why, appendLog = appendLog)
            binding.btnStartImu.isEnabled = usbManager.state.value is UsbGlassesManager.ConnectionState.Connected
        }
    }

    private fun releaseWorkers() {
        sensorReader?.destroy()
        sensorReader = null
        imuSession?.destroy()
        imuSession = null
    }

    private fun loadPreviousRunReport() {
        val crash = GeXRtureApp.crashFile(application)
        val session = GeXRtureApp.sessionLogFile(application)
        previousRunReport = buildString {
            if (crash.exists()) {
                appendLine("═══ PREVIOUS CRASH ═══")
                appendLine(crash.readText())
                // Keep a copy for this export, then clear so it is reported once.
                crash.renameTo(java.io.File(crash.parentFile, "previous-crash.txt"))
            }
            if (session.exists() && session.length() > 0) {
                appendLine("═══ PREVIOUS HANDSHAKE/IMU SESSION LOG ═══")
                appendLine(session.readText().takeLast(20_000))
            }
        }
        if (previousRunReport.isNotEmpty()) {
            binding.tvSensorStatus.text = "Previous run left a crash/session log — included in Export Log."
        }
    }

    private fun setupDeviceInfoSection() {
        val info = buildString {
            appendLine("═══ Device Info ═══")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("SOC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            }
            appendLine("USB Host: ${packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)}")
        }
        binding.tvDeviceInfo.text = info
    }

    private fun setupButtons() {
        binding.btnScanUsb.setOnClickListener {
            usbManager.scanForGlasses()
        }

        binding.btnRefreshDisplays.setOnClickListener {
            displayEnumerator.refreshDisplays()
        }

        binding.btnStartSensor.setOnClickListener {
            val state = usbManager.state.value
            if (state !is UsbGlassesManager.ConnectionState.Connected) {
                Toast.makeText(this, "Connect to glasses first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                stopWorkers("Raw reader started")
                releaseWorkers()
                sensorReader = XbxSensorReader(state.device, state.connection).also { it.startReading() }
            }
        }

        binding.btnStopSensor.setOnClickListener {
            lifecycleScope.launch { sensorReader?.stopAndJoin() }
        }

        binding.btnStartImu.setOnClickListener {
            val state = usbManager.state.value
            if (state !is UsbGlassesManager.ConnectionState.Connected) {
                Toast.makeText(this, "Connect to glasses first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resumeAfterReconnect = false
            startImuSession(state, "Started by user")
        }

        binding.btnZeroImu.setOnClickListener {
            imuSession?.requestZero()
        }

        binding.btnStopImu.setOnClickListener {
            resumeAfterReconnect = false
            lifecycleScope.launch { imuSession?.stopAndJoin() }
        }

        binding.btnExportLog.setOnClickListener {
            exportLogs()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLogOutput.text = ""
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            usbManager.state.collect { state ->
                binding.tvUsbStatus.text = when (state) {
                    is UsbGlassesManager.ConnectionState.Disconnected ->
                        "⚪ No XREAL device found"
                    is UsbGlassesManager.ConnectionState.DeviceFound ->
                        if (state.hasPermission) "🟡 Device found, opening…"
                        else "🟡 Device found — requesting permission…"
                    is UsbGlassesManager.ConnectionState.PermissionDenied ->
                        "🔴 USB permission denied"
                    is UsbGlassesManager.ConnectionState.Connected ->
                        "🟢 Connected: ${state.device.deviceName}"
                    is UsbGlassesManager.ConnectionState.Error ->
                        "🔴 Error: ${state.message}"
                }
                // Enable/disable sensor buttons
                val connected = state is UsbGlassesManager.ConnectionState.Connected
                binding.btnStartSensor.isEnabled = connected
                binding.btnStopSensor.isEnabled = connected
                binding.btnStartImu.isEnabled = connected
                binding.btnStopImu.isEnabled = connected
                binding.btnZeroImu.isEnabled = connected
                // On disconnect, beforeClose has already stopped and released the workers.
                if (state is UsbGlassesManager.ConnectionState.Connected && resumeAfterReconnect) {
                    resumeAfterReconnect = false
                    startImuSession(state, "Auto-resumed after USB reconnect", appendLog = true)
                }
            }
        }

        lifecycleScope.launch {
            displayEnumerator.displays.collect { displays ->
                val text = buildString {
                    if (displays.isEmpty()) {
                        appendLine("No displays detected")
                        return@buildString
                    }
                    displays.forEach { d ->
                        appendLine("${if (d.isExternal) "🖥️" else "📱"} Display ${d.displayId}: ${d.name}")
                        appendLine("  ${d.width}×${d.height} @${d.refreshRate}Hz")
                        appendLine("  DPI: ${d.densityDpi}  State: ${d.stateName}")
                        appendLine("  Flags: ${d.flagsDescription}")
                        appendLine("  Modes: ${d.supportedRefreshRates.joinToString(", ") { "%.1f".format(it) }}")
                        appendLine()
                    }
                }
                binding.tvDisplayInfo.text = text
            }
        }

        // Combine logs from all sources
        lifecycleScope.launch {
            combine(
                usbManager.log,
                displayEnumerator.log,
            ) { usbLog, displayLog ->
                (usbLog + displayLog).sorted()
            }.collect { combined ->
                binding.tvLogOutput.text = combined.takeLast(200).joinToString("\n")
                // Auto-scroll to bottom
                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        // Sensor reader logs (when active)
        lifecycleScope.launch {
            var lastReader: XbxSensorReader? = null
            while (true) {
                val reader = sensorReader
                if (reader != null && reader != lastReader) {
                    lastReader = reader
                    launch {
                        reader.state.collect { sensorState ->
                            binding.tvSensorStatus.text = buildString {
                                appendLine("═══ Sensor Reader ═══")
                                appendLine("Status: ${sensorState.statusMessage}")
                                appendLine("Packets: ${sensorState.packetsReceived}  Bytes: ${sensorState.bytesReceived}")
                                appendLine("Empty reads (no data / -1): ${sensorState.emptyReads}")
                                if (sensorState.lastPacketHex.isNotEmpty()) {
                                    appendLine("Last packet: ${sensorState.lastPacketHex}")
                                }
                                if (sensorState.claimedInterfaces.isNotEmpty()) {
                                    appendLine()
                                    appendLine("Interfaces:")
                                    sensorState.claimedInterfaces.forEach { ci ->
                                        appendLine("  #${ci.index}: class=${ci.interfaceClass} " +
                                            "claimed=${ci.claimed} eps=${ci.endpointCount}")
                                        ci.endpointDetails.forEach { ep ->
                                            appendLine("    $ep")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun observeSession(session: XbxSession) {
        sessionObserver?.cancel()
        sessionObserver = lifecycleScope.launch {
            session.stats.collect { s ->
                binding.tvImuStatus.text = buildString {
                    appendLine("Phase: ${s.phase}  ${s.message}")
                    appendLine("Elapsed: ${s.elapsedMs / 1000}s  Heartbeats: ${s.heartbeatsSent} (write failures ${s.heartbeatWriteFailures})  MCU acks drained: ${s.mcuReportsDrained}")
                    appendLine("Reports: ${s.imuReports}  samples: ${s.imuSamples}  init: ${s.initReports}")
                    appendLine("Unknown: ${s.unknownReports}  short: ${s.shortReports}  empty reads (no data / -1): ${s.emptyReads}")
                    appendLine("Device-ts rate: ${"%.1f".format(s.sampleRateHz)} Hz  max gap: ${"%.2f".format(s.maxGapMs)} ms  ts regressions: ${s.timestampRegressions}")
                    appendLine("Gaps >1.5/5/20 ms: ${s.gapsOver1_5ms}/${s.gapsOver5ms}/${s.gapsOver20ms}  est. missing (1 ms period assumed): ${s.estMissingSamples}")
                    appendLine("MCU events 0x6c02/0x6c12: ${s.mcuEvents}  button events 0x6c05: ${s.buttonEvents}")
                    when (s.zeroState) {
                        XbxSession.ZeroState.NOT_ZEROED -> appendLine("Turn test: press Zero, hold still 1 s")
                        XbxSession.ZeroState.CALIBRATING -> appendLine("Turn test: calibrating bias — hold still…")
                        XbxSession.ZeroState.INTEGRATING -> appendLine(
                            "Turn test angle X/Y/Z (deg if gyro is deg/s): %.1f  %.1f  %.1f".format(s.angleX, s.angleY, s.angleZ),
                        )
                    }
                    s.last?.let {
                        appendLine("gyro  (scaled, unit unconfirmed): %.3f %.3f %.3f".format(it.gx, it.gy, it.gz))
                        appendLine("accel (scaled, upstream: g):      %.3f %.3f %.3f".format(it.ax, it.ay, it.az))
                        appendLine("temp: %.1f C".format(it.temperatureC))
                    }
                }
            }
        }
    }

    private fun exportLogs() {
        val fullLog = buildString {
            appendLine("═══ GeXRture Diagnostic Export ═══")
            appendLine("Timestamp: ${java.util.Date()}")
            appendLine()
            if (previousRunReport.isNotEmpty()) {
                appendLine(previousRunReport)
                appendLine()
            }
            appendLine(binding.tvDeviceInfo.text)
            appendLine()
            appendLine("═══ USB Status ═══")
            appendLine(binding.tvUsbStatus.text)
            appendLine()
            appendLine("═══ Displays ═══")
            appendLine(binding.tvDisplayInfo.text)
            appendLine()
            appendLine("═══ Sensor ═══")
            appendLine(binding.tvSensorStatus.text)
            appendLine()
            appendLine("═══ USB Log ═══")
            appendLine(usbManager.getLogSnapshot())
            appendLine()
            appendLine("═══ Display Log ═══")
            appendLine(displayEnumerator.getLogSnapshot())
            appendLine()
            appendLine("═══ Handshake/IMU ═══")
            appendLine(binding.tvImuStatus.text)
            imuSession?.let {
                appendLine()
                appendLine("═══ Handshake/IMU Log ═══")
                // The file also holds the session cut short by an unplug before an auto-resume.
                val file = GeXRtureApp.sessionLogFile(application)
                val text = try { file.readText().takeLast(30_000) } catch (_: Exception) { "" }
                appendLine(text.ifEmpty { it.getLogSnapshot() })
            }
            appendLine()
            val reader = sensorReader
            if (reader != null) {
                appendLine("═══ Sensor Log ═══")
                appendLine(reader.getLogSnapshot())
            }
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, fullLog)
            putExtra(Intent.EXTRA_SUBJECT, "GeXRture Diagnostic Log")
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Export diagnostic log"))
    }
}
