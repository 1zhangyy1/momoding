package app.momoding.guard

import app.momoding.task.Approval
import app.momoding.task.TaskLogEntity
import com.openminis.app.scheduled.PrefilledToolCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The persisted contract replaces the old continuation/timeout queue. */
class RiskGateTest {
    private fun spec(id: String = "request") = Approval.Spec(RiskRequest(id = id, taskId = "task", sessionId = "actor",
        kind = GuardKind.SHELL, assessment = RiskAssessment(RiskClass.DESTRUCTIVE, "removes a fixture", "/tmp/fixture", listOf("check first")),
        preview = "rm /tmp/fixture", pageUrl = "fixture app", elementText = "Delete", summary = "Delete only the fixture", createdAt = 123),
        Approval.Mode.SHELL, """{"command":"rm /tmp/fixture","timeout":37,"delay":2,"tool_title":"fixture"}""")

    @Test fun completeApprovalSurvivesRestartWithoutRebuildingItsParameters() {
        val original = spec()
        val restored = Approval.Spec.parse(JSONObject(original.json().toString()))
        assertEquals(original.request, restored.request)
        assertEquals(original.mode, restored.mode)
        assertEquals(Approval.shellSignature(original.shellArgs!!), Approval.shellSignature(restored.shellArgs!!))
        assertEquals("task:request", restored.ticket)
    }

    @Test fun optionalFieldsStayAbsent() {
        val original = Approval.Spec(RiskRequest(sessionId = "actor", kind = GuardKind.SCREEN,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "sends"), preview = "Send"), Approval.Mode.SCREEN)
        val restored = Approval.Spec.parse(JSONObject(original.json().toString()))
        assertEquals(original, restored)
        assertNull(restored.request.taskId)
        assertNull(restored.shellArgs)
    }

    @Test fun allParametersBindButObjectKeyOrderAndTheInternalTicketDoNot() {
        val original = """{"command":"rm /tmp/fixture","timeout":37,"delay":2,"tool_title":"fixture"}"""
        val reordered = """{"_momoding_approval":"task:request","delay":2,"command":"rm /tmp/fixture","tool_title":"fixture","timeout":37}"""
        assertEquals(Approval.shellSignature(original), Approval.shellSignature(reordered))
        for (changed in listOf(
            """{"command":"rm /tmp/other","timeout":37,"delay":2,"tool_title":"fixture"}""",
            """{"command":"rm /tmp/fixture","timeout":38,"delay":2,"tool_title":"fixture"}""",
            """{"command":"rm /tmp/fixture","timeout":37,"delay":3,"tool_title":"fixture"}""",
            """{"command":"rm /tmp/fixture","timeout":37,"delay":2,"tool_title":"fixture","extra":true}""",
        )) assertNotEquals(Approval.shellSignature(original), Approval.shellSignature(changed))
    }

    @Test fun aScreenPermissionDoesNotMoveToAnotherTaskAppOrTarget() {
        val original = spec().copy(mode = Approval.Mode.SCREEN, shellArgs = null)
        assertTrue(original.sameAction(original.request.copy(id = "new-proposal", summary = "different explanation")))
        assertFalse(original.sameAction(original.request.copy(taskId = "next")))
        assertFalse(original.sameAction(original.request.copy(pageUrl = "other app")))
        assertFalse(original.sameAction(original.request.copy(elementText = "Delete all")))
        assertFalse(original.sameAction(original.request.copy(sessionId = "other actor")))
    }

    @Test fun multipleCardsSurviveInOneTaskAsk() {
        val requests = listOf(spec("one"), spec("two").copy(mode = Approval.Mode.SCREEN))
        val ask = Approval.ask(requests)!!
        assertEquals(requests.map { it.request }, Approval.pending(ask).map { it.request })
        assertEquals("task", JSONObject(ask).getString("screen"))
        assertTrue(Approval.pending("""{"kind":"screen_question","question":"scan"}""").isEmpty())
        assertNull(Approval.ask(emptyList()))
    }

    @Test fun anUnavailableApprovedCommandCannotFallBackToModelGuessing() {
        val approved = PrefilledToolCall("shell_execute", JSONObject(spec().shellArgs!!)
            .put(Approval.TICKET_ARG, spec().ticket).toString())
        Approval.checkPrefill(listOf(approved), setOf("shell_execute"))
        assertThrows(IllegalArgumentException::class.java) { Approval.checkPrefill(listOf(approved), emptySet()) }
        Approval.checkPrefill(listOf(PrefilledToolCall.shell("printf ordinary")), emptySet())
    }

    @Test fun deniedUsedAndWithdrawnPermissionsCannotBeReplayed() {
        fun log(kind: TaskLogEntity.Kind, body: String) = TaskLogEntity(taskId = "task", at = 0, kind = kind.name, body = body)
        val records = listOf(
            log(TaskLogEntity.Kind.APPROVAL_DECISION, Approval.Decision(spec("unused"), RiskDecision.ALLOW_ONCE).json()),
            log(TaskLogEntity.Kind.APPROVAL_DECISION, Approval.Decision(spec("used"), RiskDecision.ALLOW_ALWAYS).json()),
            log(TaskLogEntity.Kind.APPROVAL_DECISION, Approval.Decision(spec("withdrawn"), RiskDecision.ALLOW_ONCE).json()),
            log(TaskLogEntity.Kind.APPROVAL_DECISION, Approval.Decision(spec("denied"), RiskDecision.DENY).json()),
            log(TaskLogEntity.Kind.APPROVAL_USED, """{"id":"used"}"""),
            log(TaskLogEntity.Kind.APPROVAL_CLOSED, """{"id":"withdrawn","reason":"new instructions"}"""),
        )
        assertEquals(listOf("unused"), Approval.unclaimed(records).map { it.spec.request.id })
    }
}
