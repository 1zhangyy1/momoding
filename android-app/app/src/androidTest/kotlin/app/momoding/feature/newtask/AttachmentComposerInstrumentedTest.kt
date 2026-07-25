package app.momoding.feature.newtask

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.attachments.AttachmentKind
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AttachmentComposerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun readyComposerRemovesSetupDetailsAndHidesSuggestionsAfterDraftStarts() {
        val state = mutableStateOf(readyState())
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                NewTaskScreen(
                    state = state.value,
                    onAction = { action ->
                        when (action) {
                            is NewTaskAction.EditDraft ->
                                state.value = state.value.copy(draft = action.value)
                            NewTaskAction.OpenAttachmentMenu ->
                                state.value = state.value.copy(attachmentMenuOpen = true)
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("What should Momoding do?").assertIsDisplayed()
        compose.onNodeWithText("Draft a checklist for my next project").assertIsDisplayed()
        compose.onNodeWithText("Momoding is ready").assertDoesNotExist()
        compose.onNodeWithText("On this phone").assertDoesNotExist()
        compose.onNodeWithText("multimodal-gate").assertDoesNotExist()

        compose.onNodeWithText("Draft a checklist for my next project").performClick()

        compose.onAllNodesWithTag("action-EditDraft").assertCountEquals(1)
        compose.onNodeWithText("Help me break a task into clear steps").assertDoesNotExist()
        compose.onNodeWithText("Review text that I paste here").assertDoesNotExist()
        compose.onNodeWithText("What should Momoding do?").assertIsDisplayed()
        compose.onNodeWithTag("action-OpenFolderChooser").assertIsDisplayed()
        compose.onNodeWithTag("action-TogglePlanMode").assertDoesNotExist()
        compose.onNodeWithTag("action-OpenAttachmentMenu").performClick()
        compose.onNodeWithTag("action-TogglePlanMode").assertIsDisplayed()
    }

    @Test
    fun attachmentSurfaceUsesOneComposerAndFailsClosedUntilRuntimeGate() {
        val attachment = NewTaskAttachmentUiModel(
            attachmentId = "00000000-0000-4000-8000-000000000001",
            kind = AttachmentKind.IMAGE,
            displayName = "gate.png",
            mimeType = "image/png",
            byteSize = 2048,
            thumbnailPng = null,
            width = 10,
            height = 10,
        )
        val state = mutableStateOf(readyState())
        var sendDispatched = false
        var cameraRequested = false
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
                            is NewTaskAction.RemoveAttachment ->
                                state.value = state.value.copy(attachments = emptyList())
                            NewTaskAction.RequestCameraCapture -> {
                                cameraRequested = true
                                state.value = state.value.copy(attachmentMenuOpen = false)
                            }
                            NewTaskAction.Send -> sendDispatched = true
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("action-OpenAttachmentMenu").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Text file").assertIsDisplayed()
        compose.onNodeWithTag("action-ImportCamera").performClick()
        compose.runOnIdle { assertTrue(cameraRequested) }

        compose.runOnIdle {
            state.value = readyState().copy(textFileAttachmentInputEnabled = false)
        }
        compose.onNodeWithTag("action-OpenAttachmentMenu").performClick()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Text file").assertDoesNotExist()

        compose.runOnIdle {
            state.value = readyState().copy(attachments = listOf(attachment))
        }
        compose.onNodeWithText("gate.png").assertIsDisplayed()
        compose.onNodeWithTag("action-Send").assertIsNotEnabled()
        compose.onNodeWithTag("action-RemoveAttachment").performClick()
        compose.runOnIdle { assertTrue(state.value.attachments.isEmpty()) }

        compose.runOnIdle {
            state.value = readyState().copy(
                attachments = listOf(attachment),
                imageAttachmentRuntimeReady = true,
            )
        }
        compose.onNodeWithTag("action-Send").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(sendDispatched) }
    }

    private fun readyState() = NewTaskUiState(
        draftId = "attachment-gate-composer-gate",
        draftInitialized = true,
        phoneLocal = true,
        connection = NewTaskConnectionState.READY,
        profile = NewTaskProfileState.Ready(
            hostAlias = "On this phone",
            provider = "OpenRouter",
            model = "multimodal-gate",
            thinking = "default",
        ),
        photoAttachmentInputEnabled = true,
        textFileAttachmentInputEnabled = true,
        cameraAttachmentInputEnabled = true,
        imageAttachmentRuntimeReady = false,
        textFileAttachmentRuntimeReady = false,
    )
}
