package app.momoding.chat

import android.content.Context
import app.momoding.Identity
import app.momoding.guard.GateOutcome
import app.momoding.guard.GuardKind
import app.momoding.guard.RiskAssessment
import app.momoding.guard.RiskGate
import app.momoding.guard.RiskRequest
import app.momoding.task.Approval
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import app.momoding.task.TaskLogEntity
import app.momoding.guard.RiskClass
import app.momoding.hands.HandsOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.util.Locale

/**
 * Messages the Bot writes for the person to send (Grok Bot's draft bubble: pending → Send / Discard).
 * Neither OpenMinis nor nanoMuse has drafts, so this is new; the sending itself is nanoMuse's hands.
 *
 * The model ends its reply with
 *
 * ```momoding-draft
 * {"to": "张阿姨", "app": "微信", "text": "张阿姨好，房租下周一转给您～"}
 * ```
 *
 * and the chat draws it as a card. Send binds the exact card, recipient and edited text to a
 * persisted task. Screen sends use the shared queue; native SMS writes its intent before sending.
 */
object Drafts {
    const val BLOCK = "momoding-draft"

    /** [number]: the phone number for an SMS, when the Bot looked it up; [to] keeps the name the person used. */
    data class Draft(val to: String, val app: String, val text: String, val number: String = "") {
        /** Stable per draft content, so the card's state survives recomposition and restarts. */
        val id: String get() = Integer.toHexString("$app\u0000$to\u0000$text".hashCode())
    }

    /** OPENED: the SMS app is open with the message filled in, for the person to tap Send there. */
    enum class Status { PENDING, SENDING, SENT, FAILED, DISCARDED, OPENED, WAITING }

    data class State(val status: Status, val text: String? = null, val note: String? = null)

    fun parse(code: String): Draft? = runCatching {
        val o = JSONObject(code.trim())
        val text = o.optString("text").trim()
        if (text.isEmpty()) null else Draft(o.optString("to").trim(), o.optString("app").trim(), text, o.optString("number").trim())
    }.getOrNull()

    private const val PREFS = "momoding"
    private const val KEY = "drafts.state."
    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    val all: StateFlow<Map<String, State>> = states

    fun state(context: Context, id: String, legacyId: String? = null): State = states.value[id] ?: run {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY + id, null) ?: legacyId?.let { prefs.getString(KEY + it, null) }
        val s = raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let {
            State(Status.valueOf(it.optString("status", "PENDING")), it.optString("text").ifEmpty { null }, it.optString("note").ifEmpty { null })
        } ?: State(Status.PENDING)
        // A process exit cannot prove whether a send happened. The task projection supplies its receipt.
        (if (s.status == Status.SENDING) s.copy(status = Status.WAITING, note = null) else s).also { set(context, id, it, persist = false) }
    }

    /** Debug reset: forget every card's state (the prefs are cleared by the caller). */
    fun clear() { states.value = emptyMap() }

    fun set(context: Context, id: String, s: State, persist: Boolean = true) {
        states.value = states.value + (id to s)
        if (!persist) return
        val o = JSONObject().put("status", s.status.name).put("text", s.text ?: "").put("note", s.note ?: "")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY + id, o.toString()).apply()
    }

    /** The task the hands get: exact words, the right chat, stop rather than guess. */
    internal fun task(d: Draft, text: String): String = buildString {
        append("Send a message for the user")
        if (d.app.isNotBlank()) append(" in ${d.app}")
        if (d.to.isNotBlank()) append(" to ${d.to}")
        append(". The user has read and approved exactly this text; type it unchanged, then tap Send once:\n")
        append(text)
        append("\nOpen the chat with that recipient and check its title is them; if there is none yet and the recipient is a phone ")
        append("number or address, start a new message to exactly that. If a name matches no one, or more than one person, stop and ")
        append("say so instead of guessing. After sending, check the message shows in the chat.")
    }

    /** Outlives the card: the hands are in another app while this runs, and the chat may be rebuilt. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun key(conversation: String, source: String, draft: String): String = "$conversation:$source:$draft"
    fun needle(id: String): String = "\"id\":" + JSONObject.quote(id)

    /** The person tapped Send on this exact card. Task creation rejects a duplicate atomically. */
    fun start(context: Context, d: Draft, text: String, conversation: String, source: String) {
        require(conversation.isNotBlank() && source.isNotBlank())
        val app = context.applicationContext
        val key = key(conversation, source, d.id)
        set(app, key, State(Status.SENDING, text))
        scope.launch {
            val state = try { send(app, d, text, conversation, source) } catch (error: Exception) {
                TaskEngine.dao(app).draftTask(conversation, source, needle(d.id))?.let { project(app, it) }
                    ?: State(Status.FAILED, text, error.message)
            }
            set(app, key, state)
        }
    }

    private val SMS_APPS = setOf("短信", "简讯", "簡訊", "信息", "sms", "messages", "message", "text")
    private val NUMBER = Regex("""^\+?[0-9][0-9 \-]{4,}$""")
    /** An SMS goes out through the phone's own SMS service, never through the screen. */
    fun isSms(d: Draft): Boolean = d.app.trim().lowercase() in SMS_APPS ||
        (d.app.isBlank() && (NUMBER.matches(d.number) || NUMBER.matches(d.to.trim())))

    private fun metadata(d: Draft, text: String, sms: Boolean): String = JSONObject()
        .put("id", d.id).put("to", d.to).put("app", d.app).put("number", d.number).put("text", text).put("sms", sms).toString()

    private suspend fun send(context: Context, d: Draft, text: String, conversation: String, source: String): State = withContext(Dispatchers.IO) {
        TaskEngine.dao(context).draftTask(conversation, source, needle(d.id))?.let { return@withContext project(context, it) }
        if (isSms(d)) return@withContext sendSms(context, d, text, conversation, source)
        val authorization = "The person tapped Send on this card with exactly these parameters:\n" + metadata(d, text, false)
        val screen = TaskEngine.openScreen(context, HandsOperator.Options(task = task(d, text), appHint = d.app.ifBlank { null },
            sessionId = conversation, agentName = Identity.name(context),
            language = context.resources.configuration.locales[0].getDisplayLanguage(Locale.ENGLISH)),
            sourceId = source, authorization = authorization, draft = metadata(d, text, false))
        project(context, screen)
    }

    /** A projection of task/log state. A lost process never turns an uncertain send into Retry. */
    suspend fun project(context: Context, task: TaskEntity): State = project(task, TaskEngine.dao(context).log(task.id))

    internal fun project(task: TaskEntity, history: List<TaskLogEntity>): State {
        val draft = history.lastOrNull { it.kind == TaskLogEntity.Kind.DRAFT.name }?.body?.let(::JSONObject)
        val sent = draft?.optBoolean("sms") == true && history.any { it.kind == TaskLogEntity.Kind.RESULT.name &&
            runCatching { JSONObject(it.body).optBoolean("success") }.getOrDefault(false) }
        val status = when {
            sent -> Status.SENT
            task.state in listOf(TaskEntity.State.QUEUED.name, TaskEntity.State.RUNNING.name) -> Status.SENDING
            task.state == TaskEntity.State.ENDED.name && task.outcome == TaskEntity.Outcome.DONE.name -> Status.SENT
            else -> Status.WAITING
        }
        return State(status, draft?.optString("text"))
    }

    private suspend fun sendSms(context: Context, d: Draft, text: String, conversation: String, source: String): State {
        val number = when (val r = SmsSend.resolve(context, d)) {
            is SmsSend.Resolved.One -> r.number
            is SmsSend.Resolved.Several -> return State(Status.FAILED, text, "ambiguous:" + r.count)
            SmsSend.Resolved.None -> null
        }
        // The card was approved against the conversation as it was; a newer message there may change what to say.
        val written = (context.applicationContext as com.openminis.app.MinisApp).database.chatDao().message(source.substringBefore('#'))?.createdAt
        if (number != null && written != null && SmsSend.newSince(context, number, written)) {
            app.momoding.task.Inbox.post(TaskEngine.dao(context), conversation, "${TaskEngine.REPORT_CUE}${d.id} · sms draft · not_sent] The person tapped Send on the SMS draft to ${d.to.ifBlank { number }}, " +
                "but that conversation has new messages since the draft was written, so it was not sent. Read them and tell the person; offer a new draft if it should change.",
                sender = app.momoding.task.InboxEntity.Sender.COMMAND)
            app.momoding.task.Inbox.wake(context, conversation)
            return State(Status.FAILED, text, "replied")
        }
        if (number != null && SmsSend.canSend(context)) {
            return sendApprovedSms(context, d, number, text, conversation, source) { recipient, body -> SmsSend.send(context, recipient, body) }
        }
        return if (SmsSend.openPrefilled(context, number, text)) State(Status.OPENED, text) else State(Status.FAILED, text, "sms_failed")
    }

    /** The card is the approval; the delegate only runs after the exact send is on disk. */
    internal suspend fun sendApprovedSms(context: Context, d: Draft, number: String, text: String, conversation: String, source: String,
        perform: (String, String) -> Boolean,
    ): State {
        val parameters = metadata(d.copy(number = number), text, true)
        val request = RiskRequest(sessionId = conversation, kind = GuardKind.SHELL,
            assessment = RiskAssessment(RiskClass.OUTBOUND, "Sends the exact SMS approved on its card", "sms:$number"),
            preview = "Send SMS to $number:\n$text", summary = "Send SMS to $number")
        val recipe = JSONObject().put("command", request.preview).toString()
        val action = requireNotNull(RiskGate.started(GateOutcome.Allowed(effect = Approval.Spec(request, Approval.Mode.NATIVE, recipe)),
            sourceId = source, authorization = "The person tapped Send with exactly these parameters:\n$parameters", draft = parameters))
        var recorded = false
        try {
            val accepted = perform(number, text)
            withContext(NonCancellable) {
                RiskGate.finished(action, if (accepted) "The SMS service reported every part sent." else "The SMS service did not confirm every part; verify before repeating.",
                    accepted, unknown = !accepted)
            }
            recorded = true
            return TaskEngine.dao(context).task(action.taskId)?.let { project(context, it) } ?: State(Status.WAITING, text)
        } finally {
            if (!recorded) withContext(NonCancellable) {
                RiskGate.finished(action, "The SMS caller stopped without a confirmed result. Verify whether it was sent before repeating.", false, unknown = true)
            }
        }
    }
}
