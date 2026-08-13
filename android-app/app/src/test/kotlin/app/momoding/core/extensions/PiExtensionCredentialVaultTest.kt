package app.momoding.core.extensions

import app.momoding.core.auth.AeadCiphertext
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiExtensionCredentialVaultTest {
    @Test
    fun `binding is package digest slot and origin scoped without plaintext persistence`() = runBlocking {
        val store = MemoryStore()
        val vault = PiExtensionCredentialVault(TestAead(), store)
        val binding = binding()
        val secret = "fixture-secret-one".toCharArray()

        vault.bind(binding, secret)

        assertFalse(store.bytes!!.decodeToString().contains("fixture-secret-one"))
        assertEquals(
            "fixture-secret-one",
            vault.resolveBearer(binding.ref())?.concatToString(),
        )
        assertNull(vault.resolveBearer(binding.copy(packageDigest = "b".repeat(64)).ref()))
        assertNull(vault.resolveBearer(binding.copy(slot = "another-slot").ref()))
        assertEquals(listOf(binding), vault.metadata())

        vault.bind(binding, "fixture-secret-two".toCharArray())
        assertEquals(1, vault.metadata().size)
        assertEquals("fixture-secret-two", vault.resolveBearer(binding.ref())?.concatToString())
        vault.remove(binding)
        assertNull(vault.resolveBearer(binding.ref()))
        assertNull(store.bytes)
    }

    @Test
    fun `tamper invalid binding and package removal fail closed`() = runBlocking {
        val store = MemoryStore()
        val vault = PiExtensionCredentialVault(TestAead(), store)
        val first = binding()
        val second = binding().copy(packageId = "fixtures.second")
        vault.bind(first, "secret-one".toCharArray())
        vault.bind(second, "secret-two".toCharArray())
        assertEquals(2, vault.metadata().size)

        vault.removePackage(first.packageId)
        assertNull(vault.resolveBearer(first.ref()))
        assertEquals("secret-two", vault.resolveBearer(second.ref())?.concatToString())

        assertThrows(IllegalArgumentException::class.java) {
            vault.bind(first.copy(origin = "http://insecure.example"), "secret".toCharArray())
        }
        val corrupted = store.bytes!!.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        store.bytes = corrupted
        assertThrows(Exception::class.java) { vault.metadata() }
        assertFalse(corrupted.decodeToString().contains("secret-two"))
    }

    private fun binding(): PiExtensionCredentialBinding = PiExtensionCredentialBinding(
        packageId = "fixtures.network",
        packageDigest = "a".repeat(64),
        slot = "status-api",
        origin = "https://status.example.test",
    )

    private fun PiExtensionCredentialBinding.ref() = PiExtensionCredentialBindingRef(
        packageId,
        packageDigest,
        slot,
        origin,
    )
}

private class MemoryStore : VaultFileStore {
    var bytes: ByteArray? = null
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun writeAtomically(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }
    override fun delete() {
        bytes = null
    }
}

private class TestAead : VaultAead {
    private var exists = false
    private val key = 0x5a

    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext {
        exists = true
        val ciphertext = ByteArray(plaintext.size + 1)
        plaintext.forEachIndexed { index, byte ->
            ciphertext[index] = (byte.toInt() xor key).toByte()
        }
        ciphertext[ciphertext.lastIndex] = tag(aad)
        return AeadCiphertext(ByteArray(12) { (it + 1).toByte() }, ciphertext)
    }

    override fun decrypt(iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (!exists || iv.size != 12 || ciphertext.isEmpty() || ciphertext.last() != tag(aad)) {
            throw AEADBadTagException()
        }
        return ByteArray(ciphertext.size - 1) { index ->
            (ciphertext[index].toInt() xor key).toByte()
        }
    }

    override fun keyExists(): Boolean = exists
    override fun deleteKey() {
        exists = false
    }

    private fun tag(aad: ByteArray): Byte = aad.fold(0) { sum, byte -> sum + byte }.toByte()
}
