package app.momoding.core.runtime.local

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalTaskPlanStateTest {
    @Test
    fun `persisted Pi custom entries restore exact Plan tools and latest plan`() {
        val canonical = """{"explanation":"Plan safely","steps":[{"id":"one","text":"Inspect","status":"pending"}]}"""
        val digest = sha256(canonical)
        val entries = parseEntries(
            """
            [
              {"type":"custom","id":"1","parentId":null,"timestamp":"2026-07-21T00:00:00Z","customType":"pi_mobile_plan_mode","data":{"enabled":true,"prePlanActiveToolNames":["run_command","run_tests","task_plan_update"]}},
              {"type":"active_tools_change","id":"2","parentId":"1","timestamp":"2026-07-21T00:00:01Z","activeToolNames":["device_files_list","task_plan_update"]},
              {"type":"custom","id":"3","parentId":"2","timestamp":"2026-07-21T00:00:02Z","customType":"pi_mobile_task_plan","data":{"explanation":"Plan safely","steps":[{"id":"one","text":"Inspect","status":"pending"}],"planDigest":"$digest"}}
            ]
            """.trimIndent(),
        )

        val state = taskPlanStateFromEntries(entries)

        assertTrue(state.enabled)
        assertEquals(listOf("device_files_list", "task_plan_update"), state.activeToolNames)
        assertEquals(
            listOf("run_command", "run_tests", "task_plan_update"),
            state.prePlanActiveToolNames,
        )
        assertEquals(digest, state.latestPlan?.planDigest)
        assertTrue(state.canImplement)
    }

    @Test
    fun `persisted Plan exit and corrupt digest both fail closed`() {
        val entries = parseEntries(
            """
            [
              {"type":"custom","id":"1","parentId":null,"timestamp":"2026-07-21T00:00:00Z","customType":"pi_mobile_plan_mode","data":{"enabled":true,"prePlanActiveToolNames":["run_command"]}},
              {"type":"custom","id":"2","parentId":"1","timestamp":"2026-07-21T00:00:01Z","customType":"pi_mobile_task_plan","data":{"explanation":"Plan safely","steps":[{"id":"one","text":"Inspect","status":"pending"}],"planDigest":"${"0".repeat(64)}"}},
              {"type":"active_tools_change","id":"3","parentId":"2","timestamp":"2026-07-21T00:00:02Z","activeToolNames":["run_command"]},
              {"type":"custom","id":"4","parentId":"3","timestamp":"2026-07-21T00:00:03Z","customType":"pi_mobile_plan_mode","data":{"enabled":false,"reason":"exit","restoredActiveToolNames":["run_command"]}}
            ]
            """.trimIndent(),
        )

        val state = taskPlanStateFromEntries(entries)

        assertFalse(state.enabled)
        assertNull(state.prePlanActiveToolNames)
        assertNull(state.latestPlan)
        assertFalse(state.canImplement)
    }

    private fun parseEntries(raw: String): JsonArray =
        Json.parseToJsonElement(raw) as JsonArray

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
