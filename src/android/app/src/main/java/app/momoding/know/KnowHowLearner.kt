package app.momoding.know

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Layer 3 of 调研/M21: after a turn that worked hard on the web (many browser steps, or walls),
 * one small question to the chat's model, in the background: what would get the same result
 * faster next time? Its answer becomes at most three short lines — a note on a site, a line of
 * know-how for an area — written by code with limits, so each phone's playbook improves by itself.
 *
 * It sees the request, which sites were tried and how each went, and the start of the final
 * reply; never page text, so nothing a website says can turn into a lesson.
 */
object KnowHowLearner {
    private const val TAG = "KnowHowLearner"
    const val MIN_STEPS = 8
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun afterTurn(context: Context, sessionId: String, request: String?, reply: String?) {
        val steps = SiteLedger.takeTurn(sessionId)
        if (!worthLearning(steps) || request.isNullOrBlank()) return
        val app = context.applicationContext
        scope.launch {
            runCatching { learn(app, request, steps, reply.orEmpty()) }
                .onFailure { AppLogger.warning(TAG, "learning skipped: ${it.message}") }
        }
    }

    internal fun worthLearning(steps: List<SiteLedger.Step>): Boolean =
        steps.size >= MIN_STEPS || steps.any { it.outcome == SiteLedger.Outcome.WALL }

    private suspend fun learn(context: Context, request: String, steps: List<SiteLedger.Step>, reply: String) {
        val model = app.momoding.hands.Hands.textModel(context) ?: return
        val known = KnowHow.AREAS.joinToString("\n") { a -> "[$a]\n" + KnowHow.lines(context, a).joinToString("\n").ifBlank { "(nothing yet)" } }
        val sites = steps.map { it.domain }.distinct().joinToString("\n") { d ->
            SiteLedger.entry(context, d)?.let { SiteLedger.render(it, System.currentTimeMillis()) } ?: "[$d: no record]"
        }
        val provider = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, context)
        val text = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = question(request, steps, reply, known, sites))),
            systemPrompt = SYSTEM,
            maxTokens = 1024,
        ).text
        val lessons = parse(text)
        if (lessons.sites.isEmpty() && lessons.knowHow.isEmpty()) AppLogger.info(TAG, "nothing new: ${text.take(160).replace('\n', ' ')}")
        val applied = apply(context, lessons)
        AppLogger.info(TAG, "learned ${applied.size} line(s) from ${steps.size} steps: ${applied.joinToString(" | ").take(300)}")
    }

    private const val SYSTEM =
        "You improve how a phone assistant gets errands done on the web. From a summary of one job, write what would get the same result " +
            "faster next time, for this person's phone. Only concrete, reusable lessons: which site or page gave the answer and how it was read, which site " +
            "walled off and should be skipped for this kind of errand, which order to try. No personal details, no prices or dates of this job, " +
            "nothing about passwords, payments or signing in, and nothing already in the known lines. If there is nothing new, return empty lists. " +
            "Reply with JSON only: {\"sites\":[{\"domain\":\"...\",\"note\":\"...\"}],\"know_how\":[{\"area\":\"travel|shopping|procedures\",\"line\":\"...\",\"replaces\":\"start of a known line this one supersedes, or empty\"}]}. " +
            "At most three items in total, each under 120 characters, in the language of the request."

    internal fun question(request: String, steps: List<SiteLedger.Step>, reply: String, known: String, sites: String): String = buildString {
        append("Request: ").append(request.take(300)).append("\n\nSteps (site: actions, outcomes):\n")
        steps.fold(mutableListOf<Pair<String, MutableList<SiteLedger.Step>>>()) { acc, s ->
            if (acc.lastOrNull()?.first == s.domain) acc.last().second.add(s) else acc.add(s.domain to mutableListOf(s)); acc
        }.forEach { (d, ss) ->
            val walls = ss.filter { it.outcome == SiteLedger.Outcome.WALL }
            append("- ").append(d).append(": ").append(ss.size).append(" actions, ")
                .append(ss.count { it.outcome == SiteLedger.Outcome.READ }).append(" read content, ")
                .append(walls.size).append(" walled").append(walls.firstNotNullOfOrNull { it.sign }?.let { " (\"$it\")" } ?: "").append('\n')
        }
        append("\nStart of the final reply:\n").append(reply.take(500))
        append("\n\nSite records:\n").append(sites)
        append("\n\nKnown lines:\n").append(known)
    }

    data class Lessons(val sites: List<Pair<String, String>>, val knowHow: List<Triple<String, String, String?>>)

    internal fun parse(text: String?): Lessons {
        val json = text?.let { Regex("""\{[\s\S]*\}""").find(it)?.value }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Lessons(emptyList(), emptyList())
        val sites = json.optJSONArray("sites")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { o -> o.optString("domain").trim().takeIf { it.isNotEmpty() }?.let { it to o.optString("note").trim() } }
            .filter { it.second.isNotEmpty() }
        val know = json.optJSONArray("know_how")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { o -> o.optString("line").trim().takeIf { it.isNotEmpty() }?.let { Triple(o.optString("area").trim().lowercase(), it, o.optString("replaces").trim().ifEmpty { null }) } }
        return Lessons(sites.take(3), know.take(3))
    }

    private fun apply(context: Context, l: Lessons): List<String> {
        val done = mutableListOf<String>()
        for ((domain, note) in l.sites) {
            if (done.size >= 3) break
            KnowHow.sanitize(note)?.let { SiteLedger.note(context, domain, it); done += "$domain: $it" }
        }
        for ((area, line, replaces) in l.knowHow) {
            if (done.size >= 3) break
            if (KnowHow.add(context, area, line, replaces)) done += "$area: $line"
        }
        return done
    }
}
