package app.momoding.feature.extensions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.momoding.app.MomodingApp
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillSource
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.feature.settings.SettingsUiState
import app.momoding.ui.navigation.ExtensionsRoute
import app.momoding.ui.navigation.SettingsRoute
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExtensionsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screenShowsTruthfulGroupsAndRequiresConfirmationBeforeImportedDelete() {
        val actions = mutableListOf<ExtensionsAction>()
        val state = mutableStateOf(populatedState())
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                ExtensionsScreen(state.value) { action ->
                    actions += action
                    when (action) {
                        is ExtensionsAction.RequestDeleteSkill -> state.value =
                            state.value.copy(pendingDeleteSkillName = action.name)
                        ExtensionsAction.CancelDeleteSkill -> state.value =
                            state.value.copy(pendingDeleteSkillName = null)
                        else -> Unit
                    }
                }
            }
        }

        compose.onNodeWithText("review-checklist").assertIsDisplayed()
        compose.onNodeWithText("external references are unavailable", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("skill-toggle-review-checklist").assertHasClickAction().performClick()
        assertEquals(
            ExtensionsAction.SetSkillEnabled("review-checklist", false),
            actions.last(),
        )

        compose.onNodeWithTag("extensions-list").performScrollToNode(hasText("relative-files"))
        compose.onNodeWithText("Single-file import cannot use local relative references.")
            .assertIsDisplayed()
        compose.onNodeWithTag("delete-skill-relative-files").performClick()
        compose.onNodeWithText("Remove imported Skill?").assertIsDisplayed()
        compose.onNodeWithText("original document is not changed", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        assertEquals(ExtensionsAction.CancelDeleteSkill, actions.last())

        compose.onNodeWithTag("extensions-list").performScrollToNode(hasText("Child Agents"))
        compose.onNodeWithText("Plan").assertIsDisplayed()
        compose.onNodeWithText("Goal").assertIsDisplayed()
        compose.onNodeWithText("Child Agents").assertIsDisplayed()

        compose.onNodeWithTag("extensions-list").performScrollToNode(
            hasText("Not supported by Pi 0.80.6 in Emulator Alpha"),
        )
        compose.onNodeWithText("Not supported by Pi 0.80.6 in Emulator Alpha").assertIsDisplayed()
        compose.onNodeWithTag("mcp-unsupported").assertHasNoClickAction()
    }

    @Test
    fun settingsEntryOpensExtensionsAndBackReturnsToSettings() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    state = readySettings(),
                    onAction = {},
                    extensionsEntry = { onBack ->
                        ExtensionsScreen(populatedState()) { action ->
                            if (action == ExtensionsAction.Back) onBack()
                        }
                    },
                    initialBackStack = listOf(SettingsRoute),
                )
            }
        }

        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Extensions"))
        compose.onNodeWithTag("action-OpenExtensions").assertHasClickAction().performClick()
        compose.onNodeWithText("Skills and phone-local capabilities").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Developer edition").assertIsDisplayed()
    }

    @Test
    fun screenDistinguishesDisabledAndErrorWithoutOfferingAnErrorToggle() {
        val actions = mutableListOf<ExtensionsAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                ExtensionsScreen(
                    ExtensionsUiState(
                        loading = false,
                        skills = listOf(
                            ExtensionSkillUiState(
                                name = "disabled-skill",
                                description = "Installed and ready when enabled.",
                                source = SkillSource.IMPORTED,
                                enabled = false,
                                availability = SkillAvailability.AVAILABLE,
                                diagnosticMessage = null,
                            ),
                            ExtensionSkillUiState(
                                name = "runtime-error",
                                description = "Could not synchronize.",
                                source = SkillSource.IMPORTED,
                                enabled = false,
                                availability = SkillAvailability.ERROR,
                                diagnosticMessage = "Runtime resource synchronization failed.",
                            ),
                        ),
                    ),
                    actions::add,
                )
            }
        }

        compose.onNodeWithText("Imported · Disabled").assertIsDisplayed()
        compose.onNodeWithTag("skill-toggle-disabled-skill").assertHasClickAction().performClick()
        assertEquals(
            ExtensionsAction.SetSkillEnabled("disabled-skill", true),
            actions.last(),
        )

        compose.onNodeWithTag("extensions-list").performScrollToNode(hasText("runtime-error"))
        compose.onNodeWithText("Imported · Error").assertIsDisplayed()
        compose.onNodeWithText("Runtime resource synchronization failed.").assertIsDisplayed()
        compose.onNodeWithTag("skill-toggle-runtime-error").assertIsNotEnabled()
    }

    @Test
    fun explicitExtensionsRouteRendersInstalledEntry() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    state = readySettings(),
                    onAction = {},
                    extensionsEntry = { onBack ->
                        ExtensionsScreen(populatedState()) { action ->
                            if (action == ExtensionsAction.Back) onBack()
                        }
                    },
                    initialBackStack = listOf(SettingsRoute, ExtensionsRoute),
                )
            }
        }

        compose.onNodeWithText("Extend phone-local Pi").assertIsDisplayed()
    }

    private fun readySettings() = SettingsUiState(
        transport = SecureTransportUiStatus(phase = SecureTransportUiPhase.READY),
    )




    private fun populatedState() = ExtensionsUiState(
        loading = false,
        skills = listOf(
            ExtensionSkillUiState(
                name = "review-checklist",
                description = "Review work against explicit acceptance criteria.",
                source = SkillSource.BUNDLED,
                enabled = true,
                availability = SkillAvailability.AVAILABLE,
                diagnosticMessage = null,
            ),
            ExtensionSkillUiState(
                name = "relative-files",
                description = "Uses local references.",
                source = SkillSource.IMPORTED,
                enabled = false,
                availability = SkillAvailability.UNAVAILABLE,
                diagnosticMessage = "Single-file import cannot use local relative references.",
            ),
        ),
    )
}
