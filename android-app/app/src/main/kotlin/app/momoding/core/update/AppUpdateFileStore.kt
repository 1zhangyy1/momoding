package app.momoding.core.update

import java.io.File

internal class AppUpdateFileStore(
    private val directory: File,
) {
    data class Files(
        val destination: File,
        val partial: File,
        val binding: File,
    )

    fun prepareFor(release: AppRelease): Files {
        val expectedName = "momoding-${release.versionName}.apk"
        require(ReleaseVersion.parse(release.versionName) != null) { "Release version is invalid" }
        require(release.apk.name == expectedName) { "Release APK name is invalid" }
        require(release.checksum.name == "$expectedName.sha256") { "Release checksum name is invalid" }
        check(directory.mkdirs() || directory.isDirectory) { "Could not create the update cache" }

        val activeNames = setOf(expectedName, "$expectedName.part", "$expectedName.part.sha256")
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.matches(OWNED_UPDATE_FILE) && file.name !in activeNames) {
                check(file.delete()) { "Could not remove a stale update download" }
            }
        }
        File(directory, "$expectedName.part.sha256.tmp").delete()
        return Files(
            destination = File(directory, expectedName),
            partial = File(directory, "$expectedName.part"),
            binding = File(directory, "$expectedName.part.sha256"),
        )
    }

    private companion object {
        val OWNED_UPDATE_FILE = Regex(
            "^momoding-[0-9A-Za-z.+-]+\\.apk(?:\\.part(?:\\.sha256(?:\\.tmp)?)?)?$",
        )
    }
}

internal fun prepareBoundPartialDownload(
    partial: File,
    binding: File,
    expectedChecksum: String,
    expectedSize: Long,
) {
    val boundChecksum = binding
        .takeIf { it.isFile && it.length() <= MAX_PARTIAL_BINDING_BYTES }
        ?.readText()
        ?.trim()
    if (boundChecksum != expectedChecksum || partial.length() > expectedSize) {
        check(!partial.exists() || partial.delete()) { "Could not reset a stale partial download" }
        check(!binding.exists() || binding.delete()) { "Could not reset a stale partial binding" }
    }
    if (!binding.isFile) {
        val temporary = File(binding.parentFile, "${binding.name}.tmp")
        temporary.writeText("$expectedChecksum\n")
        binding.delete()
        check(temporary.renameTo(binding)) { "Could not bind the partial update to this release" }
    }
}

private const val MAX_PARTIAL_BINDING_BYTES = 128L
