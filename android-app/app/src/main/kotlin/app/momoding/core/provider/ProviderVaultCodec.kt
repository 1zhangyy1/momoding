package app.momoding.core.provider

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class EncryptedProviderEnvelope(
    val profile: ProviderProfile,
    val aad: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

object ProviderVaultCodec {
    fun encodePlaintext(credential: ProviderCredential): ByteArray {
        ProviderProfilePolicy.validate(credential)
        return encodeFields(
            listOf(
                1 to credential.profile.id,
                2 to credential.profile.kind.wireValue,
                3 to credential.profile.baseUrl,
                4 to credential.profile.modelId,
                5 to credential.profile.displayName,
                6 to credential.apiKey,
            ),
        )
    }

    fun decodePlaintext(bytes: ByteArray): ProviderCredential {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Provider vault plaintext is too large" }
        val fields = decodeFields(bytes, PLAINTEXT_FIELD_COUNT)
        return ProviderCredential(
            profile = ProviderProfile(
                id = fields[0],
                kind = ProviderKind.fromWireValue(fields[1]),
                baseUrl = fields[2],
                modelId = fields[3],
                displayName = fields[4],
            ),
            apiKey = fields[5],
        ).also(ProviderProfilePolicy::validate)
    }

    fun encodeEnvelope(
        profile: ProviderProfile,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        ProviderProfilePolicy.validate(profile)
        require(iv.size == IV_BYTES) { "Provider vault IV must be 12 bytes" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Provider vault ciphertext size is invalid"
        }
        val headerBytes = encodeProfile(profile)
        val aad = aadFor(headerBytes)
        return ByteArrayOutputStream().use { output ->
            output.write(aad)
            output.write(iv.size)
            output.write(iv)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
            output.toByteArray()
        }
    }

    fun decodeEnvelope(bytes: ByteArray): EncryptedProviderEnvelope {
        require(bytes.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Provider vault envelope size is invalid"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(input::get)
        require(magic.contentEquals(MAGIC)) { "Provider vault magic is invalid" }
        require(input.get().toInt() and 0xff == VERSION) {
            "Provider vault version is unsupported"
        }
        val headerLength = input.int
        require(
            headerLength in 1..MAX_HEADER_BYTES &&
                input.remaining() >= headerLength + 1 + IV_BYTES + 4,
        ) {
            "Provider vault header length is invalid"
        }
        val headerBytes = ByteArray(headerLength).also(input::get)
        val profile = decodeProfile(headerBytes)
        val aadLength = MAGIC.size + 1 + 4 + headerLength
        val aad = bytes.copyOfRange(0, aadLength)
        val ivLength = input.get().toInt() and 0xff
        require(ivLength == IV_BYTES && input.remaining() >= ivLength + 4) {
            "Provider vault IV is invalid"
        }
        val iv = ByteArray(ivLength).also(input::get)
        val ciphertextLength = input.int
        require(
            ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES &&
                input.remaining() == ciphertextLength,
        ) {
            "Provider vault ciphertext length is invalid"
        }
        val ciphertext = ByteArray(ciphertextLength).also(input::get)
        require(!input.hasRemaining()) { "Provider vault envelope has trailing data" }
        return EncryptedProviderEnvelope(profile, aad, iv, ciphertext)
    }

    fun aadFor(profile: ProviderProfile): ByteArray {
        ProviderProfilePolicy.validate(profile)
        return aadFor(encodeProfile(profile))
    }

    fun bindingMatches(left: ProviderProfile, right: ProviderProfile): Boolean =
        left == right

    private fun encodeProfile(profile: ProviderProfile): ByteArray =
        encodeFields(
            listOf(
                1 to profile.id,
                2 to profile.kind.wireValue,
                3 to profile.baseUrl,
                4 to profile.modelId,
                5 to profile.displayName,
            ),
        ).also {
            require(it.size <= MAX_HEADER_BYTES) { "Provider vault header is too large" }
        }

    private fun decodeProfile(bytes: ByteArray): ProviderProfile {
        val fields = decodeFields(bytes, HEADER_FIELD_COUNT)
        return ProviderProfile(
            id = fields[0],
            kind = ProviderKind.fromWireValue(fields[1]),
            baseUrl = fields[2],
            modelId = fields[3],
            displayName = fields[4],
        ).also(ProviderProfilePolicy::validate)
    }

    private fun aadFor(headerBytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(MAGIC)
            output.write(VERSION)
            output.writeInt(headerBytes.size)
            output.write(headerBytes)
            output.toByteArray()
        }

    private fun encodeFields(fields: List<Pair<Int, String>>): ByteArray =
        ByteArrayOutputStream().use { output ->
            fields.forEachIndexed { index, (id, value) ->
                require(id == index + 1) { "Provider vault field order is invalid" }
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                require(encoded.isNotEmpty() && encoded.size <= MAX_FIELD_BYTES) {
                    "Provider vault field length is invalid"
                }
                output.write(id)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            output.toByteArray()
        }

    private fun decodeFields(bytes: ByteArray, expectedCount: Int): List<String> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return buildList(expectedCount) {
            for (expectedId in 1..expectedCount) {
                require(input.remaining() >= FIELD_PREFIX_BYTES) {
                    "Provider vault field is truncated"
                }
                require(input.get().toInt() and 0xff == expectedId) {
                    "Provider vault field schema is invalid"
                }
                val length = input.int
                require(length in 1..MAX_FIELD_BYTES && input.remaining() >= length) {
                    "Provider vault field length is invalid"
                }
                val field = ByteArray(length).also(input::get)
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(field))
                    .toString()
                require(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(field)) {
                    "Provider vault field is not canonical UTF-8"
                }
                add(decoded)
            }
            require(!input.hasRemaining()) {
                "Provider vault field schema has unknown or trailing data"
            }
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    }

    const val IV_BYTES = 12
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    private const val VERSION = 1
    private const val HEADER_FIELD_COUNT = 5
    private const val PLAINTEXT_FIELD_COUNT = 6
    private const val FIELD_PREFIX_BYTES = 5
    private const val MAX_HEADER_BYTES = 2 * 1024
    private const val MAX_PLAINTEXT_BYTES = 8 * 1024
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private const val MIN_CIPHERTEXT_BYTES = 17
    private const val MIN_ENVELOPE_BYTES = 8 + 1 + 4 + 1 + 1 + IV_BYTES + 4 +
        MIN_CIPHERTEXT_BYTES
    private const val MAX_FIELD_BYTES = 4 * 1024
    private val MAGIC = "CMPROV01".toByteArray(StandardCharsets.US_ASCII)
}
