package com.gexrture.ui

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import com.gexrture.panel.PanelController
import com.gexrture.panel.PanelMode
import com.gexrture.panel.PanelPresentation
import com.gexrture.panel.PanelRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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

    companion object {
        /** 1000 px of drag on the phone ≈ 50° of panel movement. */
        private const val DRAG_DEG_PER_PX = 0.05
    }

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

    // ── M2 spatial panel ──
    private val panelController = PanelController()
    private val panelRenderer by lazy {
        PanelRenderer(panelController, { imuSession?.pose() }) {
            panelLog("Auto-recentered pinned panel: new tracking frame (session restart or axis flip)")
        }
    }
    private var panelPresentation: PanelPresentation? = null
    /** Owner asked for the panel on the glasses; it is (re)shown whenever the display exists. */
    private var panelWanted = false
    /** "Flip nod/tilt": 180° about up for the IMU axes; applied to every new session. */
    private var flipAxes = false
    private val panelLogLines = ArrayDeque<String>()

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
        setupPanel()
        observeState()
        observePanel()

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
        panelPresentation?.dismiss()
        panelPresentation = null
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
            session.tracker.flipHorizontal = flipAxes
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

    // ── M2 spatial panel ──

    private fun setupPanel() {
        binding.btnOpenPanel.setOnClickListener { binding.panelControls.visibility = View.VISIBLE }
        binding.btnBackToDiag.setOnClickListener { binding.panelControls.visibility = View.GONE }

        binding.btnPanelTracking.setOnClickListener {
            val state = usbManager.state.value
            if (state !is UsbGlassesManager.ConnectionState.Connected) {
                Toast.makeText(this, "Connect the glasses first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resumeAfterReconnect = false
            panelLog("Tracking start requested from panel controls (keep glasses still ~1 s for gyro calibration)")
            startImuSession(state, "Started from panel controls")
        }

        binding.btnPanelShow.setOnClickListener {
            panelWanted = !panelWanted
            panelLog(if (panelWanted) "Panel ON requested" else "Panel OFF")
            updatePresentation()
        }

        binding.btnPanelPin.setOnClickListener {
            val pose = imuSession?.pose()
            if (panelController.state.mode == PanelMode.PINNED) {
                panelController.follow(pose?.q)
                panelLog("FOLLOW (panel keeps its current apparent position)")
            } else {
                if (pose == null) {
                    Toast.makeText(this, "Start tracking first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                panelController.pin(pose.q, pose.epoch)
                panelLog("PINNED at world yaw=%.1f pitch=%.1f (calibrated=${pose.calibrated})"
                    .format(panelController.state.yawDeg, panelController.state.pitchDeg))
            }
        }

        binding.btnPanelRecenter.setOnClickListener {
            val pose = imuSession?.pose()
            panelController.recenter(pose?.q, pose?.epoch ?: -1)
            panelLog("Recenter (${panelController.state.mode})")
        }

        binding.btnPanelSizeReset.setOnClickListener {
            panelController.resetSize()
            panelLog("Size reset to %.0f°".format(panelController.state.widthDeg))
        }
        binding.btnFovMinus.setOnClickListener { adjustFov(-0.5) }
        binding.btnFovPlus.setOnClickListener { adjustFov(0.5) }

        binding.btnFlipAxes.setOnClickListener {
            flipAxes = !flipAxes
            imuSession?.tracker?.flipHorizontal = flipAxes
            panelLog("Flip nod/tilt = $flipAxes (tracking frame reset; pinned panel will recenter)")
        }

        binding.controlPad.onDrag = { dx, dy ->
            // Drag right → panel right (yaw + is left); drag down → panel down.
            panelController.moveBy(-dx * DRAG_DEG_PER_PX, -dy * DRAG_DEG_PER_PX)
        }
        binding.controlPad.onPinch = { factor -> panelController.scaleBy(factor.toDouble()) }
    }

    private fun adjustFov(delta: Double) {
        panelController.adjustFov(delta)
        panelLog("Vertical FOV = %.1f° (if a pinned panel slides with the head turn, FOV is too large; against it, too small)"
            .format(panelController.state.vFovDeg))
    }

    private fun observePanel() {
        lifecycleScope.launch {
            displayEnumerator.displays.collect { updatePresentation() }
        }
        lifecycleScope.launch {
            var lastStatsLog = 0L
            while (true) {
                if (binding.panelControls.visibility == View.VISIBLE) updatePanelStatus()
                val now = System.currentTimeMillis()
                if (panelPresentation != null && now - lastStatsLog > 10_000) {
                    lastStatsLog = now
                    panelLog(panelStatsLine())
                }
                delay(250)
            }
        }
    }

    private fun updatePresentation() {
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (!panelWanted || display == null) {
            if (panelPresentation != null) {
                panelPresentation?.dismiss()
                panelPresentation = null
                panelLog(if (display == null) "Glasses display gone; panel hidden" else "Panel hidden")
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val current = panelPresentation
        if (current != null && current.isShowing && current.display.displayId == display.displayId) return
        current?.dismiss()
        val p = PanelPresentation(this, display, panelRenderer)
        p.setOnDismissListener { if (panelPresentation === p) panelPresentation = null }
        try {
            p.show()
            panelPresentation = p
            // Rendering needs this activity in the foreground; keep the phone screen on.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            panelLog("Panel shown on display ${display.displayId} \"${display.name}\"")
        } catch (e: WindowManager.InvalidDisplayException) {
            panelLog("Could not show panel on display ${display.displayId}: ${e.message}")
        }
    }

    private fun panelStatsLine(): String {
        val r = panelRenderer.stats
        val st = panelController.state
        val head = r.head?.let { PanelController.headYpr(it) }
        return "PANEL ${st.mode} yaw=%.1f pitch=%.1f w=%.0f° vFov=%.1f | fps=%.1f maxFrame=%.1fms poseAge avg=%.2f max=%.2fms | track=${r.trackingState} head=%s | surface=${r.surfaceWidth}x${r.surfaceHeight}"
            .format(
                st.yawDeg, st.pitchDeg, st.widthDeg, st.vFovDeg,
                r.fps, r.maxFrameIntervalMs, r.poseAgeAvgMs, r.poseAgeMaxMs,
                head?.let { "(y=%.1f p=%.1f r=%.1f)".format(it.first, it.second, it.third) } ?: "none",
            )
    }

    private fun updatePanelStatus() {
        val st = panelController.state
        val r = panelRenderer.stats
        val pose = imuSession?.pose()
        val phase = imuSession?.stats?.value?.phase
        binding.btnPanelPin.text = if (st.mode == PanelMode.PINNED) "↩ Follow" else "📌 Pin"
        binding.btnPanelShow.text = if (panelWanted) "Hide panel" else "Show on glasses"
        binding.btnFlipAxes.text = if (flipAxes) "Flip: ON" else "Flip nod/tilt"
        binding.btnPanelTracking.isEnabled = usbManager.state.value is UsbGlassesManager.ConnectionState.Connected
        binding.tvPanelStatus.text = buildString {
            appendLine("Tracking: ${phase ?: "not started"}  pose=${
                when {
                    pose == null -> "none"
                    !pose.calibrated -> "CALIBRATING (keep still)"
                    else -> "calibrated"
                }
            }")
            pose?.let {
                val (y, p, ro) = PanelController.headYpr(it.q)
                appendLine("Head yaw/pitch/roll: %.1f / %.1f / %.1f°".format(y, p, ro))
                appendLine("Gyro bias: %.2f %.2f %.2f °/s".format(it.biasDegPerS.x, it.biasDegPerS.y, it.biasDegPerS.z))
            }
            appendLine("Panel: ${st.mode}  yaw %.1f°  pitch %.1f°  width %.0f°".format(st.yawDeg, st.pitchDeg, st.widthDeg))
            appendLine("Glasses vFOV: %.1f° (upstream 50° diag claim → 25.8°)".format(st.vFovDeg))
            if (panelPresentation != null) {
                appendLine("Render: %.1f fps  max frame %.1f ms  pose age %.2f/%.2f ms  %dx%d"
                    .format(r.fps, r.maxFrameIntervalMs, r.poseAgeAvgMs, r.poseAgeMaxMs, r.surfaceWidth, r.surfaceHeight))
                append("In glasses: ${r.trackingState}")
            } else {
                append("Panel not shown (${if (panelWanted) "waiting for glasses display" else "press Show on glasses"})")
            }
        }
    }

    private fun panelLog(msg: String) {
        val line = "[${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}] $msg"
        synchronized(panelLogLines) {
            panelLogLines.addLast(line)
            if (panelLogLines.size > 500) panelLogLines.removeFirst()
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
            appendLine("═══ Spatial panel (M2) ═══")
            appendLine(panelStatsLine())
            appendLine("flipAxes=$flipAxes panelWanted=$panelWanted")
            synchronized(panelLogLines) { panelLogLines.forEach { appendLine(it) } }
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
