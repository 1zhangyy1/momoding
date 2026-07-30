package app.momoding.core.runtime.local

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityRequestOutcome
import app.momoding.core.capabilities.AndroidCapabilityRequestResult
import app.momoding.core.capabilities.AndroidCapabilityRequirement
import app.momoding.core.capabilities.AndroidCapabilityRequester
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CalendarCapabilityAccess
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.ContactsCapabilityAccess
import app.momoding.core.capabilities.LocationCapabilityAccess
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
            var requested: CapabilityRequestRecord? = null
            var bound: Pair<String, String>? = null
            val executor = PhoneLocalCapabilityRequestToolExecutor(
                requester = AndroidCapabilityRequester {
                        taskId, capability, requirement, purpose ->
                    requested = CapabilityRequestRecord(
                        taskId,
                        capability,
                        requirement,
                        purpose,
                    )
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
                CapabilityRequestRecord(
                    "task-1",
                    AndroidCapabilityId.SAF_FOLDERS,
                    AndroidCapabilityRequirement.Default,
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
            requester = AndroidCapabilityRequester { _, capability, _, _ ->
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

    @Test
    fun `calendar read accepts partial access while write requests its exact requirement`() =
        runTest {
            val availability = initialAvailability().apply {
                this[AndroidCapabilityId.CALENDAR] = CapabilityAvailability.PARTIAL
            }
            val requirements = mutableListOf<AndroidCapabilityRequirement>()
            val executor = PhoneLocalCapabilityRequestToolExecutor(
                requester = AndroidCapabilityRequester { _, capability, requirement, _ ->
                    requirements += requirement
                    availability[capability] = CapabilityAvailability.READY
                    AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.READY)
                },
                registry = registry(availability, backgroundScope),
                folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
            )

            val read = executor.execute(
                taskId = "task-calendar",
                request = request(
                    capability = "calendar",
                    purpose = "Find meetings tomorrow",
                    requiredAccess = "read",
                ),
            )
            val write = executor.execute(
                taskId = "task-calendar",
                request = request(
                    capability = "calendar",
                    purpose = "Add a meeting",
                    requiredAccess = "write",
                ),
            )

            assertFalse(read.isError)
            assertEquals("false", read.contentPayload["requested"]?.jsonPrimitive?.content)
            assertFalse(write.isError)
            assertEquals(
                listOf(
                    AndroidCapabilityRequirement.Calendar(CalendarCapabilityAccess.WRITE),
                ),
                requirements,
            )
            assertEquals(
                "write",
                write.contentPayload["requiredAccess"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `calendar capability rejects missing or unknown access before Android UI`() = runTest {
        var requestCount = 0
        val executor = PhoneLocalCapabilityRequestToolExecutor(
            requester = AndroidCapabilityRequester { _, _, _, _ ->
                requestCount += 1
                AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.READY)
            },
            registry = registry(initialAvailability(), backgroundScope),
            folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
        )

        listOf(null, "admin").forEach { requiredAccess ->
            val failure = runCatching {
                executor.execute(
                    taskId = "task-calendar",
                    request = request(
                        capability = "calendar",
                        purpose = "Read calendar",
                        requiredAccess = requiredAccess,
                    ),
                )
            }.exceptionOrNull()
            assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
        }
        assertEquals(0, requestCount)
    }

    @Test
    fun `contacts read accepts partial access while write requests its exact requirement`() =
        runTest {
        val availability = initialAvailability().apply {
            this[AndroidCapabilityId.CONTACTS] = CapabilityAvailability.PARTIAL
        }
        val requirements = mutableListOf<AndroidCapabilityRequirement>()
        val executor = PhoneLocalCapabilityRequestToolExecutor(
            requester = AndroidCapabilityRequester { _, capability, requirement, _ ->
                requirements += requirement
                availability[capability] = CapabilityAvailability.READY
                AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.READY)
            },
            registry = registry(availability, backgroundScope),
            folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
        )

        val read = executor.execute(
            taskId = "task-contacts",
            request = request("contacts", "Find Alex", "read"),
        )
        val write = executor.execute(
            taskId = "task-contacts",
            request = request("contacts", "Update Alex", "write"),
        )

        assertFalse(read.isError)
        assertEquals("false", read.contentPayload["requested"]?.jsonPrimitive?.content)
        assertFalse(write.isError)
        assertEquals(
            listOf(
                AndroidCapabilityRequirement.Contacts(ContactsCapabilityAccess.WRITE),
            ),
            requirements,
        )
        assertEquals(
            "write",
            write.contentPayload["requiredAccess"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `location approximate accepts partial while precise requests exact access`() = runTest {
        val availability = initialAvailability().apply {
            this[AndroidCapabilityId.LOCATION] = CapabilityAvailability.PARTIAL
        }
        val requirements = mutableListOf<AndroidCapabilityRequirement>()
        val executor = PhoneLocalCapabilityRequestToolExecutor(
            requester = AndroidCapabilityRequester { _, capability, requirement, _ ->
                requirements += requirement
                availability[capability] = CapabilityAvailability.READY
                AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.READY)
            },
            registry = registry(availability, backgroundScope),
            folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
        )

        val approximate = executor.execute(
            "task-location",
            request("location", "Estimate where I am", "approximate"),
        )
        val precise = executor.execute(
            "task-location",
            request("location", "Use exact coordinates", "precise"),
        )

        assertFalse(approximate.isError)
        assertEquals("false", approximate.contentPayload["requested"]?.jsonPrimitive?.content)
        assertFalse(precise.isError)
        assertEquals(
            listOf(
                AndroidCapabilityRequirement.Location(LocationCapabilityAccess.PRECISE),
            ),
            requirements,
        )
        assertEquals(
            "precise",
            precise.contentPayload["requiredAccess"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `notification request uses the default typed capability flow`() = runTest {
        val availability = initialAvailability()
        var observedRequirement: AndroidCapabilityRequirement? = null
        val executor = PhoneLocalCapabilityRequestToolExecutor(
            requester = AndroidCapabilityRequester { _, capability, requirement, _ ->
                assertEquals(AndroidCapabilityId.NOTIFICATIONS, capability)
                observedRequirement = requirement
                availability[capability] = CapabilityAvailability.READY
                AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.READY)
            },
            registry = registry(availability, backgroundScope),
            folderGrantBinder = TaskFolderGrantBinder { _, _ -> false },
        )

        val result = executor.execute(
            "task-notification",
            request("notifications", "Post the notification I requested"),
        )

        assertFalse(result.isError)
        assertEquals(AndroidCapabilityRequirement.Default, observedRequirement)
        assertEquals(
            "notifications",
            result.contentPayload["capability"]?.jsonPrimitive?.content,
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
        requiredAccess: String? = null,
    ) = PiNativeToolRequest(
        id = "native-capability",
        kind = "android_capability_tool",
        toolCallId = "call-capability",
        toolName = "device_capability_request",
        arguments = buildJsonObject {
            put("capability", capability)
            requiredAccess?.let { put("requiredAccess", it) }
            put("purpose", purpose)
        },
    )

    private data class CapabilityRequestRecord(
        val taskId: String,
        val capability: AndroidCapabilityId,
        val requirement: AndroidCapabilityRequirement,
        val purpose: String,
    )
}
