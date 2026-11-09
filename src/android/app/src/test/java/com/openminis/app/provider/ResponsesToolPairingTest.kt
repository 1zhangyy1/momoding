package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.provider.openai.ResponsesToolPairing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-responses-orphan-tool-output] + [T-android-responses-tool-id-normalize]
 * Ports of iOS bc142f750 / ffbed35ca.
 *
 * Field report (iOS): every send failed with
 *   No tool call found for function call output with call_id call_… .
 * and kept failing on every retry and fallback model until the chat was
 * cleared, because the Responses request is rebuilt from the same history each
 * time and nothing checked its tool pairing.
 *
 * The wire tests drive the real OpenAIProvider.buildResponsesAPIBody.
 */
class ResponsesToolPairingTest {

    private val model = LLMModel(id = "gpt-5", displayName = "GPT-5", provider = "test")

    private fun provider() = OpenAIProvider(apiKey = "k", model = model,
        basePath = "https://example.invalid/v1", useResponsesAPI = true)

    private fun use(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun res(id: String, name: String = "shell_execute", content: String = "ok $id") =
        AgentContentPart.ToolResult(id = id, name = name, content = content)

    private fun assistant(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = parts.toList())

    private fun results(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun user(text: String) = LLMMessage(LLMMessage.Role.USER, text)

    /** One token per input item: fc:<call>, out:<call>, or the item's role. */
    private fun wire(messages: List<LLMMessage>): List<String> {
        val input = provider().buildResponsesAPIBody(messages = messages, systemPrompt = null,
            maxTokens = 1024, stream = false).getJSONArray("input")
        return tokens(input)
    }

    private fun tokens(input: JSONArray): List<String> = (0 until input.length()).map { i ->
        val item = input.getJSONObject(i)
        when (item.optString("type")) {
            "function_call" -> "fc:" + item.getString("call_id")
            "function_call_output" -> "out:" + item.getString("call_id")
            else -> item.optString("role")
        }
    }

    private fun outputs(messages: List<LLMMessage>): List<JSONObject> {
        val input = provider().buildResponsesAPIBody(messages = messages, systemPrompt = null,
            maxTokens = 1024, stream = false).getJSONArray("input")
        return (0 until input.length()).map { input.getJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }
    }

    // ── on the wire ───────────────────────────────────────────────────────

    // ── the sanitizer on its own ──────────────────────────────────────────

    private fun fc(id: String) = JSONObject().put("type", "function_call").put("call_id", id).put("name", "t")
    private fun out(id: String, text: String = "ok") =
        JSONObject().put("type", "function_call_output").put("call_id", id).put("output", text)
    private fun msg(role: String) = JSONObject().put("role", role).put("content", "x")
    private fun arr(vararg items: JSONObject) = JSONArray().apply { items.forEach { put(it) } }

    @Test
    fun `nothing to repair returns the same array`() {
        val input = arr(msg("user"), fc("a"), out("a"), msg("user"))
        val r = ResponsesToolPairing.sanitize(input)
        assertSame(input, r.items)
        assertFalse(r.changed)
    }

    @Test
    fun `reasoning before the trailing calls keeps them exempt`() {
        val reasoning = JSONObject().put("type", "reasoning")
        val r = ResponsesToolPairing.sanitize(arr(msg("user"), reasoning, fc("a")))
        assertFalse(r.changed)
    }

    @Test
    fun `an orphan output is dropped and reported`() {
        val r = ResponsesToolPairing.sanitize(arr(msg("user"), out("gone"), msg("user")))
        assertEquals(listOf("user", "user"), tokens(r.items))
        assertEquals(listOf("gone"), r.droppedOrphanOutputs)
    }

    // ── the history layer uses the same identity ─────────────────────────

    @Test
    fun `pairing key is the call id half, identity for ids without a bar`() {
        assertEquals("call_a", ToolPairing.key("call_a|fc_a"))
        assertEquals("call_a", ToolPairing.key("call_a"))
        assertEquals("toolu_01", ToolPairing.key("toolu_01"))
    }

}
