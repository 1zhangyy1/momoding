package app.momoding.task

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.chat.InputContinuation
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.scheduled.*
import com.openminis.app.ui.chat.InputAttachment
import com.openminis.app.ui.chat.addAttachment
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in public ViewModel/scheduler fixture. Owns only its supplied M41 session and temporary schedule. */
class HistoryMutationFixtureTest {
    @Test fun exerciseOwnedHistory(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val sid = args.getString("m41_history_session")
        assumeTrue(!sid.isNullOrBlank())
        val session = requireNotNull(sid); val mode = requireNotNull(args.getString("m41_history_mode"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        require(app.database.chatDao().getSession(session)?.title?.startsWith("M41 history") == true)
        val vm = withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, session) }
        withTimeout(15_000) { while (vm.activeEntryId.value == null || vm.messages.value.isEmpty()) delay(25) }
        val file = File(context.filesDir, "minis-sessions/$session/workspace/m41-history-owned.txt")
        if (mode == "prepare_file") {
            file.parentFile?.mkdirs(); file.writeText("M41_KEEP"); return@runBlocking
        }
        if (mode == "image") {
            val imageFile = File(context.filesDir, "m41-history-owned-image.png")
            try {
                val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                imageFile.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                withContext(Dispatchers.Main) {
                    vm.addAttachment(InputAttachment(fileName = "fixture.png", uri = Uri.fromFile(imageFile), mimeType = "image/png", kind = InputAttachment.Kind.IMAGE))
                    val paste = vm.stashPastedText("M41_HISTORY_PASTE_PAYLOAD")
                    vm.sendMessage("M41_HISTORY_IMAGE_BEFORE $paste M41_HISTORY_IMAGE_AFTER")
                }
                withTimeout(15_000) {
                    while (app.database.chatDao().loadMessages(session).none { it.partsJson.contains("M41_HISTORY_IMAGE_BEFORE") }) delay(25)
                    while (vm.turnIsRunning) delay(25)
                }
            } finally { imageFile.delete() }
            return@runBlocking
        }
        val before = app.database.chatDao().loadMessages(session)
        if (mode == "retry_inspect") {
            val source = requireNotNull(args.getString("m41_history_source"))
            val continuations = before.filter { InputContinuation.source(it.partsJson) == source }
            assertTrue("Retry must retain its own model context", continuations.isNotEmpty())
            assertEquals(1, vm.messages.value.count { it.id == source })
            assertTrue("Context records must not duplicate the user's bubble", vm.messages.value.none { shown -> continuations.any { it.id == shown.id } })
            assertEquals("Only the retried input's errors should disappear", args.getString("m41_history_expected_errors")?.toInt() ?: 0,
                vm.messages.value.count { it.error != null })
            assertEquals(before.size, before.map { it.id }.distinct().size)
            return@runBlocking
        }
        if (mode.startsWith("repeat")) {
            val source = requireNotNull(args.getString("m41_history_source"))
            val task = ScheduledTask(id = "m41-history-" + UUID.randomUUID(), label = "M41 history saved input",
                timeOfDayHour = 0, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.ONCE,
                prompt = "M41_HISTORY_IGNORED_PROMPT", targetMode = ScheduledTargetMode.RerunMessage(session, if (mode == "repeat_missing") "missing" else source), enabled = false)
            val store = ScheduledTaskStore(context)
            val backups = mutableListOf<Pair<File, ByteArray>>()
            store.upsert(task)
            try {
                if (mode == "repeat_media_missing") {
                    val row = requireNotNull(app.database.chatDao().message(source)); val parts = JSONArray(row.partsJson)
                    val ref = (0 until parts.length()).map { parts.getJSONObject(it) }.first { it.getString("type") == "mediaRef" }
                    val media = File(context.filesDir, "media/" + ref.getJSONObject("value").getString("relativePath"))
                    require(media.canonicalPath.contains("/$session/")); backups += media to media.readBytes(); assertTrue(media.delete())
                }
                if (mode == "repeat_busy") {
                    withContext(Dispatchers.Main) { vm.sendMessage("M41_HISTORY_BUSY_TOOL") }
                    withTimeout(15_000) {
                        while (!vm.turnIsRunning || app.database.chatDao().loadMessages(session).none {
                            it.role == "assistant" && it.partsJson.contains("M41_HISTORY_BOUNDARY")
                        }) delay(25)
                    }
                }
                val rounds = if (mode in listOf("repeat", "repeat_busy")) 2 else 1
                repeat(rounds) {
                    assertEquals(session, ScheduledAgentRunner.run(context, task))
                    val now = app.database.chatDao().loadMessages(session).associateBy { it.id }
                    for (old in before) assertEquals("Saved-message schedules keep all history", old.partsJson, now[old.id]?.partsJson)
                }
                val stored = requireNotNull(store.get(task.id))
                assertEquals(rounds, stored.runHistory.size)
                assertEquals(rounds, stored.fireCount)
                val expectedOk = mode in listOf("repeat", "repeat_busy")
                assertTrue(stored.runHistory.all { it.ok == expectedOk })
                val received = app.database.chatDao().loadMessages(session).filter {
                    HeadlessChatRunner.extractText(it.partsJson)?.let(ScheduledTaskMarker::parse)?.taskId == task.id
                }
                assertEquals(if (expectedOk) rounds else 0, received.size)
                if (expectedOk) {
                    val original = JSONArray(requireNotNull(app.database.chatDao().message(source)).partsJson)
                    fun refs(parts: JSONArray) = (0 until parts.length()).map { parts.getJSONObject(it) }
                        .filter { it.getString("type") == "mediaRef" }.map { it.toString() }
                    assertTrue(refs(original).isNotEmpty())
                    for (row in received) assertEquals(refs(original), refs(JSONArray(row.partsJson)))
                    assertEquals(rounds, received.map { it.id }.distinct().size)
                }
            } finally {
                backups.forEach { (media, bytes) -> media.writeBytes(bytes) }
                store.delete(task.id)
                check(context.getSharedPreferences("minis_scheduled_tasks_prefs", android.content.Context.MODE_PRIVATE).edit().commit())
            }
            return@runBlocking
        }
        require(mode in listOf("clear", "clear_pending", "clear_send", "clear_active_hand"))
        if (mode == "clear_active_hand") withTimeout(15_000) {
            while (vm.activeTaskId != args.getString("m41_history_task") || !vm.turnIsRunning) delay(25)
        }
        if (!file.exists()) { file.parentFile?.mkdirs(); file.writeText("M41_KEEP") }
        val priorInbox = app.database.taskDao().inboxFor(session)
        if (mode == "clear_pending") {
            val row = priorInbox.first { it.text == "M41_HISTORY_PENDING" }
            val prompt = Inbox.rows(context, session).single { it.id == row.id }
            withContext(Dispatchers.Main) {
                vm.javaClass.getDeclaredField("pendingSendPrompt").apply { isAccessible = true }.set(vm, prompt)
                vm.clearChat()
                vm.sendPendingWithoutCompacting()
            }
        } else withContext(Dispatchers.Main) {
            vm.clearChat()
            if (mode == "clear_send") vm.sendMessage("M41_HISTORY_AFTER")
        }
        val oldIds = (before.map { it.id } + priorInbox.map { it.id }).toSet()
        withTimeout(15_000) {
            while (app.database.chatDao().loadMessages(session).any { it.id in oldIds } || vm.turnIsRunning) delay(25)
            if (mode == "clear_send") while (app.database.chatDao().loadMessages(session).none { it.partsJson.contains("M41_HISTORY_AFTER") }) delay(25)
        }
        delay(400)
        args.getString("m41_history_task")?.let { assertEquals("Clearing history must stop its work", "STOPPED", app.database.taskDao().task(it)?.outcome) }
        assertTrue("Old input cannot return after a clear", app.database.chatDao().loadMessages(session).none { it.id in oldIds })
        assertTrue(app.database.taskDao().inboxFor(session).none { it.id in oldIds })
        assertTrue(vm.messages.value.none { it.id.substringBefore('#') in oldIds || it.sourceDbIds.any(oldIds::contains) })
        assertNotNull(app.database.chatDao().getSession(session)); assertTrue(app.database.chatDao().listCompactMarkers(session).isEmpty())
        assertEquals("M41_KEEP", file.readText())
        if (mode == "clear_send") assertEquals(1, app.database.chatDao().loadMessages(session).count { it.role == "user" && it.partsJson.contains("M41_HISTORY_AFTER") })
    }
}
