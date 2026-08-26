package app.momoding.core.location

import android.app.ActivityManager
import android.content.Context
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.LocationCapabilityAccess
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface PhoneLocalLocationToolHandler {
    fun handles(toolName: String): Boolean
    fun isPersonalDataRead(request: PiNativeToolRequest): Boolean
    suspend fun isCapabilityReady(request: PiNativeToolRequest): Boolean
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult
    fun stopTask(taskId: String, reason: String)
}

class PhoneLocalLocationToolExecutor(
    private val gateway: LocationGateway? = null,
    private val capabilityRegistry: AndroidCapabilityRegistry? = null,
    private val foregroundGate: LocationForegroundGate = LocationForegroundGate { true },
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : PhoneLocalLocationToolHandler {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean =
        runCatching { LocationToolRequestParser.parse(request.arguments) }.isSuccess

    override suspend fun isCapabilityReady(request: PiNativeToolRequest): Boolean {
        val parsed = runCatching {
            LocationToolRequestParser.parse(request.arguments)
        }.getOrNull() ?: return false
        val availability = capabilityRegistry?.refreshNow()
            ?.first { it.id == AndroidCapabilityId.LOCATION }
            ?.availability
            ?: return false
        return availability == CapabilityAvailability.READY ||
            (
                parsed.precision == LocationCapabilityAccess.APPROXIMATE &&
                    availability == CapabilityAvailability.PARTIAL
                )
    }

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return failed(null, "INVALID_ARGUMENTS", "Location arguments are invalid.", false)
        }
        val parsed = try {
            LocationToolRequestParser.parse(request.arguments)
        } catch (_: LocationToolArgumentsException) {
            return failed(null, "INVALID_ARGUMENTS", "Location arguments are invalid.", true)
        }
        val localGateway = gateway ?: return failed(
            parsed.action.wireValue,
            "PROVIDER_UNAVAILABLE",
            "Android location is not enabled in this build.",
            false,
        )
        if (!isCapabilityReady(request)) {
            return capabilityNotReady(parsed)
        }
        if (!foregroundGate.isForeground()) {
            return failed(
                parsed.action.wireValue,
                "APP_NOT_FOREGROUND",
                "Open Momoding before requesting the current location.",
                true,
            )
        }
        val job = currentCoroutineContext()[Job]
            ?: return failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android location is temporarily unavailable.",
                true,
            )
        val previous = activeJobs.putIfAbsent(taskId, job)
        if (previous != null) {
            return failed(
                parsed.action.wireValue,
                "LOCATION_REQUEST_IN_PROGRESS",
                "Another location request is already running for this task.",
                true,
            )
        }
        return try {
            when (
                val acquisition = withTimeout(timeoutMillis) {
                    localGateway.getCurrentLocation(parsed.precision)
                }
            ) {
                is LocationAcquisition.Available -> {
                    if (!foregroundGate.isForeground()) {
                        failed(
                            parsed.action.wireValue,
                            "APP_NOT_FOREGROUND",
                            "Momoding left the foreground before location was available.",
                            true,
                        )
                    } else {
                        succeeded(parsed, acquisition.reading)
                    }
                }
                LocationAcquisition.ServicesDisabled -> failed(
                    parsed.action.wireValue,
                    "LOCATION_SERVICES_DISABLED",
                    "Android location services are turned off.",
                    true,
                )
                LocationAcquisition.ProviderUnavailable -> failed(
                    parsed.action.wireValue,
                    "PROVIDER_UNAVAILABLE",
                    "No enabled Android location provider is available.",
                    true,
                )
                LocationAcquisition.NoFix -> failed(
                    parsed.action.wireValue,
                    "LOCATION_UNAVAILABLE",
                    "Android could not obtain a current location.",
                    true,
                )
            }
        } catch (_: TimeoutCancellationException) {
            failed(
                parsed.action.wireValue,
                "DEVICE_TOOL_TIMEOUT",
                "Android did not obtain a location before the timeout.",
                true,
            )
        } catch (_: SecurityException) {
            capabilityNotReady(parsed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(
                parsed.action.wireValue,
                "PROVIDER_UNAVAILABLE",
                "Android location is temporarily unavailable.",
                true,
            )
        } finally {
            activeJobs.remove(taskId, job)
        }
    }

    override fun stopTask(taskId: String, reason: String) {
        activeJobs[taskId]?.cancel(CancellationException(reason))
    }

    private fun succeeded(
        request: LocationToolRequest,
        raw: LocationReading,
    ): PiNativeAndroidToolResult {
        val observedAt = now()
        val ageMillis = Duration.between(raw.capturedAt, observedAt)
            .toMillis()
            .coerceAtLeast(0L)
        if (ageMillis > MAX_LOCATION_AGE_MILLIS) {
            return failed(
                request.action.wireValue,
                "LOCATION_STALE",
                "Android returned a location that is too old for this task.",
                true,
            )
        }
        val decimals = if (
            request.precision == LocationCapabilityAccess.APPROXIMATE
        ) {
            APPROXIMATE_DECIMALS
        } else {
            PRECISE_DECIMALS
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("action", request.action.wireValue)
            put(
                "data",
                buildJsonObject {
                    put("precision", request.precision.wireValue)
                    put("latitude", raw.latitude.roundTo(decimals).asCanonicalJsonNumber())
                    put("longitude", raw.longitude.roundTo(decimals).asCanonicalJsonNumber())
                    put(
                        "accuracyMeters",
                        if (request.precision == LocationCapabilityAccess.APPROXIMATE) {
                            raw.accuracyMeters.coerceAtLeast(APPROXIMATE_ACCURACY_FLOOR_METERS)
                        } else {
                            raw.accuracyMeters
                        }.roundTo(1).asCanonicalJsonNumber(),
                    )
                    put("capturedAt", raw.capturedAt.toString())
                    put("ageMillis", ageMillis)
                    put("providerCategory", raw.providerCategory.wireValue)
                },
            )
            put(
                "verification",
                buildJsonObject {
                    put("status", "observed")
                    put(
                        "observedAt",
                        observedAt.truncatedTo(ChronoUnit.MILLIS).toString(),
                    )
                },
            )
        }
        return PiNativeAndroidToolResult(
            contentPayload = payload,
            details = liveOnlyDetails(payload, request.precision),
        )
    }

    private fun liveOnlyDetails(
        payload: JsonObject,
        precision: LocationCapabilityAccess,
    ) = buildJsonObject {
        put("liveOnly", true)
        put("dataClass", "location")
        put("contentSha256", sha256(payload.toString()))
        put("precision", precision.wireValue)
    }

    private fun capabilityNotReady(request: LocationToolRequest) = failed(
        request.action.wireValue,
        "CAPABILITY_NOT_READY",
        if (request.precision == LocationCapabilityAccess.PRECISE) {
            "Precise Android location access is not enabled."
        } else {
            "Android location access is not enabled."
        },
        true,
        buildJsonObject {
            put("kind", "request_capability")
            put("capability", "location")
            put("requiredAccess", request.precision.wireValue)
        },
    )

    private fun failed(
        action: String?,
        code: String,
        message: String,
        retryable: Boolean,
        resolution: JsonObject? = null,
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

    private fun Double.roundTo(decimals: Int): Double =
        BigDecimal.valueOf(this).setScale(decimals, RoundingMode.HALF_UP).toDouble()

    private fun Float.roundTo(decimals: Int): Double =
        toDouble().roundTo(decimals)

    /** Matches JSON.stringify's finite-number spelling so the cross-runtime digest stays exact. */
    private fun Double.asCanonicalJsonNumber(): JsonPrimitive {
        require(isFinite()) { "Location number must be finite" }
        // Location values have already been rounded to at most five decimal places. Keeping that
        // bounded value in plain decimal avoids JVM exponent spellings such as `1.0E-5`, which
        // JSON.parse + JSON.stringify canonicalizes to `0.00001` in the Pi runtime.
        val canonical = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
        return JsonUnquotedLiteral(if (canonical == "-0") "0" else canonical)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val TOOL_NAME = "device_location"
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val MAX_LOCATION_AGE_MILLIS = 5 * 60_000L
        private const val APPROXIMATE_DECIMALS = 2
        private const val PRECISE_DECIMALS = 5
        private const val APPROXIMATE_ACCURACY_FLOOR_METERS = 1_000f

        fun create(
            context: Context,
            registry: AndroidCapabilityRegistry,
        ): PhoneLocalLocationToolExecutor {
            val application = context.applicationContext
            val activityManager = application.getSystemService(ActivityManager::class.java)
            return PhoneLocalLocationToolExecutor(
                gateway = AndroidLocationGateway.create(application),
                capabilityRegistry = registry,
                foregroundGate = LocationForegroundGate {
                    activityManager.runningAppProcesses
                        ?.firstOrNull { it.pid == android.os.Process.myPid() }
                        ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                },
            )
        }
    }
}
