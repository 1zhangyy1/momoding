package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Inspect the real request: adjacent user words merge; tool replies and media keep their order. */
class ParallelToolResultImageOrderTest {
    private fun user(text: String, structured: Boolean = false) = LLMMessage(LLMMessage.Role.USER, text,
        contentParts = if (structured) listOf(AgentContentPart.Text(text)) else emptyList())
    private fun body(messages: List<LLMMessage>): JSONArray = OpenAIProvider("k",
        LLMModel("fixture", "fixture", "test", inputModalities = listOf("text", "image")),
        basePath = "https://example.invalid/v1").buildRequestBody(messages, null, 1024, false, null, emptyList()).getJSONArray("messages")
    private fun roles(body: JSONArray) = (0 until body.length()).map { body.getJSONObject(it).getString("role") }

    @Test fun consecutiveWordsBecomeOneUserTurnWithoutCrossingTheAssistant() {
        for (structured in listOf(false, true)) {
            val body = body(listOf(user("first", structured), user("second", structured), user("third", structured),
                LLMMessage(LLMMessage.Role.ASSISTANT, "answer"), user("next", structured)))
            assertEquals(listOf("user", "assistant", "user"), roles(body))
            assertEquals("first\n\nsecond\n\nthird", body.getJSONObject(0).getString("content"))
            assertEquals("next", body.getJSONObject(2).getString("content"))
        }
    }

    @Test fun mergedWordsKeepTheAudioBetweenItsCaptions() {
        val body = body(listOf(user("before"), LLMMessage(LLMMessage.Role.USER, "audio caption",
            audioParts = listOf(LLMMessage.AudioPart("wav", "YWJj"))), user("after", true)))
        assertEquals(listOf("user"), roles(body))
        val parts = body.getJSONObject(0).getJSONArray("content")
        assertEquals(listOf("text", "input_audio", "text", "text"), (0 until parts.length()).map { parts.getJSONObject(it).getString("type") })
        assertEquals("before", parts.getJSONObject(0).getString("text"))
        assertEquals("YWJj", parts.getJSONObject(1).getJSONObject("input_audio").getString("data"))
        assertEquals("audio caption", parts.getJSONObject(2).getString("text")); assertEquals("after", parts.getJSONObject(3).getString("text"))
    }

    @Test fun parallelToolRepliesStayTogetherBeforeTheirMergedImagesAndFollowUp() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
        val calls = listOf("call_A", "call_B")
        val body = body(listOf(user("read both"), LLMMessage(LLMMessage.Role.ASSISTANT, "",
            contentParts = calls.map { AgentContentPart.ToolUse(it, "read_image", JSONObject()) }),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = calls.map {
                AgentContentPart.ToolResult(it, "read_image", "ok", imageData = png, imageMimeType = "image/png") }), user("follow up")))
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), roles(body))
        assertEquals(calls, listOf(2, 3).map { body.getJSONObject(it).getString("tool_call_id") })
        val parts = body.getJSONObject(4).getJSONArray("content")
        assertEquals(listOf("text", "image_url", "text", "image_url", "text"), (0 until parts.length()).map { parts.getJSONObject(it).getString("type") })
        assertTrue(parts.getJSONObject(0).getString("text").contains("call_A")); assertTrue(parts.getJSONObject(2).getString("text").contains("call_B"))
        assertEquals("follow up", parts.getJSONObject(4).getString("text"))
    }
}
