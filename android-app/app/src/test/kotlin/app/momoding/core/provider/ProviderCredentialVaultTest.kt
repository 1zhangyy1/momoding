package app.momoding.core.provider

import app.momoding.core.auth.AeadCiphertext
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialVaultTest {
    @Test
    fun roundTripEncryptsApiKeyAndAuthenticatesProfile() {
        val file = MemoryProviderVaultFileStore()
        val vault = ProviderCredentialVault(TestProviderAead(), file)
        val credential = credential()

        vault.store(credential)

        assertEquals(credential, vault.load())
        val bytes = requireNotNull(file.bytes)
        val envelope = ProviderVaultCodec.decodeEnvelope(bytes)
        assertEquals(credential.profile, envelope.profile)
        assertEquals(ProviderVaultCodec.IV_BYTES, envelope.iv.size)
        assertTrue(envelope.aad.contentEquals(ProviderVaultCodec.aadFor(credential.profile)))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(credential.apiKey))
        assertFalse(credential.toString().contains(credential.apiKey))
        assertTrue(credential.toString().contains("[REDACTED]"))
    }

    @Test
    fun tamperTruncationAndTrailingBytesFailClosed() {
        val file = MemoryProviderVaultFileStore()
        val vault = ProviderCredentialVault(TestProviderAead(), file)
        vault.store(credential())
        val original = requireNotNull(file.bytes)

        file.bytes = original.copyOf().also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        assertThrows(Exception::class.java, vault::load)

        file.bytes = original.copyOf(original.size - 1)
        assertThrows(IllegalArgumentException::class.java, vault::load)

        file.bytes = original + 0
        assertThrows(IllegalArgumentException::class.java, vault::load)
    }

    @Test
    fun encryptedCredentialMustMatchAuthenticatedProfileHeader() {
        val aead = TestProviderAead()
        val file = MemoryProviderVaultFileStore()
        val original = credential()
        val otherProfile = original.profile.copy(
            id = "99999999-9999-4999-8999-999999999999",
        )
        val iv = ByteArray(ProviderVaultCodec.IV_BYTES) { 7 }
        val ciphertext = aead.encryptWithIv(
            iv = iv,
            aad = ProviderVaultCodec.aadFor(otherProfile),
            plaintext = ProviderVaultCodec.encodePlaintext(original),
        )
        file.bytes = ProviderVaultCodec.encodeEnvelope(otherProfile, iv, ciphertext)

        assertThrows(IllegalArgumentException::class.java) {
            ProviderCredentialVault(aead, file).load()
        }
    }

    @Test
    fun invalidEndpointModelAndApiKeyFailBeforePersistence() {
        val file = MemoryProviderVaultFileStore()
        val vault = ProviderCredentialVault(TestProviderAead(), file)
        val original = credential()

        assertThrows(IllegalArgumentException::class.java) {
            vault.store(
                original.copy(
                    profile = original.profile.copy(baseUrl = "https://example.com/api/v1"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.store(
                original.copy(
                    profile = original.profile.copy(modelId = "missing-provider-prefix"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            vault.store(original.copy(apiKey = "contains whitespace and newline\n"))
        }
        assertFalse(vault.exists())
    }

    @Test
    fun failedAtomicPublishPreservesPreviousCredential() {
        val file = MemoryProviderVaultFileStore()
        val vault = ProviderCredentialVault(TestProviderAead(), file)
        val original = credential()
        vault.store(original)
        val durableBytes = requireNotNull(file.bytes).copyOf()
        file.failBeforePublish = true

        assertThrows(IllegalStateException::class.java) {
            vault.store(
                original.copy(
                    profile = original.profile.copy(modelId = "openai/gpt-5.2"),
                ),
            )
        }

        assertTrue(requireNotNull(file.bytes).contentEquals(durableBytes))
        file.failBeforePublish = false
        assertEquals(original, vault.load())
    }

    @Test
    fun missingKeystoreKeyNeverActivatesEncryptedProviderCredential() {
        val aead = TestProviderAead()
        val file = MemoryProviderVaultFileStore()
        val vault = ProviderCredentialVault(aead, file)
        vault.store(credential())

        aead.deleteKey()

        assertTrue(vault.exists())
        assertThrows(IllegalArgumentException::class.java, vault::load)
    }

    private fun credential(): ProviderCredential =
        ProviderCredential(
            profile = ProviderProfile(
                id = "11111111-1111-4111-8111-111111111111",
                kind = ProviderKind.OPENROUTER,
                baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                modelId = "deepseek/deepseek-v4-pro",
                displayName = "OpenRouter",
            ),
            apiKey = "test-provider-key-that-never-leaves-the-vault",
        )
}

private class MemoryProviderVaultFileStore : VaultFileStore {
    var bytes: ByteArray? = null
    var failBeforePublish: Boolean = false

    override fun read(): ByteArray? = bytes?.copyOf()

    override fun writeAtomically(bytes: ByteArray) {
        val temporary = bytes.copyOf()
        if (failBeforePublish) error("Injected pre-publish crash")
        this.bytes = temporary
    }

    override fun delete() {
        bytes = null
    }
}

private class TestProviderAead : VaultAead {
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private var exists = true
    private var ivCounter = 0

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        val iv = ByteArray(ProviderVaultCodec.IV_BYTES) { index ->
            (index + ++ivCounter).toByte()
        }
        return AeadCiphertext(iv, encryptWithIv(iv, aad, plaintext))
    }

    fun encryptWithIv(
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = crypt(Cipher.ENCRYPT_MODE, iv, aad, plaintext)

    override fun decrypt(
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = crypt(Cipher.DECRYPT_MODE, iv, aad, ciphertext)

    override fun keyExists(): Boolean = exists

    override fun deleteKey() {
        exists = false
    }

    private fun crypt(
        mode: Int,
        iv: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        check(exists) { "Provider vault key is missing" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }
}
