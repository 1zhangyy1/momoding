package app.momoding.feature.localtask

import app.momoding.core.runtime.local.PiNativeOpenRouterScenarioStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneLocalTaskViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `send projects native Pi partial text and completes the phone-local task`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prompts = mutableListOf<String>()
        val partial = status(
            terminal = false,
            events = listOf(
                Json.parseToJsonElement(
                    """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","partial":{"role":"assistant","content":[{"type":"text","text":"Partial phone reply"}]}}}""",
                ) as JsonObject,
            ),
        )
        val terminal = status(
            terminal = true,
            finalText = "Complete phone reply",
            completed = 1,
        )
        val viewModel = PhoneLocalTaskViewModel(
            runPrompt = { prompt, onStatus ->
                prompts += prompt
                onStatus(partial)
                terminal
            },
            stopPrompt = {},
        )

        viewModel.dispatch(PhoneLocalTaskAction.EditDraft("  Explain the project  "))
        viewModel.dispatch(PhoneLocalTaskAction.Send)
        advanceUntilIdle()

        assertEquals(listOf("Explain the project"), prompts)
        assertEquals(PhoneLocalTaskPhase.COMPLETED, viewModel.state.value.phase)
        assertEquals("Complete phone reply", viewModel.state.value.answer)
        assertEquals("Explain the project", viewModel.state.value.submittedPrompt)
    }

    @Test
    fun `Provider failure remains a safe visible task error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = PhoneLocalTaskViewModel(
            runPrompt = { _, _ ->
                status(
                    terminal = true,
                    providerError = "OpenRouter API key is invalid",
                    failed = 1,
                )
            },
            stopPrompt = {},
        )
        viewModel.dispatch(PhoneLocalTaskAction.EditDraft("Test Provider"))
        viewModel.dispatch(PhoneLocalTaskAction.Send)
        advanceUntilIdle()

        assertEquals(PhoneLocalTaskPhase.FAILED, viewModel.state.value.phase)
        assertEquals("OpenRouter API key is invalid", viewModel.state.value.error)
        assertTrue(viewModel.state.value.answer.isEmpty())
    }

    @Test
    fun `Stop reaches the runtime and settles the visible task as stopped`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releasePrompt = CompletableDeferred<Unit>()
        var stopCalls = 0
        val viewModel = PhoneLocalTaskViewModel(
            runPrompt = { _, _ ->
                releasePrompt.await()
                status(
                    terminal = true,
                    stopRequested = true,
                    stopCompleted = true,
                    hasAbort = true,
                )
            },
            stopPrompt = {
                stopCalls += 1
                releasePrompt.complete(Unit)
            },
        )

        viewModel.dispatch(PhoneLocalTaskAction.EditDraft("Stop this task"))
        viewModel.dispatch(PhoneLocalTaskAction.Send)
        runCurrent()
        viewModel.dispatch(PhoneLocalTaskAction.Stop)
        advanceUntilIdle()

        assertEquals(1, stopCalls)
        assertEquals(PhoneLocalTaskPhase.STOPPED, viewModel.state.value.phase)
    }

    private fun status(
        terminal: Boolean,
        events: List<JsonObject> = emptyList(),
        finalText: String? = null,
        providerError: String? = null,
        completed: Int = 0,
        failed: Int = 0,
        stopRequested: Boolean = false,
        stopCompleted: Boolean = false,
        hasAbort: Boolean = false,
    ): PiNativeOpenRouterScenarioStatus =
        PiNativeOpenRouterScenarioStatus(
            kind = "prompt",
            phase = if (terminal) "settled" else "running",
            terminal = terminal,
            expectationMet = terminal && failed == 0,
            promptSettled = terminal,
            stopRequested = stopRequested,
            stopCompleted = stopCompleted,
            promptError = null,
            providerError = providerError,
            stopError = null,
            finalText = finalText,
            events = events,
            eventTypes = events.mapNotNull {
                it["type"]?.let { type -> type.toString().trim('"') }
            },
            pendingProviderCount = if (terminal) 0 else 1,
            queuedProviderRequestCount = 0,
            queuedProviderCancellationCount = 0,
            providerRequestsIssued = 1,
            providerRequestsCompleted = completed,
            providerRequestsFailed = failed,
            providerCancellationsIssued = 0,
            lateProviderRequestsAfterStop = 0,
            pendingToolCount = 0,
            queuedToolRequestCount = 0,
            toolRequestsIssued = 0,
            toolRequestsResolved = 0,
            toolExecutionsStarted = 0,
            toolExecutionsEnded = 0,
            lateToolStartsAfterStop = 0,
            hasAgentStart = true,
            hasSettled = terminal,
            hasAbort = hasAbort,
        )
}
