package app.momoding.core.location

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.LocationCapabilityAccess
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalLocationToolExecutorTest {
    @Test
    fun `parser is strict bounded and does not echo invalid purpose`() {
        val parsed = LocationToolRequestParser.parse(arguments("approximate", "Find nearby help"))
        assertEquals(LocationToolAction.GET_CURRENT, parsed.action)
        assertEquals(LocationCapabilityAccess.APPROXIMATE, parsed.precision)

        listOf(
            arguments("city", "Find me"),
            arguments("approximate", "x".repeat(161)),
            buildJsonObject {
                arguments("approximate", "PRIVATE_LOCATION_PURPOSE").forEach { (key, value) ->
                    put(key, value)
                }
                put("rawProvider", "gps")
            },
        ).forEach { invalid ->
            assertTrue(
                runCatching { LocationToolRequestParser.parse(invalid) }.exceptionOrNull()
                    is LocationToolArgumentsException,
            )
        }
    }

    @Test
    fun `approximate result minimizes coordinates and is marked live only`() = runTest {
        val capturedAt = Instant.parse("2026-07-29T04:00:00Z")
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            acquisition = LocationAcquisition.Available(
                LocationReading(
                    latitude = 31.2304167,
                    longitude = 121.4737012,
                    accuracyMeters = 8.4f,
                    capturedAt = capturedAt,
                    providerCategory = LocationProviderCategory.SATELLITE,
                ),
            ),
            now = { capturedAt.plusSeconds(2) },
            scope = backgroundScope,
        )

        val result = executor.execute(
            TASK_ID,
            request(arguments("approximate", "Estimate my current area")),
        )

        assertFalse(result.isError)
        val data = result.contentPayload.getValue("data").jsonObject
        assertEquals(31.23, data.getValue("latitude").jsonPrimitive.double, 0.0)
        assertEquals(121.47, data.getValue("longitude").jsonPrimitive.double, 0.0)
        assertEquals(1_000.0, data.getValue("accuracyMeters").jsonPrimitive.double, 0.0)
        assertEquals("approximate", data.getValue("precision").jsonPrimitive.content)
        assertEquals("satellite", data.getValue("providerCategory").jsonPrimitive.content)
        assertEquals("true", result.details?.get("liveOnly")?.jsonPrimitive?.content)
        assertEquals("location", result.details?.get("dataClass")?.jsonPrimitive?.content)
        assertEquals(
            sha256(result.contentPayload.toString()),
            result.details?.get("contentSha256")?.jsonPrimitive?.content,
        )
        assertEquals(
            "observed",
            result.contentPayload["verification"]?.jsonObject
                ?.get("status")?.jsonPrimitive?.content,
        )
        assertFalse(result.contentPayload.toString().contains("Estimate my current area"))
    }

    @Test
    fun `precise request requires fine grant and stale or background reads fail closed`() =
        runTest {
            val now = Instant.parse("2026-07-29T04:00:00Z")
            val partial = executor(
                availability = CapabilityAvailability.PARTIAL,
                acquisition = LocationAcquisition.NoFix,
                now = { now },
                scope = backgroundScope,
            ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
            assertTrue(partial.isError)
            assertEquals(
                "CAPABILITY_NOT_READY",
                partial.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(
                "request_capability",
                partial.contentPayload["error"]?.jsonObject
                    ?.get("resolution")?.jsonObject
                    ?.get("kind")?.jsonPrimitive?.content,
            )

            val stale = executor(
                availability = CapabilityAvailability.READY,
                acquisition = LocationAcquisition.Available(
                    LocationReading(
                        31.2304167,
                        121.4737012,
                        3f,
                        now.minusSeconds(301),
                        LocationProviderCategory.NETWORK,
                    ),
                ),
                now = { now },
                scope = backgroundScope,
            ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
            assertEquals(
                "LOCATION_STALE",
                stale.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )

            val background = executor(
                availability = CapabilityAvailability.READY,
                acquisition = LocationAcquisition.NoFix,
                now = { now },
                foreground = false,
                scope = backgroundScope,
            ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
            assertEquals(
                "APP_NOT_FOREGROUND",
                background.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `Stop cancels the one underlying location request`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gateway = object : LocationGateway {
            override suspend fun getCurrentLocation(
                precision: LocationCapabilityAccess,
            ): LocationAcquisition {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val executor = PhoneLocalLocationToolExecutor(
            gateway = gateway,
            capabilityRegistry = registry(CapabilityAvailability.READY, backgroundScope),
            foregroundGate = LocationForegroundGate { true },
        )
        val pending = async {
            executor.execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
        }

        started.await()
        executor.stopTask(TASK_ID, "user_stop")
        val outcome = runCatching { pending.await() }
        assertTrue(outcome.isFailure)
        cancelled.await()
    }

    @Test
    fun `provider states map to stable bounded errors`() = runTest {
        listOf(
            LocationAcquisition.ServicesDisabled to "LOCATION_SERVICES_DISABLED",
            LocationAcquisition.ProviderUnavailable to "PROVIDER_UNAVAILABLE",
            LocationAcquisition.NoFix to "LOCATION_UNAVAILABLE",
        ).forEach { (acquisition, code) ->
            val result = executor(
                availability = CapabilityAvailability.PARTIAL,
                acquisition = acquisition,
                now = Instant::now,
                scope = backgroundScope,
            ).execute(TASK_ID, request(arguments("approximate", "Find my area")))
            assertTrue(result.isError)
            assertEquals(
                code,
                result.contentPayload["error"]?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(result.contentPayload, result.details)
            assertFalse(result.details.toString().contains("Find my area"))
        }
    }

    @Test
    fun `permission race and provider exception remain distinct typed failures`() = runTest {
        val registry = registry(CapabilityAvailability.READY, backgroundScope)
        val security = PhoneLocalLocationToolExecutor(
            gateway = LocationGateway { throw SecurityException("revoked") },
            capabilityRegistry = registry,
        ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
        assertEquals(
            "CAPABILITY_NOT_READY",
            security.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )

        val provider = PhoneLocalLocationToolExecutor(
            gateway = LocationGateway { throw IllegalStateException("private provider error") },
            capabilityRegistry = registry,
        ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))
        assertEquals(
            "PROVIDER_UNAVAILABLE",
            provider.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertFalse(provider.contentPayload.toString().contains("private provider error"))
    }

    @Test
    fun `bounded wait uses the shared device timeout code`() = runTest {
        val result = PhoneLocalLocationToolExecutor(
            gateway = LocationGateway { awaitCancellation() },
            capabilityRegistry = registry(CapabilityAvailability.READY, backgroundScope),
            timeoutMillis = 1_000L,
        ).execute(TASK_ID, request(arguments("precise", "Use exact coordinates")))

        assertEquals(
            "DEVICE_TOOL_TIMEOUT",
            result.contentPayload["error"]?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
    }

    private fun executor(
        availability: CapabilityAvailability,
        acquisition: LocationAcquisition,
        now: () -> Instant,
        scope: kotlinx.coroutines.CoroutineScope,
        foreground: Boolean = true,
    ) = PhoneLocalLocationToolExecutor(
        gateway = LocationGateway { acquisition },
        capabilityRegistry = registry(availability, scope),
        foregroundGate = LocationForegroundGate { foreground },
        now = now,
    )

    private fun registry(
        locationAvailability: CapabilityAvailability,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = AndroidCapabilityRegistry(
        probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id = id,
                    availability = if (id == AndroidCapabilityId.LOCATION) {
                        locationAvailability
                    } else {
                        CapabilityAvailability.NOT_GRANTED
                    },
                    source = "test",
                    checkedAtMillis = checkedAt,
                    safeMessage = "test",
                )
            }
        },
        scope = scope,
    )

    private fun request(arguments: JsonObject) = PiNativeToolRequest(
        id = "native-location",
        kind = "android_location_tool",
        toolCallId = "pi-location",
        toolName = PhoneLocalLocationToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun arguments(
        precision: String,
        purpose: String,
    ) = buildJsonObject {
        put("action", "get_current")
        put("precision", precision)
        put("purpose", purpose)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
    }
}
