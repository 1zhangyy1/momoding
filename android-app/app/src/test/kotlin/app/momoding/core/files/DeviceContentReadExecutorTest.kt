package app.momoding.core.files

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AttentionAcceptanceScope
import app.momoding.core.data.AttentionRequestRecord
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.TaskEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceContentReadExecutorTest {
    private lateinit var database: MomodingDatabase
    private lateinit var folders: AuthorizedFoldersRepository
    private lateinit var ledger: RoomAttentionLedger
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
        ledger = RoomAttentionLedger(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `approved read consumes an Android-only scope and revocation invalidates it`() = runBlocking {
        val metadata = folders.metadata(GRANT_ID)
        val file = metadata.documents.single { !it.isDirectory }
        val operation = acceptRead(file.alias)
        val executor = DeviceContentReadExecutor(database, folders) { now }

        val result = executor.execute(operation)

        assertEquals("hello", result.getValue("documents").jsonArray.single().jsonObject
            .getValue("content").jsonPrimitive.content)
        assertFalse(result.toString().contains("content://"))
        assertFalse(result.toString().contains("provider-root"))
        val grant = database.momodingDao().taskContentGrant(CALL_ID)
        assertNotNull(grant)
        assertEquals(5L, grant!!.consumedBytes)
        assertEquals(GRANT_ID, grant.grantId)
        assertTrue(grant.documentAliasesJson.contains(file.alias))

        folders.revoke(GRANT_ID)
        assertNotNull(database.momodingDao().taskContentGrant(CALL_ID)?.revokedAtMillis)
        val revoked = runCatching { executor.execute(operation) }.exceptionOrNull()
        assertTrue(revoked is ContentReadExecutionFailure)
        assertEquals("CONTENT_SCOPE_EXPIRED", (revoked as ContentReadExecutionFailure).code)
    }

    @Test
    fun `malformed UTF8 fails closed with a fixed policy error`() = runBlocking {
        val file = folders.metadata(GRANT_ID).documents.single { !it.isDirectory }
        val operation = acceptRead(file.alias)
        access.content = byteArrayOf(0xC3.toByte(), 0x28)

        val failure = runCatching {
            DeviceContentReadExecutor(database, folders) { now }.execute(operation)
        }.exceptionOrNull()

        assertTrue(failure is ContentReadExecutionFailure)
        assertEquals(
            "CONTENT_READ_POLICY_BLOCKED",
            (failure as ContentReadExecutionFailure).code,
        )
    }

    @Test
    fun `active task stop fence cancels content before SAF read`() = runBlocking {
        val file = folders.metadata(GRANT_ID).documents.single { !it.isDirectory }
        val operation = acceptRead(file.alias)
        RoomCommandDraftJournal(database, nowMillis = { now }).persistAccepted(
            requestId = STOP_REQUEST_ID,
            commandId = STOP_COMMAND_ID,
            kind = "session.stop",
            taskId = TASK_ID,
            canonicalPayload = """{"protocolVersion":1,"kind":"session.stop","requestId":"$STOP_REQUEST_ID","commandId":"$STOP_COMMAND_ID","taskId":"$TASK_ID","reason":"user"}""",
        )

        val failure = runCatching {
            DeviceContentReadExecutor(database, folders) { now }.execute(operation)
        }.exceptionOrNull()

        assertTrue(failure is ContentReadExecutionFailure)
        assertEquals("CONTENT_READ_CANCELLED", (failure as ContentReadExecutionFailure).code)
        assertEquals(null, database.momodingDao().taskContentGrant(CALL_ID))
    }

    private fun acceptRead(alias: String) = ledger.acceptRequest(
        AttentionRequestRecord(
            callId = CALL_ID,
            taskId = TASK_ID,
            piToolCallId = "pi-content-read",
            deviceId = DEVICE_ID,
            toolName = "device_files_read",
            arguments = buildJsonObject {
                put("grantId", GRANT_ID)
                put("purpose", "Read the project instructions")
                put("documents", buildJsonArray {
                    add(buildJsonObject {
                        put("alias", alias)
                        put("expectedMimeType", "text/plain")
                        put("maxBytes", 32)
                    })
                })
                put("totalMaxBytes", 32)
            },
            sideEffect = false,
            operationId = null,
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

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Read mobile file",
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
        text = "Inspect the project",
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        selectedGrantId = GRANT_ID,
        createCommandId = "44444444-4444-4444-8444-444444444444",
        promptCommandId = "55555555-5555-4555-8555-555555555555",
        taskId = TASK_ID,
        updatedAtMillis = now,
    )

    private class FakeAccess : SafTreeAccess {
        private var grant: PersistedGrant? = null
        var content = "hello".encodeToByteArray()

        override fun validateTreeUri(treeUri: String) = require(treeUri == TREE_URI)

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
            documents = listOf(
                SafDocumentRecord(
                    documentId = "provider-root",
                    parentDocumentId = null,
                    displayName = "Project",
                    mimeType = "vnd.android.document/directory",
                    byteCount = null,
                    lastModifiedMillis = 1,
                    depth = 0,
                ),
                SafDocumentRecord(
                    documentId = "provider-root/README.txt",
                    parentDocumentId = "provider-root",
                    displayName = "README.txt",
                    mimeType = "text/plain",
                    byteCount = 5,
                    lastModifiedMillis = 2,
                    depth = 1,
                ),
            ),
            truncated = false,
            truncationReasons = emptySet(),
        )

        override suspend fun read(
            treeUri: String,
            documentId: String,
            maxBytes: Int,
        ): ByteArray {
            require(documentId == "provider-root/README.txt")
            return content
        }
    }

    private companion object {
        const val TREE_URI = "content://provider.example/tree/provider-root"
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val DEVICE_ID = "android-content-read-test"
        const val STOP_REQUEST_ID = "44444444-4444-4444-8444-444444444440"
        const val STOP_COMMAND_ID = "55555555-5555-4555-8555-555555555550"
    }
}
