package app.momoding.core.transport

import android.annotation.SuppressLint
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PinnedHostEndpoint private constructor(
    val canonicalHttpsUrl: String,
    val host: String,
    val port: Int,
) {
    val pairUrl: String
        get() = "$canonicalHttpsUrl/v1/pair"

    val profileUrl: String
        get() = "$canonicalHttpsUrl/v1/client-profile"

    val webSocketUrl: String
        get() = "wss://${authority()}/v1/ws"

    private fun authority(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(value: String): PinnedHostEndpoint {
            require(value.isNotEmpty() && value == value.trim()) { "Endpoint is not canonical" }
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw IllegalArgumentException("Endpoint is invalid", error)
            }
            require(uri.scheme == "https") { "Endpoint must use https" }
            require(uri.rawUserInfo == null) { "Endpoint must not contain userinfo" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Endpoint must not contain query or fragment"
            }
            require(uri.rawPath.isNullOrEmpty()) { "Endpoint must not contain a path" }
            val host = requireNotNull(uri.host) { "Endpoint host is missing" }
            require(host == host.lowercase() && host.none(Char::isWhitespace) && '%' !in host) {
                "Endpoint host is not canonical"
            }
            val port = uri.port
            require(port in 1..65_535) { "Endpoint must contain an explicit port" }
            val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            val canonical = "https://$authority"
            require(value == canonical) { "Endpoint is not canonical" }
            return PinnedHostEndpoint(canonical, host, port)
        }
    }
}

class SpkiPin private constructor(
    val value: String,
    private val digestBytes: ByteArray,
) {
    fun matches(certificate: X509Certificate): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return MessageDigest.isEqual(digestBytes, actual)
    }

    fun copyDigest(): ByteArray = digestBytes.copyOf()

    companion object {
        fun parse(value: String): SpkiPin {
            require(value.startsWith(PREFIX) && value.length > PREFIX.length) {
                "SPKI pin must use sha256/base64 format"
            }
            val encoded = value.removePrefix(PREFIX)
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("SPKI pin is malformed", error)
            }
            require(bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == encoded) {
                "SPKI pin must be canonical SHA-256"
            }
            return SpkiPin(value, bytes.copyOf())
        }

        private const val PREFIX = "sha256/"
    }
}

@SuppressLint("CustomX509TrustManager") // Exact leaf SPKI + validity; OkHttp still owns hostname/SAN checks.
class ExactPinTrustManager(
    private val pin: SpkiPin,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : X509TrustManager {
    @Volatile
    private var acceptedLeaf: X509Certificate? = null

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verifyServerChain(chain, authType)
    }

    /**
     * Android's X509TrustManagerExtensions discovers this hostname-aware overload by reflection.
     * OkHttp then uses the returned chain for its independent CertificatePinner check.
     */
    @Suppress("unused")
    fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        host: String,
    ): List<X509Certificate> {
        if (host.isBlank()) throw CertificateException("Server hostname is missing")
        verifyServerChain(chain, authType)
        return chain.toList()
    }

    private fun verifyServerChain(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        if (chain.isNullOrEmpty()) throw CertificateException("Server certificate chain is empty")
        if (authType.isNullOrBlank()) throw CertificateException("Server auth type is missing")
        val leaf = chain[0]
        try {
            leaf.checkValidity(Date(nowMillis()))
        } catch (error: Exception) {
            throw CertificateException("Server leaf certificate is outside its validity window", error)
        }
        if (!pin.matches(leaf)) throw CertificateException("Server leaf SPKI does not match")
        acceptedLeaf = leaf
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        acceptedLeaf?.let { arrayOf(it) } ?: emptyArray()
}

object ExactPinnedHttpClientFactory {
    fun create(
        endpoint: PinnedHostEndpoint,
        pin: SpkiPin,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): OkHttpClient {
        val trustManager = ExactPinTrustManager(pin, nowMillis)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(endpoint.host, pin.value)
                    .build(),
            )
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .pingInterval(20.seconds.toJavaDuration())
            .build()
    }
}
