package app.momoding.core.runtime.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.debug.PhoneLocalPiNativeProviderRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLocalPiNativeProviderInstrumentedTest {
    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun realPiConsumesNativeProviderTextToolErrorAndStopInsideAndroid() = runBlocking {
        val proof = PhoneLocalPiNativeProviderRunner(context).use { runner ->
            runner.runAll()
        }

        assertTrue(proof.allPassed)
        assertEquals(4, proof.scenarios.size)
        assertEquals(1, proof.cancellationCount)
        assertEquals(5, proof.providerRequestJson.size)
        assertTrue(proof.providerRequestJson.none { it.contains("apiKey", ignoreCase = true) })
        assertTrue(
            proof.providerRequestJson.none {
                it.contains("authorization", ignoreCase = true)
            },
        )

        val text = proof.scenarios.single { it.kind == "text" }
        assertEquals("Hello from Android native Provider", text.finalText)
        assertEquals(1, text.providerRequestsCompleted)

        val tool = proof.scenarios.single { it.kind == "tool" }
        assertEquals(2, tool.providerRequestsCompleted)
        assertEquals(1, tool.toolRequestsResolved)
        assertEquals(1, tool.toolExecutionsStarted)
        assertEquals(1, tool.toolExecutionsEnded)

        val providerError = proof.scenarios.single { it.kind == "provider_error" }
        assertEquals(1, providerError.providerRequestsFailed)
        assertTrue(providerError.hasSettled)

        val stopped = proof.scenarios.single { it.kind == "stop" }
        assertTrue(stopped.stopCompleted)
        assertTrue(stopped.hasAbort)
        assertEquals(1, stopped.providerCancellationsIssued)
        assertEquals(0, stopped.lateProviderRequestsAfterStop)
        assertEquals(0, stopped.lateToolStartsAfterStop)
        assertFalse(stopped.eventTypes.contains("tool_execution_start"))
    }
}
