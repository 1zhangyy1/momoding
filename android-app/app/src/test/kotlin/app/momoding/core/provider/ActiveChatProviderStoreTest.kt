package app.momoding.core.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveChatProviderStoreTest {
    @Test
    fun `missing or corrupt selection falls back without rewriting Provider credentials`() {
        var payload: String? = null
        val store = ActiveChatProviderStore(
            readPayload = { payload },
            writePayload = { payload = it },
        )

        assertEquals(
            ActiveChatProviderSelection(ChatProviderKind.OPENROUTER, "openai/gpt-5"),
            store.load("openai/gpt-5"),
        )
        payload = "{not-json"
        assertEquals(
            ActiveChatProviderSelection(ChatProviderKind.OPENROUTER, "openai/gpt-5"),
            store.load("openai/gpt-5"),
        )
    }

    @Test
    fun `Codex selection round trips independently from the OAuth vault`() {
        var payload: String? = null
        val store = ActiveChatProviderStore(
            readPayload = { payload },
            writePayload = { payload = it },
        )
        val selection = ActiveChatProviderSelection(ChatProviderKind.CODEX, "gpt-5.4")

        store.store(selection)

        assertEquals(selection, store.load("openai/gpt-5"))
        requireNotNull(payload)
        assertEquals(false, payload!!.contains("token", ignoreCase = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Codex selection rejects OpenRouter shaped model IDs`() {
        ActiveChatProviderPolicy.validate(
            ActiveChatProviderSelection(ChatProviderKind.CODEX, "openai/gpt-5"),
        )
    }
}
