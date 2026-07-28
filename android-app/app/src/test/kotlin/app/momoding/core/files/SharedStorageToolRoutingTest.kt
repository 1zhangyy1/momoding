package app.momoding.core.files

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionAcceptanceScope
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionRequestRecord
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedStorageToolRoutingTest {
    private lateinit var database: MomodingDatabase
    private lateinit var temporary: File
    private lateinit var shared: SharedStorageRepository
    private lateinit var folders: AuthorizedFoldersRepository
    private var ready = true

    @Before
    fun setUp() {
        ready = true
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.momodingDao().upsertTask(task())
        database.momodingDao().insertDraft(draft())
        temporary = Files.createTempDirectory("momoding-shared-routing").toFile()
        shared = SharedStorageRepository(
            rootDirectories = mapOf("downloads" to temporary),
            accessReady = { ready },
            ioDispatcher = Dispatchers.Unconfined,
        )
        folders = AuthorizedFoldersRepository(
            store = RoomAuthorizedFolderStore(database),
            access = UnusedSafAccess,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
        temporary.deleteRecursively()
    }

    @Test
    fun `existing tools route synthetic grant through durable auto policy commit exactly once`() =
        runBlocking {
        val metadata = DeviceMetadataToolExecutor(database, folders, shared)
        val capabilities = metadata.execute(frame(
            callId = UUID.randomUUID().toString(),
            toolName = DeviceMetadataToolExecutor.CAPABILITIES_TOOL,
            arguments = buildJsonObject {},
        ))
        val grant = capabilities.result!!.jsonObject.getValue("grants").jsonArray.single().jsonObject
        assertEquals("shared_storage", grant.getValue("scope").jsonPrimitive.content)
        val grantId = grant.getValue("grantId").jsonPrimitive.content

        val listed = metadata.execute(frame(
            callId = UUID.randomUUID().toString(),
            toolName = DeviceMetadataToolExecutor.FILES_LIST_TOOL,
            arguments = buildJsonObject { put("grantId", grantId) },
        ))
        assertEquals(DeviceToolTerminalKind.SUCCEEDED, listed.terminal)
        val rootAlias = listed.result!!.jsonObject.getValue("entries").jsonArray
            .let { entries ->
                assertTrue(entries.isEmpty())
                shared.metadata(grantId).documents.single().alias
            }

        val executor = DeviceFileChangeExecutor(
            database = database,
            folders = folders,
            sharedStorage = shared,
            receiptId = { RECEIPT_ID },
            tokenBytes = { ByteArray(32) { 9 } },
        )
        val preparedId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()
        val prepared = executor.prepare(frame(
            callId = preparedId,
            toolName = "device_files_prepare_changes",
            arguments = buildJsonObject {
                put("grantId", grantId)
                put("purpose", "Create the requested shared-storage note")
                put(
                    "operations",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("operationId", operationId)
                                put("kind", "create_file")
                                put("parentAlias", rootAlias)
                                put("displayName", "once.txt")
                                put("mimeType", "text/plain")
                                put("content", "exactly once")
                            },
                        )
                    },
                )
            },
        ))
        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
        val digest = prepared.result!!.jsonObject.getValue("planDigest").jsonPrimitive.content
        val commitCallId = UUID.randomUUID().toString()
        val ledger = RoomAttentionLedger(database)
        val commit = ledger.acceptRequest(
            AttentionRequestRecord(
                callId = commitCallId,
                taskId = TASK_ID,
                piToolCallId = "pi-commit",
                deviceId = DEVICE_ID,
                toolName = "device_files_commit_changes",
                arguments = buildJsonObject {
                    put("preparedId", preparedId)
                    put("planDigest", digest)
                },
                sideEffect = true,
                operationId = UUID.randomUUID().toString(),
                expiresAt = "2099-01-01T00:00:00Z",
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "task-$TASK_ID"),
        ).operation

        val first = executor.autoApproveAndCommit(commit, ledger::recordTerminal)
        val replay = executor.autoApproveAndCommit(commit, ledger::recordTerminal)
        assertEquals(first, replay)
        assertEquals(
            DeviceToolTerminalKind.SUCCEEDED.wireValue,
            ledger.record(commitCallId)?.operation?.terminalKind,
        )
        assertEquals("exactly once", File(temporary, "once.txt").readText())
        assertEquals(1, temporary.listFiles()!!.count { it.name == "once.txt" })
        assertFalse(first.toString().contains(temporary.absolutePath))
    }

    @Test
    fun `revoked shared grant fails commit before any approval`() = runBlocking {
        val grant = shared.roots().single()
        val rootAlias = shared.metadata(grant.grantId).documents.single().alias
        val executor = DeviceFileChangeExecutor(
            database = database,
            folders = folders,
            sharedStorage = shared,
        )
        val preparedId = UUID.randomUUID().toString()
        val prepared = executor.prepare(frame(
            callId = preparedId,
            toolName = "device_files_prepare_changes",
            arguments = buildJsonObject {
                put("grantId", grant.grantId)
                put("purpose", "Prepare before Android authorization is revoked")
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("operationId", UUID.randomUUID().toString())
                        put("kind", "create_file")
                        put("parentAlias", rootAlias)
                        put("displayName", "must-not-exist.txt")
                        put("mimeType", "text/plain")
                        put("content", "blocked")
                    })
                })
            },
        ))
        val digest = prepared.result!!.jsonObject.getValue("planDigest").jsonPrimitive.content
        ready = false
        val callId = UUID.randomUUID().toString()
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            fileChangeHandler = executor,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            idFactory = { callId },
        )

        assertEquals(
            null,
            bridge.handleNativeRequest(
                TASK_ID,
                PiNativeToolRequest(
                    id = "revoked-pre-bind",
                    kind = "android_file_tool",
                    toolCallId = "pi-revoked-pre-bind",
                    toolName = "device_files_commit_changes",
                    arguments = buildJsonObject {
                        put("preparedId", preparedId)
                        put("planDigest", digest)
                    },
                ),
            ),
        )
        val record = requireNotNull(RoomAttentionLedger(database).record(callId))
        assertEquals(AttentionLedgerState.FAILED_CLOSED.name, record.operation.ledgerState)
        assertTrue(
            requireNotNull(record.operation.terminalFrameCanonicalJson)
                .contains("AUTHORIZED_FOLDER_UNAVAILABLE"),
        )
        assertFalse(File(temporary, "must-not-exist.txt").exists())
    }

    @Test
    fun `wrong digest and cross task commit fail closed without mutation`() = runBlocking {
        database.momodingDao().upsertTask(task(OTHER_TASK_ID))
        val grant = shared.roots().single()
        val rootAlias = shared.metadata(grant.grantId).documents.single().alias
        val executor = DeviceFileChangeExecutor(
            database = database,
            folders = folders,
            sharedStorage = shared,
        )
        suspend fun prepareFor(taskId: String, displayName: String): Pair<String, String> {
            val preparedId = UUID.randomUUID().toString()
            val prepared = executor.prepare(frame(
                callId = preparedId,
                taskId = taskId,
                toolName = "device_files_prepare_changes",
                arguments = buildJsonObject {
                    put("grantId", grant.grantId)
                    put("purpose", "Reject an invalid commit binding")
                    put("operations", buildJsonArray {
                        add(buildJsonObject {
                            put("operationId", UUID.randomUUID().toString())
                            put("kind", "create_file")
                            put("parentAlias", rootAlias)
                            put("displayName", displayName)
                            put("mimeType", "text/plain")
                            put("content", "must not be committed")
                        })
                    })
                },
            ))
            assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
            return preparedId to
                prepared.result!!.jsonObject.getValue("planDigest").jsonPrimitive.content
        }

        val wrongDigest = prepareFor(TASK_ID, "wrong-digest.txt")
        val crossTask = prepareFor(OTHER_TASK_ID, "cross-task.txt")
        val callIds = java.util.ArrayDeque(
            listOf(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        )
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            fileChangeHandler = executor,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            idFactory = { callIds.removeFirst() },
        )
        val scenarios = listOf(
            Triple("wrong-digest", wrongDigest.first, "b".repeat(64)),
            Triple("cross-task", crossTask.first, crossTask.second),
        )

        scenarios.forEach { (id, preparedId, digest) ->
            bridge.handleNativeRequest(
                TASK_ID,
                PiNativeToolRequest(
                    id = id,
                    kind = "android_file_tool",
                    toolCallId = "pi-$id",
                    toolName = "device_files_commit_changes",
                    arguments = buildJsonObject {
                        put("preparedId", preparedId)
                        put("planDigest", digest)
                    },
                ),
            )
            val record = requireNotNull(
                database.momodingDao().deviceOperations(TASK_ID).single {
                    it.piToolCallId == "pi-$id"
                },
            )
            assertEquals(AttentionLedgerState.FAILED_CLOSED.name, record.ledgerState)
            assertTrue(
                requireNotNull(record.terminalFrameCanonicalJson)
                    .contains("FILE_COMMIT_CONFLICT"),
            )
        }
        assertFalse(File(temporary, "wrong-digest.txt").exists())
        assertFalse(File(temporary, "cross-task.txt").exists())
    }

    private fun frame(
        callId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        taskId: String = TASK_ID,
    ) = DeviceToolRequestFrame(
        protocolVersion = 1,
        kind = "device.tool.request",
        callId = callId,
        taskId = taskId,
        piToolCallId = "pi-$callId",
        deviceId = DEVICE_ID,
        toolName = toolName,
        arguments = arguments,
        sideEffect = false,
        operationId = null,
        expiresAt = "2099-01-01T00:00:00Z",
        capabilityVersion = 1,
    )

    private fun task(taskId: String = TASK_ID) = TaskEntity(
        taskId = taskId,
        title = "Shared storage",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = 1,
    )

    private fun draft() = DraftEntity(
        draftId = "draft-$TASK_ID",
        text = "Use shared storage",
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        selectedGrantId = null,
        createCommandId = UUID.randomUUID().toString(),
        promptCommandId = UUID.randomUUID().toString(),
        taskId = TASK_ID,
        updatedAtMillis = 1,
    )

    private object UnusedSafAccess : SafTreeAccess {
        override fun validateTreeUri(treeUri: String) = error("SAF should not be used")
        override fun probeRoot(treeUri: String) = error("SAF should not be used")
        override fun persistedGrant(treeUri: String): PersistedGrant? = null
        override fun takePersistableGrant(treeUri: String, resultFlags: Int) =
            error("SAF should not be used")
        override fun releasePersistableGrant(treeUri: String) = Unit
        override suspend fun list(treeUri: String, maxDepth: Int, maxItems: Int) =
            error("SAF should not be used")
    }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "phone-local-android"
        const val RECEIPT_ID = "22222222-2222-4222-8222-222222222222"
    }
}
