package app.momoding.feature.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import app.momoding.core.attachments.AttachmentKind
import app.momoding.feature.newtask.NewTaskAction
import app.momoding.feature.newtask.NewTaskAttachmentUiModel
import app.momoding.feature.newtask.NewTaskConnectionState
import app.momoding.feature.newtask.NewTaskProfileState
import app.momoding.feature.newtask.NewTaskScreen
import app.momoding.feature.newtask.NewTaskUiState
import app.momoding.core.appearance.AppearanceMode
import app.momoding.ui.theme.MomodingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareDraftComposerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sharedVideoIsEditableRemovableAndNeverAutoSends() {
        val actions = mutableListOf<NewTaskAction>()
        val video = attachment(AttachmentKind.VIDEO, "clip.mp4", "video/mp4")
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                NewTaskScreen(
                    state = readyState(
                        draft = "Add context before sending",
                        attachments = listOf(video),
                    ),
                    onAction = actions::add,
                    importNotice = "Video is saved in this draft, but the Agent cannot read video yet. Remove it before sending.",
                )
            }
        }

        compose.onNodeWithTag("android-share-notice").assertIsDisplayed()
        compose.onNode(hasText("Video · Agent cannot read", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("action-EditDraft").assertIsDisplayed()
        compose.onNodeWithTag("action-Send").assertIsNotEnabled()
        assertTrue(actions.isEmpty())

        compose.onNodeWithContentDescription("Remove clip.mp4").assertIsEnabled().performClick()
        assertEquals(NewTaskAction.RemoveAttachment(video.attachmentId), actions.single())
    }

    @Test
    fun sharedTextIsReadyButRequiresAnExplicitSendTap() {
        val actions = mutableListOf<NewTaskAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                NewTaskScreen(
                    state = readyState(draft = "https://example.test"),
                    onAction = actions::add,
                    importNotice = "Shared content is ready to review. Nothing has been sent.",
                )
            }
        }

        compose.onNodeWithText("https://example.test", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("action-Send").assertIsEnabled()
        assertTrue(actions.isEmpty())

        compose.onNodeWithTag("action-Send").performClick()
        assertEquals(NewTaskAction.Send, actions.last())
    }

    private fun readyState(
        draft: String,
        attachments: List<NewTaskAttachmentUiModel> = emptyList(),
    ) = NewTaskUiState(
        draftId = "share-draft",
        phoneLocal = true,
        draft = TextFieldValue(draft),
        draftInitialized = true,
        connection = NewTaskConnectionState.READY,
        profile = NewTaskProfileState.Ready("Phone", "OpenRouter", "model", "default"),
        photoAttachmentInputEnabled = true,
        textFileAttachmentInputEnabled = true,
        imageAttachmentRuntimeReady = true,
        textFileAttachmentRuntimeReady = true,
        attachments = attachments,
    )

    private fun attachment(kind: AttachmentKind, name: String, mime: String) =
        NewTaskAttachmentUiModel(
            attachmentId = "00000000-0000-4000-8000-000000000001",
            kind = kind,
            displayName = name,
            mimeType = mime,
            byteSize = 1024,
            thumbnailPng = null,
            width = null,
            height = null,
        )
}
