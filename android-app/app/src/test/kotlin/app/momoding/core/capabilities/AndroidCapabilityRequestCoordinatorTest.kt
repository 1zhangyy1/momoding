package app.momoding.core.capabilities

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCapabilityRequestCoordinatorTest {
    @Test
    fun `request publishes task capability and purpose then returns the matching outcome`() =
        runTest {
            val coordinator = AndroidCapabilityRequestCoordinator(
                timeoutMillis = 5_000,
                requestIdFactory = { "capability-request" },
            )

            val result = async {
                coordinator.request(
                    taskId = "task-1",
                    capability = AndroidCapabilityId.SAF_FOLDERS,
                    purpose = "Read the project selected for this task",
                )
            }
            runCurrent()

            val pending = requireNotNull(coordinator.pending.value)
            assertEquals("capability-request", pending.requestId)
            assertEquals("task-1", pending.taskId)
            assertEquals(AndroidCapabilityId.SAF_FOLDERS, pending.capability)
            assertEquals(
                "Read the project selected for this task",
                pending.purpose,
            )

            coordinator.respond(
                requestId = pending.requestId,
                result = AndroidCapabilityRequestResult.READY,
                grantId = "grant-project",
            )

            assertEquals(
                AndroidCapabilityRequestOutcome(
                    AndroidCapabilityRequestResult.READY,
                    "grant-project",
                ),
                result.await(),
            )
            assertNull(coordinator.pending.value)
        }

    @Test
    fun `different response cannot resolve the current capability request`() = runTest {
        val coordinator = AndroidCapabilityRequestCoordinator(
            timeoutMillis = 100,
            requestIdFactory = { "expected" },
        )

        val result = async {
            coordinator.request(
                taskId = "task-1",
                capability = AndroidCapabilityId.PHOTO_LIBRARY,
                purpose = "Find recent screenshots",
            )
        }
        runCurrent()
        coordinator.respond("different", AndroidCapabilityRequestResult.READY)

        assertEquals(AndroidCapabilityRequestResult.TIMEOUT, result.await().result)
        assertNull(coordinator.pending.value)
    }
}
