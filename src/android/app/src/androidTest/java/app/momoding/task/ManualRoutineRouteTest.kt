package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.apps.MoApps
import com.openminis.app.MinisApp
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real handler rejects a misattributed helper before any child, task or input can be created. */
class ManualRoutineRouteTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
    private lateinit var owner: String
    private lateinit var other: String
    private lateinit var helper: String
    private lateinit var routine: String
    private var bot: MoApps.App? = null
    @Before fun setup(): Unit = runBlocking {
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }; TaskEngine.awaitRecovery()
        owner = app.chatRepository.createSession("fixture", "M41 manual route owner").id
        other = app.chatRepository.createSession("fixture", "M41 manual route other").id
        helper = app.chatRepository.createSession("fixture", "M41 manual route helper", parentSessionId = owner).id
        routine = java.util.UUID.randomUUID().toString()
        ScheduledTaskManager(app).create(ScheduledTask.fromJson(JSONObject().put("id", routine).put("label", "owned manual route")
            .put("prompt", "never dispatched").put("enabled", false).put("targetMode", "CHILD_OF:$owner")))
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER m41_manual_route_abort_${owner.replace("-", "")} BEFORE INSERT ON inbox WHEN NEW.sender = 'ROUTINE' AND instr(NEW.text, '$routine') > 0 BEGIN SELECT RAISE(ABORT, 'owned route must not dispatch'); END")
    }
    @After fun close(): Unit = runBlocking {
        ScheduledTaskManager(app).delete(routine)
        bot?.let(MoApps::remove)
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS m41_manual_route_abort_${owner.replace("-", "")}")
        app.chatRepository.deleteSession(owner); app.chatRepository.deleteSession(other)
    }
    private fun run(caller: String?, id: String = routine, env: Map<String, String> = emptyMap()) = ScheduledTaskOffloadHandler(app)
        .handle(NativeOffloadRequest(0, listOf("minis-scheduled", "run", "--id", id), env, "/", caller))
    private suspend fun assertUntouched() {
        assertEquals(listOf(helper), app.database.chatDao().childSessionIds(owner))
        assertTrue(app.database.taskDao().relatedTo(listOf(owner, other, helper)).isEmpty())
        assertTrue(app.database.taskDao().inboxFor(owner).isEmpty())
    }
    @Test fun anotherChatCannotStartTheStoredOwnersHelper(): Unit = runBlocking {
        val result = run(other); assertEquals(2, result.exitCode); assertTrue(result.output.contains("owning chat")); assertUntouched()
    }
    @Test fun aHelperCannotLaunchAnotherHandInItsParentsChat(): Unit = runBlocking {
        val result = run(helper); assertEquals(1, result.exitCode); assertTrue(result.output.contains("A helper hands no work on")); assertUntouched()
    }
    @Test fun aHelperCannotStartAnIndependentMainConversation(): Unit = runBlocking {
        val manager = ScheduledTaskManager(app); manager.store().upsert(ScheduledTask.fromJson(manager.get(routine)!!.toJson().put("targetMode", "NEW_SESSION")))
        val result = run(helper); assertEquals(1, result.exitCode); assertTrue(result.output.contains("A helper hands no work on")); assertUntouched()
    }
    @Test fun aHelperCannotSendAnotherConversationAnUnboundRoutineInput(): Unit = runBlocking {
        val manager = ScheduledTaskManager(app); manager.store().upsert(ScheduledTask.fromJson(manager.get(routine)!!.toJson().put("targetMode", "APPEND_TO:$owner")))
        val result = run(helper); assertEquals(1, result.exitCode); assertTrue(result.output.contains("A helper hands no work on")); assertUntouched()
    }
    @Test fun anAppsMomodingCannotStartAnotherHand(): Unit = runBlocking {
        bot = MoApps.create(app, "m41-route-" + owner.take(8), "M41 owned manual route").also { it.setBot(other) }
        val result = run(other); assertEquals(1, result.exitCode); assertTrue(result.output.contains("An app's Momoding hands no work on")); assertUntouched()
    }
    @Test fun aMissingInvocationDoesNotBorrowTheOwnersLatestMessage(): Unit = runBlocking {
        app.chatRepository.appendMessage(owner, "user", """[{"type":"text","value":"newer unrelated words"}]""")
        val result = run(owner); assertEquals(2, result.exitCode); assertTrue(result.output.contains("original tool invocation")); assertUntouched()
    }
    @Test fun anUnknownExecutionIdDoesNotBecomeAnUnboundFreshCall(): Unit = runBlocking {
        val result = run(owner, env = mapOf("MINIS_EXECUTION_ID" to "missing owned execution"))
        assertEquals(2, result.exitCode); assertTrue(result.output.contains("original tool invocation")); assertUntouched()
    }
    @Test fun aMissingDefinitionDoesNotCreateAReplacement(): Unit = runBlocking {
        val result = run(owner, "missing owned routine"); assertEquals(1, result.exitCode); assertTrue(result.output.contains("no task")); assertUntouched()
    }
}
