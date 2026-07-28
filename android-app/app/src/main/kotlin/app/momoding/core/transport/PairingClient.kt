package app.momoding.core.transport

import app.momoding.wire.P1bProtocol
import app.momoding.core.auth.DeviceNamePolicy
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.auth.PairingIdentity
import app.momoding.core.auth.PairingSuccess
import app.momoding.core.auth.VaultEnvelopeCodec
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class PairingClient(
    private val clientFactory: (PinnedHostEndpoint, SpkiPin) -> OkHttpClient =
        ExactPinnedHttpClientFactory::create,
) {
    fun pair(pairingCode: String, identity: PairingIdentity): PairingSuccess {
        return createPairCall(pairingCode, identity).execute()
    }

    fun createPairCall(pairingCode: String, identity: PairingIdentity): PairCall {
        requireValidPairingCode(pairingCode)
        val endpoint = PinnedHostEndpoint.parse(identity.endpoint)
        val pin = SpkiPin.parse(identity.spkiPin)
        require(CANONICAL_UUID.matches(identity.clientInstanceId)) { "clientInstanceId is invalid" }
        require(CANONICAL_UUID.matches(identity.deviceId)) { "deviceId is invalid" }
        val canonicalDeviceName = DeviceNamePolicy.canonicalize(identity.deviceName)
        val requestBytes = buildJsonObject {
            put("protocolVersion", 1)
            put("pairingCode", pairingCode)
            put("clientInstanceId", identity.clientInstanceId)
            put("deviceId", identity.deviceId)
            put("deviceName", canonicalDeviceName)
        }.toString().toByteArray(Charsets.UTF_8)
        require(requestBytes.size in 1..MAX_BODY_BYTES) { "Pair request is too large" }
        val request = Request.Builder()
            .url(endpoint.pairUrl)
            .post(requestBytes.toRequestBody(JSON_MEDIA_TYPE))
            .header("cache-control", "no-store")
            .build()

        val call = pairingClient(endpoint, pin).newCall(request)
        return OkHttpPairCall(call) { executePairCall(call, identity, canonicalDeviceName) }
    }

    private fun executePairCall(
        call: Call,
        identity: PairingIdentity,
        canonicalDeviceName: String,
    ): PairingSuccess = call.execute().use { response ->
            if (response.code != 200) throw PairingHttpException(response.code)
            val root = StrictJsonDocument.parseObject(readBounded(response.body.byteStream()), MAX_BODY_BYTES)
            root.requireExactKeys(PAIR_RESPONSE_KEYS)
            require(root.requireLong("protocolVersion") == 1L) { "Pair response version is invalid" }
            val credentialId = root.requireString("credentialId", 36)
            val deviceCredential = root.requireString("deviceCredential", 256)
            val hostId = root.requireString("hostId", 36)
            val profileObject = root["clientProfile"]?.jsonObject
                ?: error("Pair response clientProfile is invalid")
            val profile = profileObject.decodeProfile(hostId)
            VaultEnvelopeCodec.validateSecret(
                VaultSecret(
                    header = VaultHeader(identity.endpoint, identity.spkiPin, hostId, credentialId),
                    deviceCredential = deviceCredential,
                    clientInstanceId = identity.clientInstanceId,
                    deviceId = identity.deviceId,
                    deviceName = canonicalDeviceName,
                ),
            )
            PairingSuccess(hostId, credentialId, deviceCredential, profile)
        }

    fun refreshProfile(secret: VaultSecret): HostClientProfile {
        VaultEnvelopeCodec.validateSecret(secret)
        val endpoint = PinnedHostEndpoint.parse(secret.header.endpoint)
        val pin = SpkiPin.parse(secret.header.spkiPin)
        val request = Request.Builder()
            .url(endpoint.profileUrl)
            .get()
            .header("authorization", "Bearer ${secret.deviceCredential}")
            .header("cache-control", "no-store")
            .build()
        return pairingClient(endpoint, pin).newCall(request).execute().use { response ->
            if (response.code != 200) throw PairingHttpException(response.code)
            val root = StrictJsonDocument.parseObject(readBounded(response.body.byteStream()), MAX_BODY_BYTES)
            root.requireExactKeys(PROFILE_RESPONSE_KEYS)
            require(root.requireLong("protocolVersion") == 1L) { "Profile response version is invalid" }
            root["clientProfile"]?.jsonObject?.decodeProfile(secret.header.hostId)
                ?: error("Profile response is invalid")
        }
    }

    private fun JsonObject.decodeProfile(expectedHostId: String): HostClientProfile {
        requireExactKeys(PROFILE_KEYS)
        require(requireString("hostId", 36) == expectedHostId) { "Client profile Host binding differs" }
        return HostClientProfile(
            hostAlias = requireString("hostAlias", 128),
            provider = requireString("provider", 128),
            model = requireString("model", 256),
            thinking = requireString("thinking", 16),
            mutable = requireBoolean("mutable"),
            configRevision = requireLong("configRevision"),
        ).also { profile ->
            require(profile.thinking == "default" && !profile.mutable) { "Client profile is mutable" }
            require(profile.configRevision in 1..P1bProtocol.MAX_SAFE_INTEGER) {
                "Client profile revision is invalid"
            }
        }
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        require(keys == expected) { "JSON object schema differs" }
    }

    private fun JsonObject.requireString(name: String, maxChars: Int): String {
        val primitive = this[name] as? JsonPrimitive ?: error("$name is invalid")
        require(primitive.isString) { "$name is invalid" }
        return requireNotNull(primitive.contentOrNull).also { value ->
            require(value.isNotEmpty() && value.length <= maxChars) { "$name is invalid" }
        }
    }

    private fun JsonObject.requireLong(name: String): Long {
        val primitive = this[name] as? JsonPrimitive ?: error("$name is invalid")
        require(!primitive.isString) { "$name is invalid" }
        return requireNotNull(primitive.longOrNull) { "$name is invalid" }
    }

    private fun JsonObject.requireBoolean(name: String): Boolean {
        val primitive = this[name] as? JsonPrimitive ?: error("$name is invalid")
        require(!primitive.isString) { "$name is invalid" }
        return requireNotNull(primitive.booleanOrNull) { "$name is invalid" }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray = input.use { stream ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(2 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BODY_BYTES) { "Host response is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun pairingClient(endpoint: PinnedHostEndpoint, pin: SpkiPin): OkHttpClient =
        clientFactory(endpoint, pin).newBuilder()
            .callTimeout(5.seconds.toJavaDuration())
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    companion object {
        private const val MAX_BODY_BYTES = 8_192
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val PAIRING_CODE = Regex("^[A-Z2-9]{20,64}$")
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        private val PAIR_RESPONSE_KEYS = setOf(
            "protocolVersion",
            "credentialId",
            "deviceCredential",
            "hostId",
            "clientProfile",
        )
        private val PROFILE_RESPONSE_KEYS = setOf("protocolVersion", "clientProfile")
        private val PROFILE_KEYS = setOf(
            "hostId",
            "hostAlias",
            "provider",
            "model",
            "thinking",
            "mutable",
            "configRevision",
        )

        fun requireValidPairingCode(pairingCode: String) {
            require(PAIRING_CODE.matches(pairingCode)) { "Pairing code format is invalid" }
        }
    }
}

interface PairCall {
    fun execute(): PairingSuccess
    fun cancel()
}

private class OkHttpPairCall(
    private val call: Call,
    private val executeBlock: () -> PairingSuccess,
) : PairCall {
    override fun execute(): PairingSuccess = executeBlock()
    override fun cancel() = call.cancel()
}

class PairingHttpException(val statusCode: Int) : Exception("Host pairing request failed") {
    override fun toString(): String = "PairingHttpException(statusCode=$statusCode)"
}
