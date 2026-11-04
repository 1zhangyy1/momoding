package com.openminis.app.agent.jobs

/** Read-only decoder for reports written by older builds. New work reports through TaskEngine. */
data class LegacyAgentCallback(
    val kind: Kind,
    val jobId: String,
    val childSessionId: String?,
    val title: String,
    /** running · done · cancelled · timeout · failed · rejected */
    val status: String,
    val tier: String? = null,
    val elapsed: String? = null,
    val tool: String? = null,
    val activity: String? = null,
    val turn: Int? = null,
    /**
     * [T-sub-agents-v1] Which named sub agent produced this. Omitted for the
     * built-in — there is nothing to disambiguate.
     */
    val agentName: String? = null,
    val summary: String? = null,
    /**
     * [T-sub-agents-sibling-status] One line about the OTHER sub agents of this
     * conversation, on completion AND progress alike.
     *
     * The parent sees one result at a time and nothing else tells it whether
     * the rest of a batch is still coming, already lost, or never started — so
     * a model that fanned out three delegations and received one can quite
     * reasonably summarise as if it had them all.
     */
    val siblings: String? = null,
    val body: String,
) {
    enum class Kind(val wire: String) { PROGRESS("progress"), FINISHED("finished") }

    /** Elapsed "1m02s" / "45s" → seconds. */
    val elapsedSeconds: Int?
        get() {
            val e = elapsed ?: return null
            val m = e.indexOf('m')
            return if (m >= 0) {
                val mins = e.substring(0, m).toIntOrNull() ?: 0
                val secs = e.substring(m + 1).removeSuffix("s").toIntOrNull() ?: 0
                mins * 60 + secs
            } else e.removeSuffix("s").toIntOrNull()
        }

    companion object {
        const val TAG = "agent_callback"

        /** Cheap prefix test used on every render / preview pass. */
        fun isCallbackText(text: String): Boolean =
            text.startsWith("<$TAG ") || text.startsWith("<$TAG>")

        fun parse(text: String): LegacyAgentCallback? {
            if (!isCallbackText(text)) return null
            val openEnd = text.indexOf('>').takeIf { it >= 0 } ?: return null
            // `<agent_callback>` with no attributes is accepted by
            // isCallbackText, and for it the attribute span is EMPTY — start
            // (TAG.length + 1) lands one past `openEnd`. Substring would throw
            // on a message a user could simply type, taking the renderer with
            // it, so the span is clamped instead.
            val attrStart = (TAG.length + 1).coerceAtMost(openEnd)
            val attrs = parseAttributes(text.substring(attrStart, openEnd))
            val kind = when (attrs["kind"]) {
                "progress" -> Kind.PROGRESS
                "finished" -> Kind.FINISHED
                else -> return null
            }
            val jobId = attrs["job"] ?: return null
            val after = text.substring(openEnd + 1)
            val close = after.lastIndexOf("</$TAG>")
            val inner = if (close >= 0) after.substring(0, close) else after
            val bodyTag = if (kind == Kind.FINISHED) "result" else "last_message"
            return LegacyAgentCallback(
                kind = kind,
                jobId = jobId,
                childSessionId = attrs["session"],
                title = attrs["title"] ?: "",
                status = attrs["status"] ?: (if (kind == Kind.FINISHED) "done" else "running"),
                tier = attrs["model"],
                elapsed = attrs["elapsed"],
                tool = attrs["tool"],
                activity = attrs["activity"],
                turn = attrs["turn"]?.toIntOrNull(),
                agentName = attrs["agent"],
                summary = element("summary", inner)?.let(::unescapeText),
                siblings = element("other_sub_agents", inner)?.let(::unescapeText),
                body = element(bodyTag, inner) ?: inner.trim(),
            )
        }

        private fun parseAttributes(s: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            var rest = s
            while (true) {
                val eq = rest.indexOf('=').takeIf { it >= 0 } ?: break
                val key = rest.substring(0, eq).trim()
                val afterEq = rest.substring(eq + 1)
                val q1 = afterEq.indexOf('"').takeIf { it >= 0 } ?: break
                val q2 = afterEq.indexOf('"', q1 + 1).takeIf { it >= 0 } ?: break
                out[key] = unescapeText(afterEq.substring(q1 + 1, q2))
                rest = afterEq.substring(q2 + 1)
            }
            return out
        }

        private fun element(name: String, s: String): String? {
            val open = s.indexOf("<$name>").takeIf { it >= 0 } ?: return null
            val close = s.lastIndexOf("</$name>").takeIf { it >= 0 } ?: return null
            val start = open + name.length + 2
            if (start > close) return null
            return s.substring(start, close).trim()
        }

        private fun unescapeText(s: String): String =
            s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
    }
}

/**
 * Localized nouns for [LegacyAgentCallback] previews (session list, notifications).
 * Installed once by MinisApp from resources so the data layer never touches a
 * Context. Defaults are the English words.
 */
object LegacyAgentCallbackLabels {
    @Volatile var result: String = "Agent result"
    @Volatile var progress: String = "Agent progress"
    @Volatile var status: Map<String, String> = emptyMap()

    fun install(context: android.content.Context) {
        val r = context.applicationContext.resources
        result = r.getString(com.openminis.app.R.string.agent_callback_result)
        progress = r.getString(com.openminis.app.R.string.agent_callback_progress)
        status = mapOf(
            "running" to r.getString(com.openminis.app.R.string.helper_status_running),
            "done" to r.getString(com.openminis.app.R.string.helper_status_completed),
            "cancelled" to r.getString(com.openminis.app.R.string.helper_status_cancelled),
            "timeout" to r.getString(com.openminis.app.R.string.helper_status_timeout),
            "failed" to r.getString(com.openminis.app.R.string.helper_status_failed),
            "rejected" to r.getString(com.openminis.app.R.string.helper_status_rejected),
        )
    }

    fun localizedStatus(s: String): String = status[s] ?: s

    /** One-line stand-in for session previews, where the raw XML would be noise. */
    fun previewLine(cb: LegacyAgentCallback): String =
        "${if (cb.kind == LegacyAgentCallback.Kind.FINISHED) result else progress} · ${cb.title} · ${localizedStatus(cb.status)}"
}
