package app.momoding.feature.taskdetail

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.OutboundCommandState
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.transport.OutboundWireRequest
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCommandCoordinatorTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `prompt is durable before network and duplicate tap cannot create another identity`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 10L })
        val status = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val ids = ArrayDeque(listOf(COMMAND_ID, REQUEST_ID))
        val requests = mutableListOf<OutboundWireRequest>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCommandCoordinator(
            transportStatus = status,
            submitExact = { request ->
                val accepted = journal.command(request.requestId)
                assertEquals(OutboundCommandState.ACCEPTED, accepted?.state)
                assertEquals(request.canonicalPayload, accepted?.canonicalPayload)
                requests += request
                val response = promptResponse(request.requestId)
                journal.markTerminal(request.requestId, response)
                ReliabilityContractDecoder.decode(response)
            },
            journal = journal,
            applicationScope = scope,
            ioDispatcher = dispatcher,
            idFactory = { ids.removeFirst() },
        )

        assertTrue(coordinator.submit(TASK_ID, TaskCommandKind.PROMPT, "continue safely"))
        assertFalse(coordinator.submit(TASK_ID, TaskCommandKind.PROMPT, "duplicate"))
        advanceUntilIdle()

        assertEquals(1, requests.size)
        assertEquals(COMMAND_ID, requests.single().commandId)
        assertEquals(REQUEST_ID, requests.single().requestId)
        assertTrue(coordinator.observe(TASK_ID).value is TaskCommandProgress.Completed)
        scope.cancel()
    }

    @Test
    fun `offline stop persists a fence and resumes exact bytes when ready`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 10L })
        val status = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE))
        val nextCommand = "88888888-8888-4888-8888-888888888888"
        val nextRequest = "99999999-9999-4999-8999-999999999999"
        val ids = ArrayDeque(listOf(COMMAND_ID, REQUEST_ID, nextCommand, nextRequest))
        val requests = mutableListOf<OutboundWireRequest>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCommandCoordinator(
            transportStatus = status,
            submitExact = { request ->
                requests += request
                val response = if (request.kind == "session.stop") {
                    stopResponse(request.requestId)
                } else {
                    promptResponse(request.requestId)
                }
                journal.markTerminal(request.requestId, response)
                ReliabilityContractDecoder.decode(response)
            },
            journal = journal,
            applicationScope = scope,
            ioDispatcher = dispatcher,
            idFactory = { ids.removeFirst() },
        )

        assertTrue(coordinator.stop(TASK_ID))
        runCurrent()
        assertTrue(requests.isEmpty())
        assertTrue(journal.hasPendingStopFence(TASK_ID))
        val durable = journal.command(REQUEST_ID)!!
        assertEquals(OutboundCommandState.ACCEPTED, durable.state)

        status.value = SecureTransportUiStatus(SecureTransportUiPhase.READY)
        advanceUntilIdle()

        assertEquals(listOf(durable.canonicalPayload), requests.map { it.canonicalPayload })
        assertFalse(journal.hasPendingStopFence(TASK_ID))
        val completed = coordinator.observe(TASK_ID).value as TaskCommandProgress.Completed
        assertEquals(TaskCommandKind.STOP, completed.kind)
        assertEquals("stopped", completed.runState)
        coordinator.acknowledge(TASK_ID, completed.commandId)
        assertTrue(coordinator.submit(TASK_ID, TaskCommandKind.PROMPT, "continue after stop"))
        advanceUntilIdle()
        assertEquals(listOf("session.stop", "session.prompt"), requests.map { it.kind })
        scope.cancel()
    }

    @Test
    fun `ready recovery submits stop before older accepted steer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 1L
        val journal = RoomCommandDraftJournal(database, nowMillis = { now++ })
        val steerRequest = "44444444-4444-4444-8444-444444444444"
        val steerCommand = "55555555-5555-4555-8555-555555555555"
        val stopRequest = "66666666-6666-4666-8666-666666666666"
        val stopCommand = "77777777-7777-4777-8777-777777777777"
        journal.persistAccepted(
            steerRequest,
            steerCommand,
            "session.steer",
            TASK_ID,
            """{"protocolVersion":1,"kind":"session.steer","requestId":"$steerRequest","commandId":"$steerCommand","taskId":"$TASK_ID","text":"old steer"}""",
        )
        journal.persistAccepted(
            stopRequest,
            stopCommand,
            "session.stop",
            TASK_ID,
            """{"protocolVersion":1,"kind":"session.stop","requestId":"$stopRequest","commandId":"$stopCommand","taskId":"$TASK_ID","reason":"user"}""",
        )
        val status = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE))
        val order = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        TaskCommandCoordinator(
            transportStatus = status,
            submitExact = { request ->
                order += request.kind
                val response = if (request.kind == "session.stop") {
                    stopResponse(request.requestId)
                } else {
                    """{"protocolVersion":1,"kind":"response","requestId":"${request.requestId}","ok":false,"error":{"code":"ABORTED","message":"stopped","retryable":false}}"""
                }
                journal.markTerminal(request.requestId, response)
                ReliabilityContractDecoder.decode(response)
            },
            journal = journal,
            applicationScope = scope,
            ioDispatcher = dispatcher,
        )

        runCurrent()
        status.value = SecureTransportUiStatus(SecureTransportUiPhase.READY)
        advanceUntilIdle()

        assertEquals(listOf("session.stop", "session.steer"), order)
        scope.cancel()
    }

    @Test
    fun `offline accepted message keeps coordinator double tap fence after persistence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        val status = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE))
        val ids = ArrayDeque(listOf(COMMAND_ID, REQUEST_ID))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = TaskCommandCoordinator(
            transportStatus = status,
            submitExact = { error("Offline command must not reach transport") },
            journal = journal,
            applicationScope = scope,
            ioDispatcher = dispatcher,
            idFactory = { ids.removeFirst() },
        )

        assertTrue(coordinator.submit(TASK_ID, TaskCommandKind.STEER, "first durable steer"))
        runCurrent()
        assertEquals(OutboundCommandState.ACCEPTED, journal.command(REQUEST_ID)?.state)
        assertFalse(coordinator.submit(TASK_ID, TaskCommandKind.FOLLOW_UP, "must not duplicate"))
        scope.cancel()
    }

    private fun promptResponse(requestId: String) =
        """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"accepted":true,"runState":"starting"}}"""

    private fun stopResponse(requestId: String) =
        """{"protocolVersion":1,"kind":"response","requestId":"$requestId","ok":true,"data":{"accepted":true,"runState":"stopped"}}"""

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val COMMAND_ID = "22222222-2222-4222-8222-222222222222"
        const val REQUEST_ID = "33333333-3333-4333-8333-333333333333"
    }
}
