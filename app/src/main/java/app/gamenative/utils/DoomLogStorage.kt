package app.gamenative.utils

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared storage layout for the DOOM standalone Quest build.
 *
 * Legacy XR targets SDK 28 and already requests legacy external-storage access,
 * so these files are intentionally kept in public Downloads where they survive
 * app crashes/reinstalls and are easy to retrieve from the Quest Files app.
 */
object DoomLogStorage {
    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    val root: File
        get() = File(downloadsRoot(), "GameNative-DOOM")

    val logs: File
        get() = File(root, "Logs")

    val appLogs: File
        get() = File(logs, "App")

    val crashLogs: File
        get() = File(logs, "Crash")

    val wineLogs: File
        get() = File(logs, "Wine")

    val debugRunLogs: File
        get() = File(logs, "DebugRun")

    val diagnosticLogs: File
        get() = File(logs, "Diagnostics")

    val updates: File
        get() = File(root, "Updates")

    val backup: File
        get() = File(root, "Backup")

    fun ensureDirectories() {
        listOf(
            root,
            logs,
            appLogs,
            crashLogs,
            wineLogs,
            debugRunLogs,
            diagnosticLogs,
            updates,
            backup,
        ).forEach { it.mkdirs() }
    }

    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun saveAppLog(text: String): File {
        ensureDirectories()
        return File(appLogs, "app_logs_${timestamp()}.txt").apply {
            writeText(text)
        }
    }

    fun newWineLog(appId: String, debugRun: Boolean): File {
        ensureDirectories()
        val prefix = if (debugRun) "debug_run" else "wine"
        val directory = if (debugRun) debugRunLogs else wineLogs
        return File(directory, "${prefix}_${appId}_${timestamp()}.log")
    }
}
