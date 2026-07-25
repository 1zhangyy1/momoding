package app.momoding.feature.filechanges

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.data.FileChangeItem
import app.momoding.core.data.FileChangeRecord
import app.momoding.core.files.FileChangeSetState
import app.momoding.ui.theme.MomodingTheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileChangeScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun exactBeforeAfterAndNewContentAreVisibleBeforeEitherLocalDecision() {
        val actions = mutableListOf<FileChangeAction>()
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                FileChangeScreen(
                    state = pendingState(),
                    onBack = {},
                    onAction = { actions += it },
                )
            }
        }

        compose.onNodeWithText("Review changes").assertIsDisplayed()
        compose.onNodeWithText("Prepare the mobile release notes").assertIsDisplayed()
        compose.onNodeWithText("README.txt").assertIsDisplayed()
        compose.onNodeWithText("README-final.txt").assertIsDisplayed()
        compose.onAllNodesWithText(
            "Folder · Momoding-Demo / release",
            substring = false,
        ).assertCountEquals(3)
        compose.onNodeWithText(
            "These changes write to the Android folder you authorized. " +
                "Review every item before applying.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("file-change-list").performScrollToNode(hasText("release ready"))
        compose.onNodeWithText("release ready").assertIsDisplayed()
        capture("file-change-review-content.png")
        compose.onNodeWithTag("file-change-list")
            .performScrollToNode(hasText("Prepare the mobile release notes"))
        capture("file-change-review-top.png")

        compose.onNodeWithTag("file-change-reject")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("file-change-approve")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(FileChangeAction.Reject, FileChangeAction.Approve),
                actions,
            )
        }
    }

    @Test
    fun writeDeleteAndPartialFailureRemainInspectableAfterCommit() {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                FileChangeScreen(
                    state = committedPartialState(),
                    onBack = {},
                    onAction = {},
                )
            }
        }

        compose.onAllNodesWithText("Partially applied").assertCountEquals(2)
        compose.onNodeWithTag("file-change-list")
            .performScrollToNode(hasText("Modify file", substring = true))
        compose.onNodeWithText("Replacement content · 17 bytes").assertIsDisplayed()
        compose.onNodeWithTag("file-change-list")
            .performScrollToNode(hasText("Delete file", substring = true))
        compose.onNodeWithText("obsolete.txt").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("FILE_PRECONDITION_FAILED").assertIsDisplayed()
        capture("write-delete-partial-failure.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = ByteArrayOutputStream().use { stream ->
            check(
                compose.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
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

    private fun pendingState() = FileChangeUiState(
        taskId = TASK_ID,
        loadState = FileChangeLoadState.READY,
        connected = true,
        nowMillis = 1_000,
        record = FileChangeRecord(
            preparedId = PREPARED_ID,
            taskId = TASK_ID,
            commitCallId = COMMIT_CALL_ID,
            purpose = "Prepare the mobile release notes",
            planDigest = "a".repeat(64),
            state = FileChangeSetState.AWAITING_APPROVAL,
            expiresAtMillis = 60_000,
            items = listOf(
                FileChangeItem(
                    operationId = "44444444-4444-4444-8444-444444444444",
                    kind = "rename",
                    beforeName = "README.txt",
                    afterName = "README-final.txt",
                    beforeParentAlias = "doc-111111111111111111111111",
                    afterParentAlias = "doc-111111111111111111111111",
                    beforeParentDisplayPath = "Momoding-Demo / release",
                    afterParentDisplayPath = "Momoding-Demo / release",
                    mimeType = "text/plain",
                    content = null,
                    contentByteCount = null,
                    resultState = null,
                    errorCode = null,
                ),
                FileChangeItem(
                    operationId = "55555555-5555-4555-8555-555555555555",
                    kind = "create_file",
                    beforeName = null,
                    afterName = "release.txt",
                    beforeParentAlias = null,
                    afterParentAlias = "doc-111111111111111111111111",
                    beforeParentDisplayPath = null,
                    afterParentDisplayPath = "Momoding-Demo / release",
                    mimeType = "text/plain",
                    content = "release ready",
                    contentByteCount = 13,
                    resultState = null,
                    errorCode = null,
                ),
            ),
            approvalReceiptId = null,
            failureCode = null,
        ),
    )

    private fun committedPartialState() = FileChangeUiState(
        taskId = TASK_ID,
        loadState = FileChangeLoadState.READY,
        connected = true,
        record = FileChangeRecord(
            preparedId = PREPARED_ID,
            taskId = TASK_ID,
            commitCallId = COMMIT_CALL_ID,
            purpose = "Update the report and remove an obsolete file",
            planDigest = "b".repeat(64),
            state = FileChangeSetState.PARTIALLY_FAILED,
            expiresAtMillis = 60_000,
            items = listOf(
                FileChangeItem(
                    operationId = "66666666-6666-4666-8666-666666666666",
                    kind = "write_file",
                    beforeName = "report.txt",
                    afterName = "report.txt",
                    beforeParentAlias = "doc-111111111111111111111111",
                    afterParentAlias = "doc-111111111111111111111111",
                    beforeParentDisplayPath = "Momoding-Demo / release",
                    afterParentDisplayPath = "Momoding-Demo / release",
                    mimeType = "text/plain",
                    content = "updated report ok",
                    contentByteCount = 17,
                    resultState = "succeeded",
                    errorCode = null,
                ),
                FileChangeItem(
                    operationId = "77777777-7777-4777-8777-777777777777",
                    kind = "delete_file",
                    beforeName = "obsolete.txt",
                    afterName = null,
                    beforeParentAlias = "doc-111111111111111111111111",
                    afterParentAlias = null,
                    beforeParentDisplayPath = "Momoding-Demo / release",
                    afterParentDisplayPath = null,
                    mimeType = "text/plain",
                    content = null,
                    contentByteCount = null,
                    resultState = "failed",
                    errorCode = "FILE_PRECONDITION_FAILED",
                ),
            ),
            approvalReceiptId = "88888888-8888-4888-8888-888888888888",
            failureCode = "FILE_PRECONDITION_FAILED",
        ),
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val PREPARED_ID = "22222222-2222-4222-8222-222222222222"
        const val COMMIT_CALL_ID = "33333333-3333-4333-8333-333333333333"
    }
}
