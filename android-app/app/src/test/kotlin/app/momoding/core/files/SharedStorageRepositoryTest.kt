package app.momoding.core.files

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedStorageRepositoryTest {
    private lateinit var temporary: File
    private lateinit var downloads: File
    private lateinit var documents: File
    private var ready = true
    private lateinit var repository: SharedStorageRepository

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("momoding-shared-storage").toFile()
        downloads = File(temporary, "Download").apply { mkdirs() }
        documents = File(temporary, "Documents").apply { mkdirs() }
        repository = SharedStorageRepository(
            rootDirectories = linkedMapOf(
                "downloads" to downloads,
                "documents" to documents,
            ),
            accessReady = { ready },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        temporary.deleteRecursively()
    }

    @Test
    fun `opaque shared grant performs bounded create read rename and delete`() = runBlocking {
        val grant = repository.roots().single { it.rootId == "downloads" }
        assertFalse(grant.grantId.contains("Download"))
        val root = repository.metadata(grant.grantId).documents.single()
        assertTrue(root.alias.matches(Regex("^doc-[0-9a-f]{24}$")))

        val create = AuthorizedFileMutation.CreateFile(
            operationId = uuid(),
            parentAlias = root.alias,
            displayName = "MomodingGate.txt",
            mimeType = "text/plain",
            content = "hello shared storage",
        )
        repository.validateChanges(grant.grantId, listOf(create))
        assertEquals(
            "completed",
            repository.commitChanges(grant.grantId, listOf(create)).outcome,
        )

        val created = repository.metadata(grant.grantId).documents.single {
            it.displayName == "MomodingGate.txt"
        }
        val read = repository.readText(
            grant.grantId,
            listOf(
                AuthorizedDocumentReadRequest(
                    alias = created.alias,
                    expectedMimeType = "text/plain",
                    maxBytes = 128,
                ),
            ),
            totalMaxBytes = 128,
        )
        assertEquals("hello shared storage", read.documents.single().content)

        val rename = AuthorizedFileMutation.Rename(
            operationId = uuid(),
            sourceAlias = created.alias,
            displayName = "MomodingGate-renamed.txt",
            expected = created.precondition(),
        )
        repository.validateChanges(grant.grantId, listOf(rename))
        assertEquals(
            "completed",
            repository.commitChanges(grant.grantId, listOf(rename)).outcome,
        )
        val renamed = repository.metadata(grant.grantId).documents.single {
            it.displayName == "MomodingGate-renamed.txt"
        }
        val delete = AuthorizedFileMutation.DeleteFile(
            operationId = uuid(),
            sourceAlias = renamed.alias,
            expected = renamed.precondition(),
        )
        assertEquals(
            "completed",
            repository.commitChanges(grant.grantId, listOf(delete)).outcome,
        )
        assertFalse(File(downloads, "MomodingGate-renamed.txt").exists())
    }

    @Test
    fun `revocation cross root traversal precondition and stop fail closed`() = runBlocking {
        val downloadGrant = repository.roots().single { it.rootId == "downloads" }
        val documentsGrant = repository.roots().single { it.rootId == "documents" }
        val downloadRoot = repository.metadata(downloadGrant.grantId).documents.single()
        val documentsRoot = repository.metadata(documentsGrant.grantId).documents.single()
        File(downloads, "existing.txt").writeText("before")
        val existing = repository.metadata(downloadGrant.grantId).documents.single {
            it.displayName == "existing.txt"
        }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.validateChanges(
                    downloadGrant.grantId,
                    listOf(
                        AuthorizedFileMutation.CreateFile(
                            uuid(),
                            downloadRoot.alias,
                            "../escape.txt",
                            "text/plain",
                            "blocked",
                        ),
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.validateChanges(
                    downloadGrant.grantId,
                    listOf(
                        AuthorizedFileMutation.CreateFile(
                            uuid(),
                            documentsRoot.alias,
                            "cross-root.txt",
                            "text/plain",
                            "blocked",
                        ),
                    ),
                )
            }
        }

        val staleDelete = AuthorizedFileMutation.DeleteFile(
            uuid(),
            existing.alias,
            existing.precondition(),
        )
        File(downloads, "existing.txt").appendText("-changed")
        val stale = repository.commitChanges(downloadGrant.grantId, listOf(staleDelete))
        assertEquals("failed", stale.outcome)
        assertEquals("FILE_PRECONDITION_FAILED", stale.results.single().errorCode)

        val stoppedCreate = AuthorizedFileMutation.CreateFile(
            uuid(),
            downloadRoot.alias,
            "stopped.txt",
            "text/plain",
            "not written",
        )
        val stopped = repository.commitChanges(
            downloadGrant.grantId,
            listOf(stoppedCreate),
            stopRequested = { true },
        )
        assertEquals("cancelled", stopped.outcome)
        assertFalse(File(downloads, "stopped.txt").exists())

        ready = false
        assertTrue(repository.roots().isEmpty())
        assertThrows(SecurityException::class.java) {
            runBlocking { repository.metadata(downloadGrant.grantId) }
        }
        assertNull(File(temporary, "escape.txt").takeIf(File::exists))
    }

    @Test
    fun `metadata lookup of a missing public root never creates it`() {
        val missing = File(temporary, "Movies")
        val missingRepository = SharedStorageRepository(
            rootDirectories = mapOf("movies" to missing),
            accessReady = { true },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val grantId = missingRepository.roots().single().grantId

        assertThrows(SecurityException::class.java) {
            runBlocking { missingRepository.metadata(grantId) }
        }
        assertFalse(missing.exists())
    }

    private fun AuthorizedDocumentMetadata.precondition() = AuthorizedFilePrecondition(
        displayName = displayName,
        mimeType = mimeType,
        byteCount = byteCount,
        lastModifiedMillis = lastModifiedMillis,
    )

    private fun uuid(): String = UUID.randomUUID().toString()
}
