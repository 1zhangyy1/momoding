package app.momoding.core.runtime.local

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract.Document
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AuthorizedFolderEntity
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftEntity
import app.momoding.core.data.TaskEntity
import app.momoding.core.files.AuthorizedFileMutation
import app.momoding.core.files.AuthorizedFolderStore
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.PersistedGrant
import app.momoding.core.files.SafDocumentRecord
import app.momoding.core.files.SafListingRecord
import app.momoding.core.files.SafRootProbe
import app.momoding.core.files.SafTreeAccess
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
class PhoneLocalProjectWorkspaceTest {
    private lateinit var database: MomodingDatabase
    private lateinit var root: File
    private lateinit var folders: AuthorizedFoldersRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = File(context.cacheDir, "project-workspace-${System.nanoTime()}")
        check(root.mkdirs())
        folders = AuthorizedFoldersRepository(
            store = FakeStore(),
            access = FakeAccess(),
            ioDispatcher = Dispatchers.Unconfined,
            nowMillis = { 100L },
            newGrantId = { GRANT_ID },
        )
        folders.authorize(TREE_URI, READ_WRITE_FLAGS)
        database.momodingDao().upsertTask(
            TaskEntity(
                taskId = TASK_ID,
                title = "Project terminal",
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
                updatedAtMillis = 100L,
            ),
        )
        database.momodingDao().insertDraft(
            DraftEntity(
                draftId = "draft",
                text = "Update the project",
                selectedHostId = null,
                selectedModelId = "model",
                selectedMode = null,
                selectedGrantId = GRANT_ID,
                createCommandId = "create",
                promptCommandId = "prompt",
                taskId = TASK_ID,
                updatedAtMillis = 100L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun `approved task snapshot becomes deterministic write delete create operations`() = runBlocking {
        val manager = manager()

        val imported = manager.importApprovedTask(TASK_ID)
        val workspace = File(root, "workspaces/$TASK_ID")

        assertEquals(2, imported.importedFileCount)
        assertEquals("SENSITIVE_NAME", imported.exclusions.single().reason)
        assertEquals("fun main() = println(\"before\")\n", File(workspace, "src/Main.kt").readText())
        assertFalse(File(workspace, ".env").exists())
        assertFalse(
            File(root, "manifests/$TASK_ID.json").readText().contains("content://"),
        )

        File(workspace, "src/Main.kt").writeText("fun main() = println(\"after\")\n")
        check(File(workspace, "delete.txt").delete())
        File(workspace, "README.md").writeText("# Mobile project\n")

        val changes = manager().detectChanges(TASK_ID)

        assertTrue(changes.unsupported.isEmpty())
        assertEquals(listOf("README.md"), changes.createdPaths)
        assertEquals(listOf("src/Main.kt"), changes.modifiedPaths)
        assertEquals(listOf("delete.txt"), changes.deletedPaths)
        assertEquals(3, changes.operations.size)
        assertTrue(changes.operations.any { it is AuthorizedFileMutation.CreateFile })
        assertTrue(changes.operations.any { it is AuthorizedFileMutation.WriteFile })
        assertTrue(changes.operations.any { it is AuthorizedFileMutation.DeleteFile })
        assertTrue(changes.operations.all { runCatching { java.util.UUID.fromString(it.operationId) }.isSuccess })
    }

    @Test
    fun `task grant change fails closed after import`() = runBlocking {
        val manager = manager()
        manager.importApprovedTask(TASK_ID)
        val draft = requireNotNull(database.momodingDao().draft("draft"))
        check(database.momodingDao().updateDraft(draft.copy(selectedGrantId = null)) == 1)

        assertTrue(runCatching { manager.detectChanges(TASK_ID) }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `private manifest corruption fails closed before producing operations`() = runBlocking {
        val manager = manager()
        manager.importApprovedTask(TASK_ID)
        val manifest = File(root, "manifests/$TASK_ID.json")
        manifest.writeText(manifest.readText().replace("Main.kt", "Main2.kt"))

        val failure = runCatching { manager.detectChanges(TASK_ID) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message?.contains("MANIFEST_DIGEST_MISMATCH") == true)
    }

    @Test
    fun `new directory and symlink never become writable SAF operations`() = runBlocking {
        val manager = manager()
        manager.importApprovedTask(TASK_ID)
        val workspace = File(root, "workspaces/$TASK_ID")
        File(workspace, "generated").mkdirs()
        File(workspace, "generated/output.txt").writeText("not directly writable")
        val outside = File(root, "outside").apply { mkdirs() }
        val victim = File(outside, "victim.txt").apply { writeText("must survive") }
        val relativeTarget = workspace.toPath().relativize(outside.toPath())
        Files.createSymbolicLink(File(workspace, "escape-link").toPath(), relativeTarget)

        val changes = manager.detectChanges(TASK_ID)

        assertTrue(changes.operations.isEmpty())
        assertTrue(changes.unsupported.any { it.reason == "NEW_DIRECTORY" })
        assertTrue(changes.unsupported.any { it.reason == "PARENT_DIRECTORY_NOT_IN_SNAPSHOT" })
        assertTrue(changes.unsupported.any { it.reason == "SYMLINK" })

        manager.importApprovedTask(TASK_ID)
        assertEquals("must survive", victim.readText())
        assertFalse(File(workspace, "escape-link").exists())
    }

    @Test
    fun `project import waits for the shared workspace execution lock`() = runBlocking {
        val manager = manager()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            PhoneLocalWorkspaceLocks.withLock(TASK_ID) {
                locked.complete(Unit)
                release.await()
            }
        }
        locked.await()

        val importing = async { manager.importApprovedTask(TASK_ID) }
        delay(100)
        assertFalse(importing.isCompleted)

        release.complete(Unit)
        holder.await()
        assertEquals(TASK_ID, importing.await().workspaceId)
    }

    @Test
    fun `project terminal returns structured output without changing the authorized folder`() =
        runBlocking {
            val executor = toolExecutor { request ->
                PhoneLocalCommandResult(
                    runId = request.runId,
                    stdout = "/workspace\nterminal-ok",
                    stderr = "",
                    exitCode = 0,
                    timedOut = false,
                    stopped = false,
                    outputTruncated = false,
                    durationMillis = 8,
                )
            }

            val result = executor.execute(
                TASK_ID,
                projectRequest("run_command", "pwd && printf terminal-ok"),
            )

            assertTrue(result.toString(), result.getValue("ok").jsonPrimitive.content.toBoolean())
            assertEquals("terminal", result.getValue("kind").jsonPrimitive.content)
            assertEquals(
                "/workspace\nterminal-ok",
                result.getValue("stdout").jsonPrimitive.content,
            )
            assertEquals(
                "none",
                result.getValue("fileChanges").jsonObject
                    .getValue("state").jsonPrimitive.content,
            )
            assertEquals(null, database.momodingDao().fileChangeSet(PREPARED_ID))
        }

    @Test
    fun `project edits become a prepared Android diff and never commit during command execution`() =
        runBlocking {
            val executor = toolExecutor { request ->
                File(root, "workspaces/$TASK_ID/src/Main.kt")
                    .writeText("fun main() = println(\"after command\")\n")
                File(root, "workspaces/$TASK_ID/command-output.txt")
                    .writeText("created by command\n")
                PhoneLocalCommandResult(
                    runId = request.runId,
                    stdout = "changed two files",
                    stderr = "",
                    exitCode = 0,
                    timedOut = false,
                    stopped = false,
                    outputTruncated = false,
                    durationMillis = 12,
                )
            }

            val result = executor.execute(
                TASK_ID,
                projectRequest("run_command", "update-project"),
            )
            val changes = result.getValue("fileChanges").jsonObject

            assertTrue(result.toString(), result.getValue("ok").jsonPrimitive.content.toBoolean())
            assertEquals("prepared", changes.getValue("state").jsonPrimitive.content)
            assertEquals(PREPARED_ID, changes.getValue("preparedId").jsonPrimitive.content)
            assertEquals(
                listOf("command-output.txt"),
                changes.getValue("createdPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("src/Main.kt"),
                changes.getValue("modifiedPaths").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("PREPARED", database.momodingDao().fileChangeSet(PREPARED_ID)?.state)
        }

    @Test
    fun `invalid project command is rejected before Linux execution`() = runBlocking {
        var executed = false
        val executor = toolExecutor {
            executed = true
            error("must not execute")
        }

        val result = executor.execute(
            TASK_ID,
            PiNativeToolRequest(
                id = "native-invalid",
                kind = "android_project_tool",
                toolCallId = "call-invalid",
                toolName = "run_tests",
                arguments = buildJsonObject {
                    put("command", "")
                    put("timeoutMillis", 900_001)
                },
            ),
        )

        assertFalse(executed)
        assertFalse(result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals(
            "PROJECT_TOOL_ARGUMENTS_INVALID",
            result.getValue("errorCode").jsonPrimitive.content,
        )
    }

    @Test
    fun `stop targets the active project process and returns stopped result`() = runBlocking {
        val started = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>()
        val activeRunId = AtomicReference<String?>()
        val executor = PhoneLocalProjectToolExecutor(
            executeCommand = { request ->
                activeRunId.set(request.runId)
                started.complete(request.runId)
                stopped.await()
                PhoneLocalCommandResult(
                    runId = request.runId,
                    stdout = "",
                    stderr = "",
                    exitCode = 143,
                    timedOut = false,
                    stopped = true,
                    outputTruncated = false,
                    durationMillis = 25,
                )
            },
            stopCommand = { runId ->
                if (activeRunId.get() == runId) {
                    stopped.complete(Unit)
                    true
                } else {
                    false
                }
            },
            projects = manager(),
            fileChanges = DeviceFileChangeExecutor(
                database,
                folders,
                nowMillis = { 100L },
            ),
            nowMillis = { 100L },
            newPreparedId = { PREPARED_ID },
        )
        val running = async(Dispatchers.Default) {
            executor.execute(
                TASK_ID,
                projectRequest("run_command", "sleep 30"),
            )
        }

        started.await()
        assertTrue(executor.stopTask(TASK_ID))
        val result = running.await()

        assertFalse(result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertTrue(result.getValue("stopped").jsonPrimitive.content.toBoolean())
        assertEquals(
            "PROJECT_COMMAND_STOPPED",
            result.getValue("errorCode").jsonPrimitive.content,
        )
        assertFalse(executor.stopTask(TASK_ID))
    }

    @Test
    fun `stop after project registration prevents import and Linux start`() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val stopSignalled = CompletableDeferred<Unit>()
        var executed = false
        val executor = PhoneLocalProjectToolExecutor(
            executeCommand = { request ->
                executed = true
                PhoneLocalCommandResult(
                    runId = request.runId,
                    stdout = "must not run",
                    stderr = "",
                    exitCode = 0,
                    timedOut = false,
                    stopped = false,
                    outputTruncated = false,
                    durationMillis = 1,
                )
            },
            stopCommand = {
                stopSignalled.complete(Unit)
                false
            },
            projects = manager(),
            fileChanges = DeviceFileChangeExecutor(
                database,
                folders,
                nowMillis = { 100L },
            ),
            nowMillis = { 100L },
            newPreparedId = { PREPARED_ID },
            afterRunRegistered = {
                registered.complete(Unit)
                releaseRegistration.await()
            },
        )
        val running = async(Dispatchers.Default) {
            executor.execute(
                TASK_ID,
                projectRequest("run_command", "printf must-not-run"),
            )
        }

        registered.await()
        val stopping = async(Dispatchers.Default) { executor.stopTask(TASK_ID) }
        stopSignalled.await()
        releaseRegistration.complete(Unit)

        assertTrue(stopping.await())
        val result = running.await()
        assertFalse(executed)
        assertFalse(result.getValue("ok").jsonPrimitive.content.toBoolean())
        assertTrue(result.getValue("stopped").jsonPrimitive.content.toBoolean())
        assertEquals(
            "PROJECT_COMMAND_STOPPED",
            result.getValue("errorCode").jsonPrimitive.content,
        )
        assertTrue(File(root, "workspaces/$TASK_ID").listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "manifests/$TASK_ID.json").exists())
    }

    private fun toolExecutor(
        execute: suspend (PhoneLocalCommandRequest) -> PhoneLocalCommandResult,
    ) = PhoneLocalProjectToolExecutor(
        executeCommand = execute,
        stopCommand = { false },
        projects = manager(),
        fileChanges = DeviceFileChangeExecutor(
            database,
            folders,
            nowMillis = { 100L },
        ),
        nowMillis = { 100L },
        newPreparedId = { PREPARED_ID },
    )

    private fun projectRequest(toolName: String, command: String) = PiNativeToolRequest(
        id = "native-$toolName",
        kind = "android_project_tool",
        toolCallId = "call-$toolName",
        toolName = toolName,
        arguments = buildJsonObject { put("command", command) },
    )

    private fun manager() = PhoneLocalProjectWorkspace(
        database = database,
        folders = folders,
        workspaceRoot = { taskId -> File(root, "workspaces/$taskId").apply { mkdirs() } },
        manifestRoot = File(root, "manifests"),
    )

    private class FakeStore : AuthorizedFolderStore {
        private val rows = linkedMapOf<String, AuthorizedFolderEntity>()

        override suspend fun list(): List<AuthorizedFolderEntity> = rows.values.toList()
        override suspend fun get(grantId: String): AuthorizedFolderEntity? = rows[grantId]
        override suspend fun findByTreeHash(treeUriSha256: String): AuthorizedFolderEntity? =
            rows.values.firstOrNull { it.treeUriSha256 == treeUriSha256 }

        override suspend fun upsert(entity: AuthorizedFolderEntity) {
            rows[entity.grantId] = entity
        }

        override suspend fun delete(grantId: String): Boolean = rows.remove(grantId) != null
    }

    private class FakeAccess : SafTreeAccess {
        private var persisted: PersistedGrant? = null
        private val contents = mapOf(
            "root/src/Main.kt" to "fun main() = println(\"before\")\n".encodeToByteArray(),
            "root/delete.txt" to "delete me\n".encodeToByteArray(),
            "root/.env" to "TOKEN=secret\n".encodeToByteArray(),
        )

        override fun validateTreeUri(treeUri: String) = require(treeUri == TREE_URI)
        override fun probeRoot(treeUri: String) = SafRootProbe("Project", "provider.example")
        override fun persistedGrant(treeUri: String): PersistedGrant? = persisted
        override fun takePersistableGrant(treeUri: String, resultFlags: Int): PersistedGrant =
            PersistedGrant(read = true, write = true).also { persisted = it }

        override fun releasePersistableGrant(treeUri: String) {
            persisted = null
        }

        override suspend fun list(
            treeUri: String,
            maxDepth: Int,
            maxItems: Int,
        ): SafListingRecord = SafListingRecord(
            documents = listOf(
                directory("root", null, "Project", 0),
                directory("root/src", "root", "src", 1),
                file("root/src/Main.kt", "root/src", "Main.kt", 2),
                file("root/delete.txt", "root", "delete.txt", 1),
                file("root/.env", "root", ".env", 1),
            ),
            truncated = false,
            truncationReasons = emptySet(),
        )

        override suspend fun read(treeUri: String, documentId: String, maxBytes: Int): ByteArray =
            requireNotNull(contents[documentId]).also { require(it.size <= maxBytes) }.copyOf()

        private fun directory(id: String, parent: String?, name: String, depth: Int) =
            SafDocumentRecord(
                documentId = id,
                parentDocumentId = parent,
                displayName = name,
                mimeType = Document.MIME_TYPE_DIR,
                byteCount = null,
                lastModifiedMillis = 10L + depth,
                depth = depth,
                flags = Document.FLAG_DIR_SUPPORTS_CREATE.toLong(),
            )

        private fun file(id: String, parent: String, name: String, depth: Int) =
            SafDocumentRecord(
                documentId = id,
                parentDocumentId = parent,
                displayName = name,
                mimeType = "text/plain",
                byteCount = contents.getValue(id).size.toLong(),
                lastModifiedMillis = 20L + depth,
                depth = depth,
                flags = (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE).toLong(),
            )
    }

    private companion object {
        const val TASK_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val GRANT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val TREE_URI = "content://provider.example/tree/root"
        const val PREPARED_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
