package app.momoding.feature.tasks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.TaskEntity
import app.momoding.core.data.TaskAttentionKind
import app.momoding.core.data.TaskFailure
import app.momoding.core.data.TaskFailureKind
import app.momoding.core.data.TaskFailureRecovery
import app.momoding.core.data.TaskListRow
import app.momoding.core.data.TaskRepository
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class TaskHomeViewModelTest {
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
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `open task durably marks read before navigation and submits read-only open afterward`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        database.p2Dao().upsertTask(task("task-1", readState = "UNREAD"))
        val repository = TaskRepository(database, dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val opened = mutableListOf<String>()
        var readBeforeOpen = false
        val viewModel = TaskHomeViewModel(
            repository = repository,
            transportStatus = transport,
            synchronizeTaskListAction = {},
            retryConnectionAction = {},
            openTaskAction = { taskId ->
                readBeforeOpen = database.p2Dao().task(taskId)?.readState == "READ"
                opened += taskId
            },
            nowMillis = { 1_000L },
        )
        val navigation = async { viewModel.oneShots.first() }
        runCurrent()

        viewModel.dispatch(TaskHomeAction.OpenTask("task-1"))
        advanceUntilIdle()

        assertEquals(TaskHomeOneShot.OpenTask("task-1", cached = false), navigation.await())
        assertEquals(listOf("task-1"), opened)
        assertTrue(readBeforeOpen)
        assertFalse(repository.observeTaskRows().first().single().isUnread)
    }

    @Test
    fun `missing task cannot navigate or submit task open`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = TaskRepository(database, dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        var opened = false
        val viewModel = TaskHomeViewModel(
            repository = repository,
            transportStatus = transport,
            synchronizeTaskListAction = {},
            retryConnectionAction = {},
            openTaskAction = { opened = true },
        )
        val navigation = async { viewModel.oneShots.first() }
        runCurrent()

        viewModel.dispatch(TaskHomeAction.OpenTask("missing"))
        advanceUntilIdle()

        assertFalse(navigation.isCompleted)
        navigation.cancel()
        assertFalse(opened)
    }

    @Test
    fun `phone local failed task opens Provider recovery for the same task`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TaskHomeViewModel(
            repository = TaskRepository(database, StandardTestDispatcher(testScheduler)),
            phoneLocalModelId = "test/model",
        )
        val navigation = async { viewModel.oneShots.first() }
        runCurrent()

        viewModel.dispatch(TaskHomeAction.FixProvider("failed-task"))
        advanceUntilIdle()

        assertEquals(TaskHomeOneShot.OpenProvider("failed-task"), navigation.await())
    }

    @Test
    fun `exact attention marks read emits exact route then opens the same task best effort`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        database.p2Dao().upsertTask(task("task-attention", readState = "UNREAD"))
        val repository = TaskRepository(database, dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val opened = mutableListOf<String>()
        var readBeforeOpen = false
        val viewModel = TaskHomeViewModel(
            repository = repository,
            transportStatus = transport,
            synchronizeTaskListAction = {},
            retryConnectionAction = {},
            openTaskAction = { taskId ->
                readBeforeOpen = database.p2Dao().task(taskId)?.readState == "READ"
                opened += taskId
                error("Host open is best effort after local navigation")
            },
        )
        val navigation = async { viewModel.oneShots.first() }
        runCurrent()

        viewModel.dispatch(TaskHomeAction.OpenAttention("task-attention", "call-exact"))
        advanceUntilIdle()

        assertEquals(
            TaskHomeOneShot.OpenAttention("task-attention", "call-exact"),
            navigation.await(),
        )
        assertEquals(listOf("task-attention"), opened)
        assertTrue(readBeforeOpen)
        assertFalse(repository.observeTaskRows().first().single().isUnread)
    }

    @Test
    fun `exact attention survives a late navigation collector without replaying twice`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        database.p2Dao().upsertTask(task("task-late-attention", readState = "UNREAD"))
        val repository = TaskRepository(database, dispatcher)
        val opened = mutableListOf<String>()
        var readBeforeOpen = false
        val viewModel = TaskHomeViewModel(
            repository = repository,
            transportStatus = MutableStateFlow(
                SecureTransportUiStatus(SecureTransportUiPhase.READY),
            ),
            synchronizeTaskListAction = {},
            retryConnectionAction = {},
            openTaskAction = { taskId ->
                readBeforeOpen =
                    database.p2Dao().task(taskId)?.readState == "READ"
                opened += taskId
            },
        )

        viewModel.dispatch(
            TaskHomeAction.OpenAttention(
                "task-late-attention",
                "call-late-attention",
            ),
        )
        advanceUntilIdle()

        assertTrue(readBeforeOpen)
        assertEquals(listOf("task-late-attention"), opened)
        assertFalse(repository.observeTaskRows().first().single().isUnread)

        val navigation = async { viewModel.oneShots.first() }
        runCurrent()
        assertTrue(navigation.isCompleted)
        assertEquals(
            TaskHomeOneShot.OpenAttention(
                "task-late-attention",
                "call-late-attention",
            ),
            navigation.await(),
        )

        val duplicate = async { viewModel.oneShots.first() }
        runCurrent()
        assertFalse(duplicate.isCompleted)
        duplicate.cancel()
    }

    @Test
    fun `attention without an exact call falls back to detail and still opens the same task`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        database.p2Dao().upsertTask(task("task-detail", readState = "UNREAD"))
        val opened = mutableListOf<String>()
        val viewModel = TaskHomeViewModel(
            repository = TaskRepository(database, dispatcher),
            transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
            synchronizeTaskListAction = {},
            retryConnectionAction = {},
            openTaskAction = { opened += it },
        )
        val navigation = async { viewModel.oneShots.first() }
        runCurrent()

        viewModel.dispatch(TaskHomeAction.OpenAttention("task-detail", null))
        advanceUntilIdle()

        assertEquals(TaskHomeOneShot.OpenTask("task-detail", cached = false), navigation.await())
        assertEquals(listOf("task-detail"), opened)
    }

    @Test
    fun `ready transitions synchronize Host task list exactly once per transition`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val transport = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.CONNECTING))
        var synchronizations = 0
        val viewModel = TaskHomeViewModel(
            repository = TaskRepository(database, dispatcher),
            transportStatus = transport,
            synchronizeTaskListAction = { synchronizations += 1 },
            retryConnectionAction = {},
            openTaskAction = {},
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        runCurrent()

        transport.value = SecureTransportUiStatus(SecureTransportUiPhase.READY)
        advanceUntilIdle()
        assertEquals(1, synchronizations)

        transport.value = SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE)
        runCurrent()
        transport.value = SecureTransportUiStatus(SecureTransportUiPhase.READY)
        advanceUntilIdle()
        assertEquals(2, synchronizations)
    }

    @Test
    fun `production projection exposes reconnecting and cached open actions`() {
        val state = projectTaskHomeUiState(
            rows = listOf(
                TaskListRow(
                    taskId = "cached-1",
                    title = "Cached task",
                    runState = "COMPLETED",
                    recoveryState = "normal",
                    isUnread = true,
                    attentionKind = TaskAttentionKind.NONE,
                    primaryAttentionCallId = null,
                    primaryAttentionToolName = null,
                    attentionCount = 0,
                    updatedAtMillis = 1L,
                ),
            ),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.RECONNECTING, hostAlias = "Mac Studio"),
            query = "",
            searchVisible = false,
            loadState = TaskHomeLoadState.READY,
            nowMillis = 1L,
        )

        assertEquals(TaskHomeConnectionState.RECONNECTING, state.connection)
        assertEquals(TaskRowOpenAction.OPEN_CACHED_TASK, state.sections.single().rows.single().openAction)
    }

    @Test
    fun `exact attention action outranks cached while missing identity preserves fallback`() {
        data class Case(
            val name: String,
            val phase: SecureTransportUiPhase,
            val attentionKind: TaskAttentionKind,
            val callId: String?,
            val responseState: AttentionResponseState?,
            val expectedAction: TaskRowOpenAction,
        )

        val cases = listOf(
            Case(
                name = "ready task",
                phase = SecureTransportUiPhase.READY,
                attentionKind = TaskAttentionKind.NONE,
                callId = null,
                responseState = null,
                expectedAction = TaskRowOpenAction.OPEN_TASK,
            ),
            Case(
                name = "offline cached task",
                phase = SecureTransportUiPhase.OFFLINE,
                attentionKind = TaskAttentionKind.NONE,
                callId = null,
                responseState = null,
                expectedAction = TaskRowOpenAction.OPEN_CACHED_TASK,
            ),
            Case(
                name = "reconnecting cached task",
                phase = SecureTransportUiPhase.RECONNECTING,
                attentionKind = TaskAttentionKind.NONE,
                callId = null,
                responseState = null,
                expectedAction = TaskRowOpenAction.OPEN_CACHED_TASK,
            ),
            Case(
                name = "ready pending attention",
                phase = SecureTransportUiPhase.READY,
                attentionKind = TaskAttentionKind.QUESTION,
                callId = "call-ready-pending",
                responseState = AttentionResponseState.PENDING,
                expectedAction = TaskRowOpenAction.OPEN_ATTENTION,
            ),
            Case(
                name = "offline pending attention",
                phase = SecureTransportUiPhase.OFFLINE,
                attentionKind = TaskAttentionKind.CONFIRMATION,
                callId = "call-offline-pending",
                responseState = AttentionResponseState.PENDING,
                expectedAction = TaskRowOpenAction.OPEN_ATTENTION,
            ),
            Case(
                name = "reconnecting responding attention",
                phase = SecureTransportUiPhase.RECONNECTING,
                attentionKind = TaskAttentionKind.QUESTION,
                callId = "call-reconnecting-responding",
                responseState = AttentionResponseState.RESPONDING,
                expectedAction = TaskRowOpenAction.OPEN_ATTENTION,
            ),
            Case(
                name = "offline attention missing call",
                phase = SecureTransportUiPhase.OFFLINE,
                attentionKind = TaskAttentionKind.QUESTION,
                callId = null,
                responseState = AttentionResponseState.PENDING,
                expectedAction = TaskRowOpenAction.OPEN_CACHED_TASK,
            ),
            Case(
                name = "ready attention blank call",
                phase = SecureTransportUiPhase.READY,
                attentionKind = TaskAttentionKind.QUESTION,
                callId = "",
                responseState = AttentionResponseState.PENDING,
                expectedAction = TaskRowOpenAction.OPEN_TASK,
            ),
        )

        cases.forEach { case ->
            val state = projectTaskHomeUiState(
                rows = listOf(
                    TaskListRow(
                        taskId = "task-${case.name}",
                        title = case.name,
                        runState = "WAITING",
                        recoveryState = "normal",
                        isUnread = true,
                        attentionKind = case.attentionKind,
                        primaryAttentionCallId = case.callId,
                        primaryAttentionToolName = when (case.attentionKind) {
                            TaskAttentionKind.NONE -> null
                            TaskAttentionKind.CONFIRMATION ->
                                "request_user_confirmation"
                            else -> "request_user_question"
                        },
                        primaryAttentionResponseState = case.responseState,
                        attentionCount =
                            if (case.attentionKind == TaskAttentionKind.NONE) 0 else 1,
                        updatedAtMillis = 1L,
                    ),
                ),
                transport = SecureTransportUiStatus(case.phase),
                query = "",
                searchVisible = false,
                loadState = TaskHomeLoadState.READY,
                nowMillis = 1L,
            )
            val row = state.sections.single().rows.single()

            assertEquals(case.name, case.expectedAction, row.openAction)
            if (case.expectedAction == TaskRowOpenAction.OPEN_ATTENTION) {
                assertEquals("${case.name} exact call", case.callId, row.attentionCallId)
            }
        }
    }

    @Test
    fun `responding task row says response is saved instead of asking again`() {
        val state = projectTaskHomeUiState(
            rows = listOf(
                TaskListRow(
                    taskId = "responding-1",
                    title = "Responding task",
                    runState = "WAITING",
                    recoveryState = "normal",
                    isUnread = false,
                    attentionKind = TaskAttentionKind.QUESTION,
                    primaryAttentionCallId = "call-1",
                    primaryAttentionToolName = "request_user_question",
                    primaryAttentionResponseState = AttentionResponseState.RESPONDING,
                    attentionCount = 1,
                    updatedAtMillis = 1L,
                ),
            ),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY),
            query = "",
            searchVisible = false,
            loadState = TaskHomeLoadState.READY,
            nowMillis = 1L,
        )

        assertEquals(
            "Response saved; waiting for Momoding",
            state.sections.single().rows.single().detail,
        )
    }

    @Test
    fun `failed task row does not invent a Provider failure`() {
        val state = projectTaskHomeUiState(
            rows = listOf(taskRow("failed-1", "Failed task").copy(runState = "FAILED")),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY),
            query = "",
            searchVisible = false,
            loadState = TaskHomeLoadState.READY,
            nowMillis = 1L,
        )

        assertEquals(
            "Task failed — open for details",
            state.sections.single().rows.single().detail,
        )
        assertEquals(
            TaskRowRecoveryAction.OPEN_TASK,
            state.sections.single().rows.single().recoveryAction,
        )
    }

    @Test
    fun `typed Provider failure points to Provider recovery`() {
        val state = projectTaskHomeUiState(
            rows = listOf(
                taskRow("failed-provider", "Failed task").copy(
                    runState = "FAILED",
                    failure = TaskFailure(
                        TaskFailureKind.PROVIDER_AUTH,
                        "OpenRouter API key is invalid. Update it in Settings.",
                        TaskFailureRecovery.FIX_PROVIDER,
                    ),
                ),
            ),
            transport = SecureTransportUiStatus(SecureTransportUiPhase.READY),
            query = "",
            searchVisible = false,
            loadState = TaskHomeLoadState.READY,
            nowMillis = 1L,
        )

        val row = state.sections.single().rows.single()
        assertEquals("OpenRouter API key needs attention", row.detail)
        assertEquals(TaskRowRecoveryAction.FIX_PROVIDER, row.recoveryAction)
    }

    @Test
    fun `phone local projection separates pinned active and archived tasks`() {
        val rows = listOf(
            taskRow("pinned", "Pinned task", pinnedAtMillis = 30L),
            taskRow("recent", "Recent task"),
            taskRow("archived", "Archived task", archivedAtMillis = 40L),
        )
        val transport = SecureTransportUiStatus(SecureTransportUiPhase.READY)

        val active = projectTaskHomeUiState(
            rows = rows,
            transport = transport,
            controls = TaskHomeControls("", false, false, null, null),
            loadState = TaskHomeLoadState.READY,
            managementEnabled = true,
            nowMillis = 50L,
        )
        assertEquals(listOf("Pinned", "Recent"), active.sections.map { it.title })
        assertEquals(listOf("pinned"), active.sections[0].rows.map { it.taskId })
        assertEquals(listOf("recent"), active.sections[1].rows.map { it.taskId })
        assertTrue(active.managementEnabled)

        val archived = projectTaskHomeUiState(
            rows = rows,
            transport = transport,
            controls = TaskHomeControls("", false, true, null, null),
            loadState = TaskHomeLoadState.READY,
            managementEnabled = true,
            nowMillis = 50L,
        )
        assertEquals(listOf("Archived"), archived.sections.map { it.title })
        assertEquals(listOf("archived"), archived.sections.single().rows.map { it.taskId })
        assertTrue(archived.sections.single().rows.single().archived)
    }

    private fun taskRow(
        taskId: String,
        title: String,
        pinnedAtMillis: Long? = null,
        archivedAtMillis: Long? = null,
    ) = TaskListRow(
        taskId = taskId,
        title = title,
        runState = "COMPLETED",
        recoveryState = "normal",
        isUnread = false,
        attentionKind = TaskAttentionKind.NONE,
        primaryAttentionCallId = null,
        primaryAttentionToolName = null,
        attentionCount = 0,
        updatedAtMillis = 1L,
        pinnedAtMillis = pinnedAtMillis,
        archivedAtMillis = archivedAtMillis,
    )

    private fun task(taskId: String, readState: String) = TaskEntity(
        taskId = taskId,
        title = "Review the Android task shell",
        runState = "COMPLETED",
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
        updatedAtMillis = 1L,
        listedByHost = true,
        lastListSyncGeneration = null,
        lastListRevision = null,
        hostUpdatedAtMillis = 1L,
    )
}
