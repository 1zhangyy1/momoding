package app.momoding.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTest {
    @Test fun `an English planning line to a Chinese speaker is self-talk`() {
        assertTrue(Narration.selfTalk("I'll set that up, then run it once.", "以后每天早上八点给我一份北京的天气简报"))
    }

    @Test fun `a line in the person's language, or to an English speaker, shows`() {
        assertFalse(Narration.selfTalk("在查周六京沪最早的高铁。", "查一下这周六北京到上海最早的一班高铁"))
        assertFalse(Narration.selfTalk("I'll check the forecast.", "What's the weather like tomorrow?"))
        assertFalse(Narration.selfTalk("G531", "查一下 G531"))
    }
}
