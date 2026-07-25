package app.momoding.core.transport

import java.security.cert.CertificateException
import okhttp3.CertificatePinner
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedHostEndpointTest {
    @Test
    fun canonicalEndpointDerivesOnlyFrozenRoutes() {
        val endpoint = PinnedHostEndpoint.parse("https://host.example:8443")

        assertEquals("host.example", endpoint.host)
        assertEquals(8443, endpoint.port)
        assertEquals("https://host.example:8443/v1/pair", endpoint.pairUrl)
        assertEquals("https://host.example:8443/v1/client-profile", endpoint.profileUrl)
        assertEquals("wss://host.example:8443/v1/ws", endpoint.webSocketUrl)
    }

    @Test
    fun endpointRejectsEveryNonCanonicalSurface() {
        listOf(
            "http://host.example:8443",
            "https://host.example",
            "https://HOST.example:8443",
            "https://user@host.example:8443",
            "https://host.example:8443/",
            "https://host.example:8443/path",
            "https://host.example:8443?query=1",
            "https://host.example:8443#fragment",
            " https://host.example:8443",
            "https://host.example:0",
            "https://host.example:65536",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                PinnedHostEndpoint.parse(value)
            }
        }
    }

    @Test
    fun spkiPinRequiresCanonicalSha256Digest() {
        val certificate = certificate("localhost")
        val canonical = CertificatePinner.pin(certificate.certificate)

        assertTrue(SpkiPin.parse(canonical).matches(certificate.certificate))
        listOf(
            "",
            "sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "sha256/",
            "sha256/not-base64!",
            "sha256/AA==",
            canonical.removeSuffix("=") + "_",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { SpkiPin.parse(value) }
        }
    }

    @Test
    fun exactTrustManagerRejectsEmptyWrongAndOutOfWindowLeafs() {
        val valid = certificate("localhost")
        val other = certificate("localhost")
        val now = System.currentTimeMillis()
        val expired = certificate("localhost", now - 20_000, now - 10_000)
        val future = certificate("localhost", now + 10_000, now + 20_000)
        val manager = ExactPinTrustManager(SpkiPin.parse(CertificatePinner.pin(valid.certificate))) {
            now
        }

        manager.checkServerTrusted(arrayOf(valid.certificate), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(other.certificate), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(expired.certificate), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(future.certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun hostnameAwareAndroidExtensionReturnsTheVerifiedChain() {
        val valid = certificate("localhost")
        val manager = ExactPinTrustManager(
            SpkiPin.parse(CertificatePinner.pin(valid.certificate)),
        )

        val verified = manager.checkServerTrusted(
            arrayOf(valid.certificate),
            "ECDHE_ECDSA",
            "localhost",
        )

        assertEquals(listOf(valid.certificate), verified)
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(
                arrayOf(valid.certificate),
                "ECDHE_ECDSA",
                "",
            )
        }
    }

    private fun certificate(
        subjectAlternativeName: String,
        notBeforeMillis: Long? = null,
        notAfterMillis: Long? = null,
    ): HeldCertificate {
        val builder = HeldCertificate.Builder()
            .commonName(subjectAlternativeName)
            .addSubjectAlternativeName(subjectAlternativeName)
        if (notBeforeMillis != null && notAfterMillis != null) {
            builder.validityInterval(notBeforeMillis, notAfterMillis)
        }
        return builder.build()
    }
}
