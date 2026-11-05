// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/guard/RiskGate.kt
package app.momoding.guard

import android.content.Context
import app.momoding.task.Approval
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import app.momoding.task.TaskGuard
import app.momoding.task.TaskAction
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One thing the agent wants to do and is waiting on. */
data class RiskRequest(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val kind: GuardKind,
    val assessment: RiskAssessment,
    /** The command, or "Tap “Pay now”". Shown verbatim in the card's preview box. */
    val preview: String,
    /** For browser requests: the page the tap lands on. */
    val pageUrl: String? = null,
    /** For browser requests: the element's own text. */
    val elementText: String? = null,
    /** In the model's words, what this step does ("删除下周一转房租的日程"); the card leads with it. */
    val summary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val conversation: String? = null,
    val sequence: Long? = null,
) {
    /**
     * What may be remembered. Nothing alarming (a warning) is ever remembered. "For this chat"
     * is offered for deleting and sending, not for money. "Always for X" needs an object to
     * bind to; for money it is the highest tier — offered as a deliberate choice that the card
     * confirms with the phone's screen lock ([needsCredential]), and the first time is always
     * asked because the grant can only come from the card.
     */
    val canSession: Boolean get() = assessment.warnings.isEmpty() && assessment.riskClass != RiskClass.MONEY && kind != GuardKind.DEVICE
    val canAlways: Boolean get() = assessment.warnings.isEmpty() && !assessment.target.isNullOrBlank()
    val canRemember: Boolean get() = canSession || canAlways
    val needsCredential: Boolean get() = assessment.riskClass == RiskClass.MONEY
}

enum class RiskDecision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY, TIMEOUT }

sealed class GateOutcome {
    /** Go ahead. [notice] is a line for the model when something ran without asking (installs). */
    data class Allowed(val notice: String? = null, val permit: Approval.Permit? = null,
        val effect: Approval.Spec? = permit?.spec,
    ) : GateOutcome()
    /** Do not run it; [message] is what the model is told. */
    data class Denied(val message: String) : GateOutcome()
    data class Waiting(val taskId: String) : GateOutcome()
}

/** Cards observe persisted task asks. Only physical waiters and notification handles live here. */
object RiskGate {
    private const val TAG = "RiskGate"
    @Volatile private var appContext: Context? = null
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val waiters = ConcurrentHashMap<String, Job>()
    private val notified = mutableSetOf<String>()
    private val _pending = MutableStateFlow<RiskRequest?>(null)
    val pending: StateFlow<RiskRequest?> = _pending.asStateFlow()
    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued.asStateFlow()
    private val _open = MutableStateFlow<List<RiskRequest>>(emptyList())
    val open: StateFlow<List<RiskRequest>> = _open.asStateFlow()
    private val _recent = MutableStateFlow<List<Pair<RiskRequest, RiskDecision>>>(emptyList())
    val recent: StateFlow<List<Pair<RiskRequest, RiskDecision>>> = _recent.asStateFlow()
    @Volatile var backgroundNotifier: ((RiskRequest) -> Boolean)? = null
    @Volatile var cancelNotification: ((String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            TaskEngine.dao(context).activeFlow().collect { tasks ->
                val requests = tasks.flatMap { task ->
                    runCatching { Approval.pending(task.ask).map { it.request } }
                        .onFailure { AppLogger.warning(TAG, "read ask: " + it.message) }.getOrDefault(emptyList())
                }.distinctBy { it.id }.sortedWith(compareBy({ it.sequence ?: Long.MAX_VALUE }, { it.createdAt }, { it.id }))
                val ids = requests.map { it.id }.toSet()
                for (id in notified.toList()) if (id !in ids) {
                    cancelNotification?.invoke(id)
                    notified.remove(id)
                }
                _open.value = requests
                _pending.value = requests.firstOrNull()
                _queued.value = (requests.size - 1).coerceAtLeast(0)
                notifyPending()
            }
        }
    }

    fun isWaiting(id: String): Boolean = waiters[id]?.isActive == true

    fun decide(id: String, decision: RiskDecision): Job = scope.launch {
            val context = appContext ?: return@launch
            runCatching {
                val spec = TaskEngine.findApproval(context, id) ?: return@runCatching
                val result = TaskEngine.decideApproval(context, spec, decision) ?: return@runCatching
                _recent.value = (listOf(result.spec.request to result.answer) + _recent.value).take(20)
                if (!result.allowed) record(result.spec.request, result.answer)
            }.onFailure { AppLogger.warning(TAG, "decision: " + it.message) }
    }

    fun notifyPending() {
        val notifier = backgroundNotifier ?: return
        _open.value.forEach { if (it.id !in notified && notifier(it)) notified += it.id }
    }

    suspend fun checkShell(sessionId: String, command: String, summary: String? = null, taskId: String? = null,
        shellArgs: String? = null, defer: Boolean = false,
        binding: TaskGuard.Binding? = null,
    ): GateOutcome {
        val context = requireNotNull(appContext) { "The approval gate is not ready." }
        val ticket = shellArgs?.let { JSONObject(it).optString(Approval.TICKET_ARG).ifBlank { null } }
        if (ticket != null) return outcome(TaskEngine.claimApproval(context, sessionId, ticket, shellArgs))
        return check(sessionId, GuardKind.SHELL, ShellGuard.assess(command), command.trim(), summary = summary,
            taskId = taskId, shellArgs = shellArgs, defer = defer,
            mode = if (defer) Approval.Mode.SHELL else Approval.Mode.NATIVE, invocation = binding)
    }

    suspend fun check(sessionId: String, kind: GuardKind, assessment: RiskAssessment, preview: String,
        pageUrl: String? = null, elementText: String? = null, summary: String? = null, taskId: String? = null,
        shellArgs: String? = null, defer: Boolean = false,
        mode: Approval.Mode = if (kind == GuardKind.SCREEN) Approval.Mode.SCREEN else Approval.Mode.NATIVE,
        invocation: TaskGuard.Binding? = null,
    ): GateOutcome {
        val context = requireNotNull(appContext) { "The approval gate is not ready." }
        val hand = if (kind == GuardKind.SCREEN && taskId != null) TaskEntity.SCREEN else sessionId
        val binding = invocation ?: TaskGuard.bind(context, hand, taskId)
        require(binding.hand == hand) { "This invocation belongs to another conversation." }
        val outward = assessment.riskClass != RiskClass.SAFE || assessment.needsApproval
        TaskGuard.check(context, binding, outward, allowWaiting = true)?.let { return GateOutcome.Denied(it) }
        if (!outward) return GateOutcome.Allowed()
        val request = RiskRequest(sessionId = sessionId, kind = kind, assessment = assessment, preview = preview,
            pageUrl = pageUrl, elementText = elementText, summary = summary, taskId = binding.taskId)
        val spec = Approval.Spec(request, mode, shellArgs)
        if (!assessment.needsApproval) {
            TaskGuard.check(context, binding, outward = true)?.let { return GateOutcome.Denied(it) }
            return GateOutcome.Allowed(INSTALL_NOTICE, effect = spec)
        }
        if (kind == GuardKind.SCREEN) TaskEngine.screenApproval(context, request)?.let { return outcome(it) }
        if (assessment.warnings.isEmpty() && Grants.allows(assessment.riskClass, assessment.target, sessionId)) {
            TaskGuard.check(context, binding, outward = true)?.let { return GateOutcome.Denied(it) }
            RuleHits.record(sessionId, RiskText.plainTarget(assessment.target))
            return GateOutcome.Allowed(if (assessment.riskClass == RiskClass.MONEY) autoPaidNotice(RiskText.plainTarget(assessment.target)) else null,
                effect = spec)
        }
        return try {
            if (defer) {
                val saved = TaskEngine.waitForApproval(context, spec)
                GateOutcome.Waiting(requireNotNull(saved.request.taskId))
            } else outcome(await(spec))
        } catch (e: IllegalStateException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            GateOutcome.Denied(e.message ?: "The task no longer allows this request.")
        } catch (e: IllegalArgumentException) {
            GateOutcome.Denied(e.message ?: "The command cannot be saved for approval.")
        }
    }

    /** Commit the planned outward action immediately before its executor is allowed to run. */
    suspend fun started(allowed: GateOutcome.Allowed, sourceId: String? = null, authorization: String? = null, draft: String? = null): TaskAction? = allowed.effect?.let {
        TaskEngine.actionStarted(requireNotNull(appContext), it, allowed.permit?.command, sourceId, authorization, draft)
    }

    /** The executor supplies an observed result; an interruption explicitly stays uncertain. */
    suspend fun finished(action: TaskAction?, output: String, success: Boolean, unknown: Boolean = false) {
        if (action != null) TaskEngine.actionFinished(requireNotNull(appContext), action, output, success, unknown)
    }

    private suspend fun await(spec: Approval.Spec): Approval.Claim {
        val context = requireNotNull(appContext)
        val job = requireNotNull(currentCoroutineContext()[Job])
        check(waiters.putIfAbsent(spec.request.id, job) == null) { "This approval already has an executor." }
        var saved: Approval.Spec? = null
        try {
            saved = TaskEngine.waitForApproval(context, spec)
            val id = requireNotNull(saved.request.taskId)
            val row = TaskEngine.dao(context).taskFlow(id).first {
                it == null || it.state == TaskEntity.State.ENDED.name ||
                    Approval.pending(it.ask).none { request -> request.request.id == saved.request.id }
            }
            val recorded = TaskEngine.dao(context).log(id).any { it.kind == app.momoding.task.TaskLogEntity.Kind.APPROVAL_DECISION.name &&
                Approval.Decision.parse(it.body).spec.request.id == saved.request.id }
            if (!recorded) {
                val hand = if (saved.mode == Approval.Mode.SCREEN) TaskEntity.SCREEN else saved.request.sessionId
                val reason = TaskGuard.check(context, TaskGuard.Binding(hand, id))
                return Approval.Claim.Denied(reason ?: if (row == null) "The task was deleted." else "The request changed; its old approval is no longer valid.")
            }
            return TaskEngine.claimApproval(context, saved.request.sessionId, saved.ticket, actual = saved.request)
        } finally {
            waiters.remove(spec.request.id, job)
            saved?.takeIf { it.mode == Approval.Mode.NATIVE }?.request?.taskId?.let { id ->
                withContext(NonCancellable) { TaskEngine.resumeApproved(context, id) }
            }
        }
    }

    private fun outcome(claim: Approval.Claim): GateOutcome = when (claim) {
        is Approval.Claim.Denied -> GateOutcome.Denied(claim.reason)
        is Approval.Claim.Granted -> {
            val permit = claim.permit
            val request = permit.spec.request
            record(request, permit.answer)
            var notice: String? = null
            when (permit.answer) {
                RiskDecision.ALLOW_SESSION -> if (request.canSession) Grants.grantSession(request.assessment.riskClass, request.sessionId, request.assessment.reason, request.assessment.target)
                RiskDecision.ALLOW_ALWAYS -> request.assessment.target?.takeIf { request.canAlways }?.let {
                    Grants.grantAlways(request.assessment.riskClass, it, request.assessment.reason)
                    if (request.assessment.riskClass == RiskClass.MONEY) notice = rememberedNotice(RiskText.plainTarget(it), request.assessment.riskClass)
                }
                else -> Unit
            }
            GateOutcome.Allowed(notice, permit)
        }
    }

    private fun record(request: RiskRequest, answer: RiskDecision) = RuleHits.record(request.sessionId, RiskText.plainTarget(request.assessment.target), when (answer) {
        RiskDecision.ALLOW_ONCE -> RuleHits.ONCE
        RiskDecision.ALLOW_SESSION -> RuleHits.SESSION
        RiskDecision.ALLOW_ALWAYS -> RuleHits.ALWAYS
        RiskDecision.DENY -> RuleHits.DENIED
        RiskDecision.TIMEOUT -> RuleHits.TIMEOUT
    })

    const val INSTALL_NOTICE =
        "[Momoding] This command installs software; it ran without asking. Mention it to the user in one line."

    /** After "always allow for X": the model tells the user where the grant lives, once. */
    fun rememberedNotice(target: String, riskClass: RiskClass): String =
        "[Momoding] The user chose to remember this approval (${riskClass.name.lowercase()} · $target)" +
            (if (riskClass == RiskClass.MONEY) ", confirmed with their screen lock, so payments in $target will run without a card from now on" else "") +
            ". Tell them in one line that it is listed under Settings → Permissions → Remembered approvals, where it can be revoked."

    /** A payment that ran on a standing grant: always said out loud. */
    fun autoPaidNotice(target: String): String =
        "[Momoding] This payment step ran without asking because the user remembered approvals for $target. " +
            "Say so in one line, and remind them it can be revoked under Settings → Permissions."
}
