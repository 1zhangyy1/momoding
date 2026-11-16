package app.momoding.prompt

object MomodingRules {
    private val MINIS_WORD = Regex("""\bMinis\b(?![-/_]|\.[A-Za-z])""")

    /**
     * Upstream's text calls the app "Minis" in prose ("Minis settings", "inside Minis"); the
     * model repeats it to the person. Command names (`minis-config`, `minis-open`) and paths are
     * lowercase or hyphenated and stay as they are.
     */
    fun rebrand(prompt: String): String = MINIS_WORD.replace(prompt, "Momoding")
}
