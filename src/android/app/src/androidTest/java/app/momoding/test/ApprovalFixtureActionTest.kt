package app.momoding.test

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.guard.RiskDecision
import app.momoding.guard.RiskGate
import app.momoding.task.Approval
import com.openminis.app.MinisApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit stale-button probe, limited to an ended, isolated local fixture. */
@RunWith(AndroidJUnit4::class)
class ApprovalFixtureActionTest {
    @Test fun replayAnExpiredFixtureButton() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val taskId = args.getString("m41_approval_task")
        val requestId = args.getString("m41_approval_request")
        assumeTrue(taskId != null && requestId != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        withTimeout(15_000) { while (!app.subsystemsReady()) delay(25) }
        val dao = app.database.taskDao()
        val task = requireNotNull(dao.task(taskId!!))
        require(task.state == "ENDED") { "only expired fixture buttons are allowed here" }
        val spec = dao.log(task.id).filter { it.kind == "APPROVAL" }.map { Approval.Spec.parse(JSONObject(it.body)) }
            .single { it.request.id == requestId }
        require(spec.mode == Approval.Mode.SHELL && spec.request.preview.startsWith("rm -rf /var/minis/workspace/m41-approval-fixture-"))
        val owner = requireNotNull(app.chatRepository.getSession(spec.request.conversation ?: task.conversation))
        require(owner.source == "debug" && owner.title.orEmpty().startsWith("M41 approval fixture"))
        val before = dao.log(task.id).count { it.kind == "COMMAND" }
        RiskGate.decide(requestId!!, RiskDecision.ALLOW_ONCE).join()
        assertEquals(task.state, dao.task(task.id)!!.state)
        assertEquals(before, dao.log(task.id).count { it.kind == "COMMAND" })
    }
}
