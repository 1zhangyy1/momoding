package app.momoding.core.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinnedHostTlsInstrumentedTest {
    @Test
    fun exactPinnedHttpsSucceedsWithoutPlatformTrust() {
        val held = certificate("localhost")
        httpsServer(held).use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val endpoint = PinnedHostEndpoint.parse("https://localhost:${server.port}")
            val pin = SpkiPin.parse(CertificatePinner.pin(held.certificate))

            ExactPinnedHttpClientFactory.create(endpoint, pin)
                .newCall(
                    Request.Builder()
                        .url(endpoint.pairUrl)
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                )
                .execute()
                .use { response -> assertEquals(204, response.code) }

            assertEquals("/v1/pair", server.takeRequest().path)
        }
    }

    @Test
    fun wrongPinAndWrongHostnameFailBeforeAnyHttpRequest() {
        val held = certificate("localhost")
        val wrong = certificate("localhost")
        httpsServer(held).use { server ->
            val localhost = PinnedHostEndpoint.parse("https://localhost:${server.port}")
            val ipAddress = PinnedHostEndpoint.parse("https://127.0.0.1:${server.port}")

            assertThrows(Exception::class.java) {
                ExactPinnedHttpClientFactory.create(
                    localhost,
                    SpkiPin.parse(CertificatePinner.pin(wrong.certificate)),
                ).newCall(Request.Builder().url(localhost.pairUrl).build()).execute().close()
            }
            assertThrows(Exception::class.java) {
                ExactPinnedHttpClientFactory.create(
                    ipAddress,
                    SpkiPin.parse(CertificatePinner.pin(held.certificate)),
                ).newCall(Request.Builder().url(ipAddress.pairUrl).build()).execute().close()
            }

            assertEquals(0, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private fun certificate(subjectAlternativeName: String): HeldCertificate =
        HeldCertificate.Builder()
            .commonName(subjectAlternativeName)
            .addSubjectAlternativeName(subjectAlternativeName)
            .build()

    private fun httpsServer(held: HeldCertificate): MockWebServer {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(held)
            .build()
        return MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
    }
}
