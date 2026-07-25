package app.momoding.core.policy

/** Exact Android-classified actions. Pi never supplies a risk level. */
enum class CapabilityAction {
    READ_CAPABILITY_STATE,
    READ_SHARED_METADATA,
    READ_SHARED_MEDIA_METADATA,
    INSPECT_UI_TREE,
    CAPTURE_SCREEN,
    READ_USER_FILE_CONTENT,
    OPEN_ALLOWLIST_APP,
    NAVIGATE_BACK,
    SCROLL,
    CLICK_NAVIGATION,
    INPUT_DRAFT,
    CREATE_FILE,
    RENAME_FILE,
    WRITE_NEW_FILE,
    OVERWRITE_FILE,
    DELETE_FILE,
    CHANGE_SETTING,
    SEND_MESSAGE,
    PUBLISH_CONTENT,
    SUBMIT_FORM,
    INSTALL_APP,
    UNINSTALL_APP,
    DISABLE_APP,
    HANDLE_PASSWORD,
    HANDLE_OTP,
    PAYMENT_OR_TRANSFER,
    CHANGE_ACCOUNT_SECURITY,
    ANDROID_PERMISSION_FLOW,
    ACCESS_OTHER_APP_PRIVATE_DATA,
    EXECUTE_ARBITRARY_SHELL,
    USE_ROOT_BACKEND,
}

enum class CapabilityTargetKind {
    DEVICE,
    APP_UI,
    SHARED_FILE,
    PACKAGE,
    SYSTEM_SETTING,
    EXTERNAL_COMMUNICATION,
    SENSITIVE_DATA,
}

/** [syntheticId] is an Android-owned package/root/node handle, never a host path or URI. */
data class CapabilityTarget(
    val kind: CapabilityTargetKind,
    val syntheticId: String,
    val allowedByAndroid: Boolean,
) {
    init {
        require(syntheticId.isNotBlank()) { "Capability target is blank" }
    }
}

enum class DeviceExecutionState {
    ACTIVE,
    STOP_REQUESTED,
    USER_TAKEOVER,
}

data class DeviceActionRequest(
    val action: CapabilityAction,
    val target: CapabilityTarget,
    val capabilityReady: Boolean,
    val executionState: DeviceExecutionState = DeviceExecutionState.ACTIVE,
    val deviceSessionActive: Boolean = false,
    val observationFresh: Boolean = false,
    /** A visible pending approval cannot become automatic after a mode switch. */
    val approvalAlreadyPending: Boolean = false,
)

enum class PolicyDecisionKind {
    DENY,
    PROMPT_USER,
    AUTO_ALLOW,
}

enum class PolicyDecisionReason {
    HARD_BOUNDARY,
    STOPPED,
    USER_TAKEOVER,
    CAPABILITY_NOT_READY,
    TARGET_KIND_MISMATCH,
    TARGET_NOT_ALLOWED,
    DEVICE_SESSION_REQUIRED,
    STALE_OBSERVATION,
    EXISTING_PENDING_APPROVAL,
    READ_ONLY_OBSERVATION,
    MODE_POLICY,
    SYSTEM_CONFIRMATION_REQUIRED,
}

data class PolicyDecision(
    val kind: PolicyDecisionKind,
    val reason: PolicyDecisionReason,
)

object DeviceActionPolicy {
    fun decide(mode: TaskApprovalMode, request: DeviceActionRequest): PolicyDecision {
        if (request.action in HARD_DENY_ACTIONS) return deny(PolicyDecisionReason.HARD_BOUNDARY)
        when (request.executionState) {
            DeviceExecutionState.STOP_REQUESTED -> return deny(PolicyDecisionReason.STOPPED)
            DeviceExecutionState.USER_TAKEOVER -> return deny(PolicyDecisionReason.USER_TAKEOVER)
            DeviceExecutionState.ACTIVE -> Unit
        }
        if (request.target.kind !in allowedTargetKinds(request.action)) {
            return deny(PolicyDecisionReason.TARGET_KIND_MISMATCH)
        }
        if (!request.target.allowedByAndroid) return deny(PolicyDecisionReason.TARGET_NOT_ALLOWED)
        if (request.action !in CAPABILITY_READINESS_INDEPENDENT_ACTIONS && !request.capabilityReady) {
            return deny(PolicyDecisionReason.CAPABILITY_NOT_READY)
        }
        if (request.action in DEVICE_SESSION_ACTIONS && !request.deviceSessionActive) {
            return deny(PolicyDecisionReason.DEVICE_SESSION_REQUIRED)
        }
        if (request.action in FRESH_OBSERVATION_ACTIONS && !request.observationFresh) {
            return deny(PolicyDecisionReason.STALE_OBSERVATION)
        }
        if (request.approvalAlreadyPending) {
            return prompt(PolicyDecisionReason.EXISTING_PENDING_APPROVAL)
        }
        if (request.action in READ_ONLY_ACTIONS) {
            return autoAllow(PolicyDecisionReason.READ_ONLY_OBSERVATION)
        }
        if (request.action in SYSTEM_CONFIRM_ACTIONS) {
            return prompt(PolicyDecisionReason.SYSTEM_CONFIRMATION_REQUIRED)
        }
        return when (request.action) {
            in USER_CONTENT_OR_LOW_RISK_ACTIONS -> when (mode) {
                TaskApprovalMode.REQUEST_APPROVAL -> prompt(PolicyDecisionReason.MODE_POLICY)
                TaskApprovalMode.AUTO_APPROVE,
                TaskApprovalMode.FULL_ACCESS,
                -> autoAllow(PolicyDecisionReason.MODE_POLICY)
            }
            in HIGH_RISK_ACTIONS -> when (mode) {
                TaskApprovalMode.REQUEST_APPROVAL,
                TaskApprovalMode.AUTO_APPROVE,
                -> prompt(PolicyDecisionReason.MODE_POLICY)
                TaskApprovalMode.FULL_ACCESS -> autoAllow(PolicyDecisionReason.MODE_POLICY)
            }
            else -> deny(PolicyDecisionReason.HARD_BOUNDARY)
        }
    }

    private fun deny(reason: PolicyDecisionReason) = PolicyDecision(PolicyDecisionKind.DENY, reason)
    private fun prompt(reason: PolicyDecisionReason) =
        PolicyDecision(PolicyDecisionKind.PROMPT_USER, reason)
    private fun autoAllow(reason: PolicyDecisionReason) =
        PolicyDecision(PolicyDecisionKind.AUTO_ALLOW, reason)

    /**
     * Android owns this exhaustive action/target contract. A decoded Pi request cannot choose a
     * more permissive target kind and approval mode is evaluated only after this check succeeds.
     */
    internal fun allowedTargetKinds(action: CapabilityAction): Set<CapabilityTargetKind> = when (action) {
        CapabilityAction.READ_CAPABILITY_STATE -> setOf(CapabilityTargetKind.DEVICE)
        CapabilityAction.READ_SHARED_METADATA,
        CapabilityAction.READ_SHARED_MEDIA_METADATA,
        CapabilityAction.READ_USER_FILE_CONTENT,
        CapabilityAction.CREATE_FILE,
        CapabilityAction.RENAME_FILE,
        CapabilityAction.WRITE_NEW_FILE,
        CapabilityAction.OVERWRITE_FILE,
        CapabilityAction.DELETE_FILE,
        -> setOf(CapabilityTargetKind.SHARED_FILE)
        CapabilityAction.INSPECT_UI_TREE,
        CapabilityAction.CAPTURE_SCREEN,
        CapabilityAction.NAVIGATE_BACK,
        CapabilityAction.SCROLL,
        CapabilityAction.CLICK_NAVIGATION,
        CapabilityAction.INPUT_DRAFT,
        -> setOf(CapabilityTargetKind.APP_UI)
        CapabilityAction.OPEN_ALLOWLIST_APP,
        CapabilityAction.INSTALL_APP,
        CapabilityAction.UNINSTALL_APP,
        CapabilityAction.DISABLE_APP,
        CapabilityAction.ACCESS_OTHER_APP_PRIVATE_DATA,
        -> setOf(CapabilityTargetKind.PACKAGE)
        CapabilityAction.CHANGE_SETTING,
        CapabilityAction.CHANGE_ACCOUNT_SECURITY,
        CapabilityAction.ANDROID_PERMISSION_FLOW,
        -> setOf(CapabilityTargetKind.SYSTEM_SETTING)
        CapabilityAction.SEND_MESSAGE,
        CapabilityAction.PUBLISH_CONTENT,
        CapabilityAction.SUBMIT_FORM,
        CapabilityAction.PAYMENT_OR_TRANSFER,
        -> setOf(CapabilityTargetKind.EXTERNAL_COMMUNICATION)
        CapabilityAction.HANDLE_PASSWORD,
        CapabilityAction.HANDLE_OTP,
        -> setOf(CapabilityTargetKind.SENSITIVE_DATA)
        CapabilityAction.EXECUTE_ARBITRARY_SHELL,
        CapabilityAction.USE_ROOT_BACKEND,
        -> setOf(CapabilityTargetKind.DEVICE)
    }

    private val READ_ONLY_ACTIONS = setOf(
        CapabilityAction.READ_CAPABILITY_STATE,
        CapabilityAction.READ_SHARED_METADATA,
        CapabilityAction.INSPECT_UI_TREE,
        CapabilityAction.CAPTURE_SCREEN,
    )
    private val CAPABILITY_READINESS_INDEPENDENT_ACTIONS = setOf(
        CapabilityAction.READ_CAPABILITY_STATE,
    )
    private val USER_CONTENT_OR_LOW_RISK_ACTIONS = setOf(
        CapabilityAction.READ_SHARED_MEDIA_METADATA,
        CapabilityAction.READ_USER_FILE_CONTENT,
        CapabilityAction.OPEN_ALLOWLIST_APP,
        CapabilityAction.NAVIGATE_BACK,
        CapabilityAction.SCROLL,
        CapabilityAction.CLICK_NAVIGATION,
        CapabilityAction.INPUT_DRAFT,
        CapabilityAction.CREATE_FILE,
        CapabilityAction.RENAME_FILE,
        CapabilityAction.WRITE_NEW_FILE,
    )
    private val HIGH_RISK_ACTIONS = setOf(
        CapabilityAction.OVERWRITE_FILE,
        CapabilityAction.DELETE_FILE,
        CapabilityAction.CHANGE_SETTING,
        CapabilityAction.SEND_MESSAGE,
        CapabilityAction.PUBLISH_CONTENT,
        CapabilityAction.SUBMIT_FORM,
    )
    private val SYSTEM_CONFIRM_ACTIONS = setOf(
        CapabilityAction.INSTALL_APP,
        CapabilityAction.UNINSTALL_APP,
        CapabilityAction.DISABLE_APP,
    )
    private val HARD_DENY_ACTIONS = setOf(
        CapabilityAction.HANDLE_PASSWORD,
        CapabilityAction.HANDLE_OTP,
        CapabilityAction.PAYMENT_OR_TRANSFER,
        CapabilityAction.CHANGE_ACCOUNT_SECURITY,
        CapabilityAction.ANDROID_PERMISSION_FLOW,
        CapabilityAction.ACCESS_OTHER_APP_PRIVATE_DATA,
        CapabilityAction.EXECUTE_ARBITRARY_SHELL,
        CapabilityAction.USE_ROOT_BACKEND,
    )
    private val DEVICE_SESSION_ACTIONS = setOf(
        CapabilityAction.INSPECT_UI_TREE,
        CapabilityAction.CAPTURE_SCREEN,
        CapabilityAction.OPEN_ALLOWLIST_APP,
        CapabilityAction.NAVIGATE_BACK,
        CapabilityAction.SCROLL,
        CapabilityAction.CLICK_NAVIGATION,
        CapabilityAction.INPUT_DRAFT,
    )
    private val FRESH_OBSERVATION_ACTIONS = setOf(
        CapabilityAction.OPEN_ALLOWLIST_APP,
        CapabilityAction.NAVIGATE_BACK,
        CapabilityAction.SCROLL,
        CapabilityAction.CLICK_NAVIGATION,
        CapabilityAction.INPUT_DRAFT,
    )
}

/**
 * Capability/tool data is copied independently from [mode]; only [decide] observes the mode.
 * PWR-1B/PWR-4 can reuse this boundary without filtering tools for an approval choice.
 */
class TaskApprovalScope(
    val mode: TaskApprovalMode,
    implementedToolNames: Set<String>,
    grantedCapabilityStates: Map<String, String>,
) {
    val implementedToolNames: Set<String> = implementedToolNames.toSet()
    val grantedCapabilityStates: Map<String, String> = grantedCapabilityStates.toMap()

    fun decide(request: DeviceActionRequest): PolicyDecision = DeviceActionPolicy.decide(mode, request)
}
