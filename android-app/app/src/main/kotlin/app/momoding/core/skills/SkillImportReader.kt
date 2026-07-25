package app.momoding.core.skills

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class SkillImportDocument(
    val content: String,
    val byteCount: Int,
    val documentSha256: String,
)

class SkillImportException(
    val code: String,
) : IllegalArgumentException(code)

internal data class SkillImportMetadata(
    val displayName: String,
    val sizeBytes: Long?,
)

internal interface SkillImportAccess {
    fun metadata(uri: Uri): SkillImportMetadata
    fun open(uri: Uri): InputStream
}

class SkillImportReader internal constructor(
    private val access: SkillImportAccess,
) {
    constructor(contentResolver: ContentResolver) : this(
        ContentResolverSkillImportAccess(contentResolver),
    )

    fun read(uri: Uri): SkillImportDocument {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            throw SkillImportException("SKILL_DOCUMENT_URI_INVALID")
        }
        try {
            val metadata = access.metadata(uri)
            if (metadata.displayName != "SKILL.md") {
                throw SkillImportException("SKILL_DOCUMENT_NAME_INVALID")
            }
            if (metadata.sizeBytes != null && metadata.sizeBytes !in 0..MAX_SKILL_DOCUMENT_BYTES.toLong()) {
                throw SkillImportException("SKILL_DOCUMENT_TOO_LARGE")
            }
            val bytes = access.open(uri).use(::readBoundedSkillDocument)
            if (bytes.isEmpty()) throw SkillImportException("SKILL_DOCUMENT_EMPTY")
            val content = decodeStrictUtf8(bytes)
            if (content.isEmpty() || content.length > MAX_SKILL_DOCUMENT_UTF16_UNITS || '\u0000' in content) {
                throw SkillImportException("SKILL_DOCUMENT_INVALID")
            }
            return SkillImportDocument(
                content = content,
                byteCount = bytes.size,
                documentSha256 = bytes.sha256(),
            )
        } catch (error: SkillImportException) {
            throw error
        } catch (_: Exception) {
            throw SkillImportException("SKILL_DOCUMENT_UNREADABLE")
        }
    }
}

private class ContentResolverSkillImportAccess(
    private val resolver: ContentResolver,
) : SkillImportAccess {
    override fun metadata(uri: Uri): SkillImportMetadata {
        val cursor = requireNotNull(
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            ),
        ) { "SKILL_DOCUMENT_METADATA_UNAVAILABLE" }
        return cursor.use(::readMetadata)
    }

    override fun open(uri: Uri): InputStream = requireNotNull(resolver.openInputStream(uri)) {
        "SKILL_DOCUMENT_STREAM_UNAVAILABLE"
    }

    private fun readMetadata(cursor: Cursor): SkillImportMetadata {
        check(cursor.moveToFirst()) { "SKILL_DOCUMENT_METADATA_MISSING" }
        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        check(displayNameIndex >= 0 && !cursor.isNull(displayNameIndex)) {
            "SKILL_DOCUMENT_NAME_MISSING"
        }
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
        return SkillImportMetadata(cursor.getString(displayNameIndex), size)
    }
}

internal fun readBoundedSkillDocument(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
            val single = input.read()
            if (single < 0) break
            if (output.size() + 1 > MAX_SKILL_DOCUMENT_BYTES) {
                throw SkillImportException("SKILL_DOCUMENT_TOO_LARGE")
            }
            output.write(single)
            continue
        }
        if (output.size() + read > MAX_SKILL_DOCUMENT_BYTES) {
            throw SkillImportException("SKILL_DOCUMENT_TOO_LARGE")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun decodeStrictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw SkillImportException("SKILL_DOCUMENT_UTF8_INVALID")
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
