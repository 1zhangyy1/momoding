package app.momoding.apps

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Momoding Apps: small apps that live on the phone, each with its own Momoding inside
 * (调研/M27). An app is a folder the agent works in the way it works on any web project:
 *
 *     /var/minis/apps/<name>/   a git repository: index.html, its code, manifest.json (a PWA
 *                               manifest: name, icons, theme_color), NOTES.md (the app's memory)
 *     <name>/.momoding/         the platform's, kept out of git:
 *         bot.json              its Bot's chat and the chat that asked for it
 *         data.json             what the app keeps (momoding.store)
 *         log                   the app's console and errors
 *
 * Versions are git's; checking is the agent's own browser. This only knows where things are.
 */
object MoApps {
    const val LINUX_ROOT = "/var/minis/apps"
    private const val LOG_MAX = 200_000L
    private val NAME = Regex("^[a-z][a-z0-9-]{1,31}$")

    class App(val name: String, val dir: File) {
        val linuxDir: String get() = "$LINUX_ROOT/$name"
        val index: File get() = File(dir, "index.html")
        val platform: File get() = File(dir, ".momoding").also { it.mkdirs() }

        /** The manifest's name, else the folder's. */
        val title: String get() = manifest().optString("short_name").ifBlank { manifest().optString("name") }.ifBlank { name }
        val themeColor: String? get() = manifest().optString("theme_color").ifBlank { null }

        fun manifest(): JSONObject = runCatching { JSONObject(File(dir, "manifest.json").readText()) }.getOrDefault(JSONObject())

        /** The app's Bot: its chat session, and the chat that asked for the app. */
        fun bot(): JSONObject = runCatching { JSONObject(File(platform, "bot.json").readText()) }.getOrDefault(JSONObject())
        val botSession: String? get() = bot().optString("session").ifBlank { null }
        val askedIn: String? get() = bot().optString("askedIn").ifBlank { null }

        fun setBot(session: String, askedIn: String?) {
            File(platform, "bot.json").writeText(bot().put("session", session).put("askedIn", askedIn ?: "").toString(2))
        }
    }

    fun root(context: Context): File = File(context.filesDir, "minis-global/apps").also { it.mkdirs() }

    fun validName(name: String) = NAME.matches(name)

    fun get(context: Context, name: String): App? =
        name.takeIf(::validName)?.let { File(root(context), it) }?.takeIf { it.isDirectory }?.let { App(name, it) }

    fun list(context: Context): List<App> =
        root(context).listFiles().orEmpty().filter { it.isDirectory && validName(it.name) }.map { App(it.name, it) }
            .sortedByDescending { it.dir.lastModified() }

    /** The app whose Bot this chat is, if it is one. */
    fun ofSession(context: Context, sessionId: String): App? = list(context).firstOrNull { it.botSession == sessionId }

    /** A new app folder with what every app starts from; the Bot writes the rest. */
    fun create(context: Context, name: String, title: String): App {
        require(validName(name)) { "name: a short lowercase word (a-z, 0-9, -), like ledger or water" }
        val dir = File(root(context), name)
        require(!dir.exists()) { "an app called $name already exists" }
        dir.mkdirs()
        val app = App(name, dir)
        File(dir, ".gitignore").writeText(".momoding/\n")
        File(dir, "manifest.json").writeText(JSONObject().put("name", title).put("short_name", title)
            .put("start_url", "index.html").put("display", "standalone").toString(2) + "\n")
        File(dir, "NOTES.md").writeText("# $title\n\nWhat it is for, what the person asked, what was decided. Keep it current.\n")
        app.platform
        return app
    }

    fun remove(context: Context, app: App) { app.dir.deleteRecursively() }

    // ── what the app keeps ────────────────────────────────────────────────────

    /**
     * The person's data, or in the agent's browser ([preview]) a copy of it, made the first time the
     * app is checked there: checking never touches what the person keeps.
     */
    private fun dataFile(app: App, preview: Boolean): File {
        val real = File(app.platform, "data.json")
        if (!preview) return real
        return File(app.platform, "preview.json").also { if (!it.exists() && real.exists()) real.copyTo(it) }
    }

    @Synchronized fun data(app: App, preview: Boolean = false): JSONObject =
        runCatching { JSONObject(dataFile(app, preview).readText()) }.getOrDefault(JSONObject())

    @Synchronized fun dataPut(app: App, key: String, valueJson: String?, preview: Boolean = false) {
        val all = data(app, preview)
        if (valueJson == null) all.remove(key) else all.put(key, org.json.JSONTokener(valueJson).nextValue())
        val file = dataFile(app, preview)
        val tmp = File(app.platform, file.name + ".tmp")
        tmp.writeText(all.toString())
        tmp.renameTo(file)
    }

    // ── the app's log, which its Bot reads when something is wrong ───────────

    fun log(app: App, level: String, line: String) {
        val f = File(app.platform, "log")
        runCatching {
            if (f.length() > LOG_MAX) f.writeText(f.readLines().takeLast(400).joinToString("\n") + "\n")
            val at = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$at $level ${line.take(2000)}\n")
        }
    }
}
