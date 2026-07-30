package app.momoding.core.contacts

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalContactsMutationExecutorTest {
    @Test
    fun `create uses one immutable plan and returns a verified bounded contact`() = runTest {
        val gateway = MutableContactsGateway()
        val executor = executor(backgroundScope, gateway)
        val request = request("create-call", createArguments())
        val preparation = executor.prepareMutation(TASK_ID, request)

        assertTrue(preparation is ContactsMutationPreparation.Ready)
        val plan = (preparation as ContactsMutationPreparation.Ready).plan
        assertEquals(ContactsToolAction.CREATE_CONTACT, plan.action)
        assertFalse(plan.summary.contains("account-key"))

        val result = executor.executeMutation(TASK_ID, request, plan) {}

        assertFalse(result.isError)
        assertEquals(1, gateway.createCalls)
        assertEquals(
            "verified",
            result.contentPayload.getValue("verification").jsonObject
                .getValue("status").jsonPrimitive.content,
        )
        assertFalse(result.contentPayload.toString().contains("account-key"))
        assertFalse(result.contentPayload.toString().contains("rawContactId"))
    }

    @Test
    fun `update preserves unknown rows and rejects a changed live snapshot before dispatch`() =
        runTest {
            val gateway = MutableContactsGateway()
            val executor = executor(backgroundScope, gateway)
            val handle = searchHandle(executor)
            val request = request("update-call", updateArguments(handle))
            val plan = (
                executor.prepareMutation(TASK_ID, request) as
                    ContactsMutationPreparation.Ready
                ).plan
            val unknownDigest = requireNotNull(plan.rawContact).unknownRowsDigest

            val result = executor.executeMutation(TASK_ID, request, plan) {}

            assertFalse(result.isError)
            assertEquals(1, gateway.updateCalls)
            assertEquals(unknownDigest, gateway.raw?.unknownRowsDigest)
            assertEquals(setOf(ContactMutationField.EMAILS), gateway.lastUpdateFields)

            val conflictRequest = request("conflict-call", updateArguments(handle))
            val conflictPlan = (
                executor.prepareMutation(TASK_ID, conflictRequest) as
                    ContactsMutationPreparation.Ready
                ).plan
            gateway.mutateExternally()
            val beforeCalls = gateway.updateCalls

            val conflict = executor.executeMutation(
                TASK_ID,
                conflictRequest,
                conflictPlan,
            ) {}

            assertEquals("CONFLICT", conflict.errorCode())
            assertEquals(beforeCalls, gateway.updateCalls)
        }

    @Test
    fun `multi account delete is ambiguous and never removes another account record`() = runTest {
        val gateway = MutableContactsGateway().apply {
            secondRaw = raw!!.copy(
                rawContactId = 8,
                localDeviceAccount = false,
                accountKey = "second-account-key",
            )
        }
        val executor = executor(backgroundScope, gateway)
        val handle = searchHandle(executor)

        val preparation = executor.prepareMutation(
            TASK_ID,
            request("delete-call", deleteArguments(handle)),
        )

        assertEquals(
            "AMBIGUOUS_TARGET",
            (preparation as ContactsMutationPreparation.Failed).result.errorCode(),
        )
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `unknown MIME drift fails post verification and dispatched failures are outcome unknown`() =
        runTest {
            val verificationGateway = MutableContactsGateway().apply {
                corruptUnknownAfterUpdate = true
            }
            val verificationExecutor = executor(backgroundScope, verificationGateway)
            val handle = searchHandle(verificationExecutor)
            val request = request("verify-call", updateArguments(handle))
            val plan = (
                verificationExecutor.prepareMutation(TASK_ID, request) as
                    ContactsMutationPreparation.Ready
                ).plan

            val verification = verificationExecutor.executeMutation(
                TASK_ID,
                request,
                plan,
            ) {}

            assertEquals("VERIFICATION_FAILED", verification.errorCode())

            val uncertainGateway = MutableContactsGateway().apply {
                throwAfterCreate = true
            }
            val uncertainExecutor = executor(backgroundScope, uncertainGateway)
            val uncertainRequest = request("uncertain-call", createArguments())
            val uncertainPlan = (
                uncertainExecutor.prepareMutation(TASK_ID, uncertainRequest) as
                    ContactsMutationPreparation.Ready
                ).plan
            var dispatched = false

            val uncertain = uncertainExecutor.executeMutation(
                TASK_ID,
                uncertainRequest,
                uncertainPlan,
            ) { dispatched = true }

            assertTrue(dispatched)
            assertEquals("OUTCOME_UNKNOWN", uncertain.errorCode())
            assertEquals(1, uncertainGateway.createCalls)
        }

    @Test
    fun `write preparation requests exact access and direct mutation execution fails closed`() =
        runTest {
            val gateway = MutableContactsGateway()
            val readOnlyExecutor = PhoneLocalContactsToolExecutor(
                gateway = gateway,
                capabilityRegistry = registry(
                    CapabilityAvailability.PARTIAL,
                    backgroundScope,
                ),
            )
            val request = request("create-call", createArguments())

            val preparation = readOnlyExecutor.prepareMutation(TASK_ID, request)
            val direct = readOnlyExecutor.execute(TASK_ID, request)

            assertEquals(
                "CAPABILITY_NOT_READY",
                (preparation as ContactsMutationPreparation.Failed).result.errorCode(),
            )
            assertEquals(
                "write",
                preparation.result.contentPayload.getValue("error").jsonObject
                    .getValue("resolution").jsonObject
                    .getValue("requiredAccess").jsonPrimitive.content,
            )
            assertEquals("INVALID_ARGUMENTS", direct.errorCode())
            assertEquals(0, gateway.createCalls)
        }

    private fun executor(
        scope: CoroutineScope,
        gateway: ContactsGateway,
    ) = PhoneLocalContactsToolExecutor(
        gateway = gateway,
        capabilityRegistry = registry(CapabilityAvailability.READY, scope),
        now = { Instant.parse("2026-07-29T08:00:00.123456789Z") },
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
                    safeMessage = "test",
                )
            }
        },
        scope = scope,
    )

    private suspend fun searchHandle(executor: PhoneLocalContactsToolExecutor): String =
        executor.execute(
            TASK_ID,
            request(
                "search-call",
                buildJsonObject {
                    put("action", "search")
                    put("purpose", "Choose Alex")
                    put("query", "Alex")
                    put("cursor", JsonNull)
                },
            ),
        ).contentPayload.getValue("data").jsonObject
            .getValue("items").let { it as kotlinx.serialization.json.JsonArray }
            .single().jsonObject
            .getValue("contactHandle").jsonPrimitive.content

    private fun request(callId: String, arguments: JsonObject) = PiNativeToolRequest(
        id = "native-$callId",
        kind = "android_contacts_tool",
        toolCallId = callId,
        toolName = PhoneLocalContactsToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun createArguments() = buildJsonObject {
        put("action", "create_contact")
        put("purpose", "Create Alex")
        put("displayName", "Alex Chen")
        put("phones", buildJsonArray {
            add(contactValue("+8613800000000", "Mobile", true))
        })
        put("emails", buildJsonArray {
            add(contactValue("alex@example.test", "Work", true))
        })
        put(
            "organization",
            buildJsonObject {
                put("company", "Example")
                put("title", "Engineer")
            },
        )
    }

    private fun updateArguments(handle: String) = buildJsonObject {
        put("action", "update_contact")
        put("purpose", "Change Alex's email")
        put("contactHandle", handle)
        put(
            "changes",
            buildJsonObject {
                put("emails", buildJsonArray {
                    add(contactValue("alex@new.example", "Work", true))
                })
            },
        )
    }

    private fun deleteArguments(handle: String) = buildJsonObject {
        put("action", "delete_contact")
        put("purpose", "Delete duplicate Alex")
        put("contactHandle", handle)
    }

    private fun contactValue(
        value: String,
        label: String,
        primary: Boolean,
    ) = buildJsonObject {
        put("value", value)
        put("label", label)
        put("primary", primary)
    }

    private class MutableContactsGateway : ContactsGateway {
        private val target = ContactTarget(42, "private-lookup")
        var raw: ContactRawContactRecord? = rawRecord()
        var secondRaw: ContactRawContactRecord? = null
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var lastUpdateFields: Set<ContactMutationField>? = null
        var corruptUnknownAfterUpdate = false
        var throwAfterCreate = false

        override suspend fun search(
            query: String,
            offset: Int,
            limit: Int,
        ) = ContactPage(listOf(aggregate()), false, 1)

        override suspend fun getContact(target: ContactTarget): ContactRecord? =
            aggregate().takeIf { it.target == target && raw != null }

        override suspend fun getMutationSnapshot(target: ContactTarget): ContactMutationSnapshot? =
            getContact(target)?.let {
                ContactMutationSnapshot(
                    aggregate = it,
                    rawContacts = listOfNotNull(raw, secondRaw),
                )
            }

        override suspend fun getRawContact(rawContactId: Long): ContactRawContactRecord? =
            listOfNotNull(raw, secondRaw).firstOrNull { it.rawContactId == rawContactId }

        override suspend fun getContactByRawContact(rawContactId: Long): ContactRecord? =
            getRawContact(rawContactId)?.let { aggregate() }

        override suspend fun createContact(write: ContactWrite): Long {
            createCalls += 1
            raw = rawRecord(
                rawContactId = 99,
                contactId = 99,
                supported = write,
                unknownRowsDigest = "empty-unknown-digest",
            )
            if (throwAfterCreate) error("provider returned after applying")
            return 99
        }

        override suspend fun updateContact(
            rawContactId: Long,
            expectedVersion: Long,
            write: ContactWrite,
            fields: Set<ContactMutationField>,
        ): Boolean {
            updateCalls += 1
            lastUpdateFields = fields
            val before = raw ?: return false
            if (before.rawContactId != rawContactId || before.version != expectedVersion) {
                return false
            }
            raw = before.copy(
                version = before.version + 1,
                supported = write,
                unknownRowsDigest = if (corruptUnknownAfterUpdate) {
                    "corrupted-unknown-digest"
                } else {
                    before.unknownRowsDigest
                },
            )
            return true
        }

        override suspend fun deleteContact(
            rawContactId: Long,
            expectedVersion: Long,
        ): Boolean {
            deleteCalls += 1
            val before = raw ?: return false
            if (before.rawContactId != rawContactId || before.version != expectedVersion) {
                return false
            }
            raw = null
            return true
        }

        fun mutateExternally() {
            raw = requireNotNull(raw).copy(version = requireNotNull(raw).version + 1)
        }

        private fun aggregate(): ContactRecord {
            val supported = requireNotNull(raw).supported
            return ContactRecord(
                target = target,
                displayName = supported.displayName,
                phones = supported.phones,
                emails = supported.emails,
                organization = supported.organization,
            )
        }

        private fun rawRecord(
            rawContactId: Long = 7,
            contactId: Long = 42,
            supported: ContactWrite = ContactWrite(
                displayName = "Alex Chen",
                phones = listOf(ContactValue("+8613800000000", "Mobile", true)),
                emails = listOf(ContactValue("alex@example.test", "Work", true)),
                organization = ContactOrganization("Example", "Engineer"),
            ),
            unknownRowsDigest: String = "unknown-mime-digest",
        ) = ContactRawContactRecord(
            rawContactId = rawContactId,
            contactId = contactId,
            version = 1,
            localDeviceAccount = true,
            accountKey = "account-key",
            supported = supported,
            unknownRowsDigest = unknownRowsDigest,
        )
    }

    private fun PiNativeAndroidToolResult.errorCode(): String =
        contentPayload.getValue("error").jsonObject
            .getValue("code").jsonPrimitive.content

    private companion object {
        const val TASK_ID = "task-contacts-mutation"
    }
}
