package app.momoding.core.provider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCredentialVaultInstrumentedTest {
    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun androidKeystoreRoundTripLeavesNoApiKeyInDurableFile() {
        val suffix = UUID.randomUUID().toString()
        val alias = "momoding-provider-test-$suffix"
        val fileName = "provider-credential-test-$suffix.vault"
        val aead = AndroidKeystoreAead(alias)
        val fileStore = AndroidNoBackupVaultFileStore(context, fileName)
        val vault = ProviderCredentialVault(aead, fileStore)
        val credential = ProviderCredential(
            profile = ProviderProfile(
                id = "11111111-1111-4111-8111-111111111111",
                kind = ProviderKind.OPENROUTER,
                baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                modelId = "deepseek/deepseek-v4-pro",
                displayName = "OpenRouter",
            ),
            apiKey = "instrumentation-only-provider-key-${UUID.randomUUID()}",
        )

        try {
            vault.store(credential)

            assertTrue(vault.exists())
            assertTrue(aead.keyExists())
            assertEquals(credential, vault.load())
            val durableBytes = File(context.noBackupFilesDir, fileName).readBytes()
            assertFalse(
                String(durableBytes, Charsets.ISO_8859_1).contains(credential.apiKey),
            )
            assertFalse(credential.toString().contains(credential.apiKey))
        } finally {
            runCatching(vault::deleteFile)
            runCatching(vault::deleteKey)
        }
    }
}
