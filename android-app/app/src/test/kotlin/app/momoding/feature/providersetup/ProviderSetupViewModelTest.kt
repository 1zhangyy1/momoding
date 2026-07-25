package app.momoding.feature.providersetup

import app.momoding.core.provider.OpenRouterFailurePhase
import app.momoding.core.provider.OpenRouterModelSummary
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSetupViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing Provider validates fields then tests and saves without exposing saved key`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val stored = mutableListOf<ProviderCredential>()
            val tested = mutableListOf<ProviderCredential>()
            val viewModel = ProviderSetupViewModel(
                loadCredential = { null },
                storeCredential = { stored += it },
                deleteCredential = {},
                testCredential = { tested += it },
            )
            advanceUntilIdle()
            assertEquals(ProviderSetupLoadState.MISSING, viewModel.state.value.loadState)

            viewModel.dispatch(ProviderSetupAction.EditModel("bad model"))
            viewModel.dispatch(ProviderSetupAction.TestConnection)
            assertTrue(viewModel.state.value.modelError != null)
            assertTrue(tested.isEmpty())

            viewModel.dispatch(ProviderSetupAction.EditModel(MODEL_ID))
            viewModel.dispatch(ProviderSetupAction.EditApiKey(API_KEY))
            viewModel.dispatch(ProviderSetupAction.TestConnection)
            advanceUntilIdle()
            assertEquals(1, tested.size)
            assertEquals(ProviderHealth.READY, viewModel.state.value.health)
            assertTrue(viewModel.state.value.testedChangesNeedSave)
            assertFalse(viewModel.state.value.canReturnToTask)
            assertTrue(viewModel.state.value.notice!!.startsWith("Connection successful"))
            assertTrue(stored.isEmpty())

            viewModel.dispatch(ProviderSetupAction.Save)
            advanceUntilIdle()
            assertEquals(1, stored.size)
            assertTrue(viewModel.state.value.configured)
            assertEquals(ProviderHealth.READY, viewModel.state.value.health)
            assertTrue(viewModel.state.value.canReturnToTask)
            assertEquals("", viewModel.state.value.apiKeyInput)
            assertTrue(viewModel.state.value.hasSavedApiKey)
            assertFalse(viewModel.state.value.toString().contains(API_KEY))
        }

    @Test
    fun `configured Provider keeps encrypted key when only model changes then deletes explicitly`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val original = credential(MODEL_ID)
            var deleted = false
            val stored = mutableListOf<ProviderCredential>()
            val viewModel = ProviderSetupViewModel(
                loadCredential = { original },
                storeCredential = { stored += it },
                deleteCredential = { deleted = true },
                testCredential = {},
            )
            advanceUntilIdle()
            assertTrue(viewModel.state.value.configured)
            assertEquals(ProviderHealth.SAVED, viewModel.state.value.health)
            assertEquals("", viewModel.state.value.apiKeyInput)

            val changedModel = "openai/gpt-4.1-mini"
            viewModel.dispatch(ProviderSetupAction.EditModel(changedModel))
            viewModel.dispatch(ProviderSetupAction.Save)
            advanceUntilIdle()
            assertEquals(changedModel, stored.single().profile.modelId)
            assertEquals(API_KEY, stored.single().apiKey)
            assertEquals("", viewModel.state.value.apiKeyInput)

            viewModel.dispatch(ProviderSetupAction.RequestDelete)
            assertTrue(viewModel.state.value.deleteConfirmationVisible)
            viewModel.dispatch(ProviderSetupAction.ConfirmDelete)
            advanceUntilIdle()
            assertTrue(deleted)
            assertEquals(ProviderSetupLoadState.MISSING, viewModel.state.value.loadState)
            assertFalse(viewModel.state.value.configured)
        }

    @Test
    fun `Provider test exposes only local safe error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = ProviderSetupViewModel(
            loadCredential = { null },
            storeCredential = {},
            deleteCredential = {},
            testCredential = {
                throw OpenRouterRequestException(
                    statusCode = 401,
                    errorType = "authentication",
                    retryAfterSeconds = null,
                    phase = OpenRouterFailurePhase.BEFORE_STREAM,
                    safeMessage = "OpenRouter API key is invalid",
                )
            },
        )
        advanceUntilIdle()
        viewModel.dispatch(ProviderSetupAction.EditModel(MODEL_ID))
        viewModel.dispatch(ProviderSetupAction.EditApiKey(API_KEY))
        viewModel.dispatch(ProviderSetupAction.TestConnection)
        advanceUntilIdle()

        assertEquals("OpenRouter API key is invalid", viewModel.state.value.notice)
        assertEquals(ProviderHealth.INVALID, viewModel.state.value.health)
        assertEquals(ProviderSetupOperation.IDLE, viewModel.state.value.operation)
    }

    @Test
    fun `saved Provider must pass a current test before task recovery can continue`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = credential(MODEL_ID)
        val viewModel = ProviderSetupViewModel(
            loadCredential = { original },
            storeCredential = {},
            deleteCredential = {},
            testCredential = {},
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.canReturnToTask)
        viewModel.dispatch(ProviderSetupAction.TestConnection)
        advanceUntilIdle()
        assertEquals(ProviderHealth.READY, viewModel.state.value.health)
        assertFalse(viewModel.state.value.testedChangesNeedSave)
        assertTrue(viewModel.state.value.canReturnToTask)

        viewModel.dispatch(ProviderSetupAction.EditModel("openai/gpt-4.1-mini"))
        assertEquals(ProviderHealth.SAVED, viewModel.state.value.health)
        assertFalse(viewModel.state.value.canReturnToTask)
    }

    @Test
    fun `rate limit is distinct from invalid Provider configuration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = ProviderSetupViewModel(
            loadCredential = { credential(MODEL_ID) },
            storeCredential = {},
            deleteCredential = {},
            testCredential = {
                throw OpenRouterRequestException(
                    statusCode = 429,
                    errorType = "rate_limit",
                    retryAfterSeconds = 5,
                    phase = OpenRouterFailurePhase.BEFORE_STREAM,
                    safeMessage = "OpenRouter rate limit reached",
                )
            },
        )
        advanceUntilIdle()
        viewModel.dispatch(ProviderSetupAction.TestConnection)
        advanceUntilIdle()

        assertEquals(ProviderHealth.RATE_LIMITED, viewModel.state.value.health)
        assertFalse(viewModel.state.value.canReturnToTask)
    }

    @Test
    fun `model browser searches catalog while preserving manual model IDs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val catalogKeys = mutableListOf<String?>()
        val viewModel = ProviderSetupViewModel(
            loadCredential = { credential(MODEL_ID) },
            storeCredential = {},
            deleteCredential = {},
            testCredential = {},
            loadModels = { key ->
                catalogKeys += key
                listOf(
                    OpenRouterModelSummary(MODEL_ID, "DeepSeek V4 Pro", 131_072, listOf("text")),
                    OpenRouterModelSummary("openai/gpt-5", "GPT-5", 400_000, listOf("text", "image")),
                )
            },
        )
        advanceUntilIdle()

        viewModel.dispatch(ProviderSetupAction.ToggleModelCatalog)
        advanceUntilIdle()
        assertEquals(listOf(API_KEY), catalogKeys)
        assertEquals(ProviderModelCatalogState.READY, viewModel.state.value.modelCatalogState)

        viewModel.dispatch(ProviderSetupAction.EditModelSearch("gpt"))
        assertEquals(listOf("openai/gpt-5"), viewModel.state.value.visibleModels.map { it.id })
        viewModel.dispatch(ProviderSetupAction.SelectModel("openai/gpt-5"))
        assertEquals("openai/gpt-5", viewModel.state.value.modelId)
        assertFalse(viewModel.state.value.modelCatalogVisible)

        viewModel.dispatch(ProviderSetupAction.EditModel("custom/manual-model"))
        assertEquals("custom/manual-model", viewModel.state.value.modelId)
    }

    private fun credential(modelId: String): ProviderCredential =
        ProviderCredential(
            profile = ProviderProfile(
                id = "33333333-3333-4333-8333-333333333333",
                kind = ProviderKind.OPENROUTER,
                baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                modelId = modelId,
                displayName = "OpenRouter",
            ),
            apiKey = API_KEY,
        )

    private companion object {
        const val MODEL_ID = "deepseek/deepseek-v4-pro"
        const val API_KEY = "test-key-kept-out-of-provider-ui-state"
    }
}
