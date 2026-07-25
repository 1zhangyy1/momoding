package app.momoding.core.runtime.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.PhoneLocalChildAgentRepository
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.data.TaskEntity
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.core.transport.AttentionUserDecision
import app.momoding.core.skills.EnabledSkillResourceSet
import app.momoding.core.skills.PhoneLocalSkillResource
import app.momoding.core.skills.sha256Utf8
import app.momoding.core.skills.skillResourceSetDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLocalPiOpenRouterRuntimeInstrumentedTest {
    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun imageCapabilityGateUsesCredentialBoundToActualPiSessionAndConstruction() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val credentialA = testCredential().copy(
            profile = testCredential().profile.copy(
                id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                modelId = "fixture/model-a",
                displayName = "Fixture model A",
            ),
            apiKey = "$API_KEY-a",
        )
        val credentialB = testCredential().copy(
            profile = testCredential().profile.copy(
                id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                modelId = "fixture/model-b",
                displayName = "Fixture model B",
            ),
            apiKey = "$API_KEY-b",
        )
        vault.deleteFile()
        vault.deleteKey()
        vault.store(credentialA)
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val image = PiRuntimeImageInput(
            attachmentId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            mimeType = "image/jpeg",
            data = "/9j/2Q==",
        )
        try {
            server.enqueue(textResponse("model-a-session", "Session A settled."))
            runtime.startTaskSession("task-model-a", "Open session A.")
            assertEquals(
                "Bearer ${credentialA.apiKey}",
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"),
            )

            vault.store(credentialB)
            server.enqueue(imageCapabilityModelsResponse())
            assertEquals(credentialA.profile.modelId, runtime.requireImageInputCapability())
            assertEquals(
                "Bearer ${credentialA.apiKey}",
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"),
            )
            assertTrue(runtime.closeTaskSession("task-model-a"))

            server.enqueue(textResponse("model-b-session", "Session B settled."))
            runtime.startTaskSession("task-model-b", "Open session B.")
            assertEquals(
                "Bearer ${credentialB.apiKey}",
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"),
            )

            vault.store(credentialA)
            server.enqueue(imageCapabilityModelsResponse())
            val modelBFailure = runCatching {
                runtime.requireImageInputCapability()
            }.exceptionOrNull()
            assertNotNull(modelBFailure)
            assertTrue(modelBFailure?.message.orEmpty().contains("PI_MOBILE_IMAGE_MODEL_UNSUPPORTED"))
            assertEquals(
                "Bearer ${credentialB.apiKey}",
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("Authorization"),
            )
            assertTrue(runtime.closeTaskSession("task-model-b"))

            assertEquals(credentialA.profile.modelId, runtime.requireImageInputCapability())
            vault.store(credentialB)
            server.enqueue(imageCapabilityModelsResponse())
            val newTaskFailure = runCatching {
                runtime.startTaskSession(
                    taskId = "task-model-switch-before-start",
                    prompt = "Inspect this image.",
                    images = listOf(image),
                )
            }.exceptionOrNull()
            assertNotNull(newTaskFailure)
            assertTrue(newTaskFailure?.message.orEmpty().contains("PI_MOBILE_IMAGE_MODEL_UNSUPPORTED"))
            val blockedStartRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(blockedStartRequest.path.orEmpty().contains("/models"))
            assertEquals("Bearer ${credentialB.apiKey}", blockedStartRequest.getHeader("Authorization"))

            vault.store(credentialA)
            server.enqueue(textResponse("image-session-a", "Image session A settled."))
            runtime.startTaskSession(
                taskId = "task-image-session-a",
                prompt = "Inspect this image.",
                images = listOf(image),
            )
            val imageProviderRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Bearer ${credentialA.apiKey}", imageProviderRequest.getHeader("Authorization"))
            assertTrue(imageProviderRequest.body.readUtf8().contains("data:image/jpeg;base64,/9j/2Q=="))
            val imageSnapshot = runtime.taskSessionSnapshot("task-image-session-a")
            assertTrue(runtime.closeTaskSession("task-image-session-a"))

            assertEquals(credentialA.profile.modelId, runtime.requireImageInputCapability())
            vault.store(credentialB)
            server.enqueue(imageCapabilityModelsResponse())
            val restoreFailure = runCatching {
                runtime.restoreTaskSession(
                    taskId = "task-image-session-a",
                    sessionId = "task-image-session-a",
                    snapshot = imageSnapshot,
                    images = listOf(image),
                )
            }.exceptionOrNull()
            assertNotNull(restoreFailure)
            assertTrue(restoreFailure?.message.orEmpty().contains("PI_MOBILE_IMAGE_MODEL_UNSUPPORTED"))
            val blockedRestoreRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(blockedRestoreRequest.path.orEmpty().contains("/models"))
            assertEquals("Bearer ${credentialB.apiKey}", blockedRestoreRequest.getHeader("Authorization"))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun nativeImageTaskUsesPiMultimodalMessageAndReferenceOnlyRestore() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val taskId = "task-attachment-image-runtime"
        val sessionId = "session-attachment-image-runtime"
        val firstImage = PiRuntimeImageInput(
            attachmentId = "11111111-1111-4111-8111-111111111111",
            mimeType = "image/jpeg",
            data = "/9j/2Q==",
        )
        try {
            server.enqueue(defaultImageCapabilityModelsResponse())
            server.enqueue(textResponse("image-first", "I can inspect the image."))
            val first = runtime.startTaskSession(
                taskId = taskId,
                prompt = "",
                sessionId = sessionId,
                images = listOf(firstImage),
            )
            assertEquals("I can inspect the image.", first.finalText)
            val modelsRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(modelsRequest.path.orEmpty().contains("/models"))
            val firstRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val firstBody = firstRequest.body.readUtf8()
            assertTrue(firstBody.contains("data:image/jpeg;base64,/9j/2Q=="))

            val snapshot = runtime.taskSessionSnapshot(taskId)
            val persisted = snapshot.entries.toString()
            assertFalse(persisted.contains(firstImage.data))
            assertTrue(persisted.contains("attachment:${firstImage.attachmentId}"))
            assertTrue(runtime.closeTaskSession(taskId))
            val beforeRestore = server.requestCount
            val restored = runtime.restoreTaskSession(
                taskId = taskId,
                sessionId = sessionId,
                snapshot = snapshot,
                images = listOf(firstImage),
            )
            assertTrue(restored.terminal)
            assertEquals(0, restored.providerRequestsIssued)
            assertEquals(beforeRestore, server.requestCount)

            val secondImage = PiRuntimeImageInput(
                attachmentId = "22222222-2222-4222-8222-222222222222",
                mimeType = "image/png",
                data = "iVBORw0KGgo=",
            )
            server.enqueue(textResponse("image-second", "The two images differ."))
            runtime.continueTaskPrompt(
                taskId = taskId,
                prompt = "Compare with this one.",
                images = listOf(secondImage),
            )
            val secondBody = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
            assertTrue(secondBody.contains("data:image/jpeg;base64,/9j/2Q=="))
            assertTrue(secondBody.contains("data:image/png;base64,iVBORw0KGgo="))
            assertFalse(runtime.taskSessionSnapshot(taskId).entries.toString().contains(secondImage.data))
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun settledTaskSyncsAndInvokesRealPiSkillThenRestoresWithoutProviderReplay() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val taskId = "task-skill-runtime"
        val sessionId = "session-skill-runtime"
        val resources = skillResources()
        try {
            server.enqueue(textResponse("skill-base", "Base turn settled."))
            val base = runtime.startTaskSession(
                taskId = taskId,
                prompt = "Start without Skills.",
                sessionId = sessionId,
            )
            assertEquals(1, server.requestCount)
            assertTrue(base.skillNames.isEmpty())
            requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

            val synced = runtime.syncTaskSkillResources(taskId, resources)
            assertEquals(1, server.requestCount)
            assertEquals(1, synced.resourceUpdateCount)
            assertEquals(listOf("mobile-review"), synced.skillNames)

            server.enqueue(textResponse("skill-turn", "PASS — reviewed."))
            val invoked = runtime.continueTaskSkill(
                taskId = taskId,
                skillName = "mobile-review",
                additionalInstructions = "Check only the current diff.",
            )
            val skillRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val skillBody = skillRequest.body.readUtf8()
            assertTrue(skillBody.contains("mobile-review"))
            assertTrue(skillBody.contains("Return PASS or FAIL with one reason."))
            assertTrue(skillBody.contains("Check only the current diff."))
            assertEquals("PASS — reviewed.", invoked.finalText)
            assertEquals(2, invoked.turnCount)

            val snapshot = runtime.taskSessionSnapshot(taskId)
            assertTrue(runtime.closeTaskSession(taskId))
            val beforeRestore = server.requestCount
            val restored = runtime.restoreTaskSession(
                taskId = taskId,
                sessionId = sessionId,
                snapshot = snapshot,
                skillResources = resources,
            )
            assertEquals(beforeRestore, server.requestCount)
            assertEquals(listOf("mobile-review"), restored.skillNames)
            assertEquals(0, restored.providerRequestsIssued)
            val identical = runtime.syncTaskSkillResources(taskId, resources)
            assertEquals(0, identical.resourceUpdateCount)
            assertEquals(beforeRestore, server.requestCount)
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun keystoreToNativeSseToRealPiCoversTextHttpErrorAndStop() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-openrouter-instrumentation")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(
                endpoint = server.url("/api/v1"),
                baseClient = OkHttpClient(),
            ),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            server.enqueue(
                sseResponse(
                    """
                    data: {"id":"gen-real-bridge","model":"$MODEL_ID","choices":[{"delta":{"content":"Hello from the complete phone-local path"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

                    data: [DONE]

                    """.trimIndent(),
                ).addHeader("X-Generation-Id", "gen-real-bridge"),
            )
            val text = runtime.runPrompt("Reply through the phone-local Pi path.")
            assertTrue(text.toString(), text.expectationMet)
            assertEquals("prompt", text.kind)
            assertEquals("Hello from the complete phone-local path", text.finalText)
            assertEquals(1, text.providerRequestsCompleted)
            val textRequest = server.takeRequest()
            assertEquals("Bearer $API_KEY", textRequest.getHeader("Authorization"))
            assertFalse(textRequest.body.readUtf8().contains(API_KEY))

            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"error":{"code":401,"message":"remote secret detail"}}""",
                    ),
            )
            val authentication = runtime.runPrompt("Exercise the safe HTTP error path.")
            assertTrue(authentication.terminal)
            assertEquals(1, authentication.providerRequestsFailed)
            assertTrue(authentication.hasSettled)
            assertEquals("OpenRouter API key is invalid", authentication.providerError)
            assertFalse(authentication.toString().contains("remote secret detail"))
            assertFalse(authentication.toString().contains(API_KEY))
            assertFalse(server.takeRequest().body.readUtf8().contains(API_KEY))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n")
                    .setBodyDelay(60, TimeUnit.SECONDS),
            )
            val stoppedResult = async(Dispatchers.Default) {
                runtime.runPrompt("Keep streaming until Android Stop cancels the request.")
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(runtime.stop())
            val stopped = withTimeout(5_000) { stoppedResult.await() }
            assertTrue(stopped.stopCompleted)
            assertTrue(stopped.hasAbort)
            assertEquals(1, stopped.providerCancellationsIssued)
            assertEquals(0, stopped.lateProviderRequestsAfterStop)
            assertEquals(0, stopped.lateToolStartsAfterStop)
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun taskQuestionUsesNativeAttentionThenResumesTheSamePiTurnExactlyOnce() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(ledger)
        val taskId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val streamId = UUID.randomUUID().toString()
        val initialPrompt = "Ask me which implementation approach to use."
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Choose implementation",
            piSessionId = sessionId,
            streamId = streamId,
            initialPrompt = initialPrompt,
        )
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-attention-instrumentation")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(
                endpoint = server.url("/api/v1"),
                baseClient = OkHttpClient(),
            ),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            attentionBridge = bridge,
        )
        try {
            server.enqueue(questionToolResponse())
            server.enqueue(textResponse("gen-after-question", "Using the balanced approach."))

            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(taskId, initialPrompt, sessionId)
            }
            val firstRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val firstBody = firstRequest.body.readUtf8()
            assertTrue(firstBody.contains("request_user_question"))
            assertTrue(firstBody.contains("request_user_confirmation"))

            val attention = withTimeout(10_000) {
                while (ledger.unterminatedRecords().none { it.operation.taskId == taskId }) {
                    delay(5)
                }
                ledger.unterminatedRecords().single { it.operation.taskId == taskId }
            }
            assertTrue(bridge.owns(attention.operation.callId))
            assertEquals("request_user_question", attention.operation.toolName)

            bridge.submitDecision(
                AttentionUserDecision.Option(attention.operation.callId, 0),
            )
            val terminal = withTimeout(10_000) { running.await() }
            assertEquals("Using the balanced approach.", terminal.finalText)
            assertEquals(1, terminal.toolRequestsIssued)
            assertEquals(1, terminal.toolRequestsResolved)
            assertEquals(2, terminal.providerRequestsCompleted)

            val secondRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val secondBody = secondRequest.body.readUtf8()
            assertTrue(secondBody.contains("pi-question-1"))
            assertTrue(secondBody.contains("Balanced"))
            assertEquals(2, server.requestCount)

            val delivered = requireNotNull(ledger.record(attention.operation.callId))
            assertEquals(
                AttentionDeliveryState.PI_DELIVERED.name,
                delivered.operation.deliveryState,
            )
            assertEquals(
                AttentionResponseState.RESOLVED.name,
                delivered.attention.responseState,
            )
            assertFalse(bridge.owns(attention.operation.callId))
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            database.close()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun stopWhileQuestionIsPendingCancelsAttentionAndDoesNotStartAnotherProviderRequest() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(ledger)
        val taskId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Stop pending question",
            piSessionId = sessionId,
            streamId = UUID.randomUUID().toString(),
            initialPrompt = "Ask a question and wait",
        )
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-attention-stop")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            attentionBridge = bridge,
        )
        try {
            server.enqueue(questionToolResponse())
            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(taskId, "Ask a question and wait", sessionId)
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val attention = withTimeout(10_000) {
                while (ledger.unterminatedRecords().none { it.operation.taskId == taskId }) {
                    delay(5)
                }
                ledger.unterminatedRecords().single { it.operation.taskId == taskId }
            }

            assertNotNull(runtime.stop())
            val stopped = withTimeout(10_000) { running.await() }
            assertTrue(stopped.stopCompleted)
            assertEquals(1, stopped.providerRequestsCompleted)
            assertEquals(0, stopped.toolRequestsResolved)
            assertEquals(1, server.requestCount)
            val cancelled = requireNotNull(ledger.record(attention.operation.callId))
            assertEquals(AttentionLedgerState.CANCELLED.name, cancelled.operation.ledgerState)
            assertEquals(
                AttentionResponseState.CANCELLED.name,
                cancelled.attention.responseState,
            )
            assertFalse(bridge.owns(attention.operation.callId))
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            database.close()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun declinedConfirmationReturnsOneDurableRejectionThenPiContinues() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(ledger)
        val taskId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val streamId = UUID.randomUUID().toString()
        val projector = PhoneLocalPiEventProjector(database)
        projector.createTask(
            taskId = taskId,
            title = "Decline confirmation",
            piSessionId = sessionId,
            streamId = streamId,
            initialPrompt = "Confirm before acting",
        )
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-attention-decline")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            attentionBridge = bridge,
        )
        try {
            server.enqueue(confirmationToolResponse())
            server.enqueue(textResponse("gen-after-decline", "I did not perform the action."))
            var projectedEventCount = 0
            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(
                    taskId = taskId,
                    prompt = "Confirm before acting",
                    sessionId = sessionId,
                    onStatus = { status ->
                        val newEvents = status.events.drop(projectedEventCount)
                        if (newEvents.isNotEmpty()) {
                            projector.append(taskId, sessionId, streamId, newEvents)
                        }
                        projectedEventCount = status.events.size
                    },
                )
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val attention = withTimeout(10_000) {
                while (ledger.unterminatedRecords().none { it.operation.taskId == taskId }) {
                    delay(5)
                }
                ledger.unterminatedRecords().single { it.operation.taskId == taskId }
            }
            assertEquals("request_user_confirmation", attention.operation.toolName)

            bridge.submitDecision(AttentionUserDecision.Decline(attention.operation.callId))
            bridge.submitDecision(AttentionUserDecision.Decline(attention.operation.callId))
            val terminal = withTimeout(10_000) { running.await() }
            assertEquals("I did not perform the action.", terminal.finalText)
            assertEquals(1, terminal.toolRequestsResolved)
            assertEquals(2, terminal.providerRequestsCompleted)
            val followUp = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
            assertTrue(followUp.contains("USER_DECLINED"))
            assertEquals(2, server.requestCount)

            val rejected = requireNotNull(ledger.record(attention.operation.callId))
            assertEquals(AttentionDeliveryState.PI_DELIVERED.name, rejected.operation.deliveryState)
            assertEquals(AttentionResponseState.REJECTED.name, rejected.attention.responseState)
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            database.close()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun oneTaskKeepsThreeTurnsInTheSameRealPiSession() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-task-session-instrumentation")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(
                endpoint = server.url("/api/v1"),
                baseClient = OkHttpClient(),
            ),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            server.enqueue(textResponse("gen-turn-1", "Draft: build, test, release."))
            val first = runtime.startTaskSession(
                taskId = "task-android-session",
                prompt = "Create an Android release plan.",
            )
            assertEquals(1, first.turnCount)
            assertEquals(2, first.sessionEntryCount)
            val firstBody = requireNotNull(server.takeRequest()).body.readUtf8()
            assertTrue(firstBody.contains("Create an Android release plan."))
            assertFalse(firstBody.contains("Draft: build, test, release."))

            server.enqueue(textResponse("gen-turn-2", "Three steps: build, test, release."))
            val second = runtime.continueTaskPrompt(
                taskId = "task-android-session",
                prompt = "Reduce it to three steps.",
            )
            assertEquals(2, second.turnCount)
            assertEquals(4, second.sessionEntryCount)
            val secondBody = requireNotNull(server.takeRequest()).body.readUtf8()
            assertTrue(secondBody.contains("Create an Android release plan."))
            assertTrue(secondBody.contains("Draft: build, test, release."))
            assertTrue(secondBody.contains("Reduce it to three steps."))

            server.enqueue(
                textResponse(
                    "gen-turn-3",
                    "Three steps: build, test on reference Android device, release.",
                ),
            )
            val third = runtime.continueTaskPrompt(
                taskId = "task-android-session",
                prompt = "Make the second step a reference Android device device test.",
            )
            assertEquals(3, third.turnCount)
            assertEquals(6, third.sessionEntryCount)
            val thirdBody = requireNotNull(server.takeRequest()).body.readUtf8()
            assertTrue(thirdBody.contains("Create an Android release plan."))
            assertTrue(thirdBody.contains("Three steps: build, test, release."))
            assertTrue(thirdBody.contains("Make the second step a reference Android device device test."))

            val snapshot = runtime.taskSessionSnapshot("task-android-session")
            assertEquals("task-android-session", snapshot.taskId)
            assertEquals(3, snapshot.turnCount)
            assertEquals(6, snapshot.entries.size)
            assertTrue(runtime.closeTaskSession("task-android-session"))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun planModeUsesRestrictedPiToolsRestoresWithoutReplayAndImplementsOnlyLatestDigest() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-plan-mode-instrumentation")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val taskId = "task-plan-android"
        val sessionId = "session-plan-android"
        try {
            server.enqueue(planToolResponse())
            server.enqueue(textResponse("gen-plan-ready", "The plan is ready."))
            val planned = runtime.startTaskSession(
                taskId = taskId,
                prompt = "Plan an Android release gate.",
                sessionId = sessionId,
                planMode = true,
            )
            val planningBody = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
            assertTrue(planningBody.contains("PLAN MODE IS ACTIVE"))
            assertTrue(planningBody.contains("task_plan_update"))
            assertFalse(planningBody.contains("\"name\":\"run_command\""))
            assertFalse(planningBody.contains("\"name\":\"run_tests\""))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(planned.planMode)
            val digest = requireNotNull(planned.latestPlan).planDigest
            assertTrue(digest.matches(Regex("^[0-9a-f]{64}$")))

            val snapshot = runtime.taskSessionSnapshot(taskId)
            assertTrue(snapshot.planMode)
            assertEquals(digest, snapshot.latestPlan?.planDigest)
            assertTrue(runtime.closeTaskSession(taskId))

            val beforeRestore = server.requestCount
            val restored = runtime.restoreTaskSession(taskId, sessionId, snapshot)
            assertTrue(restored.terminal)
            assertTrue(restored.planMode)
            assertEquals(beforeRestore, server.requestCount)
            assertEquals(snapshot.activeToolNames, restored.activeToolNames)

            val stale = runCatching {
                runtime.implementTaskPlan(taskId, "0".repeat(64))
            }.exceptionOrNull()
            assertNotNull(stale)
            assertTrue(stale?.message.orEmpty().contains("PI_MOBILE_PLAN_DIGEST_STALE"))
            assertEquals(beforeRestore, server.requestCount)

            server.enqueue(textResponse("gen-plan-implemented", "Implemented the approved plan."))
            val implemented = runtime.implementTaskPlan(taskId, digest)
            val implementationBody = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            assertTrue(implementationBody.contains("momoding:implement-plan"))
            assertTrue(implementationBody.contains("\"name\":\"run_command\""))
            assertTrue(implementationBody.contains("\"name\":\"run_tests\""))
            assertFalse(implementationBody.contains("PLAN MODE IS ACTIVE"))
            assertFalse(implemented.planMode)
            assertEquals("Implemented the approved plan.", implemented.finalText)
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun goalUsesNativePiToolsAndRestoresPausedWithoutProviderReplay() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-goal-instrumentation")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        val taskId = "task-goal-android"
        val sessionId = "session-goal-android"
        val goalId = "goal-android"
        try {
            server.enqueue(textResponse("goal-ready", "Ready for a durable goal."))
            runtime.startTaskSession(
                taskId = taskId,
                prompt = "Prepare this task for a durable goal.",
                sessionId = sessionId,
            )
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

            server.enqueue(
                goalToolResponse(
                    generationId = "goal-progress-tool",
                    toolCallId = "goal-progress-call",
                    toolName = "task_goal_progress",
                    arguments =
                        "{\"summary\":\"First checkpoint verified.\",\"progressMarker\":\"1/2\"}",
                ),
            )
            server.enqueue(textResponse("goal-progress-text", "First checkpoint saved."))
            val progressed = runtime.startTaskGoal(
                taskId = taskId,
                goalId = goalId,
                instruction = "Finish two deterministic verification checkpoints.",
                generation = 1,
                startedAtMillis = 1_000,
            )
            val progressBody = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            assertTrue(progressBody.contains("GOAL MODE IS ACTIVE"))
            assertTrue(progressBody.contains("task_goal_progress"))
            assertTrue(progressBody.contains("task_goal_complete"))
            assertTrue(progressBody.contains("momoding:goal-continuation control="))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("active", progressed.goal?.state)
            assertEquals("1/2", progressed.goal?.progressMarker)

            val paused = runtime.setTaskGoalState(taskId, goalId, 1, "paused")
            assertEquals("paused", paused.goal?.state)
            val snapshot = runtime.taskSessionSnapshot(taskId)
            assertEquals("paused", snapshot.goal?.state)
            assertTrue(runtime.closeTaskSession(taskId))

            val beforeRestore = server.requestCount
            val restored = runtime.restoreTaskSession(taskId, sessionId, snapshot)
            assertEquals("paused", restored.goal?.state)
            assertEquals(beforeRestore, server.requestCount)

            server.enqueue(
                goalToolResponse(
                    generationId = "goal-complete-tool",
                    toolCallId = "goal-complete-call",
                    toolName = "task_goal_complete",
                    arguments =
                        "{\"summary\":\"Both checkpoints verified.\",\"terminalReason\":\"achieved\"}",
                ),
            )
            server.enqueue(textResponse("goal-complete-text", "Goal achieved."))
            val achieved = runtime.continueTaskGoal(
                taskId = taskId,
                goalId = goalId,
                generation = 1,
                turnIndex = 1,
                resume = true,
            )
            val resumeBody = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            assertTrue(resumeBody.contains("momoding:goal-continuation control="))
            assertTrue(resumeBody.contains("task_goal_complete"))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("achieved", achieved.goal?.state)
            assertEquals("achieved", achieved.goal?.terminalReason)
            assertFalse(achieved.activeToolNames.contains("task_goal_progress"))
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun childEventsAreIncrementallyDrainedByTheProductionPump() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val childEventBatches = mutableListOf<List<PiChildAgentEventEnvelope>>()
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-child-event-pump")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = { events, _ -> childEventBatches += events },
        )
        val taskId = "task-child-pump"
        try {
            server.enqueue(delegateToolResponse())
            server.enqueue(streamingChildResponse(chunkCount = 48))
            server.enqueue(textResponse("child-parent-summary", "Child analysis completed."))

            val terminal = runtime.startTaskSession(
                taskId = taskId,
                prompt = "Delegate a bounded memory analysis.",
            )

            val parentRequest = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            val childRequest = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            val summaryRequest = requireNotNull(
                server.takeRequest(5, TimeUnit.SECONDS),
            ).body.readUtf8()
            assertTrue(parentRequest.contains("\"name\":\"delegate\""))
            assertTrue(childRequest.contains("Analyze child event memory pressure."))
            assertFalse(childRequest.contains("\"tools\""))
            assertTrue(summaryRequest.contains("delegate-memory"))
            assertTrue(summaryRequest.contains("completed"))

            val child = terminal.childAgents.single()
            assertEquals("completed", child.state)
            assertTrue(child.eventCount > child.eventTypes.size)
            assertTrue(child.eventTypes.contains("message_update"))
            assertEquals(0, terminal.queuedChildEventCount)
            assertTrue(childEventBatches.size > 1)
            val childEvents = childEventBatches.flatten()
            assertEquals(child.eventCount, childEvents.size)
            assertTrue(childEvents.all { it.parentTaskId == taskId })
            assertTrue(childEvents.any { it.event["type"]?.toString() == "\"settled\"" })
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun parentStopPreservesTheChildAbortEventThroughTheProductionPump() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val childEventBatches = mutableListOf<List<PiChildAgentEventEnvelope>>()
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-child-stop-pump")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = { events, _ -> childEventBatches += events },
        )
        val taskId = "task-child-stop-pump"
        try {
            server.enqueue(delegateToolResponse())
            server.enqueue(
                sseResponse("data: [DONE]\n\n")
                    .setBodyDelay(60, TimeUnit.SECONDS),
            )

            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(
                    taskId = taskId,
                    prompt = "Delegate an analysis that must be stopped.",
                )
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(runtime.stop())

            val terminal = withTimeout(10_000) { running.await() }
            val child = terminal.childAgents.single()
            val childEvents = childEventBatches.flatten()
            assertTrue(terminal.stopCompleted)
            assertEquals("cancelled", child.state)
            assertEquals("parent_stopped", child.terminalReason)
            assertTrue(child.eventTypes.contains("abort"))
            assertEquals(child.eventCount, childEvents.size)
            assertTrue(childEvents.any { it.event["type"]?.toString() == "\"abort\"" })
            assertEquals(0, terminal.queuedChildEventCount)
            assertEquals(2, server.requestCount)
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun oneChildCancelUsesTheOwnerMailboxWithoutStoppingTheParent() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val updates = ConcurrentLinkedQueue<List<PiChildAgentSnapshot>>()
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-child-cancel-mailbox")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = { _, snapshots -> updates += snapshots },
        )
        val taskId = "task-child-cancel-mailbox"
        try {
            server.enqueue(delegateToolResponse())
            server.enqueue(
                sseResponse("data: [DONE]\n\n")
                    .setBodyDelay(60, TimeUnit.SECONDS),
            )
            server.enqueue(textResponse("child-cancel-parent", "Parent continued after child cancellation."))

            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(
                    taskId = taskId,
                    prompt = "Delegate one analysis and keep the parent alive if it is cancelled.",
                )
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val child = withTimeout(5_000) {
                while (true) {
                    updates.flatten().firstOrNull { it.state == "running" }?.let { return@withTimeout it }
                    delay(2)
                }
                error("unreachable")
            }
            assertTrue(runtime.cancelChildAgent(taskId, child.childId))

            val terminal = withTimeout(10_000) { running.await() }
            assertFalse(terminal.stopRequested)
            assertEquals("Parent continued after child cancellation.", terminal.finalText)
            assertEquals("cancelled", terminal.childAgents.single().state)
            assertEquals("user_cancelled", terminal.childAgents.single().terminalReason)
            assertEquals(3, server.requestCount)
            assertTrue(runtime.taskSessionSnapshot(taskId).childAgents.isEmpty())
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun productionPumpPersistsChildToRoomReopensAndReleasesLiveHarness() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val databaseName = "child-agent-${UUID.randomUUID()}.db"
        var database = MomodingDatabase.open(context, databaseName)
        database.momodingDao().upsertTask(childTaskEntity(CHILD_ROOM_TASK_ID))
        val childRepository = PhoneLocalChildAgentRepository(database)
        var childSinkAttempts = 0
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-child-room")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = { events, snapshots ->
                childSinkAttempts += 1
                if (childSinkAttempts == 1) error("fixture fail-once before Room commit")
                childRepository.persistRuntimeUpdate(events, snapshots)
            },
        )
        try {
            server.enqueue(delegateToolResponse())
            server.enqueue(streamingChildResponse(chunkCount = 12))
            server.enqueue(textResponse("child-room-parent", "Persisted child summary."))

            val terminal = runtime.startTaskSession(
                taskId = CHILD_ROOM_TASK_ID,
                prompt = "Delegate one analysis and persist it for Task Detail.",
            )
            val terminalChild = terminal.childAgents.single()
            assertEquals("completed", terminalChild.state)
            assertEquals(3, server.requestCount)
            assertTrue(childSinkAttempts >= 2)
            assertTrue(
                "Room acknowledgement must evict the terminal live Harness and Session",
                runtime.taskSessionSnapshot(CHILD_ROOM_TASK_ID).childAgents.isEmpty(),
            )
            assertTrue(runtime.closeTaskSession(CHILD_ROOM_TASK_ID))
            runtime.shutdown()

            database.close()
            database = MomodingDatabase.open(context, databaseName)
            val reopened = requireNotNull(
                TaskDetailRepository(database).observe(CHILD_ROOM_TASK_ID).first(),
            )
            val durable = reopened.childAgents.single()
            assertEquals("COMPLETED", durable.state)
            assertEquals(terminalChild.resultText, durable.resultText)
            assertEquals(terminalChild.eventCount, durable.eventCount)
            val durableEvents = PhoneLocalChildAgentRepository(database)
                .childEvents(CHILD_ROOM_TASK_ID, durable.parentToolCallId)
            assertEquals(terminalChild.eventCount, durableEvents.size)
            assertEquals((0 until terminalChild.eventCount).toList(), durableEvents.map { it.eventOrdinal })
        } finally {
            runCatching { runtime.shutdown() }
            database.close()
            context.deleteDatabase(databaseName)
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun twoParallelPiChildrenPersistSummarizeAndReleaseThroughTheProductionChain() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val taskId = "10000000-0000-4000-8000-000000000036"
        database.momodingDao().upsertTask(childTaskEntity(taskId))
        val childRepository = PhoneLocalChildAgentRepository(database)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-two-child-room")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = childRepository::persistRuntimeUpdate,
        )
        try {
            server.enqueue(parallelDelegateToolResponse())
            server.enqueue(textResponse("dual-child-a", "Network boundary is explicit."))
            server.enqueue(textResponse("dual-child-b", "Build boundary is reproducible."))
            server.enqueue(textResponse("dual-parent-summary", "Both analyses completed."))

            val terminal = runtime.startTaskSession(
                taskId = taskId,
                prompt = "Delegate network and build analysis in parallel.",
            )
            assertEquals(4, server.requestCount)
            assertEquals(2, terminal.childAgents.size)
            assertTrue(terminal.childAgents.all { it.state == "completed" })
            assertEquals(
                setOf("delegate-network", "delegate-build"),
                terminal.childAgents.map { it.parentToolCallId }.toSet(),
            )
            val requestBodies = List(4) {
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
            }
            assertTrue(requestBodies.last().contains("delegate-network"))
            assertTrue(requestBodies.last().contains("delegate-build"))
            assertTrue(requestBodies.last().contains("completed"))

            val durable = requireNotNull(TaskDetailRepository(database).observe(taskId).first())
            assertEquals(2, durable.childAgents.size)
            durable.childAgents.forEach { child ->
                val runtimeChild = terminal.childAgents.single {
                    it.parentToolCallId == child.parentToolCallId
                }
                assertEquals(runtimeChild.eventCount, child.eventCount)
                assertEquals(
                    runtimeChild.eventCount,
                    childRepository.childEvents(taskId, child.parentToolCallId).size,
                )
            }
            assertTrue(runtime.taskSessionSnapshot(taskId).childAgents.isEmpty())
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            database.close()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun permanentChildPersistenceFailureFailsTheParentChainClosed() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-child-persistence-failure")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(server.url("/api/v1"), OkHttpClient()),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            childUpdateSink = { _, _ -> error("permanent child persistence fixture") },
        )
        try {
            server.enqueue(delegateToolResponse())
            server.enqueue(textResponse("uncommitted-child", "Must not be claimed durable."))
            val failure = runCatching {
                withTimeout(10_000) {
                    runtime.startTaskSession(
                        taskId = "task-child-persistence-failure",
                        prompt = "Delegate an analysis that cannot be persisted.",
                    )
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty().contains("permanent child persistence fixture"))
            assertTrue(server.requestCount in 1..2)
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    @Test
    fun runningTaskAcceptsSteerThroughTheOwnerMailboxBeforeTheProviderSettles() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-task-command-mailbox")
        }.asCoroutineDispatcher()
        val runtime = PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = vault,
            client = OpenRouterNativeClient(
                endpoint = server.url("/api/v1"),
                baseClient = OkHttpClient(),
            ),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            server.enqueue(
                textResponse("gen-mailbox-1", "Long draft before steering.")
                    .setBodyDelay(1, TimeUnit.SECONDS),
            )
            server.enqueue(textResponse("gen-mailbox-2", "Short steered answer."))

            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(
                    taskId = "task-mailbox",
                    prompt = "Draft a long answer.",
                )
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2_000) {
                runtime.steerTask("task-mailbox", "Make the answer short.")
            }

            val terminal = withTimeout(10_000) { running.await() }
            assertEquals("Short steered answer.", terminal.finalText)
            assertEquals(2, terminal.providerRequestsCompleted)
            val steeredRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(steeredRequest.body.readUtf8().contains("Make the answer short."))
            assertTrue(runtime.closeTaskSession("task-mailbox"))
        } finally {
            runtime.shutdown()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    private fun sseResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    private fun textResponse(generationId: String, text: String): MockResponse =
        sseResponse(
            """
            data: {"id":"$generationId","model":"$MODEL_ID","choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

            data: [DONE]

            """.trimIndent(),
        ).addHeader("X-Generation-Id", generationId)

    private fun childTaskEntity(taskId: String) = TaskEntity(
        taskId = taskId,
        title = "Child Agent product test",
        runState = "RUNNING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = true,
        updatedAtMillis = 1,
    )

    private fun questionToolResponse(): MockResponse = sseResponse(
        """
        data: {"id":"gen-question","model":"$MODEL_ID","choices":[{"delta":{"tool_calls":[{"index":0,"id":"pi-question-1","type":"function","function":{"name":"request_user_question","arguments":"{\"question\":\"Choose an implementation approach\",\"options\":[{\"label\":\"Balanced\",\"description\":\"Prefer correctness and speed\",\"recommended\":true},{\"label\":\"Fast\"}]}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

        data: [DONE]

        """.trimIndent(),
    ).addHeader("X-Generation-Id", "gen-question")

    private fun confirmationToolResponse(): MockResponse = sseResponse(
        """
        data: {"id":"gen-confirmation","model":"$MODEL_ID","choices":[{"delta":{"tool_calls":[{"index":0,"id":"pi-confirmation-1","type":"function","function":{"name":"request_user_confirmation","arguments":"{\"summary\":\"Apply the proposed change?\",\"details\":\"The action will only run after confirmation.\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

        data: [DONE]

        """.trimIndent(),
    ).addHeader("X-Generation-Id", "gen-confirmation")

    private fun planToolResponse(): MockResponse = sseResponse(
        """
        data: {"id":"gen-plan","model":"$MODEL_ID","choices":[{"delta":{"tool_calls":[{"index":0,"id":"pi-plan-1","type":"function","function":{"name":"task_plan_update","arguments":"{\"explanation\":\"Add a deterministic release gate.\",\"steps\":[{\"id\":\"inspect\",\"text\":\"Inspect existing checks\",\"status\":\"completed\"},{\"id\":\"implement\",\"text\":\"Add release validation\",\"status\":\"in_progress\"},{\"id\":\"verify\",\"text\":\"Run focused tests\",\"status\":\"pending\"}]}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

        data: [DONE]

        """.trimIndent(),
    ).addHeader("X-Generation-Id", "gen-plan")

    private fun delegateToolResponse(): MockResponse = sseResponse(
        "data: {\"id\":\"gen-delegate\",\"model\":\"$MODEL_ID\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"delegate-memory\",\"type\":\"function\",\"function\":{\"name\":\"delegate\",\"arguments\":\"{\\\"name\\\":\\\"Memory analyst\\\",\\\"task\\\":\\\"Analyze child event memory pressure.\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":7,\"total_tokens\":16}}\n\ndata: [DONE]\n\n",
    ).addHeader("X-Generation-Id", "gen-delegate")

    private fun parallelDelegateToolResponse(): MockResponse = sseResponse(
        "data: {\"id\":\"gen-parallel-delegate\",\"model\":\"$MODEL_ID\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"delegate-network\",\"type\":\"function\",\"function\":{\"name\":\"delegate\",\"arguments\":\"{\\\"name\\\":\\\"Network analyst\\\",\\\"task\\\":\\\"Analyze the Android network boundary.\\\"}\"}},{\"index\":1,\"id\":\"delegate-build\",\"type\":\"function\",\"function\":{\"name\":\"delegate\",\"arguments\":\"{\\\"name\\\":\\\"Build analyst\\\",\\\"task\\\":\\\"Analyze build reproducibility.\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":12,\"total_tokens\":23}}\n\ndata: [DONE]\n\n",
    ).addHeader("X-Generation-Id", "gen-parallel-delegate")

    private fun streamingChildResponse(chunkCount: Int): MockResponse {
        val body = buildString {
            repeat(chunkCount) { index ->
                append("data: {\"id\":\"gen-child-stream\",\"model\":\"$MODEL_ID\",\"choices\":[{\"delta\":{\"content\":\"chunk-$index \"}}]}\n\n")
            }
            append("data: {\"id\":\"gen-child-stream\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":48,\"total_tokens\":57}}\n\n")
            append("data: [DONE]\n\n")
        }
        return sseResponse(body)
            .addHeader("X-Generation-Id", "gen-child-stream")
            .throttleBody(256, 5, TimeUnit.MILLISECONDS)
    }

    private fun goalToolResponse(
        generationId: String,
        toolCallId: String,
        toolName: String,
        arguments: String,
    ): MockResponse = sseResponse(
        "data: {\"id\":\"$generationId\",\"model\":\"$MODEL_ID\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$toolCallId\",\"type\":\"function\",\"function\":{\"name\":\"$toolName\",\"arguments\":${jsonString(arguments)}}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":7,\"total_tokens\":16}}\n\ndata: [DONE]\n\n",
    ).addHeader("X-Generation-Id", generationId)

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun testCredential(): ProviderCredential =
        ProviderCredential(
            profile = ProviderProfile(
                id = "22222222-2222-4222-8222-222222222222",
                kind = ProviderKind.OPENROUTER,
                baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                modelId = MODEL_ID,
                displayName = "OpenRouter instrumentation",
            ),
            apiKey = API_KEY,
        )

    private fun imageCapabilityModelsResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "data": [
                    {
                      "id": "fixture/model-a",
                      "name": "Fixture model A",
                      "context_length": 128000,
                      "architecture": {"input_modalities": ["text", "image"]},
                      "supported_parameters": ["tools"]
                    },
                    {
                      "id": "fixture/model-b",
                      "name": "Fixture model B",
                      "context_length": 128000,
                      "architecture": {"input_modalities": ["text"]},
                      "supported_parameters": ["tools"]
                    }
                  ]
                }
                """.trimIndent(),
            )

    private fun defaultImageCapabilityModelsResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "data": [
                    {
                      "id": "$MODEL_ID",
                      "name": "Default fixture model",
                      "context_length": 128000,
                      "architecture": {"input_modalities": ["text", "image"]},
                      "supported_parameters": ["tools"]
                    }
                  ]
                }
                """.trimIndent(),
            )

    private fun skillResources(): EnabledSkillResourceSet {
        val content = """
            ---
            name: mobile-review
            description: Review one bounded mobile change
            ---
            Return PASS or FAIL with one reason.
        """.trimIndent()
        val resource = PhoneLocalSkillResource(
            name = "mobile-review",
            description = "Review one bounded mobile change",
            content = content,
            contentSha256 = content.sha256Utf8(),
            disableModelInvocation = true,
        )
        return EnabledSkillResourceSet(listOf(resource), skillResourceSetDigest(listOf(resource)))
    }

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val API_KEY = "instrumentation-key-never-use-outside-mock-server"
        const val CHILD_ROOM_TASK_ID = "10000000-0000-4000-8000-000000000035"
    }
}
