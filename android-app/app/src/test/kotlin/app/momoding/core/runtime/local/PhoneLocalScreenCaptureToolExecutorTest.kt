package app.momoding.core.runtime.local

import app.momoding.core.accessibility.EncodedScreenCapture
import app.momoding.core.accessibility.ScreenCapturePerformer
import app.momoding.core.accessibility.ScreenCaptureResult
import app.momoding.core.accessibility.ScreenCaptureSource
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalScreenCaptureToolExecutorTest {
    @Test
    fun successfulCaptureReturnsOneLiveImageAndReferenceOnlyMetadata() = runTest {
        val bytes = "screen-bytes".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val executor = PhoneLocalScreenCaptureToolExecutor(
            coordinator = ScreenCapturePerformer { targetPackage ->
                assertEquals("com.example.target", targetPackage)
                ScreenCaptureResult.Ready(
                    EncodedScreenCapture(
                        bytes = bytes,
                        mimeType = "image/jpeg",
                        width = 720,
                        height = 1_440,
                        contentSha256 = digest,
                        source = ScreenCaptureSource.ACCESSIBILITY,
                        foregroundPackage = targetPackage,
                    ),
                )
            },
        )

        val result = executor.execute("task-1", request())

        assertFalse(result.isError)
        assertEquals(digest, result.details["contentSha256"]?.jsonPrimitive?.content)
        assertEquals(true, result.details["liveOnly"]?.jsonPrimitive?.content?.toBoolean())
        assertFalse(result.contentPayload.toString().contains("screen-bytes"))
        val content = requireNotNull(result.content)
        assertEquals(listOf("text", "image"), content.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals("image/jpeg", content[1].jsonObject["mimeType"]?.jsonPrimitive?.content)
    }

    @Test
    fun failureHasNoImageAndTaskBudgetStopsAtTenAttempts() = runTest {
        var now = 1_000L
        var stopCount = 0
        val performer = object : ScreenCapturePerformer {
            override suspend fun capture(targetPackage: String?) =
                ScreenCaptureResult.Failed(
                    "SCREEN_CAPTURE_SESSION_REQUIRED",
                    "Start a session.",
                )

            override fun stop() {
                stopCount += 1
            }
        }
        val executor = PhoneLocalScreenCaptureToolExecutor(
            coordinator = performer,
            nowMillis = { now },
        )

        executor.execute("task-1", request())
        executor.stopTask(
            "task-1",
            PhoneLocalScreenCaptureToolExecutor.NORMAL_RUN_CLEANUP_REASON,
        )
        repeat(9) {
            val result = executor.execute("task-1", request())
            assertTrue(result.isError)
            assertNull(result.content)
            assertEquals(
                "SCREEN_CAPTURE_SESSION_REQUIRED",
                result.details["errorCode"]?.jsonPrimitive?.content,
            )
        }
        val limited = executor.execute("task-1", request())
        assertEquals(
            "SCREEN_CAPTURE_LIMIT_REACHED",
            limited.details["errorCode"]?.jsonPrimitive?.content,
        )

        now += 10 * 60_000L
        assertEquals(
            "SCREEN_CAPTURE_SESSION_EXPIRED",
            executor.execute("task-1", request())
                .details["errorCode"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "SCREEN_CAPTURE_SESSION_EXPIRED",
            executor.execute("task-1", request())
                .details["errorCode"]?.jsonPrimitive?.content,
        )
        assertEquals(1, stopCount)
    }

    @Test
    fun stoppingOwnedTaskReleasesScreenSessionOnce() = runTest {
        var stopCount = 0
        val performer = object : ScreenCapturePerformer {
            override suspend fun capture(targetPackage: String?) =
                ScreenCaptureResult.Failed(
                    "SCREEN_CAPTURE_SESSION_REQUIRED",
                    "Start a session.",
                )

            override fun stop() {
                stopCount += 1
            }
        }
        val executor = PhoneLocalScreenCaptureToolExecutor(performer)

        executor.stopTask("unknown", "session_close")
        assertEquals(0, stopCount)

        executor.execute("task-1", request())
        executor.stopTask(
            "task-1",
            PhoneLocalScreenCaptureToolExecutor.NORMAL_RUN_CLEANUP_REASON,
        )
        assertEquals(0, stopCount)
        executor.stopTask("task-1", "session_close")
        executor.stopTask("task-1", "session_close")

        assertEquals(1, stopCount)
    }

    private fun request() = PiNativeToolRequest(
        id = "native-screen",
        kind = "android_screen_tool",
        toolCallId = "call-screen",
        toolName = PhoneLocalScreenCaptureToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("purpose", "Understand the visible error")
            put("targetPackage", "com.example.target")
        },
    )
}
