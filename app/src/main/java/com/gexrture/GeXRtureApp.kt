package com.gexrture

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records any uncaught exception to a local file before the process dies, so the next
 * diagnostic export can include it (the in-memory logs die with the process).
 * Native crashes (SIGSEGV) bypass this; for those use `adb logcat -b crash`.
 */
class GeXRtureApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                crashFile(this).writeText("Crash at $ts on thread ${thread.name}\n$trace")
            } catch (_: Throwable) {
                // Never let crash recording mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application): File = File(app.filesDir, "last-crash.txt")
        fun sessionLogFile(app: Application): File = File(app.filesDir, "last-session.log")
    }
}
