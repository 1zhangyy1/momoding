package app.momoding.know

import android.content.Context
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import org.json.JSONArray
import org.json.JSONObject

/**
 * `momoding-know` — what the Bot learned about getting errands done on this phone (调研/M21):
 *
 *     momoding-know lookup <site or words>
 *     momoding-know site <domain> --flow "<errand>" --outcome worked|walled|partial --how "<what worked or stopped you>"
 *     momoding-know lesson --topic <topic> "<where to look and how>" [--replaces "<start of the line it supersedes>"]
 *     momoding-know topics
 *
 * The agent decides what is worth keeping; this only stores it, with limits.
 */
class KnowOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        val sub = args.positional.firstOrNull()
        return runCatching {
            when (sub) {
                null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
                "lookup" -> lookup(args.positional.drop(1).joinToString(" "))
                "site" -> site(args)
                "lesson" -> lesson(args)
                "topics" -> ok(JSONObject().put("topics", JSONArray(KnowHow.areas(context))))
                else -> NativeOffloadResult(2, "momoding-know: unknown subcommand '$sub'\n$HELP")
            }
        }.getOrElse { NativeOffloadResult(2, "momoding-know: ${it.message}\n") }
    }

    private fun lookup(query: String): NativeOffloadResult {
        if (query.isBlank()) return NativeOffloadResult(2, "momoding-know lookup <site or words>\n")
        val now = System.currentTimeMillis()
        val sites = SiteLedger.lookup(context, query)
        val lessons = KnowHow.lookup(context, query)
        return ok(JSONObject()
            .put("sites", JSONArray().apply { sites.forEach { e -> SiteLedger.render(e, now)?.let { put(it) } } })
            .put("lessons", JSONArray(lessons))
            .put("note", if (sites.isEmpty() && lessons.isEmpty()) "Nothing recorded yet: unknown, not blocked." else "Your own records; the newest wins."))
    }

    private fun site(args: OffloadArgs): NativeOffloadResult {
        val domain = args.positional.getOrNull(1) ?: return NativeOffloadResult(2, "momoding-know site <domain> --flow … --outcome … --how …\n")
        val outcome = SiteLedger.Outcome.parse(args.get("outcome")) ?: return NativeOffloadResult(2, "--outcome must be worked, walled or partial\n")
        val e = SiteLedger.record(context, domain, args.get("flow").orEmpty(), outcome, args.get("how").orEmpty())
            ?: return NativeOffloadResult(2, "not recorded: give a site, a short --flow and --how, nothing secret\n")
        return ok(JSONObject().put("recorded", e.domain).put("records", e.records.size))
    }

    private fun lesson(args: OffloadArgs): NativeOffloadResult {
        val topic = args.get("topic") ?: return NativeOffloadResult(2, "momoding-know lesson --topic <topic> \"<lesson>\"\n")
        val line = args.positional.drop(1).joinToString(" ")
        if (!KnowHow.add(context, topic, line, args.get("replaces"))) return NativeOffloadResult(2, "not recorded: a short lesson about where and how, nothing secret\n")
        return ok(JSONObject().put("recorded", KnowHow.slug(topic)))
    }

    private fun ok(o: JSONObject) = NativeOffloadResult(0, o.toString(2) + "\n")

    companion object {
        private val HELP = """
            momoding-know — what you learned about getting errands done on this phone.

              lookup <site or words>     your records of sites and your lessons that match
              site <domain> --flow "<errand>" --outcome worked|walled|partial --how "<what worked, or what stopped you>"
              lesson --topic <topic> "<where to look and how>" [--replaces "<start of the line it supersedes>"]
              topics                     topics that have lessons

            Keep where and how only: never prices, dates, results or personal details.
        """.trimIndent() + "\n"
    }
}
