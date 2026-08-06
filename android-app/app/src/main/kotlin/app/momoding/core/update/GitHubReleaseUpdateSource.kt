package app.momoding.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

internal class GitHubReleaseUpdateSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val releasesUrl: String = DEFAULT_RELEASES_URL,
) : AppUpdateSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Momoding-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub Releases returned HTTP ${response.code}")
            }
            val body = response.body.string()
            json.decodeFromString<List<GitHubRelease>>(body)
                .asSequence()
                .filterNot(GitHubRelease::draft)
                .mapNotNull { release -> release.toAppRelease() }
                .maxByOrNull { ReleaseVersion.parse(it.versionName) ?: return@maxByOrNull MIN_VERSION }
        }
    }

    private fun GitHubRelease.toAppRelease(): AppRelease? {
        val version = ReleaseVersion.parse(tagName) ?: return null
        val versionName = tagName.removePrefix("v")
        val expectedApkName = "momoding-$versionName.apk"
        val apk = assets.singleOrNull { it.name == expectedApkName } ?: return null
        val checksum = assets.singleOrNull { it.name == "$expectedApkName.sha256" } ?: return null
        if (apk.size !in 1..MAX_APK_BYTES || checksum.size !in 1..MAX_CHECKSUM_BYTES) return null
        return AppRelease(
            versionName = versionName,
            tagName = tagName,
            title = name?.take(MAX_TITLE_CHARS)?.ifBlank { tagName } ?: tagName,
            notes = body.orEmpty().take(MAX_NOTES_CHARS),
            publishedAt = publishedAt,
            apk = apk.toAsset(),
            checksum = checksum.toAsset(),
        ).also { check(version == ReleaseVersion.parse(it.versionName)) }
    }

    private fun GitHubAsset.toAsset() = AppReleaseAsset(
        name = name,
        downloadUrl = downloadUrl,
        sizeBytes = size,
    )

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long,
    )

    private companion object {
        const val DEFAULT_RELEASES_URL =
            "https://api.github.com/repos/1zhangyy1/momoding/releases?per_page=10"
        const val MAX_APK_BYTES = 250L * 1024L * 1024L
        const val MAX_CHECKSUM_BYTES = 4L * 1024L
        const val MAX_TITLE_CHARS = 160
        const val MAX_NOTES_CHARS = 4_000
        val MIN_VERSION = checkNotNull(ReleaseVersion.parse("0.0.0-0"))
    }
}
