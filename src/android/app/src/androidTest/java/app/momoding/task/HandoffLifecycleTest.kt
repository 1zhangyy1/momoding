package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Fault the real task INSERT: its new hidden helper chat must roll back with it. */
class HandoffLifecycleTest {
    @Test fun aFailedHandoffLeavesNoHelperChatOrInbox(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25); app.providerRepository.awaitConfigLoaded() }
        TaskEngine.awaitRecovery()
        val model = app.providerRepository.config.value.modelEntries.first().model.id
        val parent = app.chatRepository.createSession(model, "M41 atomic helper fixture").id
        val sql = app.database.openHelper.writableDatabase
        val trigger = "m41_handoff_abort_" + parent.replace("-", "")
        try {
            sql.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON task WHEN NEW.conversation = '$parent' BEGIN SELECT RAISE(ABORT, 'M41 injected task insert failure'); END")
            val result = runCatching { TaskEngine.openHelper(app, parent, "M41 atomic fixture", "fixture only; never starts") }
            assertTrue(result.isFailure)
            val error = generateSequence(result.exceptionOrNull()) { it.cause }.joinToString("\n") { it.message.orEmpty() }
            assertTrue(error, error.contains("M41 injected task insert failure"))
            assertTrue(app.database.chatDao().childSessionIds(parent).isEmpty())
            assertTrue(app.database.taskDao().openIn(parent).isEmpty())
            assertTrue(app.database.taskDao().inboxFor(parent).isEmpty())
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS $trigger")
            app.database.chatDao().deleteSession(parent)
        }
    }
}
