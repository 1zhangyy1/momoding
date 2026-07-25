package app.momoding.feature.newtask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftRecord
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.transport.OutboundWireRequest
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCreationCoordinatorTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `duplicate start performs one exact create then prompt and publishes started`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft())
        val requests = mutableListOf<OutboundWireRequest>()
        val requestIds = ArrayDeque(listOf(CREATE_REQUEST_ID, PROMPT_REQUEST_ID))
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCreationCoordinator(
            transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
            submitExact = { request ->
                requests += request
                responseFor(request)
            },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
            requestIdFactory = { requestIds.removeFirst() },
        )

        coordinator.start(DRAFT_ID)
        coordinator.start(DRAFT_ID)
        advanceUntilIdle()

        assertEquals(listOf("task.create", "session.prompt"), requests.map { it.kind })
        assertEquals(CREATE_COMMAND_ID, requests[0].commandId)
        assertEquals(PROMPT_COMMAND_ID, requests[1].commandId)
        assertEquals(TASK_ID, requests[1].taskId)
        assertEquals(TaskCreationProgress.Started(TASK_ID), coordinator.observe(DRAFT_ID).value)
        assertEquals(TASK_ID, journal.draft(DRAFT_ID)?.taskId)
        applicationScope.cancel()
    }

    @Test
    fun `ready reconciliation resumes a terminal create without creating a second task`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft())
        val createPayload = """{"protocolVersion":1,"kind":"task.create","requestId":"$CREATE_REQUEST_ID","commandId":"$CREATE_COMMAND_ID","draftId":"$DRAFT_ID","title":"Ship the Android milestone"}"""
        journal.persistAccepted(CREATE_REQUEST_ID, CREATE_COMMAND_ID, "task.create", null, createPayload)
        journal.markTerminal(CREATE_REQUEST_ID, createSuccess(CREATE_REQUEST_ID))
        val requests = mutableListOf<OutboundWireRequest>()
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCreationCoordinator(
            transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
            submitExact = { request ->
                requests += request
                responseFor(request)
            },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
            requestIdFactory = { PROMPT_REQUEST_ID },
        )

        advanceUntilIdle()

        assertEquals(listOf("session.prompt"), requests.map { it.kind })
        assertEquals(TaskCreationProgress.Started(TASK_ID), coordinator.observe(DRAFT_ID).value)
        applicationScope.cancel()
    }

    @Test
    fun `typed retryable terminal error is exposed without rotating automatically`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft())
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCreationCoordinator(
            transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
            submitExact = { request ->
                ReliabilityContractDecoder.decode(
                    """{"protocolVersion":1,"kind":"response","requestId":"${request.requestId}","ok":false,"error":{"code":"DEVICE_OFFLINE","message":"fixture detail","retryable":true}}""",
                )
            },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
            requestIdFactory = { CREATE_REQUEST_ID },
        )

        coordinator.start(DRAFT_ID)
        advanceUntilIdle()

        val progress = coordinator.observe(DRAFT_ID).value
        assertTrue(progress is TaskCreationProgress.Failed)
        progress as TaskCreationProgress.Failed
        assertEquals(TaskCreationStage.CREATE, progress.stage)
        assertTrue(progress.retryable)
        assertEquals(0L, journal.draft(DRAFT_ID)?.attemptOrdinal)
        applicationScope.cancel()
    }

    @Test
    fun `multiple drafts share one global create prompt pipeline`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.saveDraft(draft())
        journal.saveDraft(
            draft(
                draftId = "draft-2",
                createCommandId = "77777777-7777-4777-8777-777777777777",
                promptCommandId = "88888888-8888-4888-8888-888888888888",
            ),
        )
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var activePipelines = 0
        var maximumActivePipelines = 0
        var nextRequest = 0
        val coordinator = TaskCreationCoordinator(
            transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
            submitExact = { request ->
                activePipelines += 1
                maximumActivePipelines = maxOf(maximumActivePipelines, activePipelines)
                try {
                    if (firstStarted.complete(Unit)) releaseFirst.await()
                    nextRequest += 1
                    if (request.kind == "task.create") {
                        val taskId = if (request.commandId == CREATE_COMMAND_ID) TASK_ID else "99999999-9999-4999-8999-999999999999"
                        ReliabilityContractDecoder.decode(
                            """{"protocolVersion":1,"kind":"response","requestId":"${request.requestId}","ok":true,"data":{"taskId":"$taskId","piSessionId":"$PI_SESSION_ID"}}""",
                        )
                    } else {
                        ReliabilityContractDecoder.decode(
                            """{"protocolVersion":1,"kind":"response","requestId":"${request.requestId}","ok":true,"data":{"accepted":true,"runState":"starting"}}""",
                        )
                    }
                } finally {
                    activePipelines -= 1
                }
            },
            journal = journal,
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
            requestIdFactory = {
                nextRequest += 1
                "00000000-0000-4000-8000-${nextRequest.toString().padStart(12, '0')}"
            },
        )

        coordinator.start(DRAFT_ID)
        coordinator.start("draft-2")
        runCurrent()
        assertTrue(firstStarted.isCompleted)
        assertEquals(1, maximumActivePipelines)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, maximumActivePipelines)
        assertTrue(coordinator.observe(DRAFT_ID).value is TaskCreationProgress.Started)
        assertTrue(coordinator.observe("draft-2").value is TaskCreationProgress.Started)
        applicationScope.cancel()
    }

    private fun responseFor(request: OutboundWireRequest) = when (request.kind) {
        "task.create" -> ReliabilityContractDecoder.decode(createSuccess(request.requestId))
        "session.prompt" -> {
            val payload = Json.parseToJsonElement(request.canonicalPayload).jsonObject
            assertEquals(TASK_ID, payload.getValue("taskId").jsonPrimitive.content)
            ReliabilityContractDecoder.decode(
                """{"protocolVersion":1,"kind":"response","requestId":"${request.requestId}","ok":true,"data":{"accepted":true,"runState":"starting"}}""",
            )
        }
        else -> error("Unexpected request ${request.kind}")
    }

    private fun createSuccess(requestId: String) =
        """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"taskId":"$TASK_ID","piSessionId":"$PI_SESSION_ID"}}"""

    private fun draft(
        draftId: String = DRAFT_ID,
        createCommandId: String = CREATE_COMMAND_ID,
        promptCommandId: String = PROMPT_COMMAND_ID,
    ) = DraftRecord(
        draftId = draftId,
        text = "Ship the Android milestone",
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        createCommandId = createCommandId,
        promptCommandId = promptCommandId,
        taskId = null,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val DRAFT_ID = "draft-1"
        const val CREATE_REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val PROMPT_REQUEST_ID = "22222222-2222-4222-8222-222222222222"
        const val CREATE_COMMAND_ID = "33333333-3333-4333-8333-333333333333"
        const val PROMPT_COMMAND_ID = "44444444-4444-4444-8444-444444444444"
        const val TASK_ID = "55555555-5555-4555-8555-555555555555"
        const val PI_SESSION_ID = "66666666-6666-4666-8666-666666666666"
    }
}
