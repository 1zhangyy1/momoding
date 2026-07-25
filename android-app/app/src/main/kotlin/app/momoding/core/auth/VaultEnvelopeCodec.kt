package app.momoding.core.auth

import app.momoding.core.transport.PinnedHostEndpoint
import app.momoding.core.transport.SpkiPin
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class VaultHeader(
    val endpoint: String,
    val spkiPin: String,
    val hostId: String,
    val credentialId: String,
)

data class VaultSecret(
    val header: VaultHeader,
    val deviceCredential: String,
    val clientInstanceId: String,
    val deviceId: String,
    val deviceName: String,
) {
    override fun toString(): String =
        "VaultSecret(header=$header, deviceCredential=[REDACTED], " +
            "clientInstanceId=$clientInstanceId, deviceId=$deviceId, deviceName=$deviceName)"
}

data class EncryptedVaultEnvelope(
    val header: VaultHeader,
    val aad: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

object VaultEnvelopeCodec {
    fun encodePlaintext(secret: VaultSecret): ByteArray {
        validateSecret(secret)
        return encodeFields(
            listOf(
                1 to secret.header.endpoint,
                2 to secret.header.spkiPin,
                3 to secret.header.hostId,
                4 to secret.header.credentialId,
                5 to secret.deviceCredential,
                6 to secret.clientInstanceId,
                7 to secret.deviceId,
                8 to secret.deviceName,
            ),
        )
    }

    fun decodePlaintext(bytes: ByteArray): VaultSecret {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Vault plaintext is too large" }
        val fields = decodeFields(bytes, 8)
        val secret = VaultSecret(
            header = VaultHeader(fields[0], fields[1], fields[2], fields[3]),
            deviceCredential = fields[4],
            clientInstanceId = fields[5],
            deviceId = fields[6],
            deviceName = fields[7],
        )
        validateSecret(secret)
        return secret
    }

    fun encodeEnvelope(
        header: VaultHeader,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        validateHeader(header)
        require(iv.size == IV_BYTES) { "Vault IV must be 12 bytes" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Vault ciphertext size is invalid"
        }
        val headerBytes = encodeHeader(header)
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

    fun decodeEnvelope(bytes: ByteArray): EncryptedVaultEnvelope {
        require(bytes.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Vault envelope size is invalid"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(input::get)
        require(magic.contentEquals(MAGIC)) { "Vault envelope magic is invalid" }
        require(input.get().toInt() and 0xff == VERSION) { "Vault envelope version is unsupported" }
        val headerLength = input.int
        require(headerLength in 1..MAX_HEADER_BYTES && input.remaining() >= headerLength + 1 + IV_BYTES + 4) {
            "Vault header length is invalid"
        }
        val headerBytes = ByteArray(headerLength).also(input::get)
        val header = decodeHeader(headerBytes)
        val aadLength = MAGIC.size + 1 + 4 + headerLength
        val aad = bytes.copyOfRange(0, aadLength)
        val ivLength = input.get().toInt() and 0xff
        require(ivLength == IV_BYTES && input.remaining() >= ivLength + 4) { "Vault IV is invalid" }
        val iv = ByteArray(ivLength).also(input::get)
        val ciphertextLength = input.int
        require(
            ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES &&
                input.remaining() == ciphertextLength,
        ) { "Vault ciphertext length is invalid" }
        val ciphertext = ByteArray(ciphertextLength).also(input::get)
        require(!input.hasRemaining()) { "Vault envelope has trailing data" }
        return EncryptedVaultEnvelope(header, aad, iv, ciphertext)
    }

    fun aadFor(header: VaultHeader): ByteArray {
        validateHeader(header)
        return aadFor(encodeHeader(header))
    }

    fun validateSecret(secret: VaultSecret) {
        validateHeader(secret.header)
        require(CANONICAL_UUID.matches(secret.clientInstanceId)) { "clientInstanceId is invalid" }
        require(CANONICAL_UUID.matches(secret.deviceId)) { "deviceId is invalid" }
        DeviceNamePolicy.requireCanonical(secret.deviceName)
        val credentialPattern = Regex(
            "^cm1\\.${Regex.escape(secret.header.credentialId)}\\.[A-Za-z0-9_-]{43}$",
        )
        require(credentialPattern.matches(secret.deviceCredential)) { "Device credential is invalid" }
    }

    fun validateHeader(header: VaultHeader) {
        PinnedHostEndpoint.parse(header.endpoint)
        SpkiPin.parse(header.spkiPin)
        require(CANONICAL_UUID.matches(header.hostId)) { "hostId is invalid" }
        require(CANONICAL_UUID.matches(header.credentialId)) { "credentialId is invalid" }
    }

    fun bindingMatches(left: VaultHeader, right: VaultHeader): Boolean =
        left.endpoint == right.endpoint &&
            left.spkiPin == right.spkiPin &&
            left.hostId == right.hostId &&
            left.credentialId == right.credentialId

    private fun encodeHeader(header: VaultHeader): ByteArray = encodeFields(
        listOf(
            1 to header.endpoint,
            2 to header.spkiPin,
            3 to header.hostId,
            4 to header.credentialId,
        ),
    ).also {
        require(it.size <= MAX_HEADER_BYTES) { "Vault header is too large" }
    }

    private fun decodeHeader(bytes: ByteArray): VaultHeader {
        val fields = decodeFields(bytes, 4)
        return VaultHeader(fields[0], fields[1], fields[2], fields[3]).also(::validateHeader)
    }

    private fun aadFor(headerBytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(MAGIC)
        output.write(VERSION)
        output.writeInt(headerBytes.size)
        output.write(headerBytes)
        output.toByteArray()
    }

    private fun encodeFields(fields: List<Pair<Int, String>>): ByteArray =
        ByteArrayOutputStream().use { output ->
            fields.forEachIndexed { index, (id, value) ->
                require(id == index + 1) { "Vault field order is invalid" }
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                require(encoded.isNotEmpty() && encoded.size <= MAX_FIELD_BYTES) {
                    "Vault field length is invalid"
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
                require(input.remaining() >= 5) { "Vault field is truncated" }
                require(input.get().toInt() and 0xff == expectedId) { "Vault field schema is invalid" }
                val length = input.int
                require(length in 1..MAX_FIELD_BYTES && input.remaining() >= length) {
                    "Vault field length is invalid"
                }
                val field = ByteArray(length).also(input::get)
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(field))
                    .toString()
                require(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(field)) {
                    "Vault field is not canonical UTF-8"
                }
                add(decoded)
            }
            require(!input.hasRemaining()) { "Vault field schema has unknown or trailing data" }
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    }

    const val IV_BYTES = 12
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    private const val VERSION = 1
    private const val MAX_HEADER_BYTES = 2 * 1024
    private const val MAX_PLAINTEXT_BYTES = 8 * 1024
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private const val MIN_CIPHERTEXT_BYTES = 17
    private const val MIN_ENVELOPE_BYTES = 8 + 1 + 4 + 1 + 1 + IV_BYTES + 4 + MIN_CIPHERTEXT_BYTES
    private const val MAX_FIELD_BYTES = 4 * 1024
    private val MAGIC = "CMVAULT1".toByteArray(StandardCharsets.US_ASCII)
    private val CANONICAL_UUID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
}
