package app.momoding.feature.attention

import app.momoding.core.data.AttentionPrompt

enum class AttentionInteractionLanguage {
    ZH_CN,
    ENGLISH,
}

/**
 * Product chrome follows the latest user turn. The prompt is only a fallback when that turn has
 * no identifiable script. Han and Latin are counted by Unicode code point; unsupported letters
 * deliberately fall back to English so fixed controls never guess from model-authored content.
 */
internal fun attentionInteractionLanguage(
    latestUserText: String?,
    promptFallback: String?,
    promptLanguageHint: AttentionInteractionLanguage? = null,
): AttentionInteractionLanguage = when (classifyInteractionScript(latestUserText)) {
    InteractionScript.ZH_CN -> AttentionInteractionLanguage.ZH_CN
    InteractionScript.ENGLISH -> AttentionInteractionLanguage.ENGLISH
    InteractionScript.UNSUPPORTED -> AttentionInteractionLanguage.ENGLISH
    InteractionScript.INDETERMINATE -> when (classifyInteractionScript(promptFallback)) {
        InteractionScript.ZH_CN -> AttentionInteractionLanguage.ZH_CN
        InteractionScript.ENGLISH,
        InteractionScript.UNSUPPORTED,
        -> AttentionInteractionLanguage.ENGLISH
        InteractionScript.INDETERMINATE -> promptLanguageHint ?: AttentionInteractionLanguage.ENGLISH
    }
}

/** Keeps prompt language available after the visible prompt body is hidden. */
internal fun attentionPromptLanguageHint(prompt: AttentionPrompt): AttentionInteractionLanguage? {
    val fallback = when (prompt) {
        is AttentionPrompt.Question -> prompt.question
        is AttentionPrompt.Confirmation -> prompt.summary
        is AttentionPrompt.ContentRead,
        is AttentionPrompt.FileChanges,
        -> return null
    }
    return when (classifyInteractionScript(fallback)) {
        InteractionScript.ZH_CN -> AttentionInteractionLanguage.ZH_CN
        InteractionScript.ENGLISH,
        InteractionScript.UNSUPPORTED,
        InteractionScript.INDETERMINATE,
        -> AttentionInteractionLanguage.ENGLISH
    }
}

private enum class InteractionScript { ZH_CN, ENGLISH, UNSUPPORTED, INDETERMINATE }

private fun classifyInteractionScript(text: String?): InteractionScript {
    if (text.isNullOrBlank()) return InteractionScript.INDETERMINATE
    var hanCount = 0
    var latinCount = 0
    var hasOtherLetters = false
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        when {
            codePoint.isHanCodePoint() -> hanCount += 1
            codePoint.isLatinCodePoint() -> latinCount += 1
            Character.isLetter(codePoint) -> hasOtherLetters = true
        }
        index += Character.charCount(codePoint)
    }
    return when {
        hasOtherLetters -> InteractionScript.UNSUPPORTED
        hanCount == 0 && latinCount == 0 -> InteractionScript.INDETERMINATE
        hanCount > latinCount -> InteractionScript.ZH_CN
        else -> InteractionScript.ENGLISH
    }
}

private fun Int.isHanCodePoint(): Boolean =
    (Character.isLetter(this) && Character.UnicodeScript.of(this) == Character.UnicodeScript.HAN) ||
        this in 0x2EBF0..0x2EE5F ||
        this in 0x30000..0x323AF

private fun Int.isLatinCodePoint(): Boolean =
    Character.isLetter(this) && Character.UnicodeScript.of(this) == Character.UnicodeScript.LATIN
