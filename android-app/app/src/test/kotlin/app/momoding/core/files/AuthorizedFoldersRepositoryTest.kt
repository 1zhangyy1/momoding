package app.momoding.core.files

import app.momoding.core.data.AuthorizedFolderEntity
import java.io.FileNotFoundException
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedFoldersRepositoryTest {
    @Test
    fun `authorization keeps raw URI local and projects opaque grant and document aliases`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)

        val authorized = repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        val listing = repository.metadata(GRANT_ID)

        assertEquals(GRANT_ID, authorized.grantId)
        assertEquals("Project files", authorized.displayName)
        assertEquals(AuthorizedFolderStatus.ACTIVE, authorized.status)
        assertFalse(authorized.toString().contains("content://"))
        assertEquals(TREE_URI, store.rows.getValue(GRANT_ID).treeUri)
        assertEquals(2, listing.documents.size)
        assertTrue(listing.documents.all { it.alias.startsWith("doc-") })
        assertTrue(listing.documents.none { it.alias.contains("provider-root") })
        assertEquals(listing.documents[0].alias, listing.documents[1].parentAlias)
        assertTrue(listing.documents.none { it.toString().contains("content://") })
    }

    @Test
    fun `selecting the same tree again retains one opaque grant identity`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)

        val first = repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        access.root = SafRootProbe("Renamed project", "provider.example")
        val second = repository.authorize(TREE_URI, READ_WRITE_FLAGS)

        assertEquals(first.grantId, second.grantId)
        assertEquals(1, store.rows.size)
        assertEquals("Renamed project", store.rows.getValue(GRANT_ID).displayName)
    }

    @Test
    fun `lost Android grant is reported and metadata fails closed`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        access.persisted = null

        val folders = repository.folders()

        assertEquals(AuthorizedFolderStatus.REAUTHORIZATION_REQUIRED, folders.single().status)
        assertFalse(folders.single().canRead)
        assertTrue(runCatching { repository.metadata(GRANT_ID) }.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `revoke releases Android permission before removing private mapping`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)

        repository.revoke(GRANT_ID)

        assertEquals(listOf(TREE_URI), access.released)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `newly acquired grant is released when durable registry write fails`() = runTest {
        val store = FakeStore(failUpsert = true)
        val access = FakeAccess()
        val repository = repository(store, access)

        assertTrue(
            runCatching {
                repository.authorize(TREE_URI, READ_WRITE_FLAGS)
            }.exceptionOrNull() is IllegalStateException,
        )
        assertEquals(listOf(TREE_URI), access.released)
        assertEquals(null, access.persisted)
    }

    @Test
    fun `provider disappearance never returns stale metadata`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        access.listFailure = FileNotFoundException("provider offline")

        assertTrue(
            runCatching { repository.metadata(GRANT_ID) }.exceptionOrNull() is FileNotFoundException,
        )
        assertEquals(
            AuthorizedFolderStatus.PROVIDER_UNAVAILABLE.name,
            store.rows.getValue(GRANT_ID).status,
        )
    }

    @Test
    fun `content read resolves an exact live alias and enforces MIME and byte budgets`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        val file = repository.metadata(GRANT_ID).documents.single { !it.isDirectory }

        val read = repository.readText(
            grantId = GRANT_ID,
            requests = listOf(
                AuthorizedDocumentReadRequest(
                    alias = file.alias,
                    expectedMimeType = "text/plain",
                    maxBytes = 32,
                ),
            ),
            totalMaxBytes = 32,
        )

        assertEquals("hello mobile", read.documents.single().content)
        assertEquals(12, read.totalBytes)
        assertFalse(read.toString().contains("content://"))
        assertFalse(read.toString().contains("provider-root"))

        val wrongMime = runCatching {
            repository.readText(
                GRANT_ID,
                listOf(AuthorizedDocumentReadRequest(file.alias, "application/json", 32)),
                32,
            )
        }.exceptionOrNull()
        assertTrue(wrongMime is IllegalArgumentException)

        val tooSmall = runCatching {
            repository.readText(
                GRANT_ID,
                listOf(AuthorizedDocumentReadRequest(file.alias, "text/plain", 4)),
                4,
            )
        }.exceptionOrNull()
        assertTrue(tooSmall is IllegalArgumentException)
    }

    @Test
    fun `content read blocks sensitive names binary files and malformed UTF8`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)
        val alias = repository.metadata(GRANT_ID).documents.single { !it.isDirectory }.alias

        access.fileDisplayName = ".env.local"
        assertTrue(
            runCatching {
                repository.readText(
                    GRANT_ID,
                    listOf(AuthorizedDocumentReadRequest(alias, "text/plain", 32)),
                    32,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        access.fileDisplayName = "archive.bin"
        access.fileMimeType = "application/octet-stream"
        assertTrue(
            runCatching {
                repository.readText(
                    GRANT_ID,
                    listOf(AuthorizedDocumentReadRequest(alias, "application/octet-stream", 32)),
                    32,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )

        access.fileDisplayName = "notes.txt"
        access.fileMimeType = "text/plain"
        access.content = byteArrayOf(0xC3.toByte(), 0x28)
        access.fileByteCount = access.content.size.toLong()
        assertTrue(
            runCatching {
                repository.readText(
                    GRANT_ID,
                    listOf(AuthorizedDocumentReadRequest(alias, "text/plain", 32)),
                    32,
                )
            }.exceptionOrNull() is CharacterCodingException,
        )
    }

    @Test
    fun `project snapshot imports bounded text with opaque aliases and stable digest`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)

        val first = repository.projectSnapshot(GRANT_ID)
        val second = repository.projectSnapshot(GRANT_ID)
        val file = first.entries.single { !it.isDirectory }

        assertEquals("private-plan.txt", file.relativePath)
        assertEquals("hello mobile", file.content!!.decodeToString())
        assertEquals(1, first.fileCount)
        assertEquals(12, first.totalBytes)
        assertEquals(first.manifestSha256, second.manifestSha256)
        assertTrue(first.manifestSha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.toString().contains("content://"))
        assertFalse(first.toString().contains("provider-root"))
    }

    @Test
    fun `project snapshot reports sensitive and binary exclusions without copying bytes`() = runTest {
        val store = FakeStore()
        val access = FakeAccess()
        val repository = repository(store, access)
        repository.authorize(TREE_URI, READ_WRITE_FLAGS)

        access.fileDisplayName = ".env.local"
        val sensitive = repository.projectSnapshot(GRANT_ID)
        assertEquals(0, sensitive.fileCount)
        assertEquals("SENSITIVE_NAME", sensitive.exclusions.single().reason)

        access.fileDisplayName = "notes.txt"
        access.content = byteArrayOf(0xC3.toByte(), 0x28)
        access.fileByteCount = access.content.size.toLong()
        val binary = repository.projectSnapshot(GRANT_ID)
        assertEquals(0, binary.fileCount)
        assertEquals("BINARY_OR_NON_UTF8", binary.exclusions.single().reason)
    }

    @Test
    fun `project snapshot excludes sensitive and generated directory trees before content read`() =
        runTest {
            val store = FakeStore()
            val access = BoundaryAccess(
                records = listOf(
                    directory("root", null, "Project", 0),
                    directory("root/secrets", "root", "secrets", 1),
                    textFile("root/secrets/token.txt", "root/secrets", "token.txt", 2, 6L),
                    directory("root/node_modules", "root", "node_modules", 1),
                    textFile("root/node_modules/pkg.js", "root/node_modules", "pkg.js", 2, 6L),
                    textFile("root/large.txt", "root", "large.txt", 1, 262_145L),
                    textFile("root/Main.kt", "root", "Main.kt", 1, 4L),
                ),
                contents = mapOf(
                    "root/secrets/token.txt" to "secret".encodeToByteArray(),
                    "root/node_modules/pkg.js" to "module".encodeToByteArray(),
                    "root/Main.kt" to "main".encodeToByteArray(),
                ),
            )
            val repository = repository(store, access)
            repository.authorize(TREE_URI, READ_WRITE_FLAGS)

            val snapshot = repository.projectSnapshot(GRANT_ID)

            assertEquals(listOf("Main.kt"), snapshot.entries.filterNot { it.isDirectory }.map { it.relativePath })
            assertEquals(listOf("root/Main.kt"), access.readDocumentIds)
            assertTrue(snapshot.exclusions.any {
                it.relativePath == "secrets/token.txt" && it.reason == "SENSITIVE_NAME"
            })
            assertTrue(snapshot.exclusions.any {
                it.relativePath == "node_modules/pkg.js" && it.reason == "GENERATED_OR_VCS_TREE"
            })
            assertTrue(snapshot.exclusions.any {
                it.relativePath == "large.txt" && it.reason == "FILE_TOO_LARGE"
            })
        }

    @Test
    fun `project snapshot rejects cyclic truncated and over-limit provider results`() = runTest {
        suspend fun failure(access: SafTreeAccess, block: suspend (AuthorizedFoldersRepository) -> Unit) {
            val store = FakeStore()
            val repository = repository(store, access)
            repository.authorize(TREE_URI, READ_WRITE_FLAGS)
            assertTrue(runCatching { block(repository) }.isFailure)
        }

        failure(
            BoundaryAccess(
                records = listOf(
                    directory("root", null, "Project", 0),
                    directory("cycle-a", "cycle-b", "a", 1),
                    directory("cycle-b", "cycle-a", "b", 2),
                ),
            ),
        ) { it.projectSnapshot(GRANT_ID) }

        failure(
            BoundaryAccess(
                records = listOf(directory("root", null, "Project", 0)),
                truncated = true,
            ),
        ) { it.projectSnapshot(GRANT_ID) }

        failure(
            BoundaryAccess(
                records = listOf(
                    directory("root", null, "Project", 0),
                    textFile("root/a.txt", "root", "a.txt", 1, 2L),
                    textFile("root/b.txt", "root", "b.txt", 1, 2L),
                ),
                contents = mapOf(
                    "root/a.txt" to "aa".encodeToByteArray(),
                    "root/b.txt" to "bb".encodeToByteArray(),
                ),
            ),
        ) { it.projectSnapshot(GRANT_ID, maxFiles = 1) }

        failure(
            BoundaryAccess(
                records = listOf(
                    directory("root", null, "Project", 0),
                    textFile("root/a.txt", "root", "a.txt", 1, null),
                ),
                contents = mapOf("root/a.txt" to ByteArray(262_145) { 'a'.code.toByte() }),
            ),
        ) { it.projectSnapshot(GRANT_ID) }

        failure(
            BoundaryAccess(
                records = listOf(
                    directory("root", null, "Project", 0),
                    textFile("root/a.txt", "root", "a.txt", 1, 2L),
                ),
                contents = mapOf("root/a.txt" to "aa".encodeToByteArray()),
            ),
        ) { it.projectSnapshot(GRANT_ID, maxTotalBytes = 1) }
    }

    private fun repository(
        store: FakeStore,
        access: SafTreeAccess,
    ) = AuthorizedFoldersRepository(
        store = store,
        access = access,
        ioDispatcher = Dispatchers.Unconfined,
        nowMillis = { 10L },
        newGrantId = { GRANT_ID },
    )

    private class FakeStore(
        private val failUpsert: Boolean = false,
    ) : AuthorizedFolderStore {
        val rows = linkedMapOf<String, AuthorizedFolderEntity>()

        override suspend fun list(): List<AuthorizedFolderEntity> = rows.values.toList()

        override suspend fun get(grantId: String): AuthorizedFolderEntity? = rows[grantId]

        override suspend fun findByTreeHash(treeUriSha256: String): AuthorizedFolderEntity? =
            rows.values.firstOrNull { it.treeUriSha256 == treeUriSha256 }

        override suspend fun upsert(entity: AuthorizedFolderEntity) {
            if (failUpsert) error("disk full")
            rows[entity.grantId] = entity
        }

        override suspend fun delete(grantId: String): Boolean = rows.remove(grantId) != null
    }

    private class FakeAccess : SafTreeAccess {
        var root = SafRootProbe("Project files", "provider.example")
        var persisted: PersistedGrant? = null
        var listFailure: FileNotFoundException? = null
        val released = mutableListOf<String>()
        var content = "hello mobile".encodeToByteArray()
        var fileDisplayName = "private-plan.txt"
        var fileMimeType = "text/plain"
        var fileByteCount: Long? = 12L

        override fun validateTreeUri(treeUri: String) {
            require(treeUri == TREE_URI)
        }

        override fun probeRoot(treeUri: String): SafRootProbe = root

        override fun persistedGrant(treeUri: String): PersistedGrant? = persisted

        override fun takePersistableGrant(treeUri: String, resultFlags: Int): PersistedGrant {
            require(resultFlags == READ_WRITE_FLAGS)
            return PersistedGrant(read = true, write = true).also { persisted = it }
        }

        override fun releasePersistableGrant(treeUri: String) {
            released += treeUri
            persisted = null
        }

        override suspend fun list(
            treeUri: String,
            maxDepth: Int,
            maxItems: Int,
        ): SafListingRecord {
            listFailure?.let { throw it }
            return SafListingRecord(
                documents = listOf(
                    SafDocumentRecord(
                        documentId = "provider-root",
                        parentDocumentId = null,
                        displayName = "Project files",
                        mimeType = "vnd.android.document/directory",
                        byteCount = null,
                        lastModifiedMillis = 1L,
                        depth = 0,
                    ),
                    SafDocumentRecord(
                        documentId = "provider-root/private-plan.txt",
                        parentDocumentId = "provider-root",
                        displayName = fileDisplayName,
                        mimeType = fileMimeType,
                        byteCount = fileByteCount,
                        lastModifiedMillis = 2L,
                        depth = 1,
                    ),
                ),
                truncated = false,
                truncationReasons = emptySet(),
            )
        }

        override suspend fun read(
            treeUri: String,
            documentId: String,
            maxBytes: Int,
        ): ByteArray {
            require(treeUri == TREE_URI)
            require(documentId == "provider-root/private-plan.txt")
            require(content.size <= maxBytes)
            return content.copyOf()
        }
    }

    private class BoundaryAccess(
        private val records: List<SafDocumentRecord>,
        private val contents: Map<String, ByteArray> = emptyMap(),
        private val truncated: Boolean = false,
    ) : SafTreeAccess {
        var persisted: PersistedGrant? = null
        val readDocumentIds = mutableListOf<String>()

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
        ) = SafListingRecord(
            documents = records,
            truncated = truncated,
            truncationReasons = if (truncated) setOf("ITEM_LIMIT") else emptySet(),
        )

        override suspend fun read(treeUri: String, documentId: String, maxBytes: Int): ByteArray {
            readDocumentIds += documentId
            return requireNotNull(contents[documentId]).copyOf()
        }
    }

    private companion object {
        const val TREE_URI = "content://provider.example/tree/provider-root"
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
        const val READ_WRITE_FLAGS = 3

        fun directory(
            id: String,
            parent: String?,
            name: String,
            depth: Int,
        ) = SafDocumentRecord(
            documentId = id,
            parentDocumentId = parent,
            displayName = name,
            mimeType = "vnd.android.document/directory",
            byteCount = null,
            lastModifiedMillis = depth.toLong(),
            depth = depth,
        )

        fun textFile(
            id: String,
            parent: String,
            name: String,
            depth: Int,
            byteCount: Long?,
        ) = SafDocumentRecord(
            documentId = id,
            parentDocumentId = parent,
            displayName = name,
            mimeType = "text/plain",
            byteCount = byteCount,
            lastModifiedMillis = depth.toLong(),
            depth = depth,
        )
    }
}
