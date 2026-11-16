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

    @Test fun `words with the block still ask for silence, but are drawn`() {
        val r = "Battery is at 100%, nothing to report.\n```momoding-quiet\n```"
        assertTrue(Quiet.asked(r))
        assertFalse(Quiet.chose(r))
        assertFalse(NotifyDecision.decide(r, failed = false).notify)
    }

    @Test fun `a quiet routine is not notified, a failed one is`() {
        assertFalse(NotifyDecision.decide("```momoding-quiet\n```", failed = false).notify)
        assertTrue(NotifyDecision.decide("```momoding-quiet\n```", failed = true).notify)
    }
}
