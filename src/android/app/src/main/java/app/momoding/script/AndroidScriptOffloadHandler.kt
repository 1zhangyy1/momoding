package app.momoding.script

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import org.json.JSONObject

/**
 * `momoding-android` — a script against Android's own API, run inside Momoding:
 *
 *     momoding-android run <file.js> [--timeout 20]
 *     momoding-android run -e "<code>"
 */
class AndroidScriptOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        return when (val sub = args.positional.firstOrNull()) {
            null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
            "run" -> run(args, request)
            else -> NativeOffloadResult(2, "momoding-android: unknown subcommand '$sub'\n$HELP")
        }
    }

    private fun run(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val given = args.positional.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
        val code = (if ("e" in args.optionNames) given else given?.let { readSandboxFile(it, request) })
            ?: return NativeOffloadResult(2, "momoding-android run <file.js> | run -e \"<code>\"\n")
        val timeout = (args.get("timeout")?.toLongOrNull() ?: 20L).coerceIn(1L, 300L) * 1000
        var r = AndroidScript.run(context, code, request.sessionId, request.binding, timeout)
        // a permission Momoding declares but does not hold yet: on by itself where it can (advanced
        // access), and the script runs again; else the model is told which card to show
        val missing = r.error?.let { PERMISSION.find(it)?.value }
        if (missing != null && app.momoding.guard.SelfGrant.grant(context, listOf(missing))) {
            r = AndroidScript.run(context, code, request.sessionId, request.binding, timeout)
        }
        val o = JSONObject()
        if (r.output.isNotEmpty()) o.put("output", r.output)
        r.value?.let { o.put("result", it) }
        r.error?.let { o.put("error", it) }
        if (missing != null && r.error != null) o.put("note", "Momoding needs $missing for this. Say in one line what it is for and end with the permission card {\"need\": \"$missing\"}.")
        app.momoding.guard.SelfGrant.notice().takeIf { it.isNotBlank() }?.let { o.put("note", it.trim()) }
        return NativeOffloadResult(if (r.error == null) 0 else 1, o.toString(2) + "\n")
    }

    private fun readSandboxFile(path: String, request: NativeOffloadRequest): String? {
        val linux = if (path.startsWith("/")) path else request.cwd.trimEnd('/') + "/" + path
        val host = request.sessionId?.let { com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it) }
            ?.let { com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(it, linux, context) }
            ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(linux)
        return host?.takeIf { it.isFile && it.length() < 256_000 }?.readText()
    }

    companion object {
        private val PERMISSION = Regex("""android\.permission\.[A-Z_]+""")
        private val HELP = """
            momoding-android — run a script against Android's own API, inside Momoding, with its permissions.

              run <file.js> [--timeout 20]     a script file in the sandbox
              run -e "<code>"                   a few lines inline

            JavaScript (Rhino, ES6) with Java at hand: `context` is the app's Context, classes by their
            full name (android.os.BatteryManager, java.io.File), print(...) to say something, the last
            expression is the result. ui(function(){ ... }) runs on the main thread. A JS function can
            stand for a Java interface (a listener); extending a Java class is not supported.

            Example:
              var bm = context.getSystemService("batterymanager");
              bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%"

            What reaches another person (a text, a call) or loses the person's data (deleting or
            changing contacts, calendar, messages, photos, phone files) asks them first, by itself.
            A script that worked: keep it with `momoding-know tool` to run it again next time.
        """.trimIndent() + "\n"
    }
}
