package app.momoding.task

/** Proof for automatic replay, stricter than permission/risk assessment. Unknown syntax stays unknown. */
internal object ReadOnlyCommand {
    private val readers = setOf("cat", "ls", "head", "tail", "wc", "cut", "tr", "pwd", "stat", "du", "df", "ps", "pgrep",
        "echo", "sleep", "uname", "id", "whoami", "readlink", "realpath", "which", "test", "[", "true", "false",
        "seq", "basename", "dirname", "sha256sum", "sha512sum", "sha1sum", "md5sum", "cksum", "od", "hexdump", "strings", "comm", "diff", "cmp", "grep")

    fun proven(text: String): Boolean {
        val commands = literalCommands(text) ?: return false
        return commands.isNotEmpty() && commands.all(::simple)
    }

    private fun simple(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val raw = words.first()
        if ('/' in raw && raw.substringBeforeLast('/') !in setOf("/bin", "/usr/bin")) return false
        val program = raw.substringAfterLast('/')
        val args = words.drop(1)
        if (program in readers) return true
        return when (program) {
            // Bash printf can assign shell variables (-v / %n), including PATH for later commands.
            "printf" -> args.dropWhile { it == "--" }.firstOrNull()?.let {
                !it.startsWith("-") && !Regex("%[-+ #0-9.*]*n").containsMatchIn(it)
            } == true
            "cd" -> args.size == 1 && !args[0].startsWith("-")
            "command" -> simple(args.dropWhile { it == "--" || it == "-p" })
            "busybox" -> args.dropWhile { it == "--" }.let { nested ->
                if (nested.firstOrNull() == "wget") busyboxGet(nested.drop(1)) else simple(nested)
            }
            "timeout" -> {
                val i = if (args.firstOrNull() == "--") 1 else 0
                args.getOrNull(i)?.matches(Regex("[0-9]+(?:\\.[0-9]+)?[smhd]?")) == true && simple(args.drop(i + 1))
            }
            "sort" -> options(args, "bdfgiMnrRsuzcCmVh", setOf('k', 't', 'S'),
                setOf("reverse", "unique", "numeric-sort", "general-numeric-sort", "human-numeric-sort", "version-sort", "ignore-case", "check", "merge", "stable", "zero-terminated"),
                setOf("key", "field-separator", "buffer-size", "random-source")) != null
            "uniq" -> options(args, "cduiDz", setOf('f', 's', 'w'),
                setOf("count", "repeated", "unique", "ignore-case", "zero-terminated", "all-repeated"), setOf("skip-fields", "skip-chars", "check-chars"))?.size?.let { it <= 1 } == true
            "date" -> options(args, "uIR", setOf('d', 'r', 'f'),
                setOf("utc", "universal", "rfc-email", "iso-8601"), setOf("date", "reference", "file", "rfc-3339"))?.all { it.startsWith("+") } == true
            "base64", "base32" -> options(args, "di", setOf('w'), setOf("decode", "ignore-garbage"), setOf("wrap"))?.size?.let { it <= 1 } == true
            "file" -> options(args, "bcdEfhiklLnNprsSv0", setOf('F', 'e', 'f', 'm', 'P'),
                setOf("brief", "dereference", "no-dereference", "mime", "mime-type", "mime-encoding", "keep-going", "raw", "special-files"),
                setOf("separator", "exclude", "files-from", "magic-file", "parameter")) != null
            "sed" -> sed(args)
            "find" -> find(args)
            "rg" -> args.firstOrNull() == "--no-config" && options(args, "nNiIlLwoxvcsSqUFzHh0a", setOf('e', 'f', 'g', 't', 'T', 'A', 'B', 'C', 'm', 'r'),
                setOf("no-config", "line-number", "ignore-case", "smart-case", "fixed-strings", "word-regexp", "line-regexp", "invert-match", "count", "count-matches", "files-with-matches", "files-without-match", "files", "hidden", "no-ignore", "no-ignore-vcs", "json", "multiline", "text", "null", "no-heading", "with-filename", "no-filename", "only-matching", "stats", "pcre2"),
                setOf("regexp", "file", "glob", "iglob", "type", "type-not", "after-context", "before-context", "context", "max-count", "replace", "encoding", "color", "max-depth", "max-filesize")) != null
            "curl" -> curl(args)
            "git" -> args == listOf("--version") || args == listOf("version")
            else -> false
        }
    }

    /** Parse only known options; unknown future executable/output options cannot silently become replayable. */
    private fun options(args: List<String>, flags: String, values: Set<Char>, longFlags: Set<String>, longValues: Set<String>): List<String>? {
        val positional = mutableListOf<String>(); var i = 0; var ended = false
        while (i < args.size) {
            val arg = args[i++]
            if (ended || !arg.startsWith("-") || arg == "-") { positional += arg; continue }
            if (arg == "--") { ended = true; continue }
            if (arg.startsWith("--")) {
                val name = arg.drop(2).substringBefore('=')
                when {
                    name in longValues -> if ('=' !in arg && i++ >= args.size) return null
                    name in longFlags && '=' !in arg -> Unit
                    else -> return null
                }
            } else {
                var j = 1
                while (j < arg.length) {
                    val c = arg[j++]
                    if (c in values) { if (j == arg.length && i++ >= args.size) return null; break }
                    if (c !in flags) return null
                }
            }
        }
        return positional
    }

    private fun sed(args: List<String>): Boolean {
        val rest = args.dropWhile { it == "-n" || it == "--quiet" || it == "--silent" }
        val script = rest.firstOrNull() ?: return false
        return !script.startsWith("-") && script.split(';').all {
            it.trim().matches(Regex("(?:(?:[0-9]+|\\$)(?:,(?:[0-9]+|\\$))?)?p"))
        } && rest.drop(1).none { it.startsWith("-") }
    }

    private fun find(args: List<String>): Boolean {
        val predicates = setOf("-name", "-iname", "-path", "-ipath", "-regex", "-iregex", "-type", "-size", "-mtime", "-mmin", "-atime", "-amin", "-ctime", "-cmin", "-maxdepth", "-mindepth", "-user", "-group", "-uid", "-gid", "-perm", "-newer", "-samefile")
        val flags = setOf("-print", "-print0", "-ls", "-empty", "-readable", "-writable", "-executable", "-true", "-false", "-not", "-a", "-and", "-o", "-or", "-prune", "-depth", "-xdev", "-mount", "-P", "-H", "-L")
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            if (arg in predicates) { if (i++ >= args.size) return false }
            else if (arg.startsWith("-") && arg !in flags) return false
        }
        return true
    }

    private fun curl(args: List<String>): Boolean {
        // curl reads a default config unless -q/--disable is its first argument.
        if (args.firstOrNull() !in setOf("-q", "--disable")) return false
        val urls = options(args.drop(1), "sSfILNivgk4601", setOf('m'),
            setOf("silent", "show-error", "fail", "fail-with-body", "head", "include", "no-buffer", "verbose", "globoff", "compressed", "insecure", "ipv4", "ipv6", "http1.0", "http1.1", "http2", "no-progress-meter"),
            setOf("max-time", "connect-timeout", "retry", "retry-delay", "retry-max-time", "max-redirs", "range")) ?: return false
        return urls.isNotEmpty() && urls.all { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun busyboxGet(args: List<String>): Boolean {
        // Explicit applet: GNU wget has config files and a different option set. Always stream to stdout.
        if (args.take(2) != listOf("-O", "-")) return false
        val urls = options(args.drop(2), "qS", setOf('T'), setOf("quiet", "server-response"), setOf("timeout")) ?: return false
        return urls.isNotEmpty() && urls.all { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** Literal shell words, pipes and sequences; expansion/code execution and writes are not a proof. */
    private fun literalCommands(text: String): List<List<String>>? {
        val commands = mutableListOf<List<String>>(); val words = mutableListOf<String>(); val word = StringBuilder()
        var quoted: Char? = null; var token = false; var required = false; var redirect: Char? = null; var i = 0
        fun flush(): Boolean {
            if (!token) return true
            val value = word.toString(); word.setLength(0); token = false
            if (redirect != null) { if (redirect == '>' && value != "/dev/null") return false; redirect = null }
            else words += value
            return true
        }
        while (i < text.length) {
            val c = text[i++]
            if (quoted == '\'') { if (c == '\'') quoted = null else word.append(c); continue }
            if (c == '\\') {
                if (i >= text.length) return null
                val next = text[i++]; if (next != '\n') { word.append(next); token = true }; continue
            }
            if (quoted == '"') {
                when (c) { '"' -> quoted = null; '$', '`' -> return null; else -> word.append(c) }; continue
            }
            when {
                c == '\'' || c == '"' -> { quoted = c; token = true }
                c == '$' || c == '`' || c in "(){}*?" -> return null
                c == '[' && (token || text.getOrNull(i)?.isWhitespace() == false) -> return null
                c == '#' && !token -> { while (i < text.length && text[i] != '\n') i++ }
                c == '<' || c == '>' -> {
                    if (redirect != null || text.getOrNull(i) == c || text.getOrNull(i) == '(') return null
                    if (token && word.all(Char::isDigit)) { word.setLength(0); token = false }
                    if (!flush()) return null
                    if (c == '>' && text.getOrNull(i) == '&' && text.getOrNull(i + 1) in setOf('1', '2') &&
                        (i + 2 == text.length || text[i + 2].isWhitespace() || text[i + 2] in ";|&")) i += 2
                    else redirect = c
                }
                c == ';' || c == '\n' || c == '|' || c == '&' -> {
                    if (!flush() || redirect != null) return null
                    if (c == '&' && text.getOrNull(i) != '&') return null
                    if ((c == '&' || c == '|') && text.getOrNull(i) == c) i++
                    if (words.isNotEmpty()) { commands += words.toList(); words.clear(); required = false }
                    else if (c != '\n' || !required) { if (c != '\n') return null }
                    if (c == '&' || c == '|') required = true
                }
                c.isWhitespace() -> if (!flush()) return null
                else -> { word.append(c); token = true }
            }
        }
        if (quoted != null || !flush() || redirect != null || (required && words.isEmpty())) return null
        if (words.isNotEmpty()) commands += words.toList()
        return commands
    }
}
