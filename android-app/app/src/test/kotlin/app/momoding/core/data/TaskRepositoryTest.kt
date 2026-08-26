package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskRepositoryTest {
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger
    private val clock = AtomicLong(100)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = RoomAttentionLedger(database) { clock.getAndIncrement() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `single observable projection uses only local ledger and prioritizes actionable`() =
        runBlocking {
            val dao = database.momodingDao()
            dao.upsertTask(task(OLDER_TASK_ID, 100, readState = "UNREAD"))
            dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
            dao.upsertTask(task(UNDATED_TASK_ID, null, readState = "UNREAD"))
            dao.upsertTask(task(HIDDEN_TASK_ID, 300, readState = "UNREAD", listed = false))

            accept(OLDER_TASK_ID, OLDER_CONFIRM_CALL_ID, "request_user_confirmation")
            accept(NEWER_TASK_ID, NEWER_RESPONDING_CALL_ID, "request_user_question")
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    frame = optionResult(NEWER_TASK_ID, NEWER_RESPONDING_CALL_ID),
                    origin = AttentionTerminalOrigin.USER,
                    nowMillis = 1_000,
                ),
            )
            ledger.markHostTerminalDurable(NEWER_RESPONDING_CALL_ID)
            accept(NEWER_TASK_ID, NEWER_PENDING_CALL_ID, "request_user_confirmation")
            dao.insertHostPendingAttention(
                listOf(
                    HostPendingAttentionEntity(
                        taskId = UNDATED_TASK_ID,
                        ordinal = 0,
                        callId = PHANTOM_HOST_CALL_ID,
                        toolName = "request_user_question",
                        argumentsJson = "{}",
                        state = "waiting",
                        expiresAt = null,
                        originFocusKey = null,
                        terminalResultJson = null,
                        rawPayload = "{}",
                    ),
                ),
            )

            val repository = TaskRepository(database, Dispatchers.Unconfined)
            val rows = repository.observeTaskRows().first()
            assertEquals(listOf(NEWER_TASK_ID, OLDER_TASK_ID, UNDATED_TASK_ID), rows.map { it.taskId })
            assertEquals(TaskAttentionKind.MULTIPLE, rows[0].attentionKind)
            assertEquals(2, rows[0].attentionCount)
            assertEquals(NEWER_PENDING_CALL_ID, rows[0].primaryAttentionCallId)
            assertEquals(TaskAttentionKind.CONFIRMATION, rows[1].attentionKind)
            assertEquals(OLDER_CONFIRM_CALL_ID, rows[1].primaryAttentionCallId)
            assertEquals(TaskAttentionKind.NONE, rows[2].attentionKind)
            assertNull(rows[2].primaryAttentionCallId)
            assertTrue(rows[1].isUnread)

            markPiDelivered(NEWER_RESPONDING_CALL_ID)
            val deliveredRows = repository.observeTaskRows().first()
            val deliveredNewer = deliveredRows.single { it.taskId == NEWER_TASK_ID }
            assertEquals(1, deliveredNewer.attentionCount)
            assertEquals(TaskAttentionKind.CONFIRMATION, deliveredNewer.attentionKind)
            assertEquals(NEWER_PENDING_CALL_ID, deliveredNewer.primaryAttentionCallId)

            assertTrue(repository.markRead(OLDER_TASK_ID))
            assertFalse(repository.observeTaskRows().first().single { it.taskId == OLDER_TASK_ID }.isUnread)
            assertFalse(repository.markRead(MISSING_TASK_ID))
        }

    @Test
    fun `exact task and call query never substitutes another local attention`() = runBlocking {
        val dao = database.momodingDao()
        dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
        dao.upsertTask(task(OLDER_TASK_ID, 100, readState = "READ"))
        accept(NEWER_TASK_ID, NEWER_PENDING_CALL_ID, "request_user_question")

        val exact = dao.observeLocalAttention(NEWER_TASK_ID, NEWER_PENDING_CALL_ID).first()
        assertNotNull(exact)
        assertEquals(NEWER_PENDING_CALL_ID, exact!!.operation.callId)
        assertEquals(1, exact.attention.size)
        assertEquals(NEWER_PENDING_CALL_ID, exact.attention.single().callId)
        assertNull(dao.observeLocalAttention(OLDER_TASK_ID, NEWER_PENDING_CALL_ID).first())
        assertEquals(
            listOf(NEWER_PENDING_CALL_ID),
            dao.observeLocalTaskAttention(NEWER_TASK_ID).first().map { it.operation.callId },
        )
    }

    @Test
    fun `media mutation is a first class confirmation in the task list`() = runBlocking {
        database.momodingDao().upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
        ledger.acceptRequest(
            AttentionRequestRecord(
                callId = MEDIA_CALL_ID,
                taskId = NEWER_TASK_ID,
                piToolCallId = "pi-$MEDIA_CALL_ID",
                deviceId = DEVICE_ID,
                toolName = "device_media",
                arguments = buildJsonObject {
                    put("approvalKind", "mutation")
                    put("action", "set_trashed")
                    put("requestDigest", "6".repeat(64))
                    put("planDigest", "7".repeat(64))
                    put("summary", "Move this photo to Android trash?")
                    put("details", "Android will ask for confirmation and verify the result.")
                },
                sideEffect = true,
                operationId = MEDIA_OPERATION_ID,
                expiresAt = EXPIRES_AT,
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(NEWER_TASK_ID, DEVICE_ID, 1, "task-$NEWER_TASK_ID"),
        )

        val row = TaskRepository(database, Dispatchers.Unconfined)
            .observeTaskRows()
            .first()
            .single()
        assertEquals(TaskAttentionKind.CONFIRMATION, row.attentionKind)
        assertEquals(MEDIA_CALL_ID, row.primaryAttentionCallId)
        assertEquals("device_media", row.primaryAttentionToolName)
    }

    @Test
    fun `typed responding state remains through Host proof and exits only after Pi proof`() =
        runBlocking {
            database.momodingDao().upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
            accept(NEWER_TASK_ID, NEWER_RESPONDING_CALL_ID, "request_user_question")
            val repository = TaskRepository(database, Dispatchers.Unconfined)
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    frame = optionResult(NEWER_TASK_ID, NEWER_RESPONDING_CALL_ID),
                    origin = AttentionTerminalOrigin.USER,
                    nowMillis = 1_000,
                ),
            )

            suspend fun assertResponding() {
                val row = repository.observeTaskRows().first().single()
                assertEquals(1, row.attentionCount)
                assertEquals(AttentionResponseState.RESPONDING, row.primaryAttentionResponseState)
            }

            assertResponding()
            ledger.markSent(NEWER_RESPONDING_CALL_ID)
            assertResponding()
            ledger.markHostTerminalDurable(NEWER_RESPONDING_CALL_ID)
            assertResponding()
            markPiDelivered(NEWER_RESPONDING_CALL_ID)

            val delivered = repository.observeTaskRows().first().single()
            assertEquals(0, delivered.attentionCount)
            assertNull(delivered.primaryAttentionResponseState)
        }

    @Test
    fun `corrupt and failed closed local pairs never become task entry points`() = runBlocking {
        val dao = database.momodingDao()
        dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
        accept(NEWER_TASK_ID, NEWER_PENDING_CALL_ID, "request_user_question")
        val projection = requireNotNull(dao.pendingAttention(NEWER_PENDING_CALL_ID))
        check(dao.updatePendingAttention(
            projection.copy(responseState = AttentionResponseState.RESPONDING.name),
        ) == 1)

        val failedClosedCall = OLDER_CONFIRM_CALL_ID
        ledger.acceptRequest(
            AttentionRequestRecord(
                callId = failedClosedCall,
                taskId = NEWER_TASK_ID,
                piToolCallId = "pi-$failedClosedCall",
                deviceId = DEVICE_ID,
                toolName = "unsupported_attention_tool",
                arguments = buildJsonObject { put("unsupported", true) },
                sideEffect = true,
                operationId = null,
                expiresAt = EXPIRES_AT,
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(NEWER_TASK_ID, DEVICE_ID, 1, "task-$NEWER_TASK_ID"),
        )

        val row = TaskRepository(database, Dispatchers.Unconfined).observeTaskRows().first().single()
        assertEquals(TaskAttentionKind.NONE, row.attentionKind)
        assertEquals(0, row.attentionCount)
        assertNull(row.primaryAttentionCallId)
    }

    @Test
    fun `phone local task management persists rename pin archive restore and guarded delete`() =
        runBlocking {
            val dao = database.momodingDao()
            dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
            val repository = TaskRepository(
                database = database,
                ioDispatcher = Dispatchers.Unconfined,
                nowMillis = { 5_000L },
            )

            assertEquals("A short title", repository.rename(NEWER_TASK_ID, "  A   short title  "))
            assertEquals("USER", dao.task(NEWER_TASK_ID)?.titleSource)
            assertEquals(0, dao.setAutomaticTaskTitle(NEWER_TASK_ID, "Automatic overwrite"))
            assertEquals("A short title", dao.task(NEWER_TASK_ID)?.title)

            assertTrue(repository.setPinned(NEWER_TASK_ID, true))
            assertEquals(5_000L, repository.observeTaskRows().first().single().pinnedAtMillis)
            repository.archive(NEWER_TASK_ID)
            assertNull(dao.task(NEWER_TASK_ID)?.pinnedAtMillis)
            assertEquals(5_000L, dao.task(NEWER_TASK_ID)?.archivedAtMillis)

            repository.restore(NEWER_TASK_ID)
            assertNull(dao.task(NEWER_TASK_ID)?.archivedAtMillis)
            assertTrue(
                runCatching { repository.deleteArchived(NEWER_TASK_ID) }.isFailure,
            )
            repository.archive(NEWER_TASK_ID)
            repository.deleteArchived(NEWER_TASK_ID)
            assertNull(dao.task(NEWER_TASK_ID))
        }

    @Test
    fun `running tasks cannot be archived or permanently deleted`() = runBlocking {
        val active = task(NEWER_TASK_ID, 200, readState = "READ").copy(
            runState = "RUNNING",
            isStreaming = true,
        )
        database.momodingDao().upsertTask(active)
        val repository = TaskRepository(database, Dispatchers.Unconfined)

        assertTrue(runCatching { repository.archive(NEWER_TASK_ID) }.isFailure)
        assertNotNull(database.momodingDao().task(NEWER_TASK_ID))
    }

    @Test
    fun `conditional archive and delete reject a task that became active after a stale read`() {
        val dao = database.momodingDao()
        dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
        val staleSettled = requireNotNull(dao.task(NEWER_TASK_ID))
        assertFalse(staleSettled.isStreaming)

        dao.upsertTask(staleSettled.copy(runState = "RUNNING", isStreaming = true))
        assertEquals(0, dao.archiveSettledTask(NEWER_TASK_ID, 5_000L))
        assertNull(dao.task(NEWER_TASK_ID)?.archivedAtMillis)

        dao.upsertTask(
            requireNotNull(dao.task(NEWER_TASK_ID)).copy(
                runState = "COMPLETED",
                isStreaming = false,
                archivedAtMillis = 4_000L,
            ),
        )
        val staleArchived = requireNotNull(dao.task(NEWER_TASK_ID))
        dao.upsertTask(staleArchived.copy(runState = "RETRYING", isStreaming = true))
        assertEquals(0, dao.deleteArchivedSettledTaskAndPayload(NEWER_TASK_ID))
        assertNotNull(dao.task(NEWER_TASK_ID))
    }

    @Test
    fun `permanent delete removes bound draft and command payloads but preserves unrelated data`() =
        runBlocking {
            val dao = database.momodingDao()
            dao.upsertTask(task(NEWER_TASK_ID, 200, readState = "READ"))
            dao.insertDraft(
                draft(
                    draftId = OWNED_DRAFT_ID,
                    taskId = NEWER_TASK_ID,
                    createCommandId = OWNED_CREATE_COMMAND_ID,
                    promptCommandId = OWNED_PROMPT_COMMAND_ID,
                    text = "private prompt that must be erased",
                ),
            )
            dao.insertDraft(
                draft(
                    draftId = UNRELATED_DRAFT_ID,
                    taskId = OLDER_TASK_ID,
                    createCommandId = UNRELATED_CREATE_COMMAND_ID,
                    promptCommandId = UNRELATED_PROMPT_COMMAND_ID,
                    text = "unrelated prompt",
                ),
            )
            dao.insertOutboundCommand(
                command(
                    requestId = "request-owned-create",
                    commandId = OWNED_CREATE_COMMAND_ID,
                    taskId = null,
                    payload = "{\"kind\":\"task.create\",\"prompt\":\"private prompt\"}",
                ),
            )
            dao.insertOutboundCommand(
                command(
                    requestId = "request-owned-prompt",
                    commandId = OWNED_PROMPT_COMMAND_ID,
                    taskId = NEWER_TASK_ID,
                    payload = "{\"kind\":\"session.prompt\",\"taskId\":\"$NEWER_TASK_ID\",\"text\":\"private prompt\"}",
                ),
            )
            dao.insertOutboundCommand(
                command(
                    requestId = "request-unrelated-create",
                    commandId = UNRELATED_CREATE_COMMAND_ID,
                    taskId = null,
                    payload = "{\"kind\":\"task.create\",\"prompt\":\"unrelated\"}",
                ),
            )

            val deletedTaskIds = mutableListOf<String>()
            val repository = TaskRepository(
                database = database,
                ioDispatcher = Dispatchers.Unconfined,
                onTaskDeleted = deletedTaskIds::add,
            )
            repository.archive(NEWER_TASK_ID)
            repository.deleteArchived(NEWER_TASK_ID)

            assertNull(dao.task(NEWER_TASK_ID))
            assertEquals(listOf(NEWER_TASK_ID), deletedTaskIds)
            assertEquals(listOf(UNRELATED_DRAFT_ID), dao.drafts().map(DraftEntity::draftId))
            assertEquals(
                listOf("request-unrelated-create"),
                dao.outboundCommands().map(OutboundCommandEntity::requestId),
            )
            assertFalse(dao.drafts().any { it.text.contains("private prompt") })
            assertFalse(
                dao.outboundCommands().any { it.canonicalPayload.contains("private prompt") },
            )
        }

    private fun draft(
        draftId: String,
        taskId: String,
        createCommandId: String,
        promptCommandId: String,
        text: String,
    ) = DraftEntity(
        draftId = draftId,
        text = text,
        selectedHostId = null,
        selectedModelId = "test/model",
        selectedMode = null,
        selectedGrantId = null,
        createCommandId = createCommandId,
        promptCommandId = promptCommandId,
        taskId = taskId,
        updatedAtMillis = 100L,
    )

    private fun command(
        requestId: String,
        commandId: String,
        taskId: String?,
        payload: String,
    ) = OutboundCommandEntity(
        requestId = requestId,
        commandId = commandId,
        kind = if (taskId == null) "task.create" else "session.prompt",
        taskId = taskId,
        canonicalPayload = payload,
        payloadSha256 = "test-digest",
        state = OutboundCommandState.TERMINAL.name,
        responseJson = "{\"ok\":true}",
        responseSha256 = "test-response-digest",
        stopFenceState = null,
        createdAtMillis = 100L,
        updatedAtMillis = 101L,
    )

    private fun accept(taskId: String, callId: String, toolName: String) {
        val arguments = if (toolName == "request_user_question") {
            buildJsonObject {
                put("question", "Choose an approach")
                put("options", buildJsonArray {
                    add(buildJsonObject { put("label", "Balanced approach") })
                })
            }
        } else {
            buildJsonObject { put("summary", "Allow this step?") }
        }
        ledger.acceptRequest(
            AttentionRequestRecord(
                callId = callId,
                taskId = taskId,
                piToolCallId = "pi-$callId",
                deviceId = DEVICE_ID,
                toolName = toolName,
                arguments = arguments,
                sideEffect = false,
                operationId = null,
                expiresAt = EXPIRES_AT,
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(taskId, DEVICE_ID, 1, "task-$taskId"),
        )
    }

    private fun markPiDelivered(callId: String): AttentionLedgerRecord {
        val operation = requireNotNull(ledger.record(callId)).operation
        val expectation = requireNotNull(
            ledger.piDeliveryExpectationForValidatedPair(operation),
        )
        return ledger.markPiDeliveredAfterVerifiedProof(
            taskId = operation.taskId,
            callId = callId,
            terminalSemanticSha256 = expectation.terminalSemanticSha256,
        )
    }

    private fun optionResult(taskId: String, callId: String) = DeviceToolResultClientFrame(
        callId = callId,
        taskId = taskId,
        deviceId = DEVICE_ID,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = buildJsonObject {
            put("outcome", "answered")
            put("answer", buildJsonObject {
                put("kind", "option")
                put("index", 0)
                put("label", "Balanced approach")
            })
        },
    )

    private fun task(
        taskId: String,
        hostUpdatedAtMillis: Long?,
        readState: String,
        listed: Boolean = true,
    ) = TaskEntity(
        taskId = taskId,
        title = "Title $taskId",
        runState = "idle",
        recoveryState = "normal",
        readState = readState,
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = hostUpdatedAtMillis ?: 50,
        listedByHost = listed,
        lastListSyncGeneration = null,
        lastListRevision = null,
        hostUpdatedAtMillis = hostUpdatedAtMillis,
    )

    private companion object {
        const val NEWER_TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OLDER_TASK_ID = "11111111-1111-4111-8111-111111111112"
        const val UNDATED_TASK_ID = "11111111-1111-4111-8111-111111111113"
        const val HIDDEN_TASK_ID = "11111111-1111-4111-8111-111111111114"
        const val MISSING_TASK_ID = "11111111-1111-4111-8111-111111111115"
        const val OWNED_DRAFT_ID = "draft-owned"
        const val UNRELATED_DRAFT_ID = "draft-unrelated"
        const val OWNED_CREATE_COMMAND_ID = "33333333-3333-4333-8333-333333333331"
        const val OWNED_PROMPT_COMMAND_ID = "33333333-3333-4333-8333-333333333332"
        const val UNRELATED_CREATE_COMMAND_ID = "33333333-3333-4333-8333-333333333333"
        const val UNRELATED_PROMPT_COMMAND_ID = "33333333-3333-4333-8333-333333333334"
        const val NEWER_RESPONDING_CALL_ID = "22222222-2222-4222-8222-222222222221"
        const val NEWER_PENDING_CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val OLDER_CONFIRM_CALL_ID = "22222222-2222-4222-8222-222222222223"
        const val PHANTOM_HOST_CALL_ID = "22222222-2222-4222-8222-222222222224"
        const val MEDIA_CALL_ID = "22222222-2222-4222-8222-222222222225"
        const val MEDIA_OPERATION_ID = "44444444-4444-4444-8444-444444444445"
        const val DEVICE_ID = "android-p2-7-device"
        const val EXPIRES_AT = "2030-01-01T00:00:00.000Z"
    }
}
