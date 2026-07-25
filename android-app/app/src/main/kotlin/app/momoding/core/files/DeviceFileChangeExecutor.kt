package app.momoding.core.files

import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionTerminalOrigin
import app.momoding.core.data.AttentionTerminalWrite
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DeviceOperationEntity
import app.momoding.core.data.FileChangeSetEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class FileChangeSetState {
    PREPARED,
    AWAITING_APPROVAL,
    APPROVED,
    COMMITTING,
    COMPLETED,
    PARTIALLY_FAILED,
    FAILED,
    REJECTED,
    EXPIRED,
    CANCELLED,
    UNKNOWN,
}

class FileChangeExecutionFailure(
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * Owns the native Pi prepare/commit contract on Android.
 *
 * Prepare is intercepted as a no-side-effect device tool. Commit is accepted by the durable
 * attention ledger, then bound here to exactly one prepared plan and outer operationId.
 */
class DeviceFileChangeExecutor(
    private val database: MomodingDatabase,
    private val folders: AuthorizedFoldersRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val receiptId: () -> String = { UUID.randomUUID().toString() },
    private val tokenBytes: () -> ByteArray = {
        ByteArray(32).also(SecureRandom()::nextBytes)
    },
) {
    private val dao = database.momodingDao()

    fun handlesPrepare(toolName: String): Boolean = toolName == PREPARE_TOOL

    suspend fun prepare(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame {
        if (frame.toolName != PREPARE_TOOL) {
            return frame.failed("UNSUPPORTED_DEVICE_CAPABILITY", "Device capability is unavailable")
        }
        if (
            frame.sideEffect ||
            frame.operationId != null ||
            frame.capabilityVersion != CAPABILITY_VERSION
        ) {
            return frame.failed(
                "UNSAFE_DEVICE_TOOL_REQUEST",
                "File prepare request is outside the active task capability",
            )
        }
        return try {
            withTimeout(PREPARE_TIMEOUT_MILLIS) {
                val requestExpiry = parseExpiry(frame.expiresAt)
                val now = nowMillis()
                if (now >= requestExpiry) {
                    return@withTimeout frame.failed(
                        "FILE_CHANGE_EXPIRED",
                        "File change request expired",
                    )
                }
                val request = parsePrepare(frame.arguments)
                val preparedId = normalizeUuid(frame.callId, "callId")
                val taskId = normalizeUuid(frame.taskId, "taskId")
                val operationsJson = encodeOperations(request.operations)
                requireTaskGrant(frame.taskId, request.grantId, requireWrite = true)
                dao.fileChangeSet(preparedId)?.let { existing ->
                    require(existing.taskId == taskId)
                    require(existing.grantId == request.grantId)
                    require(existing.purpose == request.purpose)
                    require(existing.operationsCanonicalJson == operationsJson)
                    return@withTimeout frame.succeeded(prepareResult(existing))
                }
                folders.validateChanges(request.grantId, request.operations)
                val listing = folders.mutationMetadata(request.grantId)
                val preview = buildPreview(request.operations, listing.documents)
                val expiry = minOf(requestExpiry, now + MAX_PREPARED_LIFETIME_MILLIS)
                val digestBinding = buildJsonObject {
                    put("preparedId", preparedId)
                    put("taskId", taskId)
                    put("grantId", request.grantId)
                    put("purpose", request.purpose)
                    put("operations", STRICT_JSON.parseToJsonElement(operationsJson))
                    put("expiresAtMillis", expiry)
                }
                val planDigest = sha256(digestBinding.toString())
                val entity = FileChangeSetEntity(
                    preparedId = preparedId,
                    taskId = taskId,
                    grantId = request.grantId,
                    purpose = request.purpose,
                    planDigest = planDigest,
                    operationsCanonicalJson = operationsJson,
                    previewCanonicalJson = preview.toString(),
                    operationCount = request.operations.size,
                    state = FileChangeSetState.PREPARED.name,
                    expiresAtMillis = expiry,
                    commitCallId = null,
                    commitOperationId = null,
                    approvalTokenSha256 = null,
                    approvalBindingSha256 = null,
                    approvalReceiptId = null,
                    resultCanonicalJson = null,
                    failureCode = null,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
                database.runInTransaction {
                    val existing = dao.fileChangeSet(preparedId)
                    if (existing == null) {
                        dao.insertFileChangeSet(entity)
                    } else {
                        require(existing.taskId == entity.taskId)
                        require(existing.grantId == entity.grantId)
                        require(existing.purpose == entity.purpose)
                        require(existing.planDigest == entity.planDigest)
                        require(existing.operationsCanonicalJson == entity.operationsCanonicalJson)
                    }
                }
                frame.succeeded(prepareResult(entity))
            }
        } catch (_: TimeoutCancellationException) {
            frame.failed("DEVICE_TOOL_TIMEOUT", "Android file prepare timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            frame.failed(
                "TASK_FILE_GRANT_REQUIRED",
                "This task has no matching writable mobile folder grant",
            )
        } catch (_: IllegalArgumentException) {
            frame.failed("INVALID_FILE_CHANGE_SET", "Android rejected the proposed file changes")
        } catch (_: IllegalStateException) {
            frame.failed("FILE_PRECONDITION_FAILED", "The proposed file changes are not currently safe")
        }
    }

    fun bindCommit(operation: DeviceOperationEntity): FileChangeSetEntity =
        database.runInTransaction<FileChangeSetEntity> {
            val request = parseCommit(operation)
            val current = dao.fileChangeSet(request.preparedId)
                ?: throw FileChangeExecutionFailure(
                    "PREPARED_CHANGE_NOT_FOUND",
                    "Prepared file changes are unavailable",
                )
            require(current.taskId == operation.taskId) {
                "Prepared file changes belong to another task"
            }
            require(current.planDigest == request.planDigest) {
                "Prepared file digest changed"
            }
            requireTaskGrantSync(operation.taskId, current.grantId)
            if (nowMillis() >= current.expiresAtMillis) {
                val expired = current.copy(
                    state = FileChangeSetState.EXPIRED.name,
                    failureCode = "FILE_CHANGE_EXPIRED",
                    updatedAtMillis = nowMillis(),
                )
                check(dao.updateFileChangeSet(expired) == 1)
                throw FileChangeExecutionFailure(
                    "FILE_CHANGE_EXPIRED",
                    "Prepared file changes expired",
                )
            }
            val bound = when {
                current.commitCallId == null && current.state == FileChangeSetState.PREPARED.name ->
                    current.copy(
                        state = FileChangeSetState.AWAITING_APPROVAL.name,
                        commitCallId = operation.callId,
                        commitOperationId = requireNotNull(operation.operationId),
                        updatedAtMillis = nowMillis(),
                    )
                current.commitCallId == operation.callId &&
                    current.commitOperationId == operation.operationId -> current
                else -> throw FileChangeExecutionFailure(
                    "FILE_COMMIT_CONFLICT",
                    "Prepared file changes are already bound to another commit",
                )
            }
            if (bound != current) check(dao.updateFileChangeSet(bound) == 1)
            bound
        }

    suspend fun approveAndCommit(
        operation: DeviceOperationEntity,
        durableTerminal: ((AttentionTerminalWrite) -> Unit)? = null,
    ): JsonObject {
        val current = bindCommit(operation)
        if (current.state != FileChangeSetState.AWAITING_APPROVAL.name) {
            current.resultCanonicalJson?.let {
                return STRICT_JSON.parseToJsonElement(it) as JsonObject
            }
            throw FileChangeExecutionFailure(
                "FILE_COMMIT_NOT_ACTIONABLE",
                "Prepared file changes are no longer actionable",
            )
        }
        if (dao.activeStopFenceCount(operation.taskId) != 0) {
            cancel(operation, "FILE_COMMIT_CANCELLED")
            throw FileChangeExecutionFailure(
                "FILE_COMMIT_CANCELLED",
                "File commit was cancelled",
            )
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes())
        val binding = approvalBinding(current, operation)
        val bindingSha256 = sha256(binding)
        val tokenSha256 = sha256("$binding\u0000$token")
        val approvedAtMillis = nowMillis()
        val approved = database.runInTransaction<FileChangeSetEntity> {
            val latest = requireNotNull(dao.fileChangeSet(current.preparedId))
            require(latest.state == FileChangeSetState.AWAITING_APPROVAL.name)
            val next = latest.copy(
                state = FileChangeSetState.APPROVED.name,
                approvalTokenSha256 = tokenSha256,
                approvalBindingSha256 = bindingSha256,
                updatedAtMillis = approvedAtMillis,
            )
            check(dao.updateFileChangeSet(next) == 1)
            next
        }
        check(sha256("${approvalBinding(approved, operation)}\u0000$token") == tokenSha256) {
            "Local approval token binding changed"
        }
        val committing = database.runInTransaction<FileChangeSetEntity> {
            val latest = requireNotNull(dao.fileChangeSet(approved.preparedId))
            require(latest.state == FileChangeSetState.APPROVED.name)
            require(latest.approvalTokenSha256 == tokenSha256)
            val next = latest.copy(
                state = FileChangeSetState.COMMITTING.name,
                updatedAtMillis = nowMillis(),
            )
            check(dao.updateFileChangeSet(next) == 1)
            next
        }
        val mutations = decodeOperations(committing.operationsCanonicalJson)
        val commit = try {
            folders.commitChanges(
                grantId = committing.grantId,
                mutations = mutations,
                stopRequested = { dao.activeStopFenceCount(operation.taskId) != 0 },
            )
        } catch (cancelled: CancellationException) {
            persistUnknownTerminal(
                changeSet = committing,
                operation = operation,
                failureCode = "FILE_COMMIT_INTERRUPTED",
                recordedAtMillis = approvedAtMillis,
                durableTerminal = durableTerminal,
            )
            throw cancelled
        } catch (_: Throwable) {
            persistUnknownTerminal(
                changeSet = committing,
                operation = operation,
                failureCode = "FILE_COMMIT_OUTCOME_UNKNOWN",
                recordedAtMillis = approvedAtMillis,
                durableTerminal = durableTerminal,
            )
            throw FileChangeExecutionFailure(
                "FILE_COMMIT_OUTCOME_UNKNOWN",
                "Android could not prove the final file state",
            )
        }
        val receipt = receiptId().also { normalizeUuid(it, "approvalReceiptId") }
        val result = buildJsonObject {
            put("preparedId", committing.preparedId)
            put("planDigest", committing.planDigest)
            put("outcome", commit.outcome)
            put("appliedCount", commit.appliedCount)
            put("approvalReceiptId", receipt)
            put(
                "results",
                buildJsonArray {
                    commit.results.forEach { item ->
                        add(
                            buildJsonObject {
                                put("operationId", item.operationId)
                                put("kind", item.kind)
                                put("state", item.state)
                                item.resultAlias?.let { put("resultAlias", it) }
                                item.errorCode?.let { put("errorCode", it) }
                            },
                        )
                    }
                },
            )
        }
        database.runInTransaction {
            val latest = requireNotNull(dao.fileChangeSet(committing.preparedId))
            require(latest.state == FileChangeSetState.COMMITTING.name)
            val finalState = when (commit.outcome) {
                "completed" -> FileChangeSetState.COMPLETED
                "failed" -> FileChangeSetState.FAILED
                "partially_failed", "partially_cancelled" ->
                    FileChangeSetState.PARTIALLY_FAILED
                "cancelled" -> FileChangeSetState.CANCELLED
                "unknown", "partially_unknown" -> FileChangeSetState.UNKNOWN
                else -> FileChangeSetState.UNKNOWN
            }
            check(
                dao.updateFileChangeSet(
                    latest.copy(
                        state = finalState.name,
                        approvalReceiptId = receipt,
                        resultCanonicalJson = result.toString(),
                        failureCode = commit.results.firstOrNull {
                            it.state != "succeeded"
                        }?.errorCode,
                        updatedAtMillis = nowMillis(),
                    ),
                ) == 1,
            )
            durableTerminal?.invoke(
                AttentionTerminalWrite(
                    frame = succeededFrame(operation, result),
                    origin = AttentionTerminalOrigin.USER,
                    nowMillis = approvedAtMillis,
                ),
            )
        }
        return result
    }

    /**
     * Closes only file commits that cannot still be safely actioned after process death.
     *
     * A durable result is reconstructed exactly. APPROVED means no Provider call was proven to
     * start, while COMMITTING/UNKNOWN means Android must fail closed and never replay the plan.
     * The file row and repaired Attention terminal share one Room transaction.
     */
    fun repairInterruptedCommit(
        operation: DeviceOperationEntity,
        durableTerminal: (AttentionTerminalWrite) -> Unit,
    ): Boolean = database.runInTransaction<Boolean> {
        val current = dao.fileChangeSetByCommitCall(operation.callId)
            ?: return@runInTransaction false
        require(current.taskId == operation.taskId)
        require(current.commitCallId == operation.callId)
        require(current.commitOperationId == operation.operationId)

        current.resultCanonicalJson?.let { canonical ->
            val result = STRICT_JSON.parseToJsonElement(canonical) as JsonObject
            durableTerminal(
                AttentionTerminalWrite(
                    frame = succeededFrame(operation, result),
                    origin = AttentionTerminalOrigin.LOCAL_RECOVERY,
                    nowMillis = nowMillis(),
                ),
            )
            return@runInTransaction true
        }

        val failure = when (current.state) {
            FileChangeSetState.APPROVED.name ->
                "FILE_COMMIT_NOT_ACTIONABLE" to
                    "Prepared file changes are no longer actionable"
            FileChangeSetState.COMMITTING.name,
            FileChangeSetState.UNKNOWN.name,
            -> "FILE_COMMIT_OUTCOME_UNKNOWN" to
                "Android could not prove the final file state"
            FileChangeSetState.REJECTED.name,
            FileChangeSetState.FAILED.name,
            -> "FILE_COMMIT_NOT_ACTIONABLE" to
                "Prepared file changes are no longer actionable"
            FileChangeSetState.EXPIRED.name ->
                "FILE_CHANGE_EXPIRED" to "Prepared file changes expired"
            FileChangeSetState.CANCELLED.name ->
                "FILE_COMMIT_CANCELLED" to "File commit was cancelled"
            else -> return@runInTransaction false
        }
        val repaired = current.copy(
            state = if (current.state == FileChangeSetState.COMMITTING.name) {
                FileChangeSetState.UNKNOWN.name
            } else {
                current.state
            },
            failureCode = failure.first,
            updatedAtMillis = nowMillis(),
        )
        if (repaired != current) check(dao.updateFileChangeSet(repaired) == 1)
        durableTerminal(
            AttentionTerminalWrite(
                frame = failedFrame(operation, failure.first, failure.second),
                origin = AttentionTerminalOrigin.FAILED_CLOSED,
                nowMillis = nowMillis(),
            ),
        )
        true
    }

    fun reject(operation: DeviceOperationEntity) {
        val bound = bindCommit(operation)
        if (bound.state != FileChangeSetState.AWAITING_APPROVAL.name) return
        check(
            dao.updateFileChangeSet(
                bound.copy(
                    state = FileChangeSetState.REJECTED.name,
                    failureCode = "USER_DECLINED",
                    updatedAtMillis = nowMillis(),
                ),
            ) == 1,
        )
    }

    fun cancel(operation: DeviceOperationEntity, code: String) {
        val current = dao.fileChangeSetByCommitCall(operation.callId) ?: return
        if (current.state in TERMINAL_STATES) return
        check(
            dao.updateFileChangeSet(
                current.copy(
                    state = FileChangeSetState.CANCELLED.name,
                    failureCode = code,
                    updatedAtMillis = nowMillis(),
                ),
            ) == 1,
        )
    }

    /** Cancels prepared plans that Pi never bound to a user-visible commit request. */
    fun cancelUnboundPreparedForTask(taskId: String, code: String): Int {
        require(UUID_PATTERN.matches(taskId)) { "taskId is invalid" }
        require(code.isNotBlank()) { "failure code is blank" }
        return dao.cancelUnboundPreparedFileChanges(taskId, code, nowMillis())
    }

    fun expire(operation: DeviceOperationEntity) {
        val current = dao.fileChangeSetByCommitCall(operation.callId) ?: return
        if (current.state in TERMINAL_STATES) return
        check(
            dao.updateFileChangeSet(
                current.copy(
                    state = FileChangeSetState.EXPIRED.name,
                    failureCode = "FILE_CHANGE_EXPIRED",
                    updatedAtMillis = nowMillis(),
                ),
            ) == 1,
        )
    }

    private fun persistUnknownTerminal(
        changeSet: FileChangeSetEntity,
        operation: DeviceOperationEntity,
        failureCode: String,
        recordedAtMillis: Long,
        durableTerminal: ((AttentionTerminalWrite) -> Unit)?,
    ) {
        database.runInTransaction {
            val current = requireNotNull(dao.fileChangeSet(changeSet.preparedId))
            require(current.commitCallId == operation.callId)
            require(current.commitOperationId == operation.operationId)
            check(
                dao.updateFileChangeSet(
                    current.copy(
                        state = FileChangeSetState.UNKNOWN.name,
                        failureCode = failureCode,
                        updatedAtMillis = nowMillis(),
                    ),
                ) == 1,
            )
            durableTerminal?.invoke(
                AttentionTerminalWrite(
                    frame = failedFrame(
                        operation,
                        "FILE_COMMIT_OUTCOME_UNKNOWN",
                        "Android could not prove the final file state",
                    ),
                    origin = AttentionTerminalOrigin.FAILED_CLOSED,
                    nowMillis = recordedAtMillis,
                ),
            )
        }
    }

    private fun succeededFrame(
        operation: DeviceOperationEntity,
        result: JsonObject,
    ) = DeviceToolResultClientFrame(
        callId = operation.callId,
        taskId = operation.taskId,
        deviceId = operation.deviceId,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = result,
    )

    private fun failedFrame(
        operation: DeviceOperationEntity,
        code: String,
        message: String,
    ) = DeviceToolResultClientFrame(
        callId = operation.callId,
        taskId = operation.taskId,
        deviceId = operation.deviceId,
        terminal = DeviceToolTerminalKind.FAILED,
        error = DeviceClientWireError(code, message),
    )

    private fun parsePrepare(value: kotlinx.serialization.json.JsonElement): PrepareRequest {
        val objectValue = value as? JsonObject
            ?: throw IllegalArgumentException("Prepare arguments must be an object")
        require(objectValue.keys == setOf("grantId", "purpose", "operations"))
        val grantId = normalizeUuid(
            objectValue.getValue("grantId").jsonPrimitive.content,
            "grantId",
        )
        val purpose = objectValue.getValue("purpose").jsonPrimitive.content
            .takeIf { it.isNotBlank() && it.length <= 1_024 }
            ?: throw IllegalArgumentException("Purpose is invalid")
        val operations = parseOperations(objectValue.getValue("operations") as? JsonArray)
        return PrepareRequest(grantId, purpose, operations)
    }

    private fun parseOperations(value: JsonArray?): List<AuthorizedFileMutation> {
        require(value != null && value.size in 1..16) { "Operations are invalid" }
        val operations = value.map { item ->
            val operation = item as? JsonObject
                ?: throw IllegalArgumentException("Operation is invalid")
            val operationId = normalizeUuid(
                operation.getValue("operationId").jsonPrimitive.content,
                "operationId",
            )
            when (operation.getValue("kind").jsonPrimitive.content) {
                "create_file" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "parentAlias",
                            "displayName",
                            "mimeType",
                            "content",
                        ),
                    )
                    AuthorizedFileMutation.CreateFile(
                        operationId = operationId,
                        parentAlias = requireAlias(operation.getValue("parentAlias")),
                        displayName = requireName(operation.getValue("displayName")),
                        mimeType = requireMime(operation.getValue("mimeType")),
                        content = operation.getValue("content").jsonPrimitive.content
                            .also {
                                require(it.toByteArray(Charsets.UTF_8).size <= MAX_NEW_FILE_BYTES)
                            },
                    )
                }
                "create_directory" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "parentAlias",
                            "displayName",
                        ),
                    )
                    AuthorizedFileMutation.CreateDirectory(
                        operationId = operationId,
                        parentAlias = requireAlias(operation.getValue("parentAlias")),
                        displayName = requireName(operation.getValue("displayName")),
                    )
                }
                "rename" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "sourceAlias",
                            "displayName",
                            "expected",
                        ),
                    )
                    AuthorizedFileMutation.Rename(
                        operationId = operationId,
                        sourceAlias = requireAlias(operation.getValue("sourceAlias")),
                        displayName = requireName(operation.getValue("displayName")),
                        expected = parseExpected(operation.getValue("expected")),
                    )
                }
                "move" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "sourceAlias",
                            "targetParentAlias",
                            "expected",
                        ),
                    )
                    AuthorizedFileMutation.Move(
                        operationId = operationId,
                        sourceAlias = requireAlias(operation.getValue("sourceAlias")),
                        targetParentAlias = requireAlias(
                            operation.getValue("targetParentAlias"),
                        ),
                        expected = parseExpected(operation.getValue("expected")),
                    )
                }
                "write_file" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "sourceAlias",
                            "mimeType",
                            "content",
                            "expected",
                        ),
                    )
                    AuthorizedFileMutation.WriteFile(
                        operationId = operationId,
                        sourceAlias = requireAlias(operation.getValue("sourceAlias")),
                        mimeType = requireMime(operation.getValue("mimeType")),
                        content = operation.getValue("content").jsonPrimitive.content.also {
                            require(it.toByteArray(Charsets.UTF_8).size <= MAX_NEW_FILE_BYTES)
                        },
                        expected = parseExpected(operation.getValue("expected")),
                    )
                }
                "delete_file" -> {
                    require(
                        operation.keys == setOf(
                            "operationId",
                            "kind",
                            "sourceAlias",
                            "expected",
                        ),
                    )
                    AuthorizedFileMutation.DeleteFile(
                        operationId = operationId,
                        sourceAlias = requireAlias(operation.getValue("sourceAlias")),
                        expected = parseExpected(operation.getValue("expected")),
                    )
                }
                else -> throw IllegalArgumentException("Operation kind is unavailable")
            }
        }
        require(operations.map { it.operationId }.toSet().size == operations.size)
        return operations
    }

    private fun parseExpected(
        value: kotlinx.serialization.json.JsonElement,
    ): AuthorizedFilePrecondition {
        val expected = value as? JsonObject
            ?: throw IllegalArgumentException("Expected metadata is invalid")
        require(
            expected.keys.all(
                setOf(
                    "displayName",
                    "mimeType",
                    "byteCount",
                    "lastModifiedMillis",
                )::contains,
            ),
        )
        require(
            expected.keys.containsAll(
                setOf("displayName", "mimeType", "byteCount", "lastModifiedMillis"),
            ),
        )
        return AuthorizedFilePrecondition(
            displayName = requireName(expected.getValue("displayName")),
            mimeType = requireMime(expected.getValue("mimeType")),
            byteCount = parseNullableMetadataLong(expected.getValue("byteCount")),
            lastModifiedMillis = parseNullableMetadataLong(
                expected.getValue("lastModifiedMillis"),
            ),
        )
    }

    private fun parseNullableMetadataLong(value: JsonElement): Long? {
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Expected metadata value is invalid")
        require(!primitive.isString)
        return requireNotNull(primitive.longOrNull).also { require(it >= 0) }
    }

    private fun encodeOperations(operations: List<AuthorizedFileMutation>): String =
        buildJsonArray {
            operations.forEach { mutation ->
                add(
                    buildJsonObject {
                        put("operationId", mutation.operationId)
                        put("kind", mutation.kindName())
                        when (mutation) {
                            is AuthorizedFileMutation.CreateFile -> {
                                put("parentAlias", mutation.parentAlias)
                                put("displayName", mutation.displayName)
                                put("mimeType", mutation.mimeType)
                                put("content", mutation.content)
                            }
                            is AuthorizedFileMutation.CreateDirectory -> {
                                put("parentAlias", mutation.parentAlias)
                                put("displayName", mutation.displayName)
                            }
                            is AuthorizedFileMutation.Rename -> {
                                put("sourceAlias", mutation.sourceAlias)
                                put("displayName", mutation.displayName)
                                put("expected", encodeExpected(mutation.expected))
                            }
                            is AuthorizedFileMutation.Move -> {
                                put("sourceAlias", mutation.sourceAlias)
                                put("targetParentAlias", mutation.targetParentAlias)
                                put("expected", encodeExpected(mutation.expected))
                            }
                            is AuthorizedFileMutation.WriteFile -> {
                                put("sourceAlias", mutation.sourceAlias)
                                put("mimeType", mutation.mimeType)
                                put("content", mutation.content)
                                put("expected", encodeExpected(mutation.expected))
                            }
                            is AuthorizedFileMutation.DeleteFile -> {
                                put("sourceAlias", mutation.sourceAlias)
                                put("expected", encodeExpected(mutation.expected))
                            }
                        }
                    },
                )
            }
        }.toString()

    private fun decodeOperations(value: String): List<AuthorizedFileMutation> =
        parseOperations(STRICT_JSON.parseToJsonElement(value) as? JsonArray)

    private fun encodeExpected(expected: AuthorizedFilePrecondition): JsonObject =
        buildJsonObject {
            put("displayName", expected.displayName)
            put("mimeType", expected.mimeType)
            put("byteCount", expected.byteCount?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "lastModifiedMillis",
                expected.lastModifiedMillis?.let(::JsonPrimitive) ?: JsonNull,
            )
        }

    private fun buildPreview(
        operations: List<AuthorizedFileMutation>,
        documents: List<AuthorizedDocumentMetadata>,
    ): JsonArray {
        val records = documents.associateBy { it.alias }
        return buildJsonArray {
            operations.forEach { mutation ->
                add(
                    buildJsonObject {
                        put("operationId", mutation.operationId)
                        put("kind", mutation.kindName())
                        put("risk", "writes_authorized_files")
                        put("reversible", false)
                        when (mutation) {
                            is AuthorizedFileMutation.CreateFile -> {
                                put("afterName", mutation.displayName)
                                put("afterParentAlias", mutation.parentAlias)
                                put(
                                    "afterParentDisplayPath",
                                    displayPath(mutation.parentAlias, records),
                                )
                                put("mimeType", mutation.mimeType)
                                put(
                                    "contentByteCount",
                                    mutation.content.toByteArray(Charsets.UTF_8).size,
                                )
                            }
                            is AuthorizedFileMutation.CreateDirectory -> {
                                put("afterName", mutation.displayName)
                                put("afterParentAlias", mutation.parentAlias)
                                put(
                                    "afterParentDisplayPath",
                                    displayPath(mutation.parentAlias, records),
                                )
                                put("mimeType", android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                            }
                            is AuthorizedFileMutation.Rename -> {
                                val source = requireNotNull(records[mutation.sourceAlias])
                                put("sourceAlias", mutation.sourceAlias)
                                put("beforeName", source.displayName)
                                put("afterName", mutation.displayName)
                                source.parentAlias?.let {
                                    put("beforeParentAlias", it)
                                    put("afterParentAlias", it)
                                    val parentPath = displayPath(it, records)
                                    put("beforeParentDisplayPath", parentPath)
                                    put("afterParentDisplayPath", parentPath)
                                }
                                put("mimeType", source.mimeType)
                            }
                            is AuthorizedFileMutation.Move -> {
                                val source = requireNotNull(records[mutation.sourceAlias])
                                put("sourceAlias", mutation.sourceAlias)
                                put("beforeName", source.displayName)
                                put("afterName", source.displayName)
                                source.parentAlias?.let {
                                    put("beforeParentAlias", it)
                                    put("beforeParentDisplayPath", displayPath(it, records))
                                }
                                put("afterParentAlias", mutation.targetParentAlias)
                                put(
                                    "afterParentDisplayPath",
                                    displayPath(mutation.targetParentAlias, records),
                                )
                                put("mimeType", source.mimeType)
                            }
                            is AuthorizedFileMutation.WriteFile -> {
                                val source = requireNotNull(records[mutation.sourceAlias])
                                put("sourceAlias", mutation.sourceAlias)
                                put("beforeName", source.displayName)
                                put("afterName", source.displayName)
                                source.parentAlias?.let {
                                    put("beforeParentAlias", it)
                                    put("afterParentAlias", it)
                                    val parentPath = displayPath(it, records)
                                    put("beforeParentDisplayPath", parentPath)
                                    put("afterParentDisplayPath", parentPath)
                                }
                                put("mimeType", source.mimeType)
                                put(
                                    "contentByteCount",
                                    mutation.content.toByteArray(Charsets.UTF_8).size,
                                )
                            }
                            is AuthorizedFileMutation.DeleteFile -> {
                                val source = requireNotNull(records[mutation.sourceAlias])
                                put("sourceAlias", mutation.sourceAlias)
                                put("beforeName", source.displayName)
                                source.parentAlias?.let {
                                    put("beforeParentAlias", it)
                                    put("beforeParentDisplayPath", displayPath(it, records))
                                }
                                put("mimeType", source.mimeType)
                            }
                        }
                    },
                )
            }
        }
    }

    private fun displayPath(
        alias: String,
        records: Map<String, AuthorizedDocumentMetadata>,
    ): String {
        val segments = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var cursor: String? = alias
        while (cursor != null) {
            require(visited.add(cursor)) { "Authorized folder hierarchy contains a cycle" }
            val document = requireNotNull(records[cursor]) {
                "Folder alias is outside the authorized tree"
            }
            require(document.isDirectory) { "Preview parent is not a directory" }
            segments += document.displayName
            cursor = document.parentAlias
        }
        require(segments.isNotEmpty())
        return segments.asReversed().joinToString(" / ")
    }

    private fun prepareResult(entity: FileChangeSetEntity): JsonObject = buildJsonObject {
        put("preparedId", entity.preparedId)
        put("planDigest", entity.planDigest)
        put("expiresAtMillis", entity.expiresAtMillis)
        put("operationCount", entity.operationCount)
        put("risk", "writes_authorized_files")
        put("changes", STRICT_JSON.parseToJsonElement(entity.previewCanonicalJson))
    }

    private fun parseCommit(operation: DeviceOperationEntity): CommitRequest {
        require(operation.toolName == COMMIT_TOOL)
        require(operation.sideEffect && operation.operationId != null)
        val value = STRICT_JSON.parseToJsonElement(operation.argumentsCanonicalJson) as? JsonObject
            ?: throw IllegalArgumentException("Commit arguments are invalid")
        require(value.keys == setOf("preparedId", "planDigest"))
        val preparedId = normalizeUuid(
            value.getValue("preparedId").jsonPrimitive.content,
            "preparedId",
        )
        val digest = value.getValue("planDigest").jsonPrimitive.content
        require(SHA256.matches(digest)) { "planDigest is invalid" }
        return CommitRequest(preparedId, digest)
    }

    private suspend fun requireTaskGrant(
        taskId: String,
        grantId: String,
        requireWrite: Boolean,
    ) {
        val selected = dao.draftForTask(taskId)?.selectedGrantId
        if (selected != grantId) throw SecurityException("Task grant changed")
        val current = folders.folders().singleOrNull { it.grantId == grantId }
            ?: throw SecurityException("Task grant is unavailable")
        if (!current.canRead || (requireWrite && !current.canWrite)) {
            throw SecurityException("Task grant is not writable")
        }
    }

    private fun requireTaskGrantSync(taskId: String, grantId: String) {
        require(dao.draftForTaskSync(taskId)?.selectedGrantId == grantId) {
            "Task grant changed"
        }
    }

    private fun approvalBinding(
        changeSet: FileChangeSetEntity,
        operation: DeviceOperationEntity,
    ): String = buildJsonObject {
        put("deviceId", operation.deviceId)
        put("taskId", operation.taskId)
        put("preparedId", changeSet.preparedId)
        put("planDigest", changeSet.planDigest)
        put("operationId", requireNotNull(operation.operationId))
        put("expiresAtMillis", minOf(changeSet.expiresAtMillis, parseExpiry(operation.expiresAt)))
        put("maxOps", changeSet.operationCount)
    }.toString()

    private fun requireAlias(value: kotlinx.serialization.json.JsonElement): String =
        value.jsonPrimitive.content.takeIf(DOCUMENT_ALIAS::matches)
            ?: throw IllegalArgumentException("Alias is invalid")

    private fun requireName(value: kotlinx.serialization.json.JsonElement): String =
        value.jsonPrimitive.content.takeIf { name ->
            name.isNotBlank() &&
                name.length <= 240 &&
                name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name &&
                !CONTROL_CHARACTERS.containsMatchIn(name)
        } ?: throw IllegalArgumentException("Display name is invalid")

    private fun requireMime(value: kotlinx.serialization.json.JsonElement): String =
        value.jsonPrimitive.content.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("MIME is invalid")

    private fun normalizeUuid(value: String, field: String): String {
        require(UUID_PATTERN.matches(value)) { "$field is invalid" }
        return UUID.fromString(value).toString()
    }

    private fun parseExpiry(value: String): Long =
        OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun AuthorizedFileMutation.kindName(): String = when (this) {
        is AuthorizedFileMutation.CreateFile -> "create_file"
        is AuthorizedFileMutation.CreateDirectory -> "create_directory"
        is AuthorizedFileMutation.Rename -> "rename"
        is AuthorizedFileMutation.Move -> "move"
        is AuthorizedFileMutation.WriteFile -> "write_file"
        is AuthorizedFileMutation.DeleteFile -> "delete_file"
    }

    private fun DeviceToolRequestFrame.succeeded(result: JsonObject) =
        DeviceToolResultClientFrame(
            callId = callId,
            taskId = taskId,
            deviceId = deviceId,
            terminal = DeviceToolTerminalKind.SUCCEEDED,
            result = result,
        )

    private fun DeviceToolRequestFrame.failed(code: String, message: String) =
        DeviceToolResultClientFrame(
            callId = callId,
            taskId = taskId,
            deviceId = deviceId,
            terminal = DeviceToolTerminalKind.FAILED,
            error = DeviceClientWireError(code, message),
        )

    private data class PrepareRequest(
        val grantId: String,
        val purpose: String,
        val operations: List<AuthorizedFileMutation>,
    )

    private data class CommitRequest(
        val preparedId: String,
        val planDigest: String,
    )

    private companion object {
        const val PREPARE_TOOL = "device_files_prepare_changes"
        const val COMMIT_TOOL = "device_files_commit_changes"
        const val CAPABILITY_VERSION = 1L
        const val PREPARE_TIMEOUT_MILLIS = 15_000L
        const val MAX_PREPARED_LIFETIME_MILLIS = 15 * 60_000L
        const val MAX_NEW_FILE_BYTES = 262_144
        val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE,
        )
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        val TERMINAL_STATES = setOf(
            FileChangeSetState.COMPLETED.name,
            FileChangeSetState.PARTIALLY_FAILED.name,
            FileChangeSetState.FAILED.name,
            FileChangeSetState.REJECTED.name,
            FileChangeSetState.EXPIRED.name,
            FileChangeSetState.CANCELLED.name,
            FileChangeSetState.UNKNOWN.name,
        )
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}
