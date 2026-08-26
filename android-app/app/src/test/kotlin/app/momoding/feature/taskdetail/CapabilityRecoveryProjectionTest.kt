package app.momoding.feature.taskdetail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapabilityRecoveryProjectionTest {
    @Test
    fun `exact failure grant and retry consumes one recovery candidate`() {
        val projection = CapabilityRecoveryProjection()
        projection.recordStart("failed", "device_calendar", json(CALENDAR_ARGS))
        assertNull(
            projection.observeTerminal(
                "failed",
                "device_calendar",
                ToolActivityState.FAILURE,
                json(calendarFailure()),
            ),
        )
        grant(projection, "calendar", "read")
        projection.recordStart("retry", "device_calendar", json(CALENDAR_ARGS))

        assertEquals(
            "failed",
            projection.observeTerminal(
                "retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
        projection.recordStart("second", "device_calendar", json(CALENDAR_ARGS))
        assertNull(
            projection.observeTerminal(
                "second",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `different action and capability mismatch never consume failure`() {
        val projection = CapabilityRecoveryProjection()
        projection.recordStart("failed", "device_calendar", json(CALENDAR_ARGS))
        projection.observeTerminal(
            "failed",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        grant(projection, "contacts", "read")
        projection.recordStart(
            "different-action",
            "device_calendar",
            json("""{"action":"list_calendars"}"""),
        )

        assertNull(
            projection.observeTerminal(
                "different-action",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_calendars")),
            ),
        )
        projection.recordStart("same-call", "device_calendar", json(CALENDAR_ARGS))
        assertNull(
            projection.observeTerminal(
                "same-call",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `new user turn clears failures and grants`() {
        val projection = CapabilityRecoveryProjection()
        projection.recordStart("failed", "device_calendar", json(CALENDAR_ARGS))
        projection.observeTerminal(
            "failed",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        grant(projection, "calendar", "read")
        projection.resetTurn()
        projection.recordStart("retry", "device_calendar", json(CALENDAR_ARGS))

        assertNull(
            projection.observeTerminal(
                "retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `one capability grant cannot hide multiple candidates`() {
        val projection = CapabilityRecoveryProjection()
        projection.recordStart("first-failure", "device_calendar", json(CALENDAR_ARGS))
        projection.observeTerminal(
            "first-failure",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        val secondArguments = """{"action":"list_events","start":"2026-08-26","end":"2026-08-27"}"""
        projection.recordStart("second-failure", "device_calendar", json(secondArguments))
        projection.observeTerminal(
            "second-failure",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        grant(projection, "calendar", "read")
        projection.recordStart("first-retry", "device_calendar", json(CALENDAR_ARGS))
        assertEquals(
            "first-failure",
            projection.observeTerminal(
                "first-retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
        projection.recordStart("second-retry", "device_calendar", json(secondArguments))
        assertNull(
            projection.observeTerminal(
                "second-retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `a grant that predates a failure cannot recover that failure`() {
        val projection = CapabilityRecoveryProjection()
        grant(projection, "calendar", "read")
        projection.recordStart("failed", "device_calendar", json(CALENDAR_ARGS))
        projection.observeTerminal(
            "failed",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        projection.recordStart("retry", "device_calendar", json(CALENDAR_ARGS))

        assertNull(
            projection.observeTerminal(
                "retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `a terminal with spoofed capability tool identity cannot create a grant`() {
        val projection = CapabilityRecoveryProjection()
        projection.recordStart("failed", "device_calendar", json(CALENDAR_ARGS))
        projection.observeTerminal(
            "failed",
            "device_calendar",
            ToolActivityState.FAILURE,
            json(calendarFailure()),
        )
        projection.recordStart(
            "spoofed-grant",
            "device_calendar",
            json(CALENDAR_ARGS),
        )
        projection.observeTerminal(
            "spoofed-grant",
            "device_capability_request",
            ToolActivityState.SUCCESS,
            json("""{"capability":"calendar","requiredAccess":"read","ready":true}"""),
        )
        projection.recordStart("retry", "device_calendar", json(CALENDAR_ARGS))

        assertNull(
            projection.observeTerminal(
                "retry",
                "device_calendar",
                ToolActivityState.SUCCESS,
                json(calendarSuccess("list_events")),
            ),
        )
    }

    @Test
    fun `malformed structured resolution never falls back to a fixed capability`() {
        val projection = CapabilityRecoveryProjection()
        val arguments = json("""{"action":"list"}""")
        projection.recordStart("failed", "device_notification", arguments)
        projection.observeTerminal(
            "failed",
            "device_notification",
            ToolActivityState.FAILURE,
            json(
                """{"ok":false,"action":"list","error":{"code":"CAPABILITY_NOT_READY","resolution":{"kind":"unexpected"}}}""",
            ),
        )
        grant(projection, "notifications", "read")
        projection.recordStart("retry", "device_notification", arguments)

        assertNull(
            projection.observeTerminal(
                "retry",
                "device_notification",
                ToolActivityState.SUCCESS,
                json("""{"ok":true,"action":"list"}"""),
            ),
        )
    }

    private fun grant(
        projection: CapabilityRecoveryProjection,
        capability: String,
        access: String,
    ) {
        projection.recordStart(
            "grant-$capability",
            "device_capability_request",
            json("""{"capability":"$capability","requiredAccess":"$access","purpose":"Continue"}"""),
        )
        projection.observeTerminal(
            "grant-$capability",
            "device_capability_request",
            ToolActivityState.SUCCESS,
            json("""{"capability":"$capability","requiredAccess":"$access","ready":true}"""),
        )
    }

    private fun calendarFailure() =
        """{"ok":false,"action":"list_events","error":{"code":"CAPABILITY_NOT_READY","retryable":true,"resolution":{"kind":"request_capability","capability":"calendar","requiredAccess":"read"}}}"""

    private fun calendarSuccess(action: String) =
        """{"ok":true,"action":"$action","data":{},"verification":{"status":"observed"}}"""

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    private companion object {
        const val CALENDAR_ARGS =
            """{"action":"list_events","start":"2026-08-25","end":"2026-08-26"}"""
    }
}
