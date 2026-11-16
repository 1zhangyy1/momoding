package app.momoding.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietTest {
    @Test fun `only the block is silence`() {
        assertTrue(Quiet.chose("```momoding-quiet\n```"))
        assertTrue(Quiet.chose("  ```momoding-quiet```  "))
        assertFalse(Quiet.chose("Nothing new today.\n```momoding-quiet\n```"))
        assertFalse(Quiet.chose("All done."))
        assertFalse(Quiet.chose(null))
    }

    @Test fun `a quiet routine is not notified, a failed one is`() {
        assertFalse(NotifyDecision.decide("```momoding-quiet\n```", failed = false).notify)
        assertTrue(NotifyDecision.decide("```momoding-quiet\n```", failed = true).notify)
    }
}
