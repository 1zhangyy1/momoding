package app.momoding.task

import app.momoding.hands.HandsOperator
import app.momoding.hands.HandsPrompt
import org.junit.Assert.*
import org.junit.Test

class ScreenSpecTest {
    @Test fun `a queued screen task retains its complete execution payload`() {
        val options = HandsOperator.Options("查看车票\n只查，不提交", "12306", 17, "actor",
            "Momoding", "Chinese", HandsPrompt.Mode.HYBRID, background = false, textSteps = true,
            modelEntryId = "isolated-model")
        val original = ScreenWork.Spec(options, "parent")
        assertEquals(original, ScreenWork.Spec.parse(original.json()))
    }

    @Test fun `a standalone screen task has no invented parent or app`() {
        val options = HandsOperator.Options("look", sessionId = "actor")
        val restored = ScreenWork.Spec.parse(ScreenWork.Spec(options).json())
        assertNull(restored.parent)
        assertNull(restored.options.appHint)
        assertEquals(options, restored.options)
    }

    @Test fun `a restarted run binds its task and instruction cursor from Room`() {
        val options = HandsOperator.Options("look", sessionId = "actor", taskId = "old-runtime", instructionCursor = 42)
        val restored = ScreenWork.Spec.parse(ScreenWork.Spec(options).json())
        assertNull(restored.options.taskId)
        assertEquals(0L, restored.options.instructionCursor)
    }
}
