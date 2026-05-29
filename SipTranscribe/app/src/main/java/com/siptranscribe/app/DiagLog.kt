package com.siptranscribe.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny persistent diagnostic ring-buffer.
 *
 * The existing Diagnose dialog reads `logcat -d`, but several OEM ROMs
 * (the Lenovo Tab Plus among them) refuse to let an app read its own
 * logcat buffer — so transient failures like an Azure HTTP 500 during
 * summary generation vanish with no trace the caregiver can recover.
 *
 * This writes a small, capped, plain-text log into the app's private
 * files dir that always survives, independent of logcat. Components that
 * fail in ways the user should be able to report (summary generation,
 * STT errors) append a line here; [MainActivity.showDiagnosticsDialog]
 * shows it above the logcat tail.
 */
object DiagLog {
    private const val FILE = "diag_log.txt"
    private const val MAX_LINES = 300

    @Synchronized
    fun log(context: Context, message: String) {
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val f = File(context.filesDir, FILE)
            val existing = if (f.exists()) f.readLines() else emptyList()
            val lines = (existing + "$ts  $message").takeLast(MAX_LINES)
            f.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
            // Diagnostics must never crash the caller.
        }
    }

    fun read(context: Context): String = try {
        File(context.filesDir, FILE).let { if (it.exists()) it.readText() else "" }
    } catch (_: Exception) {
        ""
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE).delete() } catch (_: Exception) {}
    }
}
