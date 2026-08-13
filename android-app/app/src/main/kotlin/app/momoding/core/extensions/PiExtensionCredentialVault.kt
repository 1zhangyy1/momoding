package app.momoding.core.extensions

import android.content.Context
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import app.momoding.core.auth.VaultAead
import app.momoding.core.auth.VaultFileStore
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PiExtensionCredentialBinding(
    val packageId: String,
    val packageDigest: String,
    val slot: String,
    val origin: String,
)

interface PiExtensionCredentialStore : PiExtensionCredentialResolver {
    fun bind(binding: PiExtensionCredentialBinding, bearerToken: CharArray)
    fun metadata(): List<PiExtensionCredentialBinding>
    fun remove(binding: PiExtensionCredentialBinding)
    fun removePackage(packageId: String)
}

class PiExtensionCredentialVault internal constructor(
    private val aead: VaultAead,
    private val fileStore: VaultFileStore,
) : PiExtensionCredentialStore {
    private val lock = PROCESS_LOCK

    override fun bind(binding: PiExtensionCredentialBinding, bearerToken: CharArray) = synchronized(lock) {
        requireBinding(binding)
        require(bearerToken.isNotEmpty() && bearerToken.size <= MAX_SECRET_CHARS &&
            bearerToken.none { it == '\u0000' }) {
            "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
        }
        val record = StoredBinding(
            packageId = binding.packageId,
            packageDigest = binding.packageDigest,
            slot = binding.slot,
            origin = binding.origin,
            bearerToken = bearerToken.concatToString(),
        )
        val current = readState()
        val retained = current.bindings.filterNot { it.key() == record.key() }
        writeState(CredentialState(bindings = (retained + record).also {
            require(it.size <= MAX_BINDINGS) { "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_LIMIT" }
        }))
    }

    override suspend fun resolveBearer(binding: PiExtensionCredentialBindingRef): CharArray? =
        synchronized(lock) {
            val requested = PiExtensionCredentialBinding(
                binding.packageId,
                binding.packageDigest,
                binding.slot,
                binding.origin,
            )
            requireBinding(requested)
            readState().bindings.singleOrNull {
                it.packageId == binding.packageId && it.packageDigest == binding.packageDigest &&
                    it.slot == binding.slot && it.origin == binding.origin
            }?.bearerToken?.toCharArray()
        }

    override fun metadata(): List<PiExtensionCredentialBinding> = synchronized(lock) {
        readState().bindings.map { binding ->
            PiExtensionCredentialBinding(
                binding.packageId,
                binding.packageDigest,
                binding.slot,
                binding.origin,
            )
        }
    }

    override fun remove(binding: PiExtensionCredentialBinding) = synchronized(lock) {
        requireBinding(binding)
        writeState(CredentialState(bindings = readState().bindings.filterNot {
            it.packageId == binding.packageId && it.packageDigest == binding.packageDigest &&
                it.slot == binding.slot && it.origin == binding.origin
        }))
    }

    override fun removePackage(packageId: String) = synchronized(lock) {
        requirePackageId(packageId)
        writeState(CredentialState(bindings = readState().bindings.filterNot {
            it.packageId == packageId
        }))
    }

    fun deleteFile() = synchronized(lock) { fileStore.delete() }

    fun deleteKey() = synchronized(lock) { aead.deleteKey() }

    private fun readState(): CredentialState {
        val bytes = fileStore.read() ?: return CredentialState()
        require(aead.keyExists()) { "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_KEY_MISSING" }
        val envelope = try {
            JSON.decodeFromString<CredentialEnvelope>(bytes.decodeToString())
        } catch (_: Exception) {
            error("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID")
        }
        require(envelope.version == VERSION && envelope.aad == AAD_LABEL) {
            "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
        }
        val iv = decode(envelope.iv)
        val ciphertext = decode(envelope.ciphertext)
        val plaintext = aead.decrypt(iv, AAD, ciphertext)
        return try {
            JSON.decodeFromString<CredentialState>(plaintext.decodeToString()).also(::requireState)
        } catch (_: Exception) {
            error("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID")
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeState(state: CredentialState) {
        requireState(state)
        if (state.bindings.isEmpty()) {
            fileStore.delete()
            return
        }
        val plaintext = JSON.encodeToString(state).encodeToByteArray()
        val encrypted = try {
            aead.encrypt(AAD, plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size == IV_BYTES) {
            "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
        }
        fileStore.writeAtomically(JSON.encodeToString(CredentialEnvelope(
            version = VERSION,
            aad = AAD_LABEL,
            iv = Base64.getEncoder().encodeToString(encrypted.iv),
            ciphertext = Base64.getEncoder().encodeToString(encrypted.ciphertext),
        )).encodeToByteArray())
    }

    private fun requireState(state: CredentialState) {
        require(state.version == VERSION && state.bindings.size <= MAX_BINDINGS &&
            state.bindings.map(StoredBinding::key).distinct().size == state.bindings.size) {
            "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
        }
        state.bindings.forEach { stored ->
            requireBinding(PiExtensionCredentialBinding(
                stored.packageId,
                stored.packageDigest,
                stored.slot,
                stored.origin,
            ))
            require(stored.bearerToken.isNotEmpty() && stored.bearerToken.length <= MAX_SECRET_CHARS &&
                '\u0000' !in stored.bearerToken) {
                "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
            }
        }
    }

    private fun decode(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        error("EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID")
    }

    companion object {
        const val KEYSTORE_ALIAS = "momoding-pi-extension-credential-v1"
        const val FILE_NAME = "pi-extension-credentials.vault"
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val MAX_BINDINGS = 64
        private const val MAX_SECRET_CHARS = 4_096
        private const val AAD_LABEL = "momoding-pi-extension-credentials-v1"
        private val AAD = AAD_LABEL.encodeToByteArray()
        private val PROCESS_LOCK = Any()
        private val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true }

        fun create(context: Context): PiExtensionCredentialVault = PiExtensionCredentialVault(
            aead = AndroidKeystoreAead(KEYSTORE_ALIAS),
            fileStore = AndroidNoBackupVaultFileStore(
                context.applicationContext,
                FILE_NAME,
            ),
        )
    }
}

private fun requireBinding(binding: PiExtensionCredentialBinding) {
    requirePackageId(binding.packageId)
    require(Regex("^[0-9a-f]{64}$").matches(binding.packageDigest) &&
        Regex("^[a-z][a-z0-9._-]{0,63}$").matches(binding.slot) &&
        canonicalHttpsOrigin(binding.origin) == binding.origin) {
        "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
    }
}

private fun requirePackageId(packageId: String) {
    require(PiRegisterToolExtensionSandbox.PACKAGE_ID.matches(packageId) && packageId.length <= 76) {
        "EXTENSION_PACKAGE_MOBILE_CREDENTIAL_INVALID"
    }
}

@Serializable
private data class CredentialEnvelope(
    val version: Int,
    val aad: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
private data class CredentialState(
    val version: Int = 1,
    val bindings: List<StoredBinding> = emptyList(),
)

@Serializable
private data class StoredBinding(
    val packageId: String,
    val packageDigest: String,
    val slot: String,
    val origin: String,
    val bearerToken: String,
) {
    fun key(): String = "$packageId\u0000$packageDigest\u0000$slot\u0000$origin"
}
