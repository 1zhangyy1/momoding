package app.momoding.core.media

import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolTerminalKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaListExecutorTest {
    @Test
    fun `full access returns only bounded metadata without identifiers`() = runTest {
        var requestedLimit = 0
        val executor = DeviceMediaListExecutor(
            scopeProvider = PhotoLibraryScopeProvider { PhotoLibraryScope.FULL },
            query = DevicePhotoMetadataQuery { limit ->
                requestedLimit = limit
                List(25) { index ->
                    DevicePhotoMetadata(
                        mediaId = index.toLong() + 100L,
                        mimeType = "image/jpeg",
                        byteCount = 1_000L + index,
                        width = 1080,
                        height = 1920,
                        capturedAtMillis = 1_000L,
                        addedAtMillis = 2_000L,
                    )
                }
            },
        )

        val result = executor.execute(request(buildJsonObject {
            put("purpose", "Find recently added screenshots")
            put("limit", 3)
        }))

        assertEquals(DeviceToolTerminalKind.SUCCEEDED, result.terminal)
        assertEquals(3, requestedLimit)
        assertEquals(3, result.result!!.jsonObject.getValue("items").jsonArray.size)
        assertEquals("full", result.result!!.jsonObject.getValue("access").jsonPrimitive.content)
        result.result!!.jsonObject.getValue("items").jsonArray.forEach { item ->
            assertTrue(
                item.jsonObject.getValue("mediaHandle").jsonPrimitive.content
                    .matches(Regex("^media-[0-9a-f]{24}$")),
            )
        }
        listOf("content://", "path", "fileName", "location", "exif", "latitude", "longitude")
            .forEach { forbidden -> assertFalse(result.result.toString().contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun `denied permission fails before querying MediaStore`() = runTest {
        var queried = false
        var requested = false
        val executor = DeviceMediaListExecutor(
            scopeProvider = PhotoLibraryScopeProvider { PhotoLibraryScope.DENIED },
            query = DevicePhotoMetadataQuery {
                queried = true
                emptyList()
            },
            permissionRequester = PhotoLibraryPermissionRequester {
                requested = true
                false
            },
        )

        val result = executor.execute(request(buildJsonObject { put("purpose", "Inspect photos") }))

        assertEquals(DeviceToolTerminalKind.FAILED, result.terminal)
        assertEquals("PHOTO_LIBRARY_PERMISSION_REQUIRED", result.error?.code)
        assertEquals(true, requested)
        assertFalse(queried)
    }

    @Test
    fun `missing permission requests Android access then rechecks live scope`() = runTest {
        var scope = PhotoLibraryScope.DENIED
        var queried = false
        val executor = DeviceMediaListExecutor(
            scopeProvider = PhotoLibraryScopeProvider { scope },
            query = DevicePhotoMetadataQuery {
                queried = true
                emptyList()
            },
            permissionRequester = PhotoLibraryPermissionRequester {
                scope = PhotoLibraryScope.PARTIAL
                true
            },
        )

        val result = executor.execute(request(buildJsonObject { put("purpose", "Inspect photos") }))

        assertEquals(DeviceToolTerminalKind.SUCCEEDED, result.terminal)
        assertEquals("partial", result.result!!.jsonObject.getValue("access").jsonPrimitive.content)
        assertEquals(true, queried)
    }

    @Test
    fun `request rejects extra fields and limits above twenty`() = runTest {
        val executor = DeviceMediaListExecutor(
            scopeProvider = PhotoLibraryScopeProvider { PhotoLibraryScope.PARTIAL },
            query = DevicePhotoMetadataQuery { emptyList() },
        )

        val extra = executor.execute(request(buildJsonObject {
            put("purpose", "Inspect photos")
            put("path", "/storage/emulated/0")
        }))
        val tooMany = executor.execute(request(buildJsonObject {
            put("purpose", "Inspect photos")
            put("limit", 21)
        }))

        assertEquals("INVALID_DEVICE_TOOL_ARGUMENTS", extra.error?.code)
        assertEquals("INVALID_DEVICE_TOOL_ARGUMENTS", tooMany.error?.code)
    }

    private fun request(arguments: JsonObject) = DeviceToolRequestFrame(
        protocolVersion = 1,
        kind = "device.tool.request",
        callId = "55555555-5555-4555-8555-555555555555",
        taskId = "33333333-3333-4333-8333-333333333333",
        piToolCallId = "pi-media-call",
        deviceId = "phone-local-android",
        toolName = DeviceMediaListExecutor.TOOL_NAME,
        arguments = arguments,
        sideEffect = false,
        expiresAt = "2099-01-01T00:00:00Z",
        capabilityVersion = DeviceMediaListExecutor.CAPABILITY_VERSION,
    )
}
