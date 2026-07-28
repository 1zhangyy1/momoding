package app.momoding.core.files

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import app.momoding.core.data.OutboundCommandEntity
import app.momoding.core.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthorizedFoldersHostUnpairTest {
    private lateinit var database: MomodingDatabase
    private lateinit var access: FakeSafAccess
    private lateinit var repository: AuthorizedFoldersRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        access = FakeSafAccess()
        repository = AuthorizedFoldersRepository(
            store = RoomAuthorizedFolderStore(database),
            access = access,
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { 10L },
            newGrantId = { GRANT_ID },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `host unpair erase preserves authorized folder until user revokes it`() = runTest {
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        val dao = database.p2Dao()
        dao.upsertTask(hostTask())
        dao.insertDraft(hostDraft())
        dao.insertOutboundCommand(hostCommand())

        dao.eraseHostScopedData()

        assertTrue(dao.allTasks().isEmpty())
        assertTrue(dao.drafts().isEmpty())
        assertTrue(dao.outboundCommands().isEmpty())
        val afterUnpair = repository.folders().single()
        assertEquals(GRANT_ID, afterUnpair.grantId)
        assertTrue(afterUnpair.canRead)
        assertFalse(access.released)

        repository.revoke(GRANT_ID)

        assertTrue(access.released)
        assertTrue(repository.folders().isEmpty())
        assertNull(dao.authorizedFolder(GRANT_ID))
    }

    private fun hostTask() = TaskEntity(
        taskId = TASK_ID,
        title = "Host-owned task",
        runState = null,
        recoveryState = null,
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = 1,
    )

    private fun hostDraft() = DraftEntity(
        draftId = DRAFT_ID,
        text = "Host-owned draft",
        selectedHostId = "host",
        selectedModelId = null,
        selectedMode = null,
        createCommandId = CREATE_COMMAND_ID,
        promptCommandId = PROMPT_COMMAND_ID,
        taskId = TASK_ID,
        updatedAtMillis = 1,
    )

    private fun hostCommand() = OutboundCommandEntity(
        requestId = REQUEST_ID,
        commandId = CREATE_COMMAND_ID,
        kind = "task.create",
        taskId = TASK_ID,
        canonicalPayload = "{}",
        payloadSha256 = SHA256,
        state = "ACCEPTED",
        responseJson = null,
        responseSha256 = null,
        stopFenceState = null,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )

    private class FakeSafAccess : SafTreeAccess {
        private var grant: PersistedGrant? = null
        var released: Boolean = false
            private set

        override fun validateTreeUri(treeUri: String) {
            require(treeUri == TREE_URI)
        }

        override fun probeRoot(treeUri: String): SafRootProbe =
            SafRootProbe("Project files", "provider.example")

        override fun persistedGrant(treeUri: String): PersistedGrant? = grant

        override fun takePersistableGrant(
            treeUri: String,
            resultFlags: Int,
        ): PersistedGrant {
            require(resultFlags == READ_WRITE_FLAGS)
            return PersistedGrant(read = true, write = true).also { grant = it }
        }

        override fun releasePersistableGrant(treeUri: String) {
            require(treeUri == TREE_URI)
            released = true
            grant = null
        }

        override suspend fun list(
            treeUri: String,
            maxDepth: Int,
            maxItems: Int,
        ): SafListingRecord = error("Metadata listing is not part of this lifecycle test")
    }

    private companion object {
        const val TREE_URI = "content://provider.example/tree/provider-root"
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val TASK_ID = "task-host-unpair"
        const val DRAFT_ID = "22222222-2222-4222-8222-222222222222"
        const val CREATE_COMMAND_ID = "33333333-3333-4333-8333-333333333333"
        const val PROMPT_COMMAND_ID = "44444444-4444-4444-8444-444444444444"
        const val REQUEST_ID = "request-host-unpair"
        const val SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val READ_WRITE_FLAGS = 3
    }
}
