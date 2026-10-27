package app.momoding.apps

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.momoding.guard.GateOutcome
import app.momoding.guard.RiskGate
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * `window.momoding`: what an app can do beyond its page. Few calls, each wide, so the Bot builds
 * with what it already knows (Fibona hands its agent the whole SDK; an app gets the agent's own
 * phone commands, through the same approvals as everywhere in Momoding):
 *
 *     momoding.run("android-calendar list --next 2")   a phone command, as the agent runs them
 *     momoding.ask(prompt, {system, json})             the person's model, as a function
 *     momoding.http(url, {method, headers, body})      the web: public addresses, no CORS
 *     momoding.store.get/set/remove/all                what the app keeps; its Bot can read it
 *     momoding.chat(text)                              the app's own Momoding, to change it
 *
 * One bridge per WebView, for every app loaded in it: each call carries the token its page was
 * served with (AppPage), which names the app. [onScreen]: the person is using the app (its host);
 * false in the agent's browser, where `chat` only logs.
 */
class AppBridge(private val context: Context, private val webView: WebView, private val onScreen: Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    fun close() = scope.cancel()

    private fun app(token: String): MoApps.App? = AppPage.appFor(token)?.let { MoApps.get(context, it) }

    @JavascriptInterface
    fun info(token: String): String {
        val app = app(token) ?: return "{}"
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return JSONObject()
            .put("app", JSONObject().put("name", app.name).put("title", app.title))
            .put("device", JSONObject().put("dark", night).put("language", Locale.getDefault().toLanguageTag()))
            .put("preview", !onScreen)
            .toString()
    }

    // in the agent's browser the app keeps a copy of the person's data, never theirs (MoApps.data)
    @JavascriptInterface fun storeGet(token: String, key: String): String? = app(token)?.let { MoApps.data(context, it, !onScreen).opt(key) }?.let(::literal)
    @JavascriptInterface fun storeSet(token: String, key: String, valueJson: String?) { app(token)?.let { MoApps.dataPut(context, it, key, valueJson, !onScreen) } }
    @JavascriptInterface fun storeAll(token: String): String = app(token)?.let { MoApps.data(context, it, !onScreen).toString() } ?: "{}"

    @JavascriptInterface
    fun log(token: String, level: String, line: String) {
        // what the agent's own checks print is marked, so the app's log tells them from the person's use
        app(token)?.let { MoApps.log(it, (if (level == "error") "ERROR" else "log") + if (onScreen) "" else " preview", line) }
    }

    @JavascriptInterface
    fun chat(token: String, text: String) {
        val app = app(token) ?: return
        if (!onScreen) { MoApps.log(app, "log", "momoding.chat(${JSONObject.quote(text)}) — opens the app's chat on the phone"); return }
        main.post { AppChat.open(context, app, text) }
    }

    /** The async calls: the page waits on a promise, answered by __momodingResolve(id, ok, value). */
    @JavascriptInterface
    fun call(token: String, id: Int, method: String, argsJson: String) {
        val app = app(token) ?: return
        scope.launch {
            val args = runCatching { JSONArray(argsJson) }.getOrDefault(JSONArray())
            val result = runCatching {
                when (method) {
                    "ask" -> ask(args.optString(0), args.optJSONObject(1) ?: JSONObject())
                    "run" -> run(app, args.optString(0))
                    "http" -> http(args.optString(0), args.optJSONObject(1) ?: JSONObject())
                    "tell" -> tell(app, args.optString(0))
                    else -> throw IllegalArgumentException("momoding.$method does not exist")
                }
            }
            result.exceptionOrNull()?.let { MoApps.log(app, "ERROR", "momoding.$method: ${it.message}") }
            val js = result.fold(
                { "window.__momodingResolve($id,true,${literal(it)})" },
                { "window.__momodingResolve($id,false,${JSONObject.quote(it.message ?: it.toString())})" },
            )
            main.post { runCatching { webView.evaluateJavascript(js, null) } }
        }
    }

    private suspend fun ask(prompt: String, opts: JSONObject): Any {
        val model = app.momoding.hands.Hands.textModel(context) ?: throw IllegalStateException("no model is set up in Momoding")
        val provider = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, context)
        val json = opts.optBoolean("json")
        val system = opts.optString("system").ifBlank { "You are part of a small app on the person's phone. Answer the app's request directly." } +
            if (json) "\nAnswer with one JSON value only: no prose, no code fence." else ""
        val text = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
            systemPrompt = system,
            maxTokens = opts.optInt("maxTokens", 4096),
        ).text.orEmpty().trim()
        if (!json) return text
        val body = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { JSONTokener(body).nextValue() }.getOrElse { throw IllegalStateException("the model did not answer JSON: ${text.take(200)}") }
    }

    private suspend fun run(app: MoApps.App, command: String): JSONObject {
        val argv = words(command)
        val name = argv.firstOrNull() ?: throw IllegalArgumentException("momoding.run needs a command")
        if (!runnable(name)) throw IllegalArgumentException("$name is not a phone command an app can run (android-*, momoding-web, minis-scheduled)")
        val handler = NativeOffloadServer.handler(name) ?: throw IllegalArgumentException("$name is not available")
        // what this app may use is the person's choice, asked on its screen (the agent's own checks are its work, not the app's)
        if (onScreen && !AppPermissions.check(app, name)) throw IllegalStateException("the person has not allowed this app to use $name")
        // the same gate as the agent's own commands, in the app's Bot chat (and on the app's screen)
        when (val gate = RiskGate.checkShell(app.botSession ?: (GATE_PREFIX + app.name), command, summary = "${app.title}: $command")) {
            is GateOutcome.Denied -> throw IllegalStateException(gate.message)
            is GateOutcome.Allowed -> Unit
        }
        val r = handler.handle(NativeOffloadRequest(pid = 0, argv = argv, env = emptyMap(), cwd = "/", sessionId = app.botSession))
        val out = JSONObject().put("code", r.exitCode).put("output", r.output)
        runCatching { JSONTokener(r.output.trim()).nextValue() }.getOrNull()?.takeIf { it is JSONObject || it is JSONArray }?.let { out.put("json", it) }
        AppLogger.info(TAG, "${app.name} ran $name -> ${r.exitCode}")
        return out
    }

    /** Something for the app's own Momoding to do, in its chat; on the screen only (in a check it is logged). */
    private fun tell(app: MoApps.App, text: String): String {
        require(text.isNotBlank()) { "momoding.tell needs what to ask" }
        if (!onScreen) { MoApps.log(app, "log preview", "momoding.tell(${JSONObject.quote(text)}) — goes to the app's Momoding on the phone"); return "sent" }
        AppBots.tell(context, app, text, AppBots.From.APP)
        return "sent"
    }

    private fun http(url: String, opts: JSONObject): JSONObject {
        val method = opts.optString("method", "GET").uppercase()
        val b = Request.Builder().url(url)
        val headers = opts.optJSONObject("headers")
        headers?.keys()?.forEach { k -> b.header(k, headers.getString(k)) }
        if (method != "GET" && method != "HEAD") {
            val body = opts.opt("body")?.let { if (it is String) it else it.toString() }.orEmpty()
            b.method(method, body.toRequestBody(headers?.optString("Content-Type")?.toMediaTypeOrNull()))
        }
        app.momoding.web.WebLookup.client.newCall(b.build()).execute().use { r ->
            val text = r.body?.source()?.let { s -> s.request(MAX_HTTP); s.buffer.readUtf8(minOf(s.buffer.size, MAX_HTTP)) }.orEmpty()
            return JSONObject().put("status", r.code).put("text", text).put("url", r.request.url.toString())
        }
    }

    companion object {
        private const val TAG = "AppBridge"
        const val GATE_PREFIX = "app:"
        const val JS_NAME = "__momodingNative"
        private const val MAX_HTTP = 2L * 1024 * 1024

        /** [value] as a JSON literal: a string quoted, an object or array as it is. */
        fun literal(value: Any?): String = JSONArray().put(value ?: JSONObject.NULL).toString().removePrefix("[").removeSuffix("]")

        /** The agent's phone commands, not the Linux shell and not the screen. */
        fun runnable(name: String) = name.startsWith("android-") || name == "momoding-web" || name == "minis-scheduled"

        /** A command line split as the shell would: spaces, '…' and "…" quotes, backslash escapes. */
        fun words(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var any = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quote != null && c == quote -> quote = null
                    quote == null && (c == '\'' || c == '"') -> { quote = c; any = true }
                    c == '\\' && quote != '\'' && i + 1 < line.length -> { cur.append(line[++i]); any = true }
                    quote == null && c.isWhitespace() -> { if (any) { out.add(cur.toString()); cur.clear(); any = false } }
                    else -> { cur.append(c); any = true }
                }
                i++
            }
            if (any) out.add(cur.toString())
            return out
        }

        /** The page's first script; [token] binds its calls to its app. */
        fun shim(token: String) = """(() => {
  if (window.momoding) return
  const N = window.$JS_NAME, T = "$token"
  if (!N) return
  let seq = 0
  const waits = {}
  window.__momodingResolve = (id, ok, value) => {
    const w = waits[id]; if (!w) return; delete waits[id]
    ok ? w[0](value) : w[1](new Error(value))
  }
  const call = (method, ...args) => new Promise((resolve, reject) => {
    const id = ++seq; waits[id] = [resolve, reject]; N.call(T, id, method, JSON.stringify(args))
  })
  const text = x => typeof x === 'string' ? x : (x instanceof Error ? (x.stack || x.message) : JSON.stringify(x))
  const info = JSON.parse(N.info(T))
  window.momoding = {
    app: info.app, device: info.device, preview: info.preview,
    store: {
      get: key => { const v = N.storeGet(T, String(key)); return v == null ? undefined : JSON.parse(v) },
      set: (key, value) => N.storeSet(T, String(key), value === undefined ? null : JSON.stringify(value)),
      remove: key => N.storeSet(T, String(key), null),
      all: () => JSON.parse(N.storeAll(T)),
    },
    ask: (prompt, options = {}) => call('ask', String(prompt), options),
    run: command => call('run', String(command)),
    http: (url, options = {}) => call('http', String(url), options),
    chat: (text = '') => N.chat(T, String(text)),
    tell: text => call('tell', String(text)),
  }
  for (const level of ['log', 'warn', 'error']) {
    const original = console[level].bind(console)
    console[level] = (...a) => { N.log(T, level === 'error' ? 'error' : 'log', a.map(text).join(' ')); original(...a) }
  }
  window.addEventListener('error', e => N.log(T, 'error', (e.message || 'error') + ' @' + String(e.filename || '').split('/').pop() + ':' + e.lineno))
  window.addEventListener('unhandledrejection', e => N.log(T, 'error', 'unhandled: ' + text(e.reason)))
})()"""
    }
}
