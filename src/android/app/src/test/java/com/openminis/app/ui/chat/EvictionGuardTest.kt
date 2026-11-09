package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vm-evict-busy] / [T-android-vm-evict-orphan]
 *
 * The bounded ChatViewModel cache (478470224, 552d90538) decided "busy" from
 * `SessionActivityTracker.activeSessions` alone. That flag is raised inside the
 * stream job AFTER `acquireSlot()`, so a send waiting for a concurrency slot
 * read as idle and could be evicted — its `viewModelScope` cancelled, the
 * message gone with no reply and no error. A parent whose background sub
 * agents were still running read as idle too; evicting it unregistered the
 * queued-delegation starter and orphaned the child's finished callback.
 *
 * Separately, `HeadlessChatRunner` cached a ViewModelProvider per session and
 * only dropped it on session delete, so after an eviction it rebuilt a VM in
 * a cleared, untracked store — a second instance beside the UI's.
 *
 * The decision is pinned directly; the wiring as source facts (the store and
 * the runner are Android-runtime objects this JVM test cannot instantiate),
 * following ChatViewModelStoreEvictionTest.
 */
class EvictionGuardTest {

    // ---- the decision ------------------------------------------------------

    @Test
    fun `a send still waiting for a concurrency slot is busy`() {
        // Tracker idle (setActive not yet called), but the stream job is alive.
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = true, streamJobActive = true, hasAgentWork = false, hasQueuedPrompts = false))
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = true, hasAgentWork = false, hasQueuedPrompts = false))
    }

    @Test
    fun `a parent with running or queued sub agents is busy`() {
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = true, hasQueuedPrompts = false))
    }

    @Test
    fun `a session holding queued prompts is busy`() {
        assertTrue(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = false, hasQueuedPrompts = true))
    }

    @Test
    fun `a genuinely idle session is evictable`() {
        assertFalse(EvictionGuard.hasWorkInFlight(isStreaming = false, streamJobActive = false, hasAgentWork = false, hasQueuedPrompts = false))
    }

}
