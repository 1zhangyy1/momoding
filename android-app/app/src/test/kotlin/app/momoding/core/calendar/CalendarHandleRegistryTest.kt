package app.momoding.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarHandleRegistryTest {
    @Test
    fun `handles are opaque task bound and invalidated explicitly`() {
        val registry = CalendarHandleRegistry()
        val target = CalendarEventTarget(42, 1_000, 2_000)
        val calendarHandle = registry.bindCalendar("task-a", 7)
        val eventHandle = registry.bindEvent("task-a", target)
        val fingerprint = registry.queryFingerprint(1_000, 2_000, 7, "Review")
        val cursor = registry.bindCursor("task-a", fingerprint, 10)

        assertTrue(calendarHandle.matches(Regex("^calendar-[0-9a-f]{24}$")))
        assertTrue(eventHandle.matches(Regex("^event-[0-9a-f]{24}$")))
        assertTrue(cursor.matches(Regex("^calendar-page-[0-9a-f]{24}$")))
        assertEquals(7L, registry.calendar("task-a", calendarHandle))
        assertEquals(target, registry.event("task-a", eventHandle))
        assertEquals(10, registry.cursor("task-a", cursor, fingerprint))
        assertNull(registry.calendar("task-b", calendarHandle))
        assertNull(registry.event("task-b", eventHandle))
        assertNull(
            registry.cursor(
                "task-a",
                cursor,
                registry.queryFingerprint(1_000, 2_001, 7, "Review"),
            ),
        )

        registry.clearTask("task-a")

        assertNull(registry.calendar("task-a", calendarHandle))
        assertNull(registry.event("task-a", eventHandle))
    }
}
