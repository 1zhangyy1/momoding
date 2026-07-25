package app.momoding.core.runtime.local

import app.momoding.core.attachments.AttachmentReadException
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.attachments.MAX_TEXT_READ_BYTES
import app.momoding.core.attachments.MIN_TEXT_READ_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface PhoneLocalAttachmentToolHandler {
    fun handles(toolName: String): Boolean
    suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject
}

/** Reads only an immutable text attachment already bound to the current Pi task message. */
class PhoneLocalAttachmentToolExecutor(
    private val attachments: AttachmentRepository,
) : PhoneLocalAttachmentToolHandler {
    override fun handles(toolName: String): Boolean = toolName == ATTACHMENT_READ_TOOL

    override suspend fun execute(taskId: String, request: PiNativeToolRequest): JsonObject {
        if (request.kind != ATTACHMENT_NATIVE_KIND || !handles(request.toolName)) {
            return failure("ATTACHMENT_TOOL_NOT_ALLOWED", "This attachment tool is not available.")
        }
        val arguments = request.arguments
        if (arguments.keys != setOf("attachmentId", "offset", "limit")) {
            return failure("ATTACHMENT_ARGUMENTS_INVALID", "The attachment read request is invalid.")
        }
        val attachmentId = arguments.string("attachmentId")
            ?: return failure("ATTACHMENT_ARGUMENTS_INVALID", "The attachment read request is invalid.")
        val offset = arguments.integerLong("offset")
            ?: return failure("ATTACHMENT_ARGUMENTS_INVALID", "The attachment read offset is invalid.")
        val limit = arguments.integerInt("limit")
            ?: return failure("ATTACHMENT_ARGUMENTS_INVALID", "The attachment read limit is invalid.")
        if (offset < 0 || limit !in MIN_TEXT_READ_BYTES..MAX_TEXT_READ_BYTES) {
            return failure("ATTACHMENT_ARGUMENTS_INVALID", "The attachment read range is invalid.")
        }
        return try {
            val page = attachments.readTaskTextAttachment(taskId, attachmentId, offset, limit)
            buildJsonObject {
                put("ok", true)
                put("attachmentId", page.attachmentId)
                put("displayName", page.displayName)
                put("mimeType", page.mimeType)
                put("offset", page.offset)
                put("nextOffset", page.nextOffset)
                put("totalBytes", page.totalBytes)
                put("eof", page.eof)
                put("content", page.content)
            }
        } catch (error: AttachmentReadException) {
            failure(error.code, error.message ?: "The text attachment could not be read.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure("ATTACHMENT_READ_FAILED", "The text attachment could not be read on this phone.")
        }
    }

    private fun failure(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("errorCode", code)
        put("errorMessage", message)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.integerLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.integerInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private companion object {
        const val ATTACHMENT_NATIVE_KIND = "android_attachment_tool"
        const val ATTACHMENT_READ_TOOL = "attachment_read"
    }
}
