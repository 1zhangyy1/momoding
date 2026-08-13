package app.momoding.core.update

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class AppUpdateDownloader(
    private val application: Application,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(UPDATE_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build(),
) : AppUpdateDownload {
    private val resumableDownloader = ResumableFileDownloader(client)

    override suspend fun download(
        release: AppRelease,
        onProgress: (Int?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        requireTrustedReleaseUrl(release.apk.downloadUrl)
        requireTrustedReleaseUrl(release.checksum.downloadUrl)
        val files = AppUpdateFileStore(
            File(application.cacheDir, UPDATE_DIRECTORY),
        ).prepareFor(release)
        val expectedChecksum = downloadChecksum(release)
        val destination = files.destination
        if (
            destination.isFile &&
            destination.length() == release.apk.sizeBytes &&
            sha256(destination) == expectedChecksum
        ) {
            return@withContext try {
                validatePackage(destination, release)
                destination
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
        destination.delete()
        val partial = files.partial
        val partialBinding = files.binding
        prepareBoundPartialDownload(
            partial = partial,
            binding = partialBinding,
            expectedChecksum = expectedChecksum,
            expectedSize = release.apk.sizeBytes,
        )
        if (!partial.isFile || partial.length() < release.apk.sizeBytes) {
            resumableDownloader.download(release.apk, partial, onProgress)
        }
        val actualChecksum = sha256(partial)
        if (actualChecksum != expectedChecksum) {
            partial.delete()
            partialBinding.delete()
            error("Downloaded APK checksum does not match the release")
        }
        check(partial.renameTo(destination)) { "Could not finalize the downloaded APK" }
        try {
            validatePackage(destination, release)
            partialBinding.delete()
            destination
        } catch (error: Throwable) {
            destination.delete()
            partialBinding.delete()
            throw error
        }
    }

    private fun downloadChecksum(release: AppRelease): String {
        val request = Request.Builder()
            .url(release.checksum.downloadUrl)
            .header("User-Agent", "Momoding-Android-Updater")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Checksum download returned HTTP ${response.code}")
            val body = response.body
            val length = body.contentLength()
            check(length <= MAX_CHECKSUM_BYTES) { "Release checksum file is too large" }
            val text = body.charStream().readText().take(MAX_CHECKSUM_BYTES.toInt() + 1)
            check(text.length <= MAX_CHECKSUM_BYTES) { "Release checksum file is too large" }
            val match = CHECKSUM_LINE.find(text) ?: error("Release checksum is invalid")
            check(match.groupValues[2] == release.apk.name) { "Release checksum names another APK" }
            match.groupValues[1].lowercase()
        }
    }

    private fun validatePackage(apk: File, release: AppRelease) {
        val packageManager = application.packageManager
        val archive = packageManager.archivePackageInfo(apk)
            ?: error("Downloaded file is not a readable APK")
        check(archive.packageName == application.packageName) {
            "Downloaded APK belongs to a different app"
        }
        check(archive.versionName == release.versionName) {
            "Downloaded APK version does not match the release"
        }
        val installed = packageManager.installedPackageInfo(application.packageName)
        check(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) {
            "Downloaded APK is not newer than this installation"
        }
        val installedSigners = installed.currentSignerDigests()
        val archiveSigners = archive.currentSignerDigests()
        check(installedSigners.isNotEmpty() && installedSigners == archiveSigners) {
            "Downloaded APK signature does not match this installation"
        }
    }

    private fun PackageManager.archivePackageInfo(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageManager.installedPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageInfo.currentSignerDigests(): Set<String> {
        val signing = signingInfo ?: return emptySet()
        return signing.apkContentsSigners.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun requireTrustedReleaseUrl(raw: String) {
        val url = raw.toHttpUrlOrNull()
            ?: throw IOException("Release download URL is invalid")
        check(url.isHttps && url.host == "github.com") { "Release download URL is not trusted" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val UPDATE_DIRECTORY = "app-updates/v1"
        const val MAX_CHECKSUM_BYTES = 4L * 1024L
        const val UPDATE_CALL_TIMEOUT_MINUTES = 30L
        val CHECKSUM_LINE = Regex("(?m)^([0-9a-fA-F]{64})[ \\t]+\\*?([^\\r\\n]+)$")
    }
}
