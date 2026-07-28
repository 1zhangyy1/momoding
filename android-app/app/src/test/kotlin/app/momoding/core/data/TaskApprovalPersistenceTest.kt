package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.TaskRunState
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskApprovalPersistenceTest {
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
    fun `new draft and new local task default to request approval`() = runBlocking {
        val draft = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
            .createDraft(DRAFT_ID)
        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, draft.approvalMode)

        projector().createTask(TASK_ID, "Task", SESSION_ID, STREAM_ID, "Prompt")
        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, database.p2Dao().task(TASK_ID)?.approvalMode)
    }

    @Test
    fun `binding a draft copies its exact mode to an existing phone-local task and preserves it`() =
        runBlocking {
            val drafts = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
            drafts.createDraft(DRAFT_ID)
            drafts.selectApprovalMode(DRAFT_ID, TaskApprovalMode.FULL_ACCESS)
            projector().createTask(TASK_ID, "Task", SESSION_ID, STREAM_ID, "Prompt")

            val bound = RoomCommandDraftJournal(database).bindDraftTask(DRAFT_ID, TASK_ID)
            assertEquals(TaskApprovalMode.FULL_ACCESS, bound.approvalMode)
            assertEquals(TaskApprovalMode.FULL_ACCESS, database.p2Dao().task(TASK_ID)?.approvalMode)

            projector().markRunState(TASK_ID, TaskRunState.RUNNING, isStreaming = true)
            assertEquals(TaskApprovalMode.FULL_ACCESS, database.p2Dao().task(TASK_ID)?.approvalMode)

            assertThrows(JournalConflictException::class.java) {
                RoomCommandDraftJournal(database).saveDraft(
                    bound.copy(approvalMode = TaskApprovalMode.AUTO_APPROVE),
                )
            }
            Unit
        }

    @Test
    fun `same binding retry preserves a later task downgrade and another draft cannot claim it`() =
        runBlocking {
            val drafts = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
            drafts.createDraft(DRAFT_ID)
            drafts.selectApprovalMode(DRAFT_ID, TaskApprovalMode.FULL_ACCESS)
            projector().createTask(TASK_ID, "Task", SESSION_ID, STREAM_ID, "Prompt")

            val journal = RoomCommandDraftJournal(database)
            journal.bindDraftTask(DRAFT_ID, TASK_ID)
            assertEquals(1, database.p2Dao().updateTaskApprovalMode(TASK_ID, TaskApprovalMode.REQUEST_APPROVAL))

            journal.bindDraftTask(DRAFT_ID, TASK_ID)
            assertEquals(TaskApprovalMode.REQUEST_APPROVAL, database.p2Dao().task(TASK_ID)?.approvalMode)

            drafts.createDraft(OTHER_DRAFT_ID)
            assertThrows(JournalConflictException::class.java) {
                journal.bindDraftTask(OTHER_DRAFT_ID, TASK_ID)
            }
            Unit
        }

    @Test
    fun `save draft cannot bypass the one-shot binding transaction`() = runBlocking {
        val drafts = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
        val unbound = drafts.createDraft(DRAFT_ID)
        val journal = RoomCommandDraftJournal(database)

        assertThrows(JournalConflictException::class.java) {
            journal.saveDraft(unbound.copy(taskId = TASK_ID))
        }
        assertEquals(null, journal.draft(DRAFT_ID)?.taskId)
    }

    @Test
    fun `a task projected after draft binding inherits the exact draft mode`() = runBlocking {
        val drafts = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
        drafts.createDraft(DRAFT_ID)
        drafts.selectApprovalMode(DRAFT_ID, TaskApprovalMode.AUTO_APPROVE)
        RoomCommandDraftJournal(database).bindDraftTask(DRAFT_ID, TASK_ID)

        RoomTaskListMerger(database, nowMillis = { 100L }, generationFactory = { GENERATION })
            .mergeCompleteList(
                listRevision = 1,
                summaries = listOf(
                    HostTaskSummary(
                        taskId = TASK_ID,
                        title = "Projected task",
                        hostUpdatedAtMillis = 99,
                        runState = "idle",
                        recoveryState = "normal",
                        snapshotVersion = 1,
                    ),
                ),
            )

        assertEquals(TaskApprovalMode.AUTO_APPROVE, database.p2Dao().task(TASK_ID)?.approvalMode)
    }

    @Test
    fun `unknown persisted task and draft modes are read as request approval`() = runBlocking {
        val drafts = DraftRepository(database, ioDispatcher = Dispatchers.Unconfined)
        drafts.createDraft(DRAFT_ID)
        projector().createTask(TASK_ID, "Task", SESSION_ID, STREAM_ID, "Prompt")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE tasks SET approvalMode = 'FUTURE_MODE' WHERE taskId = '$TASK_ID'",
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE drafts SET approvalMode = 'FUTURE_MODE' WHERE draftId = '$DRAFT_ID'",
        )

        assertEquals(TaskApprovalMode.REQUEST_APPROVAL, database.p2Dao().task(TASK_ID)?.approvalMode)
        assertEquals(
            TaskApprovalMode.REQUEST_APPROVAL,
            RoomCommandDraftJournal(database).draft(DRAFT_ID)?.approvalMode,
        )
    }

    private fun projector() = PhoneLocalPiEventProjector(
        database = database,
        emittedAt = { "2026-07-22T00:00:00.000Z" },
        nowMillis = { 100L },
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val DRAFT_ID = "draft-pwr-1a"
        const val OTHER_DRAFT_ID = "draft-pwr-1a-other"
        const val SESSION_ID = "session-pwr-1a"
        const val STREAM_ID = "stream-pwr-1a"
        const val GENERATION = "22222222-2222-4222-8222-222222222222"
    }
}
