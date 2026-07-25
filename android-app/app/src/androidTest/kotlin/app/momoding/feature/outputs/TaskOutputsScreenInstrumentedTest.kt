package app.momoding.feature.outputs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.core.data.FileChangeItem
import app.momoding.core.data.FileChangeRecord
import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.files.FileChangeSetState
import app.momoding.ui.theme.MomodingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskOutputsScreenInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedPhoneLocalFileResultShowsRealOutputAndAuthorizedSourceScope() {
        compose.setContent {
            MomodingTheme(
                appearance = AppearanceMode.LIGHT,
                systemDark = false,
            ) {
                TaskOutputsScreen(
                    state = TaskOutputsUiState(
                        loading = false,
                        sourceFolderName = "Project Notes",
                        record = FileChangeRecord(
                            preparedId = "11111111-1111-4111-8111-111111111111",
                            taskId = "22222222-2222-4222-8222-222222222222",
                            commitCallId = "33333333-3333-4333-8333-333333333333",
                            purpose = "Create the reviewed mobile report",
                            planDigest = "a".repeat(64),
                            state = FileChangeSetState.COMPLETED,
                            expiresAtMillis = Long.MAX_VALUE,
                            items = listOf(
                                FileChangeItem(
                                    operationId = "44444444-4444-4444-8444-444444444444",
                                    kind = "create_file",
                                    beforeName = null,
                                    afterName = "mobile-report.txt",
                                    beforeParentAlias = null,
                                    afterParentAlias = "doc-1234567890abcdef12345678",
                                    beforeParentDisplayPath = null,
                                    afterParentDisplayPath = "Project Notes",
                                    mimeType = "text/plain",
                                    content = "Reviewed on Android.\n",
                                    contentByteCount = 21,
                                    resultState = "succeeded",
                                    errorCode = null,
                                ),
                            ),
                            approvalReceiptId = "55555555-5555-4555-8555-555555555555",
                            failureCode = null,
                            grantId = "66666666-6666-4666-8666-666666666666",
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("task-outputs-screen").assertIsDisplayed()
        compose.onNodeWithText("Applied on this device").assertIsDisplayed()
        compose.onNodeWithText("mobile-report.txt").assertIsDisplayed()
        compose.onNodeWithText("Project Notes").assertIsDisplayed()
        compose.onNodeWithText("Task-scoped Android SAF access").assertIsDisplayed()
    }
}
