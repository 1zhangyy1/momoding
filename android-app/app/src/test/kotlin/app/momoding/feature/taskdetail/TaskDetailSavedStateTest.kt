package app.momoding.feature.taskdetail

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AttentionAcceptanceScope
import app.momoding.core.data.AttentionRequestRecord
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.ExactWireOutcome
import app.momoding.core.transport.ExactWireFailure
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.core.transport.TaskOpenSuccess
import app.momoding.wire.WireErrorCode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailSavedStateTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { command -> command.run() }
            .setTransactionExecutor { command -> command.run() }
            .build()
        database.momodingDao().upsertTask(task())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `system recreation restores unsent text but migrates old running mode to safe queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio"))
        val replay = MutableStateFlow<Map<String, app.momoding.core.transport.TaskReplayProgress>>(emptyMap())
        val coordinator = TaskCommandCoordinator(
            transportStatus = transport,
            submitExact = { error("No command expected") },
            journal = RoomCommandDraftJournal(database, nowMillis = AtomicLong(1)::getAndIncrement),
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val handle = SavedStateHandle()
        val draft = TextFieldValue("unsent follow-up", TextRange(2, 11))
        TaskDetailSavedStateCodec.saveComposer(handle, draft)
        TaskDetailSavedStateCodec.saveRunningMode(handle, RunningComposerMode.STEER)

        val savedValues = handle.keys().associateWith { key -> handle.get<Any?>(key) }
        val recreated = TaskDetailViewModel(
            taskId = TASK_ID,
            repository = TaskDetailRepository(database),
            transportStatus = transport,
            replayProgress = replay,
            openTaskAction = { ExactWireOutcome.Success(TaskOpenSuccess(1)) },
            retryConnectionAction = {},
            coordinator = coordinator,
            savedStateHandle = SavedStateHandle(savedValues),
        )

        assertEquals(draft, recreated.state.value.composer)
        assertEquals(RunningComposerMode.FOLLOW_UP, recreated.state.value.runningMode)
        assertEquals(null, database.momodingDao().draft("task-detail-$TASK_ID"))
        recreated.viewModelScope.cancel()
        applicationScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `task approval mode update is durable and returned by a recreated detail snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TaskDetailRepository(database, dispatcher)

        repository.updateApprovalMode(TASK_ID, TaskApprovalMode.FULL_ACCESS)
        advanceUntilIdle()

        assertEquals(TaskApprovalMode.FULL_ACCESS, database.momodingDao().task(TASK_ID)?.approvalMode)
        assertEquals(TaskApprovalMode.FULL_ACCESS, repository.observe(TASK_ID).first()?.approvalMode)
    }

    @Test
    fun `task approval persistence failure rolls optimistic selection back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio"))
        val coordinator = TaskCommandCoordinator(
            transportStatus = transport,
            submitExact = { error("No command expected") },
            journal = RoomCommandDraftJournal(database, nowMillis = AtomicLong(1)::getAndIncrement),
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = TaskDetailViewModel(
            taskId = TASK_ID,
            repository = TaskDetailRepository(database, dispatcher),
            transportStatus = transport,
            replayProgress = MutableStateFlow(emptyMap()),
            openTaskAction = { ExactWireOutcome.Success(TaskOpenSuccess(1)) },
            retryConnectionAction = {},
            coordinator = coordinator,
            savedStateHandle = SavedStateHandle(),
            approvalModePersistence = { throw IllegalStateException("fixture") },
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.canSelectApprovalMode)
        viewModel.dispatch(TaskDetailAction.SelectApprovalMode(TaskApprovalMode.FULL_ACCESS))
        assertEquals(TaskApprovalMode.FULL_ACCESS, viewModel.state.value.approvalMode)
        assertTrue(viewModel.state.value.approvalModeSaving)
        advanceUntilIdle()

        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, viewModel.state.value.approvalMode)
        assertFalse(viewModel.state.value.approvalModeSaving)
        assertEquals("The approval mode could not be saved.", viewModel.state.value.approvalModeError)
        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, database.momodingDao().task(TASK_ID)?.approvalMode)
        viewModel.viewModelScope.cancel()
        applicationScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `typed task open failure reaches missing and recoverable error while accepted waits for Room`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio"))
        val replay = MutableStateFlow<Map<String, app.momoding.core.transport.TaskReplayProgress>>(emptyMap())
        val coordinator = TaskCommandCoordinator(
            transportStatus = transport,
            submitExact = { error("No command expected") },
            journal = RoomCommandDraftJournal(database, nowMillis = AtomicLong(1)::getAndIncrement),
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        fun create(outcome: ExactWireOutcome<TaskOpenSuccess>) = TaskDetailViewModel(
            taskId = MISSING_TASK_ID,
            repository = TaskDetailRepository(database),
            transportStatus = transport,
            replayProgress = replay,
            openTaskAction = { outcome },
            retryConnectionAction = {},
            coordinator = coordinator,
            savedStateHandle = SavedStateHandle(),
        )

        val missing = create(ExactWireOutcome.Failure(ExactWireFailure(WireErrorCode.TASK_NOT_FOUND, false)))
        advanceUntilIdle()
        assertEquals(TaskDetailLoadState.MISSING, missing.state.value.loadState)

        val failed = create(ExactWireOutcome.Failure(ExactWireFailure(WireErrorCode.BAD_REQUEST, true)))
        advanceUntilIdle()
        assertEquals(TaskDetailLoadState.ERROR, failed.state.value.loadState)

        val accepted = create(ExactWireOutcome.Success(TaskOpenSuccess(2)))
        advanceUntilIdle()
        assertEquals(TaskDetailLoadState.LOADING, accepted.state.value.loadState)
        listOf(missing, failed, accepted).forEach { it.viewModelScope.cancel() }
        applicationScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `attention return notice is fixed and the same effect cannot be consumed twice`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio"))
        val coordinator = TaskCommandCoordinator(
            transportStatus = transport,
            submitExact = { error("No command expected") },
            journal = RoomCommandDraftJournal(database, nowMillis = AtomicLong(1)::getAndIncrement),
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = TaskDetailViewModel(
            taskId = TASK_ID,
            repository = TaskDetailRepository(database),
            transportStatus = transport,
            replayProgress = MutableStateFlow(emptyMap()),
            openTaskAction = { ExactWireOutcome.Success(TaskOpenSuccess(1)) },
            retryConnectionAction = {},
            coordinator = coordinator,
            savedStateHandle = SavedStateHandle(),
        )
        advanceUntilIdle()

        viewModel.acceptAttentionReturn("effect-1", "call-1", unavailable = true)
        assertEquals(
            TaskAttentionNavigationNotice.UNAVAILABLE,
            viewModel.state.value.attentionNavigationNotice,
        )
        assertEquals("This request is no longer available.", ATTENTION_UNAVAILABLE_NOTICE)

        viewModel.acceptAttentionReturn("effect-1", "call-1", unavailable = false)
        assertEquals(
            TaskAttentionNavigationNotice.UNAVAILABLE,
            viewModel.state.value.attentionNavigationNotice,
        )
        viewModel.viewModelScope.cancel()
        applicationScope.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `new pending attention stays inline until its exact card is opened`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio"))
        val coordinator = TaskCommandCoordinator(
            transportStatus = transport,
            submitExact = { error("No command expected") },
            journal = RoomCommandDraftJournal(database, nowMillis = AtomicLong(1)::getAndIncrement),
            applicationScope = applicationScope,
            ioDispatcher = dispatcher,
        )
        val viewModel = TaskDetailViewModel(
            taskId = TASK_ID,
            repository = TaskDetailRepository(database),
            transportStatus = transport,
            replayProgress = MutableStateFlow(emptyMap()),
            openTaskAction = { ExactWireOutcome.Success(TaskOpenSuccess(1)) },
            retryConnectionAction = {},
            coordinator = coordinator,
            savedStateHandle = SavedStateHandle(),
        )
        val navigation = mutableListOf<TaskDetailOneShot>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.oneShots.collect(navigation::add)
        }
        advanceUntilIdle()

        val ledger = RoomAttentionLedger(database) { 10L }
        ledger.acceptRequest(
            AttentionRequestRecord(
                callId = ATTENTION_CALL_ID,
                taskId = TASK_ID,
                piToolCallId = "pi-$ATTENTION_CALL_ID",
                deviceId = DEVICE_ID,
                toolName = "request_user_question",
                arguments = buildJsonObject { put("question", "Choose an implementation approach") },
                sideEffect = false,
                operationId = null,
                expiresAt = "2030-01-01T00:00:00.000Z",
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "composer"),
        )
        ledger.markAwaitingUser(ATTENTION_CALL_ID)
        advanceUntilIdle()

        assertEquals(ATTENTION_CALL_ID, viewModel.state.value.attention?.callId)
        assertEquals(emptyList<TaskDetailOneShot>(), navigation)

        viewModel.dispatch(TaskDetailAction.OpenAttention(ATTENTION_CALL_ID))
        assertEquals(listOf(TaskDetailOneShot.OpenAttention(ATTENTION_CALL_ID)), navigation)

        transport.value = SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE, "Mac Studio")
        advanceUntilIdle()
        assertEquals(listOf(TaskDetailOneShot.OpenAttention(ATTENTION_CALL_ID)), navigation)

        collector.cancel()
        viewModel.viewModelScope.cancel()
        applicationScope.cancel()
        advanceUntilIdle()
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Task shell",
        runState = "RUNNING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = true,
        updatedAtMillis = 1,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val MISSING_TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val ATTENTION_CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "android-phone-local"
    }
}
