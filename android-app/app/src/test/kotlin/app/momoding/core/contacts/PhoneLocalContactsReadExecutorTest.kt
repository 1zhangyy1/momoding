package app.momoding.core.contacts

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalContactsReadExecutorTest {
    @Test
    fun `missing read capability fails before provider access with one typed recovery`() = runTest {
        var calls = 0
        val executor = executor(
            availability = CapabilityAvailability.NOT_GRANTED,
            scope = backgroundScope,
            gateway = object : FakeContactsGateway() {
                override suspend fun search(query: String, offset: Int, limit: Int): ContactPage {
                    calls += 1
                    return ContactPage(emptyList(), false, 0)
                }
            },
        )

        val result = executor.execute(TASK_ID, request(searchArguments()))

        assertTrue(result.isError)
        val error = result.contentPayload.getValue("error").jsonObject
        assertEquals("CAPABILITY_NOT_READY", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            "contacts",
            error.getValue("resolution").jsonObject
                .getValue("capability").jsonPrimitive.content,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `search is bounded redacted and provider pagination advances by consumed rows`() = runTest {
        val gateway = RecordingContactsGateway()
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            scope = backgroundScope,
            gateway = gateway,
        )

        val first = executor.execute(TASK_ID, request(searchArguments()))
        assertFalse(first.isError)
        val firstItems = first.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray
        assertEquals(1, firstItems.size)
        val summary = firstItems.single().jsonObject
        val contactHandle = summary.getValue("contactHandle").jsonPrimitive.content
        assertTrue(contactHandle.matches(Regex("^contact-[0-9a-f]{24}$")))
        assertFalse(first.contentPayload.toString().contains("private-lookup-key"))
        assertFalse(first.contentPayload.toString().contains("\"contactId\""))
        assertEquals(
            "observed",
            first.contentPayload.getValue("verification").jsonObject
                .getValue("status").jsonPrimitive.content,
        )
        val cursor = first.contentPayload.getValue("page").jsonObject
            .getValue("nextCursor").jsonPrimitive.content

        val second = executor.execute(TASK_ID, request(searchArguments(cursor)))

        assertFalse(second.isError)
        assertEquals(listOf(0, 10), gateway.offsets)
        assertEquals(10, gateway.limits.single())
    }

    @Test
    fun `details reuse a task handle across turns but stop invalidates it`() = runTest {
        val gateway = RecordingContactsGateway()
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            scope = backgroundScope,
            gateway = gateway,
        )
        val listed = executor.execute(TASK_ID, request(searchArguments()))
        val handle = listed.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray.single().jsonObject
            .getValue("contactHandle").jsonPrimitive.content

        val crossTask = executor.execute("other-task", request(getArguments(handle)))
        assertEquals(
            "STALE_HANDLE",
            crossTask.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )

        executor.stopTask(TASK_ID, ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON)
        val details = executor.execute(TASK_ID, request(getArguments(handle)))
        assertFalse(details.isError)
        val contact = details.contentPayload.getValue("data").jsonObject
            .getValue("contact").jsonObject
        assertEquals("Alex Chen", contact.getValue("displayName").jsonPrimitive.content)
        assertEquals(2, contact.getValue("phones").jsonArray.size)
        assertEquals(1, contact.getValue("emails").jsonArray.size)

        executor.stopTask(TASK_ID, "session_stop")
        val afterStop = executor.execute(TASK_ID, request(getArguments(handle)))
        assertEquals(
            "STALE_HANDLE",
            afterStop.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun `search and details stay inside the frozen UTF-8 result budget`() = runTest {
        val hostile = (1..10).map { index ->
            ContactRecord(
                target = ContactTarget(index.toLong(), "lookup-$index"),
                displayName = "😀\\\"".repeat(200),
                phones = List(10) {
                    ContactValue("☎\\\"".repeat(128), "📱\\\"".repeat(64), it == 0)
                },
                emails = List(10) {
                    ContactValue("邮\\\"".repeat(320), "工\\\"".repeat(64), it == 0)
                },
                organization = ContactOrganization(
                    company = "公\\\"".repeat(256),
                    title = "职\\\"".repeat(160),
                ),
            )
        }
        val gateway = object : FakeContactsGateway() {
            override suspend fun search(query: String, offset: Int, limit: Int) =
                ContactPage(hostile, false, hostile.size)

            override suspend fun getContact(target: ContactTarget): ContactRecord? =
                hostile.firstOrNull { it.target == target }
        }
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            scope = backgroundScope,
            gateway = gateway,
        )

        val search = executor.execute(TASK_ID, request(searchArguments()))
        val searchBytes = search.contentPayload.toString().encodeToByteArray().size
        assertFalse(search.isError)
        assertTrue(searchBytes <= PhoneLocalContactsToolExecutor.MAX_RESULT_JSON_BYTES)
        val firstSummary = search.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray.first().jsonObject
        assertFalse(firstSummary.containsKey("primaryPhone"))
        assertFalse(firstSummary.containsKey("primaryEmail"))
        assertFalse(firstSummary.containsKey("organization"))

        val handle = firstSummary.getValue("contactHandle").jsonPrimitive.content
        val details = executor.execute(TASK_ID, request(getArguments(handle)))
        val detailBytes = details.contentPayload.toString().encodeToByteArray().size
        assertFalse(details.isError)
        assertTrue(detailBytes <= PhoneLocalContactsToolExecutor.MAX_RESULT_JSON_BYTES)
        assertTrue(
            details.contentPayload.getValue("data").jsonObject
                .getValue("contact").jsonObject
                .getValue("truncated").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `provider timeout is compact and retryable`() = runTest {
        val executor = PhoneLocalContactsToolExecutor(
            gateway = object : FakeContactsGateway() {
                override suspend fun search(query: String, offset: Int, limit: Int): ContactPage {
                    delay(Long.MAX_VALUE)
                    return ContactPage(emptyList(), false, 0)
                }
            },
            capabilityRegistry = registry(CapabilityAvailability.PARTIAL, backgroundScope),
            timeoutMillis = 1,
        )

        val result = executor.execute(TASK_ID, request(searchArguments()))

        val error = result.contentPayload.getValue("error").jsonObject
        assertEquals("DEVICE_TOOL_TIMEOUT", error.getValue("code").jsonPrimitive.content)
        assertEquals("true", error.getValue("retryable").jsonPrimitive.content)
    }

    private fun executor(
        availability: CapabilityAvailability,
        scope: CoroutineScope,
        gateway: ContactsGateway,
    ) = PhoneLocalContactsToolExecutor(
        gateway = gateway,
        capabilityRegistry = registry(availability, scope),
        now = { Instant.parse("2026-07-29T08:00:00Z") },
    )

    private fun registry(
        contactsAvailability: CapabilityAvailability,
        scope: CoroutineScope,
    ) = AndroidCapabilityRegistry(
        probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id = id,
                    availability = if (id == AndroidCapabilityId.CONTACTS) {
                        contactsAvailability
                    } else {
                        CapabilityAvailability.NOT_GRANTED
                    },
                    source = "test",
                    checkedAtMillis = checkedAt,
                    safeMessage = "test ${id.name.lowercase()} state",
                )
            }
        },
        scope = scope,
        nowMillis = { 42L },
    )

    private fun request(arguments: kotlinx.serialization.json.JsonObject) = PiNativeToolRequest(
        id = "native-${System.nanoTime()}",
        kind = "android_contacts_tool",
        toolCallId = "pi-${System.nanoTime()}",
        toolName = PhoneLocalContactsToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun searchArguments(cursor: String? = null) = buildJsonObject {
        put("action", "search")
        put("purpose", "Find Alex")
        put("query", "Alex")
        if (cursor == null) put("cursor", JsonNull) else put("cursor", cursor)
    }

    private fun getArguments(handle: String) = buildJsonObject {
        put("action", "get_contact")
        put("purpose", "Read the selected contact")
        put("contactHandle", handle)
    }

    private open class FakeContactsGateway : ContactsGateway {
        override suspend fun search(query: String, offset: Int, limit: Int): ContactPage =
            ContactPage(emptyList(), false, 0)

        override suspend fun getContact(target: ContactTarget): ContactRecord? = null
    }

    private class RecordingContactsGateway : FakeContactsGateway() {
        val offsets = mutableListOf<Int>()
        val limits = mutableSetOf<Int>()

        override suspend fun search(query: String, offset: Int, limit: Int): ContactPage {
            offsets += offset
            limits += limit
            return ContactPage(
                items = if (offset == 0) listOf(contact()) else emptyList(),
                hasMore = offset == 0,
                consumedCount = if (offset == 0) 10 else 0,
            )
        }

        override suspend fun getContact(target: ContactTarget): ContactRecord? =
            contact().takeIf { it.target == target }

        private fun contact() = ContactRecord(
            target = ContactTarget(42, "private-lookup-key"),
            displayName = "Alex Chen",
            phones = listOf(
                ContactValue("+8613800000000", "Mobile", true),
                ContactValue("01088888888", "Work", false),
            ),
            emails = listOf(ContactValue("alex@example.test", "Work", true)),
            organization = ContactOrganization("Example", "Engineer"),
        )
    }

    private companion object {
        const val TASK_ID = "task-contacts"
    }
}
