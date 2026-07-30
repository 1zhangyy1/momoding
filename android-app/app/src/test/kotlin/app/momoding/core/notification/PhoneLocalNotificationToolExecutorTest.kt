package app.momoding.core.notification

import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalNotificationToolExecutorTest {
    @Test
    fun `parser enforces six exact bounded action branches`() {
        assertTrue(NotificationToolRequestParser.parse(arguments("status")) is
            NotificationToolRequest.Status)
        assertEquals(
            10,
            (NotificationToolRequestParser.parse(arguments("list_active")) as
                NotificationToolRequest.ListActive).limit,
        )
        assertEquals(
            20,
            (NotificationToolRequestParser.parse(
                arguments("list_active", limit = 20),
            ) as NotificationToolRequest.ListActive).limit,
        )
        assertTrue(NotificationToolRequestParser.parse(
            arguments("post", title = "Title", message = "Body"),
        ) is NotificationToolRequest.Post)
        assertEquals(
            "Trimmed",
            (NotificationToolRequestParser.parse(
                arguments("post", title = "  Trimmed  ", message = "Body"),
            ) as NotificationToolRequest.Post).title,
        )

        listOf(
            arguments("post", title = "", message = "Body"),
            arguments("post", title = "x".repeat(81), message = "Body"),
            arguments("post", title = "Title", message = "x".repeat(241)),
            arguments("list_active", limit = 21),
            arguments("cancel", handle = "notification-guess"),
            buildJsonObject {
                put("action", "status")
                put("package", "other.app")
            },
            buildJsonObject {
                put("action", buildJsonObject { put("nested", true) })
            },
            buildJsonObject {
                put("action", "list_active")
                put("limit", JsonPrimitive("10"))
            },
            buildJsonObject {
                put("action", "post")
                put("title", true)
                put("message", "Body")
            },
        ).forEach { invalid ->
            assertTrue(
                runCatching { NotificationToolRequestParser.parse(invalid) }.exceptionOrNull()
                    is NotificationToolArgumentsException,
            )
        }
    }

    @Test
    fun `status and list expose only bounded app owned task handles`() = runTest {
        val gateway = FakeNotificationGateway()
        val own = NotificationIdentity(namespace(TASK_ID) + "a".repeat(32), 41_820)
        val other = NotificationIdentity(namespace(OTHER_TASK_ID) + "b".repeat(32), 41_820)
        gateway.active[own] = ActiveAgentNotification(own, "Own", "Task body", 2L)
        gateway.active[other] = ActiveAgentNotification(other, "Other", "Private", 1L)
        val executor = executor(gateway)

        val status = executor.executeImmediate(TASK_ID, request(arguments("status")))
        assertFalse(status.isError)
        assertEquals(
            "true",
            status.contentPayload["data"]?.jsonObject
                ?.get("readyToPost")?.jsonPrimitive?.content,
        )

        val listed = executor.executeImmediate(
            TASK_ID,
            request(arguments("list_active", limit = 20)),
        )
        val items = listed.contentPayload["data"]?.jsonObject
            ?.get("items")?.jsonArray.orEmpty()
        assertEquals(1, items.size)
        val item = items.single().jsonObject
        assertEquals("Own", item["title"]?.jsonPrimitive?.content)
        assertEquals("Task body", item["message"]?.jsonPrimitive?.content)
        assertTrue(
            item["notificationHandle"]?.jsonPrimitive?.content
                ?.matches(Regex("^notification-[0-9a-f]{32}$")) == true,
        )
        assertFalse(listed.contentPayload.toString().contains(other.tag))
        assertFalse(listed.contentPayload.toString().contains(own.tag))
    }

    @Test
    fun `post update and cancel use one stable handle and verify live Android state`() = runTest {
        val gateway = FakeNotificationGateway()
        val executor = executor(gateway)
        val post = request(
            arguments("post", title = "Export", message = "Ready"),
            toolCallId = "post-call",
        )
        val postPlan = (
            executor.prepareMutation(TASK_ID, post) as NotificationMutationPreparation.Ready
            ).plan
        var dispatches = 0
        val posted = executor.executeMutation(TASK_ID, post, postPlan) { dispatches += 1 }
        assertFalse(posted.isError)
        val handle = posted.contentPayload["data"]?.jsonObject
            ?.get("notificationHandle")?.jsonPrimitive?.content.orEmpty()
        assertTrue(handle.matches(Regex("^notification-[0-9a-f]{32}$")))
        assertEquals(1, gateway.posts)

        val update = request(
            arguments(
                "update",
                handle = handle,
                title = "Export complete",
                message = "Open Momoding",
            ),
            toolCallId = "update-call",
        )
        val updatePlan = (
            executor.prepareMutation(TASK_ID, update) as NotificationMutationPreparation.Ready
            ).plan
        val updated = executor.executeMutation(TASK_ID, update, updatePlan) { dispatches += 1 }
        assertFalse(updated.isError)
        assertEquals(handle, updated.contentPayload["data"]?.jsonObject
            ?.get("notificationHandle")?.jsonPrimitive?.content)
        assertEquals(2, gateway.posts)

        val cancel = request(
            arguments("cancel", handle = handle),
            toolCallId = "cancel-call",
        )
        val cancelPlan = (
            executor.prepareMutation(TASK_ID, cancel) as NotificationMutationPreparation.Ready
            ).plan
        val cancelled = executor.executeMutation(TASK_ID, cancel, cancelPlan) {
            dispatches += 1
        }
        assertFalse(cancelled.isError)
        assertEquals("cancelled", cancelled.contentPayload["data"]?.jsonObject
            ?.get("state")?.jsonPrimitive?.content)
        assertTrue(gateway.active.isEmpty())
        assertEquals(3, dispatches)
    }

    @Test
    fun `permission denial has one actionable error and settings require foreground`() = runTest {
        val gateway = FakeNotificationGateway(
            capability = NotificationCapabilitySnapshot(
                declared = true,
                runtimePermissionGranted = false,
                appNotificationsEnabled = false,
                channelState = NotificationChannelState.NOT_CREATED,
            ),
        )
        val background = PhoneLocalNotificationToolExecutor(
            gateway = gateway,
            foregroundGate = NotificationForegroundGate { false },
        )
        val preparation = background.prepareMutation(
            TASK_ID,
            request(arguments("post", title = "Title", message = "Body")),
        ) as NotificationMutationPreparation.Failed
        assertEquals(
            "CAPABILITY_NOT_READY",
            preparation.result.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(
            "Call device_capability_request with capability=notifications.",
            preparation.result.contentPayload["error"]?.jsonObject
                ?.get("resolution")?.jsonPrimitive?.content,
        )

        val open = background.executeImmediate(
            TASK_ID,
            request(arguments("open_settings")),
        )
        assertEquals(
            "APP_NOT_FOREGROUND",
            open.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertFalse(gateway.settingsOpened)
    }

    @Test
    fun `update and cancel reject an active notification changed after preparation`() = runTest {
        val gateway = FakeNotificationGateway()
        val executor = executor(gateway)
        val post = request(
            arguments("post", title = "Original", message = "Before approval"),
            "precondition-post",
        )
        val postPlan = (
            executor.prepareMutation(TASK_ID, post) as NotificationMutationPreparation.Ready
            ).plan
        val posted = executor.executeMutation(TASK_ID, post, postPlan) {}
        val handle = posted.contentPayload["data"]?.jsonObject
            ?.get("notificationHandle")?.jsonPrimitive?.content.orEmpty()
        val identity = gateway.active.keys.single()

        val update = request(
            arguments(
                "update",
                title = "Approved title",
                message = "Approved body",
                handle = handle,
            ),
            "precondition-update",
        )
        val updatePlan = (
            executor.prepareMutation(TASK_ID, update) as NotificationMutationPreparation.Ready
            ).plan
        gateway.active[identity] = gateway.active.getValue(identity).copy(
            message = "Changed while approval was open",
            postedAtMillis = 90L,
        )
        var dispatches = 0
        val updateResult = executor.executeMutation(TASK_ID, update, updatePlan) {
            dispatches += 1
        }
        assertEquals(
            "CONFLICT",
            updateResult.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(0, dispatches)
        assertEquals(1, gateway.posts)

        val cancel = request(
            arguments("cancel", handle = handle),
            "precondition-cancel",
        )
        val cancelPlan = (
            executor.prepareMutation(TASK_ID, cancel) as NotificationMutationPreparation.Ready
            ).plan
        gateway.active[identity] = gateway.active.getValue(identity).copy(
            title = "Changed again",
            postedAtMillis = 91L,
        )
        val cancelResult = executor.executeMutation(TASK_ID, cancel, cancelPlan) {
            dispatches += 1
        }
        assertEquals(
            "CONFLICT",
            cancelResult.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(0, dispatches)
        assertTrue(gateway.active.containsKey(identity))
    }

    @Test
    fun `Stop before dispatch clears the task guard and a later mutation can run`() = runTest {
        val gateway = FakeNotificationGateway()
        val executor = executor(gateway)
        val post = request(
            arguments("post", title = "Seed", message = "Seed body"),
            "stop-before-seed",
        )
        val postPlan = (
            executor.prepareMutation(TASK_ID, post) as NotificationMutationPreparation.Ready
            ).plan
        val posted = executor.executeMutation(TASK_ID, post, postPlan) {}
        val handle = posted.contentPayload["data"]?.jsonObject
            ?.get("notificationHandle")?.jsonPrimitive?.content.orEmpty()
        val update = request(
            arguments("update", title = "New", message = "New body", handle = handle),
            "stop-before-update",
        )
        val updatePlan = (
            executor.prepareMutation(TASK_ID, update) as NotificationMutationPreparation.Ready
            ).plan
        gateway.blockNextList = true
        val stopped = backgroundScope.async {
            executor.executeMutation(TASK_ID, update, updatePlan) {}
        }
        gateway.listEntered.await()

        executor.stopTask(TASK_ID, "test stop before dispatch")

        assertTrue(runCatching { stopped.await() }.exceptionOrNull() is CancellationException)
        val retry = request(
            arguments("post", title = "Retry", message = "Still allowed"),
            "stop-before-retry",
        )
        val retryPlan = (
            executor.prepareMutation(TASK_ID, retry) as NotificationMutationPreparation.Ready
            ).plan
        val retried = executor.executeMutation(TASK_ID, retry, retryPlan) {}
        assertFalse(retried.isError)
    }

    @Test
    fun `Stop after provider dispatch returns outcome unknown without a late repeat`() = runTest {
        val gateway = FakeNotificationGateway()
        val executor = executor(gateway)
        val post = request(
            arguments("post", title = "Export", message = "Ready"),
            "stop-after-post",
        )
        val plan = (
            executor.prepareMutation(TASK_ID, post) as NotificationMutationPreparation.Ready
            ).plan
        gateway.blockNextPost = true
        val stopped = backgroundScope.async {
            executor.executeMutation(TASK_ID, post, plan) {}
        }
        gateway.postEntered.await()

        executor.stopTask(TASK_ID, "test stop after dispatch")

        val result = stopped.await()
        assertEquals(
            "OUTCOME_UNKNOWN",
            result.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(1, gateway.posts)
        assertEquals(1, gateway.active.size)
    }

    private fun executor(gateway: FakeNotificationGateway) =
        PhoneLocalNotificationToolExecutor(
            gateway = gateway,
            now = { Instant.parse("2026-07-29T06:00:00Z") },
            identityFactory = { prefix ->
                NotificationIdentity(prefix + "c".repeat(32), 41_820)
            },
        )

    private class FakeNotificationGateway(
        var capability: NotificationCapabilitySnapshot = READY,
    ) : NotificationGateway {
        val active = linkedMapOf<NotificationIdentity, ActiveAgentNotification>()
        var posts = 0
        var settingsOpened = false
        var blockNextList = false
        var blockNextPost = false
        val listEntered = CompletableDeferred<Unit>()
        val postEntered = CompletableDeferred<Unit>()

        override suspend fun capability() = capability

        override suspend fun listActive(namespacePrefix: String): List<ActiveAgentNotification> {
            if (blockNextList) {
                blockNextList = false
                listEntered.complete(Unit)
                awaitCancellation()
            }
            return active.values
                .filter { it.identity.tag.startsWith(namespacePrefix) }
                .sortedByDescending(ActiveAgentNotification::postedAtMillis)
        }

        override suspend fun post(
            identity: NotificationIdentity,
            title: String,
            message: String,
        ) {
            posts += 1
            active[identity] = ActiveAgentNotification(
                identity,
                title,
                message,
                posts.toLong(),
            )
            if (blockNextPost) {
                blockNextPost = false
                postEntered.complete(Unit)
                awaitCancellation()
            }
        }

        override suspend fun cancel(identity: NotificationIdentity) {
            active.remove(identity)
        }

        override suspend fun openAppNotificationSettings() {
            settingsOpened = true
        }
    }

    private fun request(
        arguments: JsonObject,
        toolCallId: String = "pi-notification",
    ) = PiNativeToolRequest(
        id = "native-$toolCallId",
        kind = "android_notification_tool",
        toolCallId = toolCallId,
        toolName = PhoneLocalNotificationToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun arguments(
        action: String,
        title: String? = null,
        message: String? = null,
        handle: String? = null,
        limit: Int? = null,
    ) = buildJsonObject {
        put("action", action)
        title?.let { put("title", it) }
        message?.let { put("message", it) }
        handle?.let { put("notificationHandle", it) }
        limit?.let { put("limit", it) }
    }

    private fun namespace(taskId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(taskId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "momoding.agent.v1.${digest.take(24)}."
    }

    private companion object {
        const val TASK_ID = "55555555-5555-4555-8555-555555555555"
        const val OTHER_TASK_ID = "66666666-6666-4666-8666-666666666666"
        val READY = NotificationCapabilitySnapshot(
            declared = true,
            runtimePermissionGranted = true,
            appNotificationsEnabled = true,
            channelState = NotificationChannelState.ENABLED,
        )
    }
}
