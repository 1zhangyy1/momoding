package app.momoding.core.files

import android.os.Environment
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SharedStorageRoot(
    val rootId: String,
    val grantId: String,
    val displayName: String,
)

/**
 * Direct-file backend for Android's user-enabled All files access.
 *
 * The Pi contract remains the existing device_files_* contract: each public directory is exposed
 * as a stable synthetic grant and every file as a deterministic opaque alias. Absolute paths,
 * volume identifiers, and Android private directories never cross the Android/Pi boundary.
 */
class SharedStorageRepository internal constructor(
    private val rootDirectories: Map<String, File>,
    private val accessReady: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor() : this(
        rootDirectories = linkedMapOf(
            "downloads" to Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            ),
            "documents" to Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS,
            ),
            "pictures" to Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES,
            ),
            "dcim" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        ),
        accessReady = Environment::isExternalStorageManager,
    )

    private val rootsByGrant = rootDirectories.map { (rootId, directory) ->
        val normalizedId = requireRootId(rootId)
        RootRecord(
            rootId = normalizedId,
            grantId = syntheticGrantId(normalizedId),
            displayName = ROOT_LABELS.getValue(normalizedId),
            directory = directory.absoluteFile,
        )
    }.associateBy(RootRecord::grantId)

    fun isSharedGrant(grantId: String): Boolean = grantId in rootsByGrant

    fun isReady(): Boolean = accessReady()

    fun roots(): List<SharedStorageRoot> {
        if (!accessReady()) return emptyList()
        return rootsByGrant.values.sortedBy(RootRecord::rootId).map {
            SharedStorageRoot(it.rootId, it.grantId, it.displayName)
        }
    }

    fun requireReadyGrant(grantId: String) {
        requireRoot(grantId)
    }

    suspend fun metadata(
        grantId: String,
        maxDepth: Int = 8,
        maxItems: Int = 200,
    ): AuthorizedFolderListing = withContext(ioDispatcher) {
        require(maxDepth in 0..MAX_SCAN_DEPTH)
        require(maxItems in 1..MAX_SCAN_ITEMS)
        val root = requireRoot(grantId)
        listing(root, maxDepth, maxItems)
    }

    suspend fun mutationMetadata(grantId: String): AuthorizedFolderListing =
        withContext(ioDispatcher) {
            val root = requireRoot(grantId)
            listing(root, MAX_MUTATION_DEPTH, MAX_MUTATION_ITEMS).also {
                require(!it.truncated) {
                    "Shared-storage root is too large for exact mutation resolution"
                }
            }
        }

    suspend fun readText(
        grantId: String,
        requests: List<AuthorizedDocumentReadRequest>,
        totalMaxBytes: Int,
    ): AuthorizedFolderContentRead = withContext(ioDispatcher) {
        require(requests.size in 1..AuthorizedContentReadPolicy.MAX_CONTENT_DOCUMENTS)
        require(requests.map { it.alias }.toSet().size == requests.size)
        require(totalMaxBytes in 1..AuthorizedContentReadPolicy.MAX_TOTAL_CONTENT_BYTES)
        val root = requireRoot(grantId)
        val records = exactRecords(root)
        var totalBytes = 0
        val documents = requests.map { request ->
            coroutineContext.ensureActive()
            val record = records[request.alias]
                ?: throw SecurityException("File alias is outside the shared-storage root")
            AuthorizedContentReadPolicy.validateDocument(
                alias = request.alias,
                displayName = record.file.name,
                actualMimeType = record.mimeType,
                byteCount = record.byteCount,
                expectedMimeType = request.expectedMimeType,
                maxBytes = request.maxBytes,
            )
            val bytes = readBounded(record.file, request.maxBytes)
            totalBytes += bytes.size
            require(totalBytes <= totalMaxBytes) {
                "Files exceed the total approved byte limit"
            }
            AuthorizedDocumentContent(
                alias = request.alias,
                mimeType = record.mimeType,
                byteCount = bytes.size,
                content = decodeStrictUtf8(bytes),
            )
        }
        AuthorizedFolderContentRead(grantId, documents, totalBytes)
    }

    suspend fun validateChanges(
        grantId: String,
        mutations: List<AuthorizedFileMutation>,
    ) = withContext(ioDispatcher) {
        validatePlanShape(mutations)
        val root = requireRoot(grantId)
        val records = exactRecords(root)
        mutations.forEach { validateMutation(it, records) }
    }

    suspend fun commitChanges(
        grantId: String,
        mutations: List<AuthorizedFileMutation>,
        stopRequested: () -> Boolean = { false },
    ): AuthorizedFileCommitResult = withContext(ioDispatcher) {
        validatePlanShape(mutations)
        val results = mutableListOf<AuthorizedFileMutationResult>()
        var stopped = false
        mutations.forEachIndexed { index, mutation ->
            var fileCallStarted = false
            if (stopped || stopRequested()) {
                stopped = true
                results += mutation.result("cancelled", "FILE_COMMIT_CANCELLED")
                return@forEachIndexed
            }
            try {
                val root = requireRoot(grantId)
                val records = exactRecords(root)
                validateMutation(mutation, records)
                if (stopRequested()) {
                    stopped = true
                    results += mutation.result("cancelled", "FILE_COMMIT_CANCELLED")
                    return@forEachIndexed
                }
                val resultAlias = when (mutation) {
                    is AuthorizedFileMutation.CreateFile -> {
                        val parent = records.getValue(mutation.parentAlias).file
                        val target = safeChild(root, parent, mutation.displayName)
                        fileCallStarted = true
                        writeNew(target, mutation.content.encodeToByteArray())
                        alias(root.grantId, relativePath(root, target))
                    }
                    is AuthorizedFileMutation.CreateDirectory -> {
                        val parent = records.getValue(mutation.parentAlias).file
                        val target = safeChild(root, parent, mutation.displayName)
                        fileCallStarted = true
                        check(target.mkdir()) { "Directory was not created" }
                        alias(root.grantId, relativePath(root, target))
                    }
                    is AuthorizedFileMutation.Rename -> {
                        val source = records.getValue(mutation.sourceAlias).file
                        val target = safeChild(
                            root,
                            requireNotNull(source.parentFile),
                            mutation.displayName,
                        )
                        fileCallStarted = true
                        move(source, target, replace = false)
                        alias(root.grantId, relativePath(root, target))
                    }
                    is AuthorizedFileMutation.Move -> {
                        val source = records.getValue(mutation.sourceAlias).file
                        val parent = records.getValue(mutation.targetParentAlias).file
                        val target = safeChild(root, parent, source.name)
                        fileCallStarted = true
                        move(source, target, replace = false)
                        alias(root.grantId, relativePath(root, target))
                    }
                    is AuthorizedFileMutation.WriteFile -> {
                        val source = records.getValue(mutation.sourceAlias).file
                        fileCallStarted = true
                        replaceAtomically(source, mutation.content.encodeToByteArray())
                        mutation.sourceAlias
                    }
                    is AuthorizedFileMutation.DeleteFile -> {
                        val source = records.getValue(mutation.sourceAlias).file
                        fileCallStarted = true
                        check(source.delete()) { "File was not deleted" }
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
                val uncertain = fileCallStarted
                val code = if (uncertain) {
                    "FILE_COMMIT_OUTCOME_UNKNOWN"
                } else {
                    when (failure) {
                        is SecurityException -> "AUTHORIZED_FOLDER_UNAVAILABLE"
                        is IllegalArgumentException,
                        is IllegalStateException,
                        -> "FILE_PRECONDITION_FAILED"
                        else -> "FILE_COMMIT_FAILED"
                    }
                }
                results += mutation.result(if (uncertain) "unknown" else "failed", code)
                mutations.drop(index + 1).forEach { skipped ->
                    results += skipped.result(
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
                    } else if (results.any { it.state == "succeeded" }) {
                        "partially_failed"
                    } else {
                        "failed"
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

    private fun requireRoot(grantId: String): RootRecord {
        val root = rootsByGrant[grantId]
            ?: throw SecurityException("Shared-storage root is unavailable")
        if (!accessReady()) throw SecurityException("All files access is not enabled")
        if (Files.isSymbolicLink(root.directory.toPath())) {
            throw SecurityException("Shared-storage root cannot be a symbolic link")
        }
        if (!root.directory.exists() || !root.directory.isDirectory) {
            throw SecurityException("Shared-storage root is unavailable")
        }
        return root
    }

    private suspend fun listing(
        root: RootRecord,
        maxDepth: Int,
        maxItems: Int,
    ): AuthorizedFolderListing {
        val documents = mutableListOf<AuthorizedDocumentMetadata>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root.directory to 0
        var itemLimitReached = false
        var depthLimitReached = false
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (file, depth) = queue.removeFirst()
            if (documents.size >= maxItems) {
                itemLimitReached = true
                break
            }
            val safe = safeExisting(root, file)
            val relative = relativePath(root, safe)
            documents += metadata(root, safe, relative, depth)
            if (!safe.isDirectory) continue
            val children = safe.listFiles()?.sortedWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }),
            ) ?: throw IOException("Shared-storage directory could not be listed")
            if (depth >= maxDepth) {
                if (children.isNotEmpty()) depthLimitReached = true
                continue
            }
            children.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) {
                    depthLimitReached = true
                } else {
                    queue += child to depth + 1
                }
            }
        }
        return AuthorizedFolderListing(
            grantId = root.grantId,
            documents = documents,
            truncated = itemLimitReached || depthLimitReached,
            truncationReasons = buildSet {
                if (itemLimitReached) add("item_limit")
                if (depthLimitReached) add("depth_or_symlink_limit")
            },
        )
    }

    private suspend fun exactRecords(root: RootRecord): Map<String, SharedRecord> {
        val records = linkedMapOf<String, SharedRecord>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue += root.directory to 0
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (candidate, depth) = queue.removeFirst()
            require(depth <= MAX_MUTATION_DEPTH) {
                "Shared-storage root is too deep for exact alias resolution"
            }
            require(records.size < MAX_MUTATION_ITEMS) {
                "Shared-storage root is too large for exact alias resolution"
            }
            val file = safeExisting(root, candidate)
            val relative = relativePath(root, file)
            val documentAlias = alias(root.grantId, relative)
            records[documentAlias] = SharedRecord(
                file = file,
                mimeType = mimeType(file),
                byteCount = file.length().takeIf { file.isFile },
                lastModifiedMillis = file.lastModified().takeIf { it > 0L },
                parentAlias = if (relative.isEmpty()) {
                    null
                } else {
                    alias(root.grantId, relative.substringBeforeLast('/', ""))
                },
            )
            if (file.isDirectory) {
                file.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                    if (Files.isSymbolicLink(child.toPath())) {
                        throw SecurityException(
                            "Symbolic links are outside shared-storage policy",
                        )
                    }
                    queue += child to depth + 1
                } ?: throw IOException("Shared-storage directory could not be listed")
            }
        }
        return records
    }

    private fun metadata(
        root: RootRecord,
        file: File,
        relative: String,
        depth: Int,
    ): AuthorizedDocumentMetadata = AuthorizedDocumentMetadata(
        alias = alias(root.grantId, relative),
        parentAlias = if (relative.isEmpty()) {
            null
        } else {
            alias(root.grantId, relative.substringBeforeLast('/', ""))
        },
        displayName = if (relative.isEmpty()) root.displayName else file.name,
        mimeType = mimeType(file),
        byteCount = file.length().takeIf { file.isFile },
        lastModifiedMillis = file.lastModified().takeIf { it > 0L },
        depth = depth,
        providerFlags = 0,
    )

    private fun validatePlanShape(mutations: List<AuthorizedFileMutation>) {
        require(mutations.size in 1..MAX_CHANGE_OPERATIONS)
        require(mutations.map { it.operationId }.toSet().size == mutations.size)
        val sources = mutations.mapNotNull {
            when (it) {
                is AuthorizedFileMutation.Rename -> it.sourceAlias
                is AuthorizedFileMutation.Move -> it.sourceAlias
                is AuthorizedFileMutation.WriteFile -> it.sourceAlias
                is AuthorizedFileMutation.DeleteFile -> it.sourceAlias
                else -> null
            }
        }
        require(sources.toSet().size == sources.size) {
            "A source may be changed only once per plan"
        }
    }

    private fun validateMutation(
        mutation: AuthorizedFileMutation,
        records: Map<String, SharedRecord>,
    ) {
        when (mutation) {
            is AuthorizedFileMutation.CreateFile -> {
                validateName(mutation.displayName)
                require(AuthorizedContentReadPolicy.isAllowedTextMime(mutation.mimeType))
                require(mutation.content.encodeToByteArray().size <= MAX_NEW_FILE_BYTES)
                val parent = requireDirectory(records, mutation.parentAlias)
                requireNoCollision(parent.file, mutation.displayName)
            }
            is AuthorizedFileMutation.CreateDirectory -> {
                validateName(mutation.displayName)
                val parent = requireDirectory(records, mutation.parentAlias)
                requireNoCollision(parent.file, mutation.displayName)
            }
            is AuthorizedFileMutation.Rename -> {
                validateName(mutation.displayName)
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.file.name != mutation.displayName)
                requireNoCollision(requireNotNull(source.file.parentFile), mutation.displayName)
            }
            is AuthorizedFileMutation.Move -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                val target = requireDirectory(records, mutation.targetParentAlias)
                validatePrecondition(source, mutation.expected)
                require(source.file.parentFile != target.file)
                requireNoCollision(target.file, source.file.name)
                if (source.file.isDirectory) {
                    require(!target.file.toPath().startsWith(source.file.toPath())) {
                        "Directory cannot move into its own descendant"
                    }
                }
            }
            is AuthorizedFileMutation.WriteFile -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.file.isFile)
                require(source.mimeType == mutation.mimeType)
                require(AuthorizedContentReadPolicy.isAllowedTextMime(mutation.mimeType))
                require(mutation.content.encodeToByteArray().size <= MAX_NEW_FILE_BYTES)
            }
            is AuthorizedFileMutation.DeleteFile -> {
                val source = requireNonRoot(records, mutation.sourceAlias)
                validatePrecondition(source, mutation.expected)
                require(source.file.isFile) { "Directory deletion is unavailable" }
            }
        }
    }

    private fun requireDirectory(
        records: Map<String, SharedRecord>,
        alias: String,
    ): SharedRecord = requireNotNull(records[alias]) {
        "Directory alias is outside the shared-storage root"
    }.also { require(it.file.isDirectory) }

    private fun requireNonRoot(
        records: Map<String, SharedRecord>,
        alias: String,
    ): SharedRecord = requireNotNull(records[alias]) {
        "File alias is outside the shared-storage root"
    }.also { require(it.parentAlias != null) { "Shared-storage root cannot be changed" } }

    private fun validatePrecondition(
        actual: SharedRecord,
        expected: AuthorizedFilePrecondition,
    ) {
        require(actual.file.name == expected.displayName) { "File name changed" }
        require(actual.mimeType == expected.mimeType) { "File MIME changed" }
        require(actual.byteCount == expected.byteCount) { "File size changed" }
        require(actual.lastModifiedMillis == expected.lastModifiedMillis) {
            "File timestamp changed"
        }
    }

    private fun requireNoCollision(parent: File, name: String) {
        require(parent.listFiles()?.none { it.name.equals(name, ignoreCase = true) } == true) {
            "Destination name already exists"
        }
    }

    private fun validateName(value: String) {
        require(value.isNotBlank() && value.length <= 240)
        require(value != "." && value != "..")
        require('/' !in value && '\\' !in value && !CONTROL_CHARACTERS.containsMatchIn(value))
    }

    private fun safeExisting(root: RootRecord, file: File): File {
        if (Files.isSymbolicLink(file.toPath())) {
            throw SecurityException("Symbolic links are outside shared-storage policy")
        }
        val canonicalRoot = root.directory.canonicalFile.toPath()
        val canonical = file.canonicalFile
        if (!canonical.toPath().startsWith(canonicalRoot)) {
            throw SecurityException("File escaped the shared-storage root")
        }
        return canonical
    }

    private fun safeChild(root: RootRecord, parent: File, name: String): File {
        validateName(name)
        val safeParent = safeExisting(root, parent)
        require(safeParent.isDirectory)
        val child = File(safeParent, name)
        val canonicalRoot = root.directory.canonicalFile.toPath()
        require(child.canonicalFile.toPath().startsWith(canonicalRoot)) {
            "Destination escaped the shared-storage root"
        }
        return child
    }

    private fun relativePath(root: RootRecord, file: File): String {
        val rootPath = root.directory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath))
        return rootPath.relativize(filePath).joinToString("/")
    }

    private fun readBounded(file: File, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= maxBytes) { "File exceeds approved byte limit" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun writeNew(target: File, bytes: ByteArray) {
        Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use {
            it.write(bytes)
        }
    }

    private fun replaceAtomically(target: File, bytes: ByteArray) {
        val parent = requireNotNull(target.parentFile)
        val temporary = File.createTempFile(".momoding-", ".tmp", parent)
        try {
            temporary.outputStream().use { it.write(bytes) }
            move(temporary, target, replace = true)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun move(source: File, target: File, replace: Boolean) {
        val options = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replace) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        try {
            Files.move(source.toPath(), target.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replace) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(source.toPath(), target.toPath(), *fallback)
        }
    }

    private fun mimeType(file: File): String = if (file.isDirectory) {
        Document.MIME_TYPE_DIR
    } else {
        MIME_BY_EXTENSION[file.extension.lowercase()]
            ?: URLConnection.guessContentTypeFromName(file.name)
            ?: "application/octet-stream"
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .also { require('\u0000' !in it) { "NUL text is outside content policy" } }

    private fun alias(grantId: String, relativePath: String): String =
        "doc-${sha256("$grantId\u0000$relativePath").take(24)}"

    private fun syntheticGrantId(rootId: String): String = UUID.nameUUIDFromBytes(
        "momoding:all-files:$rootId".encodeToByteArray(),
    ).toString()

    private fun requireRootId(rootId: String): String {
        require(rootId in ROOT_LABELS) { "Unsupported shared-storage root" }
        return rootId
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun AuthorizedFileMutation.kindName(): String = when (this) {
        is AuthorizedFileMutation.CreateFile -> "create_file"
        is AuthorizedFileMutation.CreateDirectory -> "create_directory"
        is AuthorizedFileMutation.Rename -> "rename"
        is AuthorizedFileMutation.Move -> "move"
        is AuthorizedFileMutation.WriteFile -> "write_file"
        is AuthorizedFileMutation.DeleteFile -> "delete_file"
    }

    private fun AuthorizedFileMutation.result(
        state: String,
        errorCode: String,
    ) = AuthorizedFileMutationResult(operationId, kindName(), state, errorCode = errorCode)

    private data class RootRecord(
        val rootId: String,
        val grantId: String,
        val displayName: String,
        val directory: File,
    )

    private data class SharedRecord(
        val file: File,
        val mimeType: String,
        val byteCount: Long?,
        val lastModifiedMillis: Long?,
        val parentAlias: String?,
    )

    private companion object {
        const val MAX_SCAN_DEPTH = 32
        const val MAX_SCAN_ITEMS = 2_000
        const val MAX_MUTATION_DEPTH = 32
        const val MAX_MUTATION_ITEMS = 2_000
        const val MAX_CHANGE_OPERATIONS = 16
        const val MAX_NEW_FILE_BYTES = 262_144
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        val ROOT_LABELS = linkedMapOf(
            "downloads" to "Downloads",
            "documents" to "Documents",
            "pictures" to "Pictures",
            "dcim" to "Camera",
            "movies" to "Movies",
            "music" to "Music",
        )
        val MIME_BY_EXTENSION = mapOf(
            "txt" to "text/plain",
            "md" to "text/markdown",
            "csv" to "text/csv",
            "json" to "application/json",
            "xml" to "application/xml",
            "yaml" to "application/yaml",
            "yml" to "application/yaml",
            "kt" to "text/plain",
            "kts" to "text/plain",
            "java" to "text/plain",
            "js" to "text/javascript",
            "ts" to "text/plain",
            "tsx" to "text/plain",
            "jsx" to "text/plain",
            "py" to "text/x-python",
            "sh" to "text/x-shellscript",
            "html" to "text/html",
            "css" to "text/css",
            "gradle" to "text/plain",
        )
    }
}
