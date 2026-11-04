package app.momoding.task

import app.momoding.guard.GuardKind
import app.momoding.guard.RiskAssessment
import app.momoding.guard.RiskClass
import app.momoding.guard.RiskDecision
import app.momoding.guard.RiskRequest
import org.json.JSONArray
import org.json.JSONObject

/** Durable approval payloads; task/ask/log and inbox remain the only business state. */
object Approval {
    const val TICKET_ARG = "_momoding_approval"
    enum class Mode { SHELL, NATIVE, SCREEN }
    data class Spec(val request: RiskRequest, val mode: Mode, val shellArgs: String? = null) {
        val ticket: String get() = requireNotNull(request.taskId) + ":" + request.id
        fun json(): JSONObject = with(request) {
            JSONObject().put("id", id).put("task", taskId).put("session", sessionId).put("conversation", conversation).put("sequence", sequence).put("kind", kind.name)
                .put("risk", assessment.riskClass.name).put("reason", assessment.reason).put("target", assessment.target)
                .put("warnings", JSONArray(assessment.warnings)).put("preview", preview).put("page", pageUrl)
                .put("element", elementText).put("summary", summary).put("at", createdAt)
                .put("mode", mode.name).put("shell", shellArgs?.let(::JSONObject))
        }
        fun sameAction(other: RiskRequest): Boolean = with(request) {
            taskId == other.taskId && sessionId == other.sessionId && kind == other.kind &&
                preview == other.preview && pageUrl == other.pageUrl && elementText == other.elementText &&
                assessment.riskClass == other.assessment.riskClass && assessment.target == other.assessment.target &&
                assessment.warnings == other.assessment.warnings
        }
        companion object {
            fun parse(o: JSONObject): Spec = Spec(RiskRequest(
                id = o.getString("id"), taskId = o.optString("task").ifBlank { null }, conversation = o.optString("conversation").ifBlank { null },
                sequence = if (o.has("sequence")) o.getLong("sequence") else null,
                sessionId = o.getString("session"), kind = GuardKind.valueOf(o.getString("kind")),
                assessment = RiskAssessment(RiskClass.valueOf(o.getString("risk")), o.getString("reason"),
                    o.optString("target").ifBlank { null }, o.getJSONArray("warnings").let { a -> List(a.length()) { a.getString(it) } }),
                preview = o.getString("preview"), pageUrl = o.optString("page").ifBlank { null },
                elementText = o.optString("element").ifBlank { null }, summary = o.optString("summary").ifBlank { null },
                createdAt = o.getLong("at")), Mode.valueOf(o.getString("mode")), o.optJSONObject("shell")?.toString())
        }
    }
    data class Decision(val spec: Spec, val answer: RiskDecision) {
        fun json(): String = JSONObject().put("request", spec.json()).put("answer", answer.name).toString()
        val allowed: Boolean get() = answer in listOf(RiskDecision.ALLOW_ONCE, RiskDecision.ALLOW_SESSION, RiskDecision.ALLOW_ALWAYS)
        companion object {
            fun parse(text: String): Decision = JSONObject(text).let { Decision(Spec.parse(it.getJSONObject("request")), RiskDecision.valueOf(it.getString("answer"))) }
        }
    }
    data class Permit(val spec: Spec, val answer: RiskDecision, val command: ShellWork.Command? = null)
    sealed class Claim {
        data class Granted(val permit: Permit) : Claim()
        data class Denied(val reason: String) : Claim()
    }

    fun pending(ask: String?): List<Spec> = ask?.let { text ->
        val o = JSONObject(text)
        if (o.optString("kind") != "approval") emptyList() else o.getJSONArray("requests").let { a ->
            List(a.length()) { Spec.parse(a.getJSONObject(it)) }
        }
    }.orEmpty()

    fun ask(requests: List<Spec>): String? = requests.takeIf { it.isNotEmpty() }?.let {
        JSONObject().put("kind", "approval").put("requests", JSONArray(it.map(Spec::json)))
            .put("question", it.first().request.summary ?: it.first().request.assessment.reason)
            .put("screen", it.firstOrNull { s -> s.mode == Mode.SCREEN }?.request?.taskId).toString()
    }

    fun checkPrefill(calls: List<com.openminis.app.scheduled.PrefilledToolCall>, available: Set<String>) {
        if (calls.none { it.args().has(TICKET_ARG) }) return
        require(calls.all { it.toolName in available && it.validationError() == null }) {
            "The approved command's tool is unavailable. It was not run. Enable the tool and retry the saved message."
        }
    }

    fun unclaimed(log: List<TaskLogEntity>): List<Decision> {
        val closed = log.filter { it.kind in listOf(TaskLogEntity.Kind.APPROVAL_USED.name, TaskLogEntity.Kind.APPROVAL_CLOSED.name) }
            .map { JSONObject(it.body).getString("id") }.toSet()
        return log.filter { it.kind == TaskLogEntity.Kind.APPROVAL_DECISION.name }.map { Decision.parse(it.body) }
            .filter { it.allowed && it.spec.request.id !in closed }
    }

    /** All supplied arguments bind the permission, independent of JSON object key order. */
    fun shellSignature(json: String): String = canonical(JSONObject(json).apply { remove(TICKET_ARG) })
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        else -> value.toString()
    }
}
