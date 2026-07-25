package app.momoding.feature.share

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.text.Html
import androidx.core.content.IntentCompat
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.MAX_DRAFT_ATTACHMENTS
import app.momoding.core.attachments.SharedAttachmentInput
import java.security.MessageDigest

internal data class IncomingShare(
    val fingerprint: String,
    val text: String,
    val attachments: List<SharedAttachmentInput>,
    val errors: List<ShareInputError>,
)

internal data class ShareInputError(
    val code: ShareInputErrorCode,
    val safeMessage: String,
)

internal enum class ShareInputErrorCode {
    UNSUPPORTED_ACTION,
    UNSUPPORTED_TYPE,
    EMPTY_CONTENT,
    TEXT_TOO_LONG,
    TOO_MANY_ITEMS,
    MISSING_READ_GRANT,
    INVALID_URI,
}

internal class ShareIntentParser(
    private val resolver: ContentResolver,
) {
    fun parse(intent: Intent): IncomingShare {
        val action = intent.action
        val errors = mutableListOf<ShareInputError>()
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            errors += ShareInputError(
                ShareInputErrorCode.UNSUPPORTED_ACTION,
                "That Android action cannot be imported.",
            )
            return result(intent, "", emptyList(), errors)
        }

        val declaredMime = canonicalMime(intent.type)
        val text = extractText(intent, declaredMime, errors)
        val candidates = streamUris(intent, action)
        val boundedCandidates = candidates.take(MAX_DRAFT_ATTACHMENTS)
        val attachments = mutableListOf<SharedAttachmentInput>()

        if (candidates.size > MAX_DRAFT_ATTACHMENTS) {
            errors += ShareInputError(
                ShareInputErrorCode.TOO_MANY_ITEMS,
                "A task can include up to five shared attachments.",
            )
        }

        if (boundedCandidates.isNotEmpty() &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            errors += ShareInputError(
                ShareInputErrorCode.MISSING_READ_GRANT,
                "The sharing app did not grant temporary read access.",
            )
        } else {
            boundedCandidates.forEach { candidate ->
                val uri = candidate.uri
                if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                    errors += ShareInputError(
                        ShareInputErrorCode.INVALID_URI,
                        "A shared item used an unsafe or unsupported address.",
                    )
                    return@forEach
                }
                val resolvedMime = canonicalMime(runCatching { resolver.getType(uri) }.getOrNull())
                val effectiveMime = resolvedMime
                    ?: declaredMime?.takeUnless { it.endsWith("/*") }
                val kind = effectiveMime?.let(::kindForMime)
                if (kind == null) {
                    errors += ShareInputError(
                        ShareInputErrorCode.UNSUPPORTED_TYPE,
                        "A shared item type is not supported.",
                    )
                    return@forEach
                }
                attachments += SharedAttachmentInput(
                    sourceIndex = candidate.sourceIndex,
                    uri = uri,
                    kind = kind,
                )
            }
        }

        if (text.isEmpty() && attachments.isEmpty() && errors.isEmpty()) {
            errors += ShareInputError(
                ShareInputErrorCode.EMPTY_CONTENT,
                "The sharing app did not provide any usable content.",
            )
        }
        if (text.isEmpty() && candidates.isEmpty() && declaredMime?.startsWith("text/") != true) {
            errors += ShareInputError(
                ShareInputErrorCode.UNSUPPORTED_TYPE,
                "That shared content type is not supported.",
            )
        }
        return result(intent, text, attachments, errors)
    }

    private fun extractText(
        intent: Intent,
        declaredMime: String?,
        errors: MutableList<ShareInputError>,
    ): String {
        val acceptsCaption = declaredMime?.startsWith("text/") == true ||
            declaredMime?.let(::kindForMime) != null
        if (!acceptsCaption) {
            return ""
        }
        val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { html ->
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            }
            ?: ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        val combined = buildList {
            if (subject.isNotEmpty() && subject != shared.trim()) add(subject)
            if (shared.isNotBlank()) add(shared.trim())
        }.joinToString("\n\n")
        if (combined.length > MAX_SHARED_TEXT_UTF16_UNITS) {
            errors += ShareInputError(
                ShareInputErrorCode.TEXT_TOO_LONG,
                "Shared text is longer than the task draft limit.",
            )
            return ""
        }
        return combined
    }

    private fun streamUris(intent: Intent, action: String?): List<StreamCandidate> {
        val raw = buildList {
            if (action == Intent.ACTION_SEND) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(::add)
            } else {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ).orEmpty().forEach(::add)
            }
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
            }
        }
        val seen = LinkedHashSet<String>()
        return raw.mapIndexedNotNull { index, uri ->
            StreamCandidate(index, uri).takeIf { seen.add(uri.toString()) }
        }
    }

    private fun result(
        intent: Intent,
        text: String,
        attachments: List<SharedAttachmentInput>,
        errors: List<ShareInputError>,
    ): IncomingShare {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            digest.update(value.encodeToByteArray())
            digest.update(0)
        }
        add(intent.action.orEmpty())
        add(canonicalMime(intent.type).orEmpty())
        add(text)
        attachments.forEach { attachment ->
            add(attachment.sourceIndex.toString())
            add(attachment.kind.name)
            add(attachment.uri.toString())
        }
        errors.forEach { add(it.code.name) }
        return IncomingShare(
            fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            text = text,
            attachments = attachments,
            errors = errors.distinctBy(ShareInputError::code),
        )
    }

    private data class StreamCandidate(val sourceIndex: Int, val uri: Uri)

    private companion object {
        const val MAX_SHARED_TEXT_UTF16_UNITS = 131_072
    }
}

internal fun kindForMime(mimeType: String): AttachmentKind? = when {
    mimeType in IMAGE_MIMES -> AttachmentKind.IMAGE
    mimeType.startsWith("text/") || mimeType in STRUCTURED_TEXT_MIMES ->
        AttachmentKind.TEXT_FILE
    mimeType in VIDEO_MIMES -> AttachmentKind.VIDEO
    else -> null
}

internal fun canonicalMime(value: String?): String? = value
    ?.substringBefore(';')
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotEmpty)

internal val IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
internal val VIDEO_MIMES = setOf("video/mp4", "video/webm")
internal val STRUCTURED_TEXT_MIMES = setOf(
    "application/json",
    "application/ld+json",
    "application/xml",
    "application/javascript",
)
