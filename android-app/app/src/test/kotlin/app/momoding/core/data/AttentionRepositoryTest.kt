package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.files.AuthorizedDocumentMetadata
import app.momoding.core.files.AuthorizedFolderListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttentionRepositoryTest {
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger
    private lateinit var repository: AttentionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.momodingDao().upsertTask(task(TASK_ID))
        database.momodingDao().upsertTask(task(OTHER_TASK_ID))
        ledger = RoomAttentionLedger(database) { 10L }
        repository = AttentionRepository(database, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `exact query exposes only typed product fields and restores durable draft`() = runTest {
        acceptQuestion()

        val initial = repository.observe(TASK_ID, CALL_ID).first()
            as AttentionRecordState.Available
        val question = initial.record.prompt as AttentionPrompt.Question
        assertEquals("Which path?", question.question)
        assertEquals(listOf("Safe", "Fast"), question.options.map { it.label })
        assertTrue(question.options.first().recommended)
        assertFalse(question.options.last().recommended)
        assertEquals("Keeps checks", question.options.first().description)
        assertEquals("", initial.record.draft.customAnswer)

        repository.saveDraft(
            TASK_ID,
            CALL_ID,
            AttentionDraftWrite(
                selectedOptionIndex = null,
                customAnswer = "durable answer",
                selectionStart = 2,
                selectionEnd = 8,
                validationCode = null,
            ),
        )
        val restored = (repository.current(TASK_ID, CALL_ID) as AttentionRecordState.Available).record
        assertEquals("durable answer", restored.draft.customAnswer)
        assertEquals(2, restored.draft.selectionStart)
        assertEquals(8, restored.draft.selectionEnd)

        repository.dismiss(TASK_ID, CALL_ID)
        assertTrue(
            (repository.current(TASK_ID, CALL_ID) as AttentionRecordState.Available)
                .record.dismissed,
        )
    }

    @Test
    fun `cross task reads and mutations never fall through to the call id`() = runTest {
        acceptQuestion()

        assertEquals(
            AttentionRecordState.Missing,
            repository.current(OTHER_TASK_ID, CALL_ID),
        )
        val saveFailure = runCatching {
            repository.saveDraft(
                OTHER_TASK_ID,
                CALL_ID,
                AttentionDraftWrite(null, "wrong task", 0, 0, null),
            )
        }.exceptionOrNull()
        assertTrue(saveFailure is AttentionLedgerConflictException)

        val dismissFailure = runCatching {
            repository.dismiss(OTHER_TASK_ID, CALL_ID)
        }.exceptionOrNull()
        assertTrue(dismissFailure is AttentionLedgerConflictException)
        assertEquals("", ledger.record(CALL_ID)!!.attention.customAnswer)
        assertEquals(null, ledger.record(CALL_ID)!!.attention.dismissedAtMillis)
    }

    @Test
    fun `failed closed and corrupt rows cannot become task or exact actionable models`() = runTest {
        acceptQuestion(callId = CALL_ID)
        ledger.acceptRequest(
            request(
                callId = FAILED_CALL_ID,
                sideEffect = true,
            ),
            scope(),
        )
        assertEquals(
            AttentionRecordState.FailedClosedHidden,
            repository.current(TASK_ID, FAILED_CALL_ID),
        )

        val valid = database.momodingDao().pendingAttention(CALL_ID)!!
        database.momodingDao().updatePendingAttention(
            valid.copy(responseState = AttentionResponseState.RESPONDING.name),
        )
        assertEquals(AttentionRecordState.Corrupt, repository.current(TASK_ID, CALL_ID))
        assertEquals(emptyList<TaskAttentionSummary>(), repository.observeTaskAttention(TASK_ID).first())
    }

    @Test
    fun `exact observable model includes the durable task stop fence`() = runTest {
        acceptQuestion()
        val observedFence = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observe(TASK_ID, CALL_ID)
                .filter { state ->
                    (state as? AttentionRecordState.Available)?.record?.activeStopFence == true
                }
                .first()
        }
        database.momodingDao().insertOutboundCommand(
            OutboundCommandEntity(
                requestId = REQUEST_ID,
                commandId = COMMAND_ID,
                kind = "session.stop",
                taskId = TASK_ID,
                canonicalPayload = "{}",
                payloadSha256 = "0".repeat(64),
                state = OutboundCommandState.ACCEPTED.name,
                responseJson = null,
                responseSha256 = null,
                stopFenceState = StopFenceState.ACTIVE.name,
                createdAtMillis = 10L,
                updatedAtMillis = 10L,
            ),
        )

        val fenced = (observedFence.await() as AttentionRecordState.Available).record
        assertTrue(fenced.activeStopFence)
    }

    @Test
    fun `content read is actionable only after every exact live file name passes policy`() = runTest {
        ledger.acceptRequest(contentReadRequest(), scope())

        val unresolved = (
            repository.current(TASK_ID, CONTENT_CALL_ID) as AttentionRecordState.Available
        ).record.prompt as AttentionPrompt.ContentRead
        assertFalse(unresolved.filesVerified)
        assertEquals(DOCUMENT_ALIAS, unresolved.documents.single().displayName)

        repository = AttentionRepository(
            database = database,
            ioDispatcher = Dispatchers.Unconfined,
            contentMetadataLoader = {
                AuthorizedFolderListing(
                    grantId = GRANT_ID,
                    documents = listOf(
                        AuthorizedDocumentMetadata(
                            alias = DOCUMENT_ALIAS,
                            parentAlias = null,
                            displayName = "README.md",
                            mimeType = "text/plain",
                            byteCount = 5,
                            lastModifiedMillis = 1,
                            depth = 1,
                        ),
                    ),
                    truncated = false,
                    truncationReasons = emptySet(),
                )
            },
        )
        val verified = (
            repository.current(TASK_ID, CONTENT_CALL_ID) as AttentionRecordState.Available
        ).record.prompt as AttentionPrompt.ContentRead
        assertTrue(verified.filesVerified)
        assertEquals("README.md", verified.documents.single().displayName)

        repository = AttentionRepository(
            database = database,
            ioDispatcher = Dispatchers.Unconfined,
            contentMetadataLoader = {
                AuthorizedFolderListing(
                    grantId = GRANT_ID,
                    documents = listOf(
                        AuthorizedDocumentMetadata(
                            alias = DOCUMENT_ALIAS,
                            parentAlias = null,
                            displayName = ".env.local",
                            mimeType = "text/plain",
                            byteCount = 5,
                            lastModifiedMillis = 1,
                            depth = 1,
                        ),
                    ),
                    truncated = false,
                    truncationReasons = emptySet(),
                )
            },
        )
        val policyBlocked = (
            repository.current(TASK_ID, CONTENT_CALL_ID) as AttentionRecordState.Available
        ).record.prompt as AttentionPrompt.ContentRead
        assertFalse(policyBlocked.filesVerified)
    }

    @Test
    fun `public typed records contain no transport host or file capability fields`() {
        val fieldNames = listOf(
            AttentionRecord::class.java,
            AttentionDraft::class.java,
            AttentionOption::class.java,
            TaskAttentionSummary::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }
        val forbidden = listOf(
            "raw",
            "json",
            "uri",
            "path",
            "endpoint",
            "pin",
            "credential",
            "deviceid",
            "pitoolcallid",
            "sha256",
            "hosterror",
        )
        forbidden.forEach { token ->
            assertFalse("Typed record leaked $token", fieldNames.any { token in it })
        }
    }

    private fun acceptQuestion(callId: String = CALL_ID) {
        ledger.acceptRequest(request(callId), scope())
    }

    private fun request(
        callId: String,
        sideEffect: Boolean = false,
    ) = AttentionRequestRecord(
        callId = callId,
        taskId = TASK_ID,
        piToolCallId = "pi-$callId",
        deviceId = DEVICE_ID,
        toolName = "request_user_question",
        arguments = buildJsonObject {
            put("question", "Which path?")
            put("options", buildJsonArray {
                add(buildJsonObject {
                    put("label", "Safe")
                    put("description", "Keeps checks")
                    put("recommended", true)
                })
                add(buildJsonObject { put("label", "Fast") })
            })
        },
        sideEffect = sideEffect,
        operationId = null,
        expiresAt = "2030-01-01T00:00:00.000Z",
        capabilityVersion = 1,
    )

    private fun contentReadRequest() = AttentionRequestRecord(
        callId = CONTENT_CALL_ID,
        taskId = TASK_ID,
        piToolCallId = "pi-$CONTENT_CALL_ID",
        deviceId = DEVICE_ID,
        toolName = "device_files_read",
        arguments = buildJsonObject {
            put("grantId", GRANT_ID)
            put("purpose", "Read project instructions")
            put("documents", buildJsonArray {
                add(buildJsonObject {
                    put("alias", DOCUMENT_ALIAS)
                    put("expectedMimeType", "text/plain")
                    put("maxBytes", 32)
                })
            })
            put("totalMaxBytes", 32)
        },
        sideEffect = false,
        operationId = null,
        expiresAt = "2030-01-01T00:00:00.000Z",
        capabilityVersion = 1,
    )

    private fun scope() = AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "task-detail")

    private fun task(taskId: String) = TaskEntity(
        taskId = taskId,
        title = "Task shell",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "PENDING",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = 1,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val FAILED_CALL_ID = "44444444-4444-4444-8444-444444444444"
        const val REQUEST_ID = "55555555-5555-4555-8555-555555555555"
        const val COMMAND_ID = "66666666-6666-4666-8666-666666666666"
        const val DEVICE_ID = "android-attention-device"
        const val CONTENT_CALL_ID = "77777777-7777-4777-8777-777777777777"
        const val GRANT_ID = "88888888-8888-4888-8888-888888888888"
        const val DOCUMENT_ALIAS = "doc-0123456789abcdef01234567"
    }
}
