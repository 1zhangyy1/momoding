package app.momoding.web

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Looking things up without the browser (调研/M23 #1): a search engine's result page read as
 * hits, and any public page read as text. Seconds and a few hundred tokens, where opening the
 * browser costs a snapshot per step. Search goes to Exa's public MCP endpoint, free and keyless
 * (Cherry Studio's default; on a phone in China it answered in seconds with the right pages,
 * where scraping 360 and Sogou drew captchas after a few queries). The words searched go to Exa.
 */
object WebLookup {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val MAX_BYTES = 3L * 1024 * 1024


    /** Public addresses only: the phone's own services and the home network are not the web. */
    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val ok = all.filterNot { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            if (ok.isEmpty()) throw UnknownHostException("$hostname is not a public address")
            return ok
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(publicDns)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .build()

    class Fetched(val url: String, val type: String, val bytes: ByteArray, val charset: String?)

    fun fetch(url: String): Fetched {
        val u = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("not a web address: $url")
        val req = Request.Builder().url(u).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("the site answered ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty answer")
            val source = body.source()
            source.request(MAX_BYTES)
            val bytes = source.buffer.let { b -> b.readByteArray(minOf(b.size, MAX_BYTES)) }
            val type = body.contentType()
            return Fetched(r.request.url.toString(), type?.let { "${it.type}/${it.subtype}" } ?: "text/html", bytes, type?.charset()?.name())
        }
    }

    private const val EXA = "https://mcp.exa.ai/mcp"

    /** Exa's hits for [query]; throws when Exa cannot be reached or answers with an error. */
    fun search(query: String, limit: Int): List<Hit> {
        val call = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", JSONObject()
            .put("name", "web_search_exa")
            .put("arguments", JSONObject().put("query", query).put("numResults", limit).put("type", "auto").put("livecrawl", "fallback")))
        val req = Request.Builder().url(EXA)
            .header("Accept", "application/json, text/event-stream")
            .post(call.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("the search service answered ${r.code}")
            return WebText.exaHits(r.body?.string().orEmpty())
        }
    }

    sealed class Read {
        class Text(val url: String, val title: String, val text: String) : Read()
        class Other(val url: String, val type: String) : Read()
    }

    fun read(url: String): Read {
        val f = fetch(url)
        return when {
            f.type == "text/html" || f.type == "application/xhtml+xml" ->
                WebText.page(Jsoup.parse(ByteArrayInputStream(f.bytes), f.charset, f.url)).let { Read.Text(f.url, it.title, it.text) }
            f.type.startsWith("text/") || f.type == "application/json" ->
                Read.Text(f.url, "", String(f.bytes, runCatching { charset(f.charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)))
            else -> Read.Other(f.url, f.type)
        }
    }
}
