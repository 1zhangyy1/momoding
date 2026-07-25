package app.momoding.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionPolicyGuardTest {
    @Test
    fun `durable codec accepts exact values and unknown values fail closed`() {
        TaskApprovalMode.entries.forEach { mode ->
            assertEquals(mode, TaskApprovalMode.requirePersisted(mode.persistedValue))
            assertEquals(mode, TaskApprovalMode.fromPersistedFailClosed(mode.persistedValue))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskApprovalMode.requirePersisted("full_access")
        }
        assertEquals(
            TaskApprovalMode.REQUEST_APPROVAL,
            TaskApprovalMode.fromPersistedFailClosed("FUTURE_UNRECOGNIZED_MODE"),
        )
    }

    @Test
    fun `stop capability loss target denial takeover and stale observation fail closed in every mode`() {
        TaskApprovalMode.entries.forEach { mode ->
            assertDenied(mode, lowRiskRequest().copy(executionState = DeviceExecutionState.STOP_REQUESTED))
            assertDenied(mode, lowRiskRequest().copy(executionState = DeviceExecutionState.USER_TAKEOVER))
            assertDenied(mode, lowRiskRequest().copy(capabilityReady = false))
            assertDenied(
                mode,
                lowRiskRequest().copy(target = lowRiskRequest().target.copy(allowedByAndroid = false)),
            )
            assertDenied(mode, lowRiskRequest().copy(deviceSessionActive = false))
            assertDenied(mode, lowRiskRequest().copy(observationFresh = false))
        }
    }

    @Test
    fun `an existing visible approval never becomes automatic after a mode switch`() {
        val pending = lowRiskRequest().copy(approvalAlreadyPending = true)
        TaskApprovalMode.entries.forEach { mode ->
            assertEquals(PolicyDecisionKind.PROMPT_USER, DeviceActionPolicy.decide(mode, pending).kind)
            assertEquals(
                PolicyDecisionReason.EXISTING_PENDING_APPROVAL,
                DeviceActionPolicy.decide(mode, pending).reason,
            )
        }
    }

    @Test
    fun `capability state remains queryable when the reported capability is not ready`() {
        val query = DeviceActionRequest(
            action = CapabilityAction.READ_CAPABILITY_STATE,
            target = CapabilityTarget(CapabilityTargetKind.DEVICE, "registry", allowedByAndroid = true),
            capabilityReady = false,
        )
        TaskApprovalMode.entries.forEach { mode ->
            val decision = DeviceActionPolicy.decide(mode, query)
            assertEquals(PolicyDecisionKind.AUTO_ALLOW, decision.kind)
            assertEquals(PolicyDecisionReason.READ_ONLY_OBSERVATION, decision.reason)
        }
    }

    @Test
    fun `every action rejects every incompatible Android target kind before mode evaluation`() {
        CapabilityAction.entries.forEach { action ->
            val incompatibleKinds = CapabilityTargetKind.entries.toSet() -
                DeviceActionPolicy.allowedTargetKinds(action)
            assertTrue("$action must reject at least one incompatible target kind", incompatibleKinds.isNotEmpty())
            incompatibleKinds.forEach { targetKind ->
                val request = DeviceActionRequest(
                    action = action,
                    target = CapabilityTarget(targetKind, "mismatched-fixture", allowedByAndroid = true),
                    capabilityReady = true,
                    deviceSessionActive = true,
                    observationFresh = true,
                )
                TaskApprovalMode.entries.forEach { mode ->
                    val decision = DeviceActionPolicy.decide(mode, request)
                    assertEquals(PolicyDecisionKind.DENY, decision.kind)
                    assertEquals(
                        if (action in hardDeniedActions) {
                            PolicyDecisionReason.HARD_BOUNDARY
                        } else {
                            PolicyDecisionReason.TARGET_KIND_MISMATCH
                        },
                        decision.reason,
                    )
                }
            }
        }
    }

    @Test
    fun `hard boundaries beat a pending approval in all modes`() {
        val permissionFlow = lowRiskRequest().copy(
            action = CapabilityAction.ANDROID_PERMISSION_FLOW,
            approvalAlreadyPending = true,
        )
        TaskApprovalMode.entries.forEach { mode ->
            val decision = DeviceActionPolicy.decide(mode, permissionFlow)
            assertEquals(PolicyDecisionKind.DENY, decision.kind)
            assertEquals(PolicyDecisionReason.HARD_BOUNDARY, decision.reason)
        }
    }

    @Test
    fun `mode downgrade invalidates auto receipts and child cannot elevate`() {
        assertTrue(
            transitionTaskApprovalMode(
                TaskApprovalMode.FULL_ACCESS,
                TaskApprovalMode.AUTO_APPROVE,
            ).invalidateUnusedAutoApprovalReceipts,
        )
        assertTrue(
            transitionTaskApprovalMode(
                TaskApprovalMode.AUTO_APPROVE,
                TaskApprovalMode.REQUEST_APPROVAL,
            ).invalidateUnusedAutoApprovalReceipts,
        )
        assertFalse(
            transitionTaskApprovalMode(
                TaskApprovalMode.REQUEST_APPROVAL,
                TaskApprovalMode.FULL_ACCESS,
            ).invalidateUnusedAutoApprovalReceipts,
        )
        assertEquals(
            TaskApprovalMode.REQUEST_APPROVAL,
            effectiveChildApprovalMode(TaskApprovalMode.REQUEST_APPROVAL, TaskApprovalMode.FULL_ACCESS),
        )
        assertEquals(
            TaskApprovalMode.AUTO_APPROVE,
            effectiveChildApprovalMode(TaskApprovalMode.FULL_ACCESS, TaskApprovalMode.AUTO_APPROVE),
        )
    }

    @Test
    fun `approval mode cannot filter implemented tools or granted capability states`() {
        val tools = linkedSetOf("device_capabilities_get", "device_ui_inspect", "device_files_read")
        val capabilities = linkedMapOf("saf" to "READY", "accessibility" to "NOT_GRANTED")
        val scopes = TaskApprovalMode.entries.map { mode -> TaskApprovalScope(mode, tools, capabilities) }

        scopes.forEach { scope ->
            assertEquals(tools, scope.implementedToolNames)
            assertEquals(capabilities, scope.grantedCapabilityStates)
        }
        assertEquals(1, scopes.map(TaskApprovalScope::implementedToolNames).distinct().size)
        assertEquals(1, scopes.map(TaskApprovalScope::grantedCapabilityStates).distinct().size)
        assertNotSame(tools, scopes.first().implementedToolNames)
        assertNotSame(capabilities, scopes.first().grantedCapabilityStates)
    }

    private fun assertDenied(mode: TaskApprovalMode, request: DeviceActionRequest) {
        assertEquals(PolicyDecisionKind.DENY, DeviceActionPolicy.decide(mode, request).kind)
    }

    private fun lowRiskRequest() = DeviceActionRequest(
        action = CapabilityAction.CLICK_NAVIGATION,
        target = CapabilityTarget(CapabilityTargetKind.APP_UI, "node-1", allowedByAndroid = true),
        capabilityReady = true,
        executionState = DeviceExecutionState.ACTIVE,
        deviceSessionActive = true,
        observationFresh = true,
    )

    private val hardDeniedActions = setOf(
        CapabilityAction.HANDLE_PASSWORD,
        CapabilityAction.HANDLE_OTP,
        CapabilityAction.PAYMENT_OR_TRANSFER,
        CapabilityAction.CHANGE_ACCOUNT_SECURITY,
        CapabilityAction.ANDROID_PERMISSION_FLOW,
        CapabilityAction.ACCESS_OTHER_APP_PRIVATE_DATA,
        CapabilityAction.EXECUTE_ARBITRARY_SHELL,
        CapabilityAction.USE_ROOT_BACKEND,
    )
}
