package app.momoding.apps

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The keys an app may borrow (调研/M27 §5): what Momoding holds is the apps' too, lent by name and
 * never shown. The page writes `{{NAME}}` where a key goes; momoding.http puts the key in only on
 * the way to the one site it belongs to, so neither the page nor its Momoding ever has it.
 *
 * A model service's key goes to that service's address. A key the person types in for an app is
 * kept with Momoding's other secrets (Settings → environment variables), for every app, and goes
 * to the site named when it was asked for.
 */
object AppKeys {
    class Key(val name: String, val about: String, val host: String, val value: () -> String?)

    private val PLACEHOLDER = Regex("""\{\{([A-Za-z][A-Za-z0-9_]*)\}\}""")

    fun all(context: Context): List<Key> {
        val app = context.applicationContext as MinisApp
        val providers = app.providerRepositoryOrNull?.let { repo ->
            repo.instances.filter { it.isEnabled && it.credentialType == ProviderCredential.apiKey && repo.loadApiKey(it.id) != null }
                .map { Key(name(it.label), "${it.label}: the model service Momoding uses", host(repo.apiBaseURL(it)), { repo.loadApiKey(it.id) }) }
        }.orEmpty()
        val env = app.envVarRepository
        val typed = env.entries.value.filter { it.host.isNotEmpty() }
            .map { e -> Key(e.key, e.note, e.host, { env.getValue(e.key) }) }
        // the first of a name wins: a provider's label can repeat
        return (typed + providers).distinctBy { it.name }
    }

    fun list(context: Context): JSONArray = JSONArray().apply {
        all(context).forEach { put(JSONObject().put("name", it.name).put("about", it.about).put("site", it.host)) }
    }

    fun has(context: Context, name: String) = all(context).any { it.name == name.uppercase() }

    /** [url] and [headers] with each `{{NAME}}` filled in, if it is on its way to that key's site. */
    fun fill(context: Context, url: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
        val named = (listOf(url) + headers.values).flatMap { PLACEHOLDER.findAll(it).map { m -> m.groupValues[1].uppercase() } }.toSet()
        if (named.isEmpty()) return url to headers
        val keys = all(context).associateBy { it.name }
        val to = host(url)
        val values = named.associateWith { n ->
            val k = keys[n] ?: throw IllegalStateException("no key called $n: momoding.keys() lists them; momoding.key(\"$n\", {site, why}) asks the person")
            if (to != k.host && !to.endsWith("." + k.host)) throw IllegalStateException("$n goes to ${k.host} only, not $to")
            k.value() ?: throw IllegalStateException("$n has no value")
        }
        fun put(s: String) = PLACEHOLDER.replace(s) { values.getValue(it.groupValues[1].uppercase()) }
        return put(url) to headers.mapValues { put(it.value) }
    }

    // ── asking the person for one Momoding lacks ─────────────────────────────

    class Request(val app: MoApps.App, val name: String, val site: String, val why: String) {
        internal val answer = CompletableDeferred<String?>()
        fun answer(value: String?) { answer.complete(value?.trim()?.takeIf { it.isNotEmpty() }) }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    /** The key being asked for on an app's screen now: the app's host shows Momoding's own field for it. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()
    private val asking = Mutex()

    /** Whether Momoding has [name] now, asked of the person on [app]'s screen if not. */
    suspend fun ask(context: Context, app: MoApps.App, name: String, site: String, why: String): Boolean {
        val n = name.trim().uppercase()
        require(Regex("^[A-Z][A-Z0-9_]*$").matches(n)) { "a key's name is a word like OPENAI_KEY" }
        if (has(context, n)) return true
        val h = host(if ("://" in site) site else "https://$site")
        require(h.contains('.')) { "site: where the key is sent, like api.openai.com" }
        return asking.withLock {
            if (has(context, n)) return@withLock true
            val request = Request(app, n, h, why.trim())
            _pending.value = request
            val value = MoApps.waiting(app, "a key, $n for $h, typed into Momoding's own field (asked on its screen)",
                { withTimeoutOrNull(5 * 60_000) { request.answer.await() } }, { if (it != null) "gave it" else "did not give it" })
            _pending.value = null
            value != null && (context.applicationContext as MinisApp).envVarRepository.add(n, value, why.trim(), h)
        }
    }

    private fun host(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()

    /** A label as a key's name: "OpenRouter" → OPENROUTER. */
    private fun name(label: String) = label.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').let { if (it.firstOrNull()?.isLetter() == true) it else "KEY_$it" }
}
