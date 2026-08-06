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

class CodexOAuthVaultTest {
    @Test
    fun `round trip encrypts both tokens and redacts credential`() {
        val file = MemoryCodexVaultFileStore()
        val vault = CodexOAuthVault(TestCodexAead(), file)
        val credential = credential()

        vault.store(credential)

        assertEquals(credential, vault.load())
        val bytes = requireNotNull(file.bytes)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(credential.accessToken))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(credential.refreshToken))
        assertFalse(credential.toString().contains(credential.accessToken))
        assertFalse(credential.toString().contains(credential.refreshToken))
        assertTrue(credential.toString().contains("[REDACTED]"))
    }

    @Test
    fun `tamper truncation trailing data and missing key fail closed`() {
        val aead = TestCodexAead()
        val file = MemoryCodexVaultFileStore()
        val vault = CodexOAuthVault(aead, file)
        vault.store(credential())
        val original = requireNotNull(file.bytes)

        file.bytes = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(Exception::class.java, vault::load)

        file.bytes = original.copyOf(original.size - 1)
        assertThrows(IllegalArgumentException::class.java, vault::load)

        file.bytes = original + 0
        assertThrows(IllegalArgumentException::class.java, vault::load)

        file.bytes = original
        aead.deleteKey()
        assertThrows(IllegalArgumentException::class.java, vault::load)
    }

    @Test
    fun `failed publish preserves previous tokens and logout removes only vault file`() {
        val aead = TestCodexAead()
        val file = MemoryCodexVaultFileStore()
        val vault = CodexOAuthVault(aead, file)
        val original = credential()
        vault.store(original)
        val durable = requireNotNull(file.bytes).copyOf()
        file.failBeforePublish = true

        assertThrows(IllegalStateException::class.java) {
            vault.store(original.copy(refreshToken = "rotated-refresh-token"))
        }
        assertTrue(requireNotNull(file.bytes).contentEquals(durable))
        file.failBeforePublish = false
        assertEquals(original, vault.load())

        vault.delete()
        assertFalse(vault.exists())
        assertTrue(aead.keyExists())
    }

    private fun credential() = CodexOAuthCredential(
        accessToken = "header.payload.signature",
        refreshToken = "refresh-token-that-stays-encrypted",
        expiresAtMillis = 1_800_000_000_000L,
        accountId = "account-123",
    )
}

internal class MemoryCodexVaultFileStore : VaultFileStore {
    var bytes: ByteArray? = null
    var failBeforePublish = false

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

internal class TestCodexAead : VaultAead {
    private val key = SecretKeySpec(ByteArray(32) { 4 }, "AES")
    private var exists = true
    private var ivCounter = 0

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        val iv = ByteArray(CodexOAuthVaultCodec.IV_BYTES) { index ->
            (index + ++ivCounter).toByte()
        }
        return AeadCiphertext(iv, crypt(Cipher.ENCRYPT_MODE, iv, aad, plaintext))
    }

    override fun decrypt(
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = crypt(Cipher.DECRYPT_MODE, iv, aad, ciphertext)

    override fun keyExists(): Boolean = exists

    override fun deleteKey() {
        exists = false
    }

    private fun crypt(mode: Int, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        check(exists) { "Codex OAuth key is missing" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }
}
