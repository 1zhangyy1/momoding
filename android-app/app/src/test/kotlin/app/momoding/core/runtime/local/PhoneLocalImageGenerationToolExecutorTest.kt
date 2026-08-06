package app.momoding.core.runtime.local

import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.AttachmentSource
import app.momoding.core.attachments.AttachmentState
import app.momoding.core.attachments.TaskAttachmentRecord
import app.momoding.core.provider.OpenRouterGeneratedImage
import app.momoding.core.provider.OpenRouterImageGateway
import app.momoding.core.provider.OpenRouterImageGenerationRequest
import app.momoding.core.provider.OpenRouterImageModelSummary
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.core.provider.ProviderSelection
import app.momoding.core.provider.ProviderSelectionStore
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalImageGenerationToolExecutorTest {
    @Test
    fun `enabled tool generates persists and returns reference metadata without image bytes`() = runTest {
        val gateway = FakeGateway()
        val artifacts = FakeArtifacts()
        val executor = PhoneLocalImageGenerationToolExecutor(
            loadCredential = { credential() },
            selectionStore = enabledSelectionStore(),
            gateway = gateway,
            attachments = artifacts,
            nowMillis = { 1234L },
        )

        val first = executor.execute(TASK_ID, request("call-image-1"))
        val second = executor.execute(TASK_ID, request("call-image-2"))

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(1, gateway.listCount)
        assertEquals(2, gateway.generateCount)
        assertEquals("openai/gpt-image-2", first.details["model"]?.jsonPrimitive?.content)
        assertEquals(true, first.details["persistent"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("Momoding image 1234.jpg", artifacts.importedNames.first())
        val content = requireNotNull(first.content)
        assertEquals(listOf("text"), content.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertTrue(content.single().jsonObject.getValue("text").jsonPrimitive.content.contains(
            first.details.getValue("attachmentId").jsonPrimitive.content,
        ))
        assertFalse(first.toString().contains(API_KEY))

        executor.discardUndelivered(TASK_ID, first)
        assertEquals(listOf(first.details.getValue("attachmentId").jsonPrimitive.content), artifacts.discarded)
    }

    @Test
    fun `disabled or invalid requests fail before provider work`() = runTest {
        val gateway = FakeGateway()
        val artifacts = FakeArtifacts()
        val disabled = PhoneLocalImageGenerationToolExecutor(
            loadCredential = { credential() },
            selectionStore = ProviderSelectionStore(
                readPayload = { null },
                writePayload = {},
                deletePayload = {},
            ),
            gateway = gateway,
            attachments = artifacts,
        )

        val disabledResult = disabled.execute(TASK_ID, request("call-disabled"))
        val invalidResult = disabled.execute(
            TASK_ID,
            request("call-invalid", prompt = " "),
        )

        assertTrue(disabledResult.isError)
        assertEquals(
            "IMAGE_GENERATION_DISABLED",
            disabledResult.details["errorCode"]?.jsonPrimitive?.content,
        )
        assertTrue(invalidResult.isError)
        assertEquals(
            "IMAGE_ARGUMENTS_INVALID",
            invalidResult.details["errorCode"]?.jsonPrimitive?.content,
        )
        assertEquals(0, gateway.listCount)
        assertEquals(0, gateway.generateCount)
        assertTrue(artifacts.importedNames.isEmpty())
    }

    private fun request(toolCallId: String, prompt: String = "A green robot") = PiNativeToolRequest(
        id = "native-$toolCallId",
        kind = PhoneLocalImageGenerationToolExecutor.NATIVE_KIND,
        toolCallId = toolCallId,
        toolName = PhoneLocalImageGenerationToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("prompt", prompt)
            put("aspect_ratio", "1:1")
            put("quality", "high")
        },
    )

    private fun enabledSelectionStore(): ProviderSelectionStore {
        var payload: String? = null
        return ProviderSelectionStore(
            readPayload = { payload },
            writePayload = { payload = it },
            deletePayload = { payload = null },
        ).also { store ->
            store.store(
                ProviderSelection(
                    accountId = PROFILE_ID,
                    providerKind = ProviderKind.OPENROUTER,
                    chatModelId = CHAT_MODEL_ID,
                    imageModelId = IMAGE_MODEL_ID,
                    webSearchEnabled = true,
                    imageGenerationEnabled = true,
                ),
            )
        }
    }

    private fun credential() = ProviderCredential(
        profile = ProviderProfile(
            id = PROFILE_ID,
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = CHAT_MODEL_ID,
            displayName = "OpenRouter",
        ),
        apiKey = API_KEY,
    )

    private class FakeGateway : OpenRouterImageGateway {
        var listCount = 0
        var generateCount = 0

        override suspend fun listModels(apiKey: String): List<OpenRouterImageModelSummary> {
            assertEquals(API_KEY, apiKey)
            listCount += 1
            return listOf(
                OpenRouterImageModelSummary(
                    id = IMAGE_MODEL_ID,
                    name = "GPT Image 2",
                    inputModalities = listOf("text"),
                    outputModalities = listOf("image"),
                    supportedParameters = emptyMap(),
                    supportsStreaming = false,
                ),
            )
        }

        override suspend fun generate(
            credential: ProviderCredential,
            request: OpenRouterImageGenerationRequest,
        ): OpenRouterGeneratedImage {
            assertEquals(API_KEY, credential.apiKey)
            assertEquals(IMAGE_MODEL_ID, request.model.id)
            assertEquals("1:1", request.aspectRatio)
            assertEquals("high", request.quality)
            generateCount += 1
            return OpenRouterGeneratedImage(
                bytes = IMAGE_BYTES,
                mimeType = "image/jpeg",
                createdAtSeconds = 123L,
                usage = null,
            )
        }
    }

    private class FakeArtifacts : GeneratedImageArtifactStore {
        val importedNames = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        private val records = linkedMapOf<String, TaskAttachmentRecord>()

        override suspend fun importGeneratedImage(
            taskId: String,
            toolCallId: String,
            displayName: String,
            bytes: ByteArray,
            declaredMimeType: String,
        ): TaskAttachmentRecord {
            importedNames += displayName
            val attachmentId = if (toolCallId.endsWith("1")) {
                "11111111-1111-4111-8111-111111111111"
            } else {
                "22222222-2222-4222-8222-222222222222"
            }
            return TaskAttachmentRecord(
                attachmentId = attachmentId,
                taskId = taskId,
                messageLocalId = toolCallId,
                ordinal = 0,
                kind = AttachmentKind.IMAGE,
                state = AttachmentState.SENT,
                source = AttachmentSource.GENERATED_IMAGE,
                displayName = displayName,
                mimeType = declaredMimeType,
                byteSize = bytes.size.toLong(),
                payloadSha256 = bytes.sha256(),
                hasThumbnail = true,
                width = 1,
                height = 1,
                createdAtMillis = 1234L,
            ).also { records[attachmentId] = it }
        }

        override suspend fun discardGeneratedImage(taskId: String, attachmentId: String): Boolean {
            discarded += attachmentId
            return records.remove(attachmentId)?.taskId == taskId
        }

        private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TASK_ID = "task-image"
        const val PROFILE_ID = "11111111-1111-4111-8111-111111111111"
        const val CHAT_MODEL_ID = "deepseek/deepseek-v4-pro"
        const val IMAGE_MODEL_ID = "openai/gpt-image-2"
        const val API_KEY = "test-key-that-must-not-enter-tool-results"
        val IMAGE_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
