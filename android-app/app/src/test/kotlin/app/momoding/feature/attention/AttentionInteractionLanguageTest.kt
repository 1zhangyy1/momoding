package app.momoding.feature.attention

import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionInteractionLanguageTest {
    @Test
    fun `latest user turn is authoritative for Chinese and English`() {
        assertEquals(
            AttentionInteractionLanguage.ZH_CN,
            attentionInteractionLanguage("请先问我一个问题。", "Ask in English"),
        )
        assertEquals(
            AttentionInteractionLanguage.ENGLISH,
            attentionInteractionLanguage("Ask me one question.", "请用中文提问"),
        )
    }

    @Test
    fun `question is fallback only when latest turn has no identifiable script`() {
        assertEquals(
            AttentionInteractionLanguage.ZH_CN,
            attentionInteractionLanguage("123…？！", "请选择一个方案"),
        )
        assertEquals(
            AttentionInteractionLanguage.ENGLISH,
            attentionInteractionLanguage(null, "Choose one option"),
        )
    }

    @Test
    fun `durable prompt hint survives when responding state hides prompt body`() {
        assertEquals(
            AttentionInteractionLanguage.ZH_CN,
            attentionInteractionLanguage(
                latestUserText = "123…？！",
                promptFallback = null,
                promptLanguageHint = AttentionInteractionLanguage.ZH_CN,
            ),
        )
        assertEquals(
            AttentionInteractionLanguage.ENGLISH,
            attentionInteractionLanguage(
                latestUserText = "かな",
                promptFallback = null,
                promptLanguageHint = AttentionInteractionLanguage.ZH_CN,
            ),
        )
    }

    @Test
    fun `Han and Latin use deterministic Unicode code point counts`() {
        mapOf(
            "请创建AEC事件" to AttentionInteractionLanguage.ZH_CN,
            "please查" to AttentionInteractionLanguage.ENGLISH,
            "中a" to AttentionInteractionLanguage.ENGLISH,
            "中文🙂ABC？！" to AttentionInteractionLanguage.ENGLISH,
        ).forEach { (source, expected) ->
            assertEquals(
                source,
                expected,
                attentionInteractionLanguage(source, "请选择"),
            )
        }
    }

    @Test
    fun `Kana and other letters fail closed while symbols use prompt fallback`() {
        listOf(
            "どちらabc",
            "请选择вариант",
        ).forEach { source ->
            assertEquals(
                source,
                AttentionInteractionLanguage.ENGLISH,
                attentionInteractionLanguage(source, "请选择"),
            )
        }
        assertEquals(
            AttentionInteractionLanguage.ZH_CN,
            attentionInteractionLanguage("123…？！🙂", "请选择一个方案"),
        )
        assertEquals(
            AttentionInteractionLanguage.ZH_CN,
            attentionInteractionLanguage("123\u2160\u2E80🙂", "请选择一个方案"),
        )
    }
}
