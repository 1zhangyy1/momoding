package app.momoding.feature.newtask

object DraftTitlePolicy {
    fun title(text: String): String {
        val firstLine = text.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?: throw IllegalArgumentException("A blank draft has no title")
        val normalized = firstLine.replace(WHITESPACE, " ")
        val count = normalized.codePointCount(0, normalized.length)
        if (count <= MAX_CODE_POINTS) return normalized
        val end = normalized.offsetByCodePoints(0, MAX_CODE_POINTS)
        return normalized.substring(0, end)
    }

    private const val MAX_CODE_POINTS = 120
    private val WHITESPACE = Regex("\\s+")
}
