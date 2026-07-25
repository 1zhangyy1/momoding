package app.momoding.feature.taskdetail

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.core.appearance.AppearanceMode
import app.momoding.ui.theme.MomodingTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TaskWorkResultsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun markdownShowsCopyableCodeLinkAndTableContent() {
        show(
            TimelineItem.AssistantText(
                stableKey = "assistant-result",
                text = """
                    Here is the result.

                    ```kotlin
                    val answer = 42
                    ```

                    [Open documentation](https://example.com/docs)

                    | Check | Result |
                    | --- | --- |
                    | Unit tests | Passed |
                """.trimIndent(),
                partial = false,
            ),
        )

        compose.onNodeWithTag("assistant-markdown").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
            assertEquals(
                "val answer = 42",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim(),
            )
        }
        compose.onNodeWithText("Open documentation").assertIsDisplayed()
        compose.onNodeWithText("Unit tests", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Passed", useUnmergedTree = true).assertIsDisplayed()
        capture("markdown-code-table.png")
    }

    @Test
    fun expandedRealToolResultShowsTestSummaryLogsAndSources() {
        show(
            TimelineItem.ToolActivity(
                stableKey = "tool:tests-1",
                toolCallId = "tests-1",
                title = "Tests passed",
                detail = "Completed",
                state = ToolActivityState.SUCCESS,
                kind = ToolActivityKind.TEST,
                result = ToolResultUiModel(
                    text = "12 tests completed, 0 failed\nBUILD SUCCESSFUL",
                    sources = listOf("app/build.gradle.kts", "test-results/index.html"),
                ),
                expanded = true,
            ),
        )

        compose.onNodeWithText("Tests passed").assertIsDisplayed()
        compose.onNodeWithTag("tool-result-tests-1", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            "12 tests completed, 0 failed",
            substring = true,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Sources", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("app/build.gradle.kts", useUnmergedTree = true).assertIsDisplayed()
        capture("test-result-sources.png")
    }

    @Test
    fun expandedFailedCommandShowsFailureAndDiagnosticOutput() {
        show(
            TimelineItem.ToolActivity(
                stableKey = "tool:command-failed",
                toolCallId = "command-failed",
                title = "Command failed",
                detail = "Completed with errors",
                state = ToolActivityState.FAILURE,
                kind = ToolActivityKind.TERMINAL,
                result = ToolResultUiModel(
                    text = "exitCode: 1\nCompilation failed at Main.kt:12",
                    sources = listOf("Main.kt"),
                ),
                expanded = true,
            ),
        )

        compose.onNodeWithText("Command failed").assertIsDisplayed()
        compose.onNodeWithTag("tool-result-command-failed", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Compilation failed at Main.kt:12",
            substring = true,
            useUnmergedTree = true,
        ).assertIsDisplayed()
        capture("command-failure.png")
    }

    @Test
    fun providerRecoveryShowsTheLatestErrorOnceAndNoGlobalArtifactActions() {
        val error = "OpenRouter API key is invalid. Update it in Settings."
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = TaskDetailUiState(
                        taskId = "task-provider-recovery",
                        title = "Review Android release",
                        loadState = TaskDetailLoadState.READY,
                        connection = TaskDetailConnectionState.CONNECTED,
                        runState = TaskDetailRunState.FAILED,
                        timeline = TimelineWindow(
                            settledItems = listOf(
                                TimelineItem.UserMessage("user-prompt", "Review the release"),
                                TimelineItem.Error("provider-error", error),
                            ),
                        ),
                        composerMode = TaskComposerMode.PROMPT,
                        phoneLocal = true,
                        composerBlockedReason = null,
                    ),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithText("Provider needs attention").assertIsDisplayed()
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText(error, useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("action-OpenDiff").assertDoesNotExist()
        compose.onNodeWithTag("action-OpenOutputs").assertDoesNotExist()
        capture("phase-a-provider-recovery.png")
    }

    @Test
    fun successfulFileActivitiesOwnTheirArtifactActions() {
        var lastAction: TaskDetailAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = TaskDetailUiState(
                        taskId = "task-file-actions",
                        title = "Update Android files",
                        loadState = TaskDetailLoadState.READY,
                        connection = TaskDetailConnectionState.CONNECTED,
                        runState = TaskDetailRunState.SETTLED,
                        timeline = TimelineWindow(
                            settledItems = listOf(
                                TimelineItem.ToolActivity(
                                    stableKey = "tool:prepare",
                                    toolCallId = "prepare",
                                    title = "Prepared file changes",
                                    detail = "Completed",
                                    state = ToolActivityState.SUCCESS,
                                    kind = ToolActivityKind.MOBILE_FILE,
                                    action = ToolActivityAction.REVIEW_CHANGES,
                                ),
                                TimelineItem.ToolActivity(
                                    stableKey = "tool:commit",
                                    toolCallId = "commit",
                                    title = "Applied file changes",
                                    detail = "Completed",
                                    state = ToolActivityState.SUCCESS,
                                    kind = ToolActivityKind.MOBILE_FILE,
                                    action = ToolActivityAction.VIEW_OUTPUTS,
                                ),
                            ),
                        ),
                        composerMode = TaskComposerMode.PROMPT,
                        phoneLocal = true,
                        composerBlockedReason = null,
                    ),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("action-OpenDiff").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TaskDetailAction.OpenDiff, lastAction) }
        compose.onNodeWithTag("action-OpenOutputs").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(TaskDetailAction.OpenOutputs, lastAction) }
        capture("phase-a-contextual-actions.png")
    }

    @Test
    fun structuredPlanCardShowsStepsAndDispatchesLatestImplementAction() {
        val digest = "a".repeat(64)
        var implemented: String? = null
        val current = mutableStateOf(
            TaskDetailUiState(
                taskId = "task-plan-results",
                title = "Plan Android release",
                hostAlias = "On-device · test/model",
                loadState = TaskDetailLoadState.READY,
                connection = TaskDetailConnectionState.CONNECTED,
                runState = TaskDetailRunState.SETTLED,
                timeline = TimelineWindow(
                    settledItems = listOf(
                        TimelineItem.Plan(
                            stableKey = "plan:call-1",
                            toolCallId = "call-1",
                            explanation = "Add one safe release gate.",
                            steps = listOf(
                                TaskPlanStepUiModel("inspect", "Inspect checks", TaskPlanStepState.COMPLETED),
                                TaskPlanStepUiModel("verify", "Run focused tests", TaskPlanStepState.PENDING),
                            ),
                            planDigest = digest,
                        ),
                    ),
                ),
                composerMode = TaskComposerMode.PROMPT,
                phoneLocal = true,
                composerBlockedReason = null,
                planMode = true,
                latestPlanDigest = digest,
            ),
        )
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = current.value,
                    onAction = { action ->
                        when (action) {
                            is TaskDetailAction.ImplementPlan -> implemented = action.planDigest
                            TaskDetailAction.OpenAttachmentMenu ->
                                current.value = current.value.copy(attachmentMenuOpen = true)
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("plan-card-${digest.take(12)}").assertIsDisplayed()
        compose.onNodeWithText("Add one safe release gate.").assertIsDisplayed()
        compose.onNodeWithText("Inspect checks").assertIsDisplayed()
        compose.onNodeWithText("Run focused tests").assertIsDisplayed()
        compose.onNodeWithText("Implement plan").performClick()
        compose.runOnIdle { assertEquals(digest, implemented) }
        compose.onNodeWithTag("action-TogglePlanMode").assertDoesNotExist()
        compose.onNodeWithTag("action-OpenAttachmentMenu").performClick()
        compose.onNodeWithTag("action-TogglePlanMode").assertIsDisplayed()
        compose.onNodeWithTag("action-OpenCreateGoal").assertIsDisplayed()
        captureDevice("task-add-menu.png")
        compose.runOnIdle {
            current.value = current.value.copy(attachmentMenuOpen = false)
        }
        capture("plan-card.png")
    }

    @Test
    fun childAgentCardsShowDurableStatesMarkdownUsageAndExactCancelAction() {
        val current = mutableStateOf(
            TaskDetailUiState(
                taskId = "task-child-product",
                title = "Review Android release",
                hostAlias = "On-device · test/model",
                loadState = TaskDetailLoadState.READY,
                connection = TaskDetailConnectionState.CONNECTED,
                runState = TaskDetailRunState.RUNNING,
                composerMode = TaskComposerMode.FOLLOW_UP,
                phoneLocal = true,
                composerBlockedReason = null,
                childAgents = listOf(
                    TaskChildAgentUiModel(
                        parentToolCallId = "delegate-complete",
                        childId = "child-1",
                        name = "Build analyst",
                        instruction = "Analyze reproducibility.",
                        state = TaskChildAgentState.COMPLETED,
                        summary = "Build is reproducible.",
                        result = "**Build** is reproducible with a pinned toolchain.",
                        model = "deepseek/deepseek-v4-pro",
                        turnCount = 1,
                        inputTokens = 21,
                        outputTokens = 8,
                        contextTokens = 29,
                        eventCount = 6,
                    ),
                    TaskChildAgentUiModel(
                        parentToolCallId = "delegate-running",
                        childId = "child-2",
                        name = "Device analyst",
                        instruction = "Analyze device variance.",
                        state = TaskChildAgentState.RUNNING,
                        eventCount = 2,
                    ),
                ),
            ),
        )
        var cancelled: String? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = current.value,
                    onAction = { action ->
                        when (action) {
                            is TaskDetailAction.ToggleChildAgent -> current.value = current.value.copy(
                                childAgents = current.value.childAgents.map { child ->
                                    if (child.parentToolCallId == action.parentToolCallId) {
                                        child.copy(expanded = !child.expanded)
                                    } else {
                                        child
                                    }
                                },
                            )
                            is TaskDetailAction.CancelChildAgent -> cancelled = action.parentToolCallId
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("child-agents-section").assertIsDisplayed()
        compose.onNodeWithText("Child agents · 2").assertIsDisplayed()
        compose.onNodeWithText("1 completed · 1 running").assertIsDisplayed()
        compose.onNodeWithTag("child-agent-delegate-complete").performClick()
        compose.onNodeWithText("Build is reproducible with a pinned toolchain.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("21 in / 8 out", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("child-agent-delegate-running").performScrollTo().performClick()
        compose.onNodeWithTag("action-CancelChildAgent-delegate-running")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals("delegate-running", cancelled) }
    }

    @Test
    fun pausedGoalCardShowsProgressAndDispatchesOnlyExplicitLifecycleActions() {
        var lastAction: TaskDetailAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = TaskDetailUiState(
                        taskId = "task-goal-results",
                        title = "Verify Android release",
                        hostAlias = "On-device · test/model",
                        loadState = TaskDetailLoadState.READY,
                        connection = TaskDetailConnectionState.CONNECTED,
                        runState = TaskDetailRunState.SETTLED,
                        timeline = TimelineWindow(
                            settledItems = listOf(
                                TimelineItem.RunStatus("goal-start", "Starting goal"),
                                TimelineItem.AssistantText(
                                    "goal-progress",
                                    "First checkpoint saved.",
                                    partial = false,
                                ),
                            ),
                        ),
                        composerMode = TaskComposerMode.PROMPT,
                        phoneLocal = true,
                        composerBlockedReason = null,
                        goal = TaskGoalUiModel(
                            goalId = "goal-card-test",
                            instruction = "Finish three deterministic verification checkpoints.",
                            state = TaskGoalState.PAUSED,
                            progressSummary = "Two checkpoints passed.",
                            progressMarker = "2/3",
                            pauseReason = "user_requested",
                            automaticTurnCount = 1,
                        ),
                    ),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithTag("goal-card-goal-card-test").assertIsDisplayed()
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("Two checkpoints passed.").assertIsDisplayed()
        compose.onNodeWithText("2/3 · Auto turns 1/20").assertIsDisplayed()
        compose.onNodeWithTag("action-ResumeGoal").performClick()
        compose.runOnIdle { assertEquals(TaskDetailAction.ResumeGoal, lastAction) }
        compose.onNodeWithTag("action-ClearGoal").performClick()
        compose.runOnIdle {
            assertEquals(TaskDetailAction.ConfirmGoal(GoalConfirmation.CLEAR), lastAction)
        }
        capture("goal-paused-card.png")
    }

    @Test
    fun createGoalDialogExplainsLimitsAndDispatchesStart() {
        var lastAction: TaskDetailAction? = null
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = TaskDetailUiState(
                        taskId = "task-goal-editor",
                        title = "Create a Goal",
                        hostAlias = "On-device · test/model",
                        loadState = TaskDetailLoadState.READY,
                        connection = TaskDetailConnectionState.CONNECTED,
                        runState = TaskDetailRunState.SETTLED,
                        composerMode = TaskComposerMode.PROMPT,
                        phoneLocal = true,
                        composerBlockedReason = null,
                        goalEditorOpen = true,
                        goalEditorMode = GoalEditorMode.CREATE,
                        goalDraft = androidx.compose.ui.text.input.TextFieldValue(
                            "Finish the release verification.",
                        ),
                    ),
                    onAction = { lastAction = it },
                )
            }
        }

        compose.onNodeWithText("Create goal").assertIsDisplayed()
        compose.onNodeWithText("20 automatic turns", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("action-SubmitGoal").performClick()
        compose.runOnIdle { assertEquals(TaskDetailAction.SubmitGoal, lastAction) }
    }

    private fun show(item: TimelineItem) {
        compose.setContent {
            MomodingTheme(AppearanceMode.LIGHT, systemDark = false) {
                TaskDetailScreen(
                    state = TaskDetailUiState(
                        taskId = "task-results",
                        title = "Review Android project",
                        hostAlias = "On-device · test/model",
                        loadState = TaskDetailLoadState.READY,
                        connection = TaskDetailConnectionState.CONNECTED,
                        runState = TaskDetailRunState.SETTLED,
                        timeline = TimelineWindow(settledItems = listOf(item)),
                        composerMode = TaskComposerMode.PROMPT,
                        phoneLocal = true,
                        composerBlockedReason = null,
                    ),
                    onAction = {},
                )
            }
        }
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
}
