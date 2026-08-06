package app.momoding.core.runtime.local

import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.attachments.TaskAttachmentRecord
import app.momoding.core.provider.OpenRouterImageGateway
import app.momoding.core.provider.OpenRouterImageGenerationRequest
import app.momoding.core.provider.OpenRouterImageModelSummary
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderSelectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

interface PhoneLocalImageGenerationToolHandler {
    fun handles(toolName: String): Boolean

    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult

    suspend fun discardUndelivered(taskId: String, result: PiNativeAndroidToolResult)
}

interface GeneratedImageArtifactStore {
    suspend fun importGeneratedImage(
        taskId: String,
        toolCallId: String,
        displayName: String,
        bytes: ByteArray,
        declaredMimeType: String,
    ): TaskAttachmentRecord

    suspend fun discardGeneratedImage(taskId: String, attachmentId: String): Boolean
}

/** Executes one bounded image request, persists it, then gives Pi durable reference metadata. */
class PhoneLocalImageGenerationToolExecutor internal constructor(
    private val loadCredential: suspend () -> ProviderCredential?,
    private val selectionStore: ProviderSelectionStore,
    private val gateway: OpenRouterImageGateway,
    private val attachments: GeneratedImageArtifactStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PhoneLocalImageGenerationToolHandler {
    constructor(
        credentialVault: ProviderCredentialVault,
        selectionStore: ProviderSelectionStore,
        gateway: OpenRouterImageGateway,
        attachments: AttachmentRepository,
    ) : this(
        loadCredential = credentialVault::load,
        selectionStore = selectionStore,
        gateway = gateway,
        attachments = attachments,
    )

    private val modelCacheMutex = Mutex()
    private var cachedModels: CachedModels? = null

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (request.kind != NATIVE_KIND || !handles(request.toolName)) {
            return failure("IMAGE_TOOL_NOT_ALLOWED", "Image generation is not available.")
        }
        val arguments = request.arguments
        if (!arguments.keys.all(ALLOWED_ARGUMENTS::contains) || "prompt" !in arguments) {
            return failure("IMAGE_ARGUMENTS_INVALID", "The image request is invalid.")
        }
        val prompt = arguments.string("prompt")?.trim()
            ?.takeIf { it.length in 1..MAX_PROMPT_CHARS }
            ?: return failure("IMAGE_ARGUMENTS_INVALID", "The image prompt is invalid.")
        val aspectRatio = arguments.optionalString("aspect_ratio")
            ?: if ("aspect_ratio" in arguments) {
                return failure("IMAGE_ARGUMENTS_INVALID", "The image aspect ratio is invalid.")
            } else null
        val quality = arguments.optionalString("quality")
            ?: if ("quality" in arguments) {
                return failure("IMAGE_ARGUMENTS_INVALID", "The image quality is invalid.")
            } else null
        if (aspectRatio != null && aspectRatio !in ALLOWED_ASPECT_RATIOS) {
            return failure("IMAGE_ARGUMENTS_INVALID", "That image aspect ratio is not available.")
        }
        if (quality != null && quality !in ALLOWED_QUALITIES) {
            return failure("IMAGE_ARGUMENTS_INVALID", "That image quality is not available.")
        }

        var importedAttachmentId: String? = null
        return try {
            val credential = loadCredential()
                ?: return failure("PROVIDER_NOT_CONFIGURED", "Configure OpenRouter before generating images.")
            val selection = selectionStore.load(credential.profile)
            val modelId = selection.imageModelId
            if (!selection.imageGenerationEnabled || modelId == null) {
                return failure(
                    "IMAGE_GENERATION_DISABLED",
                    "Choose an image model and enable image generation in Provider settings.",
                )
            }
            val model = imageModels(credential).singleOrNull { it.id == modelId }
                ?: return failure(
                    "IMAGE_MODEL_UNAVAILABLE",
                    "The selected OpenRouter image model is no longer available.",
                )
            val generated = gateway.generate(
                credential,
                OpenRouterImageGenerationRequest(
                    model = model,
                    prompt = prompt,
                    aspectRatio = aspectRatio,
                    quality = quality,
                ),
            )
            val artifact = attachments.importGeneratedImage(
                taskId = taskId,
                toolCallId = request.toolCallId,
                displayName = "Momoding image ${nowMillis()}.${generated.mimeType.fileExtension()}",
                bytes = generated.bytes,
                declaredMimeType = generated.mimeType,
            )
            importedAttachmentId = artifact.attachmentId
            val metadata = buildJsonObject {
                put("ok", true)
                put("kind", "generated_image_artifact")
                put("persistent", true)
                put("attachmentId", artifact.attachmentId)
                put("displayName", artifact.displayName)
                put("model", model.id)
                put("mimeType", artifact.mimeType)
                put("byteSize", artifact.byteSize)
                put("sha256", artifact.payloadSha256)
                artifact.width?.let { put("width", it) }
                artifact.height?.let { put("height", it) }
                put("promptSummary", prompt.take(MAX_PROMPT_SUMMARY_CHARS))
                generated.usage?.let { usage ->
                    put("usage", buildJsonObject {
                        usage.promptTokens?.let { put("promptTokens", it) }
                        usage.completionTokens?.let { put("completionTokens", it) }
                        usage.totalTokens?.let { put("totalTokens", it) }
                        usage.costUsd?.let { put("costUsd", it) }
                    })
                }
            }
            PiNativeAndroidToolResult(
                contentPayload = metadata,
                details = metadata,
                content = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", metadata.toString())
                    })
                },
            )
        } catch (cancelled: CancellationException) {
            discardImported(taskId, importedAttachmentId)
            throw cancelled
        } catch (error: OpenRouterRequestException) {
            discardImported(taskId, importedAttachmentId)
            failure(
                code = error.errorType?.uppercase() ?: "IMAGE_PROVIDER_FAILED",
                message = error.message ?: "OpenRouter image generation failed.",
            )
        } catch (_: IllegalArgumentException) {
            discardImported(taskId, importedAttachmentId)
            failure("IMAGE_REQUEST_UNSUPPORTED", "The selected image model does not support that request.")
        } catch (_: Throwable) {
            discardImported(taskId, importedAttachmentId)
            failure("IMAGE_GENERATION_FAILED", "The image could not be generated and saved on this phone.")
        }
    }

    override suspend fun discardUndelivered(
        taskId: String,
        result: PiNativeAndroidToolResult,
    ) = withContext(NonCancellable) {
        val attachmentId = result.details["attachmentId"]?.let { value ->
            (value as? JsonPrimitive)?.contentOrNull
        } ?: return@withContext
        if (
            result.details["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull } ==
            "generated_image_artifact"
        ) {
            attachments.discardGeneratedImage(taskId, attachmentId)
        }
    }

    private suspend fun discardImported(taskId: String, attachmentId: String?) {
        if (attachmentId == null) return
        withContext(NonCancellable) {
            runCatching { attachments.discardGeneratedImage(taskId, attachmentId) }
        }
    }

    private suspend fun imageModels(credential: ProviderCredential): List<OpenRouterImageModelSummary> =
        modelCacheMutex.withLock {
            val cached = cachedModels
            if (
                cached != null && cached.accountId == credential.profile.id &&
                nowMillis() - cached.loadedAtMillis in 0..MODEL_CACHE_MILLIS
            ) {
                return@withLock cached.models
            }
            gateway.listModels(credential.apiKey).also { models ->
                cachedModels = CachedModels(
                    accountId = credential.profile.id,
                    loadedAtMillis = nowMillis(),
                    models = models,
                )
            }
        }

    private fun failure(code: String, message: String): PiNativeAndroidToolResult {
        val payload = buildJsonObject {
            put("ok", false)
            put("errorCode", code.take(80))
            put("errorMessage", message.take(512))
        }
        return PiNativeAndroidToolResult(payload, isError = true)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.optionalString(key: String): String? =
        if (key !in this) null else string(key)

    private fun String.fileExtension(): String = when (this) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> throw IllegalArgumentException("Unsupported generated image MIME")
    }

    private data class CachedModels(
        val accountId: String,
        val loadedAtMillis: Long,
        val models: List<OpenRouterImageModelSummary>,
    )

    companion object {
        const val TOOL_NAME = "image_generate"
        const val NATIVE_KIND = "android_image_generation_tool"
        private const val MAX_PROMPT_CHARS = 4_096
        private const val MAX_PROMPT_SUMMARY_CHARS = 160
        private const val MODEL_CACHE_MILLIS = 10L * 60L * 1_000L
        private val ALLOWED_ARGUMENTS = setOf("prompt", "aspect_ratio", "quality")
        private val ALLOWED_ASPECT_RATIOS = setOf("1:1", "16:9", "9:16", "4:3", "3:4")
        private val ALLOWED_QUALITIES = setOf("auto", "low", "medium", "high")
    }
}
