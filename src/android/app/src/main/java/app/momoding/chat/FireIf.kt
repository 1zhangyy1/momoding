package app.momoding.chat

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A routine's `--if`: a condition checked when it fires, before the run ("remind me about the
 * water bill, unless I said it is paid"; "chase the reply, if they have not answered"). Instinct's
 * wake triggers work the same way (调研/M18): one small question to the chat's model, no tools,
 * over the conversation's latest messages; when it does not hold the run is skipped — nothing
 * runs, nothing is said, the record shows it was skipped.
 *
 * It fails open: no model, an error or an unclear answer runs the routine as before, so a check
 * that goes wrong never silences a reminder.
 */
object FireIf {
    private const val TAG = "FireIf"
    private const val RECENT = 20
    private const val PER_MESSAGE_CHARS = 400

    suspend fun holds(context: Context, condition: String, sessionId: String?): Boolean {
        val model = app.momoding.hands.Hands.textModel(context) ?: return true
        val transcript = sessionId?.let { recent(context, it) }.orEmpty()
        var lastReply: String? = null
        val holds = try {
            val provider = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, context)
            val reply = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = question(condition, transcript, LocalDateTime.now()))),
                systemPrompt = SYSTEM,
                // room for a reasoning model's thinking: with a tight cap it spends it all and the answer comes back empty
                maxTokens = 1024,
            ).text
            lastReply = reply
            parse(reply) ?: true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // the caller was cancelled: not a failed check, nothing should run
        } catch (e: Exception) {
            AppLogger.warning(TAG, "check failed, running anyway: ${e.message}")
            true
        }
        AppLogger.info(TAG, "\"${condition.take(60)}\" -> ${if (holds) "run" else "skip"} (reply: ${lastReply?.take(40)?.replace('\n', ' ')})")
        return holds
    }

    private suspend fun recent(context: Context, sessionId: String): String {
        val repo = (context.applicationContext as? MinisApp)?.chatRepositoryOrNull ?: return ""
        val total = repo.messageCount(sessionId)
        return repo.loadMessagePage(sessionId, offset = maxOf(0, total - RECENT), limit = RECENT, maxChars = PER_MESSAGE_CHARS)
            .joinToString("\n") { "${it.role}: ${it.text}" }
    }

    private const val SYSTEM =
        "A scheduled task is about to fire. Its condition says when it should run; it may be written as \"if …\", " +
            "\"only if …\" or \"unless …\". Read it and the latest messages of the conversation, then answer with exactly " +
            "one word: SKIP if the messages clearly show it should not run now, otherwise RUN."

    internal fun question(condition: String, transcript: String, now: LocalDateTime): String = buildString {
        append("Now: ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.ENGLISH))).append("\n")
        append("Condition: ").append(condition.trim()).append("\n\n")
        append("Latest messages:\n").append(transcript.ifBlank { "(none)" })
    }

    /** RUN / SKIP at the start of the reply, in either case; anything else is unclear (null). */
    internal fun parse(reply: String?): Boolean? {
        val word = reply.orEmpty().trim().trimStart('*', '"', '`').take(4).uppercase()
        return when {
            word.startsWith("RUN") -> true
            word.startsWith("SKIP") -> false
            else -> null
        }
    }
}
