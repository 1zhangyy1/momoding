package app.momoding.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** momoding: the routine list quotes the first meaningful line of the last result. */
class RoutineTextTest {
    @Test fun `heading and bold are stripped`() {
        assertEquals("今天要注意的事（10月17日 周六）", RoutineText.firstLine("## 今天要注意的事（10月17日 周六）\n\n1. 日程…"))
        assertEquals("日程：今天没有安排", RoutineText.firstLine("- **日程**：今天没有安排"))
    }
    @Test fun `long lines are cut`() {
        assertEquals(41, RoutineText.firstLine("x".repeat(80)).length)
    }
}

class RoutineDaysTest {
    @org.junit.Test fun weekdaysMondayFirstInChinese() {
        org.junit.Assert.assertEquals("周五", app.momoding.chat.RoutineText.days(setOf(java.util.Calendar.FRIDAY), java.util.Locale.CHINA))
        org.junit.Assert.assertEquals("周一、周日", app.momoding.chat.RoutineText.days(setOf(java.util.Calendar.SUNDAY, java.util.Calendar.MONDAY), java.util.Locale.CHINA))
    }
}
