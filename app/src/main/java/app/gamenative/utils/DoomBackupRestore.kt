package app.gamenative.utils

import android.os.Environment
import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.service.SteamService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time migration helper for the Quest DOOM standalone build.
 *
 * The old debug-signed build was unable to update in-place to the permanent signer, so the
 * existing DOOM install was copied to the top level of public Downloads before uninstalling it.
 *
 * If that backup exists, register it in-place without moving or deleting it. If no backup exists,
 * a clean Steam install uses its own persistent folder under public Downloads.
 */
object DoomBackupRestore {
    const val DOOM_APP_ID = 379720

    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * Existing migrated backup location. The backup was copied loose into Downloads root.
     */
    val restoredGameRoot: File
        get() = downloadsRoot()

    /**
     * Dedicated location for a brand-new DOOM download when no migrated backup is present.
     * The normal GameNative install flow requests all-files/storage permission before writing here.
     */
    val cleanInstallGameRoot: File
        get() = File(downloadsRoot(), "GameNative-DOOM/Game")

    fun hasRestorableBackup(): Boolean = hasCoreFiles(restoredGameRoot)

    private fun hasCoreFiles(root: File): Boolean =
        File(root, "DOOMx64.exe").isFile &&
            File(root, "DOOMx64vk.exe").isFile &&
            File(root, "base").isDirectory &&
            File(root, "virtualtextures").isDirectory

    suspend fun restore(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = restoredGameRoot

            check(hasCoreFiles(source)) {
                "DOOM backup was not found in Downloads"
            }

            // The original backup already contains this marker. Only try to recreate it if it is
            // unexpectedly absent; do not move, overwrite, or delete any backed-up game data.
            val marker = File(source, ".download_complete")
            if (!marker.isFile) {
                check(marker.createNewFile()) {
                    "DOOM backup is present, but the install marker could not be created"
                }
            }

            val service = SteamService.instance ?: error("Steam service is not ready")
            val depotIds = SteamService
                .getMainAppDepots(DOOM_APP_ID, PrefManager.containerLanguage)
                .filterValues { it.dlcAppId == SteamService.INVALID_APP_ID }
                .keys
                .sorted()

            service.appInfoDao.insert(
                AppInfo(
                    id = DOOM_APP_ID,
                    isDownloaded = true,
                    downloadedDepots = depotIds,
                    dlcDepots = emptyList(),
                    branch = "public",
                    customInstallPath = source.absolutePath,
                ),
            )

            source
        }
    }
}
