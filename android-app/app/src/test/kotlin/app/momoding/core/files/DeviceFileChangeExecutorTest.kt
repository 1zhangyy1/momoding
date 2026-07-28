package app.momoding.core.files

import android.content.Context
import android.provider.DocumentsContract.Document
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
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeToolRequest
import app.momoding.core.transport.AttentionUserDecision
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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
class DeviceFileChangeExecutorTest {
    private lateinit var database: MomodingDatabase
    private lateinit var folders: AuthorizedFoldersRepository
    private lateinit var executor: DeviceFileChangeExecutor
    private lateinit var access: FakeAccess
    private var now = 1_000L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.momodingDao().upsertTask(task())
        database.momodingDao().insertDraft(draft())
        access = FakeAccess()
        folders = AuthorizedFoldersRepository(
            store = RoomAuthorizedFolderStore(database),
            access = access,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { now },
            newGrantId = { GRANT_ID },
        )
        folders.authorize(TREE_URI, 3)
        executor = DeviceFileChangeExecutor(
            database = database,
            folders = folders,
            nowMillis = { now },
            receiptId = { RECEIPT_ID },
            tokenBytes = { ByteArray(32) { 7 } },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `prepare has zero SAF side effects and approved commit is exactly once`() = runBlocking {
        val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
        val prepared = executor.prepare(prepareCreateFrame(rootAlias))
        now += 60_000
        val replayedPrepare = executor.prepare(prepareCreateFrame(rootAlias))

        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
        assertEquals(prepared, replayedPrepare)
        assertTrue(access.mutations.isEmpty())
        assertEquals(
            FileChangeSetState.PREPARED.name,
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
        )

        val commit = acceptCommit()
        executor.bindCommit(commit)
        val first = executor.approveAndCommit(commit)
        val afterFirst = access.mutations.toList()
        val ledger = RoomAttentionLedger(database) { now }

        assertTrue(restartedExecutor().repairInterruptedCommit(commit, ledger::recordTerminal))
        val replay = restartedExecutor().approveAndCommit(commit)

        assertEquals(listOf("create:notes.txt", "write:provider-root/notes.txt:12"), afterFirst)
        assertEquals(afterFirst, access.mutations)
        assertEquals(first, replay)
        assertEquals(
            AttentionLedgerState.TERMINAL.name,
            ledger.record(COMMIT_CALL_ID)?.operation?.ledgerState,
        )
        assertEquals("completed", first.getValue("outcome").jsonPrimitive.content)
        assertFalse(first.toString().contains("content://"))
        assertFalse(first.toString().contains("provider-root"))
        assertEquals(
            FileChangeSetState.COMPLETED.name,
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
        )
    }

    @Test
    fun `task stop cancels an unbound prepared plan without SAF side effects`() = runBlocking {
        val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
        assertEquals(
            DeviceToolTerminalKind.SUCCEEDED,
            executor.prepare(prepareCreateFrame(rootAlias)).terminal,
        )
        assertTrue(access.mutations.isEmpty())

        assertEquals(
            1,
            executor.cancelUnboundPreparedForTask(TASK_ID, "PROJECT_COMMAND_STOPPED"),
        )
        assertEquals(
            FileChangeSetState.CANCELLED.name,
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
        )
        assertEquals(
            "PROJECT_COMMAND_STOPPED",
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.failureCode,
        )
        assertTrue(access.mutations.isEmpty())
        assertEquals(
            0,
            executor.cancelUnboundPreparedForTask(TASK_ID, "PROJECT_COMMAND_STOPPED"),
        )
    }

    @Test
    fun `crash after SAF before terminal transaction becomes unknown and never replays`() =
        runBlocking {
            val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
            assertEquals(
                DeviceToolTerminalKind.SUCCEEDED,
                executor.prepare(prepareCreateFrame(rootAlias)).terminal,
            )
            val commit = acceptCommit()
            executor.bindCommit(commit)

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    executor.approveAndCommit(commit) {
                        throw IllegalStateException("simulated process death before commit")
                    }
                }
            }

            val afterCrash = access.mutations.toList()
            val ledger = RoomAttentionLedger(database) { now }
            assertEquals(
                FileChangeSetState.COMMITTING.name,
                database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
            )
            assertEquals(null, ledger.record(COMMIT_CALL_ID)?.operation?.terminalSha256)

            assertTrue(restartedExecutor().repairInterruptedCommit(commit, ledger::recordTerminal))
            assertEquals(
                FileChangeSetState.UNKNOWN.name,
                database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
            )
            assertEquals(
                AttentionLedgerState.FAILED_CLOSED.name,
                ledger.record(COMMIT_CALL_ID)?.operation?.ledgerState,
            )
            assertTrue(
                requireNotNull(ledger.record(COMMIT_CALL_ID)?.operation?.terminalFrameCanonicalJson)
                    .contains("FILE_COMMIT_OUTCOME_UNKNOWN"),
            )
            assertEquals(afterCrash, access.mutations)
        }

    @Test
    fun `production commit persists file result and Attention terminal atomically`() = runBlocking {
        val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
        assertEquals(
            DeviceToolTerminalKind.SUCCEEDED,
            executor.prepare(prepareCreateFrame(rootAlias)).terminal,
        )
        val commit = acceptCommit()
        executor.bindCommit(commit)
        val ledger = RoomAttentionLedger(database) { now }

        val result = executor.approveAndCommit(commit, ledger::recordTerminal)

        assertEquals("completed", result.getValue("outcome").jsonPrimitive.content)
        assertEquals(
            FileChangeSetState.COMPLETED.name,
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
        )
        assertEquals(
            AttentionLedgerState.TERMINAL.name,
            ledger.record(COMMIT_CALL_ID)?.operation?.ledgerState,
        )
        assertTrue(
            requireNotNull(ledger.record(COMMIT_CALL_ID)?.operation?.terminalFrameCanonicalJson)
                .contains(RECEIPT_ID),
        )
    }

    @Test
    fun `prepare preview names the exact authorized target folders`() = runBlocking {
        val documents = folders.metadata(GRANT_ID).documents
        val movable = documents.single { it.displayName == "move-me.txt" }
        val archive = documents.single { it.displayName == "archive" }
        val prepared = executor.prepare(
            prepareFrame(
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("purpose", "Move the project note into the archive")
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("operationId", ITEM_OPERATION_ID)
                                    put("kind", "move")
                                    put("sourceAlias", movable.alias)
                                    put("targetParentAlias", archive.alias)
                                    put("expected", expected(movable))
                                },
                            )
                        },
                    )
                },
            ),
        )

        val change = requireNotNull(prepared.result)
            .jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertEquals(
            "Project",
            change.getValue("beforeParentDisplayPath").jsonPrimitive.content,
        )
        assertEquals(
            "Project / archive",
            change.getValue("afterParentDisplayPath").jsonPrimitive.content,
        )
        assertFalse(change.toString().contains("provider-root"))
        assertFalse(change.toString().contains("content://"))
    }

    @Test
    fun `provider exception after mutation is durable unknown and restart never replays`() =
        runBlocking {
            val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
            assertEquals(
                DeviceToolTerminalKind.SUCCEEDED,
                executor.prepare(prepareCreateFrame(rootAlias)).terminal,
            )
            val commit = acceptCommit()
            executor.bindCommit(commit)
            access.failCreateAfterSideEffect = true

            val first = executor.approveAndCommit(commit)
            val afterFirst = access.mutations.toList()
            val replay = restartedExecutor().approveAndCommit(commit)

            assertEquals(listOf("create:notes.txt"), afterFirst)
            assertEquals(afterFirst, access.mutations)
            assertEquals(first, replay)
            assertEquals("unknown", first.getValue("outcome").jsonPrimitive.content)
            assertEquals(
                FileChangeSetState.UNKNOWN.name,
                database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
            )
        }

    @Test
    fun `changed precondition fails before rename and never mutates the provider`() = runBlocking {
        val file = folders.metadata(GRANT_ID).documents.single { it.displayName == "README.txt" }
        val prepared = executor.prepare(prepareRenameFrame(file))
        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
        access.fileLastModified = 99

        val commit = acceptCommit()
        executor.bindCommit(commit)
        val result = executor.approveAndCommit(commit)

        assertEquals("failed", result.getValue("outcome").jsonPrimitive.content)
        assertEquals(0, result.getValue("appliedCount").jsonPrimitive.content.toInt())
        assertTrue(access.mutations.isEmpty())
        assertEquals(
            FileChangeSetState.FAILED.name,
            database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
        )
    }

    @Test
    fun `approved plan executes rename and same provider move in declared order`() = runBlocking {
        val documents = folders.metadata(GRANT_ID).documents
        val file = documents.single { it.displayName == "README.txt" }
        val movable = documents.single { it.displayName == "move-me.txt" }
        val archive = documents.single { it.displayName == "archive" }
        val prepared = executor.prepare(
            prepareFrame(
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("purpose", "Finish and archive project notes")
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("operationId", ITEM_OPERATION_ID)
                                    put("kind", "rename")
                                    put("sourceAlias", file.alias)
                                    put("displayName", "README-final.txt")
                                    put("expected", expected(file))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("operationId", SECOND_ITEM_OPERATION_ID)
                                    put("kind", "move")
                                    put("sourceAlias", movable.alias)
                                    put("targetParentAlias", archive.alias)
                                    put("expected", expected(movable))
                                },
                            )
                        },
                    )
                },
            ),
        )
        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)

        val commit = acceptCommit()
        executor.bindCommit(commit)
        val result = executor.approveAndCommit(commit)

        assertEquals("completed", result.getValue("outcome").jsonPrimitive.content)
        assertEquals(
            listOf("rename:README-final.txt", "move:provider-root/move-me.txt:provider-root/archive"),
            access.mutations,
        )
    }

    @Test
    fun `approved plan writes one text file and deletes another with exact preview`() = runBlocking {
        val documents = folders.metadata(GRANT_ID).documents
        val readme = documents.single { it.displayName == "README.txt" }
        val obsolete = documents.single { it.displayName == "move-me.txt" }
        val prepared = executor.prepare(
            prepareFrame(
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("purpose", "Update the project note and remove the obsolete note")
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("operationId", ITEM_OPERATION_ID)
                                    put("kind", "write_file")
                                    put("sourceAlias", readme.alias)
                                    put("mimeType", readme.mimeType)
                                    put("content", "updated mobile project note")
                                    put("expected", expected(readme))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("operationId", SECOND_ITEM_OPERATION_ID)
                                    put("kind", "delete_file")
                                    put("sourceAlias", obsolete.alias)
                                    put("expected", expected(obsolete))
                                },
                            )
                        },
                    )
                },
            ),
        )

        assertEquals(DeviceToolTerminalKind.SUCCEEDED, prepared.terminal)
        val changes = requireNotNull(prepared.result)
            .jsonObject.getValue("changes").jsonArray.map { it.jsonObject }
        assertEquals("write_file", changes[0].getValue("kind").jsonPrimitive.content)
        assertEquals("README.txt", changes[0].getValue("beforeName").jsonPrimitive.content)
        assertEquals("README.txt", changes[0].getValue("afterName").jsonPrimitive.content)
        assertEquals("delete_file", changes[1].getValue("kind").jsonPrimitive.content)
        assertEquals("move-me.txt", changes[1].getValue("beforeName").jsonPrimitive.content)
        assertEquals(false, changes[1].containsKey("afterName"))

        val commit = acceptCommit()
        executor.bindCommit(commit)
        val result = executor.approveAndCommit(commit)

        assertEquals("completed", result.getValue("outcome").jsonPrimitive.content)
        assertEquals(
            listOf(
                "write-existing:provider-root/README.txt:27",
                "delete:provider-root/move-me.txt",
            ),
            access.mutations,
        )
    }

    @Test
    fun `existing file mutations require complete exact provider metadata`() = runBlocking {
        val readme = folders.metadata(GRANT_ID).documents.single {
            it.displayName == "README.txt"
        }
        val missingMetadata = executor.prepare(
            prepareFrame(
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("purpose", "Must not overwrite with an incomplete precondition")
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("operationId", ITEM_OPERATION_ID)
                                    put("kind", "write_file")
                                    put("sourceAlias", readme.alias)
                                    put("mimeType", readme.mimeType)
                                    put("content", "unsafe replacement")
                                    put(
                                        "expected",
                                        buildJsonObject {
                                            put("displayName", readme.displayName)
                                            put("mimeType", readme.mimeType)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
        assertEquals(DeviceToolTerminalKind.FAILED, missingMetadata.terminal)
        assertEquals("INVALID_FILE_CHANGE_SET", missingMetadata.error?.code)

        val falseNullMetadata = executor.prepare(
            prepareFrame(
                buildJsonObject {
                    put("grantId", GRANT_ID)
                    put("purpose", "Must not overwrite when Provider metadata is known")
                    put(
                        "operations",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("operationId", ITEM_OPERATION_ID)
                                    put("kind", "delete_file")
                                    put("sourceAlias", readme.alias)
                                    put(
                                        "expected",
                                        buildJsonObject {
                                            put("displayName", readme.displayName)
                                            put("mimeType", readme.mimeType)
                                            put("byteCount", JsonNull)
                                            put("lastModifiedMillis", JsonNull)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
        assertEquals(DeviceToolTerminalKind.FAILED, falseNullMetadata.terminal)
        assertEquals("INVALID_FILE_CHANGE_SET", falseNullMetadata.error?.code)
        assertTrue(access.mutations.isEmpty())
    }

    @Test
    fun `phone local review rejection never mutates and process recovery closes pending commit`() =
        runBlocking {
            val rootAlias = folders.metadata(GRANT_ID).documents.single { it.depth == 0 }.alias
            assertEquals(
                DeviceToolTerminalKind.SUCCEEDED,
                executor.prepare(prepareCreateFrame(rootAlias)).terminal,
            )
            val ledger = RoomAttentionLedger(database) { now }
            val rejectingBridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                fileChangeHandler = executor,
                nowMillis = { now },
                idFactory = { COMMIT_CALL_ID },
                operationIdFactory = { COMMIT_OPERATION_ID },
            )
            val commitRequest = PiNativeToolRequest(
                id = "native-file-commit-reject",
                kind = "android_file_tool",
                toolCallId = "pi-file-commit-reject",
                toolName = "device_files_commit_changes",
                arguments = buildJsonObject {
                    put("preparedId", PREPARE_CALL_ID)
                    put(
                        "planDigest",
                        requireNotNull(database.momodingDao().fileChangeSet(PREPARE_CALL_ID))
                            .planDigest,
                    )
                },
            )
            val rejectedCallId = rejectingBridge.accept(TASK_ID, commitRequest)
            rejectingBridge.submitDecision(
                AttentionUserDecision.RejectFileChanges(rejectedCallId),
            )

            assertTrue(access.mutations.isEmpty())
            assertEquals(
                FileChangeSetState.REJECTED.name,
                database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
            )
            assertEquals(
                AttentionLedgerState.TERMINAL.name,
                ledger.record(rejectedCallId)?.operation?.ledgerState,
            )
            assertTrue(
                requireNotNull(ledger.record(rejectedCallId)?.operation?.terminalFrameCanonicalJson)
                    .contains("USER_DECLINED"),
            )

            database.momodingDao().updateFileChangeSet(
                requireNotNull(database.momodingDao().fileChangeSet(PREPARE_CALL_ID)).copy(
                    state = FileChangeSetState.PREPARED.name,
                    commitCallId = null,
                    commitOperationId = null,
                    failureCode = null,
                ),
            )
            val pendingCallId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val pendingBridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                fileChangeHandler = executor,
                nowMillis = { now },
                idFactory = { pendingCallId },
                operationIdFactory = { "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" },
            )
            pendingBridge.accept(
                TASK_ID,
                commitRequest.copy(id = "native-file-commit-pending"),
            )
            PhoneLocalAttentionBridge(
                ledger = ledger,
                fileChangeHandler = executor,
                nowMillis = { now },
            ).recoverDestroyedRuntime()

            assertTrue(access.mutations.isEmpty())
            assertEquals(
                FileChangeSetState.CANCELLED.name,
                database.momodingDao().fileChangeSet(PREPARE_CALL_ID)?.state,
            )
            assertTrue(
                requireNotNull(ledger.record(pendingCallId)?.operation?.terminalFrameCanonicalJson)
                    .contains("FILE_COMMIT_CANCELLED"),
            )
        }

    private fun prepareCreateFrame(rootAlias: String) = prepareFrame(
        buildJsonObject {
            put("grantId", GRANT_ID)
            put("purpose", "Create a short project note")
            put(
                "operations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("operationId", ITEM_OPERATION_ID)
                            put("kind", "create_file")
                            put("parentAlias", rootAlias)
                            put("displayName", "notes.txt")
                            put("mimeType", "text/plain")
                            put("content", "hello mobile")
                        },
                    )
                },
            )
        },
    )

    private fun prepareRenameFrame(file: AuthorizedDocumentMetadata) = prepareFrame(
        buildJsonObject {
            put("grantId", GRANT_ID)
            put("purpose", "Use the final project note name")
            put(
                "operations",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("operationId", ITEM_OPERATION_ID)
                            put("kind", "rename")
                            put("sourceAlias", file.alias)
                            put("displayName", "README-final.txt")
                            put(
                                "expected",
                                buildJsonObject {
                                    put("displayName", file.displayName)
                                    put("mimeType", file.mimeType)
                                    put(
                                        "byteCount",
                                        file.byteCount?.let(::JsonPrimitive) ?: JsonNull,
                                    )
                                    put(
                                        "lastModifiedMillis",
                                        file.lastModifiedMillis?.let(::JsonPrimitive) ?: JsonNull,
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    private fun expected(file: AuthorizedDocumentMetadata) = buildJsonObject {
        put("displayName", file.displayName)
        put("mimeType", file.mimeType)
        put("byteCount", file.byteCount?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "lastModifiedMillis",
            file.lastModifiedMillis?.let(::JsonPrimitive) ?: JsonNull,
        )
    }

    private fun prepareFrame(arguments: kotlinx.serialization.json.JsonObject) =
        DeviceToolRequestFrame(
            protocolVersion = 1,
            kind = "device.tool.request",
            callId = PREPARE_CALL_ID,
            taskId = TASK_ID,
            piToolCallId = "pi-prepare",
            deviceId = DEVICE_ID,
            toolName = "device_files_prepare_changes",
            arguments = arguments,
            sideEffect = false,
            operationId = null,
            expiresAt = "2099-01-01T00:00:00Z",
            capabilityVersion = 1,
        )

    private fun acceptCommit() = RoomAttentionLedger(database) { now }.acceptRequest(
        AttentionRequestRecord(
            callId = COMMIT_CALL_ID,
            taskId = TASK_ID,
            piToolCallId = "pi-commit",
            deviceId = DEVICE_ID,
            toolName = "device_files_commit_changes",
            arguments = buildJsonObject {
                put("preparedId", PREPARE_CALL_ID)
                put(
                    "planDigest",
                    requireNotNull(database.momodingDao().fileChangeSet(PREPARE_CALL_ID)).planDigest,
                )
            },
            sideEffect = true,
            operationId = COMMIT_OPERATION_ID,
            expiresAt = "2099-01-01T00:00:00Z",
            capabilityVersion = 1,
        ),
        AttentionAcceptanceScope(
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            capabilityVersion = 1,
            originFocusKey = "task-$TASK_ID",
        ),
    ).operation

    private fun restartedExecutor() = DeviceFileChangeExecutor(
        database = database,
        folders = folders,
        nowMillis = { now },
        receiptId = { RECEIPT_ID },
        tokenBytes = { ByteArray(32) { 7 } },
    )

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Change mobile files",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "PENDING",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = now,
    )

    private fun draft() = DraftEntity(
        draftId = "draft",
        text = "Update the project",
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        selectedGrantId = GRANT_ID,
        createCommandId = UUID.randomUUID().toString(),
        promptCommandId = UUID.randomUUID().toString(),
        taskId = TASK_ID,
        updatedAtMillis = now,
    )

    private class FakeAccess : SafTreeAccess {
        private var grant: PersistedGrant? = null
        val mutations = mutableListOf<String>()
        var fileLastModified = 2L
        var failCreateAfterSideEffect = false
        private var fileDisplayName = "README.txt"
        private var created = false
        private var moveDeleted = false

        override fun validateTreeUri(treeUri: String) = require(treeUri == TREE_URI)
        override fun probeRoot(treeUri: String) = SafRootProbe("Project", "provider.example")
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
                        documentId = "provider-root/README.txt",
                        parentDocumentId = "provider-root",
                        displayName = fileDisplayName,
                        mimeType = "text/plain",
                        byteCount = 5,
                        lastModifiedMillis = fileLastModified,
                        depth = 1,
                        flags = (
                            Document.FLAG_SUPPORTS_RENAME or
                                Document.FLAG_SUPPORTS_MOVE or
                                Document.FLAG_SUPPORTS_WRITE
                            ).toLong(),
                    ),
                )
                add(
                    SafDocumentRecord(
                        documentId = "provider-root/archive",
                        parentDocumentId = "provider-root",
                        displayName = "archive",
                        mimeType = Document.MIME_TYPE_DIR,
                        byteCount = null,
                        lastModifiedMillis = 2,
                        depth = 1,
                        flags = Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
                    ),
                )
                if (!moveDeleted) {
                    add(
                        SafDocumentRecord(
                            documentId = "provider-root/move-me.txt",
                            parentDocumentId = "provider-root",
                            displayName = "move-me.txt",
                            mimeType = "text/plain",
                            byteCount = 4,
                            lastModifiedMillis = 2,
                            depth = 1,
                            flags = (
                                Document.FLAG_SUPPORTS_MOVE or
                                    Document.FLAG_SUPPORTS_DELETE
                                ).toLong(),
                        ),
                    )
                }
                if (created) {
                    add(
                        SafDocumentRecord(
                            documentId = "provider-root/notes.txt",
                            parentDocumentId = "provider-root",
                            displayName = "notes.txt",
                            mimeType = "text/plain",
                            byteCount = 12,
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

        override suspend fun create(
            treeUri: String,
            parentDocumentId: String,
            mimeType: String,
            displayName: String,
        ): String {
            mutations += "create:$displayName"
            created = true
            if (failCreateAfterSideEffect) {
                throw IllegalStateException("Provider result was lost after create")
            }
            return "provider-root/$displayName"
        }

        override suspend fun writeNew(treeUri: String, documentId: String, bytes: ByteArray) {
            mutations += "write:$documentId:${bytes.size}"
        }

        override suspend fun writeExisting(
            treeUri: String,
            documentId: String,
            bytes: ByteArray,
        ) {
            mutations += "write-existing:$documentId:${bytes.size}"
        }

        override suspend fun rename(
            treeUri: String,
            documentId: String,
            displayName: String,
        ): String {
            mutations += "rename:$displayName"
            fileDisplayName = displayName
            return documentId
        }

        override suspend fun move(
            treeUri: String,
            documentId: String,
            sourceParentDocumentId: String,
            targetParentDocumentId: String,
        ): String {
            mutations += "move:$documentId:$targetParentDocumentId"
            return documentId
        }

        override suspend fun deleteExisting(treeUri: String, documentId: String) {
            mutations += "delete:$documentId"
            moveDeleted = true
        }
    }

    private companion object {
        const val TREE_URI = "content://provider.example/tree/provider-root"
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val PREPARE_CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val COMMIT_CALL_ID = "44444444-4444-4444-8444-444444444444"
        const val ITEM_OPERATION_ID = "55555555-5555-4555-8555-555555555555"
        const val SECOND_ITEM_OPERATION_ID = "99999999-9999-4999-8999-999999999999"
        const val COMMIT_OPERATION_ID = "66666666-6666-4666-8666-666666666666"
        const val RECEIPT_ID = "77777777-7777-4777-8777-777777777777"
        const val DEVICE_ID = "android-file-change-test"
    }
}
