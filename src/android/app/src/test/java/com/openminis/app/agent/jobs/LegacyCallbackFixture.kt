package com.openminis.app.agent.jobs

/** Test encoder for immutable historical fixtures. Production only reads these envelopes. */
val LegacyAgentCallback.xml: String
        get() {
            val attrs = mutableListOf("kind" to kind.wire, "job" to jobId)
            if (!childSessionId.isNullOrEmpty()) attrs += "session" to childSessionId
            attrs += "title" to title
            attrs += "status" to status
            if (!tier.isNullOrEmpty()) attrs += "model" to tier
            // [T-sub-agents-v1] Position matches iOS: model, agent, elapsed…
            if (!agentName.isNullOrEmpty()) attrs += "agent" to agentName
            if (!elapsed.isNullOrEmpty()) attrs += "elapsed" to elapsed
            if (!tool.isNullOrEmpty()) attrs += "tool" to tool
            if (!activity.isNullOrEmpty()) attrs += "activity" to activity
            if (turn != null && turn > 0) attrs += "turn" to turn.toString()
            val open = "<${LegacyAgentCallback.TAG} " + attrs.joinToString(" ") { "${it.first}=\"${legacyEscapeAttr(it.second)}\"" } + ">"
            val lines = mutableListOf(open)
            if (!summary.isNullOrEmpty()) lines += "<summary>${legacyEscapeText(summary)}</summary>"
            if (!siblings.isNullOrEmpty()) lines += "<other_sub_agents>${legacyEscapeText(siblings)}</other_sub_agents>"
            val bodyTag = if (kind == LegacyAgentCallback.Kind.FINISHED) "result" else "last_message"
            lines += "<$bodyTag>"
            lines += body
            lines += "</$bodyTag>"
            lines += "</${LegacyAgentCallback.TAG}>"
            return lines.joinToString("\n")
        }


private fun legacyEscapeAttr(s: String): String = legacyEscapeText(s).replace("\"", "&quot;").replace("\n", " ")
private fun legacyEscapeText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
