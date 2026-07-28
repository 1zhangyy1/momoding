package app.momoding.core.runtime.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.files.DeviceMetadataToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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
class PhoneLocalCapabilityRoutingTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `immediate capability failure reaches Pi as an error result`() = runTest {
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            metadataTools = FailingMetadataHandler(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(
            bridge.handleNativeRequest(
                taskId = "task-1",
                request = request(
                    kind = "android_file_tool",
                    toolName = "device_capabilities_get",
                ),
            ),
        )

        assertTrue(result.isError)
        assertEquals("CAPABILITY_PROBE_FAILED", result.contentPayload["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `package tools route directly without an approval-mode fork`() = runTest {
        val packageHandler = FixturePackageHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            packageTools = packageHandler,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(
            bridge.handleNativeRequest(
                taskId = "task-1",
                request = request(
                    kind = "android_package_tool",
                    toolName = "device_packages_list",
                    arguments = buildJsonObject { put("purpose", "Find apps") },
                ),
            ),
        )

        assertFalse(result.isError)
        assertEquals("task-1", packageHandler.taskId)
        assertEquals("device_packages_list", packageHandler.toolName)
        assertEquals("true", result.contentPayload["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun `capability request tool routes directly to the Android capability handler`() = runTest {
        val capabilityHandler = FixtureCapabilityRequestHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            capabilityRequestTools = capabilityHandler,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = requireNotNull(
            bridge.handleNativeRequest(
                taskId = "task-1",
                request = request(
                    kind = "android_capability_tool",
                    toolName = "device_capability_request",
                    arguments = buildJsonObject {
                        put("capability", "accessibility_control")
                        put("purpose", "Inspect the current screen")
                    },
                ),
            ),
        )

        assertFalse(result.isError)
        assertEquals("task-1", capabilityHandler.taskId)
        assertEquals("accessibility_control", capabilityHandler.capability)
    }

    private fun request(
        kind: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject = buildJsonObject {},
    ) = PiNativeToolRequest(
        id = "native-$toolName",
        kind = kind,
        toolCallId = "call-$toolName",
        toolName = toolName,
        arguments = arguments,
    )

    private class FailingMetadataHandler : DeviceMetadataToolHandler {
        override fun handles(toolName: String): Boolean = toolName == "device_capabilities_get"

        override suspend fun execute(
            frame: DeviceToolRequestFrame,
        ) = DeviceToolResultClientFrame(
            callId = frame.callId,
            taskId = frame.taskId,
            deviceId = frame.deviceId,
            terminal = DeviceToolTerminalKind.FAILED,
            error = DeviceClientWireError(
                code = "CAPABILITY_PROBE_FAILED",
                message = "Android capabilities could not be checked.",
            ),
        )
    }

    private class FixturePackageHandler : PhoneLocalPackageToolHandler {
        var taskId: String? = null
        var toolName: String? = null

        override fun handles(toolName: String): Boolean =
            toolName == "device_packages_list"

        override suspend fun execute(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult {
            this.taskId = taskId
            this.toolName = request.toolName
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject { put("ok", true) },
            )
        }
    }

    private class FixtureCapabilityRequestHandler : PhoneLocalCapabilityRequestToolHandler {
        var taskId: String? = null
        var capability: String? = null

        override suspend fun execute(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult {
            this.taskId = taskId
            capability = request.arguments["capability"]?.jsonPrimitive?.content
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject { put("ready", true) },
            )
        }
    }
}
