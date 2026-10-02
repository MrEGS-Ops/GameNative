package app.gamenative.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import app.gamenative.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight, crash-resilient diagnostics for DOOM (Steam app 379720).
 *
 * This deliberately avoids verbose Wine channels. It flushes every event so the
 * last samples remain in Downloads even if Horizon Shell or GameNative is killed.
 */
object DoomDiagnostics {
    private const val MB = 1024L * 1024L
    private const val MAX_ARCHIVES = 12

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var writer: BufferedWriter? = null
    private var sampler: Job? = null
    private var currentFile: File? = null
    private var logcatProcess: java.lang.Process? = null

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    fun start(context: Context, appId: String) {
        stop("starting-new-session")
        DoomLogStorage.ensureDirectories()
        val dir = DoomLogStorage.diagnosticLogs
        val current = File(dir, "doom_session_current.txt")
        val previous = File(dir, "doom_session_previous.txt")

        if (current.exists()) {
            runCatching { current.copyTo(previous, overwrite = true) }
            runCatching {
                current.copyTo(
                    File(dir, "doom_session_${DoomLogStorage.timestamp()}.txt"),
                    overwrite = false,
                )
            }
        }

        pruneArchives(dir)
        current.delete()
        currentFile = current
        writer = BufferedWriter(FileWriter(current, false))

        startSystemLogcat(dir)

        event("===== DOOM SESSION START =====")
        event("AppId=$appId")
        event("Package=${context.packageName}")
        event("Version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        event("Android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
        event("Device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        event("GameNativePid=${Process.myPid()} uid=${Process.myUid()}")

        sampler = scope.launch {
            while (isActive) {
                sampleMemory(context.applicationContext)
                delay(1000L)
            }
        }
    }

    fun event(message: String) {
        synchronized(lock) {
            runCatching {
                writer?.apply {
                    write("${stamp()}  $message\n")
                    flush()
                }
            }
        }
    }

    private fun sampleMemory(context: Context) {
        runCatching {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val systemMemory = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(systemMemory)

            val processMemory = Debug.MemoryInfo()
            Debug.getMemoryInfo(processMemory)

            val runtime = Runtime.getRuntime()
            val javaUsed = runtime.totalMemory() - runtime.freeMemory()
            val nativeHeap = Debug.getNativeHeapAllocatedSize()

            val wineProcesses = WineProcessSnapshotHelper.readFromProc()
            val wineRss = wineProcesses.sumOf { it.memoryUsage }
            val topWine = wineProcesses
                .sortedByDescending { it.memoryUsage }
                .take(6)
                .joinToString(",") {
                    "${it.name}[${it.pid}]=${it.memoryUsage / MB}MB"
                }

            event(
                buildString {
                    append("MEM ")
                    append("available=${systemMemory.availMem / MB}MB ")
                    append("total=${systemMemory.totalMem / MB}MB ")
                    append("threshold=${systemMemory.threshold / MB}MB ")
                    append("lowMemory=${systemMemory.lowMemory} ")
                    append("java=${javaUsed / MB}MB ")
                    append("native=${nativeHeap / MB}MB ")
                    append("pss=${processMemory.totalPss / 1024}MB ")
                    append("wineRss=${wineRss / MB}MB ")
                    append("wineCount=${wineProcesses.size}")
                    if (topWine.isNotBlank()) append(" topWine=$topWine")
                },
            )
        }.onFailure {
            event("MEM sampling failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun onTrimMemory(level: Int) {
        event("ANDROID onTrimMemory level=$level")
    }

    fun onLowMemory() {
        event("ANDROID onLowMemory")
    }

    fun stop(reason: String) {
        sampler?.cancel()
        sampler = null

        runCatching { logcatProcess?.destroy() }
        logcatProcess = null

        synchronized(lock) {
            runCatching {
                writer?.apply {
                    write("${stamp()}  ===== SESSION END: $reason =====\n")
                    flush()
                    close()
                }
            }
            writer = null
        }
    }

    fun currentFile(): File? = currentFile

    private fun startSystemLogcat(dir: File) {
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null

        val current = File(dir, "doom_system_current.log")
        val previous = File(dir, "doom_system_previous.log")

        if (current.exists()) {
            runCatching { current.copyTo(previous, overwrite = true) }
            current.delete()
        }

        runCatching {
            logcatProcess = ProcessBuilder(
                "logcat",
                "-b",
                "all",
                "-v",
                "threadtime",
            )
                .redirectOutput(current)
                .redirectErrorStream(true)
                .start()
        }.onFailure {
            event("SYSTEM logcat start failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun pruneArchives(dir: File) {
        val archives = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("doom_session_20") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        archives.drop(MAX_ARCHIVES).forEach { runCatching { it.delete() } }
    }
}
