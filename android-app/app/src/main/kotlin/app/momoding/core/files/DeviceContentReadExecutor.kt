package app.momoding.core.files

import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DeviceOperationEntity
import app.momoding.core.data.TaskContentGrantEntity
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ContentReadExecutionFailure(
    val code: String,
    override val message: String,
) : Exception(message)

fun interface DeviceContentReadHandler {
    suspend fun execute(operation: DeviceOperationEntity): JsonObject
}

/**
 * Android-authoritative content reader for one approved device_files_read call.
 *
 * This is intentionally not a Runtime adapter. It consumes the Pi tool's exact native arguments,
 * creates one Android-local content scope, and resolves opaque aliases against the live SAF tree.
 */
class DeviceContentReadExecutor(
    database: MomodingDatabase,
    private val folders: AuthorizedFoldersRepository,
    private val sharedStorage: SharedStorageRepository? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DeviceContentReadHandler {
    private val dao = database.momodingDao()

    override suspend fun execute(operation: DeviceOperationEntity): JsonObject = try {
        withTimeout(READ_TIMEOUT_MILLIS) {
            require(operation.toolName == TOOL_NAME) { "Content executor received another tool" }
            require(!operation.sideEffect && operation.operationId == null) {
                "Content read must remain side-effect free"
            }
            val request = parseRequest(operation.argumentsCanonicalJson)
            val now = nowMillis()
            val requestExpiry = OffsetDateTime.parse(operation.expiresAt).toInstant().toEpochMilli()
            if (now >= requestExpiry) {
                throw ContentReadExecutionFailure(
                    "CONTENT_READ_EXPIRED",
                    "File content request expired",
                )
            }
            if (dao.activeStopFenceCount(operation.taskId) != 0) {
                throw ContentReadExecutionFailure(
                    "CONTENT_READ_CANCELLED",
                    "File content request was cancelled",
                )
            }
            val shared = sharedStorage?.takeIf { it.isSharedGrant(request.grantId) }
            if (shared != null) {
                shared.requireReadyGrant(request.grantId)
            } else {
                val selectedGrant = dao.draftForTask(operation.taskId)?.selectedGrantId
                if (selectedGrant != request.grantId) {
                    throw ContentReadExecutionFailure(
                        "TASK_FILE_GRANT_REQUIRED",
                        "This task has no matching mobile folder grant",
                    )
                }
            }
            val scopeExpiry = minOf(requestExpiry, now + MAX_SCOPE_MILLIS)
            val contentGrant = TaskContentGrantEntity(
                callId = operation.callId,
                taskId = operation.taskId,
                grantId = request.grantId,
                documentAliasesJson = buildJsonArray {
                    request.documents.forEach { add(it.alias) }
                }.toString(),
                mimeTypesJson = buildJsonArray {
                    request.documents.forEach { add(it.expectedMimeType) }
                }.toString(),
                perFileByteBudgetsJson = buildJsonArray {
                    request.documents.forEach { add(it.maxBytes) }
                }.toString(),
                totalByteBudget = request.totalMaxBytes.toLong(),
                consumedBytes = 0,
                expiresAtMillis = scopeExpiry,
                revokedAtMillis = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            val existing = dao.taskContentGrant(operation.callId)
            if (existing == null) {
                dao.insertTaskContentGrant(contentGrant)
            } else {
                if (
                    existing.taskId != contentGrant.taskId ||
                    existing.grantId != contentGrant.grantId ||
                    existing.documentAliasesJson != contentGrant.documentAliasesJson ||
                    existing.mimeTypesJson != contentGrant.mimeTypesJson ||
                    existing.perFileByteBudgetsJson != contentGrant.perFileByteBudgetsJson ||
                    existing.totalByteBudget != contentGrant.totalByteBudget
                ) {
                    throw ContentReadExecutionFailure(
                        "CONTENT_SCOPE_CONFLICT",
                        "File content approval no longer matches the request",
                    )
                }
                if (
                    existing.revokedAtMillis != null ||
                    existing.consumedBytes != 0L ||
                    now >= existing.expiresAtMillis
                ) {
                    throw ContentReadExecutionFailure(
                        "CONTENT_SCOPE_EXPIRED",
                        "File content approval expired or was revoked",
                    )
                }
            }
            val activeScope = dao.taskContentGrant(operation.callId)
                ?: throw ContentReadExecutionFailure(
                    "CONTENT_SCOPE_UNAVAILABLE",
                    "File content approval is unavailable",
                )
            if (activeScope.revokedAtMillis != null || nowMillis() >= activeScope.expiresAtMillis) {
                throw ContentReadExecutionFailure(
                    "CONTENT_SCOPE_EXPIRED",
                    "File content approval expired or was revoked",
                )
            }
            val requests = request.documents.map {
                AuthorizedDocumentReadRequest(
                    alias = it.alias,
                    expectedMimeType = it.expectedMimeType,
                    maxBytes = it.maxBytes,
                )
            }
            val read = if (shared != null) {
                shared.readText(
                    grantId = request.grantId,
                    requests = requests,
                    totalMaxBytes = request.totalMaxBytes,
                )
            } else {
                folders.readText(
                    grantId = request.grantId,
                    requests = requests,
                    totalMaxBytes = request.totalMaxBytes,
                )
            }
            val currentScope = dao.taskContentGrant(operation.callId)
                ?: throw ContentReadExecutionFailure(
                    "CONTENT_SCOPE_UNAVAILABLE",
                    "File content approval is unavailable",
                )
            if (
                currentScope.revokedAtMillis != null ||
                nowMillis() >= currentScope.expiresAtMillis ||
                dao.activeStopFenceCount(operation.taskId) != 0
            ) {
                throw ContentReadExecutionFailure(
                    "CONTENT_READ_CANCELLED",
                    "File content request was cancelled",
                )
            }
            check(read.totalBytes.toLong() <= currentScope.totalByteBudget)
            check(
                dao.updateTaskContentGrant(
                    currentScope.copy(
                        consumedBytes = read.totalBytes.toLong(),
                        updatedAtMillis = nowMillis(),
                    ),
                ) == 1,
            ) { "Content grant changed while consuming bytes" }
            buildJsonObject {
                put("grantId", read.grantId)
                put(
                    "documents",
                    buildJsonArray {
                        read.documents.forEach { document ->
                            add(
                                buildJsonObject {
                                    put("alias", document.alias)
                                    put("mimeType", document.mimeType)
                                    put("byteCount", document.byteCount)
                                    put("content", document.content)
                                },
                            )
                        }
                    },
                )
                put("totalBytes", read.totalBytes)
            }
        }
    } catch (failure: ContentReadExecutionFailure) {
        throw failure
    } catch (_: TimeoutCancellationException) {
        throw ContentReadExecutionFailure(
            "CONTENT_READ_TIMEOUT",
            "Android file content read timed out",
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        throw ContentReadExecutionFailure(
            "AUTHORIZED_FOLDER_UNAVAILABLE",
            "Mobile folder authorization is unavailable",
        )
    } catch (_: FileNotFoundException) {
        throw ContentReadExecutionFailure(
            "DOCUMENTS_PROVIDER_UNAVAILABLE",
            "Android Documents Provider is unavailable",
        )
    } catch (_: CharacterCodingException) {
        throw ContentReadExecutionFailure(
            "CONTENT_READ_POLICY_BLOCKED",
            "Android blocked this file content request",
        )
    } catch (_: IOException) {
        throw ContentReadExecutionFailure(
            "CONTENT_READ_FAILED",
            "Android could not complete this file content request",
        )
    } catch (_: IllegalArgumentException) {
        throw ContentReadExecutionFailure(
            "CONTENT_READ_POLICY_BLOCKED",
            "Android blocked this file content request",
        )
    } catch (_: IllegalStateException) {
        throw ContentReadExecutionFailure(
            "CONTENT_READ_FAILED",
            "Android could not complete this file content request",
        )
    }

    private fun parseRequest(raw: String): ContentReadRequest {
        val value = STRICT_JSON.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("Content read arguments must be an object")
        require(value.keys == setOf("grantId", "purpose", "documents", "totalMaxBytes"))
        val grantId = value["grantId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: throw IllegalArgumentException("grantId is invalid")
        val purpose = value["purpose"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 1_024 }
            ?: throw IllegalArgumentException("purpose is invalid")
        val documents = (value["documents"] as? JsonArray)
            ?.takeIf { it.size in 1..16 }
            ?.map { documentValue ->
                val document = documentValue as? JsonObject
                    ?: throw IllegalArgumentException("document is invalid")
                require(document.keys == setOf("alias", "expectedMimeType", "maxBytes"))
                val alias = document["alias"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(DOCUMENT_ALIAS::matches)
                    ?: throw IllegalArgumentException("alias is invalid")
                val mime = document["expectedMimeType"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: throw IllegalArgumentException("MIME is invalid")
                val maxBytes = document["maxBytes"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..MAX_PER_FILE_BYTES }
                    ?: throw IllegalArgumentException("file byte budget is invalid")
                ContentReadDocument(alias, mime, maxBytes)
            }
            ?: throw IllegalArgumentException("documents are invalid")
        require(documents.map { it.alias }.toSet().size == documents.size)
        val totalMaxBytes = value["totalMaxBytes"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it in 1..MAX_TOTAL_BYTES }
            ?: throw IllegalArgumentException("total byte budget is invalid")
        return ContentReadRequest(grantId, purpose, documents, totalMaxBytes)
    }

    private data class ContentReadRequest(
        val grantId: String,
        val purpose: String,
        val documents: List<ContentReadDocument>,
        val totalMaxBytes: Int,
    )

    private data class ContentReadDocument(
        val alias: String,
        val expectedMimeType: String,
        val maxBytes: Int,
    )

    private companion object {
        const val TOOL_NAME = "device_files_read"
        const val MAX_PER_FILE_BYTES = 262_144
        const val MAX_TOTAL_BYTES = 524_288
        const val MAX_SCOPE_MILLIS = 30 * 60_000L
        const val READ_TIMEOUT_MILLIS = 15_000L
        val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}
