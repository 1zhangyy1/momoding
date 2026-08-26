package app.momoding.feature.attention

import app.momoding.core.data.AttentionConfirmationPresentation
import app.momoding.core.data.AttentionPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConfirmationPresentationTest {
    private val original = AttentionPrompt.Confirmation(
        summary = "Android-generated summary",
        details = "Android-generated details",
    )

    @Test
    fun `trusted Android confirmations have bounded Chinese presentation`() {
        val expected = mapOf(
            AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_CALENDARS to
                ("列出你的日历？" to "本次工具调用最多返回 20 个日历的名称和访问状态。"),
            AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_EVENTS to
                ("读取所请求的日历事件？" to "本次工具调用最多返回 10 条事件摘要。"),
        )

        expected.forEach { (presentation, copy) ->
            val presented = presentedConfirmationPrompt(
                prompt = original,
                presentation = presentation,
                language = AttentionInteractionLanguage.ZH_CN,
            )
            assertEquals(copy.first, presented.summary)
            assertEquals(copy.second, presented.details)
        }
    }

    @Test
    fun `trusted Calendar create preserves authoritative title schedule and calendar`() {
        val presented = presentedConfirmationPrompt(
            prompt = AttentionPrompt.Confirmation(
                summary = "Create “AEC 周会”?",
                details = "Create one timed event in Momoding 测试日历.",
            ),
            presentation = AttentionConfirmationPresentation.ANDROID_CALENDAR_CREATE_EVENT,
            language = AttentionInteractionLanguage.ZH_CN,
        )

        assertEquals("创建“AEC 周会”？", presented.summary)
        assertEquals(
            "将在“Momoding 测试日历”日历中创建一条定时日程，并在执行后验证结果。",
            presented.details,
        )
    }

    @Test
    fun `trusted Clipboard set preserves authoritative character count`() {
        val presented = presentedConfirmationPrompt(
            prompt = AttentionPrompt.Confirmation(
                summary = "Copy text to the Android clipboard?",
                details = "Writes 37 characters and verifies the current clipboard without storing the text in approval history.",
            ),
            presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_SET,
            language = AttentionInteractionLanguage.ZH_CN,
        )

        assertEquals("复制文字到 Android 剪贴板？", presented.summary)
        assertEquals(
            "将把 37 个字符写入 Android 剪贴板；审批记录不保存文字正文，写入后会验证当前剪贴板。",
            presented.details,
        )
    }

    @Test
    fun `trusted Clipboard read and clear have exact bounded Chinese presentation`() {
        val read = presentedConfirmationPrompt(
            prompt = AttentionPrompt.Confirmation(
                summary = "Allow Momoding to read clipboard text?",
                details = "Returns ordinary plain text to this Tool call only. Sensitive content is withheld.",
            ),
            presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_GET,
            language = AttentionInteractionLanguage.ZH_CN,
        )
        val clear = presentedConfirmationPrompt(
            prompt = AttentionPrompt.Confirmation(
                summary = "Clear the Android clipboard?",
                details = "Clears the current clipboard and verifies it is empty.",
            ),
            presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_CLEAR,
            language = AttentionInteractionLanguage.ZH_CN,
        )

        assertEquals("读取 Android 剪贴板文字？", read.summary)
        assertEquals("只把普通文字返回给本次工具调用；敏感内容会被隐藏。", read.details)
        assertEquals("清空 Android 剪贴板？", clear.summary)
        assertEquals("将清空当前剪贴板，并验证它已为空。", clear.details)
    }

    @Test
    fun `trusted dynamic presentation fails closed to authoritative prompt`() {
        val malformed = AttentionPrompt.Confirmation(
            summary = "Create an event?",
            details = "Unrecognized Android approval facts.",
        )

        assertSame(
            malformed,
            presentedConfirmationPrompt(
                prompt = malformed,
                presentation = AttentionConfirmationPresentation.ANDROID_CALENDAR_CREATE_EVENT,
                language = AttentionInteractionLanguage.ZH_CN,
            ),
        )
        assertSame(
            malformed,
            presentedConfirmationPrompt(
                prompt = malformed,
                presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_GET,
                language = AttentionInteractionLanguage.ZH_CN,
            ),
        )
        assertSame(
            malformed,
            presentedConfirmationPrompt(
                prompt = malformed,
                presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_CLEAR,
                language = AttentionInteractionLanguage.ZH_CN,
            ),
        )
    }

    @Test
    fun `English and missing provenance preserve authoritative prompt exactly`() {
        assertSame(
            original,
            presentedConfirmationPrompt(
                prompt = original,
                presentation = AttentionConfirmationPresentation.ANDROID_CLIPBOARD_SET,
                language = AttentionInteractionLanguage.ENGLISH,
            ),
        )
        assertSame(
            original,
            presentedConfirmationPrompt(
                prompt = original,
                presentation = null,
                language = AttentionInteractionLanguage.ZH_CN,
            ),
        )
    }
}
