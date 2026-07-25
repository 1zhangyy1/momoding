package app.momoding.core.runtime.local

import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionAcceptanceScope
import app.momoding.core.data.AttentionRequestRecord
import app.momoding.core.data.AttentionTerminalOrigin
import app.momoding.core.data.AttentionTerminalWrite
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.files.ContentReadExecutionFailure
import app.momoding.core.files.DeviceContentReadHandler
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.DeviceMetadataToolHandler
import app.momoding.core.files.FileChangeExecutionFailure
import app.momoding.core.media.DeviceMediaListHandler
import app.momoding.core.media.DeviceMediaListExecutor
import app.momoding.core.media.PhotoLibraryScope
import app.momoding.core.policy.CapabilityAction
import app.momoding.core.policy.CapabilityTarget
import app.momoding.core.policy.CapabilityTargetKind
import app.momoding.core.policy.DeviceActionPolicy
import app.momoding.core.policy.DeviceActionRequest
import app.momoding.core.policy.PolicyDecisionKind
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.AttentionUserDecision
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Direct phone-local bridge between Pi's native tool mailbox and the existing Android Attention
 * ledger and Android file executors. Pi remains the only owner of the Agent loop; this class
 * executes Android-owned metadata tools and durably resolves tools that require user consent.
 */
class PhoneLocalAttentionBridge(
    private val ledger: RoomAttentionLedger,
    private val metadataTools: DeviceMetadataToolHandler? = null,
    private val contentReadHandler: DeviceContentReadHandler? = null,
    private val fileChangeHandler: DeviceFileChangeExecutor? = null,
    private val projectTools: PhoneLocalProjectToolHandler? = null,
    private val attachmentTools: PhoneLocalAttachmentToolHandler? = null,
    private val mediaTools: DeviceMediaListHandler? = null,
    private val approvalModeForTask: suspend (String) -> TaskApprovalMode = {
        TaskApprovalMode.REQUEST_APPROVAL
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val operationIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val bindingsByCallId = ConcurrentHashMap<String, PendingBinding>()
    private val callIdsByNativeRequest = ConcurrentHashMap<String, String>()
    private val readyForPi = ConcurrentLinkedQueue<ReadyDelivery>()
    private val decisionMutex = Mutex()

    fun owns(callId: String): Boolean = bindingsByCallId.containsKey(callId)

    /** Fail closed any phone-local prompt whose QuickJS execution disappeared with the process. */
    suspend fun recoverDestroyedRuntime() = withContext(ioDispatcher) {
        decisionMutex.withLock {
            ledger.unterminatedRecords()
                .filter { it.operation.deviceId == DEVICE_ID }
                .forEach { record ->
                    if (record.operation.toolName == FILES_COMMIT_TOOL) {
                        val repaired = fileChangeHandler?.repairInterruptedCommit(
                            record.operation,
                            ledger::recordTerminal,
                        ) == true
                        if (!repaired) cancelDurable(record.operation.callId, "host_shutdown")
                    } else {
                        cancelDurable(record.operation.callId, "host_shutdown")
                    }
                }
            ledger.terminalOperationsReadyForDelivery(DEVICE_ID)
                .forEach { operation ->
                    ledger.markLocalDeliveryAbandoned(operation.callId, DEVICE_ID)
                }
        }
    }

    /**
     * Handles the exact Pi-native Android tool request. A non-null result is returned immediately
     * to Pi; null means Android is durably waiting for a user decision.
     */
    internal suspend fun handleNativeRequest(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult? = when (request.kind) {
        ATTENTION_NATIVE_KIND -> {
            accept(taskId, request)
            null
        }
        FILE_NATIVE_KIND -> when (request.toolName) {
            CAPABILITIES_TOOL,
            FILES_LIST_TOOL,
            FILES_PREPARE_TOOL,
            -> withContext(ioDispatcher) {
                PiNativeAndroidToolResult(immediateToolResult(taskId, request))
            }
            FILES_READ_TOOL,
            FILES_COMMIT_TOOL,
            -> {
                accept(taskId, request)
                null
            }
            else -> throw IllegalArgumentException("PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED")
        }
        PROJECT_NATIVE_KIND -> withContext(ioDispatcher) {
            val handler = requireNotNull(projectTools) {
                "PI_MOBILE_PROJECT_TOOL_EXECUTOR_MISSING"
            }
            require(handler.handles(request.toolName)) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
            PiNativeAndroidToolResult(handler.execute(taskId, request))
        }
        ATTACHMENT_NATIVE_KIND -> withContext(ioDispatcher) {
            val handler = requireNotNull(attachmentTools) {
                "PI_MOBILE_ATTACHMENT_TOOL_EXECUTOR_MISSING"
            }
            require(handler.handles(request.toolName)) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
            PiNativeAndroidToolResult(handler.execute(taskId, request))
        }
        MEDIA_NATIVE_KIND -> handleMediaRequest(taskId, request)
        else -> throw IllegalArgumentException("PI_MOBILE_NATIVE_TOOL_KIND_UNSUPPORTED")
    }

    private suspend fun handleMediaRequest(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult? {
        val handler = requireNotNull(mediaTools) { "PI_MOBILE_MEDIA_TOOL_EXECUTOR_MISSING" }
        require(handler.handles(request.toolName)) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        if (handler.currentScope() == PhotoLibraryScope.DENIED) {
            return withContext(ioDispatcher) { immediateMediaToolResult(taskId, request) }
        }
        val decision = DeviceActionPolicy.decide(
            mode = withContext(ioDispatcher) { approvalModeForTask(taskId) },
            request = DeviceActionRequest(
                action = CapabilityAction.READ_SHARED_MEDIA_METADATA,
                target = CapabilityTarget(
                    kind = CapabilityTargetKind.SHARED_FILE,
                    syntheticId = "android-photo-library",
                    allowedByAndroid = true,
                ),
                capabilityReady = true,
            ),
        )
        return when (decision.kind) {
            PolicyDecisionKind.AUTO_ALLOW -> withContext(ioDispatcher) {
                immediateMediaToolResult(taskId, request)
            }
            PolicyDecisionKind.PROMPT_USER -> {
                accept(taskId, request)
                null
            }
            PolicyDecisionKind.DENY -> buildJsonObject {
                put("code", "DEVICE_ACTION_DENIED")
                put("message", "Photo-library access is outside the active task policy")
            }.let { PiNativeAndroidToolResult(it, isError = true) }
        }
    }

    internal fun accept(taskId: String, request: PiNativeToolRequest): String {
        require(
            (request.kind == ATTENTION_NATIVE_KIND && request.toolName in ATTENTION_TOOLS) ||
                (request.kind == FILE_NATIVE_KIND && request.toolName in CONSENT_TOOLS) ||
                (request.kind == MEDIA_NATIVE_KIND && request.toolName == MEDIA_LIST_TOOL),
        ) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        require(callIdsByNativeRequest[request.id] == null) {
            "PI_MOBILE_NATIVE_TOOL_REQUEST_DUPLICATED"
        }
        val callId = idFactory()
        val binding = PendingBinding(
            callId = callId,
            taskId = taskId,
            nativeRequestId = request.id,
            piToolCallId = request.toolCallId,
            toolName = request.toolName,
        )
        check(bindingsByCallId.putIfAbsent(callId, binding) == null) {
            "PI_MOBILE_ATTENTION_CALL_DUPLICATED"
        }
        if (callIdsByNativeRequest.putIfAbsent(request.id, callId) != null) {
            bindingsByCallId.remove(callId, binding)
            error("PI_MOBILE_NATIVE_TOOL_REQUEST_DUPLICATED")
        }
        val accepted = try {
            ledger.acceptRequest(
                request = AttentionRequestRecord(
                    callId = callId,
                    taskId = taskId,
                    piToolCallId = request.toolCallId,
                    deviceId = DEVICE_ID,
                    toolName = request.toolName,
                    arguments = request.arguments,
                    sideEffect = request.toolName == FILES_COMMIT_TOOL,
                    operationId = if (request.toolName == FILES_COMMIT_TOOL) {
                        operationIdFactory()
                    } else {
                        null
                    },
                    expiresAt = Instant.ofEpochMilli(
                        nowMillis() + ATTENTION_TTL_MILLIS,
                    ).toString(),
                    capabilityVersion = CAPABILITY_VERSION,
                ),
                scope = AttentionAcceptanceScope(
                    taskId = taskId,
                    deviceId = DEVICE_ID,
                    capabilityVersion = CAPABILITY_VERSION,
                    originFocusKey = "task-$taskId",
                ),
            )
        } catch (error: Throwable) {
            removeBinding(binding)
            throw error
        }
        if (accepted.operation.terminalSha256 != null) {
            ledger.markLocalDeliveryAbandoned(callId, DEVICE_ID)
            removeBinding(binding)
            throw IllegalArgumentException("PI_MOBILE_ATTENTION_ARGUMENTS_INVALID")
        }
        if (request.toolName == FILES_COMMIT_TOOL) {
            try {
                requireNotNull(fileChangeHandler) {
                    "PI_MOBILE_FILE_CHANGE_EXECUTOR_MISSING"
                }.bindCommit(accepted.operation)
            } catch (failure: FileChangeExecutionFailure) {
                ledger.recordTerminal(
                    AttentionTerminalWrite(
                        frame = failedFrame(accepted.operation, failure.code, failure.message),
                        origin = AttentionTerminalOrigin.USER,
                        nowMillis = nowMillis(),
                    ),
                )
            }
        }
        ledger.markAwaitingUser(callId)
        if (ledger.record(callId)?.operation?.terminalSha256 != null) {
            readyForPi += ReadyDelivery(callId)
        }
        return callId
    }

    suspend fun submitDecision(decision: AttentionUserDecision) = withContext(ioDispatcher) {
        decisionMutex.withLock {
            val binding = requireNotNull(bindingsByCallId[decision.callId]) {
                "PI_MOBILE_ATTENTION_EXECUTION_NOT_ACTIVE"
            }
            val durable = requireNotNull(ledger.record(decision.callId)) {
                "PI_MOBILE_ATTENTION_REQUEST_MISSING"
            }
            require(durable.operation.taskId == binding.taskId) {
                "PI_MOBILE_ATTENTION_TASK_CHANGED"
            }
            if (durable.operation.terminalSha256 != null) return@withLock
            when (binding.toolName) {
                QUESTION_TOOL,
                CONFIRMATION_TOOL,
                -> {
                    ledger.recordTerminal(
                        AttentionTerminalWrite(
                            frame = terminalForDecision(
                                durable.operation.argumentsCanonicalJson,
                                binding,
                                decision,
                            ),
                            origin = AttentionTerminalOrigin.USER,
                            nowMillis = nowMillis(),
                        ),
                    )
                    readyForPi += ReadyDelivery(binding.callId)
                }
                FILES_READ_TOOL -> submitContentReadDecision(durable.operation, binding, decision)
                FILES_COMMIT_TOOL -> submitFileChangeDecision(durable.operation, binding, decision)
                MEDIA_LIST_TOOL -> submitMediaListDecision(durable.operation, binding, decision)
                else -> error("Phone-local decision is bound to an unsupported tool")
            }
        }
    }

    /** Called only by the QuickJS owner thread. */
    internal suspend fun deliverReady(engine: PhoneLocalPiEngine) {
        while (true) {
            val delivery = readyForPi.poll() ?: return
            val binding = bindingsByCallId[delivery.callId] ?: continue
            val record = requireNotNull(ledger.record(delivery.callId)) {
                "PI_MOBILE_ATTENTION_TERMINAL_MISSING"
            }
            val expectation = if (delivery.liveResult == null) {
                requireNotNull(ledger.piDeliveryExpectationForValidatedPair(record.operation)) {
                    "PI_MOBILE_ATTENTION_TERMINAL_NOT_READY"
                }
            } else {
                null
            }
            val result = delivery.liveResult ?: expectation!!.contentPayload.asResultObject()
            val details = expectation?.let {
                deliveryProofDetails(record.operation, it)
            } ?: result
            try {
                engine.resolveNativeProviderToolRequest(
                    requestId = binding.nativeRequestId,
                    contentPayload = result,
                    details = details,
                    isError = expectation?.isError == true,
                )
                if (delivery.discardLiveContentAfterAttempt) {
                    ledger.discardLiveContentRead(binding.taskId, binding.callId)
                } else {
                    ledger.markPiDeliveredAfterVerifiedProof(
                        taskId = binding.taskId,
                        callId = binding.callId,
                        terminalSemanticSha256 = requireNotNull(expectation)
                            .terminalSemanticSha256,
                    )
                }
                removeBinding(binding)
            } catch (error: Throwable) {
                if (delivery.discardLiveContentAfterAttempt) {
                    ledger.discardLiveContentRead(binding.taskId, binding.callId)
                } else {
                    ledger.markLocalDeliveryAbandoned(binding.callId, DEVICE_ID)
                }
                removeBinding(binding)
                throw error
            }
        }
    }

    internal suspend fun cancelTask(taskId: String, reason: String) = withContext(ioDispatcher) {
        projectTools?.stopTask(taskId)
        decisionMutex.withLock {
            bindingsByCallId.values.filter { it.taskId == taskId }.forEach { binding ->
                val record = ledger.record(binding.callId)
                if (record?.operation?.terminalSha256 == null) {
                    cancelDurable(binding.callId, reason)
                } else if (record.operation.deliveryState != "PI_DELIVERED") {
                    ledger.markLocalDeliveryAbandoned(binding.callId, DEVICE_ID)
                }
                readyForPi.removeIf { it.callId == binding.callId }
                removeBinding(binding)
            }
        }
    }

    private fun cancelDurable(callId: String, reason: String) {
        val record = ledger.record(callId) ?: return
        if (record.operation.terminalSha256 != null) return
        if (record.operation.toolName == FILES_COMMIT_TOOL) {
            fileChangeHandler?.cancel(record.operation, "FILE_COMMIT_CANCELLED")
        }
        val error = when (record.operation.toolName) {
            FILES_READ_TOOL -> CONTENT_READ_CANCELLED
            FILES_COMMIT_TOOL -> FILE_COMMIT_CANCELLED
            else -> ATTENTION_CANCELLED
        }
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = DeviceToolResultClientFrame(
                    callId = callId,
                    taskId = record.operation.taskId,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.CANCELLED,
                    error = error,
                ),
                origin = AttentionTerminalOrigin.HOST_CANCEL,
                nowMillis = nowMillis(),
                cancelReason = reason,
            ),
        )
    }

    private suspend fun immediateToolResult(
        taskId: String,
        request: PiNativeToolRequest,
    ): JsonObject {
        val frame = requestFrame(
            taskId = taskId,
            request = request,
            sideEffect = false,
            operationId = null,
        )
        val terminal = when {
            metadataTools?.handles(request.toolName) == true -> metadataTools.execute(frame)
            fileChangeHandler?.handlesPrepare(request.toolName) == true ->
                fileChangeHandler.prepare(frame)
            else -> throw IllegalStateException("PI_MOBILE_FILE_TOOL_EXECUTOR_MISSING")
        }
        return terminal.result?.asResultObject() ?: requireNotNull(terminal.error).let { error ->
            buildJsonObject {
                put("code", error.code)
                put("message", error.message)
            }
        }
    }

    private suspend fun immediateMediaToolResult(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        val terminal = requireNotNull(mediaTools) { "PI_MOBILE_MEDIA_TOOL_EXECUTOR_MISSING" }
            .execute(requestFrame(taskId, request, sideEffect = false, operationId = null))
        val content = terminal.result?.asResultObject() ?: requireNotNull(terminal.error).let { error ->
            buildJsonObject {
                put("code", error.code)
                put("message", error.message)
            }
        }
        return PiNativeAndroidToolResult(
            contentPayload = content,
            isError = terminal.terminal != DeviceToolTerminalKind.SUCCEEDED,
        )
    }

    private suspend fun submitMediaListDecision(
        operation: app.momoding.core.data.DeviceOperationEntity,
        binding: PendingBinding,
        decision: AttentionUserDecision,
    ) {
        require(decision is AttentionUserDecision.Confirm || decision is AttentionUserDecision.Decline) {
            "Photo-library decision type is invalid"
        }
        val terminal = if (decision is AttentionUserDecision.Confirm) {
            val arguments = STRICT_JSON.parseToJsonElement(operation.argumentsCanonicalJson).jsonObject
            requireNotNull(mediaTools) { "PI_MOBILE_MEDIA_TOOL_EXECUTOR_MISSING" }.execute(
                DeviceToolRequestFrame(
                    protocolVersion = 1,
                    kind = "device.tool.request",
                    callId = operation.callId,
                    taskId = operation.taskId,
                    piToolCallId = operation.piToolCallId,
                    deviceId = operation.deviceId,
                    toolName = operation.toolName,
                    arguments = arguments,
                    sideEffect = false,
                    operationId = null,
                    expiresAt = Instant.ofEpochMilli(nowMillis() + TOOL_TTL_MILLIS).toString(),
                    capabilityVersion = operation.capabilityVersion,
                ),
            )
        } else {
            failedFrame(
                operation,
                USER_DECLINED.code,
                USER_DECLINED.message,
                rejected = true,
            )
        }
        ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = terminal,
                origin = AttentionTerminalOrigin.USER,
                nowMillis = nowMillis(),
            ),
        )
        readyForPi += ReadyDelivery(binding.callId)
    }

    private suspend fun submitContentReadDecision(
        operation: app.momoding.core.data.DeviceOperationEntity,
        binding: PendingBinding,
        decision: AttentionUserDecision,
    ) {
        require(
            decision is AttentionUserDecision.AllowContentRead ||
                decision is AttentionUserDecision.DenyContentRead,
        ) { "Content-read decision type is invalid" }
        if (decision is AttentionUserDecision.AllowContentRead) {
            try {
                val result = requireNotNull(contentReadHandler) {
                    "PI_MOBILE_CONTENT_READ_EXECUTOR_MISSING"
                }.execute(operation)
                readyForPi += ReadyDelivery(
                    callId = binding.callId,
                    liveResult = result,
                    discardLiveContentAfterAttempt = true,
                )
                return
            } catch (failure: ContentReadExecutionFailure) {
                ledger.recordTerminal(
                    AttentionTerminalWrite(
                        frame = failedFrame(
                            operation,
                            failure.code,
                            failure.message,
                            cancelled = failure.code == "CONTENT_READ_CANCELLED",
                        ),
                        origin = if (failure.code == "CONTENT_READ_CANCELLED") {
                            AttentionTerminalOrigin.HOST_CANCEL
                        } else {
                            AttentionTerminalOrigin.USER
                        },
                        nowMillis = nowMillis(),
                        cancelReason = if (failure.code == "CONTENT_READ_CANCELLED") {
                            "session_stop"
                        } else {
                            null
                        },
                    ),
                )
            }
        } else {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    frame = failedFrame(
                        operation,
                        CONTENT_READ_DECLINED.code,
                        CONTENT_READ_DECLINED.message,
                        rejected = true,
                    ),
                    origin = AttentionTerminalOrigin.USER,
                    nowMillis = nowMillis(),
                ),
            )
        }
        readyForPi += ReadyDelivery(binding.callId)
    }

    private suspend fun submitFileChangeDecision(
        operation: app.momoding.core.data.DeviceOperationEntity,
        binding: PendingBinding,
        decision: AttentionUserDecision,
    ) {
        require(
            decision is AttentionUserDecision.ApproveFileChanges ||
                decision is AttentionUserDecision.RejectFileChanges,
        ) { "File-change decision type is invalid" }
        val handler = requireNotNull(fileChangeHandler) {
            "PI_MOBILE_FILE_CHANGE_EXECUTOR_MISSING"
        }
        if (decision is AttentionUserDecision.RejectFileChanges) {
            handler.reject(operation)
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    frame = failedFrame(
                        operation,
                        USER_DECLINED_FILE_CHANGES.code,
                        USER_DECLINED_FILE_CHANGES.message,
                        rejected = true,
                    ),
                    origin = AttentionTerminalOrigin.USER,
                    nowMillis = nowMillis(),
                ),
            )
        } else {
            try {
                val result = handler.approveAndCommit(operation, ledger::recordTerminal)
                if (ledger.record(operation.callId)?.operation?.terminalSha256 == null) {
                    ledger.recordTerminal(
                        AttentionTerminalWrite(
                            frame = DeviceToolResultClientFrame(
                                callId = operation.callId,
                                taskId = operation.taskId,
                                deviceId = operation.deviceId,
                                terminal = DeviceToolTerminalKind.SUCCEEDED,
                                result = result,
                            ),
                            origin = AttentionTerminalOrigin.USER,
                            nowMillis = nowMillis(),
                        ),
                    )
                }
            } catch (failure: FileChangeExecutionFailure) {
                if (ledger.record(operation.callId)?.operation?.terminalSha256 == null) {
                    ledger.recordTerminal(
                        AttentionTerminalWrite(
                            frame = failedFrame(
                                operation,
                                failure.code,
                                failure.message,
                                cancelled = failure.code == "FILE_COMMIT_CANCELLED",
                            ),
                            origin = if (failure.code == "FILE_COMMIT_CANCELLED") {
                                AttentionTerminalOrigin.HOST_CANCEL
                            } else {
                                AttentionTerminalOrigin.USER
                            },
                            nowMillis = nowMillis(),
                            cancelReason = if (failure.code == "FILE_COMMIT_CANCELLED") {
                                "session_stop"
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }
        readyForPi += ReadyDelivery(binding.callId)
    }

    private fun requestFrame(
        taskId: String,
        request: PiNativeToolRequest,
        sideEffect: Boolean,
        operationId: String?,
    ) = DeviceToolRequestFrame(
        protocolVersion = 1,
        kind = "device.tool.request",
        callId = idFactory(),
        taskId = taskId,
        piToolCallId = request.toolCallId,
        deviceId = DEVICE_ID,
        toolName = request.toolName,
        arguments = request.arguments,
        sideEffect = sideEffect,
        operationId = operationId,
        expiresAt = Instant.ofEpochMilli(nowMillis() + TOOL_TTL_MILLIS).toString(),
        capabilityVersion = CAPABILITY_VERSION,
    )

    private fun failedFrame(
        operation: app.momoding.core.data.DeviceOperationEntity,
        code: String,
        message: String,
        cancelled: Boolean = false,
        rejected: Boolean = false,
    ) = DeviceToolResultClientFrame(
        callId = operation.callId,
        taskId = operation.taskId,
        deviceId = operation.deviceId,
        terminal = when {
            cancelled -> DeviceToolTerminalKind.CANCELLED
            rejected -> DeviceToolTerminalKind.REJECTED
            else -> DeviceToolTerminalKind.FAILED
        },
        error = DeviceClientWireError(code, message),
    )

    private fun kotlinx.serialization.json.JsonElement.asResultObject(): JsonObject =
        this as? JsonObject ?: buildJsonObject { put("result", this@asResultObject) }

    private fun deliveryProofDetails(
        operation: app.momoding.core.data.DeviceOperationEntity,
        expectation: app.momoding.core.data.AttentionPiDeliveryExpectation,
    ): JsonObject = buildJsonObject {
        put("callId", operation.callId)
        put("toolName", operation.toolName)
        put("terminalSemanticSha256", expectation.terminalSemanticSha256)
        put("sideEffect", operation.sideEffect)
        when (operation.toolName) {
            FILES_READ_TOOL -> {
                put("contentScope", "android_saf_user_approved")
                put("dataScope", "android_saf_task_grant")
            }
            FILES_COMMIT_TOOL -> {
                put("dataScope", "android_saf_task_grant")
                put("operationId", requireNotNull(operation.operationId))
            }
        }
    }

    private fun terminalForDecision(
        argumentsCanonicalJson: String,
        binding: PendingBinding,
        decision: AttentionUserDecision,
    ): DeviceToolResultClientFrame {
        require(decision.callId == binding.callId) { "PI_MOBILE_ATTENTION_CALL_CHANGED" }
        val result: JsonObject?
        val terminal: DeviceToolTerminalKind
        val error: DeviceClientWireError?
        when (decision) {
            is AttentionUserDecision.Option -> {
                require(binding.toolName == QUESTION_TOOL)
                val arguments = STRICT_JSON.parseToJsonElement(argumentsCanonicalJson).jsonObject
                val options = arguments["options"] as? JsonArray
                    ?: throw IllegalArgumentException("Question has no options")
                val option = options.getOrNull(decision.index)?.jsonObject
                    ?: throw IllegalArgumentException("Question option is outside the request")
                result = buildJsonObject {
                    put("outcome", "answered")
                    put("answer", buildJsonObject {
                        put("kind", "option")
                        put("index", decision.index)
                        put("label", option.getValue("label").jsonPrimitive.content)
                    })
                }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Custom -> {
                require(binding.toolName == QUESTION_TOOL)
                result = buildJsonObject {
                    put("outcome", "answered")
                    put("answer", buildJsonObject {
                        put("kind", "custom")
                        put("text", decision.text)
                    })
                }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Skip -> {
                require(binding.toolName == QUESTION_TOOL)
                result = buildJsonObject { put("outcome", "skipped") }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Confirm -> {
                require(binding.toolName == CONFIRMATION_TOOL)
                result = buildJsonObject { put("outcome", "confirmed") }
                terminal = DeviceToolTerminalKind.SUCCEEDED
                error = null
            }
            is AttentionUserDecision.Decline -> {
                require(binding.toolName == CONFIRMATION_TOOL)
                result = null
                terminal = DeviceToolTerminalKind.REJECTED
                error = USER_DECLINED
            }
            is AttentionUserDecision.AllowContentRead,
            is AttentionUserDecision.DenyContentRead,
            is AttentionUserDecision.ApproveFileChanges,
            is AttentionUserDecision.RejectFileChanges,
            -> error("Phone-local Attention only accepts question and confirmation decisions")
        }
        return DeviceToolResultClientFrame(
            callId = binding.callId,
            taskId = binding.taskId,
            deviceId = DEVICE_ID,
            terminal = terminal,
            result = result,
            error = error,
        )
    }

    private fun removeBinding(binding: PendingBinding) {
        bindingsByCallId.remove(binding.callId, binding)
        callIdsByNativeRequest.remove(binding.nativeRequestId, binding.callId)
    }

    private data class PendingBinding(
        val callId: String,
        val taskId: String,
        val nativeRequestId: String,
        val piToolCallId: String,
        val toolName: String,
    )

    private data class ReadyDelivery(
        val callId: String,
        val liveResult: JsonObject? = null,
        val discardLiveContentAfterAttempt: Boolean = false,
    )

    private companion object {
        const val ATTENTION_NATIVE_KIND = "android_attention"
        const val FILE_NATIVE_KIND = "android_file_tool"
        const val QUESTION_TOOL = "request_user_question"
        const val CONFIRMATION_TOOL = "request_user_confirmation"
        const val CAPABILITIES_TOOL = "device_capabilities_get"
        const val FILES_LIST_TOOL = "device_files_list"
        const val FILES_READ_TOOL = "device_files_read"
        const val FILES_PREPARE_TOOL = "device_files_prepare_changes"
        const val FILES_COMMIT_TOOL = "device_files_commit_changes"
        const val PROJECT_NATIVE_KIND = "android_project_tool"
        const val ATTACHMENT_NATIVE_KIND = "android_attachment_tool"
        const val MEDIA_NATIVE_KIND = "android_media_tool"
        const val MEDIA_LIST_TOOL = DeviceMediaListExecutor.TOOL_NAME
        const val DEVICE_ID = "phone-local-android"
        const val CAPABILITY_VERSION = 1L
        const val ATTENTION_TTL_MILLIS = 30 * 60_000L
        const val TOOL_TTL_MILLIS = 30 * 60_000L
        val ATTENTION_TOOLS = setOf(QUESTION_TOOL, CONFIRMATION_TOOL)
        val CONSENT_TOOLS = setOf(FILES_READ_TOOL, FILES_COMMIT_TOOL)
        val ATTENTION_CANCELLED = DeviceClientWireError(
            "ATTENTION_CANCELLED",
            "Attention request was cancelled",
        )
        val USER_DECLINED = DeviceClientWireError(
            "USER_DECLINED",
            "User declined the confirmation",
        )
        val CONTENT_READ_DECLINED = DeviceClientWireError(
            "CONTENT_READ_DECLINED",
            "User declined file content access",
        )
        val CONTENT_READ_CANCELLED = DeviceClientWireError(
            "CONTENT_READ_CANCELLED",
            "File content request was cancelled",
        )
        val FILE_COMMIT_CANCELLED = DeviceClientWireError(
            "FILE_COMMIT_CANCELLED",
            "File commit was cancelled",
        )
        val USER_DECLINED_FILE_CHANGES = DeviceClientWireError(
            "USER_DECLINED",
            "User declined the file changes",
        )
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }
    }
}

internal data class PiNativeAndroidToolResult(
    val contentPayload: JsonObject,
    val details: JsonObject = contentPayload,
    val isError: Boolean = false,
)
