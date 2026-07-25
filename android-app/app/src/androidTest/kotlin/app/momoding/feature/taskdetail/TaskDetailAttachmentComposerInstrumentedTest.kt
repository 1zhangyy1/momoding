package app.momoding.feature.taskdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.attachments.AttachmentKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDetailAttachmentComposerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun imageOnlyFollowUpUsesTheExistingTaskComposerAndCanBeRemovedBeforeSend() {
        val attachmentId = "11111111-1111-4111-8111-111111111111"
        var removed = false
        var state by mutableStateOf(
            TaskDetailUiState(
                taskId = "task-attachment-image-composer",
                loadState = TaskDetailLoadState.READY,
                connection = TaskDetailConnectionState.CONNECTED,
                runState = TaskDetailRunState.SETTLED,
                composerMode = TaskComposerMode.PROMPT,
                phoneLocal = true,
                imageAttachmentInputEnabled = true,
                attachments = listOf(
                    TaskDetailAttachmentUiModel(
                        attachmentId = attachmentId,
                        displayName = "selected-image.png",
                        byteSize = 4096,
                    ),
                ),
                composerBlockedReason = null,
            ),
        )
        compose.setContent {
            TaskDetailScreen(
                state = state,
                onAction = { action ->
                    when (action) {
                        TaskDetailAction.OpenAttachmentMenu -> state = state.copy(attachmentMenuOpen = true)
                        is TaskDetailAction.RemoveAttachment -> {
                            removed = action.attachmentId == attachmentId
                            state = state.copy(attachments = emptyList())
                        }
                        else -> Unit
                    }
                },
            )
        }

        compose.onNodeWithTag("attachment-$attachmentId").assertIsDisplayed()
        compose.onNodeWithTag("action-OpenAttachmentMenu").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Add").assertIsDisplayed()
        compose.onNodeWithTag("action-ImportPhotos").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { state = state.copy(attachmentMenuOpen = false) }
        compose.onNodeWithTag("action-SubmitPrompt").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("action-RemoveAttachment").performClick()
        compose.runOnIdle { assertTrue(removed) }
        compose.onNodeWithTag("attachment-$attachmentId").assertDoesNotExist()
    }

    @Test
    fun textAttachmentUsesTheSameComposerAndExposesTheDocumentPickerAction() {
        val attachmentId = "55555555-5555-4555-8555-555555555555"
        var state by mutableStateOf(
            TaskDetailUiState(
                taskId = "task-attachment-text-composer",
                loadState = TaskDetailLoadState.READY,
                connection = TaskDetailConnectionState.CONNECTED,
                runState = TaskDetailRunState.SETTLED,
                composerMode = TaskComposerMode.PROMPT,
                phoneLocal = true,
                textFileAttachmentInputEnabled = true,
                attachments = listOf(
                    TaskDetailAttachmentUiModel(
                        attachmentId = attachmentId,
                        displayName = "context.md",
                        byteSize = 2048,
                        kind = AttachmentKind.TEXT_FILE,
                    ),
                ),
                composerBlockedReason = null,
            ),
        )
        compose.setContent {
            TaskDetailScreen(
                state = state,
                onAction = { action ->
                    if (action == TaskDetailAction.OpenAttachmentMenu) {
                        state = state.copy(attachmentMenuOpen = true)
                    }
                },
            )
        }

        compose.onNodeWithText("context.md").assertIsDisplayed()
        compose.onNodeWithText("Text file · 2 KB").assertIsDisplayed()
        compose.onNodeWithTag("action-SubmitPrompt").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("action-OpenAttachmentMenu").performClick()
        compose.onNodeWithTag("action-ImportTextFile").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("action-ImportPhotos").assertDoesNotExist()
    }
}
