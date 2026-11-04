package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.GuardKind
import app.momoding.guard.RiskAssessment
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskRequest
import com.openminis.app.MinisApp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit local-model fixture only: one owned file write, then real public task callbacks.
 * This is not an SMS, shell execution or AppBridge page acceptance test. */
class LateReceiptFixtureTest {
    @Test fun stopThenDeliverTheSameNativeReceiptTwice(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val session = args.getString("m41_late_session")
        assumeTrue(!session.isNullOrBlank())
        val tag = requireNotNull(args.getString("m41_late_tag"))
        require(tag.matches(Regex("m41-late-[a-f0-9]{10}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = (context.applicationContext as MinisApp).database
        val chat = requireNotNull(db.chatDao().getSession(requireNotNull(session)))
        require(chat.title == "M41 late receipt fixture")
        val spec = Approval.Spec(RiskRequest(id = tag, sessionId = session, kind = GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "owned callback fixture only", "fixture:local-file"),
            preview = "M41 fixture native effect", summary = "M41 late receipt fixture"),
            Approval.Mode.SHELL, """{"command":"printf M41_FIXTURE_ONLY","timeout":12}""")
        val action = TaskEngine.actionStarted(context, spec, authorization = "explicit owned emulator fixture")
        val counter = File(context.filesDir, "minis-sessions/$session/workspace/$tag/count")
        require(!counter.exists()); counter.parentFile!!.mkdirs(); counter.writeText("executed\n")
        TaskEngine.stop(context, action.taskId, "M41 fixture stopped after its local effect; return not yet observed.")
        TaskEngine.actionFinished(context, action, "return not yet observed", false, unknown = true)
        repeat(2) { TaskEngine.actionFinished(context, action, "M41_LATE_OBSERVED native accepted; delivery not independently verified", true) }
        val task = db.taskDao().task(action.taskId)!!
        val history = db.taskDao().log(task.id)
        assertEquals("STOPPED", task.outcome); assertEquals(1, history.count { it.kind == "END" })
        assertEquals(2, history.count { it.kind == "COMMAND_RESULT" }); assertEquals(2, history.count { it.kind == "RESULT" })
        assertEquals(1, history.count { it.kind == "REPORT" && it.body.contains("M41_LATE_OBSERVED") })
        assertEquals(listOf("executed"), counter.readLines())
        val evidence = JSONObject().put("task", task.id).put("counter", counter.readLines().size)
            .put("scope", "public callbacks and a fake local file effect; no real message was sent")
        File(context.filesDir, "$tag.json").writeText(evidence.toString())
    }
}
