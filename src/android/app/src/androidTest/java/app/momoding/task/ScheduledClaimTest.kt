package app.momoding.task

import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.scheduled.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/** Real preference claims and counters; missing owned targets keep background delivery inert. */
class ScheduledClaimTest {
    private val manager get() = ScheduledTaskManager(InstrumentationRegistry.getInstrumentation().targetContext)
    private val ids = mutableListOf<String>()
    private val t0 = 1_800_000_000_000L
    private fun store(kind: ScheduledTriggerKind, anchor: Long = t0, count: Int? = null, repeat: ScheduledRepeatMode = ScheduledRepeatMode.ONCE): ScheduledTask {
        val task = ScheduledTask(label = "owned claim fixture", timeOfDayHour = 9, timeOfDayMinute = 0,
            repeatMode = repeat, prompt = "original {{notification}}", targetMode = ScheduledTargetMode.AppendToSession("missing owned target"),
            triggerKind = kind, delaySec = 60, intervalSec = 60, maxFires = count, anchorMs = anchor, createdAt = anchor)
        ids += task.id; manager.store().upsert(task); return task
    }
    private val posted = ScheduledNotificationTriggers.Posted("owned.fixture", "Fixture", "original title", "original body")
    @After fun cleanup() { ids.forEach { manager.delete(it) } }
    @Test fun afterClaimKeepsItsInputWithTheDisabledCounter() {
        val task = store(ScheduledTriggerKind.AFTER)
        val fire = manager.claimAlarmFire(task.id, t0 + 60_000)!!
        val saved = manager.get(task.id)!!
        assertFalse(saved.enabled); assertEquals(1, saved.triggeredCount); assertEquals(fire, saved.pendingDispatch)
        assertEquals(task.prompt, fire.task.prompt); assertEquals(t0 + 60_000, fire.at)
        assertNull(manager.claimAlarmFire(task.id, t0 + 60_001))
        assertTrue(manager.acknowledgeDispatch(task.id, fire.id)); assertFalse(manager.get(task.id)!!.enabled)
        assertNull(manager.claimAlarmFire(task.id, t0 + 120_000))
    }
    @Test fun intervalCannotSpendAnotherCountBeforeItsOriginalInputIsAccepted() {
        val task = store(ScheduledTriggerKind.INTERVAL, count = 2)
        val first = manager.claimAlarmFire(task.id, t0 + 60_000)!!
        assertNull(manager.claimAlarmFire(task.id, t0 + 120_000)); assertEquals(1, manager.get(task.id)!!.triggeredCount)
        assertEquals(first, manager.get(task.id)!!.pendingDispatch)
        manager.acknowledgeDispatch(task.id, first.id)
        val second = manager.claimAlarmFire(task.id, t0 + 120_000)!!
        assertNotEquals(first.id, second.id); assertEquals(2, manager.get(task.id)!!.triggeredCount)
        assertFalse(manager.get(task.id)!!.enabled); assertEquals(second, manager.get(task.id)!!.pendingDispatch)
        manager.acknowledgeDispatch(task.id, second.id); assertNull(manager.claimAlarmFire(task.id, t0 + 180_000))
    }
    @Test fun notificationClaimKeepsTheMatchedTextAcrossCooldownAndSettingsReload() {
        val task = store(ScheduledTriggerKind.ON_NOTIFICATION, anchor = t0 - 60_000)
        val first = manager.claimNotificationFire(task.id, t0, posted)!!
        assertTrue(first.task.prompt.contains("original title: original body"))
        assertEquals(first, manager.get(task.id)!!.pendingDispatch)
        assertNull(manager.claimNotificationFire(task.id, t0 + 120_000, posted.copy(text = "replacement")))
        assertEquals(first, manager.get(task.id)!!.pendingDispatch); assertEquals(1, manager.get(task.id)!!.triggeredCount)
        manager.acknowledgeDispatch(task.id, first.id)
        assertNull(manager.claimNotificationFire(task.id, t0 + 59_999, posted))
        val next = manager.claimNotificationFire(task.id, t0 + 60_000, posted.copy(text = "new body"))!!
        assertNotEquals(first.id, next.id); assertTrue(next.task.prompt.contains("new body")); assertEquals(2, manager.get(task.id)!!.triggeredCount)
    }
    @Test fun calendarClaimsAnEarlySlotOnceThenKeepsTheNextLocalDay() {
        val slot = Calendar.getInstance().apply { timeInMillis = t0; set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val task = store(ScheduledTriggerKind.CALENDAR, anchor = slot - 3_600_000, repeat = ScheduledRepeatMode.DAILY)
        val first = manager.claimAlarmFire(task.id, slot - 1_000)!!
        assertEquals(slot, manager.get(task.id)!!.anchorMs)
        manager.acknowledgeDispatch(task.id, first.id)
        assertNull(manager.claimAlarmFire(task.id, slot + 1_000)); assertEquals(1, manager.get(task.id)!!.triggeredCount)
        val tomorrow = Calendar.getInstance().apply { timeInMillis = slot; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        assertEquals(tomorrow, manager.get(task.id)!!.nextTriggerMs(slot))
        val second = manager.claimAlarmFire(task.id, tomorrow)!!
        assertNotEquals(first.id, second.id); assertEquals(2, manager.get(task.id)!!.triggeredCount)
    }
    @Test fun aCalendarOnceStaysDisabledAfterAcceptance() {
        val slot = Calendar.getInstance().apply { timeInMillis = t0; set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val task = store(ScheduledTriggerKind.CALENDAR, anchor = slot - 3_600_000)
        val first = manager.claimAlarmFire(task.id, slot)!!
        assertFalse(manager.get(task.id)!!.enabled)
        manager.acknowledgeDispatch(task.id, first.id); assertNull(manager.claimAlarmFire(task.id, slot + 86_400_000))
    }
    @Test fun anEarlyRelativeOrAnEventDefinitionIsNotAnAlarmFire() {
        val task = store(ScheduledTriggerKind.INTERVAL, count = 2)
        assertNull(manager.claimAlarmFire(task.id, t0 + 30_000)); assertNull(manager.get(task.id)!!.triggeredCount)
        for (kind in listOf(ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.ON_NOTIFICATION)) {
            val event = store(kind); assertNull(manager.claimAlarmFire(event.id, t0)); assertNull(manager.get(event.id)!!.pendingDispatch)
        }
    }
    @Test fun cancellationAndEditingDoNotReplaceTheAcceptedClaimRecipe() {
        val task = store(ScheduledTriggerKind.INTERVAL, count = 2)
        val fire = manager.claimAlarmFire(task.id, t0 + 60_000)!!
        manager.update(task.copy(prompt = "edited", anchorMs = null, triggeredCount = null))
        assertEquals(fire, manager.get(task.id)!!.pendingDispatch)
        manager.setEnabled(task.id, false); assertNull(manager.get(task.id)!!.pendingDispatch)
        assertFalse(manager.acknowledgeDispatch(task.id, fire.id)); manager.setEnabled(task.id, true)
        val now = manager.get(task.id)!!.anchorMs!!
        val next = manager.claimAlarmFire(task.id, now + 60_000)!!
        assertNotEquals(fire.id, next.id); assertEquals("edited", next.task.prompt); assertEquals(1, manager.get(task.id)!!.triggeredCount)
    }
    @Test fun enablingAgainKeepsTheOldClaimAndStartsANewSchedule() {
        for (kind in listOf(ScheduledTriggerKind.AFTER, ScheduledTriggerKind.INTERVAL, ScheduledTriggerKind.ON_COMPLETION)) {
            val task = store(kind, count = 2)
            val old = if (kind == ScheduledTriggerKind.ON_COMPLETION) manager.claimCompletionFire(task.id, "old")!!
                else manager.claimAlarmFire(task.id, t0 + 60_000)!!
            manager.setEnabled(task.id, true)
            val armed = manager.get(task.id)!!
            assertEquals(old, armed.pendingDispatch); assertTrue(armed.enabled); assertEquals(0, armed.triggeredCount)
            manager.acknowledgeDispatch(task.id, old.id)
            val next = if (kind == ScheduledTriggerKind.ON_COMPLETION) manager.claimCompletionFire(task.id, "new")!!
                else manager.claimAlarmFire(task.id, armed.anchorMs!! + 60_000)!!
            assertNotEquals(old.id, next.id); assertEquals(1, manager.get(task.id)!!.triggeredCount)
        }
    }
}
