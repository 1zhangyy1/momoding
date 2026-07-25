package app.momoding.feature.newtask

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.files.AuthorizedFolderStatus
import app.momoding.ui.theme.MomodingTheme
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NewTaskFolderInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chooserSelectsOneAuthorizedFolderAndProjectsTaskScopedAccess() {
        val state = mutableStateOf(
            NewTaskUiState(
                draftId = "authorized-folder-instrumented",
                draftInitialized = true,
                connection = NewTaskConnectionState.READY,
                profile = NewTaskProfileState.Ready(
                    "Mac Studio",
                    "OpenRouter",
                    "deepseek-v4-pro",
                    "High",
                ),
                mobileFolders = listOf(
                    folder(PRIMARY_GRANT, "Momoding"),
                    folder(REFERENCE_GRANT, "Reference docs"),
                ),
                selectedGrantId = PRIMARY_GRANT,
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                NewTaskScreen(
                    state = state.value,
                    onAction = { action ->
                        when (action) {
                            NewTaskAction.OpenFolderChooser ->
                                state.value = state.value.copy(folderChooserOpen = true)
                            NewTaskAction.DismissFolderChooser ->
                                state.value = state.value.copy(folderChooserOpen = false)
                            is NewTaskAction.SelectFolder ->
                                state.value = state.value.copy(
                                    selectedGrantId = action.grantId,
                                    folderChooserOpen = false,
                                )
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Momoding").assertIsDisplayed()
        compose.onNodeWithTag("action-OpenFolderChooser").performClick()
        compose.onNodeWithText("Choose mobile folder").assertIsDisplayed()
        compose.onNodeWithText("Reference docs").performClick()

        compose.onNodeWithText("Reference docs").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(REFERENCE_GRANT, state.value.selectedGrantId)
        }
    }

    @Test
    fun phoneLocalNewTaskKeepsFolderOutsideAndMovesPlanModeIntoAddMenu() {
        val state = mutableStateOf(
            NewTaskUiState(
                draftId = "plan-mode-new-task",
                draftInitialized = true,
                phoneLocal = true,
                connection = NewTaskConnectionState.READY,
                profile = NewTaskProfileState.Ready(
                    "On this phone",
                    "OpenRouter",
                    "deepseek-v4-pro",
                    "default",
                ),
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                NewTaskScreen(
                    state = state.value,
                    onAction = { action ->
                        when (action) {
                            NewTaskAction.OpenAttachmentMenu ->
                                state.value = state.value.copy(attachmentMenuOpen = true)
                            NewTaskAction.DismissAttachmentMenu ->
                                state.value = state.value.copy(attachmentMenuOpen = false)
                            NewTaskAction.TogglePlanMode ->
                                state.value = state.value.copy(planMode = !state.value.planMode)
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("action-OpenFolderChooser").assertIsDisplayed()
        compose.onNodeWithTag("action-TogglePlanMode").assertDoesNotExist()
        captureDevice("new-task-composer-folder.png")
        compose.onNodeWithTag("action-OpenAttachmentMenu").performClick()
        compose.onNodeWithTag("action-TogglePlanMode").assertIsDisplayed()
        captureDevice("new-task-add-menu.png")
        compose.onNodeWithTag("action-TogglePlanMode").performClick()
        compose.runOnIdle { assertEquals(true, state.value.planMode) }
        compose.onNodeWithText("Describe what the plan should cover").assertIsDisplayed()
    }

    private fun captureDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = ByteArrayOutputStream().use { stream ->
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.toByteArray()
        }
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe(
            "dd of=/data/local/tmp/$name status=none",
        )
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
        val error = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).use { it.readBytes() }
        check(error.isEmpty()) { error.toString(Charsets.UTF_8) }
    }

    private fun folder(grantId: String, displayName: String) = NewTaskFolderOption(
        grantId = grantId,
        displayName = displayName,
        status = AuthorizedFolderStatus.ACTIVE,
        canRead = true,
    )

    private companion object {
        const val PRIMARY_GRANT = "11111111-1111-4111-8111-111111111111"
        const val REFERENCE_GRANT = "22222222-2222-4222-8222-222222222222"
    }
}
