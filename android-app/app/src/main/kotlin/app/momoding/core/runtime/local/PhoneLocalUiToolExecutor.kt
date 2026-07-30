package app.momoding.core.runtime.local

import app.momoding.core.accessibility.AccessibilityActionDescriptionResult
import app.momoding.core.accessibility.AccessibilityInspectionRequest
import app.momoding.core.accessibility.AccessibilityInspectionResult
import app.momoding.core.accessibility.AccessibilityNodeSnapshot
import app.momoding.core.accessibility.AccessibilityRawActionResult
import app.momoding.core.accessibility.AccessibilityScrollDirection
import app.momoding.core.accessibility.AccessibilitySessionController
import app.momoding.core.accessibility.AccessibilityUiActionDescription
import app.momoding.core.accessibility.AccessibilityUiActionKind
import app.momoding.core.accessibility.AccessibilityUiActionRequest
import app.momoding.core.accessibility.AccessibilityUiSnapshot
import app.momoding.core.accessibility.MAX_ACCESSIBILITY_NODES
import app.momoding.core.policy.CapabilityAction
import app.momoding.core.policy.CapabilityTarget
import app.momoding.core.policy.CapabilityTargetKind
import app.momoding.core.policy.DeviceActionPolicy
import app.momoding.core.policy.DeviceActionRequest
import app.momoding.core.policy.DeviceExecutionState
import app.momoding.core.policy.PolicyDecision
import app.momoding.core.policy.PolicyDecisionKind
import app.momoding.core.policy.TaskApprovalMode
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface PhoneLocalUiToolHandler {
    suspend fun inspect(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult

    fun decideAction(
        taskId: String,
        request: PiNativeToolRequest,
        mode: TaskApprovalMode,
    ): PhoneLocalUiActionDisposition

    suspend fun executeAction(
        taskId: String,
        request: PiNativeToolRequest,
        authorization: PhoneLocalUiActionAuthorization,
    ): PiNativeAndroidToolResult

    fun handles(toolName: String): Boolean = toolName in setOf(
        PhoneLocalUiToolExecutor.INSPECT_TOOL,
        PhoneLocalUiToolExecutor.ACTION_TOOL,
    )

    fun stopTask(taskId: String, reason: String) = Unit
}

data class PhoneLocalUiActionAuthorization(
    val taskMode: TaskApprovalMode,
    val approvedAction: CapabilityAction,
    val userApproved: Boolean,
)

sealed interface PhoneLocalUiActionDisposition {
    data class AutoAllow(val action: CapabilityAction) : PhoneLocalUiActionDisposition

    data class Prompt(
        val action: CapabilityAction,
        val summary: String,
        val details: String,
    ) : PhoneLocalUiActionDisposition

    data class Deny(val result: PiNativeAndroidToolResult) : PhoneLocalUiActionDisposition
}

class PhoneLocalUiToolExecutor(
    private val controller: AccessibilitySessionController,
    private val isDeviceLocked: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val postActionDelayMillis: Long = 180,
) : PhoneLocalUiToolHandler {
    private val sessions = ConcurrentHashMap<String, UiControlSession>()

    override suspend fun inspect(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        require(request.toolName == INSPECT_TOOL) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        val targetPackage = request.optionalString("targetPackage", MAX_PACKAGE_LENGTH)
        val maxNodes = request.arguments["maxNodes"]?.jsonPrimitive?.intOrNull
            ?: MAX_ACCESSIBILITY_NODES
        require(
            request.arguments.keys.all { it == "targetPackage" || it == "maxNodes" } &&
                maxNodes in 1..MAX_ACCESSIBILITY_NODES
        ) { "PI_MOBILE_UI_INSPECT_ARGUMENTS_INVALID" }
        if (isDeviceLocked()) {
            return failed("UI_CONTROL_DEVICE_LOCKED", "Unlock the device before inspecting it.")
        }
        val result = controller.inspect(
            AccessibilityInspectionRequest(
                requestId = request.id,
                targetPackage = targetPackage,
                maxNodes = maxNodes,
            ),
        )
        if (result !is AccessibilityInspectionResult.Ready) return inspectionFailure(result)
        val snapshot = result.snapshot
        val packageName = snapshot.packageName
            ?: return failed(
                "UI_CONTROL_TARGET_UNAVAILABLE",
                "No foreground Android app could be identified.",
            )
        val now = nowMillis()
        val userGeneration = controller.state.value.userInteractionGeneration
        val session = sessions.compute(taskId) { _, existing ->
            when {
                existing == null || existing.isExpired(now) ->
                    UiControlSession(now, packageName, userGeneration)
                existing.targetPackage != packageName -> existing
                else -> existing.apply {
                    paused = false
                    userInteractionGeneration = userGeneration
                }
            }
        } ?: error("UI session creation failed")
        synchronized(session) {
            if (session.targetPackage != packageName) {
                return failed(
                    "UI_CONTROL_TARGET_CHANGED",
                    "This task is already bound to another foreground app.",
                )
            }
            session.snapshots[snapshot.snapshotId] = snapshot.observedAtMillis
            while (session.snapshots.size > MAX_TASK_SNAPSHOTS) {
                session.snapshots.remove(session.snapshots.keys.first())
            }
        }
        return succeeded(
            snapshot.toToolJson(
                actionCount = synchronized(session) { session.actionCount },
                sessionExpiresAtMillis = session.startedAtMillis + SESSION_TTL_MILLIS,
            ),
        )
    }

    override fun decideAction(
        taskId: String,
        request: PiNativeToolRequest,
        mode: TaskApprovalMode,
    ): PhoneLocalUiActionDisposition {
        require(request.toolName == ACTION_TOOL) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        val parsed = runCatching { request.toUiActionRequest() }.getOrElse {
            return PhoneLocalUiActionDisposition.Deny(
                failed("UI_ACTION_ARGUMENTS_INVALID", "The interface action is invalid."),
            )
        }
        val evaluation = evaluate(taskId, parsed, mode, acknowledgeUser = false)
        if (evaluation is ActionEvaluation.Failed) {
            return PhoneLocalUiActionDisposition.Deny(
                failed(evaluation.code, evaluation.safeMessage),
            )
        }
        evaluation as ActionEvaluation.Ready
        return when (evaluation.policy.kind) {
            PolicyDecisionKind.AUTO_ALLOW ->
                PhoneLocalUiActionDisposition.AutoAllow(evaluation.capabilityAction)
            PolicyDecisionKind.PROMPT_USER -> PhoneLocalUiActionDisposition.Prompt(
                action = evaluation.capabilityAction,
                summary = approvalSummary(evaluation.description),
                details = approvalDetails(evaluation.description, parsed),
            )
            PolicyDecisionKind.DENY -> PhoneLocalUiActionDisposition.Deny(
                failed(
                    if (evaluation.policy.reason.name == "HARD_BOUNDARY") {
                        "UI_ACTION_HARD_DENY"
                    } else {
                        "UI_ACTION_POLICY_DENIED"
                    },
                    if (evaluation.policy.reason.name == "HARD_BOUNDARY") {
                        "Momoding will not act on passwords, verification codes, payments, " +
                            "account security, or Android permission screens."
                    } else {
                        "The current Android task policy denied this interface action."
                    },
                ),
            )
        }
    }

    override suspend fun executeAction(
        taskId: String,
        request: PiNativeToolRequest,
        authorization: PhoneLocalUiActionAuthorization,
    ): PiNativeAndroidToolResult {
        val parsed = runCatching { request.toUiActionRequest() }.getOrElse {
            return failed("UI_ACTION_ARGUMENTS_INVALID", "The interface action is invalid.")
        }
        if (authorization.userApproved) {
            val session = sessions[taskId]
                ?: return failed(
                    "UI_CONTROL_SESSION_REQUIRED",
                    "Inspect the current interface before using interface actions.",
                )
            for (attempt in 0 until TARGET_RETURN_ATTEMPTS) {
                if (controller.state.value.foregroundPackage == session.targetPackage) {
                    break
                }
                if (isDeviceLocked()) {
                    return failed(
                        "UI_CONTROL_DEVICE_LOCKED",
                        "Unlock the device before using interface actions.",
                    )
                }
                delay(TARGET_RETURN_POLL_MILLIS)
            }
            if (controller.state.value.foregroundPackage != session.targetPackage) {
                return failed(
                    "UI_ACTION_TARGET_RETURN_TIMEOUT",
                    "Return to the approved target app within 15 seconds, then inspect again.",
                )
            }
        }
        val evaluation = evaluate(
            taskId = taskId,
            request = parsed,
            mode = authorization.taskMode,
            acknowledgeUser = authorization.userApproved,
        )
        if (evaluation is ActionEvaluation.Failed) {
            return failed(evaluation.code, evaluation.safeMessage)
        }
        evaluation as ActionEvaluation.Ready
        when (
            uiActionAuthorizationFailure(
                authorization = authorization,
                currentAction = evaluation.capabilityAction,
                currentDecision = evaluation.policy,
            )
        ) {
            UiActionAuthorizationFailure.REAPPROVAL_REQUIRED -> return failed(
                "UI_ACTION_REAPPROVAL_REQUIRED",
                "The interface action changed risk after it was approved. Inspect it again and " +
                    "request a new approval.",
            )
            UiActionAuthorizationFailure.POLICY_DENIED -> return failed(
                "UI_ACTION_POLICY_DENIED",
                "The current Android task policy denied this interface action.",
            )
            null -> Unit
        }
        val session = requireNotNull(sessions[taskId])
        val before = controller.snapshot(parsed.snapshotId)
            ?: return failed(
                "UI_SNAPSHOT_NOT_FOUND",
                "Inspect the current interface again before acting.",
            )
        synchronized(session) {
            if (session.actionCount >= MAX_ACTIONS_PER_SESSION) {
                return failed(
                    "UI_ACTION_LIMIT_REACHED",
                    "This task has reached its 20-action interface session limit.",
                )
            }
            session.actionCount += 1
        }
        when (val result = controller.performAction(parsed)) {
            is AccessibilityRawActionResult.Failed ->
                return failed(result.code, result.safeMessage)
            AccessibilityRawActionResult.Performed -> Unit
        }
        if (postActionDelayMillis > 0) delay(postActionDelayMillis)
        if (
            isDeviceLocked() ||
            controller.state.value.userInteractionGeneration !=
            synchronized(session) { session.userInteractionGeneration }
        ) {
            synchronized(session) { session.paused = true }
            return failed(
                "UI_ACTION_USER_TAKEOVER",
                "Interface control paused because the user took over the device.",
            )
        }
        val targetBoundAfter = controller.inspect(
            AccessibilityInspectionRequest(
                requestId = "${request.id}-after",
                targetPackage = session.targetPackage,
            ),
        )
        val after = when (targetBoundAfter) {
            is AccessibilityInspectionResult.Ready -> targetBoundAfter
            AccessibilityInspectionResult.TargetChanged -> controller.inspect(
                AccessibilityInspectionRequest(
                    requestId = "${request.id}-after-target-change",
                    targetPackage = null,
                ),
            ) as? AccessibilityInspectionResult.Ready
            else -> null
        }
        if (after == null) {
            synchronized(session) { session.paused = true }
            return failed(
                "UI_ACTION_OUTCOME_UNKNOWN",
                "Android accepted the action, but its resulting screen could not be verified. " +
                    "Inspect again; do not automatically repeat the action.",
            )
        }
        val afterPackage = after.snapshot.packageName
            ?: return failed(
                "UI_ACTION_OUTCOME_UNKNOWN",
                "Android accepted the action, but the resulting app could not be identified. " +
                    "Inspect again; do not automatically repeat the action.",
            )
        val targetChanged = afterPackage != session.targetPackage
        val changed = before.semanticDigest() != after.snapshot.semanticDigest()
        val noChangeCount: Int
        val paused: Boolean
        synchronized(session) {
            session.snapshots[after.snapshot.snapshotId] = after.snapshot.observedAtMillis
            session.userInteractionGeneration = controller.state.value.userInteractionGeneration
            session.noChangeCount = if (changed) 0 else session.noChangeCount + 1
            if (
                targetChanged ||
                session.noChangeCount >= MAX_CONSECUTIVE_NO_CHANGE
            ) {
                session.paused = true
            }
            noChangeCount = session.noChangeCount
            paused = session.paused
        }
        return succeeded(
            buildJsonObject {
                put("ok", true)
                put("action", parsed.action.wireValue)
                put("beforeSnapshotId", parsed.snapshotId)
                put("afterSnapshotId", after.snapshot.snapshotId)
                put("foregroundPackage", afterPackage)
                put("targetChanged", targetChanged)
                put("changed", changed)
                put("noChangeCount", noChangeCount)
                put("sessionPaused", paused)
                put("actionCount", synchronized(session) { session.actionCount })
            },
        )
    }

    override fun stopTask(taskId: String, reason: String) {
        if (reason == ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON) return
        if (sessions.remove(taskId) != null) controller.stop()
    }

    private fun evaluate(
        taskId: String,
        request: AccessibilityUiActionRequest,
        mode: TaskApprovalMode,
        acknowledgeUser: Boolean,
    ): ActionEvaluation {
        val session = sessions[taskId]
            ?: return ActionEvaluation.Failed(
                "UI_CONTROL_SESSION_REQUIRED",
                "Inspect the current interface before using interface actions.",
            )
        val now = nowMillis()
        val observedAt = synchronized(session) { session.snapshots[request.snapshotId] }
            ?: return ActionEvaluation.Failed(
                "UI_SNAPSHOT_TASK_MISMATCH",
                "This interface snapshot does not belong to the current task.",
            )
        val currentUserGeneration = controller.state.value.userInteractionGeneration
        val executionState = synchronized(session) {
            when {
                session.isExpired(now) -> DeviceExecutionState.STOP_REQUESTED
                session.paused -> DeviceExecutionState.USER_TAKEOVER
                acknowledgeUser -> {
                    session.userInteractionGeneration = currentUserGeneration
                    DeviceExecutionState.ACTIVE
                }
                session.userInteractionGeneration != currentUserGeneration ->
                    DeviceExecutionState.USER_TAKEOVER
                else -> DeviceExecutionState.ACTIVE
            }
        }
        if (isDeviceLocked()) {
            return ActionEvaluation.Failed(
                "UI_CONTROL_DEVICE_LOCKED",
                "Unlock the device before using interface actions.",
            )
        }
        val described = controller.describeAction(request)
        if (described is AccessibilityActionDescriptionResult.Failed) {
            return ActionEvaluation.Failed(described.code, described.safeMessage)
        }
        described as AccessibilityActionDescriptionResult.Ready
        if (described.description.packageName != session.targetPackage) {
            return ActionEvaluation.Failed(
                "UI_ACTION_TARGET_CHANGED",
                "The visible app changed; inspect the new interface before acting.",
            )
        }
        val context = when (
            val inspected = controller.inspect(
                AccessibilityInspectionRequest(
                    requestId = "policy-${request.snapshotId}-${now}",
                    targetPackage = session.targetPackage,
                    maxNodes = MAX_ACCESSIBILITY_NODES,
                ),
            )
        ) {
            is AccessibilityInspectionResult.Ready -> inspected.snapshot
            AccessibilityInspectionResult.TargetChanged ->
                return ActionEvaluation.Failed(
                    "UI_ACTION_TARGET_CHANGED",
                    "The visible app changed; inspect the new interface before acting.",
                )
            AccessibilityInspectionResult.Stale ->
                return ActionEvaluation.Failed(
                    "UI_SNAPSHOT_STALE",
                    "The screen changed during the safety check; inspect again.",
                )
            AccessibilityInspectionResult.Stopped ->
                return ActionEvaluation.Failed(
                    "UI_ACTION_STOPPED",
                    "The interface action was stopped.",
                )
            AccessibilityInspectionResult.NotConnected ->
                return ActionEvaluation.Failed(
                    "UI_CONTROL_SESSION_REQUIRED",
                    "Enable Accessibility control before using interface actions.",
                )
            is AccessibilityInspectionResult.Failed ->
                return ActionEvaluation.Failed(
                    "UI_ACTION_OUTCOME_UNKNOWN",
                    inspected.safeMessage,
                )
        }
        if (!isUiActionContextComplete(context)) {
            return ActionEvaluation.Failed(
                "UI_ACTION_CONTEXT_TRUNCATED",
                "The visible interface is too large to classify safely; narrow the screen and " +
                    "inspect again.",
            )
        }
        val action = classifyUiAction(described.description, context)
        val targetKind = if (action == CapabilityAction.SUBMIT_FORM) {
            CapabilityTargetKind.EXTERNAL_COMMUNICATION
        } else if (action in HARD_SENSITIVE_ACTIONS) {
            DeviceActionPolicy.allowedTargetKinds(action).first()
        } else {
            CapabilityTargetKind.APP_UI
        }
        val decision = DeviceActionPolicy.decide(
            mode,
            DeviceActionRequest(
                action = action,
                target = CapabilityTarget(
                    kind = targetKind,
                    syntheticId = buildString {
                        append(session.targetPackage)
                        described.description.node?.handle?.let { append(':').append(it) }
                    },
                    allowedByAndroid = true,
                ),
                capabilityReady = controller.state.value.connected,
                executionState = executionState,
                deviceSessionActive = !session.isExpired(now),
                observationFresh = now - observedAt <= SNAPSHOT_TTL_MILLIS,
            ),
        )
        return ActionEvaluation.Ready(
            description = described.description,
            capabilityAction = action,
            policy = decision,
        )
    }

    private fun approvalSummary(description: AccessibilityUiActionDescription): String = when (
        description.action
    ) {
        AccessibilityUiActionKind.CLICK -> "Allow Momoding to click this control?"
        AccessibilityUiActionKind.SCROLL -> "Allow Momoding to scroll this view?"
        AccessibilityUiActionKind.INPUT_DRAFT -> "Allow Momoding to enter draft text?"
        AccessibilityUiActionKind.BACK -> "Allow Momoding to go back?"
    }

    private fun approvalDetails(
        description: AccessibilityUiActionDescription,
        request: AccessibilityUiActionRequest,
    ): String = buildString {
        append("Target app: ").append(description.packageName)
        description.node?.searchableLabel()?.takeIf(String::isNotBlank)?.let {
            append(". Control: ").append(it.take(MAX_APPROVAL_LABEL_LENGTH))
        }
        request.direction?.let { append(". Direction: ").append(it.wireValue) }
        request.text?.let { append(". Draft: ").append(it.take(MAX_APPROVAL_LABEL_LENGTH)) }
        append(". After approving, return to this app within 15 seconds. ")
        append("Android will re-check the full screen and control immediately before acting.")
    }

    private fun inspectionFailure(result: AccessibilityInspectionResult): PiNativeAndroidToolResult =
        when (result) {
            AccessibilityInspectionResult.NotConnected ->
                failed("UI_CONTROL_SESSION_REQUIRED", "Enable Accessibility control first.")
            AccessibilityInspectionResult.Stopped ->
                failed("UI_ACTION_STOPPED", "The interface session was stopped.")
            AccessibilityInspectionResult.Stale ->
                failed("UI_SNAPSHOT_STALE", "The screen changed during inspection; try again.")
            AccessibilityInspectionResult.TargetChanged ->
                failed("UI_CONTROL_TARGET_CHANGED", "The visible app is not the requested target.")
            is AccessibilityInspectionResult.Failed ->
                failed("UI_INSPECTION_FAILED", result.safeMessage)
            is AccessibilityInspectionResult.Ready -> error("Ready result is not a failure")
        }

    private fun succeeded(payload: JsonObject) = PiNativeAndroidToolResult(
        contentPayload = payload,
        details = payload,
    )

    private fun failed(code: String, message: String): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("errorCode", code)
            put("errorMessage", message)
        }
        return PiNativeAndroidToolResult(
            contentPayload = payload,
            details = payload,
            isError = true,
        )
    }

    private sealed interface ActionEvaluation {
        data class Ready(
            val description: AccessibilityUiActionDescription,
            val capabilityAction: CapabilityAction,
            val policy: PolicyDecision,
        ) : ActionEvaluation

        data class Failed(val code: String, val safeMessage: String) : ActionEvaluation
    }

    private data class UiControlSession(
        val startedAtMillis: Long,
        val targetPackage: String,
        var userInteractionGeneration: Long,
        var actionCount: Int = 0,
        var noChangeCount: Int = 0,
        var paused: Boolean = false,
        val snapshots: LinkedHashMap<String, Long> = linkedMapOf(),
    ) {
        fun isExpired(now: Long): Boolean = now - startedAtMillis >= SESSION_TTL_MILLIS
    }

    companion object {
        const val INSPECT_TOOL = "device_ui_inspect"
        const val ACTION_TOOL = "device_ui_action"
        private const val SESSION_TTL_MILLIS = 10 * 60_000L
        private const val SNAPSHOT_TTL_MILLIS = 30_000L
        private const val MAX_ACTIONS_PER_SESSION = 20
        private const val MAX_CONSECUTIVE_NO_CHANGE = 3
        private const val MAX_TASK_SNAPSHOTS = 8
        private const val MAX_PACKAGE_LENGTH = 255
        private const val MAX_APPROVAL_LABEL_LENGTH = 160
        private const val TARGET_RETURN_POLL_MILLIS = 100L
        private const val TARGET_RETURN_ATTEMPTS = 150
        private val HARD_SENSITIVE_ACTIONS = setOf(
            CapabilityAction.HANDLE_PASSWORD,
            CapabilityAction.HANDLE_OTP,
            CapabilityAction.PAYMENT_OR_TRANSFER,
            CapabilityAction.CHANGE_ACCOUNT_SECURITY,
            CapabilityAction.ANDROID_PERMISSION_FLOW,
        )
    }
}

internal enum class UiActionAuthorizationFailure {
    REAPPROVAL_REQUIRED,
    POLICY_DENIED,
}

internal fun uiActionAuthorizationFailure(
    authorization: PhoneLocalUiActionAuthorization,
    currentAction: CapabilityAction,
    currentDecision: PolicyDecision,
): UiActionAuthorizationFailure? = when {
    currentAction != authorization.approvedAction ->
        UiActionAuthorizationFailure.REAPPROVAL_REQUIRED
    currentDecision.kind == PolicyDecisionKind.DENY ->
        UiActionAuthorizationFailure.POLICY_DENIED
    currentDecision.kind == PolicyDecisionKind.PROMPT_USER && !authorization.userApproved ->
        UiActionAuthorizationFailure.REAPPROVAL_REQUIRED
    else -> null
}

internal fun isUiActionContextComplete(snapshot: AccessibilityUiSnapshot): Boolean =
    !snapshot.truncated

internal fun classifyUiAction(
    description: AccessibilityUiActionDescription,
    context: AccessibilityUiSnapshot? = null,
): CapabilityAction {
    if (description.packageName in FORBIDDEN_SYSTEM_PACKAGES) {
        return CapabilityAction.ANDROID_PERMISSION_FLOW
    }
    val node = description.node
    if (node?.redacted == true) return CapabilityAction.HANDLE_PASSWORD
    val label = node?.searchableLabel().orEmpty()
    val contextLabel = context?.nodes
        ?.joinToString(" ") { it.searchableLabel() }
        .orEmpty()
    return when {
        context?.redactedNodeCount?.let { it > 0 } == true ->
            CapabilityAction.HANDLE_PASSWORD
        PASSWORD_WORDS.any(contextLabel::contains) -> CapabilityAction.HANDLE_PASSWORD
        OTP_WORDS.any(contextLabel::contains) -> CapabilityAction.HANDLE_OTP
        PAYMENT_WORDS.any(contextLabel::contains) -> CapabilityAction.PAYMENT_OR_TRANSFER
        ACCOUNT_SECURITY_WORDS.any(contextLabel::contains) ->
            CapabilityAction.CHANGE_ACCOUNT_SECURITY
        PERMISSION_WORDS.any(contextLabel::contains) ->
            CapabilityAction.ANDROID_PERMISSION_FLOW
        PASSWORD_WORDS.any(label::contains) -> CapabilityAction.HANDLE_PASSWORD
        OTP_WORDS.any(label::contains) -> CapabilityAction.HANDLE_OTP
        PAYMENT_WORDS.any(label::contains) -> CapabilityAction.PAYMENT_OR_TRANSFER
        ACCOUNT_SECURITY_WORDS.any(label::contains) ->
            CapabilityAction.CHANGE_ACCOUNT_SECURITY
        PERMISSION_WORDS.any(label::contains) -> CapabilityAction.ANDROID_PERMISSION_FLOW
        description.action == AccessibilityUiActionKind.CLICK &&
            SUBMIT_WORDS.any(label::contains) -> CapabilityAction.SUBMIT_FORM
        description.action == AccessibilityUiActionKind.CLICK &&
            CONFIRMATION_WORDS.any { label.trim() == it } &&
            SUBMIT_WORDS.any(contextLabel::contains) -> CapabilityAction.SUBMIT_FORM
        description.action == AccessibilityUiActionKind.CLICK ->
            CapabilityAction.CLICK_NAVIGATION
        description.action == AccessibilityUiActionKind.SCROLL -> CapabilityAction.SCROLL
        description.action == AccessibilityUiActionKind.INPUT_DRAFT ->
            CapabilityAction.INPUT_DRAFT
        else -> CapabilityAction.NAVIGATE_BACK
    }
}

private fun PiNativeToolRequest.optionalString(name: String, maximum: Int): String? = when (
    val value = arguments[name]
) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.also {
        require(it.isNotBlank() && it.length <= maximum)
    }
    else -> throw IllegalArgumentException("$name must be a string")
}

private fun PiNativeToolRequest.toUiActionRequest(): AccessibilityUiActionRequest {
    require(arguments.keys.all { it in UI_ACTION_ARGUMENT_KEYS })
    val action = AccessibilityUiActionKind.fromWire(
        arguments["action"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("action is missing"),
    )
    return AccessibilityUiActionRequest(
        snapshotId = arguments["snapshotId"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("snapshotId is missing"),
        nodeHandle = optionalString("nodeHandle", 320),
        action = action,
        text = optionalString("text", 4_096),
        direction = optionalString("direction", 16)?.let(
            AccessibilityScrollDirection::fromWire,
        ),
    )
}

private fun AccessibilityNodeSnapshot.searchableLabel(): String = listOfNotNull(
    text,
    contentDescription,
    hint,
    resourceId,
).joinToString(" ").lowercase()

private fun AccessibilityUiSnapshot.toToolJson(
    actionCount: Int,
    sessionExpiresAtMillis: Long,
): JsonObject = buildJsonObject {
    put("ok", true)
    put("snapshotId", snapshotId)
    put("observedAtMillis", observedAtMillis)
    packageName?.let { put("packageName", it) }
    put("truncated", truncated)
    put("redactedNodeCount", redactedNodeCount)
    put("actionCount", actionCount)
    put("actionLimit", 20)
    put("sessionExpiresAtMillis", sessionExpiresAtMillis)
    put("windows", buildJsonArray {
        windows.forEach { window ->
            add(buildJsonObject {
                put("windowId", window.windowId)
                put("type", window.type)
                put("active", window.active)
                put("focused", window.focused)
                window.rootHandle?.let { put("rootHandle", it) }
            })
        }
    })
    put("nodes", buildJsonArray {
        nodes.forEach { node ->
            add(buildJsonObject {
                put("handle", node.handle)
                node.parentHandle?.let { put("parentHandle", it) }
                put("windowId", node.windowId)
                put("depth", node.depth)
                node.className?.let { put("className", it) }
                node.resourceId?.let { put("resourceId", it) }
                node.text?.let { put("text", it) }
                node.contentDescription?.let { put("contentDescription", it) }
                node.hint?.let { put("hint", it) }
                put("redacted", node.redacted)
                put("clickable", node.clickable)
                put("editable", node.editable)
                put("scrollable", node.scrollable)
                put("checkable", node.checkable)
                put("checked", node.checked)
                put("enabled", node.enabled)
                put("visibleToUser", node.visibleToUser)
                put("bounds", buildJsonObject {
                    put("left", node.bounds.left)
                    put("top", node.bounds.top)
                    put("right", node.bounds.right)
                    put("bottom", node.bounds.bottom)
                })
            })
        }
    })
}

private fun AccessibilityUiSnapshot.semanticDigest(): String {
    val bytes = buildString {
        append(packageName).append('|').append(truncated).append('|')
        windows.forEach {
            append(it.windowId).append(':').append(it.type).append(':')
                .append(it.active).append(':').append(it.focused).append('|')
        }
        nodes.forEach {
            append(it.parentHandle?.substringAfterLast(":") ?: "root").append(':')
                .append(it.windowId).append(':').append(it.depth).append(':')
                .append(it.className).append(':').append(it.resourceId).append(':')
                .append(it.bounds).append(':').append(it.text).append(':')
                .append(it.contentDescription).append(':').append(it.hint).append(':')
                .append(it.redacted).append(':').append(it.clickable).append(':')
                .append(it.editable).append(':').append(it.scrollable).append(':')
                .append(it.checked).append(':').append(it.enabled).append('|')
        }
    }.encodeToByteArray()
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

private val UI_ACTION_ARGUMENT_KEYS = setOf(
    "snapshotId",
    "nodeHandle",
    "action",
    "text",
    "direction",
)

private val FORBIDDEN_SYSTEM_PACKAGES = setOf(
    "com.android.settings",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
)
private val PASSWORD_WORDS = listOf("password", "passcode", "密码", "口令")
private val OTP_WORDS = listOf("otp", "verification code", "验证码", "安全码")
private val PAYMENT_WORDS = listOf(
    "payment",
    "pay now",
    "purchase",
    "transfer",
    "银行卡",
    "支付",
    "付款",
    "转账",
)
private val ACCOUNT_SECURITY_WORDS = listOf(
    "account security",
    "two-factor",
    "2fa",
    "账户安全",
    "双重验证",
)
private val PERMISSION_WORDS = listOf(
    "permission",
    "allow access",
    "grant access",
    "授权",
    "允许访问",
)
private val SUBMIT_WORDS = listOf(
    "send",
    "submit",
    "publish",
    "post",
    "delete",
    "remove",
    "confirm",
    "发送",
    "提交",
    "发布",
    "删除",
    "确认",
)
private val CONFIRMATION_WORDS = listOf(
    "ok",
    "continue",
    "yes",
    "allow",
    "继续",
    "是",
    "允许",
)
