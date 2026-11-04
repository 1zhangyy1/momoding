package app.momoding.task

import org.junit.Assert.*
import org.junit.Test

class RoutineFireTest {
    @Test fun `manual and scheduled fires retain their immutable origin`() {
        for (scheduled in listOf(true, false)) {
            val fire = RoutineFire("routine-1", 123456L, scheduled)
            assertEquals(fire, RoutineFire.parse(fire.json()))
        }
    }

    @Test fun `an incomplete origin cannot cause a scheduled completion`() {
        assertNull(RoutineFire.parse("{}"))
        assertNull(RoutineFire.parse("not JSON"))
    }
}
