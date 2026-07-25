package app.momoding.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class DeviceActionPolicyMatrixTest(
    private val action: CapabilityAction,
    private val targetKind: CapabilityTargetKind,
    private val requestApproval: PolicyDecisionKind,
    private val autoApprove: PolicyDecisionKind,
    private val fullAccess: PolicyDecisionKind,
) {
    @Test
    fun `exact Android action follows the fixed three-mode matrix`() {
        val request = DeviceActionRequest(
            action = action,
            target = CapabilityTarget(targetKind, "fixture", allowedByAndroid = true),
            capabilityReady = true,
            deviceSessionActive = true,
            observationFresh = true,
        )
        assertEquals(requestApproval, DeviceActionPolicy.decide(TaskApprovalMode.REQUEST_APPROVAL, request).kind)
        assertEquals(autoApprove, DeviceActionPolicy.decide(TaskApprovalMode.AUTO_APPROVE, request).kind)
        assertEquals(fullAccess, DeviceActionPolicy.decide(TaskApprovalMode.FULL_ACCESS, request).kind)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}: request={2}, auto={3}, full={4}")
        fun rows(): List<Array<Any>> {
            val readOnly = setOf(
                CapabilityAction.READ_CAPABILITY_STATE,
                CapabilityAction.READ_SHARED_METADATA,
                CapabilityAction.INSPECT_UI_TREE,
                CapabilityAction.CAPTURE_SCREEN,
            )
            val lowRisk = setOf(
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
            val highRisk = setOf(
                CapabilityAction.OVERWRITE_FILE,
                CapabilityAction.DELETE_FILE,
                CapabilityAction.CHANGE_SETTING,
                CapabilityAction.SEND_MESSAGE,
                CapabilityAction.PUBLISH_CONTENT,
                CapabilityAction.SUBMIT_FORM,
            )
            val systemConfirm = setOf(
                CapabilityAction.INSTALL_APP,
                CapabilityAction.UNINSTALL_APP,
                CapabilityAction.DISABLE_APP,
            )
            val hardDeny = setOf(
                CapabilityAction.HANDLE_PASSWORD,
                CapabilityAction.HANDLE_OTP,
                CapabilityAction.PAYMENT_OR_TRANSFER,
                CapabilityAction.CHANGE_ACCOUNT_SECURITY,
                CapabilityAction.ANDROID_PERMISSION_FLOW,
                CapabilityAction.ACCESS_OTHER_APP_PRIVATE_DATA,
                CapabilityAction.EXECUTE_ARBITRARY_SHELL,
                CapabilityAction.USE_ROOT_BACKEND,
            )
            val classified = readOnly + lowRisk + highRisk + systemConfirm + hardDeny
            check(classified == CapabilityAction.entries.toSet())
            return buildList {
                readOnly.forEach { add(row(it, targetKindFor(it), auto, auto, auto)) }
                lowRisk.forEach { add(row(it, targetKindFor(it), prompt, auto, auto)) }
                highRisk.forEach { add(row(it, targetKindFor(it), prompt, prompt, auto)) }
                systemConfirm.forEach { add(row(it, targetKindFor(it), prompt, prompt, prompt)) }
                hardDeny.forEach { add(row(it, targetKindFor(it), deny, deny, deny)) }
            }
        }

        private fun row(
            action: CapabilityAction,
            targetKind: CapabilityTargetKind,
            request: PolicyDecisionKind,
            auto: PolicyDecisionKind,
            full: PolicyDecisionKind,
        ): Array<Any> = arrayOf(action, targetKind, request, auto, full)

        private fun targetKindFor(action: CapabilityAction): CapabilityTargetKind = when (action) {
            CapabilityAction.READ_CAPABILITY_STATE,
            CapabilityAction.EXECUTE_ARBITRARY_SHELL,
            CapabilityAction.USE_ROOT_BACKEND,
            -> CapabilityTargetKind.DEVICE
            CapabilityAction.READ_SHARED_METADATA,
            CapabilityAction.READ_SHARED_MEDIA_METADATA,
            CapabilityAction.READ_USER_FILE_CONTENT,
            CapabilityAction.CREATE_FILE,
            CapabilityAction.RENAME_FILE,
            CapabilityAction.WRITE_NEW_FILE,
            CapabilityAction.OVERWRITE_FILE,
            CapabilityAction.DELETE_FILE,
            -> CapabilityTargetKind.SHARED_FILE
            CapabilityAction.INSPECT_UI_TREE,
            CapabilityAction.CAPTURE_SCREEN,
            CapabilityAction.NAVIGATE_BACK,
            CapabilityAction.SCROLL,
            CapabilityAction.CLICK_NAVIGATION,
            CapabilityAction.INPUT_DRAFT,
            -> CapabilityTargetKind.APP_UI
            CapabilityAction.OPEN_ALLOWLIST_APP,
            CapabilityAction.INSTALL_APP,
            CapabilityAction.UNINSTALL_APP,
            CapabilityAction.DISABLE_APP,
            CapabilityAction.ACCESS_OTHER_APP_PRIVATE_DATA,
            -> CapabilityTargetKind.PACKAGE
            CapabilityAction.CHANGE_SETTING,
            CapabilityAction.CHANGE_ACCOUNT_SECURITY,
            CapabilityAction.ANDROID_PERMISSION_FLOW,
            -> CapabilityTargetKind.SYSTEM_SETTING
            CapabilityAction.SEND_MESSAGE,
            CapabilityAction.PUBLISH_CONTENT,
            CapabilityAction.SUBMIT_FORM,
            CapabilityAction.PAYMENT_OR_TRANSFER,
            -> CapabilityTargetKind.EXTERNAL_COMMUNICATION
            CapabilityAction.HANDLE_PASSWORD,
            CapabilityAction.HANDLE_OTP,
            -> CapabilityTargetKind.SENSITIVE_DATA
        }

        private val deny = PolicyDecisionKind.DENY
        private val prompt = PolicyDecisionKind.PROMPT_USER
        private val auto = PolicyDecisionKind.AUTO_ALLOW
    }
}
