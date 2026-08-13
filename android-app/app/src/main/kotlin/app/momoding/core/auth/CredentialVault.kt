package app.momoding.core.auth

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

interface VaultAead {
    fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext
    @Throws(AEADBadTagException::class)
    fun decrypt(iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray
    fun keyExists(): Boolean
    fun deleteKey()
}

data class AeadCiphertext(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface VaultFileStore {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
    fun delete()
}

class CredentialVault(
    private val aead: VaultAead,
    private val fileStore: VaultFileStore,
) {
    fun store(secret: VaultSecret) {
        val plaintext = VaultEnvelopeCodec.encodePlaintext(secret)
        val aad = VaultEnvelopeCodec.aadFor(secret.header)
        val encrypted = try {
            aead.encrypt(aad, plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.iv.size == VaultEnvelopeCodec.IV_BYTES) { "Vault IV must be 12 bytes" }
        fileStore.writeAtomically(
            VaultEnvelopeCodec.encodeEnvelope(secret.header, encrypted.iv, encrypted.ciphertext),
        )
    }

    fun load(): VaultSecret? {
        val bytes = fileStore.read() ?: return null
        require(aead.keyExists()) { "Vault key is missing" }
        val envelope = VaultEnvelopeCodec.decodeEnvelope(bytes)
        val plaintext = aead.decrypt(envelope.iv, envelope.aad, envelope.ciphertext)
        return try {
            VaultEnvelopeCodec.decodePlaintext(plaintext).also { secret ->
                require(VaultEnvelopeCodec.bindingMatches(envelope.header, secret.header)) {
                    "Vault header and ciphertext binding differ"
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun exists(): Boolean = fileStore.read() != null

    fun deleteFile() = fileStore.delete()

    fun deleteKey() = aead.deleteKey()
}

class AndroidKeystoreAead(
    private val alias: String = DEFAULT_ALIAS,
) : VaultAead {
    override fun encrypt(aad: ByteArray, plaintext: ByteArray): AeadCiphertext =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(aad)
            val ciphertext = doFinal(plaintext)
            require(iv.size == VaultEnvelopeCodec.IV_BYTES) { "AndroidKeyStore returned an invalid IV" }
            AeadCiphertext(iv.copyOf(), ciphertext)
        }

    override fun decrypt(iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
            doFinal(ciphertext)
        }

    override fun keyExists(): Boolean = keyStore().containsAlias(alias)

    override fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun getOrCreateKey(): SecretKey {
        keyStore().getKey(alias, null)?.let { return it as SecretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requireKey(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: error("Vault key is missing")

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "momoding-device-credential-v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}

class AndroidNoBackupVaultFileStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
    private val maxBytes: Int = VaultEnvelopeCodec.MAX_ENVELOPE_BYTES,
) : VaultFileStore {
    private val directory = context.applicationContext.noBackupFilesDir.canonicalFile
    private val target = File(directory, fileName)

    init {
        require('/' !in fileName && '\\' !in fileName && fileName !in setOf(".", "..")) {
            "Vault file name is invalid"
        }
        require(maxBytes in 1..MAX_SUPPORTED_BYTES) { "Vault file bound is invalid" }
        directory.mkdirs()
        require(directory.isDirectory) { "Vault directory is unavailable" }
        require(target.canonicalFile.parentFile == directory) { "Vault path escapes noBackupFilesDir" }
    }

    override fun read(): ByteArray? {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val stat = requireRegularFile(target)
        require(stat.st_size in 1..maxBytes.toLong()) {
            "Vault file size is invalid"
        }
        return FileInputStream(target).use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) {
                        "Vault file is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    override fun writeAtomically(bytes: ByteArray) {
        require(bytes.size in 1..maxBytes) { "Vault file size is invalid" }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) requireRegularFile(target)
        val temporary = File(directory, "${target.name}.tmp-${SecureRandom().nextLong().toULong()}")
        var descriptor: java.io.FileDescriptor? = null
        try {
            descriptor = Os.open(
                temporary.path,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                FILE_MODE,
            )
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(descriptor, bytes, offset, bytes.size - offset)
                check(written > 0) { "Vault write made no progress" }
                offset += written
            }
            Os.fsync(descriptor)
            Os.close(descriptor)
            descriptor = null
            Os.rename(temporary.path, target.path)
            fsyncDirectory()
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
            if (temporary.exists()) runCatching { Os.remove(temporary.path) }
        }
    }

    override fun delete() {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        requireRegularFile(target)
        Os.remove(target.path)
        fsyncDirectory()
    }

    private fun requireRegularFile(file: File): android.system.StructStat {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode)) { "Vault path is not a regular file" }
        return stat
    }

    private fun fsyncDirectory() {
        val descriptor = Os.open(
            directory.path,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "host-credential.vault"
        const val MAX_SUPPORTED_BYTES = 256 * 1024
        private const val FILE_MODE = 0x180 // 0600
    }
}
