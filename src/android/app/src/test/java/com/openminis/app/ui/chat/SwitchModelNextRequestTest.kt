package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-switch-model-next-request] + [T-android-ctx-overflow-attribute-dispatch]
 * Ports of iOS 86a045284 (+ the d6e475a35 follow-ups) and 08f3feea2.
 *
 * Picking another model while a task runs used to take effect only after the
 * whole turn: runAgentLoop kept its own provider for every request of the turn,
 * so a task with 20 tool calls ran all of them on the old model. Now the next
 * REQUEST uses the new model, and the context calibration is credited to the
 * model a request actually went to.
 */
class SwitchModelNextRequestTest {

    private val a = Any()
    private val b = Any()

    @Test
    fun `a pending switch is applied once the picker has put a different provider in place`() {
        assertTrue(ChatViewModel.modelSwitchToApply(pending = true, chosen = b, loopProvider = a))
    }

    @Test
    fun `nothing is applied without a pending switch - a group fallback moving the provider is not a user switch`() {
        assertFalse(ChatViewModel.modelSwitchToApply(pending = false, chosen = b, loopProvider = a))
    }

    @Test
    fun `a switch the picker has not applied yet stays pending`() {
        // The flag is set before the picker rebinds; a loop head in between
        // must not consume it.
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = a, loopProvider = a))
        assertFalse(ChatViewModel.modelSwitchToApply(pending = true, chosen = null, loopProvider = a))
    }

}
