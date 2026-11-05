package app.momoding.task

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Old journal observations need not contain a complete executable approval recipe. */
class JournalRecoveryTest {
    private lateinit var db: AppDatabase
    private lateinit var task: TaskEntity
    private val dao get() = db.taskDao()
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        val chat = ChatRepository(db.chatDao()).createSession("fixture", "journal recovery").id
        task = TaskEntity("legacy-work", chat, title = "legacy fixture", hand = chat, state = "RUNNING", createdAt = 1, updatedAt = 1)
        dao.insertTask(task)
    }
    @After fun close() = db.close()
    private suspend fun mark(kind: String, body: String): TaskLogEntity {
        dao.insertLog(TaskLogEntity(taskId = task.id, at = 2, kind = kind, body = body))
        return dao.log(task.id).last()
    }
    private suspend fun recoverAction() = db.withTransaction { TaskEngine.recoverActionsIn(dao, requireNotNull(dao.task(task.id))) }
    private suspend fun assertUnknown(kind: String, id: String) {
        val result = JSONObject(dao.log(task.id).single { it.kind == kind }.body)
        assertEquals(id, result.getString("id")); assertTrue(result.getBoolean("unknown")); assertFalse(result.has("success"))
        assertEquals("WAITING", dao.task(task.id)?.state); assertNotNull(dao.task(task.id)?.ask)
        assertTrue(dao.log(task.id).none { it.kind == "END" })
    }

    @Test fun aMinimalActionBecomesUnknownOnceWithoutRebuildingItsRecipe(): Unit = runBlocking {
        val row = mark("ABOUT_TO", """{"id":"native","preview":"check fixture recipient"}""")
        assertNotNull(recoverAction()); assertUnknown("RESULT", "native")
        assertEquals(row, dao.log(task.id).first()); val before = dao.log(task.id)
        assertNull(recoverAction()); assertEquals(before, dao.log(task.id))
    }
    @Test fun aKnownResultDoesNotRequireAParsableApprovalRecipe(): Unit = runBlocking {
        mark("ABOUT_TO", """{"id":"native"}"""); val result = mark("RESULT", """{"id":"native","success":true,"output":"observed"}""")
        assertNull(recoverAction()); assertNull(dao.task(task.id)?.ask)
        assertEquals(result, dao.log(task.id).last())
    }
    @Test fun unreadableActionsRetainAStableJournalIdentity(): Unit = runBlocking {
        val row = mark("ABOUT_TO", "{interrupted JSON")
        assertTrue(TaskEngine.pendingWork(dao, task.id)); assertNotNull(recoverAction())
        assertUnknown("RESULT", "log:${row.id}"); assertEquals(row, dao.log(task.id).first()); assertNull(recoverAction())
    }
    @Test fun anActionWithoutAnIdDoesNotDisappear(): Unit = runBlocking {
        val row = mark("ABOUT_TO", """{"preview":"legacy effect"}""")
        assertNotNull(recoverAction()); assertUnknown("RESULT", "log:${row.id}")
    }
    @Test fun anIdAloneIsNotARecordedOutcome(): Unit = runBlocking {
        mark("ABOUT_TO", """{"id":"partial-result"}"""); mark("RESULT", """{"id":"partial-result"}""")
        assertNotNull(recoverAction()); assertEquals(2, dao.log(task.id).count { it.kind == "RESULT" })
        assertTrue(JSONObject(dao.log(task.id).last { it.kind == "RESULT" }.body).getBoolean("unknown"))
        assertEquals("WAITING", dao.task(task.id)?.state)
    }
    @Test fun unknownAndAskRollBackTogether(): Unit = runBlocking {
        mark("ABOUT_TO", """{"id":"native"}""")
        assertTrue(runCatching { db.withTransaction { TaskEngine.recoverActionsIn(dao, task); error("before commit") } }.isFailure)
        assertNull(dao.task(task.id)?.ask); assertEquals(1, dao.log(task.id).size)
        assertNotNull(recoverAction()); assertUnknown("RESULT", "native")
    }
    @Test fun aStopSnapshotDoesNotDropAnUnreadableCommand(): Unit = runBlocking {
        val row = mark("COMMAND", "{broken command")
        val snapshot = JSONObject(TaskReceipt.stopped(dao, task).substringAfter("Stop snapshot:\n"))
        val command = snapshot.getJSONArray("other_commands").getJSONObject(0)
        assertEquals("log:${row.id}", command.getString("id")); assertEquals("unknown", command.getString("return"))
    }

    private suspend fun invocation(id: String, input: String = "{}"): String = ChatRepository(db.chatDao()).appendMessage(task.conversation, "assistant",
        """[{"type":"toolUse","value":{"toolUseId":"$id","name":"shell_execute","input":${JSONObject.quote(input)}}}]""").id

}
