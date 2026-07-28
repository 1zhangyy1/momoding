package app.momoding.core.runtime.local

import app.momoding.core.shizuku.ANDROID_SHELL_UID
import app.momoding.core.shizuku.ShizukuLifecycleStage
import app.momoding.core.shizuku.ShizukuPackageDetails
import app.momoding.core.shizuku.ShizukuPackageSummary
import app.momoding.core.shizuku.ShizukuQueryResult
import app.momoding.core.shizuku.ShizukuSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhoneLocalShizukuToolExecutorTest {
    @Test
    fun `list returns only bounded package facts with shell identity`() = runBlocking {
        val client = FakeClient()
        val result = PhoneLocalShizukuToolExecutor(client).execute(
            taskId = "task-1",
            request = request(
                toolName = PhoneLocalShizukuToolExecutor.LIST_TOOL,
                arguments = buildJsonObject {
                    put("purpose", "Find settings")
                    put("includeSystem", true)
                    put("offset", 0)
                    put("limit", 10)
                },
            ),
        )

        assertFalse(result.isError)
        val payload = result.contentPayload
        assertEquals("true", payload.getValue("ok").jsonPrimitive.content)
        assertEquals(ANDROID_SHELL_UID, payload.getValue("serverUid").jsonPrimitive.content.toInt())
        assertEquals(1, payload.getValue("packages").jsonArray.size)
        val item = payload.getValue("packages").jsonArray.single().jsonObject
        assertEquals("com.android.settings", item.getValue("packageName").jsonPrimitive.content)
        assertEquals(setOf("packageName", "label", "system", "enabled"), item.keys)
        assertFalse(payload.toString().contains("/data/"))
        assertEquals(1, client.listCalls)
    }

    @Test
    fun `inspect preserves typed not-found failure`() = runBlocking {
        val client = FakeClient(
            inspectResult = ShizukuQueryResult.Failed(
                code = "PACKAGE_NOT_FOUND",
                safeMessage = "The requested Android package is not installed.",
            ),
        )
        val result = PhoneLocalShizukuToolExecutor(client).execute(
            taskId = "task-1",
            request = request(
                toolName = PhoneLocalShizukuToolExecutor.INSPECT_TOOL,
                arguments = buildJsonObject {
                    put("purpose", "Verify package")
                    put("packageName", "com.example.missing")
                },
            ),
        )

        assertTrue(result.isError)
        assertEquals(
            "PACKAGE_NOT_FOUND",
            result.contentPayload.getValue("errorCode").jsonPrimitive.content,
        )
        assertEquals(1, client.inspectCalls)
    }

    @Test
    fun `not ready and invalid inputs never reach Shizuku query`() = runBlocking {
        val notReady = FakeClient(snapshot = snapshot(ShizukuLifecycleStage.PERMISSION_REQUIRED))
        val notReadyResult = PhoneLocalShizukuToolExecutor(notReady).execute(
            taskId = "task-1",
            request = request(
                toolName = PhoneLocalShizukuToolExecutor.LIST_TOOL,
                arguments = buildJsonObject { put("purpose", "Find apps") },
            ),
        )
        assertTrue(notReadyResult.isError)
        assertEquals(
            "CAPABILITY_NOT_READY",
            notReadyResult.contentPayload.getValue("errorCode").jsonPrimitive.content,
        )
        val notReadyMalformed = PhoneLocalShizukuToolExecutor(notReady).execute(
            taskId = "task-1",
            request = request(
                toolName = PhoneLocalShizukuToolExecutor.LIST_TOOL,
                arguments = buildJsonObject {
                    put("purpose", "Find apps")
                    put("offset", "0")
                },
            ),
        )
        assertEquals(
            "INVALID_DEVICE_TOOL_ARGUMENTS",
            notReadyMalformed.contentPayload.getValue("errorCode").jsonPrimitive.content,
        )
        assertEquals(0, notReady.listCalls)

        val invalid = FakeClient()
        listOf(
            buildJsonObject {
                put("purpose", "Find apps")
                put("offset", -1)
            },
            buildJsonObject {
                put("purpose", "Find apps")
                put("limit", 101)
            },
            buildJsonObject {
                put("purpose", "Find apps")
                put("command", "pm list packages")
            },
            buildJsonObject {
                put("purpose", buildJsonObject { put("nested", true) })
            },
            buildJsonObject {
                put("purpose", "Find apps")
                put("includeSystem", buildJsonArray { add(true) })
            },
            buildJsonObject {
                put("purpose", "Find apps")
                put("offset", "0")
            },
        ).forEach { arguments ->
            val result = PhoneLocalShizukuToolExecutor(invalid).execute(
                taskId = "task-1",
                request = request(PhoneLocalShizukuToolExecutor.LIST_TOOL, arguments),
            )
            assertTrue(result.isError)
            assertEquals(
                "INVALID_DEVICE_TOOL_ARGUMENTS",
                result.contentPayload.getValue("errorCode").jsonPrimitive.content,
            )
        }
        listOf(
            buildJsonObject {
                put("purpose", "Inspect")
                put("packageName", "com.android.settings;id")
            },
            buildJsonObject {
                put("purpose", "Inspect")
                put("packageName", buildJsonArray { add("com.android.settings") })
            },
        ).forEach { arguments ->
            val invalidPackage = PhoneLocalShizukuToolExecutor(invalid).execute(
                taskId = "task-1",
                request = request(PhoneLocalShizukuToolExecutor.INSPECT_TOOL, arguments),
            )
            assertTrue(invalidPackage.isError)
            assertEquals(
                "INVALID_DEVICE_TOOL_ARGUMENTS",
                invalidPackage.contentPayload.getValue("errorCode").jsonPrimitive.content,
            )
        }
        assertEquals(0, invalid.listCalls)
        assertEquals(0, invalid.inspectCalls)
    }

    @Test
    fun `changed Shizuku service discards the query result`() = runBlocking {
        val client = FakeClient(changePidAfterList = true)
        val result = PhoneLocalShizukuToolExecutor(client).execute(
            taskId = "task-1",
            request = request(
                PhoneLocalShizukuToolExecutor.LIST_TOOL,
                buildJsonObject { put("purpose", "Find apps") },
            ),
        )

        assertTrue(result.isError)
        assertEquals(
            "PACKAGE_QUERY_SESSION_CHANGED",
            result.contentPayload.getValue("errorCode").jsonPrimitive.content,
        )
        assertFalse(result.contentPayload.toString().contains("com.android.settings"))
    }

    @Test
    fun `cancellation is never converted into a tool failure`() = runBlocking {
        val client = FakeClient(cancelList = true)
        try {
            PhoneLocalShizukuToolExecutor(client).execute(
                taskId = "task-1",
                request = request(
                    PhoneLocalShizukuToolExecutor.LIST_TOOL,
                    buildJsonObject { put("purpose", "Find apps") },
                ),
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Stop must cancel the Pi tool instead of creating a late synthetic result.
        }
    }

    private fun request(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) = PiNativeToolRequest(
        id = "native-package",
        kind = "android_package_tool",
        toolCallId = "call-package",
        toolName = toolName,
        arguments = arguments,
    )

    private class FakeClient(
        snapshot: ShizukuSnapshot = snapshot(ShizukuLifecycleStage.READY),
        private val inspectResult: ShizukuQueryResult<ShizukuPackageDetails> =
            ShizukuQueryResult.Ready(
                ShizukuPackageDetails(
                    packageName = "com.android.settings",
                    label = "Settings",
                    system = true,
                    enabled = true,
                    versionName = "1",
                    versionCode = 1,
                    minSdk = 35,
                    targetSdk = 35,
                ),
            ),
        private val changePidAfterList: Boolean = false,
        private val cancelList: Boolean = false,
    ) : PhoneLocalShizukuClient {
        private var currentSnapshot = snapshot
        var listCalls = 0
        var inspectCalls = 0

        override fun snapshot(): ShizukuSnapshot = currentSnapshot

        override suspend fun listPackages(
            includeSystem: Boolean,
            offset: Int,
            limit: Int,
        ): ShizukuQueryResult<List<ShizukuPackageSummary>> {
            listCalls += 1
            if (cancelList) throw CancellationException("stopped")
            if (changePidAfterList) {
                currentSnapshot = currentSnapshot.copy(servicePid = 222)
            }
            return ShizukuQueryResult.Ready(
                listOf(
                    ShizukuPackageSummary(
                        packageName = "com.android.settings",
                        label = "Settings",
                        system = true,
                        enabled = true,
                    ),
                ),
            )
        }

        override suspend fun inspectPackage(
            packageName: String,
        ): ShizukuQueryResult<ShizukuPackageDetails> {
            inspectCalls += 1
            return inspectResult
        }
    }

    private companion object {
        fun snapshot(stage: ShizukuLifecycleStage) = ShizukuSnapshot(
            stage = stage,
            serverUid = ANDROID_SHELL_UID,
            serverApiVersion = 13,
            clientApiVersion = 13,
            serviceUid = ANDROID_SHELL_UID,
            servicePid = 111,
            safeMessage = if (stage == ShizukuLifecycleStage.READY) {
                "Shizuku shell access is ready for selected package queries."
            } else {
                "Authorize Momoding in Shizuku."
            },
        )
    }
}
