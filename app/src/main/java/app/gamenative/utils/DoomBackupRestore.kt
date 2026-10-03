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
 * Quest can expose the copied files for reading while still refusing direct mkdir/rename writes
 * in public Downloads. To avoid another ~69 GiB copy and to keep the backup untouched, register
 * the existing Downloads root in-place as DOOM's imported Steam install.
 */
object DoomBackupRestore {
    const val DOOM_APP_ID = 379720

    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /**
     * The restored game root is deliberately the existing public Downloads root. The migration
     * must not move or delete the user's backed-up game files.
     */
    val restoredGameRoot: File
        get() = downloadsRoot()

    fun hasRestorableBackup(): Boolean = hasCoreFiles(downloadsRoot())

    private fun hasCoreFiles(root: File): Boolean =
        File(root, "DOOMx64.exe").isFile &&
            File(root, "DOOMx64vk.exe").isFile &&
            File(root, "base").isDirectory &&
            File(root, "virtualtextures").isDirectory

    suspend fun restore(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = downloadsRoot()

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
