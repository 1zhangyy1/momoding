package app.momoding.core.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AttachmentSourceDescriptor(
    val displayName: String,
    val declaredMimeType: String?,
    val declaredSize: Long?,
    val openStream: () -> InputStream,
)

internal data class StoredAttachmentPayload(
    val payloadFileName: String,
    val thumbnailFileName: String?,
    val byteSize: Long,
    val sha256: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
)

internal data class StoredTextPage(
    val content: String,
    val nextOffset: Long,
    val eof: Boolean,
)

internal class AttachmentPayloadException(
    val code: AttachmentImportFailureCode,
    message: String,
) : Exception(message)

internal class AttachmentPayloadStore(private val root: File) {
    init {
        check(root.exists() || root.mkdirs()) { "ATTACHMENT_ROOT_CREATE_FAILED" }
        check(root.isDirectory) { "ATTACHMENT_ROOT_INVALID" }
    }

    fun import(
        attachmentId: String,
        kind: AttachmentKind,
        source: AttachmentSourceDescriptor,
    ): StoredAttachmentPayload {
        val limit = when (kind) {
            AttachmentKind.IMAGE -> MAX_IMAGE_SOURCE_BYTES
            AttachmentKind.TEXT_FILE -> MAX_TEXT_SOURCE_BYTES
            AttachmentKind.VIDEO -> MAX_VIDEO_SOURCE_BYTES
        }
        if (source.declaredSize != null && source.declaredSize > limit) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.ITEM_TOO_LARGE,
                "The selected item is larger than the local attachment limit.",
            )
        }

        val payloadName = "payload-$attachmentId.bin"
        val payload = file(payloadName)
        val temporary = file(".$payloadName.tmp")
        temporary.delete()
        payload.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            source.openStream().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > limit) {
                            throw AttachmentPayloadException(
                                AttachmentImportFailureCode.ITEM_TOO_LARGE,
                                "The selected item is larger than the local attachment limit.",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) {
                throw AttachmentPayloadException(
                    AttachmentImportFailureCode.INVALID_CONTENT,
                    "The selected item is empty.",
                )
            }
            val canonicalMime = when (kind) {
                AttachmentKind.IMAGE -> detectImageMime(temporary)
                AttachmentKind.TEXT_FILE -> validateTextFile(temporary, source)
                AttachmentKind.VIDEO -> detectVideoMime(temporary)
            }
            if (!temporary.renameTo(payload)) {
                throw AttachmentPayloadException(
                    AttachmentImportFailureCode.STORAGE_FAILED,
                    "The selected item could not be saved on this phone.",
                )
            }
            val thumbnail = if (kind == AttachmentKind.IMAGE) {
                createThumbnail(attachmentId, payload)
            } else {
                null
            }
            return StoredAttachmentPayload(
                payloadFileName = payloadName,
                thumbnailFileName = thumbnail?.fileName,
                byteSize = total,
                sha256 = digest.digest().toHex(),
                mimeType = canonicalMime,
                width = thumbnail?.width,
                height = thumbnail?.height,
            )
        } catch (known: AttachmentPayloadException) {
            temporary.delete()
            payload.delete()
            throw known
        } catch (_: Exception) {
            temporary.delete()
            payload.delete()
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.READ_FAILED,
                "The selected item could not be read.",
            )
        }
    }

    fun readThumbnail(fileName: String): ByteArray? {
        val thumbnail = file(fileName)
        if (!thumbnail.isFile || thumbnail.length() !in 1..MAX_THUMBNAIL_BYTES) return null
        return thumbnail.readBytes()
    }

    fun runtimeImage(
        payloadFileName: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): ByteArray {
        val payload = file(payloadFileName)
        if (!payload.isFile || payload.length() != expectedBytes || expectedBytes !in 1..MAX_IMAGE_SOURCE_BYTES) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.READ_FAILED,
                "The selected image is no longer available on this phone.",
            )
        }
        val actualSha256 = payload.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().toHex()
        }
        if (actualSha256 != expectedSha256) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected image failed its local integrity check.",
            )
        }

        val normalized = decodeOriented(payload, RUNTIME_IMAGE_LONG_EDGE).bitmap
        try {
            for (quality in RUNTIME_IMAGE_JPEG_QUALITIES) {
                val output = ByteArrayOutputStream()
                if (normalized.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    val bytes = output.toByteArray()
                    if (bytes.size in 1..MAX_RUNTIME_IMAGE_BYTES) return bytes
                }
            }
        } finally {
            normalized.recycle()
        }
        throw AttachmentPayloadException(
            AttachmentImportFailureCode.ITEM_TOO_LARGE,
            "The selected image could not fit the Agent image limit.",
        )
    }

    fun runtimeTextPage(
        payloadFileName: String,
        expectedBytes: Long,
        expectedSha256: String,
        offset: Long,
        limit: Int,
    ): StoredTextPage {
        val payload = file(payloadFileName)
        if (!payload.isFile || payload.length() != expectedBytes || expectedBytes !in 1..MAX_TEXT_SOURCE_BYTES) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.READ_FAILED,
                "The selected text file is no longer available on this phone.",
            )
        }
        val bytes = payload.readBytes()
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        if (actualSha256 != expectedSha256) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected text file failed its local integrity check.",
            )
        }
        if (offset !in 0..bytes.size.toLong()) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The requested text offset is outside this attachment.",
            )
        }
        val start = offset.toInt()
        if (start < bytes.size && bytes[start].isUtf8ContinuationByte()) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The requested text offset is not a UTF-8 boundary.",
            )
        }
        if (start == bytes.size) return StoredTextPage("", offset, true)
        var end = minOf(bytes.size, start + limit)
        while (end < bytes.size && end > start && bytes[end].isUtf8ContinuationByte()) end -= 1
        if (end == start) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The requested text page is too small for the next UTF-8 character.",
            )
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val content = try {
            decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString()
        } catch (_: Exception) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected text file is not valid UTF-8.",
            )
        }
        return StoredTextPage(content, end.toLong(), end == bytes.size)
    }

    fun delete(payloadFileName: String, thumbnailFileName: String?) {
        file(payloadFileName).delete()
        thumbnailFileName?.let { file(it).delete() }
    }

    fun pruneOrphans(referencedFileNames: Set<String>): Int {
        var deleted = 0
        root.listFiles().orEmpty().forEach { candidate ->
            val shouldDelete = candidate.name.endsWith(".tmp") || candidate.name !in referencedFileNames
            if (shouldDelete && candidate.isFile && candidate.delete()) deleted += 1
        }
        return deleted
    }

    private fun detectImageMime(payload: File): String {
        val header = payload.inputStream().use { input -> input.readPrefix(16) }
        return when {
            header.startsWith(PNG_HEADER) -> "image/png"
            header.startsWith(JPEG_HEADER) -> "image/jpeg"
            header.startsWith(GIF_87_HEADER) || header.startsWith(GIF_89_HEADER) -> "image/gif"
            header.startsWith(RIFF_HEADER) && header.copyOfRange(8, 12).contentEquals(WEBP_HEADER) ->
                "image/webp"
            else -> throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected image is not a supported PNG, JPEG, WebP, or GIF.",
            )
        }
    }

    private fun validateTextFile(payload: File, source: AttachmentSourceDescriptor): String {
        val declared = source.declaredMimeType?.substringBefore(';')?.lowercase()
        val inferred = inferTextMime(source.displayName)
        val canonical = when {
            declared?.startsWith("text/") == true -> declared
            declared in ALLOWED_STRUCTURED_TEXT_MIMES -> requireNotNull(declared)
            inferred != null -> inferred
            else -> throw AttachmentPayloadException(
                AttachmentImportFailureCode.UNSUPPORTED_TYPE,
                "Only text, code, Markdown, JSON, and XML files can be staged.",
            )
        }
        val bytes = payload.readBytes()
        if (bytes.any { it == 0.toByte() }) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected file is not valid text.",
            )
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (_: Exception) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected file is not valid UTF-8 text.",
            )
        }
        return canonical
    }

    private fun detectVideoMime(payload: File): String {
        val header = payload.inputStream().use { input -> input.readPrefix(16) }
        return when {
            header.size >= 12 && header.copyOfRange(4, 8).contentEquals(FTYP_HEADER) ->
                "video/mp4"
            header.startsWith(EBML_HEADER) -> "video/webm"
            else -> throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The shared video is not a supported MP4 or WebM file.",
            )
        }
    }

    private fun createThumbnail(attachmentId: String, payload: File): ThumbnailResult {
        val thumbnailName = "thumb-$attachmentId.png"
        val thumbnail = file(thumbnailName)
        val temporary = file(".$thumbnailName.tmp")
        thumbnail.delete()
        temporary.delete()
        val decoded = decodeOriented(payload, THUMBNAIL_LONG_EDGE)
        val bitmap = decoded.bitmap
        try {
            temporary.outputStream().buffered().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw AttachmentPayloadException(
                        AttachmentImportFailureCode.STORAGE_FAILED,
                        "The image preview could not be saved.",
                    )
                }
            }
            if (temporary.length() !in 1..MAX_THUMBNAIL_BYTES || !temporary.renameTo(thumbnail)) {
                throw AttachmentPayloadException(
                    AttachmentImportFailureCode.STORAGE_FAILED,
                    "The image preview could not be saved.",
                )
            }
            return ThumbnailResult(thumbnailName, decoded.originalWidth, decoded.originalHeight)
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
    }

    private fun decodeOriented(payload: File, targetLongEdge: Int): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(payload.absolutePath, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0 || rawWidth.toLong() * rawHeight.toLong() > MAX_IMAGE_PIXELS) {
            throw AttachmentPayloadException(
                AttachmentImportFailureCode.INVALID_CONTENT,
                "The selected image dimensions are not supported.",
            )
        }
        var sampleSize = 1
        while (max(rawWidth, rawHeight) / (sampleSize * 2) >= targetLongEdge) sampleSize *= 2
        val decoded = BitmapFactory.decodeFile(
            payload.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw AttachmentPayloadException(
            AttachmentImportFailureCode.INVALID_CONTENT,
            "The selected image could not be decoded.",
        )
        val transform = runCatching {
            ExifInterface(payload).let { exif -> ImageTransform(exif.isFlipped, exif.rotationDegrees) }
        }.getOrDefault(ImageTransform(flipped = false, rotationDegrees = 0))
        val oriented = if (!transform.flipped && transform.rotationDegrees == 0) {
            decoded
        } else {
            val matrix = Matrix().apply {
                if (transform.flipped) postScale(-1f, 1f)
                if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { transformed -> if (transformed !== decoded) decoded.recycle() }
        }
        val resizeScale = targetLongEdge.toDouble() / max(oriented.width, oriented.height)
        val bitmap = if (resizeScale < 1.0) {
            oriented.scale(
                max(1, (oriented.width * resizeScale).roundToInt()),
                max(1, (oriented.height * resizeScale).roundToInt()),
            ).also { scaled -> if (scaled !== oriented) oriented.recycle() }
        } else {
            oriented
        }
        val swapsDimensions = transform.rotationDegrees == 90 || transform.rotationDegrees == 270
        return DecodedImage(
            bitmap = bitmap,
            originalWidth = if (swapsDimensions) rawHeight else rawWidth,
            originalHeight = if (swapsDimensions) rawWidth else rawHeight,
        )
    }

    private fun file(name: String): File {
        require(FILE_NAME.matches(name)) { "ATTACHMENT_FILE_NAME_INVALID" }
        return File(root, name)
    }

    private data class ThumbnailResult(val fileName: String, val width: Int, val height: Int)
    private data class ImageTransform(val flipped: Boolean, val rotationDegrees: Int)
    private data class DecodedImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
        const val MAX_IMAGE_SOURCE_BYTES = 20L * 1024L * 1024L
        const val MAX_TEXT_SOURCE_BYTES = 4L * 1024L * 1024L
        const val MAX_VIDEO_SOURCE_BYTES = 40L * 1024L * 1024L
        const val MAX_THUMBNAIL_BYTES = 2L * 1024L * 1024L
        const val MAX_IMAGE_PIXELS = 100_000_000L
        const val THUMBNAIL_LONG_EDGE = 256
        const val RUNTIME_IMAGE_LONG_EDGE = 1280
        const val MAX_RUNTIME_IMAGE_BYTES = 1_048_576
        val RUNTIME_IMAGE_JPEG_QUALITIES = listOf(82, 72, 62, 52, 42)

        val FILE_NAME = Regex("^[a-z0-9.-]{1,128}$")
        val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val GIF_87_HEADER = "GIF87a".encodeToByteArray()
        val GIF_89_HEADER = "GIF89a".encodeToByteArray()
        val RIFF_HEADER = "RIFF".encodeToByteArray()
        val WEBP_HEADER = "WEBP".encodeToByteArray()
        val FTYP_HEADER = "ftyp".encodeToByteArray()
        val EBML_HEADER = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val ALLOWED_STRUCTURED_TEXT_MIMES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/javascript",
        )
    }
}

private fun Byte.isUtf8ContinuationByte(): Boolean = (toInt() and 0xC0) == 0x80

private fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val buffer = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val read = read(buffer, offset, maxBytes - offset)
        if (read < 0) break
        if (read == 0) continue
        offset += read
    }
    return buffer.copyOf(offset)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private fun inferTextMime(displayName: String): String? = when (
    displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
) {
    "txt", "md", "markdown", "kt", "kts", "java", "js", "ts", "tsx", "jsx", "py",
    "rb", "go", "rs", "swift", "c", "cc", "cpp", "h", "hpp", "css", "html", "htm",
    "sh", "zsh", "bash", "yaml", "yml", "toml", "properties", "gradle" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "application/xml"
    else -> null
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
