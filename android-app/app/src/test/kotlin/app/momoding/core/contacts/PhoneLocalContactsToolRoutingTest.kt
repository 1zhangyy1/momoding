package app.momoding.core.contacts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import app.momoding.core.transport.AttentionUserDecision
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalContactsToolRoutingTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.momodingDao().upsertTask(task())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `request approval redacts the query and executes only after confirmation`() = runTest {
        val ledger = RoomAttentionLedger(database)
        val handler = FixtureContactsHandler()
        val bridge = bridge(ledger, handler, TaskApprovalMode.REQUEST_APPROVAL)
        val request = request(query = "PRIVATE_CONTACT_QUERY")

        assertNull(bridge.handleNativeRequest(TASK_ID, request))
        val pending = ledger.unterminatedRecords().single()
        assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
        assertFalse(pending.operation.argumentsCanonicalJson.contains("PRIVATE_CONTACT_QUERY"))
        assertEquals(0, handler.executionCount)

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        assertEquals(1, handler.executionCount)
        assertEquals(request, handler.request)
        assertTrue(ledger.record(pending.operation.callId)?.operation?.terminalSha256 == null)
        ledger.discardLiveApprovedRead(TASK_ID, pending.operation.callId)
        assertNull(ledger.record(pending.operation.callId))
    }

    @Test
    fun `automatic modes read immediately while decline and missing permission never read`() =
        runTest {
            listOf(TaskApprovalMode.AUTO_APPROVE, TaskApprovalMode.FULL_ACCESS).forEach { mode ->
                val handler = FixtureContactsHandler()
                val result = bridge(RoomAttentionLedger(database), handler, mode)
                    .handleNativeRequest(TASK_ID, request(id = "native-$mode"))
                assertEquals(false, result?.isError)
                assertEquals(1, handler.executionCount)
            }

            val declineLedger = RoomAttentionLedger(database)
            val declineHandler = FixtureContactsHandler()
            val declineBridge = bridge(
                declineLedger,
                declineHandler,
                TaskApprovalMode.REQUEST_APPROVAL,
            )
            assertNull(declineBridge.handleNativeRequest(TASK_ID, request(id = "native-decline")))
            val pending = declineLedger.unterminatedRecords().single()
            declineBridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))
            assertEquals(0, declineHandler.executionCount)
            assertEquals(
                "rejected",
                declineLedger.record(pending.operation.callId)?.operation?.terminalKind,
            )

            val unreadyHandler = FixtureContactsHandler().apply { readCapabilityReady = false }
            val unreadyLedger = RoomAttentionLedger(database)
            val unready = bridge(
                unreadyLedger,
                unreadyHandler,
                TaskApprovalMode.REQUEST_APPROVAL,
            ).handleNativeRequest(TASK_ID, request(id = "native-unready"))
            assertEquals(1, unreadyHandler.executionCount)
            assertEquals(false, unready?.isError)
            assertTrue(unreadyLedger.unterminatedRecords().isEmpty())
        }

    @Test
    fun `contact create waits for approval executes once and replays compacted terminal`() =
        runTest {
            val ledger = RoomAttentionLedger(database)
            val handler = FixtureContactsHandler()
            val bridge = bridge(ledger, handler, TaskApprovalMode.REQUEST_APPROVAL)
            val request = mutationRequest(
                id = "native-create",
                toolCallId = "pi-contact-create",
            )

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
            assertEquals(1, handler.preparationCount)
            assertEquals(0, handler.mutationExecutionCount)
            assertFalse(pending.operation.argumentsCanonicalJson.contains("PRIVATE_PHONE"))

            bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

            assertEquals(1, handler.mutationExecutionCount)
            val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
            assertEquals(AttentionLedgerState.TERMINAL.name, terminal.ledgerState)
            val expectation = requireNotNull(
                ledger.piDeliveryExpectationForValidatedPair(terminal),
            )
            ledger.markPiDeliveredAfterVerifiedProof(
                taskId = TASK_ID,
                callId = terminal.callId,
                terminalSemanticSha256 = expectation.terminalSemanticSha256,
            )
            val compacted = requireNotNull(ledger.record(terminal.callId)).operation
            assertFalse(compacted.argumentsCanonicalJson.contains("PRIVATE_PHONE"))
            assertFalse(compacted.terminalFrameCanonicalJson.orEmpty().contains("PRIVATE_PHONE"))
            assertTrue(compacted.terminalFrameCanonicalJson.orEmpty().contains("\"redacted\":true"))

            val replay = bridge.handleNativeRequest(
                TASK_ID,
                mutationRequest(
                    id = "native-create-replay",
                    toolCallId = "pi-contact-create",
                ),
            )

            assertEquals(false, replay?.isError)
            assertEquals(
                "create_contact",
                replay?.contentPayload?.get("action")?.jsonPrimitive?.content,
            )
            assertEquals(1, handler.preparationCount)
            assertEquals(1, handler.mutationExecutionCount)
        }

    @Test
    fun `automatic contact writes skip visible prompt while delete always asks`() = runTest {
        listOf(
            TaskApprovalMode.AUTO_APPROVE to "create_contact",
            TaskApprovalMode.FULL_ACCESS to "update_contact",
        ).forEachIndexed { index, (mode, action) ->
            val scopedDatabase = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                MomodingDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                scopedDatabase.momodingDao().upsertTask(task())
                val handler = FixtureContactsHandler()
                val ledger = RoomAttentionLedger(scopedDatabase)
                val result = bridge(ledger, handler, mode).handleNativeRequest(
                    TASK_ID,
                    mutationRequest(
                        id = "native-auto-$index",
                        toolCallId = "pi-contact-auto-$index",
                        action = action,
                    ),
                )

                assertEquals(false, result?.isError)
                assertEquals(
                    "auto_policy",
                    result?.details?.get("approvalOrigin")?.jsonPrimitive?.content,
                )
                assertEquals(1, handler.mutationExecutionCount)
                assertTrue(ledger.unterminatedRecords().isEmpty())
            } finally {
                scopedDatabase.close()
            }
        }

        val deleteHandler = FixtureContactsHandler()
        val deleteLedger = RoomAttentionLedger(database)
        val deleteBridge = bridge(
            deleteLedger,
            deleteHandler,
            TaskApprovalMode.FULL_ACCESS,
        )
        assertNull(
            deleteBridge.handleNativeRequest(
                TASK_ID,
                mutationRequest(
                    id = "native-delete",
                    toolCallId = "pi-contact-delete",
                    action = "delete_contact",
                ),
            ),
        )
        val pending = deleteLedger.unterminatedRecords().single()
        assertEquals(0, deleteHandler.mutationExecutionCount)
        deleteBridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))
        assertEquals(0, deleteHandler.mutationExecutionCount)
        assertEquals(
            "rejected",
            deleteLedger.record(pending.operation.callId)?.operation?.terminalKind,
        )
    }

    @Test
    fun `duplicate conflicts and dispatched runtime recovery never repeat a contact write`() =
        runTest {
            val ledger = RoomAttentionLedger(database)
            val handler = FixtureContactsHandler()
            val bridge = bridge(ledger, handler, TaskApprovalMode.REQUEST_APPROVAL)
            assertNull(
                bridge.handleNativeRequest(
                    TASK_ID,
                    mutationRequest("native-first", "pi-stable"),
                ),
            )

            val conflict = bridge.handleNativeRequest(
                TASK_ID,
                mutationRequest(
                    id = "native-conflict",
                    toolCallId = "pi-stable",
                    displayName = "Different contact",
                ),
            )
            assertEquals(true, conflict?.isError)
            assertEquals(
                "CONTACTS_DUPLICATE_CONFLICT",
                conflict?.contentPayload?.get("error")?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(1, handler.preparationCount)

            val pending = ledger.unterminatedRecords().single()
            val durableArguments = Json.parseToJsonElement(
                pending.operation.argumentsCanonicalJson,
            ).jsonObject
            ledger.markContactsMutationDispatched(
                pending.operation.callId,
                durableArguments.getValue("planDigest").jsonPrimitive.content,
            )
            val rebuilt = bridge(
                ledger,
                handler,
                TaskApprovalMode.REQUEST_APPROVAL,
            )
            rebuilt.recoverDestroyedRuntime()

            val recovered = requireNotNull(ledger.record(pending.operation.callId))
            assertEquals(AttentionLedgerState.FAILED_CLOSED.name, recovered.operation.ledgerState)
            assertTrue(
                recovered.operation.terminalFrameCanonicalJson
                    ?.contains("\"code\":\"OUTCOME_UNKNOWN\"") == true,
            )
            val replay = rebuilt.handleNativeRequest(
                TASK_ID,
                mutationRequest("native-replay", "pi-stable"),
            )
            assertEquals(true, replay?.isError)
            assertEquals(
                "OUTCOME_UNKNOWN",
                replay?.contentPayload?.get("error")?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(1, handler.preparationCount)
            assertEquals(0, handler.mutationExecutionCount)
        }

    private fun bridge(
        ledger: RoomAttentionLedger,
        handler: PhoneLocalContactsToolHandler,
        mode: TaskApprovalMode,
    ) = PhoneLocalAttentionBridge(
        ledger = ledger,
        contactsTools = handler,
        approvalModeForTask = { mode },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        id: String = "native-contacts",
        query: String = "Alex",
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_contacts_tool",
        toolCallId = "pi-$id",
        toolName = PhoneLocalContactsToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", "search")
            put("purpose", "Find Alex")
            put("query", query)
            put("cursor", JsonNull)
        },
    )

    private fun mutationRequest(
        id: String,
        toolCallId: String,
        action: String = "create_contact",
        displayName: String = "Private Alex",
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_contacts_tool",
        toolCallId = toolCallId,
        toolName = PhoneLocalContactsToolExecutor.TOOL_NAME,
        arguments = when (action) {
            "create_contact" -> buildJsonObject {
                put("action", action)
                put("purpose", "Create a contact")
                put("displayName", displayName)
                put(
                    "phones",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("value", "PRIVATE_PHONE")
                                put("label", "Mobile")
                                put("primary", true)
                            },
                        )
                    },
                )
                put("emails", kotlinx.serialization.json.buildJsonArray {})
                put("organization", JsonNull)
            }
            "update_contact" -> buildJsonObject {
                put("action", action)
                put("purpose", "Rename the contact")
                put("contactHandle", "contact-${"1".repeat(24)}")
                put("changes", buildJsonObject { put("displayName", displayName) })
            }
            "delete_contact" -> buildJsonObject {
                put("action", action)
                put("purpose", "Delete the contact")
                put("contactHandle", "contact-${"1".repeat(24)}")
            }
            else -> error("Unsupported Contacts fixture action")
        },
    )

    private class FixtureContactsHandler : PhoneLocalContactsToolHandler {
        var executionCount = 0
        var preparationCount = 0
        var mutationExecutionCount = 0
        var readCapabilityReady = true
        var request: PiNativeToolRequest? = null

        override fun handles(toolName: String): Boolean =
            toolName == PhoneLocalContactsToolExecutor.TOOL_NAME

        override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean =
            request.arguments["action"]?.jsonPrimitive?.content in setOf("search", "get_contact")

        override fun isMutation(request: PiNativeToolRequest): Boolean =
            request.arguments["action"]?.jsonPrimitive?.content in
                setOf("create_contact", "update_contact", "delete_contact")

        override fun mutationRequestDigest(request: PiNativeToolRequest): String =
            sha256(request.arguments.toString())

        override suspend fun isReadCapabilityReady(): Boolean = readCapabilityReady

        override suspend fun prepareMutation(
            taskId: String,
            request: PiNativeToolRequest,
        ): ContactsMutationPreparation {
            preparationCount += 1
            val action = requireNotNull(
                ContactsToolAction.fromWireValue(
                    request.arguments.getValue("action").jsonPrimitive.content,
                ),
            )
            val requestDigest = mutationRequestDigest(request)
            return ContactsMutationPreparation.Ready(
                ContactsMutationPlan(
                    taskId = taskId,
                    piToolCallId = request.toolCallId,
                    action = action,
                    requestDigest = requestDigest,
                    snapshotDigest = "2".repeat(64),
                    planDigest = sha256("$taskId:${request.toolCallId}:$requestDigest"),
                    summary = "${action.wireValue} one contact?",
                    details = "Apply one bounded change and verify live Contacts state.",
                    before = null,
                    rawContact = null,
                    write = null,
                    fields = if (action == ContactsToolAction.DELETE_CONTACT) {
                        emptySet()
                    } else {
                        setOf(ContactMutationField.DISPLAY_NAME)
                    },
                ),
            )
        }

        override suspend fun executeMutation(
            taskId: String,
            request: PiNativeToolRequest,
            plan: ContactsMutationPlan,
            onProviderDispatch: suspend () -> Unit,
        ): PiNativeAndroidToolResult {
            onProviderDispatch()
            mutationExecutionCount += 1
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject {
                    put("ok", true)
                    put("action", plan.action.wireValue)
                    put(
                        "data",
                        if (plan.action == ContactsToolAction.DELETE_CONTACT) {
                            buildJsonObject { put("deleted", true) }
                        } else {
                            buildJsonObject {
                                put(
                                    "contact",
                                    buildJsonObject {
                                        put("contactHandle", "contact-${"a".repeat(24)}")
                                        put("displayName", "Private Alex")
                                        put(
                                            "phones",
                                            kotlinx.serialization.json.buildJsonArray {},
                                        )
                                        put(
                                            "emails",
                                            kotlinx.serialization.json.buildJsonArray {},
                                        )
                                        put("organization", JsonNull)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "verification",
                        buildJsonObject {
                            put("status", "verified")
                            put("observedAt", "2026-07-29T08:00:00Z")
                            put("planDigest", plan.planDigest)
                        },
                    )
                },
            )
        }

        override suspend fun execute(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult {
            executionCount += 1
            this.request = request
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject {
                    put("ok", true)
                    put("action", "search")
                },
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Contacts fixture",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val TASK_ID = "22222222-2222-4222-8222-222222222222"
    }
}
