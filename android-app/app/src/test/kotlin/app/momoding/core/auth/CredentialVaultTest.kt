package app.momoding.core.auth

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultTest {
    @Test
    fun roundTripBindsHeaderAndCiphertext() {
        val file = MemoryVaultFileStore()
        val vault = CredentialVault(TestAead(), file)
        val secret = secret()

        vault.store(secret)

        assertEquals(secret, vault.load())
        val envelope = VaultEnvelopeCodec.decodeEnvelope(requireNotNull(file.bytes))
        assertEquals(secret.header, envelope.header)
        assertEquals(VaultEnvelopeCodec.IV_BYTES, envelope.iv.size)
        assertTrue(envelope.aad.contentEquals(VaultEnvelopeCodec.aadFor(secret.header)))
        assertFalse(String(requireNotNull(file.bytes), Charsets.ISO_8859_1).contains(secret.deviceCredential))
    }

    @Test
    fun tamperTruncationAndTrailingBytesFailClosed() {
        val file = MemoryVaultFileStore()
        val vault = CredentialVault(TestAead(), file)
        vault.store(secret())
        val original = requireNotNull(file.bytes)

        val ciphertextTampered = original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.bytes = ciphertextTampered
        assertThrows(Exception::class.java, vault::load)

        file.bytes = original.copyOf(original.size - 1)
        assertThrows(IllegalArgumentException::class.java, vault::load)

        file.bytes = original + 0
        assertThrows(IllegalArgumentException::class.java, vault::load)
    }

    @Test
    fun duplicatedBindingInsideCiphertextMustMatchAuthenticatedHeader() {
        val aead = TestAead()
        val file = MemoryVaultFileStore()
        val original = secret()
        val otherHeader = original.header.copy(hostId = "99999999-9999-4999-8999-999999999999")
        val iv = ByteArray(VaultEnvelopeCodec.IV_BYTES) { 7 }
        val encrypted = aead.encryptWithIv(
            iv,
            VaultEnvelopeCodec.aadFor(otherHeader),
            VaultEnvelopeCodec.encodePlaintext(original),
        )
        file.bytes = VaultEnvelopeCodec.encodeEnvelope(otherHeader, iv, encrypted)

        assertThrows(IllegalArgumentException::class.java) {
            CredentialVault(aead, file).load()
        }
    }

    @Test
    fun strictCodecRejectsUnknownOrTrailingPlaintextFields() {
        val encoded = VaultEnvelopeCodec.encodePlaintext(secret())

        assertThrows(IllegalArgumentException::class.java) {
            VaultEnvelopeCodec.decodePlaintext(encoded + byteArrayOf(9, 0, 0, 0, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultEnvelopeCodec.decodePlaintext(encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun failedAtomicPublishPreservesPreviousDurableCredential() {
        val file = MemoryVaultFileStore()
        val vault = CredentialVault(TestAead(), file)
        val original = secret()
        vault.store(original)
        val durableBytes = requireNotNull(file.bytes).copyOf()
        file.failBeforePublish = true

        assertThrows(IllegalStateException::class.java) {
            vault.store(
                original.copy(deviceName = "Replacement"),
            )
        }

        assertTrue(requireNotNull(file.bytes).contentEquals(durableBytes))
        file.failBeforePublish = false
        assertEquals(original, vault.load())
    }

    @Test
    fun missingKeyNeverTurnsEncryptedFileIntoAnActiveCredential() {
        val aead = TestAead()
        val file = MemoryVaultFileStore()
        val vault = CredentialVault(aead, file)
        vault.store(secret())

        aead.deleteKey()

        assertTrue(vault.exists())
        assertThrows(IllegalArgumentException::class.java, vault::load)
    }

    private fun secret(): VaultSecret {
        val credentialId = "22222222-2222-4222-8222-222222222222"
        val credentialSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        val pin = "sha256/${Base64.getEncoder().encodeToString(ByteArray(32) { 4 })}"
        return VaultSecret(
            header = VaultHeader(
                endpoint = "https://host.example:8443",
                spkiPin = pin,
                hostId = "11111111-1111-4111-8111-111111111111",
                credentialId = credentialId,
            ),
            deviceCredential = "cm1.$credentialId.$credentialSecret",
            clientInstanceId = "33333333-3333-4333-8333-333333333333",
            deviceId = "44444444-4444-4444-8444-444444444444",
            deviceName = "Test Android",
        )
    }
}

private class MemoryVaultFileStore : VaultFileStore {
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

private class TestAead : VaultAead {
    private var key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
    private var exists = true

    private var ivCounter = 0

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        val iv = ByteArray(VaultEnvelopeCodec.IV_BYTES) { index -> (index + ++ivCounter).toByte() }
        return AeadCiphertext(iv, encryptWithIv(iv, aad, plaintext))
    }

    fun encryptWithIv(iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, iv, aad, plaintext)

    override fun decrypt(iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, iv, aad, ciphertext)

    override fun keyExists(): Boolean = exists

    override fun deleteKey() {
        exists = false
    }

    private fun crypt(mode: Int, iv: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        check(exists) { "Vault key is missing" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(input)
        }
    }
}
