package app.momoding.core.update

data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

data class AppRelease(
    val versionName: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val publishedAt: String?,
    val apk: AppReleaseAsset,
    val checksum: AppReleaseAsset,
)

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class UpToDate(val versionName: String) : AppUpdateUiState
    data class Available(val release: AppRelease) : AppUpdateUiState
    data class Downloading(
        val release: AppRelease,
        val progressPercent: Int?,
    ) : AppUpdateUiState
    data class Failed(
        val message: String,
        val release: AppRelease? = null,
    ) : AppUpdateUiState
}

internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1
        prerelease.indices.union(other.prerelease.indices).forEach { index ->
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            comparePrereleasePart(left, right).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    companion object {
        private val pattern = Regex(
            "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$",
        )

        fun parse(raw: String): ReleaseVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            return ReleaseVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                prerelease = match.groupValues[4]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                    .orEmpty(),
            )
        }

        private fun comparePrereleasePart(left: String, right: String): Int {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return when {
                leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}

internal fun interface AppUpdateSource {
    suspend fun latestRelease(): AppRelease?
}

internal fun interface AppUpdateDownload {
    suspend fun download(release: AppRelease, onProgress: (Int?) -> Unit): java.io.File
}
