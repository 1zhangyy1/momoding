package com.openminis.app.service

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the actual tracker: a background command outlives its model turn. */
class BackgroundCommandKeepAliveTest {
    @After fun cleanup() {
        SessionActivityTracker.finishBackgroundWork("command-one")
        SessionActivityTracker.finishBackgroundWork("command-two")
    }

    @Test fun `the last background command owns the service without a model or chat presence`() {
        assertFalse(SessionActivityTracker.shouldRunServiceNow())
        SessionActivityTracker.keepBackgroundWork("command-one", "chat-one") {}
        SessionActivityTracker.keepBackgroundWork("command-two", "chat-two") {}
        assertTrue(SessionActivityTracker.shouldRunServiceNow())
        SessionActivityTracker.finishBackgroundWork("command-one")
        assertTrue(SessionActivityTracker.shouldRunServiceNow())
        SessionActivityTracker.finishBackgroundWork("command-two")
        assertFalse(SessionActivityTracker.shouldRunServiceNow())
    }

    @Test fun `notification cancellation reaches background commands too`() {
        var stopped = false
        SessionActivityTracker.keepBackgroundWork("command-one", "chat-one") {
            stopped = true
            SessionActivityTracker.finishBackgroundWork("command-one")
        }
        SessionActivityTracker.cancelAllActiveStreams()
        assertTrue(stopped)
        assertFalse(SessionActivityTracker.shouldRunServiceNow())
    }
}
