package app.momoding.core.files

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
class DeviceMetadataToolExecutorTest {
    private lateinit var database: MomodingDatabase
    private lateinit var repository: AuthorizedFoldersRepository
    private lateinit var executor: DeviceMetadataToolExecutor

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        val access = FakeAccess()
        repository = AuthorizedFoldersRepository(
            store = RoomAuthorizedFolderStore(database),
            access = access,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { 10L },
            newGrantId = { GRANT_ID },
        )
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        database.momodingDao().insertDraft(draft(TASK_ID, GRANT_ID))
        executor = DeviceMetadataToolExecutor(database, repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `capabilities expose only the folder selected for this task and no raw URI`() = runBlocking {
        val result = executor.execute(request(
            toolName = DeviceMetadataToolExecutor.CAPABILITIES_TOOL,
            arguments = buildJsonObject {},
        ))

        assertEquals(result.error.toString(), DeviceToolTerminalKind.SUCCEEDED, result.terminal)
        val payload = result.result!!.jsonObject
        assertEquals(GRANT_ID, payload.getValue("grants").jsonArray.single().jsonObject
            .getValue("grantId").jsonPrimitive.content)
        assertFalse(payload.toString().contains("content://"))
        assertFalse(payload.toString().contains("provider-root"))
    }

    @Test
    fun `list returns bounded opaque metadata and rejects another grant`() = runBlocking {
        val listed = executor.execute(request(
            toolName = DeviceMetadataToolExecutor.FILES_LIST_TOOL,
            arguments = buildJsonObject {
                put("grantId", GRANT_ID)
                put("recursive", false)
            },
        ))

        assertEquals(listed.error.toString(), DeviceToolTerminalKind.SUCCEEDED, listed.terminal)
        val entries = listed.result!!.jsonObject.getValue("entries").jsonArray
        assertEquals(2, entries.size)
        assertTrue(entries.all {
            it.jsonObject.getValue("alias").jsonPrimitive.content.startsWith("doc-")
        })
        assertFalse(listed.result.toString().contains("content://"))
        assertFalse(listed.result.toString().contains("provider-root"))

        val denied = executor.execute(request(
            toolName = DeviceMetadataToolExecutor.FILES_LIST_TOOL,
            arguments = buildJsonObject {
                put("grantId", "22222222-2222-4222-8222-222222222222")
            },
        ))
        assertEquals(DeviceToolTerminalKind.FAILED, denied.terminal)
        assertEquals("TASK_FILE_GRANT_REQUIRED", denied.error?.code)
    }

    @Test
    fun `task without a folder can query capabilities but cannot list files`() = runBlocking {
        database.momodingDao().insertDraft(draft(OTHER_TASK_ID, null))

        val capabilities = executor.execute(request(
            taskId = OTHER_TASK_ID,
            toolName = DeviceMetadataToolExecutor.CAPABILITIES_TOOL,
            arguments = buildJsonObject {},
        ))
        assertEquals(
            capabilities.error.toString(),
            DeviceToolTerminalKind.SUCCEEDED,
            capabilities.terminal,
        )
        assertEquals(0, capabilities.result!!.jsonObject.getValue("grants").jsonArray.size)

        val denied = executor.execute(request(
            taskId = OTHER_TASK_ID,
            toolName = DeviceMetadataToolExecutor.FILES_LIST_TOOL,
            arguments = buildJsonObject { put("grantId", GRANT_ID) },
        ))
        assertEquals(DeviceToolTerminalKind.FAILED, denied.terminal)
        assertEquals("TASK_FILE_GRANT_REQUIRED", denied.error?.code)
    }

    private fun request(
        taskId: String = TASK_ID,
        toolName: String,
        arguments: JsonObject,
    ) = DeviceToolRequestFrame(
        protocolVersion = 1,
        kind = "device.tool.request",
        callId = CALL_ID,
        taskId = taskId,
        piToolCallId = "pi-tool-call",
        deviceId = "android-device",
        toolName = toolName,
        arguments = arguments,
        sideEffect = false,
        expiresAt = "2099-01-01T00:00:00Z",
        capabilityVersion = 1,
    )

    private fun draft(taskId: String, grantId: String?) = DraftEntity(
        draftId = "draft-$taskId",
        text = "Inspect mobile files",
        selectedHostId = null,
        selectedModelId = null,
        selectedMode = null,
        selectedGrantId = grantId,
        createCommandId = if (taskId == TASK_ID) CREATE_ID else OTHER_CREATE_ID,
        promptCommandId = if (taskId == TASK_ID) PROMPT_ID else OTHER_PROMPT_ID,
        taskId = taskId,
        updatedAtMillis = 1,
    )

    private class FakeAccess : SafTreeAccess {
        private var persisted: PersistedGrant? = null

        override fun validateTreeUri(treeUri: String) {
            require(treeUri == TREE_URI)
        }

        override fun probeRoot(treeUri: String) =
            SafRootProbe("Project files", "provider.example")

        override fun persistedGrant(treeUri: String): PersistedGrant? = persisted

        override fun takePersistableGrant(treeUri: String, resultFlags: Int) =
            PersistedGrant(read = true, write = true).also { persisted = it }

        override fun releasePersistableGrant(treeUri: String) {
            persisted = null
        }

        override suspend fun list(
            treeUri: String,
            maxDepth: Int,
            maxItems: Int,
        ) = SafListingRecord(
            documents = listOf(
                record("provider-root", null, "Project files", DIRECTORY_MIME, 0),
                record("provider-root/src", "provider-root", "src", DIRECTORY_MIME, 1),
                record("provider-root/README.md", "provider-root", "README.md", "text/markdown", 1),
            ),
            truncated = false,
            truncationReasons = emptySet(),
        )

        private fun record(
            id: String,
            parent: String?,
            name: String,
            mime: String,
            depth: Int,
        ) = SafDocumentRecord(
            documentId = id,
            parentDocumentId = parent,
            displayName = name,
            mimeType = mime,
            byteCount = if (mime == DIRECTORY_MIME) null else 42,
            lastModifiedMillis = 2,
            depth = depth,
        )
    }

    private companion object {
        const val TREE_URI = "content://provider.example/tree/provider-root"
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val TASK_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_TASK_ID = "44444444-4444-4444-8444-444444444444"
        const val CALL_ID = "55555555-5555-4555-8555-555555555555"
        const val CREATE_ID = "66666666-6666-4666-8666-666666666666"
        const val PROMPT_ID = "77777777-7777-4777-8777-777777777777"
        const val OTHER_CREATE_ID = "88888888-8888-4888-8888-888888888888"
        const val OTHER_PROMPT_ID = "99999999-9999-4999-8999-999999999999"
        const val READ_WRITE_FLAGS = 3
        const val DIRECTORY_MIME = "vnd.android.document/directory"
    }
}
