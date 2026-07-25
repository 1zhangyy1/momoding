package app.momoding.core.runtime.local

import android.content.Intent
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.DeviceContentReadExecutor
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.DeviceMetadataToolExecutor
import app.momoding.core.files.PersistedGrant
import app.momoding.core.files.RoomAuthorizedFolderStore
import app.momoding.core.files.SafDocumentRecord
import app.momoding.core.files.SafListingRecord
import app.momoding.core.files.SafRootProbe
import app.momoding.core.files.SafTreeAccess
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.core.transport.AttentionUserDecision
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLocalFileAgentInstrumentedTest {
    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun capabilitiesListReadPrepareReviewCommitReturnToTheSamePiTurn() = runBlocking {
        val server = MockWebServer()
        val vault = ProviderCredentialVault.create(context)
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val taskId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val access = LocalFileAccess()
        val folders = AuthorizedFoldersRepository(
            store = RoomAuthorizedFolderStore(database),
            access = access,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { now },
            newGrantId = { FILE_GRANT_ID },
        )
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Update an authorized mobile project",
            piSessionId = sessionId,
            streamId = UUID.randomUUID().toString(),
            initialPrompt = INITIAL_PROMPT,
        )
        folders.authorize(
            LOCAL_TREE_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        database.momodingDao().insertDraft(
            DraftEntity(
                draftId = "phone-local-file-draft",
                text = INITIAL_PROMPT,
                selectedHostId = null,
                selectedModelId = MODEL_ID,
                selectedMode = null,
                selectedGrantId = FILE_GRANT_ID,
                createCommandId = UUID.randomUUID().toString(),
                promptCommandId = UUID.randomUUID().toString(),
                taskId = taskId,
                updatedAtMillis = now,
            ),
        )
        val listing = folders.metadata(FILE_GRANT_ID)
        val rootAlias = listing.documents.single { it.depth == 0 }.alias
        val note = listing.documents.single { it.displayName == "project-note.txt" }
        val prepareArguments = buildJsonObject {
            put("grantId", FILE_GRANT_ID)
            put("purpose", "Create the reviewed mobile report")
            put(
                "operations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("operationId", FILE_MUTATION_ID)
                            put("kind", "create_file")
                            put("parentAlias", rootAlias)
                            put("displayName", "mobile-report.txt")
                            put("mimeType", "text/plain")
                            put("content", "Reviewed on Android.\n")
                        },
                    )
                },
            )
        }
        val fileExecutor = DeviceFileChangeExecutor(
            database = database,
            folders = folders,
            nowMillis = { now },
            receiptId = { FILE_RECEIPT_ID },
            tokenBytes = { ByteArray(32) { 3 } },
        )
        val prepared = fileExecutor.prepare(
            DeviceToolRequestFrame(
                protocolVersion = 1,
                kind = "device.tool.request",
                callId = FILE_PREPARE_CALL_ID,
                taskId = taskId,
                piToolCallId = "pi-file-prepare",
                deviceId = "phone-local-android",
                toolName = "device_files_prepare_changes",
                arguments = prepareArguments,
                sideEffect = false,
                expiresAt = Instant.ofEpochMilli(now + 30 * 60_000L).toString(),
                capabilityVersion = 1,
            ),
        )
        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
        assertTrue(access.mutations.isEmpty())
        val planDigest = requireNotNull(prepared.result)
            .jsonObject.getValue("planDigest").jsonPrimitive.content
        val callIds = ArrayDeque(
            listOf(
                FILE_CAPABILITIES_CALL_ID,
                FILE_LIST_CALL_ID,
                FILE_READ_CALL_ID,
                FILE_PREPARE_CALL_ID,
                FILE_COMMIT_CALL_ID,
            ),
        )
        val ledger = RoomAttentionLedger(database) { now }
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            metadataTools = DeviceMetadataToolExecutor(database, folders),
            contentReadHandler = DeviceContentReadExecutor(
                database = database,
                folders = folders,
                nowMillis = { now },
            ),
            fileChangeHandler = fileExecutor,
            nowMillis = { now },
            idFactory = { callIds.removeFirst() },
            operationIdFactory = { FILE_COMMIT_OPERATION_ID },
        )
        vault.deleteFile()
        vault.deleteKey()
        vault.store(testCredential())
        server.start()
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-file-agent-instrumentation")
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
            server.enqueue(
                toolResponse(
                    "gen-capabilities",
                    "pi-capabilities",
                    "device_capabilities_get",
                    "{}",
                ),
            )
            server.enqueue(
                toolResponse(
                    "gen-list",
                    "pi-list",
                    "device_files_list",
                    buildJsonObject { put("grantId", FILE_GRANT_ID) }.toString(),
                ),
            )
            server.enqueue(
                toolResponse(
                    "gen-read",
                    "pi-read",
                    "device_files_read",
                    buildJsonObject {
                        put("grantId", FILE_GRANT_ID)
                        put("purpose", "Read the note needed for the requested report")
                        put(
                            "documents",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("alias", note.alias)
                                        put("expectedMimeType", "text/plain")
                                        put("maxBytes", 4_096)
                                    },
                                )
                            },
                        )
                        put("totalMaxBytes", 4_096)
                    }.toString(),
                ),
            )
            server.enqueue(
                toolResponse(
                    "gen-prepare",
                    "pi-file-prepare",
                    "device_files_prepare_changes",
                    prepareArguments.toString(),
                ),
            )
            server.enqueue(
                toolResponse(
                    "gen-commit",
                    "pi-file-commit",
                    "device_files_commit_changes",
                    buildJsonObject {
                        put("preparedId", FILE_PREPARE_CALL_ID)
                        put("planDigest", planDigest)
                    }.toString(),
                ),
            )
            server.enqueue(
                textResponse(
                    "gen-file-finished",
                    "The reviewed mobile report was created.",
                ),
            )

            val running = async(Dispatchers.Default) {
                runtime.startTaskSession(taskId, INITIAL_PROMPT, sessionId)
            }
            val requestBodies = mutableListOf(
                requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8(),
            )
            val offeredTools = requestBodies.single()
            assertTrue(offeredTools.contains("device_capabilities_get"))
            assertTrue(offeredTools.contains("device_files_list"))
            assertTrue(offeredTools.contains("device_files_read"))
            assertTrue(offeredTools.contains("device_files_prepare_changes"))
            assertTrue(offeredTools.contains("device_files_commit_changes"))

            val read = withTimeoutOrNull(10_000) {
                while (ledger.unterminatedRecords().none {
                        it.operation.taskId == taskId &&
                            it.operation.toolName == "device_files_read"
                    }
                ) {
                    delay(5)
                }
                ledger.unterminatedRecords().single {
                    it.operation.taskId == taskId &&
                        it.operation.toolName == "device_files_read"
                }
            } ?: error(
                "PHONE_LOCAL_FILE_READ_NOT_REACHED:" +
                    "requests=${server.requestCount}:" +
                    "runningCompleted=${running.isCompleted}:" +
                    "operations=${database.momodingDao().deviceOperations(taskId).map { it.toolName }}",
            )
            bridge.submitDecision(AttentionUserDecision.AllowContentRead(read.operation.callId))

            val commit = withTimeoutOrNull(10_000) {
                while (ledger.unterminatedRecords().none {
                        it.operation.taskId == taskId &&
                            it.operation.toolName == "device_files_commit_changes"
                    }
                ) {
                    delay(5)
                }
                ledger.unterminatedRecords().single {
                    it.operation.taskId == taskId &&
                        it.operation.toolName == "device_files_commit_changes"
                }
            } ?: run {
                val completed = running.await()
                while (requestBodies.size < server.requestCount) {
                    requestBodies += requireNotNull(server.takeRequest()).body.readUtf8()
                }
                error(
                    "PHONE_LOCAL_FILE_COMMIT_NOT_REACHED:" +
                        "requests=${server.requestCount}:" +
                        "issued=${completed.toolRequestsIssued}:" +
                        "resolved=${completed.toolRequestsResolved}:" +
                        "errors=${completed.promptError}:" +
                        "operations=${database.momodingDao().deviceOperations(taskId).map { it.toolName }}:" +
                        "bodies=${requestBodies.drop(3)}",
                )
            }
            assertEquals(
                listOf("read:provider-root/project-note.txt"),
                access.mutations,
            )
            bridge.submitDecision(AttentionUserDecision.ApproveFileChanges(commit.operation.callId))

            val terminal = withTimeout(15_000) { running.await() }
            assertEquals("The reviewed mobile report was created.", terminal.finalText)
            assertEquals(5, terminal.toolRequestsIssued)
            assertEquals(5, terminal.toolRequestsResolved)
            assertEquals(
                listOf(
                    "read:provider-root/project-note.txt",
                    "create:mobile-report.txt",
                    "write:provider-root/mobile-report.txt:21",
                ),
                access.mutations,
            )
            assertEquals(null, ledger.record(read.operation.callId))
            assertEquals(
                AttentionDeliveryState.PI_DELIVERED.name,
                requireNotNull(ledger.record(commit.operation.callId))
                    .operation.deliveryState,
            )
            repeat(5) {
                requestBodies += requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    .body.readUtf8()
            }
            val providerPayloads = requestBodies.joinToString("\n")
            assertFalse(providerPayloads.contains("content://"))
            assertFalse(providerPayloads.contains("UNSAFE_DEVICE_TOOL_REQUEST"))
            assertFalse(providerPayloads.contains("PI_MOBILE_UNSUPPORTED_TOOL_SCHEMA"))
            assertTrue(providerPayloads.contains("Project"))
            assertTrue(runtime.closeTaskSession(taskId))
        } finally {
            runtime.shutdown()
            database.close()
            server.shutdown()
            vault.deleteFile()
            vault.deleteKey()
        }
    }

    private fun toolResponse(
        generationId: String,
        toolCallId: String,
        toolName: String,
        arguments: String,
    ): MockResponse = sseResponse(
        "data: " + JSONObject()
            .put("id", generationId)
            .put("model", MODEL_ID)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "delta",
                            JSONObject().put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("index", 0)
                                        .put("id", toolCallId)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", toolName)
                                                .put("arguments", arguments),
                                        ),
                                ),
                            ),
                        )
                        .put("finish_reason", "tool_calls"),
                ),
            )
            .put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", 9)
                    .put("completion_tokens", 7)
                    .put("total_tokens", 16),
            ).toString() + "\n\ndata: [DONE]\n\n",
    ).addHeader("X-Generation-Id", generationId)

    private fun textResponse(generationId: String, text: String): MockResponse =
        sseResponse(
            """
            data: {"id":"$generationId","model":"$MODEL_ID","choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":7,"total_tokens":16}}

            data: [DONE]

            """.trimIndent(),
        ).addHeader("X-Generation-Id", generationId)

    private fun sseResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun testCredential() = ProviderCredential(
        profile = ProviderProfile(
            id = "22222222-2222-4222-8222-222222222222",
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = MODEL_ID,
            displayName = "OpenRouter instrumentation",
        ),
        apiKey = "instrumentation-key-never-use-outside-mock-server",
    )

    private class LocalFileAccess : SafTreeAccess {
        private var grant: PersistedGrant? = null
        private var created = false
        val mutations = mutableListOf<String>()

        override fun validateTreeUri(treeUri: String) = require(treeUri == LOCAL_TREE_URI)

        override fun probeRoot(treeUri: String) =
            SafRootProbe("Project", "provider.example")

        override fun persistedGrant(treeUri: String): PersistedGrant? = grant

        override fun takePersistableGrant(treeUri: String, resultFlags: Int) =
            PersistedGrant(read = true, write = true).also { grant = it }

        override fun releasePersistableGrant(treeUri: String) {
            grant = null
        }

        override suspend fun list(
            treeUri: String,
            maxDepth: Int,
            maxItems: Int,
        ) = SafListingRecord(
            documents = buildList {
                add(
                    SafDocumentRecord(
                        documentId = "provider-root",
                        parentDocumentId = null,
                        displayName = "Project",
                        mimeType = Document.MIME_TYPE_DIR,
                        byteCount = null,
                        lastModifiedMillis = 1,
                        depth = 0,
                        flags = Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
                    ),
                )
                add(
                    SafDocumentRecord(
                        documentId = "provider-root/project-note.txt",
                        parentDocumentId = "provider-root",
                        displayName = "project-note.txt",
                        mimeType = "text/plain",
                        byteCount = 13,
                        lastModifiedMillis = 2,
                        depth = 1,
                        flags = 0,
                    ),
                )
                if (created) {
                    add(
                        SafDocumentRecord(
                            documentId = "provider-root/mobile-report.txt",
                            parentDocumentId = "provider-root",
                            displayName = "mobile-report.txt",
                            mimeType = "text/plain",
                            byteCount = 21,
                            lastModifiedMillis = 3,
                            depth = 1,
                            flags = 0,
                        ),
                    )
                }
            },
            truncated = false,
            truncationReasons = emptySet(),
        )

        override suspend fun read(
            treeUri: String,
            documentId: String,
            maxBytes: Int,
        ): ByteArray {
            require(documentId == "provider-root/project-note.txt")
            mutations += "read:$documentId"
            return "Project note\n".toByteArray()
        }

        override suspend fun create(
            treeUri: String,
            parentDocumentId: String,
            mimeType: String,
            displayName: String,
        ): String {
            require(parentDocumentId == "provider-root")
            require(displayName == "mobile-report.txt")
            mutations += "create:$displayName"
            created = true
            return "provider-root/mobile-report.txt"
        }

        override suspend fun writeNew(
            treeUri: String,
            documentId: String,
            bytes: ByteArray,
        ) {
            require(documentId == "provider-root/mobile-report.txt")
            mutations += "write:$documentId:${bytes.size}"
        }

        override suspend fun deleteNew(treeUri: String, documentId: String) {
            created = false
            mutations += "delete:$documentId"
        }
    }

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val INITIAL_PROMPT = "Read the project note and create a reviewed report."
        const val FILE_GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val FILE_CAPABILITIES_CALL_ID = "21111111-1111-4111-8111-111111111111"
        const val FILE_LIST_CALL_ID = "31111111-1111-4111-8111-111111111111"
        const val FILE_READ_CALL_ID = "41111111-1111-4111-8111-111111111111"
        const val FILE_PREPARE_CALL_ID = "51111111-1111-4111-8111-111111111111"
        const val FILE_COMMIT_CALL_ID = "61111111-1111-4111-8111-111111111111"
        const val FILE_COMMIT_OPERATION_ID = "71111111-1111-4111-8111-111111111111"
        const val FILE_MUTATION_ID = "81111111-1111-4111-8111-111111111111"
        const val FILE_RECEIPT_ID = "91111111-1111-4111-8111-111111111111"
        const val LOCAL_TREE_URI = "content://provider.example/tree/provider-root"
    }
}
