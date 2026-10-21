package app.momoding.providers

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.repository.ProviderRepository

/**
 * First run: once a provider's models are in, the chat model is picked for the person (docs
 * 调研/M11, M12 — "不让用户自己挑模型"). A small preference list per vendor, first match wins;
 * the pick becomes the default group "Momoding" with the next good one as fallback. The screen
 * model needs nothing here: the hands find a vision model on their own (Hands.screenModel).
 */
object AutoSetup {
    /** Ordered substrings of model ids, best first, known to work for an agent loop. */
    internal val CHAT_PREFERENCE = listOf(
        // Bailian / DashScope, DeepSeek, Kimi, Zhipu, Ark, SiliconFlow
        "deepseek-v4.1-flash", "deepseek-v4-flash", "deepseek-chat", "deepseek-v3.2",
        "glm-5.3", "glm-5.2",
        "qwen3.8-max", "qwen3.8-plus", "qwen3.7-max",
        "kimi-k3", "kimi-k2.6",
        "doubao-seed",
        // OpenRouter (vendor/model)
        "z-ai/glm-5.3", "deepseek/deepseek-v4.1-flash", "moonshotai/kimi-k3", "google/gemini-3.8-flash",
    )
    private val SKIP = Regex("""embed|rerank|tts|whisper|asr|image|audio|:batch|:free|-vl-|ocr|guard|coder""", RegexOption.IGNORE_CASE)

    /** Every model on the preference list that the provider has, best first: the models page offers these. */
    internal fun recommended(modelIds: List<String>): List<String> {
        val usable = modelIds.filterNot { SKIP.containsMatchIn(it) }
        // one per preference entry (the plain id over dated variants)
        return CHAT_PREFERENCE.mapNotNull { want ->
            usable.filter { it.contains(want, ignoreCase = true) && !it.contains("prime") }.minByOrNull { it.length }
        }.distinct()
    }

    /** "deepseek" for deepseek/deepseek-v4.1-flash: models of one family stand in for each other. */
    internal fun family(id: String) = id.substringAfter('/').takeWhile { it.isLetter() }.lowercase()

    /** The chat models to put in the group, best first (at most two). */
    internal fun pick(modelIds: List<String>): List<String> {
        val usable = modelIds.filterNot { SKIP.containsMatchIn(it) }
        // the best one, and the fallback from another family
        val ranked = recommended(modelIds)
        if (ranked.isEmpty()) return usable.take(1)
        val first = ranked.first()
        return listOfNotNull(first, ranked.firstOrNull { family(it) != family(first) })
    }

    /**
     * A provider none of whose models is on the list (OpenAI, Anthropic, a relay): its chat models
     * as Minis orders them everywhere, newest first (a stale model off the top of a list is the
     * trap OpenMinis#83 describes); its own lists already hold only what the account can call.
     */
    internal fun ranked(entries: List<ModelEntry>): List<ModelEntry> =
        com.openminis.app.data.repository.ModelEntryRanking.sortedByReleaseRank(entries.filterNot { SKIP.containsMatchIn(it.model.id) })

    /** Creates the default group when there is none and the provider's models are in. True when it did. */
    fun ensureDefaultGroup(repo: ProviderRepository): Boolean {
        val cfg = repo.config.value
        if (cfg.modelGroups.isNotEmpty() || cfg.instances.isEmpty()) return false
        val entries: List<ModelEntry> = cfg.modelEntries.filter { !it.isHidden }
        if (entries.isEmpty()) return false
        val known = recommended(entries.map { it.model.id }).isNotEmpty()
        // a model and a fallback: when the first is refused, the second answers
        val chosen = if (known) pick(entries.map { it.model.id }).mapNotNull { id -> entries.firstOrNull { it.model.id == id }?.id }
        else ranked(entries).take(2).map { it.id }
        if (chosen.isEmpty()) return false
        val group = ModelGroup(name = "Momoding", memberEntryIds = chosen.toMutableList())
        repo.addGroup(group)
        repo.defaultPrimaryGroupId = group.id
        return true
    }
}
