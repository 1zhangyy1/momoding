package app.momoding.core.clipboard

import app.momoding.core.runtime.local.PiNativeToolRequest
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalClipboardToolExecutorTest {
    @Test
    fun `parser enforces exact bounded action branches`() {
        assertTrue(ClipboardToolRequestParser.parse(arguments("get")) is ClipboardToolRequest.Get)
        assertEquals(
            "copy me",
            (ClipboardToolRequestParser.parse(arguments("set", "copy me")) as
                ClipboardToolRequest.Set).text,
        )
        assertTrue(
            ClipboardToolRequestParser.parse(arguments("clear")) is ClipboardToolRequest.Clear,
        )

        listOf(
            buildJsonObject {
                put("action", "get")
                put("purpose", "read")
                put("text", "not allowed")
            },
            arguments("set", ""),
            arguments("set", "x".repeat(4_097)),
            arguments("delete"),
            buildJsonObject {
                put("action", "clear")
                put("purpose", "x".repeat(161))
            },
        ).forEach { invalid ->
            assertTrue(
                runCatching { ClipboardToolRequestParser.parse(invalid) }.exceptionOrNull()
                    is ClipboardToolArgumentsException,
            )
        }
    }

    @Test
    fun `ordinary read is live only bounded and does not echo purpose`() = runTest {
        val gateway = FakeClipboardGateway(
            ClipboardSnapshot.Text("ordinary note", sourceMarkedSensitive = false),
        )
        val executor = PhoneLocalClipboardToolExecutor(
            gateway = gateway,
            now = { Instant.parse("2026-07-29T05:00:00Z") },
        )
        val result = executor.executeRead(
            TASK_ID,
            request(arguments("get", purpose = "PRIVATE_PURPOSE")),
        )

        assertFalse(result.isError)
        assertEquals(
            "ordinary note",
            result.contentPayload["data"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
        assertEquals(
            13,
            result.contentPayload["data"]?.jsonObject
                ?.get("characterCount")?.jsonPrimitive?.int,
        )
        assertEquals("true", result.details?.get("liveOnly")?.jsonPrimitive?.content)
        assertEquals("clipboard", result.details?.get("dataClass")?.jsonPrimitive?.content)
        assertEquals(
            sha256(result.contentPayload.toString()),
            result.details?.get("contentSha256")?.jsonPrimitive?.content,
        )
        assertFalse(result.contentPayload.toString().contains("PRIVATE_PURPOSE"))
    }

    @Test
    fun `sensitive source and local secret patterns are withheld without echo`() = runTest {
        val values = listOf(
            ClipboardSnapshot.Text("harmless note", sourceMarkedSensitive = true),
            ClipboardSnapshot.Text(
                "-----BEGIN " +
                    "PRIVATE KEY-----\nPRIVATE_SECRET\n-----END PRIVATE KEY-----",
                false,
            ),
            ClipboardSnapshot.Text("password=PRIVATE_PASSWORD", false),
            ClipboardSnapshot.Text("123456", false),
            ClipboardSnapshot.Text("Your verification code is 123456", false),
            ClipboardSnapshot.Text("验证码：654321，请勿告诉他人", false),
            ClipboardSnapshot.Text("4111 1111 1111 1111", false),
            ClipboardSnapshot.Text("Bearer PRIVATE_BEARER_TOKEN_12345", false),
        )
        values.forEach { snapshot ->
            val result = PhoneLocalClipboardToolExecutor(
                gateway = FakeClipboardGateway(snapshot),
            ).executeRead(TASK_ID, request(arguments("get")))
            assertTrue(result.isError)
            assertEquals(
                "CLIPBOARD_CONTENT_RESTRICTED",
                result.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertFalse(result.contentPayload.toString().contains((snapshot as ClipboardSnapshot.Text).value))
            assertNull(result.details?.get("dataClass"))
        }
    }

    @Test
    fun `unsupported empty oversized and background reads have stable errors`() = runTest {
        val cases = listOf(
            ClipboardSnapshot.Unsupported to "CLIPBOARD_FORMAT_UNSUPPORTED",
            ClipboardSnapshot.Empty to "CLIPBOARD_EMPTY",
            ClipboardSnapshot.Text("a".repeat(8_193), false) to
                "CLIPBOARD_CONTENT_TOO_LARGE",
        )
        cases.forEach { (snapshot, code) ->
            val result = PhoneLocalClipboardToolExecutor(
                gateway = FakeClipboardGateway(snapshot),
            ).executeRead(TASK_ID, request(arguments("get")))
            assertEquals(
                code,
                result.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
        }
        val background = PhoneLocalClipboardToolExecutor(
            gateway = FakeClipboardGateway(ClipboardSnapshot.Text("note", false)),
            foregroundGate = ClipboardForegroundGate { false },
        ).executeRead(TASK_ID, request(arguments("get")))
        assertEquals(
            "APP_NOT_FOREGROUND",
            background.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `set and clear execute only matching approved plans and post verify`() = runTest {
        val gateway = FakeClipboardGateway(ClipboardSnapshot.Empty)
        val executor = PhoneLocalClipboardToolExecutor(
            gateway = gateway,
            now = { Instant.parse("2026-07-29T05:00:00Z") },
        )
        val setRequest = request(arguments("set", "ordinary note"))
        val preparation = executor.prepareMutation(TASK_ID, setRequest)
        val setPlan = (preparation as ClipboardMutationPreparation.Ready).plan
        var dispatches = 0
        val setResult = executor.executeMutation(TASK_ID, setRequest, setPlan) {
            dispatches += 1
        }
        assertFalse(setResult.isError)
        assertEquals(1, dispatches)
        assertEquals(
            "text",
            setResult.contentPayload["data"]?.jsonObject
                ?.get("state")?.jsonPrimitive?.content,
        )
        assertEquals(
            false,
            setResult.contentPayload["data"]?.jsonObject
                ?.get("sensitive")?.jsonPrimitive?.boolean,
        )
        assertEquals("ordinary note", gateway.text)

        val clearRequest = request(arguments("clear"))
        val clearPlan = (
            executor.prepareMutation(TASK_ID, clearRequest) as
                ClipboardMutationPreparation.Ready
            ).plan
        val clearResult = executor.executeMutation(TASK_ID, clearRequest, clearPlan) {
            dispatches += 1
        }
        assertFalse(clearResult.isError)
        assertEquals(2, dispatches)
        assertNull(gateway.text)
        assertEquals(
            "verified",
            clearResult.contentPayload["verification"]?.jsonObject
                ?.get("status")?.jsonPrimitive?.content,
        )

        val changed = request(arguments("set", "different"))
        val conflict = executor.executeMutation(TASK_ID, changed, setPlan) {}
        assertEquals(
            "CONFLICT",
            conflict.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `sensitive set marks Android clip but durable plan metadata contains no text`() = runTest {
        val gateway = FakeClipboardGateway(ClipboardSnapshot.Empty)
        val executor = PhoneLocalClipboardToolExecutor(gateway = gateway)
        val secret = "password=PRIVATE_PASSWORD"
        val request = request(arguments("set", secret))
        val plan = (
            executor.prepareMutation(TASK_ID, request) as ClipboardMutationPreparation.Ready
            ).plan
        assertTrue(plan.sensitive)
        assertFalse(plan.summary.contains(secret))
        assertFalse(plan.details.contains(secret))
        assertEquals(64, plan.requestDigest.length)
        assertEquals(64, plan.planDigest.length)

        val result = executor.executeMutation(TASK_ID, request, plan) {}
        assertFalse(result.isError)
        assertTrue(gateway.sensitive)
        assertFalse(result.contentPayload.toString().contains(secret))
    }

    @Test
    fun `foreground loss after dispatch fence returns unknown without writing in background`() =
        runTest {
            val gateway = FakeClipboardGateway(ClipboardSnapshot.Empty)
            var foreground = true
            val executor = PhoneLocalClipboardToolExecutor(
                gateway = gateway,
                foregroundGate = ClipboardForegroundGate { foreground },
            )
            val request = request(arguments("set", "ordinary note"))
            val plan = (
                executor.prepareMutation(TASK_ID, request) as
                    ClipboardMutationPreparation.Ready
                ).plan

            val result = executor.executeMutation(TASK_ID, request, plan) {
                foreground = false
            }

            assertTrue(result.isError)
            assertEquals(
                "OUTCOME_UNKNOWN",
                result.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertNull(gateway.text)
        }

    @Test
    fun `Stop cancels an in-flight read`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val executor = PhoneLocalClipboardToolExecutor(
            gateway = object : ClipboardGateway {
                override suspend fun read(): ClipboardSnapshot {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }

                override suspend fun setText(text: String, sensitive: Boolean) = Unit
                override suspend fun clear() = Unit
            },
        )
        val pending = async {
            executor.executeRead(TASK_ID, request(arguments("get")))
        }
        started.await()
        executor.stopTask(TASK_ID, "user_stop")
        assertTrue(runCatching { pending.await() }.isFailure)
        cancelled.await()
    }

    @Test
    fun `classifier avoids ordinary numbers and detects fixed secret classes`() {
        assertNull(ClipboardSensitiveClassifier.classify("Meet at 12:30 on floor 6"))
        assertNull(ClipboardSensitiveClassifier.classify("order number 123456"))
        assertNotNull(ClipboardSensitiveClassifier.classify("123456"))
        assertEquals(
            ClipboardSensitivity.API_TOKEN,
            ClipboardSensitiveClassifier.classify(
                "sk-or-v1-" + "1234567890abcdefghijklmnop",
            ),
        )
    }

    private class FakeClipboardGateway(
        initial: ClipboardSnapshot,
    ) : ClipboardGateway {
        var text: String? = (initial as? ClipboardSnapshot.Text)?.value
        var sensitive: Boolean =
            (initial as? ClipboardSnapshot.Text)?.sourceMarkedSensitive == true
        private var snapshot: ClipboardSnapshot = initial

        override suspend fun read(): ClipboardSnapshot = when {
            snapshot is ClipboardSnapshot.Unsupported -> ClipboardSnapshot.Unsupported
            text == null -> ClipboardSnapshot.Empty
            else -> ClipboardSnapshot.Text(requireNotNull(text), sensitive)
        }

        override suspend fun setText(text: String, sensitive: Boolean) {
            this.text = text
            this.sensitive = sensitive
            snapshot = ClipboardSnapshot.Text(text, sensitive)
        }

        override suspend fun clear() {
            text = null
            sensitive = false
            snapshot = ClipboardSnapshot.Empty
        }
    }

    private fun request(arguments: JsonObject) = PiNativeToolRequest(
        id = "native-clipboard",
        kind = "android_clipboard_tool",
        toolCallId = "pi-clipboard",
        toolName = PhoneLocalClipboardToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun arguments(
        action: String,
        text: String? = null,
        purpose: String = "Use the clipboard for this task",
    ) = buildJsonObject {
        put("action", action)
        if (text != null) put("text", text)
        put("purpose", purpose)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
    }
}
