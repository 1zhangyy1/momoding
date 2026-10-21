// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/hands/Hands.kt
package app.momoding.hands

import kotlinx.coroutines.launch
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.openminis.app.MinisApp
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The screen as a hand (0.1.12). Off by default; when the user switches it on and the three
 * prerequisites hold, the agent gets `momoding-hands` — a screenshot-driven operator that taps,
 * types and swipes in the apps that have no API — as the last rung of the ladder.
 *
 * The eyes are the screenshot alone: no accessibility tree is read for perception. The
 * accessibility service is the hand (gestures, the screenshot itself, the focused field to
 * type into) and nothing more.
 */
object Hands {
    private const val PREFS = "momoding"
    private const val KEY_ENABLED = "hands.enabled"
    private const val KEY_MODEL = "hands.model_entry"
    const val DEEP_LINK = "minis://settings/hands"
    const val MIN_SDK = Build.VERSION_CODES.R

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ENABLED, on).apply() }

    /** The switch as a flow: the current value, then every change, wherever it is made. */
    fun enabledFlow(context: Context): Flow<Boolean> = callbackFlow {
        val p = prefs(context.applicationContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) trySend(p.getBoolean(KEY_ENABLED, false))
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(p.getBoolean(KEY_ENABLED, false))
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** The chosen screen model's entry id; null means "pick one" ([screenModel]). */
    private const val KEY_BACKGROUND = "hands.background"

    /** Work on the hands' own hidden screen when Shizuku allows it (the person keeps their screen). On unless switched off. */
    fun background(context: Context): Boolean = prefs(context).getBoolean(KEY_BACKGROUND, true)
    fun setBackground(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_BACKGROUND, on).apply() }

    fun modelEntryId(context: Context): String? = prefs(context).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
    /** Null forgets the choice: the screen's model follows the automatic order again. */
    fun setModelEntryId(context: Context, id: String?) {
        prefs(context).edit().apply { if (id == null) remove(KEY_MODEL) else putString(KEY_MODEL, id) }.apply()
    }

    // ── readiness ──────────────────────────────────────────────────────────

    data class Readiness(
        val androidOk: Boolean,
        val serviceOn: Boolean,
        val overlayOk: Boolean,
        val model: ScreenModel?,
    ) {
        val ready: Boolean get() = androidOk && serviceOn && overlayOk && model != null
    }

    fun readiness(context: Context): Readiness = Readiness(
        androidOk = Build.VERSION.SDK_INT >= MIN_SDK,
        serviceOn = MinisAccessibilityService.getInstance() != null,
        overlayOk = Settings.canDrawOverlays(context),
        model = screenModel(context),
    )

    /** Usable right now: switched on and every prerequisite in place. */
    fun usable(context: Context): Boolean = enabled(context) && readiness(context).ready

    // ── the screen model ───────────────────────────────────────────────────

    /** How the screen model was arrived at, for the settings page to say. */
    enum class Why { CHOSEN, DEFAULT, CHAT, GROUP, VISION_GROUP, ANY }

    /**
     * The hands' default model wherever it is served (contract C4): `qwen3.8-27b` on nanoMuse
     * Cloud and 阿里云百炼, `qwen/qwen3.8-27b` on OpenRouter. The chat model is a separate
     * setting; a chat model that happens to see is not thereby the one that operates the phone.
     */
    val DEFAULT_MODEL_IDS = listOf("qwen3.8-27b", "qwen/qwen3.8-27b")

    /** A model that sees pictures, with the key to call it. */
    data class ScreenModel(val instance: ProviderInstance, val entry: ModelEntry, val apiKey: String, val why: Why = Why.ANY) {
        val label: String get() = entry.model.displayName.ifBlank { entry.model.id } + " · " + instance.label.ifBlank { instance.providerType.name }
        val modelId: String get() = entry.model.id
    }

    /** Every enabled, visible model that declares image input, for the picker. */
    fun visionEntries(context: Context): List<Pair<ProviderInstance, ModelEntry>> {
        val repo = repo(context) ?: return emptyList()
        val cfg = repo.config.value
        return cfg.modelEntries.filter { !it.isHidden && it.model.hasImageInput }.mapNotNull { e ->
            cfg.instances.firstOrNull { it.id == e.providerInstanceId && it.isEnabled }?.let { it to e }
        }
    }

    /** Screen models known to do well (阶段C C3/C4: qwen3.8-27b 10/11, gemini-3.8-flash 5/5), best first, by substring. */
    private val SCREEN_PREFERENCE = listOf("qwen3.8-27b", "gemini-3.8-flash", "qwen3.7-vl", "qwen-vl", "glm-5v", "glm-4.6v", "doubao-seed", "kimi-vl", "gemini")

    /** The few vision models the picker offers: one per family, the preferred ones first (at most [max]). */
    fun recommendedScreenModels(context: Context, max: Int = 6): List<Pair<ProviderInstance, ModelEntry>> {
        val all = visionEntries(context)
        val ranked = SCREEN_PREFERENCE.mapNotNull { want -> all.filter { it.second.model.id.contains(want, ignoreCase = true) }.minByOrNull { it.second.model.id.length } }
        return ranked.distinctBy { app.momoding.providers.AutoSetup.family(it.second.model.id) }.take(max)
    }

    /** No configured model can see the screen: the one sentence that says so, or null when one can. */
    fun unavailableLine(context: Context): String? =
        if (visionEntries(context).isEmpty()) context.getString(com.openminis.app.R.string.mo_hands_no_vision_model) else null

    /**
     * The model that looks at the screen — a setting of its own beside the chat model. In order:
     * the one chosen in Settings → Hands; the hands' default (`qwen3.8-27b` on 阿里云百炼,
     * `qwen/qwen3.8-27b` on OpenRouter) when the person has it; the chat model last used when it
     * sees; the first sighted member of the default group; the Vision Group; last any enabled
     * vision model, preferring names that say so (`vl`, `vision`). Null when none of the
     * person's models sees. [automatic] ignores the choice, for the picker's *Automatic* row.
     *
     * momoding: nanoMuse's order without its Cloud relay, its model slots and its vendor
     * catalogue; whether a model sees is what the model entry declares.
     */
    fun screenModel(context: Context, automatic: Boolean = false): ScreenModel? {
        val repo = repo(context) ?: return null
        val cfg = repo.config.value
        fun usable(inst: ProviderInstance?, entry: ModelEntry?, why: Why): ScreenModel? {
            if (inst == null || entry == null || !inst.isEnabled || !entry.model.hasImageInput) return null
            val key = repo.usableApiKey(inst) ?: return null
            return ScreenModel(inst, entry, key, why)
        }
        fun byEntry(entry: ModelEntry?, why: Why): ScreenModel? =
            entry?.let { e -> usable(cfg.instances.firstOrNull { it.id == e.providerInstanceId }, e, why) }
        if (!automatic) modelEntryId(context)?.let { id -> byEntry(cfg.modelEntries.firstOrNull { it.id == id }, Why.CHOSEN) }?.let { return it }
        DEFAULT_MODEL_IDS.firstNotNullOfOrNull { wanted ->
            cfg.modelEntries.filter { !it.isHidden && it.model.id.equals(wanted, ignoreCase = true) }
                .firstNotNullOfOrNull { byEntry(it, Why.DEFAULT) }
        }?.let { return it }
        byEntry(repo.lastUsedVisibleEntry(), Why.CHAT)?.let { return it }
        val defaultGroup = cfg.modelGroups.firstOrNull { it.id == cfg.defaultPrimaryGroupId } ?: cfg.modelGroups.firstOrNull()
        defaultGroup?.memberEntryIds?.firstNotNullOfOrNull { id ->
            byEntry(cfg.modelEntries.firstOrNull { it.id == id }, Why.GROUP)
        }?.let { return it }
        repo.resolveVisionCandidates().firstNotNullOfOrNull { (inst, e) -> usable(inst, e, Why.VISION_GROUP) }?.let { return it }
        val all = visionEntries(context).mapNotNull { (inst, e) -> usable(inst, e, Why.ANY) }
        return all.firstOrNull { Regex("vl|vision", RegexOption.IGNORE_CASE).containsMatchIn(it.modelId) } ?: all.firstOrNull()
    }

    /**
     * The chat's own model, for the hands' steps that read only the element list: no picture to
     * look at, so the fast text model answers instead of waiting on the screen model.
     */
    fun textModel(context: Context): ScreenModel? {
        val repo = repo(context) ?: return null
        val entry = repo.lastUsedVisibleEntry() ?: return null
        val inst = repo.config.value.instances.firstOrNull { it.id == entry.providerInstanceId }?.takeIf { it.isEnabled } ?: return null
        val key = repo.usableApiKey(inst) ?: return null
        return ScreenModel(inst, entry, key, Why.CHAT)
    }

    private fun repo(context: Context): ProviderRepository? = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull

    // ── a run in progress ──────────────────────────────────────────────────

    /** True while the hands are working; the settings page and the overlay observer read it. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    internal fun setActive(on: Boolean) { _active.value = on; if (!on) _progress.value = null }

    /** Where a run is: the app it is in and the step, for the chat's status line. Null when idle. */
    /** [background]: on the hands' hidden display; [waiting]: what the person is asked to do (a login, a check), while they are. */
    data class Progress(val app: String?, val step: Int, val background: Boolean = false, val waiting: String? = null)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    internal fun reportStep(app: String?, step: Int, background: Boolean = false) { _progress.value = Progress(app?.takeIf { it.isNotBlank() }, step, background) }
    internal fun reportWaiting(reason: String?) { _progress.value = _progress.value?.copy(waiting = reason?.takeIf { it.isNotBlank() }) }

    /**
     * Takes the hands for a run: true when they were free, false when another run holds them.
     * One atomic step, so two `momoding-hands run` arriving together cannot both start; the
     * run that gets them hands them back in [HandsOperator.run]'s finish.
     */
    internal fun claim(): Boolean = _active.compareAndSet(expect = false, update = true)

    /** The run in progress, so Stop can reach it from anywhere. */
    @Volatile internal var current: HandsOperator? = null

    /** Ends the run in progress after the action in flight; a no-op when none. */
    fun stopCurrent(reason: String = "stopped from the app") { current?.requestStop(reason) }

    // ── what the agent is told ─────────────────────────────────────────────

    /**
     * The system-prompt paragraph: the ladder, and the CLI when the hands are usable. English,
     * like the rest of the prompt; honest about what is off so the agent explains and links the
     * setting instead of pretending to tap.
     */
    fun promptParagraph(context: Context): String {
        val on = enabled(context)
        val r = readiness(context)
        val handsName = com.openminis.app.i18n.LocaleWrap.uiContext(context).getString(com.openminis.app.R.string.mo_hands_title)
        return buildString {
            append("## Apps without an API — the ladder (Momoding)\n")
            append("Most everyday apps here (12306, 微信, 支付宝, 美团, 淘宝, 京东…) have no API. Climb in this order (never mention the order, rungs or levels to the person): ")
            append("(1) a skill, a CLI or an MCP server; (2) the page fetched with the user's login or `browser_use`; (3) the phone's own screen, last. ")
            append("A task is rarely all screen: find the number with a command, the button with the screen, and ask before it is pressed. ")
            append("Use the screen only for a step that needs an app's screen; never to look around the phone or for a routine's trial run. When the person asks you to open an app or a page and look, open it with the screen and answer from what it shows.\n")
            if (on && r.ready) {
                append("The screen is available: `momoding-hands run --task \"<one clear task, with every detail the hands need>\" [--app \"<app name>\"]` ")
                append("(screen model: ${r.model?.label}). It reads the screen's elements (a screenshot when they are not enough) and taps, types and swipes; a capsule with Stop shows the user what it does. ")
                append("It never types passwords, codes or card numbers: for a login or a code it hands the phone to the user and waits. Taps that pay, send, post or delete go through the same approval card as the shell and the browser. ")
                append("It returns JSON: `outcome` is done (with `answer`), stopped (the user tapped Stop — do not restart it), needs_user (`question` — relay it and wait), infeasible or failed (with `message`); `last_screen` is a picture you can show with `![screen](<last_screen>)`; `done` lists the taps that paid, sent or deleted and went through — report those as done whatever the outcome. ")
                append("Tell the user in one line that you are about to use the screen and which app, then run it; give one task per run, and never a payment as the task. `momoding-hands apps` lists the installed apps; `momoding-hands status` says what is set up.")
            } else if (on) {
                val missing = buildList {
                    if (!r.androidOk) add("Android 11 or newer")
                    if (!r.serviceOn) add("the accessibility service is off")
                    if (!r.overlayOk) add("no permission to draw over other apps")
                    if (r.model == null) add("no model that can see pictures")
                }.joinToString(", ")
                append("The screen is switched on but not usable yet ($missing). If a task needs it, say so in one line and give the link [$handsName]($DEEP_LINK) (label in the person's language) — it opens the setting; do not try android-a11y-cli instead.")
            } else {
                append("Operating the phone's screen is switched off (the default). If a task can only be done on the screen, say so in one line and give the link [$handsName]($DEEP_LINK) (label in the person's language) so the user can switch it on; do not try android-a11y-cli instead, and do not pretend.")
            }
            // the phone's own files: one switch away; once on, the whole storage is mounted for the shell
            if (app.momoding.files.PhoneFiles.allowed()) {
                kotlinx.coroutines.MainScope().launch { app.momoding.files.PhoneFiles.ensureMounted(context) }
                append("\nThe phone's own files (Download, Documents, DCIM, Pictures…) are at /var/minis/mounts/phone.")
            } else {
                append("\nThe phone's own files (Download, Documents, photos) are not open to you yet: when a task needs them, ask with the permission card, need \"files\" (one switch, nothing to mount by hand).")
            }
            // working on after the person leaves: the phone's battery saver freezes apps that are not let off
            if (!com.openminis.app.power.PowerOptimizationManager.isIgnoringBatteryOptimizations(context)) {
                append("\nWorking on after the person leaves the app is not allowed yet: the phone may freeze you as soon as they switch away. When a task will keep going (the screen, a long job) or the last one stopped when they left, ask for it with the permission card, need \"background\".")
            }
        }
    }
}
