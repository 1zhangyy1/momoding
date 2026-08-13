package app.momoding.core.skills

import java.security.MessageDigest
import kotlinx.serialization.Serializable

const val MAX_SKILL_DOCUMENT_BYTES: Int = 64 * 1024
const val MAX_SKILL_DOCUMENT_UTF16_UNITS: Int = 64 * 1024
const val MAX_SKILL_RESOURCES: Int = 64
const val MAX_SKILL_PACKAGE_FILES: Int = 256
const val MAX_SKILL_PACKAGE_FILE_BYTES: Int = 4 * 1024 * 1024
const val MAX_SKILL_PACKAGE_TOTAL_BYTES: Int = 8 * 1024 * 1024
const val MAX_SKILL_RESOURCE_PATH_CHARS: Int = 512

private val SKILL_NAME_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

enum class SkillSource {
    BUNDLED,
    IMPORTED,
}

enum class SkillAvailability {
    AVAILABLE,
    UNAVAILABLE,
    ERROR,
}

@Serializable
data class PhoneLocalSkillResource(
    val name: String,
    val description: String,
    val content: String,
    val contentSha256: String,
    val disableModelInvocation: Boolean,
    val packageDigest: String = contentSha256,
    val packageFileCount: Int = 1,
)

data class SkillPackageFile(
    val relativePath: String,
    val mimeType: String,
    val content: ByteArray,
    val contentSha256: String,
)

data class SkillPackageFileDescriptor(
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val contentSha256: String,
)

data class SkillPackageDocument(
    val rootDisplayName: String,
    val skillDocument: SkillImportDocument,
    val files: List<SkillPackageFile>,
    val packageDigest: String,
)

data class SkillDocumentParseResult(
    val resource: PhoneLocalSkillResource,
    val availability: SkillAvailability,
    val diagnosticCode: String?,
    val diagnosticMessage: String?,
    val sourceDocumentSha256: String? = null,
)

fun interface PhoneLocalSkillParser {
    suspend fun parseSkillDocument(content: String): SkillDocumentParseResult
}

class SkillDocumentParseException(
    val code: String,
) : IllegalArgumentException(code)

internal fun requireValidSkillResource(resource: PhoneLocalSkillResource) {
    require(
        resource.name.length in 1..64 &&
            SKILL_NAME_PATTERN.matches(resource.name)
    ) { "SKILL_NAME_INVALID" }
    require(
        resource.description.isNotBlank() &&
            resource.description.length <= 1024 &&
            '\u0000' !in resource.description
    ) { "SKILL_DESCRIPTION_INVALID" }
    require(
        resource.content.length <= MAX_SKILL_DOCUMENT_UTF16_UNITS &&
            '\u0000' !in resource.content
    ) { "SKILL_CONTENT_INVALID" }
    require(
        SHA256_PATTERN.matches(resource.contentSha256) &&
            resource.contentSha256 == resource.content.sha256Utf8()
    ) { "SKILL_CONTENT_DIGEST_INVALID" }
    require(SHA256_PATTERN.matches(resource.packageDigest)) { "SKILL_PACKAGE_DIGEST_INVALID" }
    require(resource.packageFileCount in 1..MAX_SKILL_PACKAGE_FILES) {
        "SKILL_PACKAGE_FILE_COUNT_INVALID"
    }
}

internal fun requireValidSkillPackageFiles(files: List<SkillPackageFile>) {
    files.forEach { file ->
        require(
            SHA256_PATTERN.matches(file.contentSha256) &&
                file.contentSha256 == file.content.sha256()
        ) { "SKILL_PACKAGE_FILE_DIGEST_INVALID" }
    }
    requireValidSkillPackageDescriptors(files.map(SkillPackageFile::descriptor))
}

internal fun skillPackageDigest(files: List<SkillPackageFile>): String {
    requireValidSkillPackageFiles(files)
    return skillPackageDigestFromDescriptors(files.map(SkillPackageFile::descriptor))
}

internal fun skillPackageDigestFromDescriptors(files: List<SkillPackageFileDescriptor>): String {
    requireValidSkillPackageDescriptors(files)
    val canonical = buildString {
        files.sortedBy(SkillPackageFileDescriptor::relativePath).forEach { file ->
            append(file.relativePath.length)
            append(':')
            append(file.relativePath)
            append(file.mimeType.length)
            append(':')
            append(file.mimeType)
            append(file.byteSize)
            append(':')
            append(file.contentSha256)
        }
    }
    return canonical.sha256Utf8()
}

private fun requireValidSkillPackageDescriptors(files: List<SkillPackageFileDescriptor>) {
    require(files.size in 1..MAX_SKILL_PACKAGE_FILES) { "SKILL_PACKAGE_FILE_LIMIT_EXCEEDED" }
    require(files.map(SkillPackageFileDescriptor::relativePath).distinct().size == files.size) {
        "SKILL_PACKAGE_PATH_DUPLICATED"
    }
    var totalBytes = 0L
    files.forEach { file ->
        requireValidSkillPackageDescriptor(file)
        totalBytes += file.byteSize
        require(totalBytes <= MAX_SKILL_PACKAGE_TOTAL_BYTES) { "SKILL_PACKAGE_TOO_LARGE" }
    }
    require(files.any { it.relativePath == "SKILL.md" }) { "SKILL_PACKAGE_DOCUMENT_MISSING" }
}

internal fun requireValidSkillPackageDescriptor(file: SkillPackageFileDescriptor) {
    requireValidSkillResourcePath(file.relativePath)
    require(file.mimeType.isNotBlank() && file.mimeType.length <= 255 && '\u0000' !in file.mimeType) {
        "SKILL_PACKAGE_MIME_INVALID"
    }
    require(file.byteSize in 0..MAX_SKILL_PACKAGE_FILE_BYTES.toLong()) {
        "SKILL_PACKAGE_FILE_TOO_LARGE"
    }
    require(SHA256_PATTERN.matches(file.contentSha256)) { "SKILL_PACKAGE_FILE_DIGEST_INVALID" }
}

private fun SkillPackageFile.descriptor(): SkillPackageFileDescriptor = SkillPackageFileDescriptor(
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = content.size.toLong(),
    contentSha256 = contentSha256,
)

internal fun requireValidSkillResourcePath(path: String) {
    require(path.length in 1..MAX_SKILL_RESOURCE_PATH_CHARS && '\u0000' !in path) {
        "SKILL_RESOURCE_PATH_INVALID"
    }
    require(!path.startsWith('/') && !path.endsWith('/') && '\\' !in path) {
        "SKILL_RESOURCE_PATH_INVALID"
    }
    val segments = path.split('/')
    require(segments.size <= 12 && segments.all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." &&
            segment.length <= 128 && '\u0000' !in segment
    }) { "SKILL_RESOURCE_PATH_INVALID" }
}

internal fun skillResourceSetDigest(resources: List<PhoneLocalSkillResource>): String {
    require(resources.size <= MAX_SKILL_RESOURCES) { "SKILL_RESOURCE_LIMIT_EXCEEDED" }
    resources.forEach(::requireValidSkillResource)
    val sorted = resources.sortedBy(PhoneLocalSkillResource::name)
    require(sorted.map(PhoneLocalSkillResource::name).distinct().size == sorted.size) {
        "SKILL_NAME_DUPLICATED"
    }
    val canonical = buildString {
        sorted.forEach { resource ->
            append(resource.name.length)
            append(':')
            append(resource.name)
            append(resource.description.length)
            append(':')
            append(resource.description)
            append(resource.contentSha256)
            if (resource.packageDigest != resource.contentSha256 || resource.packageFileCount != 1) {
                append("package:")
                append(resource.packageDigest)
                append(':')
                append(resource.packageFileCount)
                append(':')
            }
            append(if (resource.disableModelInvocation) '1' else '0')
        }
    }
    return canonical.sha256Utf8()
}

internal fun String.sha256Utf8(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
