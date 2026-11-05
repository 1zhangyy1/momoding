package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in crash boundary: actual handoff with a held callback. Only recovery executes the saved Linux read. */
class ReadRecoveryFixtureTest {
    @Test fun prepareOwnedUnreturnedCommand(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_read_session")
        assumeTrue(!sid.isNullOrBlank())
        val session = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(session)?.title?.startsWith("M41 read recovery") == true)
        val file = File(context.filesDir, "minis-sessions/$session/workspace/m41-read-owned.txt")
        file.parentFile?.mkdirs(); file.writeText("M41_READ_OWNED_BYTES")
        val text = when (InstrumentationRegistry.getArguments().getString("m41_read_mode")) {
            "http" -> "sleep 12; busybox wget -O - -q -T 10 http://127.0.0.1:18785/owned-read"
            "missing" -> "sleep 12; curl -q -fsS --max-time 10 http://127.0.0.1:18785/owned-read"
            else -> "sleep 12; cat /var/minis/workspace/m41-read-owned.txt"
        }
        val args = JSONObject().put("command", text).put("timeout", 41).put("delay", 2).put("tool_title", "M41 read recovery read")
        val input = app.chatRepository.appendMessage(session, "user", """[{"type":"text","value":"M41_READ_REQUEST"}]""")
        TaskEngine.dao(context).insertInbox(InboxEntity(input.id, session, sender = "USER", text = "M41_READ_REQUEST",
            arrivedAt = System.currentTimeMillis(), readAt = System.currentTimeMillis()))
        val id = "m41_read_call"
        val source = app.chatRepository.appendMessage(session, "assistant", JSONArray().put(JSONObject().put("type", "toolUse")
            .put("value", JSONObject().put("toolUseId", id).put("name", "shell_execute").put("input", args.toString()))).toString())
        val call = ToolCall(source.id, id, "shell_execute")
        val result = withContext(call) {
            ShellWork.run(context, session, null, "M41 read recovery read", text) { _, _ ->
                delay(60_000); ToolExecutionResult(file.readText(), true)
            }
        }
        assertTrue(result.success)
        val task = TaskEngine.dao(context).recentTasks(100).single { it.conversation == session }
        assertEquals("RUNNING", task.state)
        assertTrue(TaskEngine.dao(context).log(task.id).none { it.kind == "COMMAND_RESULT" })
        File(context.filesDir, "m41-read-proof.json").writeText(JSONObject().put("session", session).put("task", task.id)
            .put("source", source.id).put("input", input.id).put("call", id).put("args", args).toString())
    }

    @Test fun liveRecoveryGuards(): Unit = runBlocking {
        val sid = InstrumentationRegistry.getArguments().getString("m41_read_live_session")
        assumeTrue(!sid.isNullOrBlank())
        val session = requireNotNull(sid)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.chatRepository.getSession(session)?.title?.startsWith("M41 read recovery") == true)
        val dao = TaskEngine.dao(context)
        suspend fun pending(tag: String): ReadRecovery.Pending {
            val text = "cat /var/minis/workspace/m41-read-owned.txt"
            val args = JSONObject().put("command", text).put("timeout", 41).put("tool_title", "owned live recovery").toString()
            val source = app.chatRepository.appendMessage(session, "assistant", JSONArray().put(JSONObject().put("type", "toolUse")
                .put("value", JSONObject().put("toolUseId", tag).put("name", "shell_execute").put("input", args))).toString())
            val now = System.currentTimeMillis()
            val task = TaskEntity(java.util.UUID.randomUUID().toString(), session, title = tag, hand = session,
                state = "RUNNING", createdAt = now, updatedAt = now)
            dao.insertTask(task)
            val command = ShellWork.Command(tag, "$session~command~$tag", text, ToolCall(source.id, tag, "shell_execute"))
            TaskEngine.recordCommandIn(dao, task, command)
            return requireNotNull(ReadRecovery.pendingIn(dao, task, command))
        }
        suspend fun settled(read: ReadRecovery.Pending) = withTimeout(5_000) {
            while (dao.log(read.task.id).none { it.kind == "COMMAND_RESULT" }) delay(25)
        }
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val read = pending("live-dedupe")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val execute: suspend () -> ToolExecutionResult = { starts.incrementAndGet(); entered.complete(Unit); release.await(); ToolExecutionResult("owned observed bytes", true) }
        val handles = (1..8).map { async(Dispatchers.Default) { ShellWork.resume(context, read, execute) } }.awaitAll()
        assertTrue(handles.all { it === handles.first() }); entered.await(); assertEquals(1, starts.get())
        release.complete(Unit); handles.first().await(); settled(read)
        val known = ShellWork.resume(context, read) { starts.incrementAndGet(); ToolExecutionResult("must not run", true) }
        runCatching { known.await() }; delay(100)
        assertEquals(1, starts.get()); assertEquals(1, dao.log(read.task.id).count { it.kind == "COMMAND_RESULT" })

        val stopped = pending("live-stop")
        val running = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val handle = ShellWork.resume(context, stopped) {
            try { running.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        running.await(); TaskEngine.stop(context, stopped.task.id)
        withTimeout(5_000) { cancelled.await(); handle.join() }; settled(stopped)
        assertEquals("STOPPED", dao.task(stopped.task.id)?.outcome)
        assertTrue(dao.log(stopped.task.id).filter { it.kind == "COMMAND_RESULT" }.none { JSONObject(it.body).optBoolean("success") })

        val removed = pending("live-deleted-source")
        app.database.chatDao().deleteMessages(session)
        val stale = ShellWork.resume(context, removed) { starts.incrementAndGet(); ToolExecutionResult("must not run", true) }
        runCatching { stale.await() }; settled(removed)
        assertEquals(1, starts.get()); assertEquals("WAITING", dao.task(removed.task.id)?.state)
        File(context.filesDir, "m41-read-live-proof.json").writeText(JSONObject().put("starts", starts.get())
            .put("dedupe", true).put("known_result_skipped", true).put("stop_cancels", true).put("deleted_source_skipped", true).toString())
    }
}
