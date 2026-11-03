package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tools-granular-switches] The switch → tool mapping and the schema gate.
 *
 * The Context-bound halves (isEnabled / setEnabled / migrateLegacyIfNeeded)
 * need SharedPreferences, and this module has no Robolectric or mocking
 * library, so they are not covered here. What IS covered is the part a
 * refactor is most likely to get wrong silently: which tool each switch
 * governs, and whether turning one off actually removes the tool from the
 * schema the model sees.
 */
class AgentToolSwitchTest {

    @Test
    fun `each optional tool is governed by the expected switch`() {
        assertEquals(AgentToolSwitch.BROWSER, AgentToolSwitch.governing("browser_use"))
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("delegate_task"))
        // agent_status must ride with delegate_task: inspecting agents you can
        // no longer create is a dead tool in the schema.
        assertEquals(AgentToolSwitch.AGENTS, AgentToolSwitch.governing("agent_status"))
    }

    @Test
    fun `core tools are ungoverned and therefore always available`() {
        // A null here means "always on". If a future edit accidentally routed
        // one of these through a switch, the agent could lose shell or file
        // access from a settings toggle that never claimed to do that.
        for (core in listOf("shell_execute", "file_read", "file_write", "file_edit", "memory_get")) {
            assertNull("$core must not be gated by a tool switch", AgentToolSwitch.governing(core))
        }
    }

    @Test
    fun `browser defaults on and keys match the iOS contract`() {
        // Byte-identical keys are what let a future settings sync carry the
        // user's choice across platforms; a typo here would silently split them.
        assertEquals("agent.tools.browser.enabled", AgentToolSwitch.BROWSER.key)
        assertEquals("agent.tools.agents.enabled", AgentToolSwitch.AGENTS.key)
        assertTrue("browser_use is mature and ships on", AgentToolSwitch.BROWSER.defaultValue)
    }

    @Test
    fun `disabling the browser removes it from the schema`() {
        fun names(browser: Boolean) = AgentTools.makeAgentTools(browserEnabled = browser).map { it.name }.toSet()
        assertTrue("browser_use" in names(browser = true))
        val noBrowser = names(browser = false)
        assertFalse("browser_use must be absent, not merely refused", "browser_use" in noBrowser)
        assertTrue("shell_execute" in noBrowser && "file_read" in noBrowser)
    }

    @Test
    fun `no schema has a delegation tool`() {
        // momoding (调研/M39): work is handed on with `momoding-task new --to helper`, a command
        for (helper in listOf(false, true)) {
            val names = AgentTools.makeAgentTools(isHelper = helper).map { it.name }
            for (gone in listOf("subagent_task", "delegate_task", "agent_status")) assertFalse(gone in names)
        }
    }
}
