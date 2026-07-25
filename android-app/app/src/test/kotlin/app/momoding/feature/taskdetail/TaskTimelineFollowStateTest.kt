package app.momoding.feature.taskdetail

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTimelineFollowStateTest {
    @Test
    fun `following stays attached across streaming deltas`() {
        val state = TaskTimelineFollowState().withNewContent()
        assertEquals(TaskTimelineFollowMode.FOLLOWING, state.mode)
        assertEquals(0, state.unseenCount)
    }

    @Test
    fun `user scroll detaches counts unseen updates and near bottom reattaches`() {
        val detached = TaskTimelineFollowState()
            .withViewport(nearBottom = false, userScrolling = true)
            .withNewContent()
            .withNewContent()
        assertEquals(TaskTimelineFollowMode.DETACHED, detached.mode)
        assertEquals(2, detached.unseenCount)

        val following = detached.withViewport(nearBottom = true, userScrolling = false)
        assertEquals(TaskTimelineFollowState(), following)
    }
}
