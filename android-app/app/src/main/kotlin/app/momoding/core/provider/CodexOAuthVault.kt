package app.momoding.core.provider

import android.content.Context
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CodexOAuthVault internal constructor(
    private val aead: VaultAead,
    private val fileStore: VaultFileStore,
) {
    fun store(credential: CodexOAuthCredential) {
        CodexOAuthCredentialPolicy.validate(credential)
        val plaintext = CodexOAuthVaultCodec.encodePlaintext(credential)
        val encrypted = try {
            aead.encrypt(CodexOAuthVaultCodec.AAD, plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size == CodexOAuthVaultCodec.IV_BYTES) {
            "Codex OAuth vault IV is invalid"
        }
        fileStore.writeAtomically(
            CodexOAuthVaultCodec.encodeEnvelope(encrypted.iv, encrypted.ciphertext),
        )
    }

    fun load(): CodexOAuthCredential? {
        val envelopeBytes = fileStore.read() ?: return null
        require(aead.keyExists()) { "Codex OAuth vault key is missing" }
        val envelope = CodexOAuthVaultCodec.decodeEnvelope(envelopeBytes)
        val plaintext = aead.decrypt(
            envelope.iv,
            CodexOAuthVaultCodec.AAD,
            envelope.ciphertext,
        )
        return try {
            CodexOAuthVaultCodec.decodePlaintext(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun exists(): Boolean = fileStore.read() != null

    fun delete() = fileStore.delete()

    companion object {
        const val KEYSTORE_ALIAS = "momoding-codex-oauth-v1"
        const val FILE_NAME = "codex-oauth.vault"

        fun create(context: Context): CodexOAuthVault = CodexOAuthVault(
            aead = AndroidKeystoreAead(KEYSTORE_ALIAS),
            fileStore = AndroidNoBackupVaultFileStore(context.applicationContext, FILE_NAME),
        )
    }
}

internal object CodexOAuthCredentialPolicy {
    fun validate(credential: CodexOAuthCredential) {
        requireToken(credential.accessToken, MAX_TOKEN_BYTES, "access token")
        requireToken(credential.refreshToken, MAX_TOKEN_BYTES, "refresh token")
        require(credential.expiresAtMillis > 0) { "Codex OAuth expiry is invalid" }
        requireToken(credential.accountId, MAX_ACCOUNT_ID_BYTES, "account id")
    }

    private fun requireToken(value: String, maxBytes: Int, name: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..maxBytes && value.all { it.code in 0x21..0x7e }) {
            "Codex OAuth $name is invalid"
        }
    }

    const val MAX_TOKEN_BYTES = 6 * 1024
    const val MAX_ACCOUNT_ID_BYTES = 512
}

internal data class CodexOAuthEncryptedEnvelope(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object CodexOAuthVaultCodec {
    private val MAGIC = "MMCODEX1".toByteArray(StandardCharsets.US_ASCII)
    val AAD: ByteArray
        get() = MAGIC + byteArrayOf(VERSION.toByte())

    fun encodePlaintext(credential: CodexOAuthCredential): ByteArray {
        CodexOAuthCredentialPolicy.validate(credential)
        return ByteArrayOutputStream().use { output ->
            output.writeField(1, credential.accessToken)
            output.writeField(2, credential.refreshToken)
            output.writeField(3, credential.expiresAtMillis.toString())
            output.writeField(4, credential.accountId)
            output.toByteArray()
        }.also { require(it.size <= MAX_PLAINTEXT_BYTES) { "Codex OAuth plaintext is too large" } }
    }

    fun decodePlaintext(bytes: ByteArray): CodexOAuthCredential {
        require(bytes.size in 1..MAX_PLAINTEXT_BYTES) { "Codex OAuth plaintext size is invalid" }
        val fields = decodeFields(bytes, 4)
        return CodexOAuthCredential(
            accessToken = fields[0],
            refreshToken = fields[1],
            expiresAtMillis = fields[2].toLongOrNull()
                ?: throw IllegalArgumentException("Codex OAuth expiry is invalid"),
            accountId = fields[3],
        ).also(CodexOAuthCredentialPolicy::validate)
    }

    fun encodeEnvelope(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "Codex OAuth vault IV is invalid" }
        require(ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Codex OAuth vault ciphertext size is invalid"
        }
        return ByteArrayOutputStream().use { output ->
            output.write(AAD)
            output.write(iv.size)
            output.write(iv)
            output.writeInt(ciphertext.size)
            output.write(ciphertext)
            output.toByteArray()
        }
    }

    fun decodeEnvelope(bytes: ByteArray): CodexOAuthEncryptedEnvelope {
        require(bytes.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Codex OAuth vault envelope size is invalid"
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val aad = ByteArray(AAD.size).also(input::get)
        require(aad.contentEquals(AAD)) { "Codex OAuth vault header is invalid" }
        val ivLength = input.get().toInt() and 0xff
        require(ivLength == IV_BYTES && input.remaining() >= ivLength + 4) {
            "Codex OAuth vault IV is invalid"
        }
        val iv = ByteArray(ivLength).also(input::get)
        val ciphertextLength = input.int
        require(
            ciphertextLength in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES &&
                input.remaining() == ciphertextLength,
        ) { "Codex OAuth vault ciphertext size is invalid" }
        return CodexOAuthEncryptedEnvelope(
            iv = iv,
            ciphertext = ByteArray(ciphertextLength).also(input::get),
        )
    }

    private fun ByteArrayOutputStream.writeField(id: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..CodexOAuthCredentialPolicy.MAX_TOKEN_BYTES) {
            "Codex OAuth field is invalid"
        }
        write(id)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun decodeFields(bytes: ByteArray, expectedCount: Int): List<String> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return buildList(expectedCount) {
            for (expectedId in 1..expectedCount) {
                require(input.remaining() >= FIELD_PREFIX_BYTES) { "Codex OAuth field is truncated" }
                require(input.get().toInt() and 0xff == expectedId) {
                    "Codex OAuth field schema is invalid"
                }
                val length = input.int
                require(length in 1..CodexOAuthCredentialPolicy.MAX_TOKEN_BYTES && input.remaining() >= length) {
                    "Codex OAuth field length is invalid"
                }
                val field = ByteArray(length).also(input::get)
                val value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(field))
                    .toString()
                require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(field)) {
                    "Codex OAuth field is not canonical UTF-8"
                }
                add(value)
            }
            require(!input.hasRemaining()) { "Codex OAuth vault has trailing data" }
        }
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
    }

    const val IV_BYTES = 12
    const val MAX_ENVELOPE_BYTES = 16 * 1024
    private const val VERSION = 1
    private const val FIELD_PREFIX_BYTES = 5
    private const val MAX_PLAINTEXT_BYTES = 14 * 1024
    private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 16
    private const val MIN_CIPHERTEXT_BYTES = 17
    private const val MIN_ENVELOPE_BYTES = 8 + 1 + 1 + IV_BYTES + 4 + MIN_CIPHERTEXT_BYTES
}

interface CodexCredentialSource {
    suspend fun requireValidCredential(): CodexOAuthCredential

    suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): CodexOAuthCredential
}

class CodexOAuthCredentialManager internal constructor(
    private val vault: CodexOAuthVault,
    private val gateway: CodexOAuthGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CodexCredentialSource {
    private val refreshMutex = Mutex()

    fun store(credential: CodexOAuthCredential) = vault.store(credential)

    fun current(): CodexOAuthCredential? = vault.load()

    override suspend fun requireValidCredential(): CodexOAuthCredential = refreshMutex.withLock {
        val current = vault.load() ?: error("CODEX_OAUTH_NOT_CONFIGURED")
        if (current.expiresAtMillis - nowMillis() > REFRESH_SKEW_MILLIS) return@withLock current
        val refreshed = gateway.refresh(current.refreshToken)
        require(refreshed.accountId == current.accountId) {
            "Codex OAuth refresh changed account"
        }
        vault.store(refreshed)
        refreshed
    }

    override suspend fun refreshAfterUnauthorized(
        rejectedAccessToken: String,
    ): CodexOAuthCredential = refreshMutex.withLock {
        val current = vault.load() ?: error("CODEX_OAUTH_NOT_CONFIGURED")
        if (current.accessToken != rejectedAccessToken) return@withLock current
        val refreshed = gateway.refresh(current.refreshToken)
        require(refreshed.accountId == current.accountId) {
            "Codex OAuth refresh changed account"
        }
        vault.store(refreshed)
        refreshed
    }

    fun logout() = vault.delete()

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
    }
}
