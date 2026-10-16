package app.momoding.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyDecisionTest {
    private fun fence(json: String) = "\n\n```momoding-notify\n$json\n```"

    @Test fun quietWhenModelSaysNothingNew() {
        val d = NotifyDecision.decide("Inbox checked, nothing new." + fence("""{"notify": false, "text": "Nothing new"}"""), failed = false)
        assertFalse(d.notify)
        assertEquals("Nothing new", d.body)
    }

    @Test fun usesTextAsBody() {
        val d = NotifyDecision.decide("Long reply…" + fence("""{"notify": true, "text": "Flight CA1234 delayed 2h"}"""), failed = false)
        assertTrue(d.notify)
        assertEquals("Flight CA1234 delayed 2h", d.body)
    }

    @Test fun missingFenceNotifiesWithReplyStart() {
        val d = NotifyDecision.decide("Gold is up 3% today.", failed = false)
        assertTrue(d.notify)
        assertEquals("Gold is up 3% today.", d.body)
    }

    @Test fun brokenJsonNotifiesWithVisibleText() {
        val d = NotifyDecision.decide("Done." + fence("{notify: nope"), failed = false)
        assertTrue(d.notify)
        assertEquals("Done.", d.body)
    }

    @Test fun failureAlwaysNotifies() {
        val d = NotifyDecision.decide("Error: timeout" + fence("""{"notify": false}"""), failed = true)
        assertTrue(d.notify)
        assertEquals("Error: timeout", d.body)
    }

    @Test fun blankTextFallsBackToReply() {
        val d = NotifyDecision.decide("Meeting moved to 4pm." + fence("""{"notify": true, "text": ""}"""), failed = false)
        assertEquals("Meeting moved to 4pm.", d.body)
    }

    @Test fun emptyReply() {
        assertEquals("(no response)", NotifyDecision.decide(null, failed = false).body)
    }
}
