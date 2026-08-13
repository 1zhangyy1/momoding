package app.momoding.core.update

import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

internal class ResumableFileDownloader(
    private val client: OkHttpClient,
) {
    fun download(
        asset: AppReleaseAsset,
        partial: File,
        onProgress: (Int?) -> Unit,
    ) {
        require(asset.sizeBytes in 1..MAX_DOWNLOAD_BYTES) { "Release APK size is invalid" }
        partial.parentFile?.mkdirs()

        var restarted = false
        while (true) {
            val offset = partial.takeIf(File::isFile)?.length().orZero()
            if (offset > asset.sizeBytes) {
                check(partial.delete()) { "Could not reset an invalid partial download" }
                restarted = true
                continue
            }
            onProgress(progress(offset, asset.sizeBytes))

            val request = Request.Builder()
                .url(asset.downloadUrl)
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Momoding-Android-Updater")
                .apply {
                    if (offset > 0L) header("Range", "bytes=$offset-")
                }
                .build()
            client.newCall(request).execute().use { response ->
                if (offset > 0L && response.code == HTTP_RANGE_NOT_SATISFIABLE && !restarted) {
                    check(partial.delete()) { "Could not reset a rejected partial download" }
                    restarted = true
                    return@use
                }
                if (!response.isSuccessful) {
                    error("APK download returned HTTP ${response.code}")
                }

                val append = when {
                    offset == 0L && response.code == HTTP_OK -> false
                    response.code == HTTP_PARTIAL -> {
                        requireValidContentRange(
                            raw = response.header("Content-Range"),
                            expectedStart = offset,
                            expectedTotal = asset.sizeBytes,
                        )
                        offset > 0L
                    }
                    offset > 0L && response.code == HTTP_OK -> false
                    else -> error("APK download returned an invalid range response")
                }
                val initialBytes = if (append) offset else 0L
                val body = response.body
                val responseBytes = body.contentLength().takeIf { it >= 0L }
                check(responseBytes == null || initialBytes + responseBytes <= asset.sizeBytes) {
                    "Release APK is larger than declared"
                }

                var total = initialBytes
                body.byteStream().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= asset.sizeBytes && total <= MAX_DOWNLOAD_BYTES) {
                                "Release APK is larger than declared"
                            }
                            output.write(buffer, 0, read)
                            onProgress(progress(total, asset.sizeBytes))
                        }
                    }
                }
                if (total != asset.sizeBytes) {
                    throw java.io.IOException("Downloaded APK is incomplete")
                }
                onProgress(100)
                return
            }
        }
    }

    private fun requireValidContentRange(
        raw: String?,
        expectedStart: Long,
        expectedTotal: Long,
    ) {
        val match = raw?.let(CONTENT_RANGE::matchEntire)
            ?: error("APK download returned an invalid Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: error("APK download returned an invalid range start")
        val end = match.groupValues[2].toLongOrNull()
            ?: error("APK download returned an invalid range end")
        val total = match.groupValues[3].toLongOrNull()
            ?: error("APK download returned an invalid range total")
        check(
            start == expectedStart &&
                end >= start &&
                end == expectedTotal - 1L &&
                total == expectedTotal,
        ) { "APK download returned an unexpected Content-Range" }
    }

    private fun progress(downloadedBytes: Long, totalBytes: Long): Int =
        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)

    private fun Long?.orZero(): Long = this ?: 0L

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val MAX_DOWNLOAD_BYTES = 250L * 1024L * 1024L
        val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
    }
}
