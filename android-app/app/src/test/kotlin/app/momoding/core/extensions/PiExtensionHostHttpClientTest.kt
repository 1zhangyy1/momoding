package app.momoding.core.extensions

import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiExtensionHostHttpClientTest {
    @Test
    fun `public address policy rejects every representative special-use range`() {
        val denied = listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "::1",
            "64:ff9b::1",
            "2001::1",
            "2001:db8::1",
            "2002::1",
            "3fff::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
        ).map(InetAddress::getByName) + listOf(
            Inet6Address.getByAddress(
                null,
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1),
                -1,
            ),
        )
        denied.forEach { address ->
            assertThrows(PiExtensionHostHttpException::class.java) {
                PublicPiExtensionNetworkAddressPolicy.requireAllowed("fixture.invalid", listOf(address))
            }
        }

        PublicPiExtensionNetworkAddressPolicy.requireAllowed(
            "fixture.global",
            listOf(InetAddress.getByName("8.8.8.8")),
        )
        PublicPiExtensionNetworkAddressPolicy.requireAllowed(
            "fixture.global-v6",
            listOf(InetAddress.getByName("2606:4700:4700::1111")),
        )
        assertThrows(PiExtensionHostHttpException::class.java) {
            PublicPiExtensionNetworkAddressPolicy.requireAllowed(
                "fixture.mixed",
                listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("100.64.0.1")),
            )
        }
    }

    @Test
    fun `declared https uses package credential across redirect and returns redacted audit`() = runBlocking {
        httpsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "private-cookie=1")
                    .setHeader("WWW-Authenticate", "Bearer hidden")
                    .setBody("{\"status\":\"ok\"}"),
            )
            val seen = mutableListOf<PiExtensionCredentialBindingRef>()
            val client = fixture.client(PiExtensionCredentialResolver { binding ->
                seen += binding
                SECRET.toCharArray()
            })

            val result = client.execute(
                artifact(fixture.origin, credential = true),
                request(fixture.url("/start"), credentialSlot = SLOT),
            )

            assertEquals(200, result.audit.status)
            assertEquals("GET", result.audit.method)
            assertEquals(fixture.origin, result.audit.origin)
            assertEquals(1, result.audit.redirects)
            assertEquals(15, result.audit.responseBytes)
            assertEquals("utf8", result.response.string("bodyEncoding"))
            assertEquals("{\"status\":\"ok\"}", result.response.string("body"))
            val returnedHeaders = result.response["headers"] as JsonObject
            assertEquals("application/json", returnedHeaders.string("content-type"))
            assertFalse("set-cookie" in returnedHeaders)
            assertFalse("www-authenticate" in returnedHeaders)
            assertEquals(1, seen.size)
            assertEquals(SECRET, fixture.server.takeRequest().getHeader("Authorization")?.removePrefix("Bearer "))
            assertEquals(SECRET, fixture.server.takeRequest().getHeader("Authorization")?.removePrefix("Bearer "))
            assertFalse(result.toJson().toString().contains(SECRET))
        }
    }

    @Test
    fun `origin method sensitive header private address and credential echo fail closed`() = runBlocking {
        httpsFixture().use { fixture ->
            val allowLocal = fixture.client(PiExtensionCredentialResolver { SECRET.toCharArray() })
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED") {
                allowLocal.execute(
                    artifact("https://different.example"),
                    request(fixture.url("/wrong-origin")),
                )
            }
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED") {
                allowLocal.execute(
                    artifact(fixture.origin),
                    request(fixture.url("/post"), method = "POST"),
                )
            }
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED") {
                allowLocal.execute(
                    artifact(fixture.origin),
                    request(
                        fixture.url("/header"),
                        headers = JsonObject(mapOf("Authorization" to JsonPrimitive("stolen"))),
                    ),
                )
            }
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED") {
                allowLocal.execute(
                    artifact(fixture.origin),
                    request(
                        fixture.url("/method-tunnel"),
                        headers = JsonObject(mapOf(
                            "X-HTTP-Method-Override" to JsonPrimitive("DELETE"),
                        )),
                    ),
                )
            }
            assertEquals(0, fixture.server.requestCount)
            val productionPolicy = PiExtensionHostHttpClient(
                baseClient = fixture.baseClient,
                credentials = PiExtensionCredentialResolver { null },
            )
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_ORIGIN_DENIED") {
                productionPolicy.execute(artifact(fixture.origin), request(fixture.url("/private")))
            }

            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"token\":\"$SECRET\"}"),
            )
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_ECHO_DENIED") {
                allowLocal.execute(
                    artifact(fixture.origin, credential = true),
                    request(fixture.url("/echo"), credentialSlot = SLOT),
                )
            }
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("ETag", SECRET)
                    .setBody("safe"),
            )
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_ECHO_DENIED") {
                allowLocal.execute(
                    artifact(fixture.origin, credential = true),
                    request(fixture.url("/header-echo"), credentialSlot = SLOT),
                )
            }
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain; charset=utf-16le")
                    .setBody(Buffer().write(SECRET.toByteArray(Charsets.UTF_16LE))),
            )
            assertHttpCode("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_ECHO_DENIED") {
                allowLocal.execute(
                    artifact(fixture.origin, credential = true),
                    request(fixture.url("/charset-echo"), credentialSlot = SLOT),
                )
            }
            assertEquals(3, fixture.server.requestCount)
        }
    }

    @Test
    fun `response bytes are bounded and cancellation closes the live call`() = runBlocking {
        httpsFixture().use { fixture ->
            val client = fixture.client(PiExtensionCredentialResolver { null })
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("x".repeat(MAX_HTTP_RESPONSE_BODY_BYTES + 1)),
            )
            assertHttpCode("EXTENSION_PACKAGE_RESULT_TOO_LARGE") {
                client.execute(artifact(fixture.origin), request(fixture.url("/large")))
            }
            assertEquals("/large", fixture.server.takeRequest().path)

            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = async { client.execute(artifact(fixture.origin), request(fixture.url("/stop"))) }
            withContext(Dispatchers.IO) {
                checkNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
            }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertNull(fixture.server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `cancellation after response headers closes a throttled response body`() = runBlocking {
        httpsFixture().use { fixture ->
            val bodyStarted = CompletableDeferred<Unit>()
            val observedClient = fixture.baseClient.newBuilder()
                .eventListener(object : EventListener() {
                    override fun responseBodyStart(call: Call) {
                        bodyStarted.complete(Unit)
                    }
                })
                .build()
            val client = PiExtensionHostHttpClient(
                baseClient = observedClient,
                credentials = PiExtensionCredentialResolver { null },
                addressPolicy = PiExtensionNetworkAddressPolicy { _, addresses ->
                    require(addresses.isNotEmpty())
                },
            )
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("slow-body")
                    .throttleBody(1, 10, TimeUnit.SECONDS),
            )
            val pending = async { client.execute(artifact(fixture.origin), request(fixture.url("/drip"))) }
            withTimeout(5_000) { bodyStarted.await() }
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `credential echo scan stays bounded for adversarial near matches`() = runBlocking {
        httpsFixture().use { fixture ->
            val adversarialSecret = "a".repeat(4_095) + "b"
            val client = fixture.client(PiExtensionCredentialResolver {
                adversarialSecret.toCharArray()
            })
            fixture.server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/plain; charset=utf-8")
                    .setBody("a".repeat(MAX_HTTP_RESPONSE_BODY_BYTES)),
            )

            val result = withTimeout(5_000) {
                client.execute(
                    artifact(fixture.origin, credential = true),
                    request(fixture.url("/adversarial"), credentialSlot = SLOT),
                )
            }

            assertEquals(MAX_HTTP_RESPONSE_BODY_BYTES, result.audit.responseBytes)
            assertEquals("utf8", result.response.string("bodyEncoding"))
        }
    }

    private fun artifact(origin: String, credential: Boolean = false): PiRegisterToolArtifact =
        PiRegisterToolArtifact(
            packageId = PACKAGE_ID,
            packageDigest = DIGEST,
            entrypoint = "dist/index.js",
            modules = mapOf("dist/index.js" to "export default () => {};"),
            tools = listOf(PiRegisterToolManifestTool(
                name = "fixture",
                label = "Fixture",
                description = "Fixture",
                parameters = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "additionalProperties" to JsonPrimitive(false),
                )),
            )),
            hostTools = emptyList(),
            httpPolicy = PiRegisterToolHttpPolicy(
                origins = listOf(origin),
                methods = listOf("GET"),
                credentialSlots = if (credential) listOf(PiRegisterToolCredentialSlot(
                    slot = SLOT,
                    origin = origin,
                )) else emptyList(),
            ),
        )

    private fun request(
        url: String,
        method: String = "GET",
        headers: JsonObject = JsonObject(mapOf("accept" to JsonPrimitive("application/json"))),
        credentialSlot: String? = null,
    ): JsonObject = JsonObject(mapOf(
        "url" to JsonPrimitive(url),
        "method" to JsonPrimitive(method),
        "headers" to headers,
        "body" to JsonNull,
        "credentialSlot" to (credentialSlot?.let(::JsonPrimitive) ?: JsonNull),
    ))

    private fun httpsFixture(): HttpsFixture {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(held.certificate)
            .build()
        val server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        return HttpsFixture(
            server,
            OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build(),
        )
    }

    private data class HttpsFixture(
        val server: MockWebServer,
        val baseClient: OkHttpClient,
    ) : AutoCloseable {
        val origin: String get() = "https://localhost:${server.port}"
        fun url(path: String): String = origin + path
        fun client(credentials: PiExtensionCredentialResolver): PiExtensionHostHttpClient =
            PiExtensionHostHttpClient(
                baseClient = baseClient,
                credentials = credentials,
                addressPolicy = PiExtensionNetworkAddressPolicy { _, addresses ->
                    require(addresses.isNotEmpty())
                },
            )
        override fun close() = server.shutdown()
    }

    private suspend fun assertHttpCode(code: String, block: suspend () -> Unit) {
        val thrown = assertThrows(PiExtensionHostHttpException::class.java) {
            runBlocking { block() }
        }
        assertEquals(code, thrown.code)
    }

    private fun JsonObject.string(key: String): String = (this[key] as JsonPrimitive).content

    companion object {
        private const val PACKAGE_ID = "fixtures.network"
        private val DIGEST = "a".repeat(64)
        private const val SLOT = "status-api"
        private const val SECRET = "fixture-super-secret-value"
    }
}
