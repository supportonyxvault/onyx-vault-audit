package com.onyxvault.messenger.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ONYX VAULT - Diagnostic Logger (temporaer, fuer Debug-Session)
 *
 * Schreibt Log-Events in app-internes File: filesDir/onyx_diag.log
 * - Max 500 KB (ring-buffer: aeltere Haelfte wird abgeschnitten)
 * - Thread-safe via synchronized
 * - Export via SettingsScreen -> "DIAGNOSE-LOG EXPORTIEREN"
 *
 * Nach Debug-Session: Diese Datei + alle log()-Aufrufe wieder entfernen.
 */
object DiagnosticLogger {
    private const val TAG = "DIAG"
    private const val MAX_SIZE_BYTES = 500 * 1024L  // 500 KB
    private const val LOG_FILENAME = "onyx_diag.log"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = File(context.filesDir, LOG_FILENAME)
            log("DIAG", "===== Session Start (pid=${android.os.Process.myPid()}) =====")
        }
    }

    fun log(tag: String, msg: String) {
        Log.i(tag, msg)
        synchronized(lock) {
            val f = logFile ?: return
            try {
                // Ring-Buffer: wenn > MAX, haelfte vorne wegwerfen
                if (f.length() > MAX_SIZE_BYTES) {
                    val content = f.readText()
                    val truncated = content.substring(content.length / 2).substringAfter('\n')
                    f.writeText("[truncated...]\n$truncated")
                }
                FileWriter(f, true).use { fw ->
                    fw.write("${dateFmt.format(Date())}  [$tag]  $msg\n")
                }
            } catch (e: IOException) {
                Log.w(TAG, "log write failed: ${e.message}")
            }
        }
    }

    fun getLogFile(context: Context): File? {
        synchronized(lock) {
            if (logFile == null) init(context)
            return logFile
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try { File(context.filesDir, LOG_FILENAME).delete() } catch (_: Exception) {}
            logFile = null
            init(context)
        }
    }
}
