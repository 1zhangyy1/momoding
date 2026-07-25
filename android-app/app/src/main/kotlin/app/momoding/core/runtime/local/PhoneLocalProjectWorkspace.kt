package app.momoding.core.runtime.local

import android.content.Context
import android.provider.DocumentsContract.Document
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.files.AuthorizedFileMutation
import app.momoding.core.files.AuthorizedFilePrecondition
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.AuthorizedProjectSnapshotEntry
import app.momoding.core.files.AuthorizedProjectSnapshotExclusion
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PhoneLocalProjectImportResult(
    val taskId: String,
    val grantId: String,
    val workspaceId: String,
    val manifestSha256: String,
    val importedFileCount: Int,
    val importedBytes: Int,
    val exclusions: List<AuthorizedProjectSnapshotExclusion>,
)

data class PhoneLocalProjectUnsupportedChange(
    val relativePath: String,
    val reason: String,
)

data class PhoneLocalProjectChangeSet(
    val taskId: String,
    val grantId: String,
    val snapshotSha256: String,
    val operations: List<AuthorizedFileMutation>,
    val createdPaths: List<String>,
    val modifiedPaths: List<String>,
    val deletedPaths: List<String>,
    val unsupported: List<PhoneLocalProjectUnsupportedChange>,
)

/**
 * Imports one explicitly selected task folder into the App-private Linux workspace and converts
 * later text-file changes back into the existing Android-authoritative file mutation model.
 *
 * No SAF URI enters the workspace or manifest. This class does not commit to the real directory;
 * callers must pass [PhoneLocalProjectChangeSet.operations] through DeviceFileChangeExecutor so
 * that Diff, user confirmation and live Provider preconditions remain mandatory.
 */
class PhoneLocalProjectWorkspace internal constructor(
    private val database: MomodingDatabase,
    private val folders: AuthorizedFoldersRepository,
    private val workspaceRoot: (String) -> File,
    private val manifestRoot: File,
) {
    constructor(
        context: Context,
        database: MomodingDatabase,
        folders: AuthorizedFoldersRepository,
        linuxRuntime: PhoneLocalLinuxRuntime,
    ) : this(
        database = database,
        folders = folders,
        workspaceRoot = linuxRuntime::workspace,
        manifestRoot = File(
            context.applicationContext.noBackupFilesDir,
            "phone-local-linux/project-manifests",
        ),
    )

    suspend fun hasImportedTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        requireUuid(taskId, "taskId")
        val selectedGrant = database.momodingDao().draftForTask(taskId)?.selectedGrantId ?: return@withContext false
        val currentGrant = folders.folders().singleOrNull { it.grantId == selectedGrant }
            ?: return@withContext false
        if (!currentGrant.canRead) return@withContext false
        PhoneLocalWorkspaceLocks.withLock(taskId) {
            if (!manifestFile(taskId).isFile) return@withLock false
            val manifest = readManifest(taskId)
            check(manifest.grantId == selectedGrant) {
                "PHONE_LOCAL_PROJECT_MANIFEST_GRANT_MISMATCH"
            }
            val workspace = workspaceRoot(taskId)
            check(!Files.isSymbolicLink(workspace.toPath()) && workspace.isDirectory) {
                "PHONE_LOCAL_PROJECT_WORKSPACE_MISSING"
            }
            true
        }
    }

    suspend fun importApprovedTask(taskId: String): PhoneLocalProjectImportResult =
        withContext(Dispatchers.IO) {
            requireUuid(taskId, "taskId")
            val grantId = database.momodingDao().draftForTask(taskId)?.selectedGrantId
                ?: throw SecurityException("Task has no user-selected project folder")
            val currentGrant = folders.folders().singleOrNull { it.grantId == grantId }
                ?: throw SecurityException("Task project folder is unavailable")
            check(currentGrant.canRead) { "Task project folder is not readable" }
            val snapshot = folders.projectSnapshot(grantId)
            check(snapshot.grantId == grantId) { "Project snapshot grant changed" }

            PhoneLocalWorkspaceLocks.withLock(taskId) {
                check(database.momodingDao().draftForTask(taskId)?.selectedGrantId == grantId) {
                    "Task project folder changed during import"
                }
                val workspace = workspaceRoot(taskId)
                resetWorkspaceNoFollow(workspace)
                snapshot.entries.asSequence()
                    .filter(AuthorizedProjectSnapshotEntry::isDirectory)
                    .sortedBy(AuthorizedProjectSnapshotEntry::relativePath)
                    .forEach { entry ->
                        if (entry.relativePath.isNotEmpty()) {
                            val directory = safeChild(workspace, entry.relativePath)
                            check(directory.mkdirs() || directory.isDirectory) {
                                "PHONE_LOCAL_PROJECT_DIRECTORY_CREATE_FAILED"
                            }
                        }
                    }
                snapshot.entries.asSequence()
                    .filterNot(AuthorizedProjectSnapshotEntry::isDirectory)
                    .sortedBy(AuthorizedProjectSnapshotEntry::relativePath)
                    .forEach { entry ->
                        val content = requireNotNull(entry.content)
                        val target = safeChild(workspace, entry.relativePath)
                        check(
                            target.parentFile?.mkdirs() != false ||
                                target.parentFile?.isDirectory == true,
                        ) {
                            "PHONE_LOCAL_PROJECT_PARENT_CREATE_FAILED"
                        }
                        target.writeBytes(content)
                    }
                val manifest = StoredManifest(
                    taskId = taskId,
                    grantId = grantId,
                    snapshotSha256 = snapshot.manifestSha256,
                    entries = snapshot.entries.map { entry ->
                        StoredEntry(
                            alias = entry.alias,
                            parentAlias = entry.parentAlias,
                            relativePath = entry.relativePath,
                            displayName = entry.displayName,
                            mimeType = entry.mimeType,
                            byteCount = entry.byteCount,
                            lastModifiedMillis = entry.lastModifiedMillis,
                            sha256 = entry.sha256,
                            directory = entry.isDirectory,
                        )
                    },
                )
                writeManifest(manifest)
                PhoneLocalProjectImportResult(
                    taskId = taskId,
                    grantId = grantId,
                    workspaceId = taskId,
                    manifestSha256 = snapshot.manifestSha256,
                    importedFileCount = snapshot.fileCount,
                    importedBytes = snapshot.totalBytes,
                    exclusions = snapshot.exclusions,
                )
            }
        }

    /** Discards only the App-private snapshot; the user-authorized SAF tree is never mutated. */
    suspend fun discardImportedTask(taskId: String) = withContext(Dispatchers.IO) {
        requireUuid(taskId, "taskId")
        PhoneLocalWorkspaceLocks.withLock(taskId) {
            resetWorkspaceNoFollow(workspaceRoot(taskId))
            val manifest = manifestFile(taskId)
            check(!manifest.exists() || manifest.delete()) {
                "PHONE_LOCAL_PROJECT_MANIFEST_DELETE_FAILED"
            }
        }
    }

    suspend fun detectChanges(taskId: String): PhoneLocalProjectChangeSet =
        withContext(Dispatchers.IO) {
            requireUuid(taskId, "taskId")
            PhoneLocalWorkspaceLocks.withLock(taskId) {
                val manifest = readManifest(taskId)
                val selectedGrant = database.momodingDao().draftForTask(taskId)?.selectedGrantId
                if (selectedGrant != manifest.grantId) {
                    throw SecurityException("Task project folder changed after import")
                }
                val workspace = workspaceRoot(taskId)
                check(!Files.isSymbolicLink(workspace.toPath()) && workspace.isDirectory) {
                    "PHONE_LOCAL_PROJECT_WORKSPACE_MISSING"
                }
                val current = scanWorkspace(workspace)
            val originalFiles = manifest.entries.filterNot(StoredEntry::directory)
                .associateBy(StoredEntry::relativePath)
            val originalDirectories = manifest.entries.filter(StoredEntry::directory)
                .associateBy(StoredEntry::relativePath)
            val currentFiles = current.files
            val operations = mutableListOf<AuthorizedFileMutation>()
            val unsupported = current.unsupported.toMutableList()
            val created = (currentFiles.keys - originalFiles.keys).sorted()
            val deleted = (originalFiles.keys - currentFiles.keys).sorted()
            val modified = (currentFiles.keys intersect originalFiles.keys)
                .filter { path -> currentFiles.getValue(path).sha256 != originalFiles.getValue(path).sha256 }
                .sorted()

            val createdDirectories = (current.directories - originalDirectories.keys - setOf(""))
                .sorted()
            createdDirectories.forEach { path ->
                unsupported += PhoneLocalProjectUnsupportedChange(path, "NEW_DIRECTORY")
            }
            val deletedDirectories = (originalDirectories.keys - current.directories - setOf(""))
                .sorted()
            deletedDirectories.forEach { path ->
                unsupported += PhoneLocalProjectUnsupportedChange(path, "DELETED_DIRECTORY")
            }

            created.forEach { path ->
                val parentPath = path.substringBeforeLast('/', "")
                val parent = originalDirectories[parentPath]
                if (parent == null) {
                    unsupported += PhoneLocalProjectUnsupportedChange(
                        path,
                        "PARENT_DIRECTORY_NOT_IN_SNAPSHOT",
                    )
                    return@forEach
                }
                val file = currentFiles.getValue(path)
                operations += AuthorizedFileMutation.CreateFile(
                    operationId = operationId(taskId, manifest.snapshotSha256, "create", path),
                    parentAlias = parent.alias,
                    displayName = path.substringAfterLast('/'),
                    mimeType = inferTextMime(path),
                    content = file.content,
                )
            }
            modified.forEach { path ->
                val before = originalFiles.getValue(path)
                val after = currentFiles.getValue(path)
                operations += AuthorizedFileMutation.WriteFile(
                    operationId = operationId(taskId, manifest.snapshotSha256, "write", path),
                    sourceAlias = before.alias,
                    mimeType = before.mimeType,
                    content = after.content,
                    expected = before.precondition(),
                )
            }
            deleted.forEach { path ->
                val before = originalFiles.getValue(path)
                operations += AuthorizedFileMutation.DeleteFile(
                    operationId = operationId(taskId, manifest.snapshotSha256, "delete", path),
                    sourceAlias = before.alias,
                    expected = before.precondition(),
                )
            }
            if (operations.size > MAX_CHANGE_OPERATIONS) {
                unsupported += PhoneLocalProjectUnsupportedChange(
                    relativePath = "",
                    reason = "TOO_MANY_CHANGES",
                )
            }
                PhoneLocalProjectChangeSet(
                    taskId = taskId,
                    grantId = manifest.grantId,
                    snapshotSha256 = manifest.snapshotSha256,
                    operations = if (unsupported.isEmpty()) operations else emptyList(),
                    createdPaths = created,
                    modifiedPaths = modified,
                    deletedPaths = deleted,
                    unsupported = unsupported.distinct().sortedBy { it.relativePath },
                )
            }
        }

    private fun scanWorkspace(workspace: File): WorkspaceScan {
        val files = linkedMapOf<String, CurrentFile>()
        val directories = linkedSetOf("")
        val unsupported = mutableListOf<PhoneLocalProjectUnsupportedChange>()
        var totalBytes = 0
        fun visit(directory: File, relativeDirectory: String) {
            directory.listFiles()?.sortedBy(File::getName)?.forEach { child ->
                val relativePath = listOf(relativeDirectory, child.name)
                    .filter(String::isNotEmpty)
                    .joinToString("/")
                validateRelativePath(relativePath)
                if (Files.isSymbolicLink(child.toPath())) {
                    unsupported += PhoneLocalProjectUnsupportedChange(relativePath, "SYMLINK")
                    return@forEach
                }
                if (child.isDirectory) {
                    directories += relativePath
                    visit(child, relativePath)
                    return@forEach
                }
                if (!child.isFile) {
                    unsupported += PhoneLocalProjectUnsupportedChange(relativePath, "SPECIAL_FILE")
                    return@forEach
                }
                if (child.length() > MAX_FILE_BYTES) {
                    unsupported += PhoneLocalProjectUnsupportedChange(relativePath, "FILE_TOO_LARGE")
                    return@forEach
                }
                val bytes = child.readBytes()
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    unsupported += PhoneLocalProjectUnsupportedChange("", "TOTAL_BYTES_EXCEEDED")
                    return@forEach
                }
                val content = decodeStrictUtf8(bytes)
                if (content == null || '\u0000' in content) {
                    unsupported += PhoneLocalProjectUnsupportedChange(
                        relativePath,
                        "BINARY_OR_NON_UTF8",
                    )
                    return@forEach
                }
                files[relativePath] = CurrentFile(
                    content = content,
                    sha256 = sha256(bytes),
                )
            }
        }
        visit(workspace, "")
        return WorkspaceScan(files, directories, unsupported)
    }

    private fun resetWorkspaceNoFollow(workspace: File) {
        val root = workspace.toPath()
        if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: java.io.IOException,
                    ): FileVisitResult {
                        if (Files.isSymbolicLink(file)) {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }
                        throw exc
                    }

                    override fun postVisitDirectory(
                        directory: Path,
                        exc: java.io.IOException?,
                    ): FileVisitResult {
                        exc?.let { throw it }
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        check(workspace.mkdirs()) { "PHONE_LOCAL_PROJECT_WORKSPACE_CREATE_FAILED" }
    }

    private fun safeChild(root: File, relativePath: String): File {
        validateRelativePath(relativePath)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Project path escaped the private workspace"
        }
        return target
    }

    private fun validateRelativePath(relativePath: String) {
        require(relativePath.isNotEmpty()) { "Project path is empty" }
        val segments = relativePath.split('/')
        require(segments.all { segment ->
            segment.isNotBlank() &&
                segment != "." &&
                segment != ".." &&
                '\\' !in segment &&
                !CONTROL_CHARACTERS.containsMatchIn(segment)
        }) { "Project path is invalid" }
    }

    private fun writeManifest(manifest: StoredManifest) {
        check(manifestRoot.mkdirs() || manifestRoot.isDirectory) {
            "PHONE_LOCAL_PROJECT_MANIFEST_DIRECTORY_FAILED"
        }
        val destination = manifestFile(manifest.taskId)
        val temporary = File(manifestRoot, ".${manifest.taskId}.${UUID.randomUUID()}.tmp")
        temporary.writeText(manifest.toJson().toString())
        if (destination.exists()) check(destination.delete()) {
            temporary.delete()
            "PHONE_LOCAL_PROJECT_MANIFEST_REPLACE_FAILED"
        }
        check(temporary.renameTo(destination)) {
            temporary.delete()
            "PHONE_LOCAL_PROJECT_MANIFEST_WRITE_FAILED"
        }
    }

    private fun readManifest(taskId: String): StoredManifest {
        val source = manifestFile(taskId)
        check(source.isFile) { "PHONE_LOCAL_PROJECT_MANIFEST_MISSING" }
        val manifest = StoredManifest.fromJson(
            STRICT_JSON.parseToJsonElement(source.readText()).jsonObject,
        )
        check(manifest.taskId == taskId) { "PHONE_LOCAL_PROJECT_MANIFEST_TASK_MISMATCH" }
        check(manifest.snapshotSha256 == manifest.canonicalDigest()) {
            "PHONE_LOCAL_PROJECT_MANIFEST_DIGEST_MISMATCH"
        }
        return manifest
    }

    private fun manifestFile(taskId: String): File = File(manifestRoot, "$taskId.json")

    private data class WorkspaceScan(
        val files: Map<String, CurrentFile>,
        val directories: Set<String>,
        val unsupported: List<PhoneLocalProjectUnsupportedChange>,
    )

    private data class CurrentFile(
        val content: String,
        val sha256: String,
    )

    private data class StoredManifest(
        val taskId: String,
        val grantId: String,
        val snapshotSha256: String,
        val entries: List<StoredEntry>,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("version", MANIFEST_VERSION)
            put("taskId", taskId)
            put("grantId", grantId)
            put("snapshotSha256", snapshotSha256)
            put("entries", buildJsonArray { entries.forEach { add(it.toJson()) } })
        }

        fun canonicalDigest(): String = sha256(
            entries.sortedBy(StoredEntry::relativePath)
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
                }.toByteArray(Charsets.UTF_8),
        )

        companion object {
            fun fromJson(value: JsonObject): StoredManifest {
                require(value.keys == setOf("version", "taskId", "grantId", "snapshotSha256", "entries"))
                require(value.getValue("version").jsonPrimitive.longOrNull == MANIFEST_VERSION.toLong())
                return StoredManifest(
                    taskId = value.requiredString("taskId"),
                    grantId = value.requiredString("grantId"),
                    snapshotSha256 = value.requiredSha256("snapshotSha256"),
                    entries = value.getValue("entries").jsonArray.map { entry ->
                        StoredEntry.fromJson(entry.jsonObject)
                    },
                )
            }
        }
    }

    private data class StoredEntry(
        val alias: String,
        val parentAlias: String?,
        val relativePath: String,
        val displayName: String,
        val mimeType: String,
        val byteCount: Long?,
        val lastModifiedMillis: Long?,
        val sha256: String?,
        val directory: Boolean,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("alias", alias)
            parentAlias?.let { put("parentAlias", it) }
            put("relativePath", relativePath)
            put("displayName", displayName)
            put("mimeType", mimeType)
            byteCount?.let { put("byteCount", it) }
            lastModifiedMillis?.let { put("lastModifiedMillis", it) }
            sha256?.let { put("sha256", it) }
            put("directory", directory)
        }

        fun precondition(): AuthorizedFilePrecondition = AuthorizedFilePrecondition(
            displayName = displayName,
            mimeType = mimeType,
            byteCount = byteCount,
            lastModifiedMillis = lastModifiedMillis,
        )

        companion object {
            fun fromJson(value: JsonObject): StoredEntry {
                require(
                    value.keys.all {
                        it in setOf(
                            "alias",
                            "parentAlias",
                            "relativePath",
                            "displayName",
                            "mimeType",
                            "byteCount",
                            "lastModifiedMillis",
                            "sha256",
                            "directory",
                        )
                    },
                )
                return StoredEntry(
                    alias = value.requiredString("alias"),
                    parentAlias = value.optionalString("parentAlias"),
                    relativePath = value.getValue("relativePath").jsonPrimitive.content,
                    displayName = value.requiredString("displayName"),
                    mimeType = value.requiredString("mimeType"),
                    byteCount = value["byteCount"]?.jsonPrimitive?.longOrNull,
                    lastModifiedMillis = value["lastModifiedMillis"]?.jsonPrimitive?.longOrNull,
                    sha256 = value.optionalString("sha256"),
                    directory = value.getValue("directory").jsonPrimitive.content.toBooleanStrict(),
                )
            }
        }
    }

    private companion object {
        const val MANIFEST_VERSION = 1
        const val MAX_FILE_BYTES = 262_144L
        const val MAX_TOTAL_BYTES = 8 * 1_024 * 1_024
        const val MAX_CHANGE_OPERATIONS = 16
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        val STRICT_JSON = Json { ignoreUnknownKeys = false; isLenient = false }
        val SHA256 = Regex("[0-9a-f]{64}")

        fun operationId(taskId: String, snapshot: String, kind: String, path: String): String =
            UUID.nameUUIDFromBytes("$taskId\u0000$snapshot\u0000$kind\u0000$path".toByteArray()).toString()

        fun inferTextMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "xml" -> "application/xml"
            "yaml", "yml" -> "application/yaml"
            else -> "text/plain"
        }

        fun requireUuid(value: String, label: String) {
            require(runCatching { UUID.fromString(value) }.isSuccess) { "$label is invalid" }
        }

        fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        fun JsonObject.requiredString(key: String): String =
            getValue(key).jsonPrimitive.contentOrNull?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("$key is invalid")

        fun JsonObject.optionalString(key: String): String? =
            get(key)?.jsonPrimitive?.contentOrNull

        fun JsonObject.requiredSha256(key: String): String =
            requiredString(key).takeIf(SHA256::matches)
                ?: throw IllegalArgumentException("$key is invalid")
    }
}
