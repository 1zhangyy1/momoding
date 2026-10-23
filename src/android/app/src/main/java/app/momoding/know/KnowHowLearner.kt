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
 * The safety net of 调研/M21: the agent records what it learned while it works, but when a turn
 * worked hard on the web (many browser steps) and it may not have, one small question to the
 * chat's model, in the background: what would get the same result faster next time? The model
 * judges what happened; its answer — at most three site records or lessons — is written by code
 * with limits.
 *
 * It sees the request, which sites were opened and how many steps each took, the start of the
 * final reply and what is already recorded; never page text, so nothing a website says can turn
 * into a lesson.
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

    internal fun worthLearning(steps: List<SiteLedger.Step>): Boolean = steps.size >= MIN_STEPS

    private suspend fun learn(context: Context, request: String, steps: List<SiteLedger.Step>, reply: String) {
        val model = app.momoding.hands.Hands.textModel(context) ?: return
        val known = KnowHow.areas(context).joinToString("\n") { a -> "[$a]\n" + KnowHow.lines(context, a).joinToString("\n") }.ifBlank { "(none yet)" }
        val now = System.currentTimeMillis()
        val sites = steps.map { it.domain }.distinct().joinToString("\n") { d ->
            SiteLedger.lookup(context, d).firstOrNull { it.domain == d }?.let { SiteLedger.render(it, now) } ?: "[$d: no record]"
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
            "faster next time, for this person's phone. Judge from the steps and the reply what worked and what did not. Only concrete, reusable " +
            "lessons: which site or page gave the answer and how it was read, which site walled off and should be skipped for this kind of errand, " +
            "which order to try. No personal details, no prices, dates or results of this job, nothing about passwords, payments or signing in, and " +
            "nothing already recorded. If there is nothing new, return empty lists. Reply with JSON only: " +
            "{\"sites\":[{\"domain\":\"...\",\"flow\":\"the errand, e.g. 查机票\",\"outcome\":\"worked|walled|partial\",\"how\":\"...\"}]," +
            "\"lessons\":[{\"topic\":\"a short topic name, e.g. travel\",\"line\":\"...\",\"replaces\":\"start of a recorded line this one supersedes, or empty\"}]}. " +
            "At most three items in total, each under 120 characters, in the language of the request."

    internal fun question(request: String, steps: List<SiteLedger.Step>, reply: String, known: String, sites: String): String = buildString {
        append("Request: ").append(request.take(300)).append("\n\nSites opened, in order (site: browser actions):\n")
        steps.fold(mutableListOf<Pair<String, MutableList<SiteLedger.Step>>>()) { acc, s ->
            if (acc.lastOrNull()?.first == s.domain) acc.last().second.add(s) else acc.add(s.domain to mutableListOf(s)); acc
        }.forEach { (d, ss) ->
            append("- ").append(d).append(": ").append(ss.size).append(" (").append(ss.groupingBy { it.action }.eachCount().entries.joinToString(", ") { "${it.key}×${it.value}" }).append(")\n")
        }
        append("\nStart of the final reply:\n").append(reply.take(500))
        append("\n\nSite records:\n").append(sites)
        append("\n\nRecorded lessons:\n").append(known)
    }

    data class SiteLesson(val domain: String, val flow: String, val outcome: SiteLedger.Outcome, val how: String)
    data class Lessons(val sites: List<SiteLesson>, val knowHow: List<Triple<String, String, String?>>)

    internal fun parse(text: String?): Lessons {
        val json = text?.let { Regex("""\{[\s\S]*\}""").find(it)?.value }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Lessons(emptyList(), emptyList())
        val sites = json.optJSONArray("sites")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().mapNotNull { o ->
            val outcome = SiteLedger.Outcome.parse(o.optString("outcome")) ?: return@mapNotNull null
            val d = o.optString("domain").trim(); val f = o.optString("flow").trim(); val h = o.optString("how").trim()
            if (d.isEmpty() || f.isEmpty() || h.isEmpty()) null else SiteLesson(d, f, outcome, h)
        }
        val know = json.optJSONArray("lessons")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { o -> o.optString("line").trim().takeIf { it.isNotEmpty() }?.let { Triple(o.optString("topic").trim(), it, o.optString("replaces").trim().ifEmpty { null }) } }
        return Lessons(sites.take(3), know.take(3))
    }

    private fun apply(context: Context, l: Lessons): List<String> {
        val done = mutableListOf<String>()
        for (s in l.sites) {
            if (done.size >= 3) break
            if (SiteLedger.record(context, s.domain, s.flow, s.outcome, s.how) != null) done += "${s.domain} ${s.flow}: ${s.outcome} — ${s.how}"
        }
        for ((area, line, replaces) in l.knowHow) {
            if (done.size >= 3) break
            if (KnowHow.add(context, area, line, replaces)) done += "$area: $line"
        }
        return done
    }
}
