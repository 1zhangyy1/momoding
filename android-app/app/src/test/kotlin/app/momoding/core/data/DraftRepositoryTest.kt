package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.ArrayDeque
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.momoding.core.policy.TaskApprovalMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftRepositoryTest {
    private lateinit var database: MomodingDatabase
    private var clock = 1L

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
    fun `create observe flush and corrupt recovery preserve UTF-16 selection contract`() = runBlocking {
        val ids = ArrayDeque(listOf(CREATE_ID, PROMPT_ID))
        val repository = DraftRepository(
            database = database,
            nowMillis = { clock++ },
            commandIdFactory = { ids.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val created = repository.createDraft(
            draftId = "draft-selection",
            text = "A\uD83D\uDE00B",
            selectionStart = 1,
            selectionEnd = 3,
        )
        assertEquals(1, created.selectionStart)
        assertEquals(3, created.selectionEnd)
        assertEquals(created, repository.observeDraft("draft-selection").first())

        val repairedOnSave = repository.flushTextAndSelection(
            draftId = "draft-selection",
            text = "A\uD83D\uDE00B",
            selectionStart = 2,
            selectionEnd = 2,
        )
        assertEquals(0, repairedOnSave.selectionStart)
        assertEquals(0, repairedOnSave.selectionEnd)

        listOf(
            -1 to 0,
            3 to 2,
            0 to 5,
            2 to 3,
        ).forEach { (start, end) ->
            database.openHelper.writableDatabase.execSQL(
                "UPDATE drafts SET selectionStart = ?, selectionEnd = ? " +
                    "WHERE draftId = 'draft-selection'",
                arrayOf(start, end),
            )
            val repaired = repository.repairDraft("draft-selection")!!
            assertEquals(0, repaired.selectionStart)
            assertEquals(0, repaired.selectionEnd)
            val durable = database.momodingDao().draft("draft-selection")!!
            assertEquals(0, durable.selectionStart)
            assertEquals(0, durable.selectionEnd)
        }
    }

    @Test
    fun `retryable create rotation is atomic and preserves draft fields`() {
        val generated = ArrayDeque(listOf(RETRY_CREATE_ID, RETRY_PROMPT_ID))
        val journal = RoomCommandDraftJournal(
            database = database,
            commandIdFactory = { generated.removeFirst() },
            nowMillis = { clock++ },
        )
        journal.saveDraft(draft("create-failure", taskId = null))
        journal.persistAccepted(
            "create-request",
            CREATE_ID,
            "task.create",
            null,
            "{\"kind\":\"task.create\"}",
        )
        journal.markTerminal("create-request", retryableError("create-request"))

        val rotated = journal.rotateRetryableAttempt("create-failure", expectedOrdinal = 0)
        assertEquals(1L, rotated.attemptOrdinal)
        assertEquals(RETRY_CREATE_ID, rotated.createCommandId)
        assertEquals(RETRY_PROMPT_ID, rotated.promptCommandId)
        assertEquals("A\uD83D\uDE00B", rotated.text)
        assertEquals(1, rotated.selectionStart)
        assertEquals(3, rotated.selectionEnd)
        assertThrows(JournalConflictException::class.java) {
            journal.rotateRetryableAttempt("create-failure", expectedOrdinal = 0)
        }
    }

    @Test
    fun `retryable prompt rotates only prompt after matching create success`() {
        val journal = RoomCommandDraftJournal(
            database = database,
            commandIdFactory = { RETRY_PROMPT_ID },
            nowMillis = { clock++ },
        )
        journal.saveDraft(draft("prompt-failure", taskId = null))
        journal.persistAccepted(
            "create-request",
            CREATE_ID,
            "task.create",
            null,
            "{\"kind\":\"task.create\"}",
        )
        journal.markTerminal(
            "create-request",
            "{\"protocolVersion\":1,\"kind\":\"response\",\"requestId\":\"create-request\"," +
                "\"ok\":true,\"data\":{\"taskId\":\"$TASK_ID\",\"piSessionId\":\"$PI_ID\"}}",
        )
        journal.bindDraftTask("prompt-failure", TASK_ID)
        journal.persistAccepted(
            "prompt-request",
            PROMPT_ID,
            "session.prompt",
            TASK_ID,
            "{\"kind\":\"session.prompt\",\"taskId\":\"$TASK_ID\"}",
        )
        journal.markTerminal("prompt-request", retryableError("prompt-request"))

        val rotated = journal.rotateRetryableAttempt("prompt-failure", expectedOrdinal = 0)
        assertEquals(1L, rotated.attemptOrdinal)
        assertEquals(CREATE_ID, rotated.createCommandId)
        assertEquals(RETRY_PROMPT_ID, rotated.promptCommandId)
        assertEquals(TASK_ID, rotated.taskId)
    }

    @Test
    fun `selected mobile folder becomes immutable when the draft binds to a task`() = runBlocking {
        val ids = ArrayDeque(listOf(CREATE_ID, PROMPT_ID))
        val repository = DraftRepository(
            database = database,
            nowMillis = { clock++ },
            commandIdFactory = { ids.removeFirst() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        repository.createDraft("folder-draft")

        val selected = repository.selectAuthorizedFolder("folder-draft", GRANT_ID)
        assertEquals(GRANT_ID, selected.selectedGrantId)

        val journal = RoomCommandDraftJournal(database, nowMillis = { clock++ })
        val bound = journal.bindDraftTask("folder-draft", TASK_ID)
        assertEquals(GRANT_ID, bound.selectedGrantId)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.selectAuthorizedFolder("folder-draft", null)
            }
        }
        Unit
    }

    @Test
    fun `concurrent text and approval mode updates preserve both fields`() = runBlocking {
        val ids = ArrayDeque(listOf(CREATE_ID, PROMPT_ID))
        val repository = DraftRepository(
            database = database,
            nowMillis = { 10L },
            commandIdFactory = { ids.removeFirst() },
            ioDispatcher = Dispatchers.Default,
        )
        repository.createDraft("concurrent-draft")

        coroutineScope {
            val textUpdate = async {
                repository.flushTextAndSelection(
                    draftId = "concurrent-draft",
                    text = "keep this text",
                    selectionStart = 2,
                    selectionEnd = 9,
                )
            }
            val modeUpdate = async {
                repository.selectApprovalMode(
                    "concurrent-draft",
                    TaskApprovalMode.FULL_ACCESS,
                )
            }
            textUpdate.await()
            modeUpdate.await()
        }

        val durable = RoomCommandDraftJournal(database).draft("concurrent-draft")!!
        assertEquals("keep this text", durable.text)
        assertEquals(2, durable.selectionStart)
        assertEquals(9, durable.selectionEnd)
        assertEquals(TaskApprovalMode.FULL_ACCESS, durable.approvalMode)
    }

    private fun draft(draftId: String, taskId: String?) = DraftRecord(
        draftId = draftId,
        text = "A\uD83D\uDE00B",
        selectedHostId = "host",
        selectedModelId = "model",
        selectedMode = "ask-before-edits",
        createCommandId = CREATE_ID,
        promptCommandId = PROMPT_ID,
        taskId = taskId,
        updatedAtMillis = clock++,
        selectionStart = 1,
        selectionEnd = 3,
        attemptOrdinal = 0,
    )

    private fun retryableError(requestId: String) =
        "{\"protocolVersion\":1,\"kind\":\"response\",\"requestId\":\"$requestId\"," +
            "\"ok\":false,\"error\":{\"code\":\"DEVICE_OFFLINE\"," +
            "\"message\":\"fixture\",\"retryable\":true}}"

    private companion object {
        const val CREATE_ID = "11111111-1111-4111-8111-111111111111"
        const val PROMPT_ID = "22222222-2222-4222-8222-222222222222"
        const val RETRY_CREATE_ID = "33333333-3333-4333-8333-333333333333"
        const val RETRY_PROMPT_ID = "44444444-4444-4444-8444-444444444444"
        const val TASK_ID = "55555555-5555-4555-8555-555555555555"
        const val PI_ID = "66666666-6666-4666-8666-666666666666"
        const val GRANT_ID = "77777777-7777-4777-8777-777777777777"
    }
}
