package app.momoding.core.skills

import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.SkillEntity
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

    fun observeSkills(): Flow<List<SkillRecord>> = dao.observeAll().map { entities ->
        entities.map(SkillEntity::toRecord)
    }.flowOn(ioDispatcher)

    suspend fun skills(): List<SkillRecord> = withContext(ioDispatcher) {
        dao.all().map(SkillEntity::toRecord)
    }

    suspend fun importSkill(parsed: SkillDocumentParseResult): SkillRecord =
        withContext(ioDispatcher) {
            database.runInTransaction<SkillRecord> {
                requireParsedSkill(parsed)
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
                entity.toRecord()
            }
        }

    suspend fun seedBundledSkills(
        parsedSkills: List<SkillDocumentParseResult>,
    ): List<SkillRecord> = withContext(ioDispatcher) {
        database.runInTransaction<List<SkillRecord>> {
            require(parsedSkills.map { it.resource.name }.distinct().size == parsedSkills.size) {
                "SKILL_NAME_DUPLICATED"
            }
            parsedSkills.forEach(::requireParsedSkill)
            val existingNames = dao.all().associateBy(SkillEntity::name)
            val newNames = parsedSkills.map { it.resource.name }.filterNot(existingNames::containsKey)
            require(dao.count() + newNames.size <= MAX_SKILL_RESOURCES) {
                "SKILL_RESOURCE_LIMIT_EXCEEDED"
            }
            parsedSkills.sortedBy { it.resource.name }.map { parsed ->
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
                entity.toRecord()
            }
        }
    }

    suspend fun setEnabled(name: String, enabled: Boolean): SkillRecord =
        withContext(ioDispatcher) {
            database.runInTransaction<SkillRecord> {
                val existing = requireNotNull(dao.byName(name)) { "SKILL_NOT_FOUND" }
                val record = existing.toRecord()
                if (enabled) {
                    require(record.availability == SkillAvailability.AVAILABLE) {
                        "SKILL_UNAVAILABLE"
                    }
                    requireValidSkillResource(record.resource)
                }
                val updated = existing.copy(enabled = enabled, updatedAtMillis = nowMillis())
                check(dao.update(updated) == 1) { "SKILL_UPDATE_FAILED" }
                updated.toRecord()
            }
        }

    suspend fun deleteImported(name: String): Boolean = withContext(ioDispatcher) {
        database.runInTransaction<Boolean> {
            val existing = dao.byName(name) ?: return@runInTransaction false
            require(existing.source == SkillSource.IMPORTED.name) { "BUNDLED_SKILL_CANNOT_BE_DELETED" }
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
            record.resource
        }.sortedBy(PhoneLocalSkillResource::name)
        EnabledSkillResourceSet(enabled, skillResourceSetDigest(enabled))
    }

    suspend fun invocationContext(name: String): SkillInvocationContext = withContext(ioDispatcher) {
        database.runInTransaction<SkillInvocationContext> {
            val records = dao.all().map(SkillEntity::toRecord)
            val enabled = records.filter(SkillRecord::enabled).map { record ->
                require(record.availability == SkillAvailability.AVAILABLE) {
                    "SKILL_ENABLED_RECORD_UNAVAILABLE"
                }
                requireValidSkillResource(record.resource)
                record.resource
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
}

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
