package app.momoding.core.skills

import java.security.MessageDigest
import kotlinx.serialization.Serializable

const val MAX_SKILL_DOCUMENT_BYTES: Int = 64 * 1024
const val MAX_SKILL_DOCUMENT_UTF16_UNITS: Int = 64 * 1024
const val MAX_SKILL_RESOURCES: Int = 64

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
)

data class SkillDocumentParseResult(
    val resource: PhoneLocalSkillResource,
    val availability: SkillAvailability,
    val diagnosticCode: String?,
    val diagnosticMessage: String?,
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
            append(if (resource.disableModelInvocation) '1' else '0')
        }
    }
    return canonical.sha256Utf8()
}

internal fun String.sha256Utf8(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
