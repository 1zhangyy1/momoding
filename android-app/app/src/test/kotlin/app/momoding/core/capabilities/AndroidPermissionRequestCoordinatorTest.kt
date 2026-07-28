package app.momoding.core.capabilities

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPermissionRequestCoordinatorTest {
    @Test
    fun `request publishes normalized permissions and resumes with the matching decision`() = runTest {
        val coordinator = AndroidPermissionRequestCoordinator(
            timeoutMillis = 5_000,
            requestIdFactory = { "permission-request" },
        )

        val result = async {
            coordinator.request(
                listOf(
                    " android.permission.READ_MEDIA_IMAGES ",
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                ),
            )
        }
        runCurrent()

        val pending = requireNotNull(coordinator.pending.value)
        assertEquals("permission-request", pending.requestId)
        assertEquals(
            listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ),
            pending.permissions,
        )

        coordinator.respond(pending.requestId, AndroidPermissionRequestResult.GRANTED)

        assertEquals(AndroidPermissionRequestResult.GRANTED, result.await())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `response for a different request cannot resolve the pending request`() = runTest {
        val coordinator = AndroidPermissionRequestCoordinator(
            timeoutMillis = 100,
            requestIdFactory = { "expected" },
        )

        val result = async {
            coordinator.request(listOf("android.permission.READ_MEDIA_IMAGES"))
        }
        runCurrent()
        coordinator.respond("different", AndroidPermissionRequestResult.GRANTED)

        assertEquals(AndroidPermissionRequestResult.TIMEOUT, result.await())
        assertNull(coordinator.pending.value)
    }
}
