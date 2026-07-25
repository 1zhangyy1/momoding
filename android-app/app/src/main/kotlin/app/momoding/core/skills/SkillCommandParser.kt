package app.momoding.core.skills

private const val SKILL_COMMAND_PREFIX = "/skill:"
private const val MAX_SKILL_INSTRUCTIONS_UTF16_UNITS = 65_536
private val SKILL_COMMAND_NAME_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

sealed interface SkillComposerInput {
    data class Prompt(val text: String) : SkillComposerInput

    data class Skill(
        val originalText: String,
        val name: String,
        val additionalInstructions: String?,
    ) : SkillComposerInput

    data class Invalid(
        val originalText: String,
        val reason: SkillCommandInvalidReason,
    ) : SkillComposerInput
}

enum class SkillCommandInvalidReason {
    NAME_REQUIRED,
    NAME_INVALID,
    INSTRUCTIONS_INVALID,
}

/**
 * Parses only the explicit mobile composer surface. Ordinary text is returned byte-for-byte as a
 * prompt; a recognized Skill command is never rewritten into prompt text.
 */
fun parseSkillComposerInput(text: String): SkillComposerInput {
    if (!text.startsWith(SKILL_COMMAND_PREFIX)) return SkillComposerInput.Prompt(text)
    val tail = text.substring(SKILL_COMMAND_PREFIX.length)
    val nameEnd = tail.indexOfFirst(Char::isWhitespace).let { if (it == -1) tail.length else it }
    val name = tail.substring(0, nameEnd)
    if (name.isEmpty()) {
        return SkillComposerInput.Invalid(text, SkillCommandInvalidReason.NAME_REQUIRED)
    }
    if (name.length > 64 || !SKILL_COMMAND_NAME_PATTERN.matches(name)) {
        return SkillComposerInput.Invalid(text, SkillCommandInvalidReason.NAME_INVALID)
    }
    val instructions = tail.substring(nameEnd).trim().takeIf(String::isNotEmpty)
    if (
        instructions != null &&
        (instructions.length > MAX_SKILL_INSTRUCTIONS_UTF16_UNITS || '\u0000' in instructions)
    ) {
        return SkillComposerInput.Invalid(text, SkillCommandInvalidReason.INSTRUCTIONS_INVALID)
    }
    return SkillComposerInput.Skill(
        originalText = text,
        name = name,
        additionalInstructions = instructions,
    )
}
