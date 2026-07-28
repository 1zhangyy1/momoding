package app.momoding.core.files

import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
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
 * Executes only the two metadata-only Pi file tools.
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
    private val sharedStorage: SharedStorageRepository? = null,
    private val capabilityRegistry: AndroidCapabilityRegistry? = null,
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
        val capabilityStates = capabilityRegistry?.refreshNow().orEmpty()
        val advertisedTools = if (capabilityRegistry == null) {
            LEGACY_CAPABILITY_TOOLS
        } else {
            PHONE_LOCAL_CAPABILITY_TOOLS
        }
        return frame.succeeded(
            buildJsonObject {
                put("capabilityVersion", DEVICE_CAPABILITY_VERSION)
                put(
                    "tools",
                    buildJsonArray {
                        advertisedTools.forEach(::add)
                    },
                )
                put(
                    "capabilities",
                    buildJsonArray {
                        capabilityStates.forEach { state ->
                            add(state.toToolJson())
                        }
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
                        sharedStorage?.roots()?.forEach { root ->
                            add(
                                buildJsonObject {
                                    put("grantId", root.grantId)
                                    put("displayName", root.displayName)
                                    put("rootId", root.rootId)
                                    put("scope", "shared_storage")
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
        val shared = sharedStorage?.takeIf { it.isSharedGrant(grantId) }
        val listing = if (shared != null) {
            shared.metadata(
                grantId = grantId,
                maxDepth = MAX_METADATA_DEPTH,
                maxItems = MAX_METADATA_ITEMS,
            )
        } else {
            val grant = taskGrant(frame.taskId)
            if (grant?.grantId != grantId) throw TaskGrantUnavailable()
            folders.metadata(
                grantId = grantId,
                maxDepth = MAX_METADATA_DEPTH,
                maxItems = MAX_METADATA_ITEMS,
            )
        }
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

    private fun AndroidCapabilityState.toToolJson() = buildJsonObject {
        put("id", id.name.lowercase())
        put("availability", availability.name.lowercase())
        put("source", source)
        put("safeMessage", safeMessage)
        put(
            "toolNames",
            buildJsonArray {
                capabilityToolNames(id).forEach(::add)
            },
        )
    }

    private fun capabilityToolNames(id: AndroidCapabilityId): List<String> = when (id) {
        AndroidCapabilityId.SAF_FOLDERS,
        AndroidCapabilityId.ALL_FILES,
        -> FILE_CAPABILITY_TOOLS
        AndroidCapabilityId.PHOTO_LIBRARY -> listOf(MEDIA_LIST_TOOL)
        AndroidCapabilityId.ACCESSIBILITY_CONTROL -> listOf(
            UI_INSPECT_TOOL,
            UI_ACTION_TOOL,
            SCREEN_CAPTURE_TOOL,
        )
        AndroidCapabilityId.SCREEN_CAPTURE -> listOf(SCREEN_CAPTURE_TOOL)
        AndroidCapabilityId.SHIZUKU_SHELL_UID -> listOf(
            PACKAGES_LIST_TOOL,
            PACKAGE_INSPECT_TOOL,
        )
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
        const val SCREEN_CAPTURE_TOOL = "device_screen_capture"
        const val UI_INSPECT_TOOL = "device_ui_inspect"
        const val UI_ACTION_TOOL = "device_ui_action"
        const val PACKAGES_LIST_TOOL = "device_packages_list"
        const val PACKAGE_INSPECT_TOOL = "device_package_inspect"
        val SUPPORTED_TOOLS = setOf(CAPABILITIES_TOOL, FILES_LIST_TOOL)
        private val FILE_CAPABILITY_TOOLS = listOf(
            FILES_LIST_TOOL,
            FILES_READ_TOOL,
            FILES_PREPARE_TOOL,
            FILES_COMMIT_TOOL,
        )
        private val LEGACY_CAPABILITY_TOOLS = listOf(
            CAPABILITIES_TOOL,
            *FILE_CAPABILITY_TOOLS.toTypedArray(),
            MEDIA_LIST_TOOL,
        )
        private val PHONE_LOCAL_CAPABILITY_TOOLS = listOf(
            *LEGACY_CAPABILITY_TOOLS.toTypedArray(),
            SCREEN_CAPTURE_TOOL,
            UI_INSPECT_TOOL,
            UI_ACTION_TOOL,
            PACKAGES_LIST_TOOL,
            PACKAGE_INSPECT_TOOL,
        )
        private const val REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val MAX_METADATA_DEPTH = 8
        private const val MAX_METADATA_ITEMS = 200
        private val DOCUMENT_ALIAS = Regex("^doc-[0-9a-f]{24}$")
    }
}
