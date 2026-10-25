package com.gexrture.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovers and manages USB permission for XREAL glasses.
 *
 * This class handles:
 * - Enumerating currently attached USB devices for XREAL vendor ID
 * - Requesting and tracking USB permission grants
 * - Listening for USB attach/detach broadcast events
 * - Opening a UsbDeviceConnection for transport use
 *
 * ⚠️ UNVERIFIED: USB descriptor matching is based on upstream-reported IDs.
 * The actual device may expose different interfaces/endpoints.
 */
class UsbGlassesManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbGlassesManager"
        private const val ACTION_USB_PERMISSION = "com.gexrture.USB_PERMISSION"
    }

    /** Observable connection state. */
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class DeviceFound(val device: UsbDevice, val hasPermission: Boolean) : ConnectionState()
        data object PermissionDenied : ConnectionState()
        data class Connected(val device: UsbDevice, val connection: UsbDeviceConnection) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var currentConnection: UsbDeviceConnection? = null

    // ── Broadcast receiver for USB events ──

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    appendLog("USB permission ${if (granted) "GRANTED" else "DENIED"} for ${device?.deviceName}")
                    if (granted && device != null) {
                        openConnection(device)
                    } else {
                        _state.value = ConnectionState.PermissionDenied
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB device attached")
                    scanForGlasses()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    appendLog("USB device detached: ${device?.deviceName}")
                    disconnect()
                }
            }
        }
    }

    // ── Public API ──

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        appendLog("USB receiver registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        disconnect()
    }

    /**
     * Scans currently attached USB devices for any matching XREAL vendor ID.
     * Returns a diagnostic summary of ALL attached USB devices for logging.
     */
    fun scanForGlasses(): List<UsbDeviceSummary> {
        val allDevices = usbManager.deviceList.values.toList()
        val summaries = allDevices.map { device ->
            UsbDeviceSummary(
                name = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                deviceSubclass = device.deviceSubclass,
                interfaceCount = device.interfaceCount,
                isXreal = XrealDeviceIds.isXrealDevice(device.vendorId),
                isXbx = XrealDeviceIds.isXbxDevice(device.vendorId, device.productId),
                modelName = XrealDeviceIds.modelName(device.vendorId, device.productId),
                hasPermission = usbManager.hasPermission(device),
                serialNumber = try { device.serialNumber } catch (_: SecurityException) { null },
            )
        }

        appendLog("USB scan: ${allDevices.size} device(s) found")
        summaries.forEach { s ->
            appendLog(
                "  ${s.name}: vid=0x${s.vendorId.toString(16)} pid=0x${s.productId.toString(16)} " +
                "${s.modelName} xreal=${s.isXreal} xbx=${s.isXbx} perm=${s.hasPermission} " +
                "class=${s.deviceClass} sub=${s.deviceSubclass} ifaces=${s.interfaceCount}"
            )
        }

        // Find the first XREAL device, preferring XBX models.
        val xbxDevice = allDevices.firstOrNull {
            XrealDeviceIds.isXbxDevice(it.vendorId, it.productId)
        }
        val xrealDevice = xbxDevice ?: allDevices.firstOrNull {
            XrealDeviceIds.isXrealDevice(it.vendorId)
        }

        val current = _state.value
        if (xrealDevice != null && current is ConnectionState.Connected &&
            current.device.deviceName == xrealDevice.deviceName
        ) {
            // Observed on device: the ATTACHED intent re-scan opened a second connection.
            appendLog("Already connected to ${xrealDevice.deviceName}; keeping existing connection")
            return summaries
        }

        if (xrealDevice != null) {
            val hasPerm = usbManager.hasPermission(xrealDevice)
            _state.value = ConnectionState.DeviceFound(xrealDevice, hasPerm)
            if (hasPerm) {
                openConnection(xrealDevice)
            } else {
                requestPermission(xrealDevice)
            }
        } else {
            _state.value = ConnectionState.Disconnected
            appendLog("No XREAL device found among attached USB devices")
        }

        return summaries
    }

    /**
     * Called synchronously before the connection is closed. Must stop every worker that may be
     * inside bulkTransfer on it; closing under an in-flight transfer can crash natively.
     */
    var beforeClose: (() -> Unit)? = null

    fun disconnect() {
        if (currentConnection != null) {
            try { beforeClose?.invoke() } catch (e: Exception) { Log.w(TAG, "beforeClose failed", e) }
        }
        currentConnection?.close()
        currentConnection = null
        _state.value = ConnectionState.Disconnected
        appendLog("Disconnected")
    }

    fun getLogSnapshot(): String = _log.value.joinToString("\n")

    // ── Internal ──

    private fun requestPermission(device: UsbDevice) {
        appendLog("Requesting USB permission for ${device.deviceName}")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        // Explicit package: Android 14+ throws for a mutable PendingIntent wrapping an implicit Intent.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun openConnection(device: UsbDevice) {
        val current = _state.value
        if (current is ConnectionState.Connected && current.device.deviceName == device.deviceName) {
            appendLog("Already connected to ${device.deviceName}; keeping existing connection")
            return
        }
        try {
            val conn = usbManager.openDevice(device)
            if (conn != null) {
                currentConnection = conn
                _state.value = ConnectionState.Connected(device, conn)
                appendLog("Connection opened: ${device.deviceName}")
                logDeviceDetails(device, conn)
            } else {
                _state.value = ConnectionState.Error("Failed to open USB device (null connection)")
                appendLog("ERROR: openDevice returned null")
            }
        } catch (e: Exception) {
            _state.value = ConnectionState.Error("Failed to open USB device: ${e.message}")
            appendLog("ERROR opening device: ${e.message}")
            Log.e(TAG, "Failed to open USB device", e)
        }
    }

    private fun logDeviceDetails(device: UsbDevice, conn: UsbDeviceConnection) {
        appendLog("  Device descriptor raw (first 18 bytes):")
        val rawDesc = conn.rawDescriptors
        if (rawDesc != null && rawDesc.size >= 18) {
            val hex = rawDesc.take(18).joinToString(" ") { "0x%02x".format(it) }
            appendLog("    $hex")
        } else {
            appendLog("    (unavailable or short: ${rawDesc?.size ?: 0} bytes)")
        }

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            appendLog(
                "  Interface $i: class=${iface.interfaceClass} " +
                "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} " +
                "endpoints=${iface.endpointCount}"
            )
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                appendLog(
                    "    EP $e: addr=0x${ep.address.toString(16)} " +
                    "dir=${if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                    "type=${ep.type} maxPkt=${ep.maxPacketSize} interval=${ep.interval}"
                )
            }
        }
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$ts] $msg"
        Log.d(TAG, msg)
        _log.value = _log.value + line
    }
}

/**
 * Diagnostic summary of a single USB device, used for UI display.
 */
data class UsbDeviceSummary(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val interfaceCount: Int,
    val isXreal: Boolean,
    val isXbx: Boolean,
    val modelName: String,
    val hasPermission: Boolean,
    val serialNumber: String?,
)
