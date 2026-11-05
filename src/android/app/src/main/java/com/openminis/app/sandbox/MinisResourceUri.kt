package com.openminis.app.sandbox

import java.net.URLDecoder
import java.net.URLEncoder

/** A shared file link keeps its original session even when another chat receives it. */
object MinisResourceUri {
    private fun ownerQuery(uri: String): String? = uri.substringAfter('?', "").split('&')
        .firstOrNull { it.substringBefore('=') in setOf("session", "sessionId") }

    fun hasSession(uri: String): Boolean = ownerQuery(uri) != null

    fun sessionOf(uri: String, fallback: String?): String? {
        val query = ownerQuery(uri)
        val owner = if (query == null) fallback else runCatching {
            URLDecoder.decode(query.substringAfter('=', ""), "UTF-8")
        }.getOrNull()
        return owner?.takeIf { it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it && '\u0000' !in it }
    }

    fun withSession(uri: String, session: String): String =
        uri + (if ('?' in uri) "&" else "?") + "session=" + URLEncoder.encode(session, "UTF-8")
}
