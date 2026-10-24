package app.momoding.web

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Looking things up without the browser (调研/M23 #1): a search engine's result page read as
 * hits, and any public page read as text. Seconds and a few hundred tokens, where opening the
 * browser costs a snapshot per step. Free engines that answer without a key from China:
 * 360 first, Sogou when 360 gives nothing (Bing serves bots unrelated results).
 */
object WebLookup {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val MAX_BYTES = 3L * 1024 * 1024

    /** Search engines look harder at a client that never keeps their cookies. */
    private val jar = object : CookieJar {
        private val kept = HashMap<String, List<Cookie>>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            kept[url.host] = (kept[url.host].orEmpty().filter { c -> cookies.none { it.name == c.name } } + cookies)
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            kept[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
    }

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
        .cookieJar(jar).dns(publicDns)
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

    data class Found(val source: String, val hits: List<Hit>)

    fun search(query: String, limit: Int): Found? {
        val q = URLEncoder.encode(query, "UTF-8")
        val engines = listOf(
            Triple("360", "https://www.so.com/s?q=$q", WebText::hits360),
            Triple("sogou", "https://www.sogou.com/web?query=$q", WebText::hitsSogou),
        )
        for ((name, url, parse) in engines) {
            val hits = runCatching { parse(String(fetch(url).bytes, Charsets.UTF_8)) }.getOrNull().orEmpty()
                .distinctBy { it.url }
            if (hits.size >= 2) return Found(name, hits.take(limit))
        }
        return null
    }

    sealed class Read {
        class Text(val url: String, val title: String, val text: String) : Read()
        class Other(val url: String, val type: String) : Read()
    }

    fun read(url: String): Read {
        var f = fetch(url)
        // a search engine's link hop: follow where it points, once
        if (f.type == "text/html") WebText.redirectTarget(String(f.bytes, Charsets.UTF_8))?.let { f = fetch(it) }
        return when {
            f.type == "text/html" || f.type == "application/xhtml+xml" ->
                WebText.page(Jsoup.parse(ByteArrayInputStream(f.bytes), f.charset, f.url)).let { Read.Text(f.url, it.title, it.text) }
            f.type.startsWith("text/") || f.type == "application/json" ->
                Read.Text(f.url, "", String(f.bytes, runCatching { charset(f.charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)))
            else -> Read.Other(f.url, f.type)
        }
    }
}
