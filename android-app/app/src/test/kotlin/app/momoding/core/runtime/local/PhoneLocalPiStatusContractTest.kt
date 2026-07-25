package app.momoding.core.runtime.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalPiStatusContractTest {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    @Test
    fun strictStatusContractIncludesSkillResourceProjection() {
        val expected = PiNativeOpenRouterScenarioStatus(
            kind = "prompt",
            phase = "settled",
            terminal = true,
            expectationMet = true,
            promptSettled = true,
            stopRequested = false,
            stopCompleted = false,
            events = emptyList(),
            eventTypes = emptyList(),
            pendingProviderCount = 0,
            queuedProviderRequestCount = 0,
            queuedProviderCancellationCount = 0,
            providerRequestsIssued = 1,
            providerRequestsCompleted = 1,
            providerRequestsFailed = 0,
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
            hasSettled = true,
            hasAbort = false,
            resourceSetDigest = "e3b0c44298fc1c149afbf4c8996fb924",
            resourceSetTrusted = true,
            resourceTransitionPending = false,
            resourceUpdateCount = 2,
            skillNames = listOf("review"),
        )

        val encoded = json.encodeToString(expected)
        val decoded = json.decodeFromString<PiNativeOpenRouterScenarioStatus>(encoded)

        assertEquals(expected.resourceSetDigest, decoded.resourceSetDigest)
        assertTrue(decoded.resourceSetTrusted)
        assertFalse(decoded.resourceTransitionPending)
        assertEquals(2, decoded.resourceUpdateCount)
        assertEquals(listOf("review"), decoded.skillNames)
    }
}
