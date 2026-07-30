package app.momoding.core.media

import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface PhoneLocalMediaToolHandler {
    fun handles(toolName: String): Boolean
    fun action(request: PiNativeToolRequest): MediaToolAction?
    fun mutationRequestDigest(request: PiNativeToolRequest): String
    suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): MediaMutationPreparation
    suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: MediaMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String)
}

class PhoneLocalMediaToolExecutor(
    private val scopeProvider: PhotoLibraryScopeProvider,
    private val gateway: MediaGateway?,
    private val handles: MediaHandleRegistry,
    private val consentRequester: MediaSystemConsentRequester,
    private val now: () -> Instant = Instant::now,
    private val queryTimeoutMillis: Long = QUERY_TIMEOUT_MILLIS,
) : PhoneLocalMediaToolHandler {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun action(request: PiNativeToolRequest): MediaToolAction? =
        runCatching { MediaToolRequestParser.parse(request.arguments).action }.getOrNull()

    override fun mutationRequestDigest(request: PiNativeToolRequest): String =
        sha256(canonicalJson(request.arguments))

    override suspend fun prepareMutation(
        taskId: String,
        request: PiNativeToolRequest,
    ): MediaMutationPreparation {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return MediaMutationPreparation.Failed(invalidArguments(null))
        }
        val parsed = try {
            MediaToolRequestParser.parse(request.arguments)
        } catch (_: MediaToolArgumentsException) {
            return MediaMutationPreparation.Failed(invalidArguments(null))
        }
        if (scopeProvider.current() == PhotoLibraryScope.DENIED) {
            return MediaMutationPreparation.Failed(capabilityNotReady(parsed.action))
        }
        val localGateway = gateway ?: return MediaMutationPreparation.Failed(
            unavailable(parsed.action),
        )
        val mediaId = handles.resolve(taskId, parsed.mediaHandle)
            ?: return MediaMutationPreparation.Failed(staleHandle(parsed.action))
        val snapshot = try {
            withTimeout(queryTimeoutMillis) { localGateway.get(mediaId) }
        } catch (_: TimeoutCancellationException) {
            return MediaMutationPreparation.Failed(timedOut(parsed.action))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return MediaMutationPreparation.Failed(capabilityNotReady(parsed.action))
        } catch (_: Exception) {
            return MediaMutationPreparation.Failed(unavailable(parsed.action))
        } ?: return MediaMutationPreparation.Failed(staleHandle(parsed.action))
        val desired = when (parsed) {
            is MediaToolRequest.SetFavorite -> parsed.favorite
            is MediaToolRequest.SetTrashed -> parsed.trashed
            is MediaToolRequest.Delete -> null
        }
        val requestDigest = mutationRequestDigest(request)
        val snapshotDigest = snapshotDigest(snapshot)
        val planDigest = sha256(
            listOf(
                taskId,
                request.toolCallId,
                parsed.action.wireValue,
                snapshotDigest,
                desired?.toString().orEmpty(),
                requestDigest,
            ).joinToString("\u001f"),
        )
        return MediaMutationPreparation.Ready(
            MediaMutationPlan(
                taskId = taskId,
                piToolCallId = request.toolCallId,
                action = parsed.action,
                mediaHandle = parsed.mediaHandle,
                mediaId = mediaId,
                desired = desired,
                snapshotDigest = snapshotDigest,
                requestDigest = requestDigest,
                planDigest = planDigest,
                summary = when (parsed.action) {
                    MediaToolAction.SET_FAVORITE ->
                        if (desired == true) "Favorite this photo?" else "Remove this photo from favorites?"
                    MediaToolAction.SET_TRASHED ->
                        if (desired == true) "Move this photo to Android trash?" else "Restore this photo from Android trash?"
                    MediaToolAction.DELETE -> "Permanently delete this photo?"
                },
                details = when (parsed.action) {
                    MediaToolAction.SET_FAVORITE ->
                        "Android will show its own confirmation and Momoding will verify the favorite state."
                    MediaToolAction.SET_TRASHED ->
                        "Android will show its own confirmation and Momoding will verify the trash state."
                    MediaToolAction.DELETE ->
                        "Android will show its own confirmation. This permanently removes one photo and cannot be undone."
                },
            ),
        )
    }

    override suspend fun executeMutation(
        taskId: String,
        request: PiNativeToolRequest,
        plan: MediaMutationPlan,
        onProviderDispatch: suspend () -> Unit,
    ): PiNativeAndroidToolResult {
        if (
            taskId != plan.taskId ||
            request.toolCallId != plan.piToolCallId ||
            mutationRequestDigest(request) != plan.requestDigest
        ) {
            return conflict(plan)
        }
        val localGateway = gateway ?: return unavailable(plan.action)
        return supervisorScope {
            val providerCallIssued = AtomicBoolean(false)
            val operation = async(start = CoroutineStart.LAZY) {
                currentCoroutineContext().ensureActive()
                val current = withTimeout(queryTimeoutMillis) {
                    localGateway.get(plan.mediaId)
                } ?: return@async conflict(plan)
                if (snapshotDigest(current) != plan.snapshotDigest) return@async conflict(plan)
                if (alreadyDesired(current, plan)) {
                    return@async mutationSucceeded(plan, changed = false)
                }
                val consent = localGateway.consentRequest(
                    plan.action,
                    plan.mediaId,
                    plan.desired,
                )
                withContext(NonCancellable) {
                    onProviderDispatch()
                    providerCallIssued.set(true)
                }
                when (consentRequester.request(taskId, plan.action, consent)) {
                    MediaSystemConsentResult.APPROVED -> verifyMutation(localGateway, plan)
                    MediaSystemConsentResult.DENIED -> consentDeclined(plan)
                    MediaSystemConsentResult.UNAVAILABLE -> consentUnavailable(plan)
                    MediaSystemConsentResult.TIMEOUT -> outcomeUnknown(plan)
                }
            }
            if (activeJobs.putIfAbsent(taskId, operation) != null) {
                operation.cancel()
                return@supervisorScope failed(
                    plan.action,
                    "MEDIA_REQUEST_IN_PROGRESS",
                    "Another Android media change is already running for this task.",
                    true,
                )
            }
            try {
                operation.start()
                operation.await()
            } catch (_: TimeoutCancellationException) {
                if (providerCallIssued.get()) outcomeUnknown(plan) else timedOut(plan.action)
            } catch (cancelled: CancellationException) {
                if (providerCallIssued.get()) outcomeUnknown(plan) else throw cancelled
            } catch (_: SecurityException) {
                if (providerCallIssued.get()) outcomeUnknown(plan) else capabilityNotReady(plan.action)
            } catch (_: Exception) {
                if (providerCallIssued.get()) outcomeUnknown(plan) else unavailable(plan.action)
            } finally {
                activeJobs.remove(taskId, operation)
            }
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        activeJobs[taskId]?.cancel(CancellationException(reason))
    }

    private suspend fun verifyMutation(
        gateway: MediaGateway,
        plan: MediaMutationPlan,
    ): PiNativeAndroidToolResult {
        repeat(POST_VERIFY_ATTEMPTS) { attempt ->
            val observed = gateway.get(plan.mediaId)
            val verified = when (plan.action) {
                MediaToolAction.SET_FAVORITE -> observed?.favorite == plan.desired
                MediaToolAction.SET_TRASHED -> observed?.trashed == plan.desired
                MediaToolAction.DELETE -> observed == null
            }
            if (verified) {
                if (plan.action == MediaToolAction.DELETE) {
                    handles.forget(plan.taskId, plan.mediaHandle)
                }
                return mutationSucceeded(plan, changed = true)
            }
            if (attempt < POST_VERIFY_ATTEMPTS - 1) delay(POST_VERIFY_DELAY_MILLIS)
        }
        return verificationFailed(plan)
    }

    private fun alreadyDesired(snapshot: MediaItemSnapshot, plan: MediaMutationPlan): Boolean =
        when (plan.action) {
            MediaToolAction.SET_FAVORITE -> snapshot.favorite == plan.desired
            MediaToolAction.SET_TRASHED -> snapshot.trashed == plan.desired
            MediaToolAction.DELETE -> false
        }

    private fun mutationSucceeded(
        plan: MediaMutationPlan,
        changed: Boolean,
    ) = PiNativeAndroidToolResult(
        buildJsonObject {
            put("ok", true)
            put("action", plan.action.wireValue)
            put(
                "data",
                buildJsonObject {
                    put("changed", changed)
                    when (plan.action) {
                        MediaToolAction.SET_FAVORITE ->
                            put("favorite", requireNotNull(plan.desired))
                        MediaToolAction.SET_TRASHED ->
                            put("trashed", requireNotNull(plan.desired))
                        MediaToolAction.DELETE -> put("deleted", true)
                    }
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

    private fun invalidArguments(action: MediaToolAction?) = failed(
        action,
        "INVALID_ARGUMENTS",
        "Media arguments are invalid.",
        false,
    )

    private fun staleHandle(action: MediaToolAction) = failed(
        action,
        "STALE_HANDLE",
        "The media handle is unavailable. Call device_media_list and try again.",
        true,
    )

    private fun capabilityNotReady(action: MediaToolAction) = failed(
        action,
        "CAPABILITY_NOT_READY",
        "Android photo-library access is not enabled.",
        true,
        "Call device_capability_request with capability=photo_library.",
    )

    private fun conflict(plan: MediaMutationPlan) = failed(
        plan.action,
        "CONFLICT",
        "The photo changed after this operation was prepared. List media and try again.",
        false,
    )

    private fun consentDeclined(plan: MediaMutationPlan) = failed(
        plan.action,
        "MEDIA_SYSTEM_CONSENT_DECLINED",
        "Android system confirmation was declined.",
        false,
    )

    private fun consentUnavailable(plan: MediaMutationPlan) = failed(
        plan.action,
        "MEDIA_SYSTEM_CONSENT_UNAVAILABLE",
        "Android could not open system confirmation for this media change.",
        true,
    )

    private fun timedOut(action: MediaToolAction) = failed(
        action,
        "DEVICE_TOOL_TIMEOUT",
        "Android media access timed out before a change started.",
        true,
    )

    private fun verificationFailed(plan: MediaMutationPlan) = failed(
        plan.action,
        "VERIFICATION_FAILED",
        "Android could not verify the requested media change.",
        false,
    )

    private fun outcomeUnknown(plan: MediaMutationPlan) = failed(
        plan.action,
        "OUTCOME_UNKNOWN",
        "Android opened system confirmation, but the final media state could not be verified.",
        false,
    )

    private fun unavailable(action: MediaToolAction) = failed(
        action,
        "MEDIA_PROVIDER_UNAVAILABLE",
        "Android media storage is temporarily unavailable.",
        true,
    )

    private fun failed(
        action: MediaToolAction?,
        code: String,
        message: String,
        retryable: Boolean,
        resolution: String? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", false)
            action?.let { put("action", it.wireValue) }
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

    private fun snapshotDigest(snapshot: MediaItemSnapshot): String = sha256(
        listOf(
            snapshot.mediaId.toString(),
            snapshot.mimeType,
            snapshot.favorite.toString(),
            snapshot.trashed.toString(),
            snapshot.byteCount?.toString().orEmpty(),
            snapshot.capturedAtMillis?.toString().orEmpty(),
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
        const val TOOL_NAME = MEDIA_TOOL_NAME
        private const val QUERY_TIMEOUT_MILLIS = 5_000L
        private const val POST_VERIFY_ATTEMPTS = 10
        private const val POST_VERIFY_DELAY_MILLIS = 100L
    }
}
