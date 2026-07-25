package app.momoding.feature.providersetup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.provider.OpenRouterModelSummary
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderModelCatalogInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchableCatalogSelectionKeepsManualModelFieldAsTheSourceOfTruth() {
        var lastAction: ProviderSetupAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                ProviderSetupScreen(
                    state = ProviderSetupUiState(
                        loadState = ProviderSetupLoadState.CONFIGURED,
                        health = ProviderHealth.SAVED,
                        savedProfile = ProviderProfile(
                            id = "11111111-1111-4111-8111-111111111111",
                            kind = ProviderKind.OPENROUTER,
                            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                            modelId = "deepseek/deepseek-v4-pro",
                            displayName = "OpenRouter",
                        ),
                        modelId = "deepseek/deepseek-v4-pro",
                        hasSavedApiKey = true,
                        modelCatalogVisible = true,
                        modelCatalogState = ProviderModelCatalogState.READY,
                        modelCatalog = listOf(
                            OpenRouterModelSummary(
                                id = "openai/gpt-5",
                                name = "GPT-5",
                                contextLength = 400_000,
                                inputModalities = listOf("text", "image"),
                            ),
                        ),
                    ),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("model-search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("model-openai/gpt-5").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(ProviderSetupAction.SelectModel("openai/gpt-5"), lastAction)
        }
    }
}
