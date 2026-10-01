package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-updater for the custom MrEGS-Ops/GameNative DOOM Quest releases.
 *
 * Only releases whose tag starts with doom-v are considered. The APK must pass
 * package-name, version-code, SHA-256 and signing-certificate checks before the
 * Android package installer is opened.
 */
object UpdateManager {
    private const val RELEASES_URL =
        "https://api.github.com/repos/MrEGS-Ops/GameNative/releases?per_page=30"
    private const val TAG_PREFIX = "doom-v"
    private const val MANIFEST_NAME = "update.json"
    private const val APK_NAME = "GameNative-DOOM.apk"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val sha256: String,
        val releaseNotes: String,
    )

    fun checkForUpdate(): UpdateInfo? {
        val releases = JSONArray(httpGetText(RELEASES_URL))
        var selectedRelease: JSONObject? = null

        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            val tag = release.optString("tag_name")
            if (!release.optBoolean("draft", false) && tag.startsWith(TAG_PREFIX)) {
                selectedRelease = release
                break
            }
        }

        val release = selectedRelease ?: return null
        val assets = release.getJSONArray("assets")
        var manifestUrl: String? = null
        var apkUrl: String? = null

        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.getString("name")) {
                MANIFEST_NAME -> manifestUrl = asset.getString("browser_download_url")
                APK_NAME -> apkUrl = asset.getString("browser_download_url")
            }
        }

        require(!manifestUrl.isNullOrBlank()) { "Release is missing $MANIFEST_NAME" }
        require(!apkUrl.isNullOrBlank()) { "Release is missing $APK_NAME" }

        val manifest = JSONObject(httpGetText(manifestUrl!!))
        val remoteCode = manifest.getLong("versionCode")
        if (remoteCode <= BuildConfig.VERSION_CODE.toLong()) return null

        return UpdateInfo(
            versionName = manifest.getString("versionName"),
            versionCode = remoteCode,
            apkUrl = apkUrl!!,
            sha256 = manifest.getString("sha256"),
            releaseNotes = release.optString("body"),
        )
    }

    fun downloadAndVerify(context: Context, update: UpdateInfo): File {
        DoomLogStorage.ensureDirectories()
        val target = File(
            DoomLogStorage.updates,
            "GameNative-DOOM-${update.versionName}.apk",
        )

        download(update.apkUrl, target)

        val actualHash = sha256(target)
        if (!actualHash.equals(update.sha256, ignoreCase = true)) {
            target.delete()
            throw SecurityException("APK SHA-256 does not match release manifest")
        }

        verifyPackageAndCertificate(context, target, update.versionCode)
        return target
    }

    fun ensureInstallPermission(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return false
        }
        return true
    }

    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )

        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            },
        )
    }

    private fun verifyPackageAndCertificate(
        context: Context,
        apk: File,
        expectedVersionCode: Long,
    ) {
        val pm = context.packageManager
        val archive = getArchivePackageInfo(pm, apk)
            ?: throw SecurityException("Unable to read update APK")

        if (archive.packageName != context.packageName) {
            apk.delete()
            throw SecurityException(
                "Update package mismatch: ${archive.packageName} != ${context.packageName}",
            )
        }

        val archiveVersionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode
            else @Suppress("DEPRECATION") archive.versionCode.toLong()

        if (archiveVersionCode != expectedVersionCode) {
            apk.delete()
            throw SecurityException(
                "Update version mismatch: APK=$archiveVersionCode manifest=$expectedVersionCode",
            )
        }

        val installedVersionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }

        if (archiveVersionCode <= installedVersionCode) {
            apk.delete()
            throw SecurityException("Update version is not newer than installed version")
        }

        val installedCert = certificateForInstalledApp(pm, context.packageName)
        val archiveCert = certificateForArchive(pm, apk)
        if (!installedCert.contentEquals(archiveCert)) {
            apk.delete()
            throw SecurityException("Update APK signing certificate does not match installed app")
        }
    }

    @Suppress("DEPRECATION")
    private fun getArchivePackageInfo(
        pm: PackageManager,
        apk: File,
    ) = pm.getPackageArchiveInfo(
        apk.absolutePath,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        },
    )

    @Suppress("DEPRECATION")
    private fun certificateForInstalledApp(
        pm: PackageManager,
        packageName: String,
    ): ByteArray {
        val info = pm.getPackageInfo(
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            },
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo!!.apkContentsSigners.first().toByteArray()
        } else {
            info.signatures!!.first().toByteArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun certificateForArchive(
        pm: PackageManager,
        apk: File,
    ): ByteArray {
        val info = getArchivePackageInfo(pm, apk)
            ?: throw SecurityException("Unable to inspect APK signing certificate")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo!!.apkContentsSigners.first().toByteArray()
        } else {
            info.signatures!!.first().toByteArray()
        }
    }

    private fun download(url: String, target: File) {
        target.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "GameNative-DOOM-Updater")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "GameNative-DOOM-Updater")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
