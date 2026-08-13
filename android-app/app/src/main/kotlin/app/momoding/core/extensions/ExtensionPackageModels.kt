package app.momoding.core.extensions

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MAX_EXTENSION_PACKAGES = 32
const val MAX_EXTENSION_PACKAGE_FILES = 256
const val MAX_EXTENSION_PACKAGE_NODES = 512
const val MAX_EXTENSION_PACKAGE_FILE_BYTES = 4 * 1024 * 1024
const val MAX_EXTENSION_PACKAGE_TOTAL_BYTES = 8 * 1024 * 1024
const val MAX_EXTENSION_MANIFEST_BYTES = 64 * 1024
const val MAX_EXTENSION_JAVASCRIPT_MODULE_BYTES = 256 * 1024
const val MAX_EXTENSION_JAVASCRIPT_TOTAL_BYTES = 1024 * 1024
const val MAX_EXTENSION_STATE_ENTRIES = 32
const val MAX_EXTENSION_STATE_VALUE_BYTES = 8 * 1024
const val MAX_EXTENSION_STATE_TOTAL_BYTES = 32 * 1024
const val MAX_EXTENSION_PACKAGE_PATH_CHARS = 512
const val EXTENSION_MANIFEST_PATH = "momoding-extension.json"
const val EXTENSION_SOURCE_LOCK_PATH = "momoding-source-lock.json"

enum class ExtensionToolType(val wireValue: String) {
    ANDROID_TOOL_ALIAS("android-tool-alias"),
    CONNECTOR_PROXY("connector-proxy"),
    PROMPT_TOOL("prompt-tool"),
    JAVASCRIPT_TOOL("javascript-tool"),
    PI_REGISTER_TOOL("pi-register-tool"),
}

@Serializable
data class ExtensionToolSnapshot(
    val type: String,
    val name: String,
    val description: String,
    val targetTool: String? = null,
    val prompt: String? = null,
    val parameters: JsonObject? = null,
    val label: String? = null,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String> = emptyList(),
    val executionMode: String? = null,
)

@Serializable
data class ExtensionHostToolSnapshot(
    val name: String,
    val targetTool: String,
    val capability: String? = null,
)

@Serializable
data class ExtensionPackageSnapshot(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val runtime: String,
    val entrypoint: String? = null,
    val tools: List<ExtensionToolSnapshot>,
    val requiredCapabilities: List<String>,
    val optionalCapabilities: List<String>,
    val networkOrigins: List<String> = emptyList(),
    val hostTools: List<ExtensionHostToolSnapshot> = emptyList(),
    val httpPolicy: PiRegisterToolHttpPolicy = PiRegisterToolHttpPolicy(),
    val packageDigest: String,
)

enum class ExtensionPackageCompatibility(val wireValue: String) {
    DIRECT("direct"),
    BUILD_REQUIRED("build_required"),
    HOST_SHIM_REQUIRED("host_shim_required"),
    UNSUPPORTED("unsupported"),
    REJECTED("rejected"),
}

data class ExtensionPackageCompatibilityDiagnostic(
    val compatibility: ExtensionPackageCompatibility,
    val code: String,
    val packageName: String?,
    val detail: String,
    val nextAction: String,
)

data class ExtensionPackageAccessDiff(
    val addedRequiredCapabilities: List<String> = emptyList(),
    val removedRequiredCapabilities: List<String> = emptyList(),
    val addedOptionalCapabilities: List<String> = emptyList(),
    val removedOptionalCapabilities: List<String> = emptyList(),
    val addedHostTools: List<String> = emptyList(),
    val removedHostTools: List<String> = emptyList(),
    val addedOrigins: List<String> = emptyList(),
    val removedOrigins: List<String> = emptyList(),
    val addedHttpMethods: List<String> = emptyList(),
    val removedHttpMethods: List<String> = emptyList(),
    val addedCredentialBindings: List<String> = emptyList(),
    val removedCredentialBindings: List<String> = emptyList(),
    val runtimeChanged: Boolean = false,
) {
    val changed: Boolean = listOf(
        addedRequiredCapabilities,
        removedRequiredCapabilities,
        addedOptionalCapabilities,
        removedOptionalCapabilities,
        addedHostTools,
        removedHostTools,
        addedOrigins,
        removedOrigins,
        addedHttpMethods,
        removedHttpMethods,
        addedCredentialBindings,
        removedCredentialBindings,
    ).any(List<String>::isNotEmpty) || runtimeChanged
}

data class ExtensionPackageInstallResult(
    val record: ExtensionPackageRecord,
    val wasUpdate: Boolean,
    val unchanged: Boolean,
    val accessDiff: ExtensionPackageAccessDiff,
)

sealed interface ExtensionPackageImportResult {
    data class Installed(val result: ExtensionPackageInstallResult) : ExtensionPackageImportResult
    data class Diagnosed(
        val diagnostic: ExtensionPackageCompatibilityDiagnostic,
    ) : ExtensionPackageImportResult
}

sealed interface ExtensionPackageReadResult {
    data class Artifact(val document: ExtensionPackageDocument) : ExtensionPackageReadResult
    data class Diagnostic(
        val diagnostic: ExtensionPackageCompatibilityDiagnostic,
    ) : ExtensionPackageReadResult
}

data class ExtensionPackageManifest(
    val snapshot: ExtensionPackageSnapshot,
    val canonicalJson: String,
)

data class ExtensionPackageFile(
    val relativePath: String,
    val mimeType: String,
    val content: ByteArray,
    val contentSha256: String,
)

data class ExtensionPackageFileDescriptor(
    val relativePath: String,
    val byteSize: Long,
    val contentSha256: String,
)

data class ExtensionPackageDocument(
    val rootDisplayName: String,
    val manifestContent: String,
    val files: List<ExtensionPackageFile>,
)

data class ExtensionPackageRecord(
    val manifest: ExtensionPackageManifest,
    val enabled: Boolean,
    val fileCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class EnabledExtensionPackageSet(
    val packages: List<ExtensionPackageSnapshot>,
    val digest: String,
)

data class JavaScriptExtensionInvocation(
    val extensionPackage: ExtensionPackageSnapshot,
    val tool: ExtensionToolSnapshot,
    val files: List<ExtensionPackageFile>,
    val stateJson: Map<String, String>,
)

data class PiRegisterToolInvocation(
    val artifact: PiRegisterToolArtifact,
    val state: Map<String, JsonElement>,
)

class ExtensionPackageException(
    val code: String,
) : IllegalArgumentException(code)

internal val EXTENSION_CAPABILITY_IDS = setOf(
    "saf_folders",
    "photo_library",
    "calendar",
    "contacts",
    "location",
    "notifications",
    "accessibility_control",
    "screen_capture",
    "all_files",
    "shizuku_shell_uid",
)

internal val EXTENSION_ANDROID_TOOL_CAPABILITIES: Map<String, String?> = mapOf(
    "device_capabilities_get" to null,
    "device_media_list" to "photo_library",
    "device_calendar" to "calendar",
    "device_contacts" to "contacts",
    "device_location" to "location",
    "device_clipboard" to null,
    "device_notification" to "notifications",
    "device_ui_inspect" to "accessibility_control",
    "device_ui_action" to "accessibility_control",
    "device_packages_list" to "shizuku_shell_uid",
    "device_package_inspect" to "shizuku_shell_uid",
)

internal fun extensionPackageSetDigest(packages: List<ExtensionPackageSnapshot>): String {
    require(packages.size <= MAX_EXTENSION_PACKAGES) { "EXTENSION_PACKAGE_LIMIT_EXCEEDED" }
    val sorted = packages.sortedBy(ExtensionPackageSnapshot::id)
    require(sorted.map(ExtensionPackageSnapshot::id).distinct().size == sorted.size) {
        "EXTENSION_PACKAGE_ID_DUPLICATED"
    }
    return buildString {
        sorted.forEach { manifest ->
            append(manifest.id.length)
            append(':')
            append(manifest.id)
            append(':')
            append(manifest.packageDigest)
        }
    }.sha256Utf8()
}

internal fun extensionPackageSnapshotsWireJson(
    packages: List<ExtensionPackageSnapshot>,
): String = buildJsonArray {
    packages.forEach { extensionPackage ->
        add(buildJsonObject {
            put("schemaVersion", extensionPackage.schemaVersion)
            put("id", extensionPackage.id)
            put("name", extensionPackage.name)
            put("version", extensionPackage.version)
            put("description", extensionPackage.description)
            put("runtime", extensionPackage.runtime)
            put(
                "entrypoint",
                extensionPackage.entrypoint?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("tools", buildJsonArray {
                extensionPackage.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", tool.type)
                        put("name", tool.name)
                        put("description", tool.description)
                        when (tool.type) {
                            ExtensionToolType.ANDROID_TOOL_ALIAS.wireValue ->
                                put("targetTool", requireNotNull(tool.targetTool))
                            ExtensionToolType.CONNECTOR_PROXY.wireValue -> Unit
                            ExtensionToolType.PROMPT_TOOL.wireValue ->
                                put("prompt", requireNotNull(tool.prompt))
                            ExtensionToolType.JAVASCRIPT_TOOL.wireValue -> {
                                val parameters = requireNotNull(tool.parameters)
                                put("parameters", parameters)
                                put("parametersDigest", extensionToolParametersDigest(parameters))
                            }
                            ExtensionToolType.PI_REGISTER_TOOL.wireValue -> {
                                put("label", requireNotNull(tool.label))
                                val parameters = requireNotNull(tool.parameters)
                                put("parameters", parameters)
                                put(
                                    "promptSnippet",
                                    tool.promptSnippet?.let(::JsonPrimitive) ?: JsonNull,
                                )
                                put(
                                    "promptGuidelines",
                                    JsonArray(tool.promptGuidelines.map(::JsonPrimitive)),
                                )
                                put("executionMode", requireNotNull(tool.executionMode))
                            }
                            else -> error("EXTENSION_PACKAGE_TOOL_TYPE_INVALID")
                        }
                    })
                }
            })
            put("requiredCapabilities", JsonArray(extensionPackage.requiredCapabilities.map(::JsonPrimitive)))
            put("optionalCapabilities", JsonArray(extensionPackage.optionalCapabilities.map(::JsonPrimitive)))
            if (extensionPackage.schemaVersion == 1) {
                put("networkOrigins", JsonArray(extensionPackage.networkOrigins.map(::JsonPrimitive)))
            } else if (extensionPackage.schemaVersion == 2) {
                put("hostTools", buildJsonArray {
                    extensionPackage.hostTools.forEach { hostTool ->
                        add(buildJsonObject {
                            put("name", hostTool.name)
                            put("targetTool", hostTool.targetTool)
                            put(
                                "capability",
                                hostTool.capability?.let(::JsonPrimitive) ?: JsonNull,
                            )
                        })
                    }
                })
                put("httpPolicy", buildJsonObject {
                    put("origins", JsonArray(extensionPackage.httpPolicy.origins.map(::JsonPrimitive)))
                    put("methods", JsonArray(extensionPackage.httpPolicy.methods.map(::JsonPrimitive)))
                    put("credentialSlots", buildJsonArray {
                        extensionPackage.httpPolicy.credentialSlots.forEach { slot ->
                            add(buildJsonObject {
                                put("slot", slot.slot)
                                put("origin", slot.origin)
                                put("placement", slot.placement)
                            })
                        }
                    })
                })
            }
            put("packageDigest", extensionPackage.packageDigest)
        })
    }
}.toString()

internal fun extensionToolParametersDigest(parameters: JsonObject): String =
    canonicalJson(parameters).sha256Utf8()

internal fun extensionPackageDigest(
    manifestWithoutDigest: JsonObject,
    files: List<ExtensionPackageFile>,
): String {
    val descriptors = files
        .filterNot { it.relativePath == EXTENSION_MANIFEST_PATH }
        .map { file ->
            ExtensionPackageFileDescriptor(
                relativePath = file.relativePath,
                byteSize = file.content.size.toLong(),
                contentSha256 = file.contentSha256,
            )
        }
    val canonical = buildString {
        append("manifest:")
        append(canonicalJson(manifestWithoutDigest))
        descriptors.sortedBy(ExtensionPackageFileDescriptor::relativePath).forEach { file ->
            append("\nfile:")
            append(file.relativePath.length)
            append(':')
            append(file.relativePath)
            append(':')
            append(file.byteSize)
            append(':')
            append(file.contentSha256)
        }
    }
    return canonical.sha256Utf8()
}

internal fun canonicalJson(element: JsonElement): String = when (element) {
    JsonNull -> "null"
    is JsonPrimitive -> element.toString()
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
    is JsonObject -> element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
}

internal fun requireValidExtensionPackagePath(path: String) {
    if (
        path.length !in 1..MAX_EXTENSION_PACKAGE_PATH_CHARS ||
        path.startsWith('/') || path.endsWith('/') || '\\' in path || '\u0000' in path
    ) throw ExtensionPackageException("EXTENSION_PACKAGE_PATH_INVALID")
    val segments = path.split('/')
    if (
        segments.size > 12 || segments.any { segment ->
            segment.isBlank() || segment == "." || segment == ".." ||
                segment.length > 128 || '\u0000' in segment
        }
    ) throw ExtensionPackageException("EXTENSION_PACKAGE_PATH_INVALID")
}

internal fun String.sha256Utf8(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
