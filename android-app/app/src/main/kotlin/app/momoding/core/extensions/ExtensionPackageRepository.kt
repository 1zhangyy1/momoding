package app.momoding.core.extensions

import android.net.Uri
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.ExtensionPackageEntity
import app.momoding.core.data.ExtensionPackageFileEntity
import app.momoding.core.data.ExtensionPackageStateEntity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class ExtensionPackageRepository(
    private val database: MomodingDatabase,
    private val parser: ExtensionPackageManifestParser = ExtensionPackageManifestParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val clearPackageCredentials: suspend (String) -> Unit = {},
) {
    private val packages = database.extensionPackageDao()
    private val files = database.extensionPackageFileDao()
    private val state = database.extensionPackageStateDao()
    private val json = Json { ignoreUnknownKeys = false }

    fun observePackages(): Flow<List<ExtensionPackageRecord>> = packages.observeAll().map { rows ->
        rows.map(::recordWithoutFileVerification)
    }.flowOn(ioDispatcher)

    suspend fun packageRecords(): List<ExtensionPackageRecord> = withContext(ioDispatcher) {
        packages.all().map(::recordWithoutFileVerification)
    }

    suspend fun install(
        manifest: ExtensionPackageManifest,
        packageFiles: List<ExtensionPackageFile>,
    ): ExtensionPackageRecord = installWithResult(manifest, packageFiles).record

    suspend fun installWithResult(
        manifest: ExtensionPackageManifest,
        packageFiles: List<ExtensionPackageFile>,
    ): ExtensionPackageInstallResult = withContext(ioDispatcher) {
        val result = database.runInTransaction<ExtensionPackageInstallResult> {
            val verified = parser.parse(
                requireNotNull(packageFiles.singleOrNull {
                    it.relativePath == EXTENSION_MANIFEST_PATH
                }) { "EXTENSION_PACKAGE_MANIFEST_MISSING" }
                    .content.toString(Charsets.UTF_8),
                packageFiles,
            )
            require(verified.snapshot == manifest.snapshot) { "EXTENSION_PACKAGE_MANIFEST_CHANGED" }
            val existing = packages.byId(manifest.snapshot.id)
            if (existing == null) {
                require(packages.count() < MAX_EXTENSION_PACKAGES) {
                    "EXTENSION_PACKAGE_LIMIT_EXCEEDED"
                }
            } else if (existing.packageDigest == manifest.snapshot.packageDigest) {
                return@runInTransaction ExtensionPackageInstallResult(
                    record = verifiedRecord(existing),
                    wasUpdate = false,
                    unchanged = true,
                    accessDiff = ExtensionPackageAccessDiff(),
                )
            }
            val previous = existing?.let(::recordWithoutFileVerification)?.manifest?.snapshot
            val now = nowMillis()
            val entity = manifest.toEntity(
                enabled = false,
                fileCount = packageFiles.size,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            if (existing == null) packages.insert(entity) else {
                check(packages.update(entity) == 1) { "EXTENSION_PACKAGE_UPDATE_FAILED" }
                files.deleteForPackage(entity.packageId)
                state.deleteForPackage(entity.packageId)
            }
            files.insertAll(packageFiles.map { it.toEntity(entity.packageId) })
            ExtensionPackageInstallResult(
                record = verifiedRecord(entity),
                wasUpdate = previous != null,
                unchanged = false,
                accessDiff = previous?.accessDiff(manifest.snapshot)
                    ?: ExtensionPackageAccessDiff(),
            )
        }
        if (result.wasUpdate && !result.unchanged) {
            withContext(NonCancellable) { clearPackageCredentials(result.record.manifest.snapshot.id) }
        }
        result
    }

    suspend fun setEnabled(packageId: String, enabled: Boolean): ExtensionPackageRecord =
        withContext(ioDispatcher) {
            database.runInTransaction<ExtensionPackageRecord> {
                val existing = requireNotNull(packages.byId(packageId)) {
                    "EXTENSION_PACKAGE_NOT_FOUND"
                }
                if (enabled) {
                    verifiedRecord(existing)
                }
                val updated = existing.copy(enabled = enabled, updatedAtMillis = nowMillis())
                check(packages.update(updated) == 1) { "EXTENSION_PACKAGE_UPDATE_FAILED" }
                if (enabled) verifiedRecord(updated) else recordWithoutFileVerification(updated)
            }
        }

    suspend fun delete(packageId: String): Boolean = withContext(ioDispatcher) {
        val deleted = database.runInTransaction<Boolean> {
            val existing = packages.byId(packageId) ?: return@runInTransaction false
            files.deleteForPackage(existing.packageId)
            state.deleteForPackage(existing.packageId)
            packages.delete(existing.packageId) == 1
        }
        if (deleted) withContext(NonCancellable) { clearPackageCredentials(packageId) }
        deleted
    }

    suspend fun enabledPackageSet(): EnabledExtensionPackageSet = withContext(ioDispatcher) {
        val snapshots = packages.all().filter(ExtensionPackageEntity::enabled)
            .map { verifiedRecord(it).manifest.snapshot }
            .sortedBy(ExtensionPackageSnapshot::id)
        EnabledExtensionPackageSet(snapshots, extensionPackageSetDigest(snapshots))
    }

    suspend fun authorizeTool(
        packageId: String,
        packageDigest: String,
        toolName: String,
        type: String,
        description: String,
        targetTool: String?,
        prompt: String?,
        parameters: JsonObject? = null,
    ): ExtensionToolSnapshot = withContext(ioDispatcher) {
        val entity = packages.byId(packageId)
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_FOUND")
        if (!entity.enabled) throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_ENABLED")
        if (entity.packageDigest != packageDigest) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_SNAPSHOT_STALE")
        }
        val manifest = verifiedRecord(entity).manifest.snapshot
        manifest.tools.singleOrNull { tool ->
            tool.name == toolName && tool.type == type && tool.description == description &&
                tool.targetTool == targetTool && tool.prompt == prompt && tool.parameters == parameters
        } ?: throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
    }

    suspend fun javascriptInvocation(
        packageId: String,
        packageDigest: String,
        toolName: String,
        description: String,
        parametersDigest: String,
        arguments: JsonObject,
    ): JavaScriptExtensionInvocation = withContext(ioDispatcher) {
        database.runInTransaction<JavaScriptExtensionInvocation> {
            val entity = packages.byId(packageId)
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_FOUND")
            if (!entity.enabled) throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_ENABLED")
            if (entity.packageDigest != packageDigest) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_SNAPSHOT_STALE")
            }
            val record = verifiedRecord(entity)
            if (record.manifest.snapshot.runtime != "javascript-v1") {
                throw ExtensionPackageException("EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED")
            }
            val tool = record.manifest.snapshot.tools.singleOrNull { candidate ->
                candidate.type == ExtensionToolType.JAVASCRIPT_TOOL.wireValue &&
                    candidate.name == toolName && candidate.description == description &&
                    candidate.parameters?.let(::extensionToolParametersDigest) == parametersDigest
            } ?: throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
            val parameters = requireNotNull(tool.parameters)
            requireValidExtensionToolArguments(parameters, arguments)
            JavaScriptExtensionInvocation(
                extensionPackage = record.manifest.snapshot,
                tool = tool,
                files = files.files(packageId).map(ExtensionPackageFileEntity::toModel),
                stateJson = state.entries(packageId).associate { it.stateKey to it.valueJson },
            )
        }
    }

    suspend fun commitJavaScriptState(
        packageId: String,
        packageDigest: String,
        values: Map<String, String>,
    ) = withContext(ioDispatcher) {
        requireValidState(values)
        database.runInTransaction<Unit> {
            val entity = packages.byId(packageId)
                ?: throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_FOUND")
            if (!entity.enabled) throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_ENABLED")
            if (entity.packageDigest != packageDigest) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_SNAPSHOT_STALE")
            }
            state.deleteForPackage(packageId)
            val now = nowMillis()
            state.insertAll(values.toSortedMap().map { (key, valueJson) ->
                ExtensionPackageStateEntity(packageId, key, valueJson, now)
            })
        }
    }

    suspend fun piRegisterToolInvocation(
        packageId: String,
        packageDigest: String,
        toolName: String,
    ): PiRegisterToolInvocation = withContext(ioDispatcher) {
        database.runInTransaction<PiRegisterToolInvocation> {
            val artifact = authorizedPiRegisterToolArtifact(packageId, packageDigest, toolName)
            val stateValues = state.entries(packageId).associate { entry ->
                entry.stateKey to try {
                    json.parseToJsonElement(entry.valueJson)
                } catch (_: Exception) {
                    throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
                }
            }
            requireValidPiState(stateValues)
            PiRegisterToolInvocation(artifact, stateValues)
        }
    }

    suspend fun authorizePiRegisterToolArtifact(
        packageId: String,
        packageDigest: String,
        toolName: String,
    ): PiRegisterToolArtifact = withContext(ioDispatcher) {
        database.runInTransaction<PiRegisterToolArtifact> {
            authorizedPiRegisterToolArtifact(packageId, packageDigest, toolName)
        }
    }

    suspend fun commitPiRegisterToolState(commit: PiExtensionStateCommit): Boolean =
        withContext(ioDispatcher) {
            requireValidPiState(commit.state)
            database.runInTransaction<Boolean> {
                val entity = packages.byId(commit.packageId) ?: return@runInTransaction false
                if (!entity.enabled || entity.packageDigest != commit.packageDigest) {
                    return@runInTransaction false
                }
                val record = verifiedRecord(entity)
                val artifact = record.toPiRegisterToolArtifact(
                    files.files(commit.packageId).map(ExtensionPackageFileEntity::toModel),
                    commit.toolName,
                )
                if (artifact != commit.artifact) return@runInTransaction false
                state.deleteForPackage(commit.packageId)
                val now = nowMillis()
                state.insertAll(commit.state.toSortedMap().map { (key, value) ->
                    ExtensionPackageStateEntity(
                        packageId = commit.packageId,
                        stateKey = key,
                        valueJson = canonicalJson(value),
                        updatedAtMillis = now,
                    )
                })
                true
            }
        }

    private fun authorizedPiRegisterToolArtifact(
        packageId: String,
        packageDigest: String,
        toolName: String,
    ): PiRegisterToolArtifact {
        val entity = packages.byId(packageId)
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_FOUND")
        if (!entity.enabled) throw ExtensionPackageException("EXTENSION_PACKAGE_NOT_ENABLED")
        if (entity.packageDigest != packageDigest) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_SNAPSHOT_STALE")
        }
        val record = verifiedRecord(entity)
        return record.toPiRegisterToolArtifact(
            files.files(packageId).map(ExtensionPackageFileEntity::toModel),
            toolName,
        )
    }

    private fun ExtensionPackageRecord.toPiRegisterToolArtifact(
        storedFiles: List<ExtensionPackageFile>,
        toolName: String,
    ): PiRegisterToolArtifact {
        val snapshot = manifest.snapshot
        if (snapshot.schemaVersion != 2 || snapshot.runtime != "pi-register-tool-v1") {
            throw ExtensionPackageException("EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED")
        }
        if (snapshot.tools.none { tool ->
                tool.type == ExtensionToolType.PI_REGISTER_TOOL.wireValue && tool.name == toolName
            }
        ) throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_NOT_AUTHORIZED")
        val modules = storedFiles.asSequence()
            .filter { it.relativePath.startsWith("dist/") && it.relativePath.endsWith(".js") }
            .associate { it.relativePath to it.content.decodeStrictUtf8() }
        val resources = storedFiles.asSequence()
            .filter { file ->
                file.relativePath.startsWith("resources/") ||
                    file.relativePath.substringAfterLast('/').startsWith("README", ignoreCase = true)
            }
            .associate { it.relativePath to it.content.decodeStrictUtf8() }
        return PiRegisterToolArtifact(
            packageId = snapshot.id,
            packageDigest = snapshot.packageDigest,
            entrypoint = requireNotNull(snapshot.entrypoint),
            modules = modules,
            tools = snapshot.tools.map { tool ->
                PiRegisterToolManifestTool(
                    name = tool.name,
                    label = requireNotNull(tool.label),
                    description = tool.description,
                    parameters = requireNotNull(tool.parameters),
                    promptSnippet = tool.promptSnippet,
                    promptGuidelines = tool.promptGuidelines,
                    executionMode = requireNotNull(tool.executionMode),
                )
            },
            hostTools = snapshot.hostTools.map { host ->
                PiRegisterHostTool(host.name, host.targetTool, host.capability)
            },
            httpPolicy = snapshot.httpPolicy,
            resources = resources,
        )
    }

    private fun requireValidPiState(values: Map<String, JsonElement>) {
        requireValidState(values.mapValues { (_, value) -> canonicalJson(value) })
    }

    private fun requireValidState(values: Map<String, String>) {
        if (values.size > MAX_EXTENSION_STATE_ENTRIES) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
        }
        var totalBytes = 0
        values.forEach { (key, valueJson) ->
            if (!STATE_KEY.matches(key)) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_KEY_INVALID")
            }
            val bytes = valueJson.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_EXTENSION_STATE_VALUE_BYTES) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_VALUE_TOO_LARGE")
            }
            totalBytes += bytes
            if (totalBytes > MAX_EXTENSION_STATE_TOTAL_BYTES) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
            }
            try {
                json.parseToJsonElement(valueJson)
            } catch (_: Exception) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_VALUE_INVALID")
            }
        }
    }

    private fun verifiedRecord(entity: ExtensionPackageEntity): ExtensionPackageRecord {
        val storedFiles = files.files(entity.packageId).map(ExtensionPackageFileEntity::toModel)
        if (storedFiles.size != entity.fileCount) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
        }
        val manifestFile = storedFiles.singleOrNull { it.relativePath == EXTENSION_MANIFEST_PATH }
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
        val parsed = parser.parse(manifestFile.content.toString(Charsets.UTF_8), storedFiles)
        if (
            parsed.canonicalJson != entity.manifestCanonicalJson ||
            parsed.snapshot.packageDigest != entity.packageDigest ||
            parsed.snapshot.id != entity.packageId
        ) throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
        return ExtensionPackageRecord(
            manifest = parsed,
            enabled = entity.enabled,
            fileCount = entity.fileCount,
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis,
        )
    }

    private fun recordWithoutFileVerification(entity: ExtensionPackageEntity): ExtensionPackageRecord {
        val snapshot = try {
            json.decodeFromString<ExtensionPackageSnapshot>(entity.manifestCanonicalJson)
        } catch (_: Exception) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
        }
        if (snapshot.id != entity.packageId || snapshot.packageDigest != entity.packageDigest) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
        }
        return ExtensionPackageRecord(
            manifest = ExtensionPackageManifest(snapshot, entity.manifestCanonicalJson),
            enabled = entity.enabled,
            fileCount = entity.fileCount,
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis,
        )
    }
}

private fun ByteArray.decodeStrictUtf8(): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: Exception) {
    throw ExtensionPackageException("EXTENSION_PACKAGE_STORAGE_CORRUPT")
}

private val STATE_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$")

class ExtensionPackageCatalogService(
    private val reader: ExtensionPackageImportReader,
    private val parser: ExtensionPackageManifestParser,
    private val repository: ExtensionPackageRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun importPackage(uri: Uri): ExtensionPackageImportResult {
        return when (val read = withContext(ioDispatcher) { reader.readForImport(uri) }) {
            is ExtensionPackageReadResult.Diagnostic ->
                ExtensionPackageImportResult.Diagnosed(read.diagnostic)
            is ExtensionPackageReadResult.Artifact -> {
                val manifest = parser.parse(read.document.manifestContent, read.document.files)
                ExtensionPackageImportResult.Installed(
                    repository.installWithResult(manifest, read.document.files),
                )
            }
        }
    }
}

private fun ExtensionPackageSnapshot.accessDiff(
    next: ExtensionPackageSnapshot,
): ExtensionPackageAccessDiff {
    fun ExtensionHostToolSnapshot.display() =
        "$name -> $targetTool${capability?.let { " [$it]" }.orEmpty()}"
    fun PiRegisterToolCredentialSlot.display() = "$slot @ $origin ($placement)"
    val currentHostTools = hostTools.map(ExtensionHostToolSnapshot::display).toSet()
    val nextHostTools = next.hostTools.map(ExtensionHostToolSnapshot::display).toSet()
    val currentCredentials = httpPolicy.credentialSlots
        .map(PiRegisterToolCredentialSlot::display).toSet()
    val nextCredentials = next.httpPolicy.credentialSlots
        .map(PiRegisterToolCredentialSlot::display).toSet()
    return ExtensionPackageAccessDiff(
        addedRequiredCapabilities =
            (next.requiredCapabilities.toSet() - requiredCapabilities.toSet()).sorted(),
        removedRequiredCapabilities =
            (requiredCapabilities.toSet() - next.requiredCapabilities.toSet()).sorted(),
        addedOptionalCapabilities =
            (next.optionalCapabilities.toSet() - optionalCapabilities.toSet()).sorted(),
        removedOptionalCapabilities =
            (optionalCapabilities.toSet() - next.optionalCapabilities.toSet()).sorted(),
        addedHostTools = (nextHostTools - currentHostTools).sorted(),
        removedHostTools = (currentHostTools - nextHostTools).sorted(),
        addedOrigins = (next.networkOrigins.toSet() - networkOrigins.toSet()).sorted(),
        removedOrigins = (networkOrigins.toSet() - next.networkOrigins.toSet()).sorted(),
        addedHttpMethods =
            (next.httpPolicy.methods.toSet() - httpPolicy.methods.toSet()).sorted(),
        removedHttpMethods =
            (httpPolicy.methods.toSet() - next.httpPolicy.methods.toSet()).sorted(),
        addedCredentialBindings = (nextCredentials - currentCredentials).sorted(),
        removedCredentialBindings = (currentCredentials - nextCredentials).sorted(),
        runtimeChanged = runtime != next.runtime || schemaVersion != next.schemaVersion,
    )
}

private fun ExtensionPackageManifest.toEntity(
    enabled: Boolean,
    fileCount: Int,
    createdAtMillis: Long,
    updatedAtMillis: Long,
) = ExtensionPackageEntity(
    packageId = snapshot.id,
    name = snapshot.name,
    version = snapshot.version,
    description = snapshot.description,
    manifestCanonicalJson = canonicalJson,
    packageDigest = snapshot.packageDigest,
    enabled = enabled,
    fileCount = fileCount,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private fun ExtensionPackageFile.toEntity(packageId: String) = ExtensionPackageFileEntity(
    packageId = packageId,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = content.size.toLong(),
    contentSha256 = contentSha256,
    content = content,
)

private fun ExtensionPackageFileEntity.toModel() = ExtensionPackageFile(
    relativePath = relativePath,
    mimeType = mimeType,
    content = content,
    contentSha256 = contentSha256,
)
