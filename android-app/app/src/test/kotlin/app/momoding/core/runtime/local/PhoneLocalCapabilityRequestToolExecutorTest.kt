package app.momoding.core.runtime.local

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityRequestOutcome
import app.momoding.core.capabilities.AndroidCapabilityRequestResult
import app.momoding.core.capabilities.AndroidCapabilityRequester
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalCapabilityRequestToolExecutorTest {
    @Test
    fun `SAF request binds the selected folder to the active task before returning ready`() =
        runTest {
            val availability = initialAvailability()
            var requested: Triple<String, AndroidCapabilityId, String>? = null
            var bound: Pair<String, String>? = null
            val executor = PhoneLocalCapabilityRequestToolExecutor(
                requester = AndroidCapabilityRequester { taskId, capability, purpose ->
                    requested = Triple(taskId, capability, purpose)
                    availability[capability] = CapabilityAvailability.READY
                    AndroidCapabilityRequestOutcome(
                        AndroidCapabilityRequestResult.READY,
                        grantId = "grant-project",
                    )
                },
                registry = registry(availability, backgroundScope),
                folderGrantBinder = TaskFolderGrantBinder { taskId, grantId ->
                    bound = taskId to grantId
                    true
                },
            )

            val result = executor.execute(
                taskId = "task-1",
                request = request("saf_folders", "Read this project"),
            )

            assertFalse(result.isError)
            assertEquals(
                Triple(
                    "task-1",
                    AndroidCapabilityId.SAF_FOLDERS,
                    "Read this project",
                ),
                requested,
            )
            assertEquals("task-1" to "grant-project", bound)
            assertEquals("true", result.contentPayload["ready"]?.jsonPrimitive?.content)
            assertEquals(
                "grant-project",
                result.contentPayload["grantId"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `ready non-SAF capability skips Android UI while denial remains explicit`() = runTest {
        val availability = initialAvailability().apply {
            this[AndroidCapabilityId.PHOTO_LIBRARY] = CapabilityAvailability.PARTIAL
        }
        var requestCount = 0
        val executor = PhoneLocalCapabilityRequestToolExecutor(
            requester = AndroidCapabilityRequester { _, capability, _ ->
                requestCount += 1
                AndroidCapabilityRequestOutcome(
                    if (capability == AndroidCapabilityId.ALL_FILES) {
                        AndroidCapabilityRequestResult.DENIED
                    } else {
                        AndroidCapabilityRequestResult.UNAVAILABLE
                    },
                )
            },
            registry = registry(availability, backgroundScope),
            folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
        )

        val alreadyReady = executor.execute(
            taskId = "task-1",
            request = request("photo_library", "Find screenshots"),
        )
        val denied = executor.execute(
            taskId = "task-1",
            request = request("all_files", "Inspect shared storage"),
        )

        assertFalse(alreadyReady.isError)
        assertEquals("false", alreadyReady.contentPayload["requested"]?.jsonPrimitive?.content)
        assertEquals(1, requestCount)
        assertTrue(denied.isError)
        assertEquals(
            "DEVICE_CAPABILITY_DENIED",
            denied.contentPayload["code"]?.jsonPrimitive?.content,
        )
    }

    private fun registry(
        availability: MutableMap<AndroidCapabilityId, CapabilityAvailability>,
        scope: CoroutineScope,
    ) = AndroidCapabilityRegistry(
        probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id = id,
                    availability = availability.getValue(id),
                    source = "test",
                    checkedAtMillis = checkedAt,
                    safeMessage = "test ${id.name.lowercase()} state",
                )
            }
        },
        scope = scope,
        nowMillis = { 42L },
    )

    private fun initialAvailability() = AndroidCapabilityId.entries.associateWith {
        CapabilityAvailability.NOT_GRANTED
    }.toMutableMap()

    private fun request(
        capability: String,
        purpose: String,
    ) = PiNativeToolRequest(
        id = "native-capability",
        kind = "android_capability_tool",
        toolCallId = "call-capability",
        toolName = "device_capability_request",
        arguments = buildJsonObject {
            put("capability", capability)
            put("purpose", purpose)
        },
    )
}
