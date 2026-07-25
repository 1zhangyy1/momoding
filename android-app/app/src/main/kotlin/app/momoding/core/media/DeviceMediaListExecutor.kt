package app.momoding.core.media

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.photoLibraryAvailability
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class PhotoLibraryScope(val wireValue: String) {
    FULL("full"),
    PARTIAL("partial"),
    DENIED("denied"),
}

data class DevicePhotoMetadata(
    val mimeType: String,
    val byteCount: Long?,
    val width: Int?,
    val height: Int?,
    val capturedAtMillis: Long?,
    val addedAtMillis: Long?,
)

fun interface PhotoLibraryScopeProvider {
    fun current(): PhotoLibraryScope
}

fun interface DevicePhotoMetadataQuery {
    suspend fun newestImages(limit: Int): List<DevicePhotoMetadata>
}

interface DeviceMediaListHandler {
    fun handles(toolName: String): Boolean
    fun currentScope(): PhotoLibraryScope
    suspend fun execute(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame
}

class DeviceMediaListExecutor(
    private val scopeProvider: PhotoLibraryScopeProvider,
    private val query: DevicePhotoMetadataQuery,
) : DeviceMediaListHandler {
    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun currentScope(): PhotoLibraryScope = scopeProvider.current()

    override suspend fun execute(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame {
        if (!handles(frame.toolName)) {
            return frame.failed("UNSUPPORTED_DEVICE_CAPABILITY", "Photo-library metadata is unavailable")
        }
        if (
            frame.sideEffect ||
            frame.operationId != null ||
            frame.capabilityVersion != CAPABILITY_VERSION ||
            isExpired(frame.expiresAt)
        ) {
            return frame.failed("UNSAFE_DEVICE_TOOL_REQUEST", "Photo-library request is outside the active task capability")
        }
        val arguments = frame.arguments as? JsonObject
            ?: return frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Photo-library arguments are invalid")
        if (!arguments.keys.all(ALLOWED_KEYS::contains) || !arguments.keys.contains("purpose")) {
            return frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Photo-library arguments are invalid")
        }
        val purpose = arguments["purpose"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (purpose.isEmpty() || purpose.length > MAX_PURPOSE_UTF16) {
            return frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Photo-library arguments are invalid")
        }
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_ITEMS) {
            return frame.failed("INVALID_DEVICE_TOOL_ARGUMENTS", "Photo-library arguments are invalid")
        }
        val scope = currentScope()
        if (scope == PhotoLibraryScope.DENIED) {
            return frame.failed("PHOTO_LIBRARY_PERMISSION_REQUIRED", "Enable photo-library access in Device capabilities")
        }
        return try {
            val items = withTimeout(REQUEST_TIMEOUT_MILLIS) { query.newestImages(limit) }.take(limit)
            frame.succeeded(
                buildJsonObject {
                    put("access", scope.wireValue)
                    put("limit", limit)
                    put("returnedCount", items.size)
                    put("items", buildJsonArray {
                        items.forEachIndexed { index, item ->
                            add(buildJsonObject {
                                put("index", index + 1)
                                put("mimeType", item.mimeType)
                                item.byteCount?.takeIf { it >= 0L }?.let { put("byteCount", it) }
                                item.width?.takeIf { it > 0 }?.let { put("width", it) }
                                item.height?.takeIf { it > 0 }?.let { put("height", it) }
                                item.capturedAtMillis?.takeIf { it > 0L }?.let { put("capturedAtMillis", it) }
                                item.addedAtMillis?.takeIf { it > 0L }?.let { put("addedAtMillis", it) }
                            })
                        }
                    })
                },
            )
        } catch (_: TimeoutCancellationException) {
            frame.failed("DEVICE_TOOL_TIMEOUT", "Photo-library lookup timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            frame.failed("PHOTO_LIBRARY_PERMISSION_REQUIRED", "Enable photo-library access in Device capabilities")
        } catch (_: Exception) {
            frame.failed("PHOTO_LIBRARY_UNAVAILABLE", "Photo-library metadata is temporarily unavailable")
        }
    }

    private fun isExpired(expiresAt: String): Boolean = try {
        !OffsetDateTime.parse(expiresAt).toInstant().isAfter(java.time.Instant.now())
    } catch (_: DateTimeParseException) {
        true
    }

    private fun DeviceToolRequestFrame.succeeded(result: JsonObject) = DeviceToolResultClientFrame(
        callId = callId,
        taskId = taskId,
        deviceId = deviceId,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = result,
    )

    private fun DeviceToolRequestFrame.failed(code: String, message: String) =
        DeviceToolResultClientFrame(
            callId = callId,
            taskId = taskId,
            deviceId = deviceId,
            terminal = DeviceToolTerminalKind.FAILED,
            error = DeviceClientWireError(code, message),
        )

    companion object {
        const val TOOL_NAME = "device_media_list"
        const val CAPABILITY_VERSION = 1L
        const val MAX_ITEMS = 20
        private const val DEFAULT_LIMIT = 20
        private const val MAX_PURPOSE_UTF16 = 512
        private const val REQUEST_TIMEOUT_MILLIS = 5_000L
        private val ALLOWED_KEYS = setOf("purpose", "limit")

        fun create(context: Context): DeviceMediaListExecutor {
            val appContext = context.applicationContext
            return DeviceMediaListExecutor(
                scopeProvider = PhotoLibraryScopeProvider {
                    when (photoLibraryAvailability(android.os.Build.VERSION.SDK_INT) { permission ->
                        androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }) {
                        CapabilityAvailability.READY -> PhotoLibraryScope.FULL
                        CapabilityAvailability.PARTIAL -> PhotoLibraryScope.PARTIAL
                        else -> PhotoLibraryScope.DENIED
                    }
                },
                query = AndroidMediaStorePhotoQuery(appContext.contentResolver),
            )
        }
    }
}

private class AndroidMediaStorePhotoQuery(
    private val resolver: ContentResolver,
) : DevicePhotoMetadataQuery {
    override suspend fun newestImages(limit: Int): List<DevicePhotoMetadata> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val queryArgs = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null)
            ?.use { cursor ->
                val mime = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val byteCount = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val captured = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext() && this.size < limit) {
                        add(
                            DevicePhotoMetadata(
                                mimeType = cursor.getString(mime)?.takeIf(MEDIA_MIME::matches) ?: "image/*",
                                byteCount = cursor.longOrNull(byteCount),
                                width = cursor.intOrNull(width),
                                height = cursor.intOrNull(height),
                                capturedAtMillis = cursor.longOrNull(captured),
                                addedAtMillis = cursor.longOrNull(added)?.let { seconds -> seconds * 1_000L },
                            ),
                        )
                    }
                }
            }.orEmpty()
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private companion object {
        val MEDIA_MIME = Regex("^image/[A-Za-z0-9.+-]{1,120}$")
    }
}
