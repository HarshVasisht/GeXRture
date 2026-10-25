package com.gexrture.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enumerates and monitors external displays using Android DisplayManager.
 *
 * When XREAL glasses are connected via USB-C with DP Alt Mode, they should
 * appear as a secondary/external display. This class:
 * - Lists all displays (including the built-in one)
 * - Monitors for display add/remove/change events
 * - Records display properties for diagnostics
 */
class DisplayEnumerator(private val context: Context) {

    companion object {
        private const val TAG = "DisplayEnumerator"
    }

    data class DisplayInfo(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val densityDpi: Int,
        val flags: Int,
        val state: Int,
        val isDefault: Boolean,
        val isExternal: Boolean,
        val rotation: Int,
        val supportedRefreshRates: List<Float>,
    ) {
        val stateName: String
            get() = when (state) {
                Display.STATE_OFF -> "OFF"
                Display.STATE_ON -> "ON"
                Display.STATE_DOZE -> "DOZE"
                Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
                Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
                Display.STATE_VR -> "VR"
                else -> "UNKNOWN($state)"
            }

        val flagsDescription: String
            get() {
                val parts = mutableListOf<String>()
                if (flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) parts.add("PROTECTED_BUFFERS")
                if (flags and Display.FLAG_SECURE != 0) parts.add("SECURE")
                if (flags and Display.FLAG_PRIVATE != 0) parts.add("PRIVATE")
                if (flags and Display.FLAG_PRESENTATION != 0) parts.add("PRESENTATION")
                if (flags and Display.FLAG_ROUND != 0) parts.add("ROUND")
                return if (parts.isEmpty()) "none" else parts.joinToString(", ")
            }
    }

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _displays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    val displays: StateFlow<List<DisplayInfo>> = _displays.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            appendLog("Display ADDED: id=$displayId")
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            appendLog("Display REMOVED: id=$displayId")
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            // Fires very often on device (several per second around USB events) with no visible
            // property change; refreshDisplays only logs displays whose summary changed.
            refreshDisplays()
        }
    }

    fun register() {
        displayManager.registerDisplayListener(displayListener, null)
        appendLog("Display listener registered")
        refreshDisplays()
    }

    fun unregister() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun refreshDisplays() {
        val allDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val builtIn = defaultDisplay?.let { listOf(it) } ?: emptyList()

        // Combine built-in + presentation displays, deduplicating by ID
        val combined = (builtIn + allDisplays.toList())
            .distinctBy { it.displayId }
            .map { display ->
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getMetrics(metrics)

                // Modes repeat per resolution/config; keep distinct refresh rates only.
                val supportedModes = display.supportedModes
                    .map { Math.round(it.refreshRate * 100) / 100f }
                    .distinct()
                    .sortedDescending()

                DisplayInfo(
                    displayId = display.displayId,
                    name = display.name,
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    refreshRate = display.refreshRate,
                    densityDpi = metrics.densityDpi,
                    flags = display.flags,
                    state = display.state,
                    isDefault = display.displayId == Display.DEFAULT_DISPLAY,
                    isExternal = display.displayId != Display.DEFAULT_DISPLAY,
                    rotation = display.rotation,
                    supportedRefreshRates = supportedModes,
                )
            }

        _displays.value = combined
        val ids = combined.map { it.displayId }.toSet()
        lastSummaries.keys.retainAll(ids)
        combined.forEach { d ->
            val summary = "Display ${d.displayId}: \"${d.name}\" ${d.width}×${d.height} " +
                "@${"%.2f".format(d.refreshRate)}Hz dpi=${d.densityDpi} state=${d.stateName} " +
                "flags=[${d.flagsDescription}] external=${d.isExternal} " +
                "modes=${d.supportedRefreshRates}"
            if (lastSummaries[d.displayId] != summary) {
                lastSummaries[d.displayId] = summary
                appendLog(summary)
            }
        }
    }

    /** Last logged summary per display id, so unchanged displays are not re-logged. */
    private val lastSummaries = mutableMapOf<Int, String>()

    fun getLogSnapshot(): String = _log.value.joinToString("\n")

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        Log.d(TAG, msg)
        _log.value = (_log.value + line).takeLast(500)
    }
}
