package app.momoding.core.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import app.momoding.core.data.AuthorizedFolderEntity
import app.momoding.core.data.MomodingDatabase
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class AuthorizedFolderStatus {
    ACTIVE,
    READ_ONLY,
    REAUTHORIZATION_REQUIRED,
    PROVIDER_UNAVAILABLE,
}

data class AuthorizedFolderSummary(
    val grantId: String,
    val displayName: String,
    val authorityLabel: String,
    val status: AuthorizedFolderStatus,
    val canRead: Boolean,
    val canWrite: Boolean,
)

data class AuthorizedDocumentMetadata(
    val alias: String,
    val parentAlias: String?,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long?,
    val lastModifiedMillis: Long?,
    val depth: Int,
    val providerFlags: Long = 0,
) {
    val isDirectory: Boolean
        get() = mimeType == Document.MIME_TYPE_DIR
}

data class AuthorizedFolderListing(
    val grantId: String,
    val documents: List<AuthorizedDocumentMetadata>,
    val truncated: Boolean,
    val truncationReasons: Set<String>,
)

data class AuthorizedDocumentReadRequest(
    val alias: String,
    val expectedMimeType: String,
    val maxBytes: Int,
)

data class AuthorizedDocumentContent(
    val alias: String,
    val mimeType: String,
    val byteCount: Int,
    val content: String,
)

data class AuthorizedFolderContentRead(
    val grantId: String,
    val documents: List<AuthorizedDocumentContent>,
    val totalBytes: Int,
)

data class AuthorizedProjectSnapshotEntry(
    val alias: String,
    val parentAlias: String?,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long?,
    val lastModifiedMillis: Long?,
    val sha256: String?,
    val content: ByteArray?,
) {
    val isDirectory: Boolean
        get() = mimeType == Document.MIME_TYPE_DIR
}

data class AuthorizedProjectSnapshotExclusion(
    val relativePath: String,
    val reason: String,
)

data class AuthorizedProjectSnapshot(
    val grantId: String,
    val entries: List<AuthorizedProjectSnapshotEntry>,
    val exclusions: List<AuthorizedProjectSnapshotExclusion>,
    val fileCount: Int,
    val totalBytes: Int,
    val manifestSha256: String,
)

data class AuthorizedFilePrecondition(
    val displayName: String,
    val mimeType: String,
    val byteCount: Long?,
    val lastModifiedMillis: Long?,
)

sealed interface AuthorizedFileMutation {
    val operationId: String

    data class CreateFile(
        override val operationId: String,
        val parentAlias: String,
        val displayName: String,
        val mimeType: String,
        val content: String,
    ) : AuthorizedFileMutation

    data class CreateDirectory(
        override val operationId: String,
        val parentAlias: String,
        val displayName: String,
    ) : AuthorizedFileMutation

    data class Rename(
        override val operationId: String,
        val sourceAlias: String,
        val displayName: String,
        val expected: AuthorizedFilePrecondition,
    ) : AuthorizedFileMutation

    data class Move(
        override val operationId: String,
        val sourceAlias: String,
        val targetParentAlias: String,
        val expected: AuthorizedFilePrecondition,
    ) : AuthorizedFileMutation

    data class WriteFile(
        override val operationId: String,
        val sourceAlias: String,
        val mimeType: String,
        val content: String,
        val expected: AuthorizedFilePrecondition,
    ) : AuthorizedFileMutation

    data class DeleteFile(
        override val operationId: String,
        val sourceAlias: String,
        val expected: AuthorizedFilePrecondition,
    ) : AuthorizedFileMutation
}

data class AuthorizedFileMutationResult(
    val operationId: String,
    val kind: String,
    val state: String,
    val resultAlias: String? = null,
    val errorCode: String? = null,
)

data class AuthorizedFileCommitResult(
    val outcome: String,
    val appliedCount: Int,
    val results: List<AuthorizedFileMutationResult>,
)

/**
 * One shared policy for both the consent surface and the actual SAF read.
 *
 * The UI may describe a file as approvable only when the live Provider metadata would also pass
 * the executor's non-content checks. The executor repeats every check immediately before reading.
 */
internal object AuthorizedContentReadPolicy {
    const val MAX_CONTENT_DOCUMENTS = 16
    const val MAX_PER_FILE_CONTENT_BYTES = 262_144
    const val MAX_TOTAL_CONTENT_BYTES = 524_288
    const val MAX_CONTENT_SCAN_DEPTH = 32
    const val MAX_CONTENT_SCAN_ITEMS = 2_000

    val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
    private val SENSITIVE_FILE_NAME = Regex(
        "(^|[._-])(env|credentials?|secrets?|private[_-]?key|id_rsa|id_ed25519)([._-]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun validateDocument(
        alias: String,
        displayName: String,
        actualMimeType: String,
        byteCount: Long?,
        expectedMimeType: String,
        maxBytes: Int,
    ) {
        require(DOCUMENT_ALIAS.matches(alias)) { "Document alias is invalid" }
        require(maxBytes in 1..MAX_PER_FILE_CONTENT_BYTES) {
            "Per-file content byte limit is outside policy"
        }
        require(actualMimeType != Document.MIME_TYPE_DIR) { "Directories cannot be read" }
        require(actualMimeType == expectedMimeType) {
            "Document MIME type changed after metadata listing"
        }
        require(isAllowedTextMime(actualMimeType)) { "Document MIME type is not readable text" }
        require(!SENSITIVE_FILE_NAME.containsMatchIn(displayName)) {
            "Sensitive file names cannot be approved for content read"
        }
        byteCount?.let { knownSize ->
            require(knownSize in 0..maxBytes.toLong()) {
                "Document exceeds the approved byte limit"
            }
        }
    }

    fun isApprovable(
        metadata: AuthorizedDocumentMetadata,
        expectedMimeType: String,
        maxBytes: Int,
    ): Boolean = runCatching {
        validateDocument(
            alias = metadata.alias,
            displayName = metadata.displayName,
            actualMimeType = metadata.mimeType,
            byteCount = metadata.byteCount,
            expectedMimeType = expectedMimeType,
            maxBytes = maxBytes,
        )
    }.isSuccess

    fun isAllowedTextMime(mimeType: String): Boolean =
        mimeType.startsWith("text/") ||
            mimeType in setOf(
                "application/json",
                "application/ld+json",
                "application/xml",
                "application/x-yaml",
                "application/yaml",
            ) ||
            mimeType.endsWith("+json") ||
            mimeType.endsWith("+xml")

    fun isSensitiveFileName(displayName: String): Boolean =
        SENSITIVE_FILE_NAME.containsMatchIn(displayName)
}

internal data class SafRootProbe(
    val displayName: String,
    val authority: String,
)

internal data class SafDocumentRecord(
    val documentId: String,
    val parentDocumentId: String?,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long?,
    val lastModifiedMillis: Long?,
    val depth: Int,
    val flags: Long = 0,
)

internal data class SafListingRecord(
    val documents: List<SafDocumentRecord>,
    val truncated: Boolean,
    val truncationReasons: Set<String>,
)

internal data class PersistedGrant(
    val read: Boolean,
    val write: Boolean,
)

internal interface AuthorizedFolderStore {
    suspend fun list(): List<AuthorizedFolderEntity>
    suspend fun get(grantId: String): AuthorizedFolderEntity?
    suspend fun findByTreeHash(treeUriSha256: String): AuthorizedFolderEntity?
    suspend fun upsert(entity: AuthorizedFolderEntity)
    suspend fun delete(grantId: String): Boolean
}

internal class RoomAuthorizedFolderStore(
    database: MomodingDatabase,
) : AuthorizedFolderStore {
    private val dao = database.p2Dao()

    override suspend fun list(): List<AuthorizedFolderEntity> = dao.authorizedFolders()

    override suspend fun get(grantId: String): AuthorizedFolderEntity? =
        dao.authorizedFolder(grantId)

    override suspend fun findByTreeHash(treeUriSha256: String): AuthorizedFolderEntity? =
        dao.authorizedFolderByTreeHash(treeUriSha256)

    override suspend fun upsert(entity: AuthorizedFolderEntity) =
        dao.upsertAuthorizedFolder(entity)

    override suspend fun delete(grantId: String): Boolean =
        dao.revokeAuthorizedFolderAndContentGrants(grantId, System.currentTimeMillis())
}

internal interface SafTreeAccess {
    fun validateTreeUri(treeUri: String)
    fun probeRoot(treeUri: String): SafRootProbe
    fun persistedGrant(treeUri: String): PersistedGrant?
    fun takePersistableGrant(treeUri: String, resultFlags: Int): PersistedGrant
    fun releasePersistableGrant(treeUri: String)
    suspend fun list(treeUri: String, maxDepth: Int, maxItems: Int): SafListingRecord
    suspend fun read(treeUri: String, documentId: String, maxBytes: Int): ByteArray =
        throw UnsupportedOperationException("Content read is unavailable")
    suspend fun create(
        treeUri: String,
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String = throw UnsupportedOperationException("Document create is unavailable")
    suspend fun writeNew(treeUri: String, documentId: String, bytes: ByteArray): Unit =
        throw UnsupportedOperationException("Document write is unavailable")
    suspend fun writeExisting(treeUri: String, documentId: String, bytes: ByteArray): Unit =
        throw UnsupportedOperationException("Existing document write is unavailable")
    suspend fun rename(treeUri: String, documentId: String, displayName: String): String =
        throw UnsupportedOperationException("Document rename is unavailable")
    suspend fun move(
        treeUri: String,
        documentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String = throw UnsupportedOperationException("Document move is unavailable")
    suspend fun deleteNew(treeUri: String, documentId: String): Unit =
        throw UnsupportedOperationException("Document cleanup is unavailable")
    suspend fun deleteExisting(treeUri: String, documentId: String): Unit =
        throw UnsupportedOperationException("Document delete is unavailable")
}

internal class AndroidSafTreeAccess(
    context: Context,
) : SafTreeAccess {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun validateTreeUri(treeUri: String) {
        val uri = treeUri.toUri()
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Selected folder is not a content URI" }
        require(DocumentsContract.isTreeUri(uri)) { "Selected folder is not a document tree" }
        require(!uri.authority.isNullOrBlank()) { "Selected folder has no Provider authority" }
        DocumentsContract.getTreeDocumentId(uri)
    }

    override fun probeRoot(treeUri: String): SafRootProbe {
        val tree = treeUri.toUri()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        resolver.query(root, ROOT_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException("Selected folder is unavailable")
            val mimeType = cursor.requiredString(Document.COLUMN_MIME_TYPE)
            require(mimeType == Document.MIME_TYPE_DIR) { "Selected URI is not a directory" }
            return SafRootProbe(
                displayName = cursor.requiredString(Document.COLUMN_DISPLAY_NAME)
                    .safeDisplayName()
                    .ifBlank { "Authorized folder" },
                authority = requireNotNull(tree.authority),
            )
        }
        throw FileNotFoundException("Provider returned no root cursor")
    }

    override fun persistedGrant(treeUri: String): PersistedGrant? {
        val uri = treeUri.toUri()
        return resolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
            PersistedGrant(read = it.isReadPermission, write = it.isWritePermission)
        }
    }

    override fun takePersistableGrant(treeUri: String, resultFlags: Int): PersistedGrant {
        val uri = treeUri.toUri()
        val modeFlags = resultFlags and MODE_FLAGS
        require(modeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "Folder selection did not grant read access"
        }
        resolver.takePersistableUriPermission(uri, modeFlags)
        return persistedGrant(treeUri)
            ?: throw SecurityException("Android did not persist the selected folder")
    }

    override fun releasePersistableGrant(treeUri: String) {
        val uri = treeUri.toUri()
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) resolver.releasePersistableUriPermission(uri, flags)
    }

    override suspend fun list(
        treeUri: String,
        maxDepth: Int,
        maxItems: Int,
    ): SafListingRecord {
        require(maxDepth in 0..MAX_DEPTH) { "Metadata depth is outside the supported range" }
        require(maxItems in 1..MAX_ITEMS) { "Metadata item limit is outside the supported range" }
        val tree = treeUri.toUri()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val documents = mutableListOf<SafDocumentRecord>()
        val truncation = linkedSetOf<String>()

        suspend fun visit(documentUri: Uri, parentId: String?, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > maxDepth) {
                truncation += "DEPTH_LIMIT"
                return
            }
            if (documents.size >= maxItems) {
                truncation += "ITEM_LIMIT"
                return
            }
            val current = queryDocument(documentUri, parentId, depth)
            documents += current
            if (current.mimeType != Document.MIME_TYPE_DIR) return

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                current.documentId,
            )
            resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    if (documents.size >= maxItems) {
                        truncation += "ITEM_LIMIT"
                        break
                    }
                    val childId = cursor.requiredString(Document.COLUMN_DOCUMENT_ID)
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    visit(child, current.documentId, depth + 1)
                }
            } ?: throw FileNotFoundException("Provider returned no child cursor")
        }

        visit(root, null, 0)
        return SafListingRecord(
            documents = documents,
            truncated = truncation.isNotEmpty(),
            truncationReasons = truncation,
        )
    }

    override suspend fun read(
        treeUri: String,
        documentId: String,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes in 1..MAX_CONTENT_BYTES) { "Content byte limit is outside policy" }
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openInputStream(document)?.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1_024))
            val buffer = ByteArray(8 * 1_024)
            while (output.size() <= maxBytes) {
                coroutineContext.ensureActive()
                val remaining = maxBytes + 1 - output.size()
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= maxBytes) { "Document exceeds the approved byte limit" }
            return bytes
        }
        throw FileNotFoundException("Provider returned no document stream")
    }

    override suspend fun create(
        treeUri: String,
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocumentId)
        val created = DocumentsContract.createDocument(
            resolver,
            parent,
            mimeType,
            displayName,
        ) ?: throw FileNotFoundException("Provider did not create the document")
        require(created.authority == tree.authority) { "Provider changed during create" }
        return DocumentsContract.getDocumentId(created)
    }

    override suspend fun writeNew(treeUri: String, documentId: String, bytes: ByteArray) {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openOutputStream(document, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw FileNotFoundException("Provider returned no document output stream")
    }

    override suspend fun writeExisting(treeUri: String, documentId: String, bytes: ByteArray) {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openOutputStream(document, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw FileNotFoundException("Provider returned no document output stream")
    }

    override suspend fun rename(
        treeUri: String,
        documentId: String,
        displayName: String,
    ): String {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val renamed = DocumentsContract.renameDocument(
            resolver,
            document,
            displayName,
        ) ?: throw FileNotFoundException("Provider did not rename the document")
        require(renamed.authority == tree.authority) { "Provider changed during rename" }
        return DocumentsContract.getDocumentId(renamed)
    }

    override suspend fun move(
        treeUri: String,
        documentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val sourceParent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            sourceParentDocumentId,
        )
        val targetParent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            targetParentDocumentId,
        )
        val moved = DocumentsContract.moveDocument(
            resolver,
            document,
            sourceParent,
            targetParent,
        ) ?: throw FileNotFoundException("Provider did not move the document")
        require(moved.authority == tree.authority) { "Provider changed during move" }
        return DocumentsContract.getDocumentId(moved)
    }

    override suspend fun deleteNew(treeUri: String, documentId: String) {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        check(DocumentsContract.deleteDocument(resolver, document)) {
            "Provider did not remove the failed new document"
        }
    }

    override suspend fun deleteExisting(treeUri: String, documentId: String) {
        coroutineContext.ensureActive()
        val tree = treeUri.toUri()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        check(DocumentsContract.deleteDocument(resolver, document)) {
            "Provider did not delete the document"
        }
    }

    private fun queryDocument(documentUri: Uri, parentId: String?, depth: Int): SafDocumentRecord {
        resolver.query(documentUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException("Document cursor was empty")
            return cursor.toRecord(parentId, depth)
        }
        throw FileNotFoundException("Provider returned no document cursor")
    }

    private fun Cursor.toRecord(parentId: String?, depth: Int): SafDocumentRecord =
        SafDocumentRecord(
            documentId = requiredString(Document.COLUMN_DOCUMENT_ID),
            parentDocumentId = parentId,
            displayName = requiredString(Document.COLUMN_DISPLAY_NAME).safeDisplayName(),
            mimeType = requiredString(Document.COLUMN_MIME_TYPE),
            byteCount = optionalLong(Document.COLUMN_SIZE),
            lastModifiedMillis = optionalLong(Document.COLUMN_LAST_MODIFIED),
            depth = depth,
            flags = optionalLong(Document.COLUMN_FLAGS) ?: 0,
        )

    private fun Cursor.requiredString(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun Cursor.optionalLong(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun String.safeDisplayName(): String =
        replace(CONTROL_CHARACTERS, "\uFFFD").take(MAX_DISPLAY_NAME_LENGTH)

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ITEMS = 2_000
        const val MAX_CONTENT_BYTES = 262_144
        const val MAX_DISPLAY_NAME_LENGTH = 240
        const val MODE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        val ROOT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

class AuthorizedFoldersRepository internal constructor(
    private val store: AuthorizedFolderStore,
    private val access: SafTreeAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newGrantId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(
        context: Context,
        database: MomodingDatabase,
    ) : this(
        store = RoomAuthorizedFolderStore(database),
        access = AndroidSafTreeAccess(context),
    )

    suspend fun authorize(treeUri: String, resultFlags: Int): AuthorizedFolderSummary =
        withContext(ioDispatcher) {
            access.validateTreeUri(treeUri)
            val root = access.probeRoot(treeUri)
            val uriHash = sha256(treeUri)
            val existing = store.findByTreeHash(uriHash)
            val before = access.persistedGrant(treeUri)
            val persisted = access.takePersistableGrant(treeUri, resultFlags)
            check(persisted.read) { "Selected folder is not readable" }
            val now = nowMillis()
            val entity = AuthorizedFolderEntity(
                grantId = existing?.grantId ?: newGrantId().also(::requireOpaqueGrantId),
                treeUri = treeUri,
                treeUriSha256 = uriHash,
                displayName = root.displayName,
                authority = root.authority,
                persistedRead = persisted.read,
                persistedWrite = persisted.write,
                status = status(persisted).name,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            try {
                store.upsert(entity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (before == null) runCatching { access.releasePersistableGrant(treeUri) }
                throw failure
            }
            entity.toSummary(persisted)
        }

    suspend fun folders(): List<AuthorizedFolderSummary> = withContext(ioDispatcher) {
        store.list().map { entity ->
            val persisted = runCatching { access.persistedGrant(entity.treeUri) }.getOrNull()
            val currentStatus = when {
                persisted == null -> AuthorizedFolderStatus.REAUTHORIZATION_REQUIRED
                !persisted.read -> AuthorizedFolderStatus.REAUTHORIZATION_REQUIRED
                runCatching { access.probeRoot(entity.treeUri) }.isFailure ->
                    AuthorizedFolderStatus.PROVIDER_UNAVAILABLE
                else -> status(persisted)
            }
            if (
                entity.status != currentStatus.name ||
                entity.persistedRead != (persisted?.read == true) ||
                entity.persistedWrite != (persisted?.write == true)
            ) {
                store.upsert(
                    entity.copy(
                        persistedRead = persisted?.read == true,
                        persistedWrite = persisted?.write == true,
                        status = currentStatus.name,
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
            entity.copy(status = currentStatus.name).toSummary(persisted)
        }
    }

    suspend fun metadata(
        grantId: String,
        maxDepth: Int = 8,
        maxItems: Int = 200,
    ): AuthorizedFolderListing = withContext(ioDispatcher) {
        requireOpaqueGrantId(grantId)
        val entity = store.get(grantId) ?: throw SecurityException("Authorized folder is unavailable")
        val persisted = access.persistedGrant(entity.treeUri)
            ?: throw SecurityException("Folder access must be authorized again")
        check(persisted.read) { "Folder read access is unavailable" }
        val listing = try {
            access.list(entity.treeUri, maxDepth, maxItems)
        } catch (failure: FileNotFoundException) {
            store.upsert(
                entity.copy(
                    status = AuthorizedFolderStatus.PROVIDER_UNAVAILABLE.name,
                    updatedAtMillis = nowMillis(),
                ),
            )
            throw failure
        }
        AuthorizedFolderListing(
            grantId = entity.grantId,
            documents = listing.documents.map { document ->
                AuthorizedDocumentMetadata(
                    alias = documentAlias(entity.grantId, document.documentId),
                    parentAlias = document.parentDocumentId?.let {
                        documentAlias(entity.grantId, it)
                    },
                    displayName = document.displayName,
                    mimeType = document.mimeType,
                    byteCount = document.byteCount,
                    lastModifiedMillis = document.lastModifiedMillis,
                    depth = document.depth,
                    providerFlags = document.flags,
                )
            },
            truncated = listing.truncated,
            truncationReasons = listing.truncationReasons,
        )
    }

    /**
     * Reads only exact aliases from one persisted SAF tree after caller-side user consent.
     *
     * Alias resolution is repeated against the live Provider tree for every call. A stale alias,
     * moved document, MIME change, sensitive name, binary type, invalid UTF-8, or byte overrun
     * fails closed before any content is returned to the Host.
     */
    suspend fun readText(
        grantId: String,
        requests: List<AuthorizedDocumentReadRequest>,
        totalMaxBytes: Int,
    ): AuthorizedFolderContentRead = withContext(ioDispatcher) {
        requireOpaqueGrantId(grantId)
        require(requests.size in 1..AuthorizedContentReadPolicy.MAX_CONTENT_DOCUMENTS) {
            "Content request must contain 1-${AuthorizedContentReadPolicy.MAX_CONTENT_DOCUMENTS} documents"
        }
        require(requests.map { it.alias }.toSet().size == requests.size) {
            "Content request repeats a document alias"
        }
        require(totalMaxBytes in 1..AuthorizedContentReadPolicy.MAX_TOTAL_CONTENT_BYTES) {
            "Total content byte limit is outside policy"
        }
        require(requests.sumOf { it.maxBytes.toLong() } >= 1L) {
            "Content request has no byte budget"
        }
        val entity = store.get(grantId)
            ?: throw SecurityException("Authorized folder is unavailable")
        val persisted = access.persistedGrant(entity.treeUri)
            ?: throw SecurityException("Folder access must be authorized again")
        check(persisted.read) { "Folder read access is unavailable" }
        val listing = access.list(
            entity.treeUri,
            maxDepth = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_DEPTH,
            maxItems = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_ITEMS,
        )
        require(!listing.truncated) { "Authorized folder is too large for exact alias resolution" }
        val recordsByAlias = listing.documents.associateBy { document ->
            documentAlias(entity.grantId, document.documentId)
        }
        var totalBytes = 0
        val documents = requests.map { request ->
            val record = recordsByAlias[request.alias]
                ?: throw SecurityException("Document alias is outside the authorized folder")
            AuthorizedContentReadPolicy.validateDocument(
                alias = request.alias,
                displayName = record.displayName,
                actualMimeType = record.mimeType,
                byteCount = record.byteCount,
                expectedMimeType = request.expectedMimeType,
                maxBytes = request.maxBytes,
            )
            val bytes = access.read(entity.treeUri, record.documentId, request.maxBytes)
            totalBytes += bytes.size
            require(totalBytes <= totalMaxBytes) { "Documents exceed the total approved byte limit" }
            AuthorizedDocumentContent(
                alias = request.alias,
                mimeType = record.mimeType,
                byteCount = bytes.size,
                content = decodeStrictUtf8(bytes),
            )
        }
        AuthorizedFolderContentRead(
            grantId = grantId,
            documents = documents,
            totalBytes = totalBytes,
        )
    }

    /**
     * Reads a bounded, text-only project snapshot after the Android product surface has made the
     * task-scoped project import explicit to the user.
     *
     * This method never returns a SAF URI or Provider document id. Generated dependency trees,
     * sensitive names, binary/non-UTF-8 files and unsupported MIME types are reported as
     * exclusions instead of being copied into the command workspace.
     */
    suspend fun projectSnapshot(
        grantId: String,
        maxFiles: Int = MAX_PROJECT_FILES,
        maxTotalBytes: Int = MAX_PROJECT_TOTAL_BYTES,
    ): AuthorizedProjectSnapshot = withContext(ioDispatcher) {
        requireOpaqueGrantId(grantId)
        require(maxFiles in 1..MAX_PROJECT_FILES) { "Project file limit is outside policy" }
        require(maxTotalBytes in 1..MAX_PROJECT_TOTAL_BYTES) {
            "Project byte limit is outside policy"
        }
        val entity = store.get(grantId)
            ?: throw SecurityException("Authorized folder is unavailable")
        val persisted = access.persistedGrant(entity.treeUri)
            ?: throw SecurityException("Folder access must be authorized again")
        check(persisted.read) { "Folder read access is unavailable" }
        val listing = access.list(
            entity.treeUri,
            maxDepth = MAX_PROJECT_SCAN_DEPTH,
            maxItems = MAX_PROJECT_SCAN_ITEMS,
        )
        require(!listing.truncated) { "Authorized project is too large for an exact snapshot" }
        val recordsById = listing.documents.associateBy(SafDocumentRecord::documentId)
        require(recordsById.size == listing.documents.size) {
            "Provider returned duplicate project document ids"
        }
        val pathsById = mutableMapOf<String, String>()
        val resolvingPathIds = mutableSetOf<String>()
        fun relativePath(record: SafDocumentRecord): String {
            pathsById[record.documentId]?.let { return it }
            require(resolvingPathIds.add(record.documentId)) {
                "Provider returned a cyclic project parent chain"
            }
            return try {
                val path = if (record.parentDocumentId == null) {
                    ""
                } else {
                    validateProjectPathSegment(record.displayName)
                    val parent = requireNotNull(recordsById[record.parentDocumentId]) {
                        "Provider returned an orphan project document"
                    }
                    listOf(relativePath(parent), record.displayName)
                        .filter(String::isNotEmpty)
                        .joinToString("/")
                }
                pathsById[record.documentId] = path
                path
            } finally {
                resolvingPathIds.remove(record.documentId)
            }
        }
        listing.documents.forEach(::relativePath)
        require(pathsById.values.toSet().size == pathsById.size) {
            "Provider returned duplicate project paths"
        }

        val exclusions = mutableListOf<AuthorizedProjectSnapshotExclusion>()
        val entries = mutableListOf<AuthorizedProjectSnapshotEntry>()
        var fileCount = 0
        var totalBytes = 0
        listing.documents.sortedWith(
            compareBy<SafDocumentRecord>({ it.depth }, { relativePath(it) }),
        ).forEach { record ->
            coroutineContext.ensureActive()
            val path = relativePath(record)
            val segments = path.split('/').filter(String::isNotEmpty)
            val excludedTree = segments.firstOrNull { segment ->
                segment.lowercase() in PROJECT_EXCLUDED_DIRECTORY_NAMES
            }
            if (excludedTree != null) {
                exclusions += AuthorizedProjectSnapshotExclusion(path, "GENERATED_OR_VCS_TREE")
                return@forEach
            }
            val sensitiveTree = segments.firstOrNull(
                AuthorizedContentReadPolicy::isSensitiveFileName,
            )
            if (sensitiveTree != null) {
                exclusions += AuthorizedProjectSnapshotExclusion(path, "SENSITIVE_NAME")
                return@forEach
            }
            val alias = documentAlias(entity.grantId, record.documentId)
            val parentAlias = record.parentDocumentId?.let { parentId ->
                documentAlias(entity.grantId, parentId)
            }
            if (record.mimeType == Document.MIME_TYPE_DIR) {
                entries += AuthorizedProjectSnapshotEntry(
                    alias = alias,
                    parentAlias = parentAlias,
                    relativePath = path,
                    displayName = record.displayName,
                    mimeType = record.mimeType,
                    byteCount = record.byteCount,
                    lastModifiedMillis = record.lastModifiedMillis,
                    sha256 = null,
                    content = null,
                )
                return@forEach
            }
            val exclusionReason = when {
                !AuthorizedContentReadPolicy.isAllowedTextMime(record.mimeType) ->
                    "UNSUPPORTED_MIME"
                record.byteCount != null && record.byteCount > MAX_PROJECT_FILE_BYTES ->
                    "FILE_TOO_LARGE"
                else -> null
            }
            if (exclusionReason != null) {
                exclusions += AuthorizedProjectSnapshotExclusion(path, exclusionReason)
                return@forEach
            }
            require(fileCount < maxFiles) { "Authorized project exceeds the approved file limit" }
            val bytes = access.read(entity.treeUri, record.documentId, MAX_PROJECT_FILE_BYTES)
            require(bytes.size <= MAX_PROJECT_FILE_BYTES) {
                "Provider returned a project file beyond the approved byte limit"
            }
            val text = runCatching { decodeStrictUtf8(bytes) }.getOrNull()
            if (text == null || '\u0000' in text) {
                exclusions += AuthorizedProjectSnapshotExclusion(path, "BINARY_OR_NON_UTF8")
                return@forEach
            }
            require(totalBytes + bytes.size <= maxTotalBytes) {
                "Authorized project exceeds the approved total byte limit"
            }
            fileCount += 1
            totalBytes += bytes.size
            entries += AuthorizedProjectSnapshotEntry(
                alias = alias,
                parentAlias = parentAlias,
                relativePath = path,
                displayName = record.displayName,
                mimeType = record.mimeType,
                byteCount = record.byteCount,
                lastModifiedMillis = record.lastModifiedMillis,
                sha256 = sha256(bytes),
                content = bytes,
            )
        }
        val canonicalManifest = entries.sortedBy(AuthorizedProjectSnapshotEntry::relativePath)
            .joinToString("\n") { entry ->
                listOf(
                    entry.relativePath,
                    entry.alias,
                    entry.parentAlias.orEmpty(),
                    entry.mimeType,
                    entry.byteCount?.toString().orEmpty(),
                    entry.lastModifiedMillis?.toString().orEmpty(),
                    entry.sha256.orEmpty(),
                ).joinToString("\u0000")
            }
        AuthorizedProjectSnapshot(
            grantId = grantId,
            entries = entries,
            exclusions = exclusions.sortedBy(AuthorizedProjectSnapshotExclusion::relativePath),
            fileCount = fileCount,
            totalBytes = totalBytes,
            manifestSha256 = sha256(canonicalManifest),
        )
    }

    suspend fun mutationMetadata(
        grantId: String,
    ): AuthorizedFolderListing = withContext(ioDispatcher) {
        val (entity, listing) = writableListing(grantId)
        AuthorizedFolderListing(
            grantId = entity.grantId,
            documents = listing.documents.map { document ->
                AuthorizedDocumentMetadata(
                    alias = documentAlias(entity.grantId, document.documentId),
                    parentAlias = document.parentDocumentId?.let {
                        documentAlias(entity.grantId, it)
                    },
                    displayName = document.displayName,
                    mimeType = document.mimeType,
                    byteCount = document.byteCount,
                    lastModifiedMillis = document.lastModifiedMillis,
                    depth = document.depth,
                    providerFlags = document.flags,
                )
            },
            truncated = listing.truncated,
            truncationReasons = listing.truncationReasons,
        )
    }

    suspend fun validateChanges(
        grantId: String,
        mutations: List<AuthorizedFileMutation>,
    ) = withContext(ioDispatcher) {
        require(mutations.size in 1..MAX_CHANGE_OPERATIONS) {
            "File plan must contain 1-$MAX_CHANGE_OPERATIONS operations"
        }
        require(mutations.map { it.operationId }.toSet().size == mutations.size) {
            "File plan repeats an operationId"
        }
        val changedSources = mutations.mapNotNull {
            when (it) {
                is AuthorizedFileMutation.Rename -> it.sourceAlias
                is AuthorizedFileMutation.Move -> it.sourceAlias
                is AuthorizedFileMutation.WriteFile -> it.sourceAlias
                is AuthorizedFileMutation.DeleteFile -> it.sourceAlias
                else -> null
            }
        }
        require(changedSources.toSet().size == changedSources.size) {
            "A source may be changed only once per plan"
        }
        val (_, listing) = writableListing(grantId)
        val entity = store.get(grantId)
            ?: throw SecurityException("Authorized folder is unavailable")
        val records = listing.documents.associateBy {
            documentAlias(entity.grantId, it.documentId)
        }
        mutations.forEach { validateMutation(it, records) }
    }

    /**
     * Executes a locally approved plan. Every item is re-resolved against the live SAF tree before
     * its first Provider call. The first failure stops the remaining items; no failed/unknown item
     * is replayed by this method.
     */
    suspend fun commitChanges(
        grantId: String,
        mutations: List<AuthorizedFileMutation>,
        stopRequested: () -> Boolean = { false },
    ): AuthorizedFileCommitResult = withContext(ioDispatcher) {
        require(mutations.size in 1..MAX_CHANGE_OPERATIONS) {
            "File plan must contain 1-$MAX_CHANGE_OPERATIONS operations"
        }
        require(mutations.map { it.operationId }.toSet().size == mutations.size) {
            "File plan repeats an operationId"
        }
        val results = mutableListOf<AuthorizedFileMutationResult>()
        var stopped = false
        mutations.forEachIndexed { index, mutation ->
            var providerCallStarted = false
            if (stopped || stopRequested()) {
                stopped = true
                results += AuthorizedFileMutationResult(
                    operationId = mutation.operationId,
                    kind = mutation.kindName(),
                    state = "cancelled",
                    errorCode = "FILE_COMMIT_CANCELLED",
                )
                return@forEachIndexed
            }
            try {
                val (entity, listing) = writableListing(grantId)
                val records = listing.documents.associateBy {
                    documentAlias(entity.grantId, it.documentId)
                }
                validateMutation(mutation, records)
                val resultAlias = when (mutation) {
                    is AuthorizedFileMutation.CreateFile -> {
                        val parent = requireNotNull(records[mutation.parentAlias])
                        providerCallStarted = true
                        val created = access.create(
                            entity.treeUri,
                            parent.documentId,
                            mutation.mimeType,
                            mutation.displayName,
                        )
                        try {
                            access.writeNew(
                                entity.treeUri,
                                created,
                                mutation.content.toByteArray(Charsets.UTF_8),
                            )
                        } catch (failure: Throwable) {
                            if (runCatching {
                                    access.deleteNew(entity.treeUri, created)
                                }.isSuccess
                            ) {
                                providerCallStarted = false
                            }
                            throw failure
                        }
                        documentAlias(entity.grantId, created)
                    }
                    is AuthorizedFileMutation.CreateDirectory -> {
                        val parent = requireNotNull(records[mutation.parentAlias])
                        providerCallStarted = true
                        val created = access.create(
                            entity.treeUri,
                            parent.documentId,
                            Document.MIME_TYPE_DIR,
                            mutation.displayName,
                        )
                        documentAlias(entity.grantId, created)
                    }
                    is AuthorizedFileMutation.Rename -> {
                        val source = requireNotNull(records[mutation.sourceAlias])
                        providerCallStarted = true
                        val renamed = access.rename(
                            entity.treeUri,
                            source.documentId,
                            mutation.displayName,
                        )
                        documentAlias(entity.grantId, renamed)
                    }
                    is AuthorizedFileMutation.Move -> {
                        val source = requireNotNull(records[mutation.sourceAlias])
                        val target = requireNotNull(records[mutation.targetParentAlias])
                        providerCallStarted = true
                        val moved = access.move(
                            entity.treeUri,
                            source.documentId,
                            requireNotNull(source.parentDocumentId),
                            target.documentId,
                        )
                        documentAlias(entity.grantId, moved)
                    }
                    is AuthorizedFileMutation.WriteFile -> {
                        val source = requireNotNull(records[mutation.sourceAlias])
                        providerCallStarted = true
                        access.writeExisting(
                            entity.treeUri,
                            source.documentId,
                            mutation.content.toByteArray(Charsets.UTF_8),
                        )
                        mutation.sourceAlias
                    }
                    is AuthorizedFileMutation.DeleteFile -> {
                        val source = requireNotNull(records[mutation.sourceAlias])
                        providerCallStarted = true
                        access.deleteExisting(entity.treeUri, source.documentId)
                        null
                    }
                }
                results += AuthorizedFileMutationResult(
                    operationId = mutation.operationId,
                    kind = mutation.kindName(),
                    state = "succeeded",
                    resultAlias = resultAlias,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val uncertain = providerCallStarted
                val errorCode = if (uncertain) {
                    "FILE_COMMIT_OUTCOME_UNKNOWN"
                } else {
                    when (failure) {
                        is SecurityException -> "AUTHORIZED_FOLDER_UNAVAILABLE"
                        is FileNotFoundException -> "DOCUMENTS_PROVIDER_UNAVAILABLE"
                        is IllegalArgumentException,
                        is IllegalStateException,
                        -> "FILE_PRECONDITION_FAILED"
                        else -> "FILE_COMMIT_FAILED"
                    }
                }
                results += AuthorizedFileMutationResult(
                    operationId = mutation.operationId,
                    kind = mutation.kindName(),
                    state = if (uncertain) "unknown" else "failed",
                    errorCode = errorCode,
                )
                mutations.drop(index + 1).forEach { skipped ->
                    results += AuthorizedFileMutationResult(
                        operationId = skipped.operationId,
                        kind = skipped.kindName(),
                        state = "cancelled",
                        errorCode = if (uncertain) {
                            "NOT_STARTED_AFTER_UNKNOWN"
                        } else {
                            "NOT_STARTED_AFTER_FAILURE"
                        },
                    )
                }
                return@withContext AuthorizedFileCommitResult(
                    outcome = if (uncertain) {
                        if (results.any { it.state == "succeeded" }) {
                            "partially_unknown"
                        } else {
                            "unknown"
                        }
                    } else {
                        if (results.any { it.state == "succeeded" }) {
                            "partially_failed"
                        } else {
                            "failed"
                        }
                    },
                    appliedCount = results.count { it.state == "succeeded" },
                    results = results,
                )
            }
        }
        AuthorizedFileCommitResult(
            outcome = if (stopped) {
                if (results.any { it.state == "succeeded" }) "partially_cancelled" else "cancelled"
            } else {
                "completed"
            },
            appliedCount = results.count { it.state == "succeeded" },
            results = results,
        )
    }

    suspend fun revoke(grantId: String) = withContext(ioDispatcher) {
        requireOpaqueGrantId(grantId)
        val entity = store.get(grantId) ?: return@withContext
        access.releasePersistableGrant(entity.treeUri)
        check(store.delete(grantId)) { "Authorized folder registry changed concurrently" }
    }

    private suspend fun writableListing(
        grantId: String,
    ): Pair<AuthorizedFolderEntity, SafListingRecord> {
        requireOpaqueGrantId(grantId)
        val entity = store.get(grantId)
            ?: throw SecurityException("Authorized folder is unavailable")
        val persisted = access.persistedGrant(entity.treeUri)
            ?: throw SecurityException("Folder access must be authorized again")
        check(persisted.read && persisted.write) { "Folder write access is unavailable" }
        val listing = access.list(
            entity.treeUri,
            maxDepth = MAX_MUTATION_SCAN_DEPTH,
            maxItems = MAX_MUTATION_SCAN_ITEMS,
        )
        require(!listing.truncated) {
            "Authorized folder is too large for exact mutation resolution"
        }
        return entity to listing
    }

    private fun validateMutation(
        mutation: AuthorizedFileMutation,
        records: Map<String, SafDocumentRecord>,
    ) {
        when (mutation) {
            is AuthorizedFileMutation.CreateFile -> {
                validateName(mutation.displayName)
                require(AuthorizedContentReadPolicy.isAllowedTextMime(mutation.mimeType)) {
                    "Only bounded text files may be created"
                }
                require(mutation.content.toByteArray(Charsets.UTF_8).size <= MAX_NEW_FILE_BYTES) {
                    "New file content exceeds policy"
                }
                val parent = requireDirectory(records, mutation.parentAlias)
                requireFlag(parent, Document.FLAG_DIR_SUPPORTS_CREATE)
                requireNoSiblingCollision(records.values, parent.documentId, mutation.displayName)
            }
            is AuthorizedFileMutation.CreateDirectory -> {
                validateName(mutation.displayName)
                val parent = requireDirectory(records, mutation.parentAlias)
                requireFlag(parent, Document.FLAG_DIR_SUPPORTS_CREATE)
                requireNoSiblingCollision(records.values, parent.documentId, mutation.displayName)
            }
            is AuthorizedFileMutation.Rename -> {
                validateName(mutation.displayName)
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.displayName != mutation.displayName) { "Rename is a no-op" }
                requireFlag(source, Document.FLAG_SUPPORTS_RENAME)
                requireNoSiblingCollision(
                    records.values.filterNot { it.documentId == source.documentId },
                    requireNotNull(source.parentDocumentId),
                    mutation.displayName,
                )
            }
            is AuthorizedFileMutation.Move -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                val target = requireDirectory(records, mutation.targetParentAlias)
                validatePrecondition(source, mutation.expected)
                require(source.parentDocumentId != target.documentId) { "Move is a no-op" }
                requireFlag(source, Document.FLAG_SUPPORTS_MOVE)
                requireFlag(target, Document.FLAG_DIR_SUPPORTS_CREATE)
                requireNoSiblingCollision(records.values, target.documentId, source.displayName)
                if (source.mimeType == Document.MIME_TYPE_DIR) {
                    var cursor: SafDocumentRecord? = target
                    while (cursor != null) {
                        require(cursor.documentId != source.documentId) {
                            "Directory cannot move into its own descendant"
                        }
                        cursor = cursor.parentDocumentId?.let { parentId ->
                            records.values.firstOrNull { it.documentId == parentId }
                        }
                    }
                }
            }
            is AuthorizedFileMutation.WriteFile -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.mimeType != Document.MIME_TYPE_DIR) { "Directories cannot be written" }
                require(source.mimeType == mutation.mimeType) { "Document MIME changed" }
                require(AuthorizedContentReadPolicy.isAllowedTextMime(mutation.mimeType)) {
                    "Only bounded text files may be written"
                }
                require(mutation.content.toByteArray(Charsets.UTF_8).size <= MAX_NEW_FILE_BYTES) {
                    "Replacement content exceeds policy"
                }
                requireFlag(source, Document.FLAG_SUPPORTS_WRITE)
            }
            is AuthorizedFileMutation.DeleteFile -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.mimeType != Document.MIME_TYPE_DIR) {
                    "Directory deletion is unavailable"
                }
                requireFlag(source, Document.FLAG_SUPPORTS_DELETE)
            }
        }
    }

    private fun requireDirectory(
        records: Map<String, SafDocumentRecord>,
        alias: String,
    ): SafDocumentRecord {
        require(AuthorizedContentReadPolicy.DOCUMENT_ALIAS.matches(alias)) {
            "Document alias is invalid"
        }
        return requireNotNull(records[alias]) { "Directory alias is outside the authorized tree" }
            .also { require(it.mimeType == Document.MIME_TYPE_DIR) { "Target is not a directory" } }
    }

    private fun requireNonRoot(
        records: Map<String, SafDocumentRecord>,
        alias: String,
    ): SafDocumentRecord {
        require(AuthorizedContentReadPolicy.DOCUMENT_ALIAS.matches(alias)) {
            "Document alias is invalid"
        }
        return requireNotNull(records[alias]) { "Source alias is outside the authorized tree" }
            .also { require(it.parentDocumentId != null) { "Authorized root cannot be changed" } }
    }

    private fun validatePrecondition(
        actual: SafDocumentRecord,
        expected: AuthorizedFilePrecondition,
    ) {
        require(actual.displayName == expected.displayName) { "Document name changed" }
        require(actual.mimeType == expected.mimeType) { "Document MIME changed" }
        require(actual.byteCount == expected.byteCount) { "Document size changed" }
        require(actual.lastModifiedMillis == expected.lastModifiedMillis) {
            "Document timestamp changed"
        }
    }

    private fun requireFlag(document: SafDocumentRecord, flag: Int) {
        require(document.flags and flag.toLong() != 0L) { "Provider operation is unsupported" }
    }

    private fun requireNoSiblingCollision(
        records: Collection<SafDocumentRecord>,
        parentDocumentId: String,
        displayName: String,
    ) {
        require(records.none {
            it.parentDocumentId == parentDocumentId &&
                it.displayName.equals(displayName, ignoreCase = true)
        }) { "Destination name already exists" }
    }

    private fun validateName(value: String) {
        require(value.isNotBlank() && value.length <= 240) { "Display name is invalid" }
        require(value != "." && value != "..") { "Display name is invalid" }
        require('/' !in value && '\\' !in value && !CONTROL_CHARACTERS.containsMatchIn(value)) {
            "Display name is invalid"
        }
    }

    private fun AuthorizedFolderEntity.toSummary(
        persisted: PersistedGrant?,
    ): AuthorizedFolderSummary {
        val currentStatus = runCatching { AuthorizedFolderStatus.valueOf(status) }
            .getOrDefault(AuthorizedFolderStatus.REAUTHORIZATION_REQUIRED)
        return AuthorizedFolderSummary(
            grantId = grantId,
            displayName = displayName,
            authorityLabel = "Android SAF",
            status = currentStatus,
            canRead = persisted?.read == true,
            canWrite = persisted?.write == true,
        )
    }

    private companion object {
        const val MAX_CHANGE_OPERATIONS = 16
        const val MAX_NEW_FILE_BYTES = 262_144
        const val MAX_MUTATION_SCAN_DEPTH = 32
        const val MAX_MUTATION_SCAN_ITEMS = 2_000
        const val MAX_PROJECT_FILES = 256
        const val MAX_PROJECT_FILE_BYTES = 262_144
        const val MAX_PROJECT_TOTAL_BYTES = 8 * 1_024 * 1_024
        const val MAX_PROJECT_SCAN_DEPTH = 24
        const val MAX_PROJECT_SCAN_ITEMS = 2_000
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        val PROJECT_EXCLUDED_DIRECTORY_NAMES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".kotlin",
            ".cache",
            "build",
            "dist",
            "node_modules",
        )

        fun validateProjectPathSegment(value: String) {
            require(value.isNotBlank() && value.length <= 240) { "Project path is invalid" }
            require(value != "." && value != "..") { "Project path is invalid" }
            require('/' !in value && '\\' !in value && !CONTROL_CHARACTERS.containsMatchIn(value)) {
                "Project path is invalid"
            }
        }

        fun AuthorizedFileMutation.kindName(): String = when (this) {
            is AuthorizedFileMutation.CreateFile -> "create_file"
            is AuthorizedFileMutation.CreateDirectory -> "create_directory"
            is AuthorizedFileMutation.Rename -> "rename"
            is AuthorizedFileMutation.Move -> "move"
            is AuthorizedFileMutation.WriteFile -> "write_file"
            is AuthorizedFileMutation.DeleteFile -> "delete_file"
        }

        fun decodeStrictUtf8(bytes: ByteArray): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()

        fun status(grant: PersistedGrant): AuthorizedFolderStatus =
            if (grant.write) AuthorizedFolderStatus.ACTIVE else AuthorizedFolderStatus.READ_ONLY

        fun requireOpaqueGrantId(grantId: String) {
            require(runCatching { UUID.fromString(grantId) }.isSuccess) {
                "grantId must be an opaque UUID"
            }
        }

        fun documentAlias(grantId: String, documentId: String): String =
            "doc-${sha256("$grantId\u0000$documentId").take(24)}"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

        fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
