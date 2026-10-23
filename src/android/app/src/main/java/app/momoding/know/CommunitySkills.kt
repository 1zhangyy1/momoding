package app.momoding.know

import android.content.Context
import android.util.Base64
import com.openminis.app.MinisApp
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The shared half of 调研/M21: skills distilled from errands that worked, kept in Momoding's
 * community repo (github.com/1zhangyy1/momoding-skills, mirrored to Gitee for China) and reviewed
 * there. `index.json` lists each skill's sites and topics; it is fetched at most once a day,
 * Gitee first. `momoding-know lookup` matches against it, `install` imports a skill, `share`
 * hands the person a link to contribute one — the person submits it, never the app.
 */
object CommunitySkills {
    const val GITHUB = "1zhangyy1/momoding-skills"
    const val GITEE = "nuomijizhangyy/momoding-skills"
    private const val DAY_MS = 24 * 3600_000L

    data class Item(val name: String, val description: String, val sites: List<String>, val topics: List<String>, val verified: String, val path: String)

    private fun cache(context: Context) = File(File(context.filesDir, "momoding"), "community-index.json")

    /** The catalogue: the cached copy, refreshed when older than a day (or [force]); empty when nothing could be read. */
    fun index(context: Context, force: Boolean = false): List<Item> {
        val f = cache(context)
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < DAY_MS
        if (!fresh || force) fetch("index.json")?.let { body -> parseIndex(body).takeIf { it.isNotEmpty() }?.let { f.parentFile?.mkdirs(); f.writeText(body) } }
        return runCatching { parseIndex(f.readText()) }.getOrDefault(emptyList())
    }

    /** Import [name] into the skills; the installed SKILL.md path, or null. */
    fun install(context: Context, name: String): String? {
        val item = index(context).firstOrNull { it.name == name } ?: return null
        val content = fetch(item.path) ?: return null
        val repo = (context.applicationContext as? MinisApp)?.skillRepository ?: return null
        val skill = repo.importFromContent(content, SkillRepository.ImportSource.URL, "https://github.com/$GITHUB/blob/main/${item.path}") ?: return null
        return repo.skillMdPath(skill.id)
    }

    // ── pure parts (tests) ─────────────────────────────────────────────────

    internal fun parseIndex(body: String): List<Item> = runCatching {
        val arr = JSONObject(body).optJSONArray("skills") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val name = o.optString("name").takeIf { Regex("""^[a-z0-9][a-z0-9-]{1,60}$""").matches(it) } ?: return@mapNotNull null
            val path = o.optString("path").takeIf { it == "skills/$name/SKILL.md" } ?: return@mapNotNull null
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
            Item(name, o.optString("description").take(300), list("sites"), list("topics"), o.optString("verified"), path)
        }
    }.getOrDefault(emptyList())

    /** Skills whose sites, topics, name or description match any word of [query]. */
    internal fun match(items: List<Item>, query: String): List<Item> {
        val words = query.lowercase().split(Regex("""\s+""")).map { it.removePrefix("www.") }.filter { it.length >= 2 }
        if (words.isEmpty()) return emptyList()
        return items.filter { i ->
            words.any { w -> i.sites.any { s -> s.contains(w) || w.contains(s) } || i.topics.any { it.lowercase().contains(w) || w.contains(it.lowercase()) } ||
                i.name.contains(w) || i.description.lowercase().contains(w) }
        }.take(5)
    }

    /** A GitHub page that opens a pull request adding [text] as skills/[name]/SKILL.md (the person signs in and submits). */
    internal fun shareUrl(name: String, text: String): String =
        "https://github.com/$GITHUB/new/main?filename=" + URLEncoder.encode("skills/$name/SKILL.md", "UTF-8") + "&value=" + URLEncoder.encode(text, "UTF-8")

    // ── network ────────────────────────────────────────────────────────────

    /** A file of the repo, from Gitee's API (reachable in China), else GitHub raw. */
    private fun fetch(path: String): String? =
        runCatching {
            JSONObject(get("https://gitee.com/api/v5/repos/$GITEE/contents/$path?ref=main") ?: error("no gitee")).optString("content")
                .takeIf { it.isNotEmpty() }?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
        }.getOrNull() ?: get("https://raw.githubusercontent.com/$GITHUB/main/$path")

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 10000
            if (responseCode != 200) null else inputStream.bufferedReader().readText()
        }
    }.getOrNull()
}
