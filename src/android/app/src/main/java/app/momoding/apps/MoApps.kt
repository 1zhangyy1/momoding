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
 *         data.json             a read-only copy of what the app keeps (momoding.store), for its
 *                               Momoding to read; the store itself is outside the sandbox
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
        /** Outside the sandbox (`files/momoding-app-data/`): what nothing run there may change — the person's data, their answers. */
        val vault: File get() = File(dir.parentFile!!.parentFile!!.parentFile!!, "momoding-app-data").also { it.mkdirs() }

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

    fun remove(context: Context, app: App) { removeData(context, app); app.dir.deleteRecursively() }

    // ── what the app keeps ────────────────────────────────────────────────────

    /**
     * What the person's app keeps lives outside the sandbox (`files/momoding-app-data/<name>.json`),
     * so nothing run there can change it; `.momoding/data.json` is a read-only copy kept current for
     * the app's Momoding to read. In the agent's browser ([preview]) the app keeps a copy of its own,
     * `.momoding/preview.json`, made from the person's data the first time it is checked there.
     */
    private fun store(context: Context, app: App): File {
        val f = File(app.vault, "${app.name}.json")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            // apps from before the store moved out of the sandbox
            File(app.platform, "data.json").takeIf { it.isFile }?.let { it.setWritable(true); it.copyTo(f) }
        }
        return f
    }

    private fun previewFile(context: Context, app: App): File =
        File(app.platform, "preview.json").also { p -> if (!p.exists()) store(context, app).takeIf { it.exists() }?.copyTo(p) }

    @Synchronized fun data(context: Context, app: App, preview: Boolean = false): JSONObject =
        runCatching { JSONObject((if (preview) previewFile(context, app) else store(context, app)).readText()) }.getOrDefault(JSONObject())

    @Synchronized fun dataPut(context: Context, app: App, key: String, valueJson: String?, preview: Boolean = false) {
        val all = data(context, app, preview)
        if (valueJson == null) all.remove(key) else all.put(key, org.json.JSONTokener(valueJson).nextValue())
        val target = if (preview) previewFile(context, app) else store(context, app)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(all.toString())
        tmp.renameTo(target)
        if (!preview) {
            val copy = File(app.platform, "data.json")
            copy.setWritable(true)
            copy.writeText(all.toString(2))
            copy.setReadOnly()
        }
    }

    fun removeData(context: Context, app: App) {
        app.vault.listFiles().orEmpty().filter { it.name.startsWith(app.name + ".") }.forEach { it.delete() }
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
