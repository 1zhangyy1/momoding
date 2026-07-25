package app.momoding.core.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialVaultInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val suffix = UUID.randomUUID().toString()
    private val alias = "momoding-vault-instrumented-$suffix"
    private val fileName = "vault-instrumented-$suffix.bin"
    private lateinit var aead: AndroidKeystoreAead
    private lateinit var files: AndroidNoBackupVaultFileStore

    @Before
    fun setUp() {
        aead = AndroidKeystoreAead(alias)
        files = AndroidNoBackupVaultFileStore(context, fileName)
        files.delete()
        aead.deleteKey()
    }

    @After
    fun tearDown() {
        files.delete()
        aead.deleteKey()
    }

    @Test
    fun realAndroidKeystoreSurvivesVaultReopenAndRejectsTamperAndKeyDeletion() {
        val expected = secret()
        CredentialVault(aead, files).store(expected)
        val reopened = CredentialVault(AndroidKeystoreAead(alias), AndroidNoBackupVaultFileStore(context, fileName))

        assertTrue(aead.keyExists())
        assertEquals(expected, reopened.load())
        val durable = requireNotNull(files.read())
        assertFalse(String(durable, Charsets.ISO_8859_1).contains(expected.deviceCredential))

        files.writeAtomically(durable.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertThrows(Exception::class.java, reopened::load)

        files.writeAtomically(durable)
        aead.deleteKey()
        assertTrue(reopened.exists())
        assertThrows(IllegalArgumentException::class.java, reopened::load)
    }

    @Test
    fun deletionRemovesDurableFileThenKeystoreAlias() {
        val vault = CredentialVault(aead, files)
        vault.store(secret())

        vault.deleteFile()
        vault.deleteKey()

        assertFalse(vault.exists())
        assertFalse(aead.keyExists())
        assertFalse(File(context.noBackupFilesDir, fileName).exists())
    }

    private fun secret(): VaultSecret {
        val credentialId = "22222222-2222-4222-8222-222222222222"
        val credentialSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
        return VaultSecret(
            header = VaultHeader(
                endpoint = "https://host.example:8443",
                spkiPin = "sha256/${Base64.getEncoder().encodeToString(ByteArray(32) { 4 })}",
                hostId = "11111111-1111-4111-8111-111111111111",
                credentialId = credentialId,
            ),
            deviceCredential = "cm1.$credentialId.$credentialSecret",
            clientInstanceId = "33333333-3333-4333-8333-333333333333",
            deviceId = "44444444-4444-4444-8444-444444444444",
            deviceName = "API 35 Android",
        )
    }
}
