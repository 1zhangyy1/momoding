package app.momoding.core.notification

import android.app.ActivityManager
import android.content.Context
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun interface NotificationForegroundGate {
    fun isForeground(): Boolean
}

interface PhoneLocalNotificationToolHandler {
    fun handles(toolName: String): Boolean
    fun action(request: PiNativeToolRequest): NotificationToolAction?
    fun isMutation(request: PiNativeToolRequest): Boolean
    fun mutationRequestDigest(request: PiNativeToolRequest): String
    suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): NotificationMutationPreparation
    suspend fun executeImmediate(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult
    suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: NotificationMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String)
}

class PhoneLocalNotificationToolExecutor(
    private val gateway: NotificationGateway? = null,
    private val handles: NotificationHandleRegistry = NotificationHandleRegistry(),
    private val foregroundGate: NotificationForegroundGate = NotificationForegroundGate { true },
    private val now: () -> Instant = Instant::now,
    private val identityFactory: (String) -> NotificationIdentity = { namespace ->
        NotificationIdentity(
            tag = namespace + UUID.randomUUID().toString().replace("-", ""),
            id = NOTIFICATION_ID,
        )
    },
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : PhoneLocalNotificationToolHandler {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun action(request: PiNativeToolRequest): NotificationToolAction? =
        runCatching { NotificationToolRequestParser.parse(request.arguments) }
            .getOrNull()
            ?.action

    override fun isMutation(request: PiNativeToolRequest): Boolean =
        action(request)?.isMutation == true

    override fun mutationRequestDigest(request: PiNativeToolRequest): String =
        sha256(canonicalJson(request.arguments))

    override suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): NotificationMutationPreparation {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return NotificationMutationPreparation.Failed(invalidArguments(null, false))
        }
        val parsed = try {
            NotificationToolRequestParser.parse(request.arguments)
        } catch (_: NotificationToolArgumentsException) {
            return NotificationMutationPreparation.Failed(invalidArguments(null, true))
        }
        if (!parsed.action.isMutation) {
            return NotificationMutationPreparation.Failed(
                invalidArguments(parsed.action.wireValue, false),
            )
        }
        val localGateway = gateway ?: return NotificationMutationPreparation.Failed(
            unavailable(parsed.action.wireValue),
        )
        val capability = runCatching { localGateway.capability() }.getOrElse {
            return NotificationMutationPreparation.Failed(unavailable(parsed.action.wireValue))
        }
        if (
            parsed.action in setOf(NotificationToolAction.POST, NotificationToolAction.UPDATE) &&
            !capability.readyToPost
        ) {
            return NotificationMutationPreparation.Failed(capabilityNotReady(parsed.action))
        }
        val namespace = namespace(taskId)
        val existing = when (parsed) {
            is NotificationToolRequest.Update ->
                resolveActive(taskId, parsed.notificationHandle, namespace, localGateway)
            is NotificationToolRequest.Cancel ->
                resolveActive(taskId, parsed.notificationHandle, namespace, localGateway)
            else -> null
        }
        if (
            parsed is NotificationToolRequest.Update ||
            parsed is NotificationToolRequest.Cancel
        ) {
            existing ?: return NotificationMutationPreparation.Failed(
                staleHandle(parsed.action.wireValue),
            )
        }
        val identity = existing?.identity ?: identityFactory(namespace).also {
            require(it.tag.startsWith(namespace)) {
                "Notification identity escaped the task namespace"
            }
        }
        val title = when (parsed) {
            is NotificationToolRequest.Post -> parsed.title
            is NotificationToolRequest.Update -> parsed.title
            else -> null
        }
        val message = when (parsed) {
            is NotificationToolRequest.Post -> parsed.message
            is NotificationToolRequest.Update -> parsed.message
            else -> null
        }
        val handle = when (parsed) {
            is NotificationToolRequest.Update -> parsed.notificationHandle
            is NotificationToolRequest.Cancel -> parsed.notificationHandle
            else -> null
        }
        val existingStateDigest = existing?.let(::activeStateDigest)
        val requestDigest = mutationRequestDigest(request)
        val planDigest = sha256(
            listOf(
                taskId,
                request.toolCallId,
                parsed.action.wireValue,
                identity.tag,
                identity.id.toString(),
                existingStateDigest.orEmpty(),
                requestDigest,
            ).joinToString("\u001f"),
        )
        return NotificationMutationPreparation.Ready(
            NotificationMutationPlan(
                taskId = taskId,
                piToolCallId = request.toolCallId,
                action = parsed.action,
                identity = identity,
                notificationHandle = handle,
                existingStateDigest = existingStateDigest,
                title = title,
                message = message,
                requestDigest = requestDigest,
                planDigest = planDigest,
                summary = when (parsed.action) {
                    NotificationToolAction.POST -> "Post a Momoding notification?"
                    NotificationToolAction.UPDATE -> "Update a Momoding notification?"
                    NotificationToolAction.CANCEL -> "Cancel a Momoding notification?"
                    else -> error("Read crossed the mutation boundary")
                },
                details = when (parsed.action) {
                    NotificationToolAction.POST ->
                        "Posts one bounded notification and verifies it in Android active notifications."
                    NotificationToolAction.UPDATE ->
                        "Replaces one Momoding-owned notification and verifies the new content."
                    NotificationToolAction.CANCEL ->
                        "Removes one Momoding-owned notification and verifies it is no longer active."
                    else -> error("Read crossed the mutation boundary")
                },
            ),
        )
    }

    override suspend fun executeImmediate(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) return invalidArguments(null, false)
        val parsed = try {
            NotificationToolRequestParser.parse(request.arguments)
        } catch (_: NotificationToolArgumentsException) {
            return invalidArguments(null, true)
        }
        if (parsed.action.isMutation) return invalidArguments(parsed.action.wireValue, false)
        val localGateway = gateway ?: return unavailable(parsed.action.wireValue)
        return try {
            withTimeout(timeoutMillis) {
                when (parsed) {
                    NotificationToolRequest.Status -> status(localGateway)
                    is NotificationToolRequest.ListActive ->
                        listActive(taskId, parsed.limit, localGateway)
                    NotificationToolRequest.OpenSettings -> {
                        if (!foregroundGate.isForeground()) {
                            appNotForeground(parsed.action.wireValue)
                        } else {
                            localGateway.openAppNotificationSettings()
                            succeeded(
                                parsed.action,
                                buildJsonObject { put("opened", true) },
                                "observed",
                            )
                        }
                    }
                    else -> error("Mutation crossed the immediate boundary")
                }
            }
        } catch (_: TimeoutCancellationException) {
            timedOut(parsed.action.wireValue)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable(parsed.action.wireValue)
        }
    }

    override suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: NotificationMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult {
        if (
            taskId != plan.taskId ||
            request.toolCallId != plan.piToolCallId ||
            mutationRequestDigest(request) != plan.requestDigest
        ) {
            return failed(
                plan.action.wireValue,
                "CONFLICT",
                "The notification change no longer matches its approved plan.",
                false,
            )
        }
        val localGateway = gateway ?: return unavailable(plan.action.wireValue)
        return supervisorScope {
            val providerCallIssued = AtomicBoolean(false)
            val operation = async(start = CoroutineStart.LAZY) {
                currentCoroutineContext().ensureActive()
                withTimeout(timeoutMillis) {
                    if (
                        plan.action in setOf(
                            NotificationToolAction.UPDATE,
                            NotificationToolAction.CANCEL,
                        )
                    ) {
                        val observed = localGateway.listActive(namespace(taskId))
                            .firstOrNull { it.identity == plan.identity }
                        if (
                            observed == null ||
                            activeStateDigest(observed) != plan.existingStateDigest
                        ) {
                            return@withTimeout conflict(plan)
                        }
                    }
                    withContext(NonCancellable) {
                        onProviderDispatch()
                        providerCallIssued.set(true)
                    }
                    when (plan.action) {
                        NotificationToolAction.POST,
                        NotificationToolAction.UPDATE,
                        -> {
                            localGateway.post(
                                plan.identity,
                                requireNotNull(plan.title),
                                requireNotNull(plan.message),
                            )
                            val observed = awaitActive(localGateway, taskId, plan.identity) {
                                it.title == plan.title && it.message == plan.message
                            }
                            if (
                                observed?.title == plan.title &&
                                observed.message == plan.message
                            ) {
                                val handle = plan.notificationHandle
                                    ?: handles.bind(taskId, plan.identity)
                                mutationSucceeded(plan, handle, "active")
                            } else {
                                verificationFailed(plan)
                            }
                        }
                        NotificationToolAction.CANCEL -> {
                            localGateway.cancel(plan.identity)
                            val stillActive = !awaitAbsent(localGateway, taskId, plan.identity)
                            if (stillActive) {
                                verificationFailed(plan)
                            } else {
                                handles.forget(taskId, plan.identity)
                                mutationSucceeded(
                                    plan,
                                    requireNotNull(plan.notificationHandle),
                                    "cancelled",
                                )
                            }
                        }
                        else -> error("Read crossed the mutation execution boundary")
                    }
                }
            }
            if (activeJobs.putIfAbsent(taskId, operation) != null) {
                operation.cancel()
                return@supervisorScope failed(
                    plan.action.wireValue,
                    "NOTIFICATION_REQUEST_IN_PROGRESS",
                    "Another notification change is already running for this task.",
                    true,
                )
            }
            try {
                operation.start()
                operation.await()
            } catch (_: TimeoutCancellationException) {
                if (providerCallIssued.get()) {
                    outcomeUnknown(plan)
                } else {
                    timedOut(plan.action.wireValue)
                }
            } catch (cancelled: CancellationException) {
                if (providerCallIssued.get()) outcomeUnknown(plan) else throw cancelled
            } catch (_: Exception) {
                if (providerCallIssued.get()) {
                    outcomeUnknown(plan)
                } else {
                    unavailable(plan.action.wireValue)
                }
            } finally {
                activeJobs.remove(taskId, operation)
            }
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        activeJobs[taskId]?.cancel(CancellationException(reason))
    }

    private suspend fun status(gateway: NotificationGateway): PiNativeAndroidToolResult {
        val snapshot = gateway.capability()
        return succeeded(
            NotificationToolAction.STATUS,
            buildJsonObject {
                put("readyToPost", snapshot.readyToPost)
                put("permissionDeclared", snapshot.declared)
                put("runtimePermissionGranted", snapshot.runtimePermissionGranted)
                put("appNotificationsEnabled", snapshot.appNotificationsEnabled)
                put("channelState", snapshot.channelState.wireValue)
                if (!snapshot.readyToPost) {
                    put(
                        "resolution",
                        "Call device_capability_request with capability=notifications.",
                    )
                }
            },
            "observed",
        )
    }

    private suspend fun listActive(
        taskId: String,
        limit: Int,
        gateway: NotificationGateway,
    ): PiNativeAndroidToolResult {
        val active = gateway.listActive(namespace(taskId)).take(limit)
        return succeeded(
            NotificationToolAction.LIST_ACTIVE,
            buildJsonObject {
                put("returnedCount", active.size)
                put(
                    "items",
                    buildJsonArray {
                        active.forEach { item ->
                            add(
                                buildJsonObject {
                                    put(
                                        "notificationHandle",
                                        handles.bind(taskId, item.identity),
                                    )
                                    put("title", item.title)
                                    put("message", item.message)
                                    put(
                                        "postedAt",
                                        Instant.ofEpochMilli(item.postedAtMillis)
                                            .truncatedTo(ChronoUnit.MILLIS)
                                            .toString(),
                                    )
                                },
                            )
                        }
                    },
                )
            },
            "observed",
        )
    }

    private suspend fun resolveActive(
        taskId: String,
        handle: String,
        namespace: String,
        gateway: NotificationGateway,
    ): ActiveAgentNotification? {
        val identity = handles.resolve(taskId, handle) ?: return null
        return gateway.listActive(namespace).firstOrNull { it.identity == identity }
    }

    private suspend fun awaitActive(
        gateway: NotificationGateway,
        taskId: String,
        identity: NotificationIdentity,
        predicate: (ActiveAgentNotification) -> Boolean,
    ): ActiveAgentNotification? {
        repeat(POST_VERIFY_ATTEMPTS) { attempt ->
            val observed = gateway.listActive(namespace(taskId))
                .firstOrNull { it.identity == identity }
            if (observed != null && predicate(observed)) return observed
            if (attempt < POST_VERIFY_ATTEMPTS - 1) delay(POST_VERIFY_DELAY_MILLIS)
        }
        return null
    }

    private suspend fun awaitAbsent(
        gateway: NotificationGateway,
        taskId: String,
        identity: NotificationIdentity,
    ): Boolean {
        repeat(POST_VERIFY_ATTEMPTS) { attempt ->
            if (gateway.listActive(namespace(taskId)).none { it.identity == identity }) {
                return true
            }
            if (attempt < POST_VERIFY_ATTEMPTS - 1) delay(POST_VERIFY_DELAY_MILLIS)
        }
        return false
    }

    private fun mutationSucceeded(
        plan: NotificationMutationPlan,
        handle: String,
        state: String,
    ) = PiNativeAndroidToolResult(
        buildJsonObject {
            put("ok", true)
            put("action", plan.action.wireValue)
            put(
                "data",
                buildJsonObject {
                    put("notificationHandle", handle)
                    put("state", state)
                },
            )
            put(
                "verification",
                buildJsonObject {
                    put("status", "verified")
                    put("observedAt", observedAt())
                    put("planDigest", plan.planDigest)
                },
            )
        },
    )

    private fun succeeded(
        action: NotificationToolAction,
        data: JsonObject,
        verificationStatus: String,
    ) = PiNativeAndroidToolResult(
        buildJsonObject {
            put("ok", true)
            put("action", action.wireValue)
            put("data", data)
            put(
                "verification",
                buildJsonObject {
                    put("status", verificationStatus)
                    put("observedAt", observedAt())
                },
            )
        },
    )

    private fun capabilityNotReady(action: NotificationToolAction) = failed(
        action.wireValue,
        "CAPABILITY_NOT_READY",
        "Android notification access is not enabled.",
        true,
        "Call device_capability_request with capability=notifications.",
    )

    private fun staleHandle(action: String) = failed(
        action,
        "STALE_HANDLE",
        "The notification handle is unavailable. Call list_active and try again.",
        true,
    )

    private fun conflict(plan: NotificationMutationPlan) = failed(
        plan.action.wireValue,
        "CONFLICT",
        "The active notification changed after it was prepared. List active notifications and try again.",
        false,
    )

    private fun invalidArguments(action: String?, retryable: Boolean) = failed(
        action,
        "INVALID_ARGUMENTS",
        "Notification arguments are invalid.",
        retryable,
    )

    private fun appNotForeground(action: String) = failed(
        action,
        "APP_NOT_FOREGROUND",
        "Open Momoding before opening Android notification settings.",
        true,
    )

    private fun timedOut(action: String) = failed(
        action,
        "DEVICE_TOOL_TIMEOUT",
        "Android notification access timed out.",
        true,
    )

    private fun verificationFailed(plan: NotificationMutationPlan) = failed(
        plan.action.wireValue,
        "VERIFICATION_FAILED",
        "Android could not verify the requested notification change.",
        false,
    )

    private fun outcomeUnknown(plan: NotificationMutationPlan) = failed(
        plan.action.wireValue,
        "OUTCOME_UNKNOWN",
        "Android started the notification change but its final state could not be verified.",
        false,
    )

    private fun unavailable(action: String) = failed(
        action,
        "PROVIDER_UNAVAILABLE",
        "Android notifications are temporarily unavailable.",
        true,
    )

    private fun failed(
        action: String?,
        code: String,
        message: String,
        retryable: Boolean,
        resolution: String? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", false)
            if (action == null) put("action", JsonNull) else put("action", action)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("retryable", retryable)
                    resolution?.let { put("resolution", it) }
                },
            )
        },
        isError = true,
    )

    private fun namespace(taskId: String): String =
        "momoding.agent.v1.${sha256(taskId).take(24)}."

    private fun activeStateDigest(notification: ActiveAgentNotification): String = sha256(
        listOf(
            notification.identity.tag,
            notification.identity.id.toString(),
            notification.title,
            notification.message,
            notification.postedAtMillis.toString(),
        ).joinToString("\u001f"),
    )

    private fun observedAt(): String =
        now().truncatedTo(ChronoUnit.MILLIS).toString()

    private fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(
            prefix = "{",
            postfix = "}",
        ) { (key, item) -> "${JsonPrimitive(key)}:${canonicalJson(item)}" }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") {
            canonicalJson(it)
        }
        else -> value.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val TOOL_NAME = NOTIFICATION_TOOL_NAME
        private const val NOTIFICATION_ID = 41_820
        private const val REQUEST_TIMEOUT_MILLIS = 5_000L
        private const val POST_VERIFY_ATTEMPTS = 10
        private const val POST_VERIFY_DELAY_MILLIS = 50L

        fun create(context: Context): PhoneLocalNotificationToolExecutor {
            val application = context.applicationContext
            val activityManager = application.getSystemService(ActivityManager::class.java)
            return PhoneLocalNotificationToolExecutor(
                gateway = AndroidNotificationGateway.create(application),
                foregroundGate = NotificationForegroundGate {
                    activityManager.runningAppProcesses
                        ?.firstOrNull { it.pid == android.os.Process.myPid() }
                        ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                },
            )
        }
    }
}
