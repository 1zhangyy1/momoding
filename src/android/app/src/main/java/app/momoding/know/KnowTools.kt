package app.momoding.know

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scripts that already did an errand once, kept to run again (调研/M50 step 4). A helper that looked
 * up a timetable spent a dozen rounds working out a site's API, wrote a script that worked, and the
 * script was lost with its temp folder; the next helper worked it all out again. A lesson in words
 * does not save those rounds; the working script does. Kept at
 * /var/minis/memory/know-how/tools/<name>.<ext>, with what it does and how to run it in tools.json;
 * `momoding-know lookup` lists the ones that match, ready to run.
 */
object KnowTools {
    const val SANDBOX_DIR = "/var/minis/memory/know-how/tools"
    const val MAX_TOOLS = 50
    const val MAX_BYTES = 64_000

    data class Tool(val name: String, val file: String, val what: String, val usage: String, val site: String?, val at: Long)

    fun dir(context: Context): File = File(KnowHow.dir(context), "tools")
    private fun index(context: Context): File = File(dir(context), "tools.json")

    fun all(context: Context): List<Tool> = runCatching {
        val a = JSONArray(index(context).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Tool(it.getString("name"), it.getString("file"), it.optString("what"), it.optString("usage"), it.optString("site").ifBlank { null }, it.optLong("at"))
        }
    }.getOrDefault(emptyList())

    /** Keep [script] as tool [name]; the same name replaces the older one. Null when it is not a usable tool. */
    fun save(context: Context, name: String, ext: String, script: String, what: String, usage: String, site: String?, now: Long = System.currentTimeMillis()): Tool? {
        val slug = slug(name) ?: return null
        val suffix = ext.lowercase().takeIf { Regex("""[a-z0-9]{1,5}""").matches(it) } ?: "sh"
        if (script.isBlank() || script.length > MAX_BYTES || what.isBlank() || usage.isBlank()) return null
        val tool = Tool(slug, "$slug.$suffix", what.trim().take(160), usage.trim().take(200), site?.trim()?.lowercase()?.ifBlank { null }, now)
        synchronized(this) {
            dir(context).mkdirs()
            File(dir(context), tool.file).writeText(script)
            val kept = all(context).filterNot { it.name == slug }
            (kept.sortedByDescending { it.at }.drop(MAX_TOOLS - 1)).forEach { File(dir(context), it.file).delete() }
            write(context, listOf(tool) + kept.sortedByDescending { it.at }.take(MAX_TOOLS - 1))
        }
        return tool
    }

    /** Tools whose name, site or description matches [query]. */
    fun lookup(context: Context, query: String): List<Tool> = match(all(context), query)

    internal fun match(tools: List<Tool>, query: String): List<Tool> {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return tools.filter { t ->
            val hay = "${t.name} ${t.site.orEmpty()} ${t.what}".lowercase()
            words.any { w -> hay.contains(w) || (t.site != null && w.contains(t.site)) }
        }.take(5)
    }

    fun render(t: Tool): JSONObject = JSONObject()
        .put("tool", t.name).put("what", t.what)
        .put("path", "$SANDBOX_DIR/${t.file}").put("usage", t.usage)

    internal fun slug(name: String): String? =
        name.trim().lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').take(40).takeIf { it.length >= 3 }

    private fun write(context: Context, tools: List<Tool>) {
        index(context).writeText(JSONArray().apply {
            tools.forEach { t ->
                put(JSONObject().put("name", t.name).put("file", t.file).put("what", t.what).put("usage", t.usage).put("site", t.site ?: "").put("at", t.at))
            }
        }.toString(1))
    }
}
