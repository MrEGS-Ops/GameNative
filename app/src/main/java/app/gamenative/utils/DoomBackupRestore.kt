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
 * This helper moves only the known DOOM game-root entries into a persistent GameNative-DOOM/Game
 * folder and registers that folder as an imported Steam install. Moving inside public Downloads is
 * normally an atomic rename on the same filesystem, so the ~69 GiB install is not copied again.
 */
object DoomBackupRestore {
    const val DOOM_APP_ID = 379720

    private val gameRootEntries = listOf(
        ".DepotDownloader",
        ".DownloadInfo",
        "EmptySteamDepot",
        "_CommonRedist",
        "base",
        "virtualtextures",
        ".download_complete",
        ".steam_coldclient_used",
        ".vcredist_installed",
        "cChromeEditorLibrary.dll",
        "DOOMx64.exe",
        "DOOMx64vk.exe",
        "bink2w64.dll",
        "steam_api64.dll",
        "steam_controller_config.vdf",
        "superscriptx64.dll",
    )

    @Suppress("DEPRECATION")
    private fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    val restoredGameRoot: File
        get() = File(DoomLogStorage.root, "Game")

    fun hasRestorableBackup(): Boolean {
        val source = downloadsRoot()
        val destination = restoredGameRoot
        return hasCoreFiles(source) || hasCoreFiles(destination)
    }

    private fun hasCoreFiles(root: File): Boolean =
        File(root, "DOOMx64.exe").isFile &&
            File(root, "DOOMx64vk.exe").isFile &&
            File(root, "base").isDirectory &&
            File(root, "virtualtextures").isDirectory

    suspend fun restore(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val source = downloadsRoot()
            val destination = restoredGameRoot

            DoomLogStorage.ensureDirectories()
            check(destination.mkdirs() || destination.isDirectory) {
                "Could not create ${destination.absolutePath}"
            }

            if (!hasCoreFiles(destination)) {
                check(hasCoreFiles(source)) {
                    "DOOM backup was not found in Downloads"
                }

                val moved = mutableListOf<Pair<File, File>>()
                try {
                    for (name in gameRootEntries) {
                        val from = File(source, name)
                        if (!from.exists()) continue

                        val to = File(destination, name)
                        check(!to.exists()) {
                            "Restore destination already contains $name"
                        }
                        check(from.renameTo(to)) {
                            "Could not move $name into the persistent DOOM game folder"
                        }
                        moved += from to to
                    }
                } catch (error: Throwable) {
                    // Best-effort rollback. Never delete either side during rollback.
                    moved.asReversed().forEach { (original, movedFile) ->
                        if (movedFile.exists() && !original.exists()) {
                            movedFile.renameTo(original)
                        }
                    }
                    throw error
                }
            }

            check(hasCoreFiles(destination)) {
                "Restored DOOM folder is missing required game files"
            }

            MarkerUtils.addMarker(
                destination.absolutePath,
                app.gamenative.enums.Marker.DOWNLOAD_COMPLETE_MARKER,
            )

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
                    customInstallPath = destination.absolutePath,
                ),
            )

            destination
        }
    }
}
