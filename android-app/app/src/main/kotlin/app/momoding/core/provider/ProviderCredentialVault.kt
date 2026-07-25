package app.momoding.core.provider

import android.content.Context
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore

class ProviderCredentialVault(
    private val aead: VaultAead,
    private val fileStore: VaultFileStore,
) {
    fun store(credential: ProviderCredential) {
        val plaintext = ProviderVaultCodec.encodePlaintext(credential)
        val aad = ProviderVaultCodec.aadFor(credential.profile)
        val encrypted = try {
            aead.encrypt(aad, plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size == ProviderVaultCodec.IV_BYTES) {
            "Provider vault IV must be 12 bytes"
        }
        fileStore.writeAtomically(
            ProviderVaultCodec.encodeEnvelope(
                credential.profile,
                encrypted.iv,
                encrypted.ciphertext,
            ),
        )
    }

    fun load(): ProviderCredential? {
        val bytes = fileStore.read() ?: return null
        require(aead.keyExists()) { "Provider vault key is missing" }
        val envelope = ProviderVaultCodec.decodeEnvelope(bytes)
        val plaintext = aead.decrypt(envelope.iv, envelope.aad, envelope.ciphertext)
        return try {
            ProviderVaultCodec.decodePlaintext(plaintext).also { credential ->
                require(
                    ProviderVaultCodec.bindingMatches(
                        envelope.profile,
                        credential.profile,
                    ),
                ) {
                    "Provider vault header and ciphertext binding differ"
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun exists(): Boolean = fileStore.read() != null

    fun deleteFile() = fileStore.delete()

    fun deleteKey() = aead.deleteKey()

    companion object {
        const val KEYSTORE_ALIAS = "momoding-provider-credential-v1"
        const val FILE_NAME = "provider-credential.vault"

        fun create(context: Context): ProviderCredentialVault =
            ProviderCredentialVault(
                aead = AndroidKeystoreAead(KEYSTORE_ALIAS),
                fileStore = AndroidNoBackupVaultFileStore(
                    context = context.applicationContext,
                    fileName = FILE_NAME,
                ),
            )
    }
}
