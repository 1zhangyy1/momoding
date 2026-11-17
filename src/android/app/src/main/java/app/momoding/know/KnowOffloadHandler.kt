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
 *     momoding-know tool --name <name> --file <script> --what "<what it does>" --usage "<how to run it>" [--site <domain>]
 *     momoding-know topics
 *     momoding-know install <community skill>
 *     momoding-know share --name <skill-name> --file <SKILL.md you wrote>
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
                "tool" -> tool(args, request)
                "topics" -> ok(JSONObject().put("topics", JSONArray(KnowHow.areas(context))))
                "install" -> install(args.positional.getOrNull(1))
                "share" -> when (args.positional.getOrNull(1)) {
                    "off", "on" -> { CommunitySkills.setAutoShare(context, args.positional[1] == "on"); ok(JSONObject().put("auto_share", args.positional[1] == "on")) }
                    else -> share(args, request)
                }
                else -> NativeOffloadResult(2, "momoding-know: unknown subcommand '$sub'\n$HELP")
            }
        }.getOrElse { NativeOffloadResult(2, "momoding-know: ${it.message}\n") }
    }

    private fun lookup(query: String): NativeOffloadResult {
        if (query.isBlank()) return NativeOffloadResult(2, "momoding-know lookup <site or words>\n")
        val now = System.currentTimeMillis()
        val sites = SiteLedger.lookup(context, query)
        val lessons = KnowHow.lookup(context, query)
        val tools = KnowTools.lookup(context, query)
        val installed = (context.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.skills?.value?.map { it.id }?.toSet().orEmpty()
        val community = CommunitySkills.match(CommunitySkills.index(context), query)
        return ok(JSONObject()
            .put("tools", JSONArray().apply { tools.forEach { put(KnowTools.render(it)) } })
            .put("sites", JSONArray().apply { sites.forEach { e -> SiteLedger.render(e, now)?.let { put(it) } } })
            .put("lessons", JSONArray(lessons))
            .put("community", JSONArray().apply {
                community.forEach { c ->
                    put(JSONObject().put("skill", c.name).put("description", c.description).put("verified", c.verified)
                        .put(if (c.name in installed) "read" else "install", if (c.name in installed) "/var/minis/skills/${c.name}/SKILL.md" else "momoding-know install ${c.name}"))
                }
            })
            .put("note", (if (tools.isNotEmpty()) "A tool of yours did this errand before: run it first; if the site changed, fix it and keep it again. " else "") +
                "Your own records win over community skills; no record means unknown, not blocked."))
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

    private fun tool(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val name = args.get("name").orEmpty()
        val path = args.get("file") ?: return NativeOffloadResult(2, "momoding-know tool --name <name> --file <script> --what … --usage …\n")
        val script = readSandboxFile(path, request) ?: return NativeOffloadResult(2, "cannot read $path (a file under 64 KB)\n")
        val t = KnowTools.save(context, name, path.substringAfterLast('.', "sh"), script, args.get("what").orEmpty(), args.get("usage").orEmpty(), args.get("site"))
            ?: return NativeOffloadResult(2, "not kept: give --name (letters and digits), --what and --usage\n")
        return ok(KnowTools.render(t).put("kept", true))
    }

    private fun install(name: String?): NativeOffloadResult {
        if (name.isNullOrBlank()) return NativeOffloadResult(2, "momoding-know install <community skill>\n")
        val path = CommunitySkills.install(context, name) ?: return NativeOffloadResult(1, "could not install $name (not in the community index, or offline)\n")
        return ok(JSONObject().put("installed", name).put("read", path))
    }

    private fun share(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val name = args.get("name")?.trim().orEmpty()
        // A SKILL.md starts with "---", which a flag parser reads as another flag: it comes as a file.
        val text = args.get("file")?.let { readSandboxFile(it, request) } ?: args.get("text").orEmpty()
        if (!Regex("""^[a-z0-9][a-z0-9-]{1,60}$""").matches(name) || !text.trimStart().startsWith("---")) {
            return NativeOffloadResult(2, "momoding-know share --name <skill-name> --file <SKILL.md with front matter: name, description, sites, topics, verified>\n")
        }
        if (text.length > 6000) return NativeOffloadResult(2, "too long to share: keep the skill under 6000 characters\n")
        var why: String? = null
        if (CommunitySkills.autoShare(context)) {
            CommunitySkills.submit(context, name, text) { why = it }?.let { s ->
                return ok(JSONObject().put("shared", s.id).put("tell_user",
                    if (s.firstTime) "Say in one line that you shared this route with the community, without anything personal, and that it can be turned off in Settings (or by telling you). From now on you share proven routes without saying so."
                    else "Nothing to say unless asked; it is waiting for review by the community's maintainers."))
            }
            // the relay refused it: fix what it said, or fall back to a link
            if (why?.startsWith("relay unreachable") == false) return NativeOffloadResult(1, "not shared: $why\n")
        }
        return ok(JSONObject().put("link", CommunitySkills.shareUrl(name, text))
            .put("tell_user", (if (why != null) "The automatic share is unavailable right now. " else "") +
                "Give the person this link as a button; they sign in to GitHub and submit it themselves. Nothing has been sent."))
    }

    private fun readSandboxFile(path: String, request: NativeOffloadRequest): String? {
        val linux = if (path.startsWith("/")) path else request.cwd.trimEnd('/') + "/" + path
        // a helper's /var/minis is its parent's (ExecutionCoordinator.mountedSessionIdFor)
        val host = request.sessionId?.let { com.openminis.app.sandbox.ExecutionCoordinator.mountedSessionIdFor(it) }
            ?.let { com.openminis.app.sandbox.PRootKernel.resolveSessionHostPath(it, linux, context) }
            ?: com.openminis.app.sandbox.PRootKernel.resolveHostPath(linux)
        return host?.takeIf { it.isFile && it.length() < 64_000 }?.readText()
    }

    private fun ok(o: JSONObject) = NativeOffloadResult(0, o.toString(2) + "\n")

    companion object {
        private val HELP = """
            momoding-know — what you learned about getting errands done on this phone.

              lookup <site or words>     your records of sites and your lessons that match
              site <domain> --flow "<errand>" --outcome worked|walled|partial --how "<what worked, or what stopped you>"
              lesson --topic <topic> "<where to look and how>" [--replaces "<start of the line it supersedes>"]
              tool --name <name> --file <script> --what "<what it does>" --usage "<how to run it>" [--site <domain>]
                                         keep a script that did an errand, to run it next time
              topics                     topics that have lessons
              install <skill>            install a community skill that lookup found, then read it
              share --name <skill-name> --file <SKILL.md>
                                         share a proven route with the community (a pull request,
                                         reviewed before it is merged); share off / share on

            Keep where and how only: never prices, dates, results or personal details.

            A script that got an errand done (a site's API, parsing its answer) is worth more than
            a lesson in words: keep it with `tool`, with what changes each time (a date, a city)
            as arguments, nothing personal inside. lookup lists it; next time run it instead of
            working the site out again. If it no longer works, fix it and keep it again.

            Record a site when it worked or walled you off; a route worth reusing, or how the person
            wants it done ("火车票直接去 12306 查"), is a lesson. When a route of yours has worked at
            least twice and the community does not have it (or has it worse), share it without
            asking: write a SKILL.md (front matter: name, description, sites, topics, verified date;
            then where to look, how to read it, what walls you off; nothing personal), run share,
            and do what its reply says. If the person says not to share, run `share off`.
        """.trimIndent() + "\n"
    }
}
