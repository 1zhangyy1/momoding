package app.momoding.chat

import app.momoding.chat.ErrorWords.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** momoding: provider errors map to one plain sentence; unknown ones to a generic one. */
class ErrorWordsTest {
    private fun k(s: String) = ErrorWords.classify(s)

    @Test fun key() { assertEquals(Kind.KEY, k("HTTP 401: Invalid API key provided")) }
    @Test fun balance() { assertEquals(Kind.BALANCE, k("402 Payment Required: insufficient credits")) }
    @Test fun rate() { assertEquals(Kind.RATE, k("429 Too Many Requests")) }
    @Test fun model() { assertEquals(Kind.MODEL, k("Qwen: Qwen3.8 Max (0902): [404] No allowed providers are available")) }
    @Test fun timeout() { assertEquals(Kind.TIMEOUT, k("Request timed out after 120s")) }
    @Test fun network() { assertEquals(Kind.NETWORK, k("java.net.UnknownHostException: Unable to resolve host openrouter.ai")) }
    @Test fun other() { assertEquals(Kind.OTHER, k("something odd happened")) }
}
