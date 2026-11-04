package app.momoding.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.chat.Drafts
import com.openminis.app.MinisApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A fake carrier boundary. Never requests SEND_SMS or calls Android's SMS service. */
@RunWith(AndroidJUnit4::class)
class DraftSendJournalTest {
    @Test fun exactParametersAreOnDiskBeforeTheCarrierDelegateAndDuplicateTapsNeverSendTwice(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }
        val chat = app.chatRepository.createSession("M41 fake carrier fixture").id
        val d = Drafts.Draft("Fixture recipient", "SMS", "original text", "+15550000000")
        var calls = 0
        try {
            val result = Drafts.sendApprovedSms(app, d, d.number, "exact edited text", chat, "fixture-card") { number, text ->
                calls++
                assertEquals(d.number, number); assertEquals("exact edited text", text)
                runBlocking {
                    val task = app.database.taskDao().draftTask(chat, "fixture-card", Drafts.needle(d.id))!!
                    val history = app.database.taskDao().log(task.id)
                    assertEquals(1, history.count { it.kind == "ABOUT_TO" })
                    assertTrue(history.none { it.kind == "RESULT" })
                    val saved = JSONObject(history.single { it.kind == "DRAFT" }.body)
                    assertEquals(number, saved.getString("number")); assertEquals(text, saved.getString("text"))
                }
                true
            }
            assertEquals(Drafts.Status.SENT, result.status)
            assertTrue(runCatching { Drafts.sendApprovedSms(app, d, d.number, "changed text", chat, "fixture-card") { _, _ -> calls++; true } }.isFailure)
            assertEquals(1, calls)
            val task = app.database.taskDao().draftTask(chat, "fixture-card", Drafts.needle(d.id))!!
            val history = app.database.taskDao().log(task.id)
            assertEquals("DONE", task.outcome)
            assertEquals(1, history.count { it.kind == "RESULT" })
            assertEquals(1, history.count { it.kind == "END" })
        } finally { app.database.chatDao().deleteSession(chat) }
    }
}
