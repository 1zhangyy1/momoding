package app.momoding.feature.files

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import app.momoding.app.MomodingApp
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.files.AuthorizedDocumentMetadata
import app.momoding.core.files.AuthorizedFolderStatus
import app.momoding.core.files.AuthorizedFolderSummary
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.feature.settings.SettingsUiState
import app.momoding.feature.settings.SettingsScreen
import app.momoding.ui.navigation.AuthorizedFoldersRoute
import app.momoding.ui.navigation.HostGateRoute
import app.momoding.ui.navigation.SettingsRoute
import app.momoding.ui.theme.MomodingTheme
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AuthorizedFoldersInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screenExposesMetadataOnlyScopeAndRequiresExplicitRevokeConfirmation() {
        val actions = mutableListOf<AuthorizedFoldersAction>()
        val state = mutableStateOf(populatedState())
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                AuthorizedFoldersScreen(
                    state = state.value,
                    onAction = { action ->
                        actions += action
                        if (action is AuthorizedFoldersAction.RequestRevoke) {
                            state.value = state.value.copy(
                                pendingRevokeGrantId = action.grantId,
                            )
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("You choose every folder").assertIsDisplayed()
        compose.onNodeWithText("opaque grant ID", substring = true).assertIsDisplayed()
        compose.onNodeWithText("content://", substring = true).assertDoesNotExist()
        compose.onNodeWithText("private-plan.txt").assertIsDisplayed()

        compose.onNodeWithText("Add folder").performClick()
        assertEquals(AuthorizedFoldersAction.AddFolder, actions.last())

        compose.onNodeWithText("Remove").performClick()
        assertEquals(
            AuthorizedFoldersAction.RequestRevoke(GRANT_ID),
            actions.last(),
        )

        compose.onNodeWithText("Remove folder access?").assertIsDisplayed()
        compose.onNodeWithText("does not delete or modify any files", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(AuthorizedFoldersAction.CancelRevoke, actions.last())
    }

    @Test
    fun settingsExposesInstalledMobileFilesEntry() {
        val filesEntryOpened = AtomicBoolean(false)
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                SettingsScreen(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(
                            phase = SecureTransportUiPhase.READY,
                        ),
                    ),
                    onAction = {},
                    contentPadding = PaddingValues(),
                    onOpenMobileFiles = { filesEntryOpened.set(true) },
                )
            }
        }

        compose.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Mobile files"))
        compose.onNodeWithTag("action-OpenMobileFiles").assertHasClickAction().performClick()
        assertEquals(true, filesEntryOpened.get())
    }

    @Test
    fun installedMobileFilesRouteBackReturnsToSettings() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(
                            phase = SecureTransportUiPhase.READY,
                        ),
                    ),
                    onAction = {},
                    authorizedFoldersEntry = { _, onBack ->
                        AuthorizedFoldersScreen(
                            state = AuthorizedFoldersUiState(loading = false),
                            onAction = { action ->
                                if (action == AuthorizedFoldersAction.Back) onBack()
                            },
                        )
                    },
                    initialBackStack = listOf(SettingsRoute, AuthorizedFoldersRoute()),
                )
            }
        }

        compose.onNodeWithText("Android SAF folders").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Developer edition").assertIsDisplayed()
    }

    @Test
    fun unpairedHostGateKeepsMobileFilesReachableAndRevocable() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, false) {
                MomodingApp(
                    state = SettingsUiState(
                        transport = SecureTransportUiStatus(
                            phase = SecureTransportUiPhase.UNPAIRED,
                        ),
                    ),
                    onAction = {},
                    authorizedFoldersEntry = { _, onBack ->
                        AuthorizedFoldersScreen(
                            state = populatedState(),
                            onAction = { action ->
                                if (action == AuthorizedFoldersAction.Back) onBack()
                            },
                        )
                    },
                    initialBackStack = listOf(HostGateRoute),
                )
            }
        }

        compose.onNodeWithText("Manage mobile files").assertHasClickAction().performClick()
        compose.onNodeWithText("Android SAF folders").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertHasClickAction()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Connect your Pi Host").assertIsDisplayed()
    }

    private fun populatedState() = AuthorizedFoldersUiState(
        loading = false,
        folders = listOf(
            AuthorizedFolderSummary(
                grantId = GRANT_ID,
                displayName = "Project files",
                authorityLabel = "Android SAF",
                status = AuthorizedFolderStatus.ACTIVE,
                canRead = true,
                canWrite = true,
            ),
        ),
        selectedGrantId = GRANT_ID,
        documents = listOf(
            AuthorizedDocumentMetadata(
                alias = "doc-111111111111111111111111",
                parentAlias = null,
                displayName = "Project files",
                mimeType = "vnd.android.document/directory",
                byteCount = null,
                lastModifiedMillis = 1L,
                depth = 0,
            ),
            AuthorizedDocumentMetadata(
                alias = "doc-222222222222222222222222",
                parentAlias = "doc-111111111111111111111111",
                displayName = "private-plan.txt",
                mimeType = "text/plain",
                byteCount = 12,
                lastModifiedMillis = 2L,
                depth = 1,
            ),
        ),
    )

    private companion object {
        const val GRANT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
