package app.momoding.core.runtime.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.debug.PhoneLocalPiSmokeRunner
import app.momoding.debug.PhoneLocalPiScenarioRunner
import app.momoding.debug.PhoneLocalPiRoomUiRunner
import app.momoding.feature.taskdetail.TaskDetailRunState
import app.momoding.feature.taskdetail.ToolActivityState
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillDocumentParseException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLocalPiRuntimeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun bootsInspectsAndClosesRealPiAgentHarnessTwentyTimes() = runBlocking {
        val results = PhoneLocalPiSmokeRunner(context).use { runner ->
            runner.run(iterations = 20)
        }

        assertEquals(20, results.size)
        for (result in results) {
            assertEquals("0.80.6", result.manifest.piVersion)
            assertEquals("0.80.6", result.bootstrap.piVersion)
            assertEquals("AgentHarness", result.bootstrap.runtime)
            assertEquals("phone-local-bootstrap", result.bootstrap.modelId)
            assertTrue(result.bootstrap.ok)
            assertTrue(result.bootstrap.capabilities.getValue("secureRandom"))
            assertTrue(result.bundleSizeBytes > 100_000)
            assertTrue(result.bundleSizeBytes < 1_000_000)
        }
    }

    @Test
    fun runtimeSupportsPromiseMicrotasksWithoutClaimingTimers() = runBlocking {
        val result = PhoneLocalPiSmokeRunner(context).use { runner ->
            runner.run(iterations = 1).single()
        }

        assertTrue(result.bootstrap.capabilities.getValue("queueMicrotask"))
        assertEquals(false, result.bootstrap.capabilities.getValue("timers"))
    }

    @Test
    fun fakeProviderNativeEventsMockToolErrorsAndStopRunInsideQuickJs() = runBlocking {
        val proof = PhoneLocalPiScenarioRunner(context).use { runner ->
            runner.runAll()
        }

        assertTrue(proof.allPassed)
        assertEquals(4, proof.scenarios.size)
        assertTrue(proof.nativeEventCount > 20)

        val success = proof.scenarios.single { it.kind == "tool_success" }
        assertEquals(1, success.toolRequestsResolved)
        assertEquals(1, success.toolExecutionsStarted)
        assertEquals(1, success.toolExecutionsEnded)
        assertEquals(0, success.toolErrors)
        assertTrue(success.eventTypes.contains("message_update"))

        val toolError = proof.scenarios.single { it.kind == "tool_error" }
        assertEquals(1, toolError.toolRequestsRejected)
        assertEquals(1, toolError.toolErrors)

        val providerError = proof.scenarios.single { it.kind == "provider_error" }
        assertEquals(0, providerError.toolRequestsIssued)
        assertTrue(providerError.hasSettled)

        val stopped = proof.scenarios.single { it.kind == "stop_before_tool" }
        assertTrue(stopped.stopCompleted)
        assertTrue(stopped.hasAbort)
        assertEquals(0, stopped.toolRequestsIssued)
        assertEquals(0, stopped.toolExecutionsStarted)
        assertEquals(0, stopped.lateToolStartsAfterStop)
    }

    @Test
    fun nativePiEventsRoundTripThroughRoomAndExistingUiReducer() = runBlocking {
        val scenarios = PhoneLocalPiScenarioRunner(context).use { runner ->
            runner.runAll()
        }
        val proof = PhoneLocalPiRoomUiRunner(context).verify(scenarios)

        assertEquals(2, proof.projectedTaskCount)
        assertTrue(proof.rawEventCount > 10)
        assertTrue(proof.nativePayloadsStructurallyEqual)
        assertEquals(ToolActivityState.SUCCESS, proof.successToolState)
        assertEquals(ToolActivityState.FAILURE, proof.errorToolState)
        assertEquals(TaskDetailRunState.SETTLED, proof.successRunState)
        assertEquals(TaskDetailRunState.SETTLED, proof.errorRunState)
    }

    @Test
    fun androidBridgeUsesRealPiSkillLoaderAndClearsMailboxWithoutProvider() = runBlocking {
        val runtime = skillParserRuntime()
        try {
            val valid = runtime.parseSkillDocument(VALID_SKILL)
            assertEquals("review-checklist", valid.resource.name)
            assertEquals("Review work safely.", valid.resource.description)
            assertEquals(SkillAvailability.AVAILABLE, valid.availability)
            assertEquals(null, valid.diagnosticCode)

            val relative = runtime.parseSkillDocument(RELATIVE_SKILL)
            assertEquals(SkillAvailability.UNAVAILABLE, relative.availability)
            assertEquals("RELATIVE_DEPENDENCY_UNSUPPORTED", relative.diagnosticCode)

            val malformed = runCatching { runtime.parseSkillDocument(MALFORMED_SKILL) }
                .exceptionOrNull()
            assertTrue(malformed is SkillDocumentParseException)

            // A valid parse after a failed parse proves the terminal mailbox was cleared.
            assertEquals(
                SkillAvailability.AVAILABLE,
                runtime.parseSkillDocument(VALID_SKILL).availability,
            )
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun parserBusyFenceBlocksAgentAndCloseThenCancellationClearsMailbox() = runBlocking {
        val runtime = skillParserRuntime(pollMillis = 200L)
        try {
            val parsing = async { runtime.parseSkillDocument(VALID_SKILL) }
            delay(30L)

            val agentError = runCatching { runtime.runPrompt("Must not reach Provider") }
                .exceptionOrNull()
            assertTrue(agentError?.message?.contains("PI_MOBILE_SKILL_PARSE_IN_PROGRESS") == true)
            val closeError = runCatching { runtime.close() }.exceptionOrNull()
            assertTrue(closeError?.message?.contains("PI_MOBILE_SKILL_PARSE_IN_PROGRESS") == true)

            parsing.cancelAndJoin()
            assertEquals(
                SkillAvailability.AVAILABLE,
                runtime.parseSkillDocument(VALID_SKILL).availability,
            )
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun shutdownWaitsForParserCleanupAndRejectsNewWork() = runBlocking {
        val runtime = skillParserRuntime(pollMillis = 100L)
        val parsing = async { runtime.parseSkillDocument(VALID_SKILL) }
        delay(20L)
        val shuttingDown = async { runtime.shutdown() }

        assertEquals(SkillAvailability.AVAILABLE, parsing.await().availability)
        shuttingDown.await()
        val closedError = runCatching { runtime.parseSkillDocument(VALID_SKILL) }
            .exceptionOrNull()
        assertTrue(closedError != null)
    }

    private fun skillParserRuntime(pollMillis: Long = 1L): PhoneLocalPiOpenRouterRuntime {
        val ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-pi-skill-test")
        }.asCoroutineDispatcher()
        return PhoneLocalPiOpenRouterRuntime(
            context = context,
            credentialVault = ProviderCredentialVault.create(context),
            client = OpenRouterNativeClient(),
            ownerDispatcher = ownerDispatcher,
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            skillParsePollMillis = pollMillis,
        )
    }

    companion object {
        private val VALID_SKILL = """
            ---
            name: review-checklist
            description: Review work safely.
            ---

            Review against explicit acceptance criteria.
        """.trimIndent()
        private val RELATIVE_SKILL = """
            ---
            name: relative-review
            description: Review with a local reference.
            ---

            Read [the checklist](references/checklist.md).
        """.trimIndent()
        private val MALFORMED_SKILL = """
            ---
            name: missing-description
            ---

            This document is invalid.
        """.trimIndent()
    }
}
