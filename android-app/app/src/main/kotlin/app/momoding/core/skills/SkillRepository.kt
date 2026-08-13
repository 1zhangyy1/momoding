package app.momoding.core.skills

import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.SkillEntity
import app.momoding.core.data.SkillPackageFileEntity
import app.momoding.core.data.SkillPackageFileMetadata
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class SkillRecord(
    val skillId: String,
    val source: SkillSource,
    val resource: PhoneLocalSkillResource,
    val enabled: Boolean,
    val availability: SkillAvailability,
    val diagnosticCode: String?,
    val diagnosticMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class EnabledSkillResourceSet(
    val resources: List<PhoneLocalSkillResource>,
    val digest: String,
)

data class SkillInvocationContext(
    val requested: SkillRecord?,
    val enabledResourceSet: EnabledSkillResourceSet,
)

class SkillRepository(
    private val database: MomodingDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.skillDao()
    private val packageFiles = database.skillPackageFileDao()

    fun observeSkills(): Flow<List<SkillRecord>> = dao.observeAll().map { entities ->
        entities.map { entity -> recordWithPackage(entity.toRecord()) }
    }.flowOn(ioDispatcher)

    suspend fun skills(): List<SkillRecord> = withContext(ioDispatcher) {
        dao.all().map { entity -> recordWithPackage(entity.toRecord()) }
    }

    suspend fun importSkill(
        parsed: SkillDocumentParseResult,
        files: List<SkillPackageFile> = listOf(parsed.singleFilePackage()),
    ): SkillRecord =
        withContext(ioDispatcher) {
            database.runInTransaction<SkillRecord> {
                requireParsedSkill(parsed)
                requireValidSkillPackageFiles(files)
                requirePackageMatchesSkill(parsed, files)
                val name = parsed.resource.name
                require(dao.byName(name) == null) { "SKILL_NAME_DUPLICATED" }
                require(dao.count() < MAX_SKILL_RESOURCES) { "SKILL_RESOURCE_LIMIT_EXCEEDED" }
                val now = nowMillis()
                val entity = parsed.toEntity(
                    source = SkillSource.IMPORTED,
                    enabled = false,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
                dao.insert(entity)
                packageFiles.insertAll(files.map { it.toEntity(entity.skillId) })
                recordWithPackage(entity.toRecord())
            }
        }

    suspend fun seedBundledSkills(
        parsedSkills: List<SkillDocumentParseResult>,
        filesByName: Map<String, List<SkillPackageFile>> = parsedSkills.associate { parsed ->
            parsed.resource.name to listOf(parsed.singleFilePackage())
        },
    ): List<SkillRecord> = withContext(ioDispatcher) {
        database.runInTransaction<List<SkillRecord>> {
            require(parsedSkills.map { it.resource.name }.distinct().size == parsedSkills.size) {
                "SKILL_NAME_DUPLICATED"
            }
            parsedSkills.forEach(::requireParsedSkill)
            require(filesByName.keys == parsedSkills.map { it.resource.name }.toSet()) {
                "SKILL_PACKAGE_SET_MISMATCH"
            }
            val existingNames = dao.all().associateBy(SkillEntity::name)
            val newNames = parsedSkills.map { it.resource.name }.filterNot(existingNames::containsKey)
            require(dao.count() + newNames.size <= MAX_SKILL_RESOURCES) {
                "SKILL_RESOURCE_LIMIT_EXCEEDED"
            }
            parsedSkills.sortedBy { it.resource.name }.map { parsed ->
                val files = requireNotNull(filesByName[parsed.resource.name])
                requireValidSkillPackageFiles(files)
                requirePackageMatchesSkill(parsed, files)
                val existing = dao.byName(parsed.resource.name)
                require(existing == null || existing.source == SkillSource.BUNDLED.name) {
                    "SKILL_NAME_DUPLICATED"
                }
                val now = nowMillis()
                val entity = parsed.toEntity(
                    source = SkillSource.BUNDLED,
                    enabled = existing?.enabled ?: (parsed.availability == SkillAvailability.AVAILABLE),
                    createdAtMillis = existing?.createdAtMillis ?: now,
                    updatedAtMillis = now,
                ).let { candidate ->
                    if (parsed.availability == SkillAvailability.AVAILABLE) candidate
                    else candidate.copy(enabled = false)
                }
                if (existing == null) dao.insert(entity) else check(dao.update(entity) == 1)
                packageFiles.deleteForSkill(entity.skillId)
                packageFiles.insertAll(files.map { it.toEntity(entity.skillId) })
                recordWithPackage(entity.toRecord())
            }
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean): SkillRecord =
        withContext(ioDispatcher) {
            database.runInTransaction<SkillRecord> {
                val existing = requireNotNull(dao.byName(name)) { "SKILL_NOT_FOUND" }
                val record = recordWithPackage(existing.toRecord())
                if (enabled) {
                    require(record.availability == SkillAvailability.AVAILABLE) {
                        "SKILL_UNAVAILABLE"
                    }
                    requireValidSkillResource(record.resource)
                }
                val updated = existing.copy(enabled = enabled, updatedAtMillis = nowMillis())
                check(dao.update(updated) == 1) { "SKILL_UPDATE_FAILED" }
                recordWithPackage(updated.toRecord())
            }
        }

    suspend fun deleteImported(name: String): Boolean = withContext(ioDispatcher) {
        database.runInTransaction<Boolean> {
            val existing = dao.byName(name) ?: return@runInTransaction false
            require(existing.source == SkillSource.IMPORTED.name) { "BUNDLED_SKILL_CANNOT_BE_DELETED" }
            packageFiles.deleteForSkill(existing.skillId)
            dao.delete(existing.skillId) == 1
        }
    }

    suspend fun enabledResourceSet(): EnabledSkillResourceSet = withContext(ioDispatcher) {
        val enabled = dao.all().filter(SkillEntity::enabled).map { entity ->
            val record = entity.toRecord()
            require(record.availability == SkillAvailability.AVAILABLE) {
                "SKILL_ENABLED_RECORD_UNAVAILABLE"
            }
            requireValidSkillResource(record.resource)
            resourceWithPackage(record)
        }.sortedBy(PhoneLocalSkillResource::name)
        EnabledSkillResourceSet(enabled, skillResourceSetDigest(enabled))
    }

    suspend fun invocationContext(name: String): SkillInvocationContext = withContext(ioDispatcher) {
        database.runInTransaction<SkillInvocationContext> {
            val records = dao.all().map { entity -> recordWithPackage(entity.toRecord()) }
            val enabled = records.filter(SkillRecord::enabled).map { record ->
                require(record.availability == SkillAvailability.AVAILABLE) {
                    "SKILL_ENABLED_RECORD_UNAVAILABLE"
                }
                requireValidSkillResource(record.resource)
                resourceWithPackage(record)
            }.sortedBy(PhoneLocalSkillResource::name)
            SkillInvocationContext(
                requested = records.singleOrNull { it.resource.name == name },
                enabledResourceSet = EnabledSkillResourceSet(
                    resources = enabled,
                    digest = skillResourceSetDigest(enabled),
                ),
            )
        }
    }

    suspend fun listEnabledResources(
        skillName: String,
        prefix: String?,
        offset: Int,
        limit: Int,
    ): SkillResourceListPage =
        withContext(ioDispatcher) {
            requireValidSkillNameForLookup(skillName)
            val normalizedPrefix = prefix?.also(::requireValidSkillResourcePrefix)
            require(offset >= 0) { "SKILL_RESOURCE_OFFSET_INVALID" }
            require(limit in 1..MAX_SKILL_RESOURCE_LIST_ITEMS) { "SKILL_RESOURCE_LIMIT_INVALID" }
            val skill = requireEnabledSkill(skillName)
            val matching = packageMetadata(skill).map { entity ->
                SkillResourceEntry(
                    path = entity.relativePath,
                    mimeType = entity.mimeType,
                    byteSize = entity.byteSize,
                    contentSha256 = entity.contentSha256,
                )
            }.filter { entry ->
                normalizedPrefix == null || entry.path == normalizedPrefix ||
                    entry.path.startsWith("$normalizedPrefix/")
            }
            require(offset <= matching.size) { "SKILL_RESOURCE_OFFSET_INVALID" }
            val items = matching.drop(offset).take(limit)
            SkillResourceListPage(
                items = items,
                offset = offset,
                nextOffset = offset + items.size,
                eof = offset + items.size == matching.size,
            )
        }

    suspend fun readEnabledTextResource(
        skillName: String,
        path: String,
        offset: Long,
        limit: Int,
    ): SkillTextResourcePage = withContext(ioDispatcher) {
        requireValidSkillNameForLookup(skillName)
        requireValidSkillResourcePath(path)
        require(offset >= 0) { "SKILL_RESOURCE_OFFSET_INVALID" }
        require(limit in MIN_SKILL_RESOURCE_READ_BYTES..MAX_SKILL_RESOURCE_READ_BYTES) {
            "SKILL_RESOURCE_LIMIT_INVALID"
        }
        val skill = requireEnabledSkill(skillName)
        val entity = packageMetadata(skill).singleOrNull { it.relativePath == path }
            ?: throw SkillResourceReadException("SKILL_RESOURCE_NOT_FOUND")
        if (!entity.isReadableTextResource()) {
            throw SkillResourceReadException("SKILL_RESOURCE_BINARY")
        }
        val bytes = packageFiles.content(skill.skillId, path)
            ?: legacyContent(skill, entity)
            ?: throw SkillResourceReadException("SKILL_RESOURCE_CORRUPT")
        if (bytes.size.toLong() != entity.byteSize || bytes.sha256() != entity.contentSha256) {
            throw SkillResourceReadException("SKILL_RESOURCE_CORRUPT")
        }
        val start = offset.toIntOrNullExact()
            ?.takeIf { it in 0..bytes.size }
            ?: throw SkillResourceReadException("SKILL_RESOURCE_OFFSET_INVALID")
        if (start < bytes.size && bytes[start].isUtf8ContinuationByte()) {
            throw SkillResourceReadException("SKILL_RESOURCE_OFFSET_INVALID")
        }
        if (start == bytes.size) {
            return@withContext SkillTextResourcePage(
                skillName = skillName,
                path = path,
                mimeType = entity.mimeType,
                byteSize = entity.byteSize,
                offset = offset,
                nextOffset = offset,
                eof = true,
                content = "",
            )
        }
        var end = minOf(bytes.size, start + limit)
        while (end < bytes.size && end > start && bytes[end].isUtf8ContinuationByte()) end -= 1
        if (end == start) throw SkillResourceReadException("SKILL_RESOURCE_LIMIT_INVALID")
        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, end - start))
                .toString()
        } catch (_: Exception) {
            throw SkillResourceReadException("SKILL_RESOURCE_BINARY")
        }
        if (content.any(::isUnsafeTextControl)) {
            throw SkillResourceReadException("SKILL_RESOURCE_BINARY")
        }
        SkillTextResourcePage(
            skillName = skillName,
            path = path,
            mimeType = entity.mimeType,
            byteSize = entity.byteSize,
            offset = offset,
            nextOffset = end.toLong(),
            eof = end == bytes.size,
            content = content,
        )
    }

    private fun requireParsedSkill(parsed: SkillDocumentParseResult) {
        requireValidSkillResource(parsed.resource)
        require(parsed.availability != SkillAvailability.ERROR) { "SKILL_PARSE_ERROR_NOT_PERSISTABLE" }
        if (parsed.availability == SkillAvailability.AVAILABLE) {
            require(parsed.diagnosticCode == null && parsed.diagnosticMessage == null) {
                "SKILL_AVAILABLE_DIAGNOSTIC_INVALID"
            }
        } else {
            require(!parsed.diagnosticCode.isNullOrBlank()) { "SKILL_DIAGNOSTIC_CODE_REQUIRED" }
            require(!parsed.diagnosticMessage.isNullOrBlank()) { "SKILL_DIAGNOSTIC_MESSAGE_REQUIRED" }
        }
        require(parsed.diagnosticCode?.length.orZero() <= 128) { "SKILL_DIAGNOSTIC_CODE_INVALID" }
        require(parsed.diagnosticMessage?.length.orZero() <= 512) { "SKILL_DIAGNOSTIC_MESSAGE_INVALID" }
        require(parsed.diagnosticMessage?.contains('\u0000') != true) { "SKILL_DIAGNOSTIC_MESSAGE_INVALID" }
        require(parsed.diagnosticMessage?.contains("content://", ignoreCase = true) != true) {
            "SKILL_DIAGNOSTIC_MESSAGE_INVALID"
        }
    }

    private fun requireEnabledSkill(name: String): SkillRecord {
        val record = dao.byName(name)?.toRecord()
            ?: throw SkillResourceReadException("SKILL_NOT_FOUND")
        if (!record.enabled || record.availability != SkillAvailability.AVAILABLE) {
            throw SkillResourceReadException("SKILL_NOT_ENABLED")
        }
        return record
    }

    private fun packageMetadata(record: SkillRecord): List<SkillPackageFileMetadata> {
        val stored = packageFiles.metadataForSkill(record.skillId)
        if (stored.isNotEmpty()) return stored.onEach(::requireValidPackageMetadata)
        val bytes = record.resource.content.toByteArray(Charsets.UTF_8)
        return listOf(
            SkillPackageFileMetadata(
                relativePath = "SKILL.md",
                mimeType = "text/markdown",
                byteSize = bytes.size.toLong(),
                contentSha256 = bytes.sha256(),
            ),
        )
    }

    private fun resourceWithPackage(record: SkillRecord): PhoneLocalSkillResource {
        val files = packageMetadata(record)
        val metadata = packageMetadataForStored(record.resource, files)
        return record.resource.copy(packageDigest = metadata.first, packageFileCount = metadata.second)
    }

    private fun legacyContent(
        record: SkillRecord,
        metadata: SkillPackageFileMetadata,
    ): ByteArray? = if (metadata.relativePath == "SKILL.md") {
        record.resource.content.toByteArray(Charsets.UTF_8)
    } else {
        null
    }

    private fun recordWithPackage(record: SkillRecord): SkillRecord =
        record.copy(resource = resourceWithPackage(record))
}

data class SkillResourceEntry(
    val path: String,
    val mimeType: String,
    val byteSize: Long,
    val contentSha256: String,
)

data class SkillResourceListPage(
    val items: List<SkillResourceEntry>,
    val offset: Int,
    val nextOffset: Int,
    val eof: Boolean,
)

data class SkillTextResourcePage(
    val skillName: String,
    val path: String,
    val mimeType: String,
    val byteSize: Long,
    val offset: Long,
    val nextOffset: Long,
    val eof: Boolean,
    val content: String,
)

class SkillResourceReadException(
    val code: String,
) : IllegalStateException(code)

private fun SkillDocumentParseResult.toEntity(
    source: SkillSource,
    enabled: Boolean,
    createdAtMillis: Long,
    updatedAtMillis: Long,
): SkillEntity = SkillEntity(
    skillId = "skill:${source.name.lowercase()}:${resource.name}",
    source = source.name,
    name = resource.name,
    description = resource.description,
    content = resource.content,
    contentSha256 = resource.contentSha256,
    disableModelInvocation = resource.disableModelInvocation,
    enabled = enabled,
    availability = availability.name,
    diagnosticCode = diagnosticCode,
    diagnosticMessage = diagnosticMessage,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private fun SkillDocumentParseResult.singleFilePackage(): SkillPackageFile {
    val bytes = resource.content.toByteArray(Charsets.UTF_8)
    return SkillPackageFile(
        relativePath = "SKILL.md",
        mimeType = "text/markdown",
        content = bytes,
        contentSha256 = bytes.sha256(),
    )
}

private fun SkillPackageFile.toEntity(skillId: String): SkillPackageFileEntity =
    SkillPackageFileEntity(
        skillId = skillId,
        relativePath = relativePath,
        mimeType = mimeType,
        byteSize = content.size.toLong(),
        contentSha256 = contentSha256,
        content = content,
    )

private fun requirePackageMatchesSkill(
    parsed: SkillDocumentParseResult,
    files: List<SkillPackageFile>,
) {
    val skillFile = files.singleOrNull { it.relativePath == "SKILL.md" }
        ?: throw IllegalArgumentException("SKILL_PACKAGE_DOCUMENT_MISSING")
    val metadata = packageMetadataFor(parsed.resource, files)
    require(parsed.resource.packageDigest == metadata.first) { "SKILL_PACKAGE_DIGEST_MISMATCH" }
    require(parsed.resource.packageFileCount == metadata.second) { "SKILL_PACKAGE_FILE_COUNT_MISMATCH" }
    val legacyGeneratedSingleFile = files.size == 1 &&
        skillFile.contentSha256 == parsed.resource.contentSha256
    if (!legacyGeneratedSingleFile) {
        require(parsed.sourceDocumentSha256 == skillFile.contentSha256) {
            "SKILL_PACKAGE_DOCUMENT_MISMATCH"
        }
    }
}

private fun packageMetadataFor(
    resource: PhoneLocalSkillResource,
    files: List<SkillPackageFile>,
): Pair<String, Int> {
    return packageMetadataForStored(resource, files.map(SkillPackageFile::toMetadata))
}

private fun packageMetadataForStored(
    resource: PhoneLocalSkillResource,
    files: List<SkillPackageFileMetadata>,
): Pair<String, Int> {
    val skillFile = files.single { it.relativePath == "SKILL.md" }
    val legacySingleFile = files.size == 1 &&
        skillFile.contentSha256 == resource.contentSha256 &&
        skillFile.byteSize == resource.content.toByteArray(Charsets.UTF_8).size.toLong()
    val descriptors = files.map(SkillPackageFileMetadata::toDescriptor)
    return (if (legacySingleFile) resource.contentSha256 else skillPackageDigestFromDescriptors(descriptors)) to files.size
}

private fun requireValidPackageMetadata(metadata: SkillPackageFileMetadata) =
    requireValidSkillPackageDescriptor(metadata.toDescriptor())

private fun SkillPackageFile.toMetadata(): SkillPackageFileMetadata = SkillPackageFileMetadata(
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = content.size.toLong(),
    contentSha256 = contentSha256,
)

private fun SkillPackageFileMetadata.toDescriptor(): SkillPackageFileDescriptor =
    SkillPackageFileDescriptor(relativePath, mimeType, byteSize, contentSha256)

private fun SkillPackageFileMetadata.isReadableTextResource(): Boolean {
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    if (normalizedMime.startsWith("text/")) return true
    if (normalizedMime in TEXT_APPLICATION_MIME_TYPES) return true
    return normalizedMime == "application/octet-stream" &&
        relativePath.substringAfterLast('.', "").lowercase() in TEXT_FILE_EXTENSIONS
}

private fun isUnsafeTextControl(character: Char): Boolean =
    character == '\u0000' || (character.code < 0x20 && character !in "\t\n\r")

private fun requireValidSkillNameForLookup(name: String) {
    require(name.length in 1..64 && Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$").matches(name)) {
        "SKILL_NAME_INVALID"
    }
}

private fun requireValidSkillResourcePrefix(prefix: String) {
    if (prefix.isEmpty()) return
    requireValidSkillResourcePath(prefix)
}

private fun Long.toIntOrNullExact(): Int? =
    takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun Byte.isUtf8ContinuationByte(): Boolean = (toInt() and 0xC0) == 0x80

private const val MAX_SKILL_RESOURCE_LIST_ITEMS = 64
const val MIN_SKILL_RESOURCE_READ_BYTES: Int = 256
const val MAX_SKILL_RESOURCE_READ_BYTES: Int = 65_536

private val TEXT_APPLICATION_MIME_TYPES = setOf(
    "application/json",
    "application/ld+json",
    "application/javascript",
    "application/xml",
    "application/x-httpd-php",
    "application/x-sh",
    "application/yaml",
    "application/toml",
)

private val TEXT_FILE_EXTENSIONS = setOf(
    "bash", "c", "cc", "conf", "cpp", "css", "csv", "go", "h", "hpp", "html", "ini",
    "java", "js", "json", "jsx", "kt", "kts", "md", "mjs", "php", "properties", "py",
    "rb", "rs", "sh", "sql", "swift", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml", "zsh",
)

private fun SkillEntity.toRecord(): SkillRecord {
    val parsedSource = runCatching { SkillSource.valueOf(source) }
        .getOrElse { throw IllegalStateException("SKILL_RECORD_CORRUPT") }
    val parsedAvailability = runCatching { SkillAvailability.valueOf(availability) }
        .getOrElse { throw IllegalStateException("SKILL_RECORD_CORRUPT") }
    val resource = PhoneLocalSkillResource(
        name = name,
        description = description,
        content = content,
        contentSha256 = contentSha256,
        disableModelInvocation = disableModelInvocation,
    )
    runCatching { requireValidSkillResource(resource) }
        .getOrElse { throw IllegalStateException("SKILL_RECORD_CORRUPT") }
    if (skillId != "skill:${parsedSource.name.lowercase()}:$name") {
        throw IllegalStateException("SKILL_RECORD_CORRUPT")
    }
    val diagnosticIsValid = if (parsedAvailability == SkillAvailability.AVAILABLE) {
        diagnosticCode == null && diagnosticMessage == null
    } else {
        !diagnosticCode.isNullOrBlank() &&
            diagnosticCode.length <= 128 &&
            !diagnosticMessage.isNullOrBlank() &&
            diagnosticMessage.length <= 512 &&
            '\u0000' !in diagnosticMessage &&
            !diagnosticMessage.contains("content://", ignoreCase = true)
    }
    if (!diagnosticIsValid) throw IllegalStateException("SKILL_RECORD_CORRUPT")
    return SkillRecord(
        skillId = skillId,
        source = parsedSource,
        resource = resource,
        enabled = enabled,
        availability = parsedAvailability,
        diagnosticCode = diagnosticCode,
        diagnosticMessage = diagnosticMessage,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
}

private fun Int?.orZero(): Int = this ?: 0
