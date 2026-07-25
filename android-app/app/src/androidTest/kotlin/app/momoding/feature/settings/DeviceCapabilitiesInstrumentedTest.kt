package app.momoding.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.ui.theme.MomodingTheme
import org.junit.Rule
import org.junit.Test

class DeviceCapabilitiesInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capabilitiesExposePartialPhotoStatusAndUserManagedAccess() {
        var states by mutableStateOf(sampleStates())
        var openedFolders = false
        var managedPhotos = false
        var startedFullAccessSetup = false
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                DeviceCapabilitiesScreen(
                    states = states,
                    onBack = {},
                    onRefresh = {
                        states = states.map { state ->
                            if (state.id == AndroidCapabilityId.SAF_FOLDERS) {
                                state.copy(
                                    availability = CapabilityAvailability.NOT_GRANTED,
                                    safeMessage = "Folder access must be authorized again.",
                                )
                            } else {
                                state
                            }
                        }
                    },
                    onOpenFolders = { openedFolders = true },
                    onManagePhotoAccess = { managedPhotos = true },
                    onSetUpFullAccess = { startedFullAccessSetup = true },
                )
            }
        }

        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasTestTag("setup-full-access"))
        compose.onNodeWithTag("setup-full-access").performClick()
        compose.runOnIdle { check(startedFullAccessSetup) }
        compose.onNodeWithTag("capability-SAF_FOLDERS")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Ready",
                ),
            )
        listOf("Ready", "Limited access", "Not granted", "Check failed", "Start session", "Not installed")
            .forEach { label ->
                compose.onNodeWithTag("device-capabilities-list")
                    .performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed()
            }
        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasText("Manage folders"))
        compose.onNodeWithText("Manage folders").performClick()
        compose.runOnIdle { check(openedFolders) }
        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasText("Manage photo access"))
        compose.onNodeWithText("Manage photo access").performClick()
        compose.runOnIdle { check(managedPhotos) }
        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasTestTag("refresh-device-capabilities"))
        compose.onNodeWithTag("refresh-device-capabilities")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("capability-SAF_FOLDERS")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Not granted",
                ),
            )
        compose.onNodeWithText("Folder access must be authorized again.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun screenRemainsUsableAtBothTargetViewportsDarkThemeAndTwoHundredPercentFont() {
        var width by mutableStateOf(360.dp)
        var height by mutableStateOf(800.dp)
        var appearance by mutableStateOf(AppearanceMode.LIGHT)
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density = density, fontScale = fontScale),
            ) {
                MomodingTheme(appearance, systemDark = appearance == AppearanceMode.DARK) {
                    Box(Modifier.size(width, height)) {
                        DeviceCapabilitiesScreen(
                            states = sampleStates(
                                allFiles = CapabilityAvailability.UNSUPPORTED,
                            ),
                            onBack = {},
                            onRefresh = {},
                            onOpenFolders = {},
                        )
                    }
                }
            }
        }

        assertViewportCoreIsReachable()
        compose.runOnIdle {
            width = 412.dp
            height = 915.dp
            appearance = AppearanceMode.DARK
            fontScale = 2f
        }
        compose.waitForIdle()
        assertViewportCoreIsReachable()
    }

    private fun assertViewportCoreIsReachable() {
        compose.onNodeWithText("Device capabilities").assertIsDisplayed()
        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasText("Access is shared across task modes"))
        compose.onNodeWithText("Access is shared across task modes").assertIsDisplayed()
        compose.onNodeWithTag("device-capabilities-list")
            .performScrollToNode(hasTestTag("capability-SHIZUKU_SHELL_UID"))
        compose.onNodeWithTag("capability-SHIZUKU_SHELL_UID")
            .assertIsDisplayed()
    }
}

private fun sampleStates(
    allFiles: CapabilityAvailability = CapabilityAvailability.SESSION_REQUIRED,
): List<AndroidCapabilityState> = listOf(
    capability(AndroidCapabilityId.SAF_FOLDERS, CapabilityAvailability.READY, "One authorized folder available."),
    capability(AndroidCapabilityId.PHOTO_LIBRARY, CapabilityAvailability.PARTIAL, "Only selected photos are available."),
    capability(AndroidCapabilityId.ACCESSIBILITY_CONTROL, CapabilityAvailability.NOT_GRANTED, "Accessibility control is not enabled."),
    capability(AndroidCapabilityId.SCREEN_CAPTURE, CapabilityAvailability.ERROR, "Android access could not be checked. Try again."),
    capability(AndroidCapabilityId.ALL_FILES, allFiles, "Start an Android session first."),
    capability(AndroidCapabilityId.SHIZUKU_SHELL_UID, CapabilityAvailability.MISSING_DEPENDENCY, "Shizuku support is not installed in this build."),
)

private fun capability(
    id: AndroidCapabilityId,
    availability: CapabilityAvailability,
    message: String,
) = AndroidCapabilityState(
    id = id,
    availability = availability,
    source = "Test probe",
    checkedAtMillis = 1L,
    safeMessage = message,
)
