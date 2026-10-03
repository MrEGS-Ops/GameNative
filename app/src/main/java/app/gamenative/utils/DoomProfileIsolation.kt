package app.gamenative.utils

import java.io.File

/**
 * One-launch, reversible DOOM PROFILE isolation.
 *
 * The original PROFILE is copied to public Downloads first, then renamed in-place.
 * A fresh PROFILE directory is created for the test. On normal GameNative exit the
 * original is restored automatically. If GameNative itself is killed, the next DOOM
 * launch restores any stranded original before doing anything else.
 */
object DoomProfileIsolation {
    data class Outcome(
        val changed: Boolean,
        val message: String,
    )

    private const val ORIGINAL_BACKUP_NAME = "PROFILE.gamenative-original"
    private const val ACTIVE_MARKER_NAME = ".gamenative-profile-isolation"

    private fun profileCandidates(containerRoot: File): List<File> {
        val usersRoot = File(containerRoot, ".wine/drive_c/users")
        return usersRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { windowsUser ->
                val saveUsers = File(
                    windowsUser,
                    "Saved Games/id Software/DOOM/base/savegame.user",
                )
                saveUsers.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { steamUser -> File(steamUser, "PROFILE") }
                    ?: emptyList()
            }
            ?: emptyList()
    }

    private fun strandedBackups(containerRoot: File): List<File> {
        val usersRoot = File(containerRoot, ".wine/drive_c/users")
        if (!usersRoot.isDirectory) return emptyList()
        return usersRoot.walkTopDown()
            .maxDepth(10)
            .filter { it.isDirectory && it.name == ORIGINAL_BACKUP_NAME }
            .toList()
    }

    private fun publicBackupRoot(label: String): File =
        File(
            DoomLogStorage.backup,
            "ProfileIsolation/${label}_${DoomLogStorage.timestamp()}",
        ).apply { mkdirs() }

    private fun relativeLabel(profile: File): String {
        val steamUser = profile.parentFile?.name ?: "steam-user"
        val windowsUser = profile.parentFile
            ?.parentFile
            ?.parentFile
            ?.parentFile
            ?.parentFile
            ?.parentFile
            ?.name
            ?: "windows-user"
        return "$windowsUser/$steamUser"
    }

    /**
     * Restores any original PROFILE left behind by a previous isolation run.
     * The temporary test PROFILE is preserved in public Downloads when possible.
     */
    fun restoreIfNeeded(containerRoot: File): Outcome {
        val backups = strandedBackups(containerRoot)
        if (backups.isEmpty()) return Outcome(false, "No isolated PROFILE to restore")

        val recoveredRoot = publicBackupRoot("TestResult")
        var restored = 0
        val failures = mutableListOf<String>()

        backups.forEach { originalBackup ->
            val steamUserDir = originalBackup.parentFile ?: return@forEach
            val profile = File(steamUserDir, "PROFILE")
            val marker = File(steamUserDir, ACTIVE_MARKER_NAME)

            try {
                if (profile.exists()) {
                    val archive = File(
                        recoveredRoot,
                        "${steamUserDir.parentFile?.parentFile?.parentFile?.parentFile?.parentFile?.name ?: "xuser"}/${steamUserDir.name}/PROFILE",
                    )
                    archive.parentFile?.mkdirs()
                    val copied = runCatching {
                        profile.copyRecursively(archive, overwrite = true)
                    }.getOrDefault(false)
                    if (copied) {
                        profile.deleteRecursively()
                    } else {
                        val fallback = File(
                            steamUserDir,
                            "PROFILE.gamenative-test-${DoomLogStorage.timestamp()}",
                        )
                        if (!profile.renameTo(fallback)) {
                            throw IllegalStateException("Could not preserve temporary test PROFILE")
                        }
                    }
                }

                if (!originalBackup.renameTo(profile)) {
                    throw IllegalStateException("Could not restore original PROFILE")
                }
                marker.delete()
                restored++
            } catch (t: Throwable) {
                failures += "${steamUserDir.name}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        return if (failures.isEmpty()) {
            Outcome(restored > 0, "Restored $restored original PROFILE folder(s)")
        } else {
            Outcome(
                restored > 0,
                "Restored $restored PROFILE folder(s); failures=${failures.joinToString("; ")}",
            )
        }
    }

    /**
     * Arms a fresh PROFILE for this launch only. The original is copied to public
     * Downloads before it is renamed, so there are two recoverable copies during the test.
     */
    fun begin(containerRoot: File): Outcome {
        val stale = restoreIfNeeded(containerRoot)
        val profiles = profileCandidates(containerRoot).filter { it.isDirectory }
        if (profiles.isEmpty()) {
            return Outcome(
                stale.changed,
                "No existing DOOM PROFILE found to isolate",
            )
        }

        val backupRoot = publicBackupRoot("Original")
        var isolated = 0
        val failures = mutableListOf<String>()

        profiles.forEach { profile ->
            val steamUserDir = profile.parentFile ?: return@forEach
            val siblingBackup = File(steamUserDir, ORIGINAL_BACKUP_NAME)
            val marker = File(steamUserDir, ACTIVE_MARKER_NAME)

            try {
                if (siblingBackup.exists()) {
                    throw IllegalStateException("Original backup already exists")
                }

                val publicCopy = File(backupRoot, "${relativeLabel(profile)}/PROFILE")
                publicCopy.parentFile?.mkdirs()
                if (!profile.copyRecursively(publicCopy, overwrite = true)) {
                    throw IllegalStateException("Public backup copy failed")
                }

                if (!profile.renameTo(siblingBackup)) {
                    throw IllegalStateException("Could not move original PROFILE aside")
                }

                if (!profile.mkdirs() && !profile.isDirectory) {
                    siblingBackup.renameTo(profile)
                    throw IllegalStateException("Could not create fresh PROFILE")
                }

                marker.writeText(
                    "Original public backup: ${publicCopy.absolutePath}\n" +
                        "Created: ${DoomLogStorage.timestamp()}\n",
                )
                isolated++
            } catch (t: Throwable) {
                failures += "${steamUserDir.name}: ${t.message ?: t.javaClass.simpleName}"
            }
        }

        return if (failures.isEmpty()) {
            Outcome(
                isolated > 0,
                "Isolated $isolated PROFILE folder(s); originals backed up to ${backupRoot.absolutePath}",
            )
        } else {
            Outcome(
                isolated > 0,
                "Isolated $isolated PROFILE folder(s); failures=${failures.joinToString("; ")}",
            )
        }
    }
}
