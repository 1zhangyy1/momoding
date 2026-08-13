package app.momoding.core.extensions

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ExtensionPackageManifestParser(
    private val json: Json = Json,
) {
    fun parse(content: String, files: List<ExtensionPackageFile>): ExtensionPackageManifest {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (_: Exception) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_MANIFEST_JSON_INVALID")
        }
        val schemaVersion = root.requiredInt("schemaVersion")
        return when (schemaVersion) {
            1 -> parseV1(root, files)
            2 -> parseV2(root, files)
            else -> fail("EXTENSION_PACKAGE_SCHEMA_UNSUPPORTED")
        }
    }

    private fun parseV1(
        root: JsonObject,
        files: List<ExtensionPackageFile>,
    ): ExtensionPackageManifest {
        requireKeys(root, V1_TOP_LEVEL_KEYS)
        val common = parseCommon(root)
        val entrypoint = when (common.runtime) {
            DECLARATIVE_RUNTIME -> {
                if (root["entrypoint"] != JsonNull) fail("EXTENSION_PACKAGE_ENTRYPOINT_INVALID")
                null
            }
            JAVASCRIPT_RUNTIME -> root.requiredString("entrypoint", 1, MAX_EXTENSION_PACKAGE_PATH_CHARS)
            else -> fail("EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED")
        }
        val capabilities = parseCapabilities(root)
        val networkOrigins = root.stringArray("networkOrigins", 8)
        if (networkOrigins.distinct().size != networkOrigins.size) {
            fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_DUPLICATED")
        }
        networkOrigins.forEach(::requireHttpsOrigin)
        val tools = parseToolArray(root, ::parseV1Tool)
        tools.forEach { tool ->
            if (tool.type == ExtensionToolType.ANDROID_TOOL_ALIAS.wireValue) {
                val capability = targetCapability(requireNotNull(tool.targetTool))
                if (capability != null && capability !in capabilities.first) {
                    fail("EXTENSION_PACKAGE_CAPABILITY_UNDECLARED")
                }
            }
        }
        return finish(
            root = root,
            files = files,
            entrypoint = entrypoint,
            common = common,
            tools = tools,
            requiredCapabilities = capabilities.first,
            optionalCapabilities = capabilities.second,
            networkOrigins = networkOrigins,
            hostTools = emptyList(),
            httpPolicy = PiRegisterToolHttpPolicy(),
        )
    }

    private fun parseV2(
        root: JsonObject,
        files: List<ExtensionPackageFile>,
    ): ExtensionPackageManifest {
        requireKeys(root, V2_TOP_LEVEL_KEYS)
        val common = parseCommon(root)
        if (common.runtime != PI_REGISTER_TOOL_RUNTIME) {
            fail("EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED")
        }
        if (common.id.length > 76 || common.description.length > 512) {
            fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        }
        val entrypoint = root.requiredString("entrypoint", 1, 256)
        val capabilities = parseCapabilities(root)
        val tools = parseToolArray(root, ::parsePiRegisterTool)
        if (tools.any { it.type != ExtensionToolType.PI_REGISTER_TOOL.wireValue }) {
            fail("EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED")
        }
        val hostTools = parseHostTools(root, capabilities.first)
        val httpPolicy = parseHttpPolicy(root)
        val sourceLock = parseSourceLock(files, common.version)
        if (sourceLock.packageVersion != common.version) {
            fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        }
        requirePiRegisterToolRuntimeFiles(files)
        return finish(
            root = root,
            files = files,
            entrypoint = entrypoint,
            common = common,
            tools = tools,
            requiredCapabilities = capabilities.first,
            optionalCapabilities = capabilities.second,
            networkOrigins = httpPolicy.origins,
            hostTools = hostTools,
            httpPolicy = httpPolicy,
        )
    }

    private fun parseCommon(root: JsonObject): CommonManifest {
        val id = root.requiredString("id", 3, 80)
        if (!EXTENSION_ID_PATTERN.matches(id)) fail("EXTENSION_PACKAGE_ID_INVALID")
        val name = root.requiredString("name", 1, 80)
        val version = root.requiredString("version", 1, 40)
        if (!VERSION_PATTERN.matches(version)) fail("EXTENSION_PACKAGE_VERSION_INVALID")
        return CommonManifest(
            schemaVersion = root.requiredInt("schemaVersion"),
            id = id,
            name = name,
            version = version,
            description = root.requiredString("description", 1, 1_024),
            runtime = root.requiredString("runtime", 1, 40),
        )
    }

    private fun parseCapabilities(root: JsonObject): Pair<List<String>, List<String>> {
        val required = root.stringArray("requiredCapabilities", 10)
        val optional = root.stringArray("optionalCapabilities", 10)
        if (required.distinct().size != required.size || optional.distinct().size != optional.size) {
            fail("EXTENSION_PACKAGE_CAPABILITY_DUPLICATED")
        }
        if (required.any { it !in EXTENSION_CAPABILITY_IDS } || optional.any { it !in EXTENSION_CAPABILITY_IDS }) {
            fail("EXTENSION_PACKAGE_CAPABILITY_UNKNOWN")
        }
        if (required.toSet().intersect(optional.toSet()).isNotEmpty()) {
            fail("EXTENSION_PACKAGE_CAPABILITY_CONFLICT")
        }
        return required.sorted() to optional.sorted()
    }

    private fun parseToolArray(
        root: JsonObject,
        parser: (JsonElement) -> ExtensionToolSnapshot,
    ): List<ExtensionToolSnapshot> {
        val elements = root["tools"] as? JsonArray ?: fail("EXTENSION_PACKAGE_TOOLS_INVALID")
        if (elements.size !in 1..16) fail("EXTENSION_PACKAGE_TOOL_LIMIT_INVALID")
        val tools = elements.map(parser)
        if (tools.map(ExtensionToolSnapshot::name).distinct().size != tools.size) {
            fail("EXTENSION_PACKAGE_TOOL_NAME_DUPLICATED")
        }
        return tools
    }

    private fun parseV1Tool(element: JsonElement): ExtensionToolSnapshot {
        val tool = element as? JsonObject ?: fail("EXTENSION_PACKAGE_TOOL_INVALID")
        val type = tool.requiredString("type", 1, 40)
        val expectedKeys = when (type) {
            ExtensionToolType.ANDROID_TOOL_ALIAS.wireValue -> ALIAS_TOOL_KEYS
            ExtensionToolType.CONNECTOR_PROXY.wireValue -> CONNECTOR_TOOL_KEYS
            ExtensionToolType.PROMPT_TOOL.wireValue -> PROMPT_TOOL_KEYS
            ExtensionToolType.JAVASCRIPT_TOOL.wireValue -> JAVASCRIPT_TOOL_KEYS
            else -> fail("EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED")
        }
        requireKeys(tool, expectedKeys)
        val name = requireToolName(tool)
        val description = tool.requiredString("description", 1, 512)
        return when (type) {
            ExtensionToolType.ANDROID_TOOL_ALIAS.wireValue -> {
                val target = tool.requiredString("targetTool", 1, 80)
                targetCapability(target)
                ExtensionToolSnapshot(type, name, description, targetTool = target)
            }
            ExtensionToolType.CONNECTOR_PROXY.wireValue ->
                ExtensionToolSnapshot(type, name, description)
            ExtensionToolType.PROMPT_TOOL.wireValue -> ExtensionToolSnapshot(
                type = type,
                name = name,
                description = description,
                prompt = tool.requiredString("prompt", 1, 16 * 1_024),
            )
            else -> ExtensionToolSnapshot(
                type = type,
                name = name,
                description = description,
                parameters = requireValidExtensionToolSchema(
                    tool["parameters"] as? JsonObject
                        ?: fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID"),
                ),
            )
        }
    }

    private fun parsePiRegisterTool(element: JsonElement): ExtensionToolSnapshot {
        val tool = element as? JsonObject ?: fail("EXTENSION_PACKAGE_TOOL_INVALID")
        requireKeys(tool, PI_REGISTER_TOOL_KEYS)
        if (tool.requiredString("type", 1, 40) != ExtensionToolType.PI_REGISTER_TOOL.wireValue) {
            fail("EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED")
        }
        val executionMode = tool.requiredString("executionMode", 1, 20)
        if (executionMode != "sequential") fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED")
        return ExtensionToolSnapshot(
            type = ExtensionToolType.PI_REGISTER_TOOL.wireValue,
            name = requireToolName(tool),
            label = tool.requiredString("label", 1, 80),
            description = tool.requiredString("description", 1, 512),
            parameters = requireValidPiRegisterToolSchema(
                tool["parameters"] as? JsonObject
                    ?: fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID"),
            ),
            promptSnippet = tool.nullableString("promptSnippet", 1, 512),
            promptGuidelines = tool.stringArray("promptGuidelines", 16, 512),
            executionMode = executionMode,
        )
    }

    private fun parseHostTools(
        root: JsonObject,
        requiredCapabilities: List<String>,
    ): List<ExtensionHostToolSnapshot> {
        val elements = root["hostTools"] as? JsonArray
            ?: fail("EXTENSION_PACKAGE_HOST_TOOLS_INVALID")
        if (elements.size > 16) fail("EXTENSION_PACKAGE_HOST_TOOL_LIMIT_INVALID")
        val tools = elements.map { element ->
            val host = element as? JsonObject ?: fail("EXTENSION_PACKAGE_HOST_TOOL_INVALID")
            requireKeys(host, HOST_TOOL_KEYS)
            val name = host.requiredString("name", 1, 64)
            if (!TOOL_NAME_PATTERN.matches(name)) fail("EXTENSION_PACKAGE_HOST_TOOL_INVALID")
            val targetTool = host.requiredString("targetTool", 1, 80)
            val expectedCapability = targetCapability(targetTool)
            val capability = host.nullableString("capability", 1, 64)
            if (capability != expectedCapability || capability != null && capability !in requiredCapabilities) {
                fail("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED")
            }
            ExtensionHostToolSnapshot(name, targetTool, capability)
        }
        if (tools.map(ExtensionHostToolSnapshot::name).distinct().size != tools.size) {
            fail("EXTENSION_PACKAGE_HOST_TOOL_DUPLICATED")
        }
        return tools
    }

    private fun parseHttpPolicy(root: JsonObject): PiRegisterToolHttpPolicy {
        val value = root["httpPolicy"] as? JsonObject
            ?: fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        requireKeys(value, HTTP_POLICY_KEYS)
        val origins = value.stringArray("origins", 8).sorted()
        val methods = value.stringArray("methods", 6).sorted()
        val credentials = value["credentialSlots"] as? JsonArray
            ?: fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        if (credentials.size > 8) fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        val slots = credentials.map { element ->
            val slot = element as? JsonObject ?: fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
            requireKeys(slot, CREDENTIAL_SLOT_KEYS)
            PiRegisterToolCredentialSlot(
                slot = slot.requiredString("slot", 1, 64),
                origin = slot.requiredString("origin", 9, 255),
                placement = slot.requiredString("placement", 1, 40),
            )
        }.sortedBy(PiRegisterToolCredentialSlot::slot)
        val policy = PiRegisterToolHttpPolicy(origins, methods, slots)
        try {
            requirePiExtensionHttpPolicy(policy)
        } catch (_: ExtensionPackageException) {
            fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID")
        }
        return policy
    }

    private fun parseSourceLock(
        files: List<ExtensionPackageFile>,
        manifestVersion: String,
    ): SourceLock {
        val file = files.singleOrNull { it.relativePath == EXTENSION_SOURCE_LOCK_PATH }
            ?: fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_MISSING")
        if (file.content.size > MAX_EXTENSION_MANIFEST_BYTES) {
            fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        }
        val value = try {
            json.parseToJsonElement(decodeUtf8(file.content)).jsonObject
        } catch (_: Exception) {
            fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        }
        requireKeys(value, SOURCE_LOCK_KEYS, "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        if (value.requiredInt("schemaVersion") != 1 ||
            value.requiredString("profile", 1, 80) != "momoding-pi-mobile-v1"
        ) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        val kind = value.requiredString("kind", 1, 16)
        val packageName = value.requiredString("packageName", 1, 214)
        val packageVersion = value.requiredString("packageVersion", 1, 40)
        if (packageVersion != manifestVersion || !VERSION_PATTERN.matches(packageVersion)) {
            fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        }
        val sourceDigest = value.requiredString("sourceDigest", 64, 64)
        if (!SHA256_PATTERN.matches(sourceDigest)) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        val integrity = value.nullableString("registryIntegrity", 1, 512)
        val repositoryUrl = value.nullableString("repositoryUrl", 1, 512)
        val revision = value.nullableString("revision", 1, 64)
        val packer = value["packer"] as? JsonObject
            ?: fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        requireKeys(packer, PACKER_KEYS, "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        if (packer.requiredString("node", 1, 32) != "22.22.3" ||
            packer.requiredString("typescript", 1, 32) != "5.9.3" ||
            packer.requiredString("esbuild", 1, 32) != "0.27.2"
        ) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        when (kind) {
            "local" -> if (integrity != null || repositoryUrl != null || revision != null) {
                fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
            }
            "npm" -> if (integrity?.matches(NPM_INTEGRITY_PATTERN) != true ||
                repositoryUrl != null || revision != null
            ) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
            "git" -> if (integrity != null || revision?.matches(GIT_REVISION_PATTERN) != true ||
                repositoryUrl?.let(::isSafeHttpsRepository) != true
            ) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
            else -> fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID")
        }
        return SourceLock(packageName, packageVersion)
    }

    private fun finish(
        root: JsonObject,
        files: List<ExtensionPackageFile>,
        entrypoint: String?,
        common: CommonManifest,
        tools: List<ExtensionToolSnapshot>,
        requiredCapabilities: List<String>,
        optionalCapabilities: List<String>,
        networkOrigins: List<String>,
        hostTools: List<ExtensionHostToolSnapshot>,
        httpPolicy: PiRegisterToolHttpPolicy,
    ): ExtensionPackageManifest {
        val declaredDigest = root.requiredString("packageDigest", 64, 64)
        if (!SHA256_PATTERN.matches(declaredDigest)) fail("EXTENSION_PACKAGE_DIGEST_INVALID")
        requireValidFiles(files)
        validateRuntime(common.runtime, entrypoint, tools, files)
        val computedDigest = extensionPackageDigest(JsonObject(root - "packageDigest"), files)
        if (computedDigest != declaredDigest) fail("EXTENSION_PACKAGE_DIGEST_MISMATCH")
        return ExtensionPackageManifest(
            snapshot = ExtensionPackageSnapshot(
                schemaVersion = common.schemaVersion,
                id = common.id,
                name = common.name,
                version = common.version,
                description = common.description,
                runtime = common.runtime,
                entrypoint = entrypoint,
                tools = tools,
                requiredCapabilities = requiredCapabilities,
                optionalCapabilities = optionalCapabilities,
                networkOrigins = networkOrigins.sorted(),
                hostTools = hostTools,
                httpPolicy = httpPolicy,
                packageDigest = declaredDigest,
            ),
            canonicalJson = canonicalJson(root),
        )
    }

    private fun validateRuntime(
        runtime: String,
        entrypoint: String?,
        tools: List<ExtensionToolSnapshot>,
        files: List<ExtensionPackageFile>,
    ) {
        if (runtime == DECLARATIVE_RUNTIME) {
            if (tools.any { it.type == ExtensionToolType.JAVASCRIPT_TOOL.wireValue }) {
                fail("EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED")
            }
            return
        }
        when (runtime) {
            JAVASCRIPT_RUNTIME -> if (tools.none {
                it.type == ExtensionToolType.JAVASCRIPT_TOOL.wireValue
            }) fail("EXTENSION_PACKAGE_JAVASCRIPT_TOOL_MISSING")
            PI_REGISTER_TOOL_RUNTIME -> if (tools.any {
                it.type != ExtensionToolType.PI_REGISTER_TOOL.wireValue
            }) fail("EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED")
            else -> fail("EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED")
        }
        val path = requireNotNull(entrypoint)
        requireValidExtensionPackagePath(path)
        if (!path.startsWith("dist/") || !path.endsWith(".js")) {
            fail("EXTENSION_PACKAGE_ENTRYPOINT_INVALID")
        }
        val modules = files.filter {
            it.relativePath.startsWith("dist/") && it.relativePath.endsWith(".js")
        }
        if (runtime == PI_REGISTER_TOOL_RUNTIME && modules.size > 32) {
            fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
        }
        if (modules.none { it.relativePath == path }) fail("EXTENSION_PACKAGE_ENTRYPOINT_MISSING")
        var totalBytes = 0
        modules.forEach { module ->
            if (module.content.size > MAX_EXTENSION_JAVASCRIPT_MODULE_BYTES) {
                fail("EXTENSION_PACKAGE_JAVASCRIPT_MODULE_TOO_LARGE")
            }
            totalBytes += module.content.size
            if (totalBytes > MAX_EXTENSION_JAVASCRIPT_TOTAL_BYTES) {
                fail("EXTENSION_PACKAGE_JAVASCRIPT_TOO_LARGE")
            }
            decodeUtf8(module.content)
        }
    }

    private fun requirePiRegisterToolRuntimeFiles(files: List<ExtensionPackageFile>) {
        files.filterNot { file ->
            file.relativePath == EXTENSION_MANIFEST_PATH ||
                file.relativePath.startsWith("dist/") && file.relativePath.endsWith(".js")
        }.forEach { resource ->
            if (resource.relativePath.startsWith("dist/") || resource.content.size > 64 * 1024) {
                fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
            }
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(resource.content))
            } catch (_: Exception) {
                fail("EXTENSION_PACKAGE_ARTIFACT_INVALID")
            }
        }
    }

    private fun targetCapability(target: String): String? {
        if (target !in EXTENSION_ANDROID_TOOL_CAPABILITIES) {
            fail("EXTENSION_PACKAGE_ALIAS_TARGET_UNSUPPORTED")
        }
        return EXTENSION_ANDROID_TOOL_CAPABILITIES[target]
    }

    private fun requireValidFiles(files: List<ExtensionPackageFile>) {
        if (files.size !in 1..MAX_EXTENSION_PACKAGE_FILES) fail("EXTENSION_PACKAGE_FILE_LIMIT_EXCEEDED")
        if (files.map(ExtensionPackageFile::relativePath).distinct().size != files.size) {
            fail("EXTENSION_PACKAGE_PATH_DUPLICATED")
        }
        if (files.count { it.relativePath == EXTENSION_MANIFEST_PATH } != 1) {
            fail("EXTENSION_PACKAGE_MANIFEST_MISSING")
        }
        var total = 0L
        files.forEach { file ->
            requireValidExtensionPackagePath(file.relativePath)
            if (file.content.size > MAX_EXTENSION_PACKAGE_FILE_BYTES) {
                fail("EXTENSION_PACKAGE_FILE_TOO_LARGE")
            }
            total += file.content.size
            if (total > MAX_EXTENSION_PACKAGE_TOTAL_BYTES) fail("EXTENSION_PACKAGE_TOO_LARGE")
            if (file.content.sha256() != file.contentSha256) {
                fail("EXTENSION_PACKAGE_FILE_DIGEST_INVALID")
            }
        }
    }

    private fun requireToolName(tool: JsonObject): String {
        val name = tool.requiredString("name", 1, 64)
        if (!TOOL_NAME_PATTERN.matches(name)) fail("EXTENSION_PACKAGE_TOOL_NAME_INVALID")
        return name
    }

    private fun requireHttpsOrigin(raw: String) {
        if (raw.length !in 9..255 || '\u0000' in raw) {
            fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID")
        }
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID")
        }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null ||
            raw.endsWith('/')
        ) fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID")
    }

    private fun isSafeHttpsRepository(raw: String): Boolean =
        raw.length in 1..512 && SAFE_GIT_REPOSITORY.matches(raw)

    private fun decodeUtf8(content: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(content))
            .toString()
    } catch (_: Exception) {
        fail("EXTENSION_PACKAGE_JAVASCRIPT_UTF8_INVALID")
    }

    private fun JsonObject.requiredString(key: String, minimum: Int, maximum: Int): String {
        val primitive = this[key] as? JsonPrimitive
            ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        if (!primitive.isString || primitive.booleanOrNull != null) {
            fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        }
        val value = primitive.contentOrNull ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        if (value.length !in minimum..maximum || '\u0000' in value) {
            fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        }
        return value
    }

    private fun JsonObject.nullableString(
        key: String,
        minimum: Int,
        maximum: Int,
    ): String? = if (this[key] == JsonNull) null else requiredString(key, minimum, maximum)

    private fun JsonObject.requiredInt(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")

    private fun JsonObject.stringArray(
        key: String,
        maximum: Int,
        itemMaximum: Int = 255,
    ): List<String> {
        val values = this[key] as? JsonArray
            ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        if (values.size > maximum) fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
        return values.map { element ->
            val primitive = element as? JsonPrimitive
                ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
            if (!primitive.isString) fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
            val value = primitive.contentOrNull
                ?: fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
            if (value.isBlank() || value.length > itemMaximum || '\u0000' in value) {
                fail("EXTENSION_PACKAGE_MANIFEST_FIELD_INVALID")
            }
            value
        }
    }

    private fun requireKeys(
        value: JsonObject,
        expected: Set<String>,
        code: String = "EXTENSION_PACKAGE_MANIFEST_FIELDS_INVALID",
    ) {
        if (value.keys != expected) fail(code)
    }

    private fun fail(code: String): Nothing = throw ExtensionPackageException(code)

    private data class CommonManifest(
        val schemaVersion: Int,
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val runtime: String,
    )

    private data class SourceLock(
        val packageName: String,
        val packageVersion: String,
    )

    private companion object {
        const val DECLARATIVE_RUNTIME = "declarative-v1"
        const val JAVASCRIPT_RUNTIME = "javascript-v1"
        const val PI_REGISTER_TOOL_RUNTIME = "pi-register-tool-v1"
        val EXTENSION_ID_PATTERN = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$")
        val VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
        val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val GIT_REVISION_PATTERN = Regex("^[0-9a-f]{40}$")
        val NPM_INTEGRITY_PATTERN = Regex("^sha512-[A-Za-z0-9+/]{86}==$")
        val SAFE_GIT_REPOSITORY = Regex(
            "^https://[a-z0-9]+(?:[.-][a-z0-9]+)*(?::[1-9][0-9]{0,4})?" +
                "(?:/[A-Za-z0-9._~!$&'()*+,;=:@-]+)*/?$",
        )
        val V1_TOP_LEVEL_KEYS = setOf(
            "schemaVersion", "id", "name", "version", "description", "runtime", "entrypoint",
            "tools", "requiredCapabilities", "optionalCapabilities", "networkOrigins", "packageDigest",
        )
        val V2_TOP_LEVEL_KEYS = setOf(
            "schemaVersion", "id", "name", "version", "description", "runtime", "entrypoint",
            "tools", "hostTools", "requiredCapabilities", "optionalCapabilities", "httpPolicy",
            "packageDigest",
        )
        val ALIAS_TOOL_KEYS = setOf("type", "name", "description", "targetTool")
        val CONNECTOR_TOOL_KEYS = setOf("type", "name", "description")
        val PROMPT_TOOL_KEYS = setOf("type", "name", "description", "prompt")
        val JAVASCRIPT_TOOL_KEYS = setOf("type", "name", "description", "parameters")
        val PI_REGISTER_TOOL_KEYS = setOf(
            "type", "name", "label", "description", "parameters", "promptSnippet",
            "promptGuidelines", "executionMode",
        )
        val HOST_TOOL_KEYS = setOf("name", "targetTool", "capability")
        val HTTP_POLICY_KEYS = setOf("origins", "methods", "credentialSlots")
        val CREDENTIAL_SLOT_KEYS = setOf("slot", "origin", "placement")
        val SOURCE_LOCK_KEYS = setOf(
            "schemaVersion", "profile", "kind", "packageName", "packageVersion", "sourceDigest",
            "registryIntegrity", "repositoryUrl", "revision", "packer",
        )
        val PACKER_KEYS = setOf("node", "typescript", "esbuild")
    }
}
