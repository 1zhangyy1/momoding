package app.momoding.core.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSelectionStoreTest {
    @Test
    fun `missing selection adopts safe defaults without touching credential vault`() {
        var payload: String? = null
        val store = store(read = { payload }, write = { payload = it })

        val selection = store.reconcile(profile())

        assertEquals(PROFILE_ID, selection.accountId)
        assertEquals(MODEL_ID, selection.chatModelId)
        assertTrue(selection.webSearchEnabled)
        assertFalse(selection.imageGenerationEnabled)
        assertNull(selection.imageModelId)
        assertFalse(requireNotNull(payload).contains(API_KEY_MARKER))
    }

    @Test
    fun `same account preserves feature choices while chat model follows vault profile`() {
        var payload: String? = null
        val store = store(read = { payload }, write = { payload = it })
        store.store(
            ProviderSelection(
                accountId = PROFILE_ID,
                providerKind = ProviderKind.OPENROUTER,
                chatModelId = MODEL_ID,
                imageModelId = "openai/gpt-image-2",
                webSearchEnabled = false,
                imageGenerationEnabled = true,
            ),
        )

        val reconciled = store.reconcile(profile(modelId = "openai/gpt-5.6"))

        assertEquals("openai/gpt-5.6", reconciled.chatModelId)
        assertEquals("openai/gpt-image-2", reconciled.imageModelId)
        assertFalse(reconciled.webSearchEnabled)
        assertTrue(reconciled.imageGenerationEnabled)
    }

    @Test
    fun `different account discards prior feature choices`() {
        var payload: String? = null
        val store = store(read = { payload }, write = { payload = it })
        store.store(
            ProviderSelection(
                accountId = PROFILE_ID,
                providerKind = ProviderKind.OPENROUTER,
                chatModelId = MODEL_ID,
                imageModelId = "openai/gpt-image-2",
                webSearchEnabled = false,
                imageGenerationEnabled = true,
            ),
        )

        val reconciled = store.reconcile(
            profile(id = "22222222-2222-4222-8222-222222222222"),
        )

        assertTrue(reconciled.webSearchEnabled)
        assertFalse(reconciled.imageGenerationEnabled)
        assertNull(reconciled.imageModelId)
    }

    @Test
    fun `corrupt or future payload fails to defaults instead of breaking saved provider`() {
        var payload: String? = """{"version":2,"secret":"$API_KEY_MARKER"}"""
        val store = store(read = { payload }, write = { payload = it })

        val selection = store.reconcile(profile())

        assertEquals(ProviderSelection.defaults(profile()), selection)
        assertFalse(requireNotNull(payload).contains(API_KEY_MARKER))
    }

    @Test
    fun `image generation cannot be enabled without an image model`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderSelectionPolicy.validate(
                ProviderSelection.defaults(profile()).copy(imageGenerationEnabled = true),
            )
        }
    }

    private fun store(
        read: () -> String?,
        write: (String) -> Unit,
    ): ProviderSelectionStore = ProviderSelectionStore(
        readPayload = read,
        writePayload = { payload ->
            write(payload)
        },
        deletePayload = {},
    )

    private fun profile(
        id: String = PROFILE_ID,
        modelId: String = MODEL_ID,
    ): ProviderProfile = ProviderProfile(
        id = id,
        kind = ProviderKind.OPENROUTER,
        baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
        modelId = modelId,
        displayName = "OpenRouter",
    )

    private companion object {
        const val PROFILE_ID = "11111111-1111-4111-8111-111111111111"
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val API_KEY_MARKER = "must-never-enter-provider-selection"
    }
}
