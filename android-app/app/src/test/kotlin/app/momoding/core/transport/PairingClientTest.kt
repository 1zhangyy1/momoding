package app.momoding.core.transport

import app.momoding.wire.ReliabilityProtocol
import app.momoding.core.auth.PairingIdentity
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingClientTest {
    @Test
    fun pairUsesExactRouteAndDecodesFrozenResponse() {
        val captured = AtomicReference<Request>()
        val client = clientResponding(validPairResponse(), captured)

        val result = client.pair(PAIRING_CODE, identity())

        assertEquals(HOST_ID, result.hostId)
        assertEquals(CREDENTIAL_ID, result.credentialId)
        assertEquals("default", result.profile.thinking)
        assertFalse(result.profile.mutable)
        assertEquals("/v1/pair", captured.get().url.encodedPath)
        assertEquals("POST", captured.get().method)
        assertEquals("application/json", captured.get().body?.contentType().toString())
        val body = Buffer().also { captured.get().body?.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"pairingCode\":\"$PAIRING_CODE\""))
        assertTrue(body.contains("\"clientInstanceId\":\"$CLIENT_ID\""))
        assertFalse(result.toString().contains(DEVICE_CREDENTIAL))
    }

    @Test
    fun refreshProfileUsesBearerWithoutLeakingCredentialFromDomainObjects() {
        val captured = AtomicReference<Request>()
        val client = clientResponding(
            """{"protocolVersion":1,"clientProfile":${profileJson()}}""",
            captured,
        )
        val secret = vaultSecret()

        val profile = client.refreshProfile(secret)

        assertEquals(1, profile.configRevision)
        assertEquals("Bearer $DEVICE_CREDENTIAL", captured.get().header("authorization"))
        assertEquals("/v1/client-profile", captured.get().url.encodedPath)
        assertFalse(secret.toString().contains(DEVICE_CREDENTIAL))
    }

    @Test
    fun duplicateUnknownAndBindingMismatchedResponsesFailClosed() {
        val duplicateTopLevel = validPairResponse().replace(
            "{\"protocolVersion\":1,",
            "{\"protocolVersion\":1,\"protocolVersion\":1,",
        )
        val duplicateEscapedProfileKey = validPairResponse().replace(
            "\"hostId\":\"$HOST_ID\"",
            "\"hostId\":\"$HOST_ID\",\"host\\u0049d\":\"$HOST_ID\"",
        )
        val unknown = validPairResponse().replace(
            "\"clientProfile\":",
            "\"unknown\":true,\"clientProfile\":",
        )
        val wrongHost = validPairResponse().replace(
            profileJson(),
            profileJson().replace(HOST_ID, "99999999-9999-4999-8999-999999999999"),
        )
        val wrongCredentialId = validPairResponse().replace(
            "\"credentialId\":\"$CREDENTIAL_ID\"",
            "\"credentialId\":\"99999999-9999-4999-8999-999999999999\"",
        )

        listOf(duplicateTopLevel, duplicateEscapedProfileKey, unknown, wrongHost, wrongCredentialId)
            .forEach { response ->
                assertThrows(Exception::class.java) {
                    clientResponding(response).pair(PAIRING_CODE, identity())
                }
            }
    }

    @Test
    fun invalidProfileAndOversizedBodyFailClosed() {
        val mutable = validPairResponse().replace("\"mutable\":false", "\"mutable\":true")
        val floatingRevision = validPairResponse().replace("\"configRevision\":1", "\"configRevision\":1.0")
        val unsafeRevision = validPairResponse().replace(
            "\"configRevision\":1",
            "\"configRevision\":${ReliabilityProtocol.MAX_SAFE_INTEGER + 1}",
        )
        listOf(mutable, floatingRevision, unsafeRevision, "x".repeat(8_193)).forEach { response ->
            assertThrows(Exception::class.java) {
                clientResponding(response).pair(PAIRING_CODE, identity())
            }
        }
    }

    @Test
    fun httpFailureNeverIncludesServerBodyOrSecrets() {
        val serverBody = "server-secret-detail"
        val captured = AtomicReference<Request>()
        val client = clientResponding(serverBody, captured, statusCode = 401)

        val failure = assertThrows(PairingHttpException::class.java) {
            client.pair(PAIRING_CODE, identity())
        }

        assertEquals(401, failure.statusCode)
        assertFalse(failure.message.orEmpty().contains(serverBody))
        assertFalse(failure.toString().contains(PAIRING_CODE))
    }

    @Test
    fun invalidPairIdentityFailsBeforeAnyNetworkRequest() {
        listOf(
            identity().copy(clientInstanceId = "not-a-uuid"),
            identity().copy(deviceId = "not-a-uuid"),
            identity().copy(deviceName = ""),
            identity().copy(deviceName = "x".repeat(81)),
            identity().copy(deviceName = "valid\u0000invalid"),
            identity().copy(deviceName = "bad\ud800name"),
        ).forEach { invalid ->
            val captured = AtomicReference<Request>()
            val client = clientResponding(validPairResponse(), captured)

            assertThrows(IllegalArgumentException::class.java) {
                client.pair(PAIRING_CODE, invalid)
            }
            assertNull(captured.get())
        }
    }

    @Test
    fun pairCanonicalizesHostWhitespaceAndCountsUnicodeScalars() {
        val captured = AtomicReference<Request>()
        val client = clientResponding(validPairResponse(), captured)
        val eightyEmoji = "\ud83d\ude80".repeat(80)

        client.pair(PAIRING_CODE, identity().copy(deviceName = "\ufeff  Android Phone\u3000"))
        val canonicalBody = Buffer().also { captured.get().body?.writeTo(it) }.readUtf8()
        assertTrue(canonicalBody.contains("\"deviceName\":\"Android Phone\""))

        client.pair(PAIRING_CODE, identity().copy(deviceName = eightyEmoji))
        assertThrows(IllegalArgumentException::class.java) {
            client.pair(PAIRING_CODE, identity().copy(deviceName = "$eightyEmoji\ud83d\ude80"))
        }
    }

    private fun clientResponding(
        body: String,
        captured: AtomicReference<Request> = AtomicReference(),
        statusCode: Int = 200,
    ): PairingClient {
        val interceptor = Interceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message(if (statusCode == 200) "OK" else "Rejected")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return PairingClient { _, _ -> okHttp }
    }

    private fun identity(): PairingIdentity = PairingIdentity(
        endpoint = "https://host.example:8443",
        spkiPin = PIN,
        clientInstanceId = CLIENT_ID,
        deviceId = DEVICE_ID,
        deviceName = "Android Phone",
    )

    private fun vaultSecret(): VaultSecret = VaultSecret(
        header = VaultHeader("https://host.example:8443", PIN, HOST_ID, CREDENTIAL_ID),
        deviceCredential = DEVICE_CREDENTIAL,
        clientInstanceId = CLIENT_ID,
        deviceId = DEVICE_ID,
        deviceName = "Android Phone",
    )

    private fun validPairResponse(): String =
        """{"protocolVersion":1,"credentialId":"$CREDENTIAL_ID","deviceCredential":"$DEVICE_CREDENTIAL","hostId":"$HOST_ID","clientProfile":${profileJson()}}"""

    private fun profileJson(): String =
        """{"hostId":"$HOST_ID","hostAlias":"Local Host","provider":"provider","model":"model","thinking":"default","mutable":false,"configRevision":1}"""

    companion object {
        private const val PAIRING_CODE = "ABCDEFGHJKLMNPQRSTUV"
        private const val HOST_ID = "11111111-1111-4111-8111-111111111111"
        private const val CREDENTIAL_ID = "22222222-2222-4222-8222-222222222222"
        private const val CLIENT_ID = "33333333-3333-4333-8333-333333333333"
        private const val DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        private val PIN = "sha256/${Base64.getEncoder().encodeToString(ByteArray(32) { 4 })}"
        private val DEVICE_CREDENTIAL =
            "cm1.$CREDENTIAL_ID.${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })}"
    }
}
