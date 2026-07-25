package app.momoding.core.files

import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.MomodingDatabase
import java.io.FileNotFoundException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Executes only the two metadata-only file tools exposed to Pi.
 *
 * The task grant is resolved from Android-private Room state. Real SAF URIs never enter a result,
 * error, log, or Host frame.
 */
interface DeviceMetadataToolHandler {
    fun handles(toolName: String): Boolean
    suspend fun execute(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame
}

class DeviceMetadataToolExecutor(
    database: MomodingDatabase,
    private val folders: AuthorizedFoldersRepository,
) : DeviceMetadataToolHandler {
    private val dao = database.momodingDao()

    override fun handles(toolName: String): Boolean = toolName in SUPPORTED_TOOLS

    override suspend fun execute(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame {
        if (!handles(frame.toolName)) {
            return frame.failed("UNSUPPORTED_DEVICE_CAPABILITY", "Device capability is unavailable")
        }
        if (
            frame.sideEffect ||
            frame.operationId != null ||
            frame.capabilityVersion != DEVICE_CAPABILITY_VERSION ||
            isExpired(frame.expiresAt)
        ) {
            return frame.failed(
                "UNSAFE_DEVICE_TOOL_REQUEST",
                "Device metadata request is outside the active task capability",
            )
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                when (frame.toolName) {
                    CAPABILITIES_TOOL -> capabilities(frame)
                    FILES_LIST_TOOL -> filesList(frame)
                    else -> error("Unsupported metadata tool reached executor")
                }
            }
        } catch (_: TimeoutCancellationException) {
            frame.failed("DEVICE_TOOL_TIMEOUT", "Android metadata lookup timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidDeviceMetadataArguments) {
            frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Device metadata arguments are invalid")
        } catch (_: TaskGrantUnavailable) {
            frame.failed("TASK_FILE_GRANT_REQUIRED", "This task has no matching mobile folder grant")
        } catch (_: SecurityException) {
            frame.failed("AUTHORIZED_FOLDER_UNAVAILABLE", "Mobile folder authorization is unavailable")
        } catch (_: FileNotFoundException) {
            frame.failed("DOCUMENTS_PROVIDER_UNAVAILABLE", "Android Documents Provider is unavailable")
        } catch (_: IllegalStateException) {
            frame.failed("AUTHORIZED_FOLDER_UNAVAILABLE", "Mobile folder authorization is unavailable")
        } catch (_: IllegalArgumentException) {
            frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Device metadata arguments are invalid")
        }
    }

    private suspend fun capabilities(
        frame: DeviceToolRequestFrame,
    ): DeviceToolResultClientFrame {
        requireExactKeys(
            frame.arguments as? JsonObject ?: throw InvalidDeviceMetadataArguments(),
            emptySet(),
        )
        val grant = taskGrant(frame.taskId)
        return frame.succeeded(
            buildJsonObject {
                put("capabilityVersion", DEVICE_CAPABILITY_VERSION)
                put(
                    "tools",
                    buildJsonArray {
                        add(CAPABILITIES_TOOL)
                        add(FILES_LIST_TOOL)
                        add(FILES_READ_TOOL)
                        add(FILES_PREPARE_TOOL)
                        add(FILES_COMMIT_TOOL)
                        add(MEDIA_LIST_TOOL)
                    },
                )
                put(
                    "grants",
                    buildJsonArray {
                        grant?.let {
                            add(
                                buildJsonObject {
                                    put("grantId", it.grantId)
                                    put("displayName", it.displayName)
                                    put("scope", "task")
                                    put("access", "metadata_only")
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private suspend fun filesList(
        frame: DeviceToolRequestFrame,
    ): DeviceToolResultClientFrame {
        val arguments = frame.arguments as? JsonObject ?: throw InvalidDeviceMetadataArguments()
        requireExactKeys(
            arguments,
            allowed = setOf("grantId", "parentAlias", "recursive"),
            required = setOf("grantId"),
        )
        val grantId = arguments["grantId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: throw InvalidDeviceMetadataArguments()
        val parentAlias = arguments["parentAlias"]?.jsonPrimitive?.contentOrNull
            ?.also {
                if (!DOCUMENT_ALIAS.matches(it)) throw InvalidDeviceMetadataArguments()
            }
        val recursive = arguments["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val grant = taskGrant(frame.taskId)
        if (grant?.grantId != grantId) throw TaskGrantUnavailable()

        val listing = folders.metadata(
            grantId = grantId,
            maxDepth = MAX_METADATA_DEPTH,
            maxItems = MAX_METADATA_ITEMS,
        )
        val entries = selectEntries(listing.documents, parentAlias, recursive)
        return frame.succeeded(
            buildJsonObject {
                put("grantId", grantId)
                parentAlias?.let { put("parentAlias", it) }
                put("recursive", recursive)
                put(
                    "entries",
                    buildJsonArray {
                        entries.forEach { document ->
                            add(
                                buildJsonObject {
                                    put("alias", document.alias)
                                    document.parentAlias?.let { put("parentAlias", it) }
                                    put("displayName", document.displayName)
                                    put("mimeType", document.mimeType)
                                    document.byteCount?.let { put("byteCount", it) }
                                    document.lastModifiedMillis?.let {
                                        put("lastModifiedMillis", it)
                                    }
                                    put("directory", document.isDirectory)
                                },
                            )
                        }
                    },
                )
                put("truncated", listing.truncated)
                put(
                    "truncationReasons",
                    buildJsonArray {
                        listing.truncationReasons.sorted().forEach(::add)
                    },
                )
            },
        )
    }

    private suspend fun taskGrant(taskId: String): AuthorizedFolderSummary? {
        val selectedGrantId = dao.draftForTask(taskId)?.selectedGrantId ?: return null
        return folders.folders().firstOrNull {
            it.grantId == selectedGrantId && it.canRead
        }
    }

    private fun selectEntries(
        documents: List<AuthorizedDocumentMetadata>,
        parentAlias: String?,
        recursive: Boolean,
    ): List<AuthorizedDocumentMetadata> {
        if (documents.isEmpty()) return emptyList()
        val root = documents.minByOrNull(AuthorizedDocumentMetadata::depth)
            ?: return emptyList()
        val selectedParent = parentAlias ?: root.alias
        if (documents.none { it.alias == selectedParent && it.isDirectory }) {
            throw TaskGrantUnavailable()
        }
        if (!recursive) return documents.filter { it.parentAlias == selectedParent }

        val descendants = mutableSetOf(selectedParent)
        val selected = mutableListOf<AuthorizedDocumentMetadata>()
        documents.sortedBy(AuthorizedDocumentMetadata::depth).forEach { document ->
            if (document.alias == selectedParent) return@forEach
            if (document.parentAlias in descendants) {
                selected += document
                if (document.isDirectory) descendants += document.alias
            }
        }
        return selected
    }

    private fun isExpired(expiresAt: String): Boolean = try {
        !OffsetDateTime.parse(expiresAt).toInstant().isAfter(java.time.Instant.now())
    } catch (_: DateTimeParseException) {
        true
    }

    private fun requireExactKeys(
        value: JsonObject,
        allowed: Set<String>,
        required: Set<String> = emptySet(),
    ) {
        if (!value.keys.all(allowed::contains) || !value.keys.containsAll(required)) {
            throw InvalidDeviceMetadataArguments()
        }
    }

    private fun DeviceToolRequestFrame.succeeded(
        result: JsonObject,
    ): DeviceToolResultClientFrame = DeviceToolResultClientFrame(
        callId = callId,
        taskId = taskId,
        deviceId = deviceId,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = result,
    )

    private fun DeviceToolRequestFrame.failed(
        code: String,
        message: String,
    ): DeviceToolResultClientFrame = DeviceToolResultClientFrame(
        callId = callId,
        taskId = taskId,
        deviceId = deviceId,
        terminal = DeviceToolTerminalKind.FAILED,
        error = DeviceClientWireError(code, message),
    )

    private class InvalidDeviceMetadataArguments : IllegalArgumentException()
    private class TaskGrantUnavailable : SecurityException()

    companion object {
        const val DEVICE_CAPABILITY_VERSION = 1L
        const val CAPABILITIES_TOOL = "device_capabilities_get"
        const val FILES_LIST_TOOL = "device_files_list"
        const val FILES_READ_TOOL = "device_files_read"
        const val FILES_PREPARE_TOOL = "device_files_prepare_changes"
        const val FILES_COMMIT_TOOL = "device_files_commit_changes"
        const val MEDIA_LIST_TOOL = "device_media_list"
        val SUPPORTED_TOOLS = setOf(CAPABILITIES_TOOL, FILES_LIST_TOOL)
        private const val REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val MAX_METADATA_DEPTH = 8
        private const val MAX_METADATA_ITEMS = 200
        private val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
    }
}
