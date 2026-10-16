package app.momoding.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: turn-limited prompt addenda count down per session and follow a draft's promotion. */
class SessionAddendaTest {
    @Test
    fun `an addendum lasts exactly its turns`() {
        SessionAddenda.add("s1", "wake", "routine turn", turns = 1)
        assertEquals("routine turn", SessionAddenda.forPrompt("s1"))
        SessionAddenda.onTurnFinished("s1")
        assertNull(SessionAddenda.forPrompt("s1"))
    }

    @Test
    fun `the same tag replaces, other sessions are untouched`() {
        SessionAddenda.add("s2", "wake", "old", turns = 2)
        SessionAddenda.add("s2", "wake", "new", turns = 2)
        assertEquals("new", SessionAddenda.forPrompt("s2"))
        assertNull(SessionAddenda.forPrompt("other"))
        SessionAddenda.remove("s2", "wake")
    }

    @Test
    fun `a draft's addenda move to its real id`() {
        SessionAddenda.add("__new__d", "wake", "carried", turns = 1)
        SessionAddenda.rebind("__new__d", "real")
        assertNull(SessionAddenda.forPrompt("__new__d"))
        assertTrue(SessionAddenda.has("real", "wake"))
        SessionAddenda.onTurnFinished("real")
    }
}
