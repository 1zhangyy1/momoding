package app.momoding.core.runtime.local

import app.momoding.core.accessibility.AccessibilityBounds
import app.momoding.core.accessibility.AccessibilityNodeSnapshot
import app.momoding.core.accessibility.AccessibilityUiActionDescription
import app.momoding.core.accessibility.AccessibilityUiActionKind
import app.momoding.core.accessibility.AccessibilityUiSnapshot
import app.momoding.core.policy.CapabilityAction
import app.momoding.core.policy.CapabilityTarget
import app.momoding.core.policy.CapabilityTargetKind
import app.momoding.core.policy.DeviceActionPolicy
import app.momoding.core.policy.DeviceActionRequest
import app.momoding.core.policy.PolicyDecisionKind
import app.momoding.core.policy.TaskApprovalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneLocalUiToolPolicyTest {
    @Test
    fun lowRiskClickPromptsOnlyInRequestApproval() {
        val action = classifyUiAction(description(node(text = "Open details")))
        assertEquals(CapabilityAction.CLICK_NAVIGATION, action)

        assertEquals(
            PolicyDecisionKind.PROMPT_USER,
            decide(action, TaskApprovalMode.REQUEST_APPROVAL),
        )
        assertEquals(
            PolicyDecisionKind.AUTO_ALLOW,
            decide(action, TaskApprovalMode.AUTO_APPROVE),
        )
        assertEquals(
            PolicyDecisionKind.AUTO_ALLOW,
            decide(action, TaskApprovalMode.FULL_ACCESS),
        )
    }

    @Test
    fun submitStillPromptsInAutoApproveButFullAccessCanRunIt() {
        val action = classifyUiAction(description(node(text = "Submit form")))
        assertEquals(CapabilityAction.SUBMIT_FORM, action)

        assertEquals(
            PolicyDecisionKind.PROMPT_USER,
            decide(action, TaskApprovalMode.AUTO_APPROVE),
        )
        assertEquals(
            PolicyDecisionKind.AUTO_ALLOW,
            decide(action, TaskApprovalMode.FULL_ACCESS),
        )
    }

    @Test
    fun sensitiveAndAndroidPermissionTargetsAreHardDeniedInEveryMode() {
        val actions = listOf(
            classifyUiAction(description(node(text = "OTP verification code"))),
            classifyUiAction(description(node(text = "Pay now"))),
            classifyUiAction(description(node(redacted = true))),
            classifyUiAction(
                description(
                    node(text = "Allow"),
                    packageName = "com.android.permissioncontroller",
                ),
            ),
        )

        TaskApprovalMode.entries.forEach { mode ->
            actions.forEach { action ->
                assertEquals(PolicyDecisionKind.DENY, decide(action, mode))
            }
        }
    }

    @Test
    fun truncatedContextCannotAuthorizeAnAction() {
        assertFalse(
            isUiActionContextComplete(
                context(nodes = listOf(node(text = "Continue")), truncated = true),
            ),
        )
    }

    @Test
    fun fullWindowContextFindsSensitiveFieldsAndGenericDestructiveConfirmation() {
        assertEquals(
            CapabilityAction.HANDLE_PASSWORD,
            classifyUiAction(
                description(node(text = "Continue")),
                context(nodes = listOf(node(text = "Password"), node(text = "Continue"))),
            ),
        )
        assertEquals(
            CapabilityAction.SUBMIT_FORM,
            classifyUiAction(
                description(node(text = "OK")),
                context(nodes = listOf(node(text = "Delete this project?"), node(text = "OK"))),
            ),
        )
    }

    @Test
    fun automaticLowRiskGrantCannotExpandIntoDestructiveContext() {
        val authorization = PhoneLocalUiActionAuthorization(
            taskMode = TaskApprovalMode.AUTO_APPROVE,
            approvedAction = CapabilityAction.CLICK_NAVIGATION,
            userApproved = false,
        )

        assertEquals(
            UiActionAuthorizationFailure.REAPPROVAL_REQUIRED,
            uiActionAuthorizationFailure(
                authorization = authorization,
                currentAction = CapabilityAction.SUBMIT_FORM,
                currentDecision = decision(
                    CapabilityAction.SUBMIT_FORM,
                    TaskApprovalMode.AUTO_APPROVE,
                ),
            ),
        )
    }

    @Test
    fun userApprovalCoversOnlyTheRiskClassShownInItsPrompt() {
        val authorization = PhoneLocalUiActionAuthorization(
            taskMode = TaskApprovalMode.REQUEST_APPROVAL,
            approvedAction = CapabilityAction.CLICK_NAVIGATION,
            userApproved = true,
        )

        assertEquals(
            UiActionAuthorizationFailure.REAPPROVAL_REQUIRED,
            uiActionAuthorizationFailure(
                authorization = authorization,
                currentAction = CapabilityAction.SUBMIT_FORM,
                currentDecision = decision(
                    CapabilityAction.SUBMIT_FORM,
                    TaskApprovalMode.REQUEST_APPROVAL,
                ),
            ),
        )
    }

    private fun decide(action: CapabilityAction, mode: TaskApprovalMode): PolicyDecisionKind {
        return decision(action, mode).kind
    }

    private fun decision(action: CapabilityAction, mode: TaskApprovalMode) =
        DeviceActionPolicy.decide(
            mode,
            request(action),
        )

    private fun request(action: CapabilityAction): DeviceActionRequest {
        val targetKind = when (action) {
            CapabilityAction.SUBMIT_FORM -> CapabilityTargetKind.EXTERNAL_COMMUNICATION
            CapabilityAction.HANDLE_PASSWORD,
            CapabilityAction.HANDLE_OTP,
            -> CapabilityTargetKind.SENSITIVE_DATA
            CapabilityAction.PAYMENT_OR_TRANSFER -> CapabilityTargetKind.EXTERNAL_COMMUNICATION
            CapabilityAction.ANDROID_PERMISSION_FLOW -> CapabilityTargetKind.SYSTEM_SETTING
            else -> CapabilityTargetKind.APP_UI
        }
        return DeviceActionRequest(
            action = action,
            target = CapabilityTarget(targetKind, "fixture", allowedByAndroid = true),
            capabilityReady = true,
            deviceSessionActive = true,
            observationFresh = true,
        )
    }

    private fun description(
        node: AccessibilityNodeSnapshot?,
        packageName: String = "dev.fixture",
    ) = AccessibilityUiActionDescription(
        action = AccessibilityUiActionKind.CLICK,
        packageName = packageName,
        node = node,
        snapshotObservedAtMillis = 1,
    )

    private fun node(
        text: String? = null,
        redacted: Boolean = false,
    ) = AccessibilityNodeSnapshot(
        handle = "ui-11111111111111111111111111111111:n1",
        parentHandle = null,
        windowId = 1,
        depth = 0,
        packageName = "dev.fixture",
        className = "android.widget.Button",
        resourceId = null,
        bounds = AccessibilityBounds(0, 0, 100, 100),
        text = text,
        contentDescription = null,
        hint = null,
        redacted = redacted,
        clickable = true,
        longClickable = false,
        editable = false,
        scrollable = false,
        checkable = false,
        checked = false,
        enabled = true,
        visibleToUser = true,
    )

    private fun context(
        nodes: List<AccessibilityNodeSnapshot>,
        truncated: Boolean = false,
    ) = AccessibilityUiSnapshot(
        snapshotId = "ui-11111111111111111111111111111111",
        observedAtMillis = 1,
        packageName = "dev.fixture",
        windows = emptyList(),
        nodes = nodes,
        truncated = truncated,
        redactedNodeCount = nodes.count(AccessibilityNodeSnapshot::redacted),
    )
}
