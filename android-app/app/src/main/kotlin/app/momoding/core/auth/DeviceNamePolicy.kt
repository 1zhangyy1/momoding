package app.momoding.core.auth

/** Canonical device-name contract shared with pi-host's canonicalDeviceName(). */
object DeviceNamePolicy {
    fun canonicalize(value: String): String {
        val normalized = value.trimEcmaScriptWhitespace()
        require(normalized.isNotEmpty()) { "deviceName is invalid" }

        var scalarCount = 0
        var index = 0
        while (index < normalized.length) {
            val first = normalized[index]
            val codePoint = when {
                first.isHighSurrogate() -> {
                    require(index + 1 < normalized.length && normalized[index + 1].isLowSurrogate()) {
                        "deviceName is invalid"
                    }
                    Character.toCodePoint(first, normalized[index + 1]).also { index += 2 }
                }
                first.isLowSurrogate() -> throw IllegalArgumentException("deviceName is invalid")
                else -> first.code.also { index += 1 }
            }
            require(codePoint !in 0x00..0x1f && codePoint !in 0x7f..0x9f) {
                "deviceName is invalid"
            }
            scalarCount += 1
            require(scalarCount <= MAX_SCALARS) { "deviceName is invalid" }
        }
        return normalized
    }

    fun requireCanonical(value: String): String = value.also {
        require(canonicalize(value) == value) { "deviceName is not canonical" }
    }

    private fun String.trimEcmaScriptWhitespace(): String {
        var start = 0
        var end = length
        while (start < end && this[start].isEcmaScriptWhitespace()) start += 1
        while (end > start && this[end - 1].isEcmaScriptWhitespace()) end -= 1
        return substring(start, end)
    }

    private fun Char.isEcmaScriptWhitespace(): Boolean = code in setOf(
        0x0009,
        0x000a,
        0x000b,
        0x000c,
        0x000d,
        0x0020,
        0x00a0,
        0x1680,
        0x2028,
        0x2029,
        0x202f,
        0x205f,
        0x3000,
        0xfeff,
    ) || code in 0x2000..0x200a

    private const val MAX_SCALARS = 80
}
