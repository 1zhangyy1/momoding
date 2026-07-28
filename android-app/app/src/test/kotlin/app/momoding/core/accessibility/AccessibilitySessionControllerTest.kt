package app.momoding.core.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySessionControllerTest {
    @Test
    fun serviceLifecycleIsTheOnlySourceOfConnectedState() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()

        assertFalse(controller.state.value.connected)
        controller.onServiceConnected(service)
        controller.onContentChanged(service, "dev.fixture")
        assertTrue(controller.state.value.connected)
        assertEquals("dev.fixture", controller.state.value.foregroundPackage)

        controller.onServiceDisconnected(service)
        assertFalse(controller.state.value.connected)
        assertEquals(null, controller.state.value.foregroundPackage)
    }

    @Test
    fun contentChangeMakesAnInFlightSnapshotStale() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()
        controller.onServiceConnected(service)
        controller.onContentChanged(service, "dev.fixture")
        val token = requireNotNull(controller.currentTokenForTest())

        controller.onContentChanged(service, "dev.fixture")

        assertEquals(
            AccessibilityInspectionResult.Stale,
            controller.completeForTest(token, request(), snapshot()),
        )
    }

    @Test
    fun stopInvalidatesAnInFlightSnapshotWithoutDisablingAndroidAccess() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()
        controller.onServiceConnected(service)
        controller.onContentChanged(service, "dev.fixture")
        val token = requireNotNull(controller.currentTokenForTest())

        controller.stop()

        assertEquals(
            AccessibilityInspectionResult.Stopped,
            controller.completeForTest(token, request(), snapshot()),
        )
        assertTrue(controller.state.value.connected)
        assertEquals(null, controller.state.value.foregroundPackage)
    }

    @Test
    fun targetPackageChangeFailsClosed() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()
        controller.onServiceConnected(service)
        controller.onContentChanged(service, "dev.fixture")
        val token = requireNotNull(controller.currentTokenForTest())
        val targetRequest = request(targetPackage = "dev.fixture")

        controller.onContentChanged(service, "dev.other")

        assertEquals(
            AccessibilityInspectionResult.Stale,
            controller.completeForTest(token, targetRequest, snapshot()),
        )
    }

    @Test
    fun userTouchHasAnIndependentGenerationFromOrdinaryContentEvents() {
        val controller = AccessibilitySessionController()
        val service = MomodingAccessibilityService()
        controller.onServiceConnected(service)

        controller.onContentChanged(service, "dev.fixture")
        assertEquals(0, controller.state.value.userInteractionGeneration)

        controller.onContentChanged(service, "dev.fixture", userInteraction = true)
        assertEquals(1, controller.state.value.userInteractionGeneration)
        assertEquals("dev.fixture", controller.state.value.foregroundPackage)
    }

    private fun request(targetPackage: String? = null) = AccessibilityInspectionRequest(
        requestId = "request-1",
        targetPackage = targetPackage,
    )

    private fun snapshot() = AccessibilityUiSnapshot(
        snapshotId = "ui-test",
        observedAtMillis = 1,
        packageName = "dev.fixture",
        windows = emptyList(),
        nodes = emptyList(),
        truncated = false,
        redactedNodeCount = 0,
    )
}
