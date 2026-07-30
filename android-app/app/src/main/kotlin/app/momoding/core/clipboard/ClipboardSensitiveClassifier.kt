package app.momoding.core.clipboard

enum class ClipboardSensitivity {
    SOURCE_MARKED,
    PRIVATE_KEY,
    API_TOKEN,
    CREDENTIAL,
    OTP_OR_PIN,
    PAYMENT_CARD,
}

object ClipboardSensitiveClassifier {
    fun classify(
        value: String,
        sourceMarkedSensitive: Boolean = false,
    ): ClipboardSensitivity? {
        if (sourceMarkedSensitive) return ClipboardSensitivity.SOURCE_MARKED
        val trimmed = value.trim()
        if (PRIVATE_KEY.containsMatchIn(trimmed)) return ClipboardSensitivity.PRIVATE_KEY
        if (
            COMMON_TOKEN.containsMatchIn(trimmed) ||
            BEARER_TOKEN.containsMatchIn(trimmed) ||
            JWT.containsMatchIn(trimmed)
        ) {
            return ClipboardSensitivity.API_TOKEN
        }
        if (CREDENTIAL_ASSIGNMENT.containsMatchIn(trimmed)) {
            return ClipboardSensitivity.CREDENTIAL
        }
        if (
            OTP_OR_PIN.matches(trimmed) ||
            LABELED_OTP_OR_PIN.containsMatchIn(trimmed)
        ) {
            return ClipboardSensitivity.OTP_OR_PIN
        }
        if (looksLikePaymentCard(trimmed)) return ClipboardSensitivity.PAYMENT_CARD
        return null
    }

    private fun looksLikePaymentCard(value: String): Boolean {
        if (!value.matches(Regex("^[0-9 -]+$"))) return false
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        var sum = 0
        var double = false
        for (index in digits.indices.reversed()) {
            var digit = digits[index].digitToInt()
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return sum % 10 == 0
    }

    private val PRIVATE_KEY = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----",
        RegexOption.IGNORE_CASE,
    )
    private val COMMON_TOKEN = Regex(
        """\b(?:sk-or-v1-[A-Za-z0-9_-]{16,}|sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{16,}|AIza[0-9A-Za-z_-]{20,})\b""",
    )
    private val BEARER_TOKEN = Regex(
        """(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}""",
    )
    private val JWT = Regex(
        """\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""",
    )
    private val CREDENTIAL_ASSIGNMENT = Regex(
        """(?i)\b(?:password|passwd|pwd|secret|api[_ -]?key|access[_ -]?token|refresh[_ -]?token)\s*[:=]\s*\S{4,}""",
    )
    private val OTP_OR_PIN = Regex("""^[0-9]{4,8}$""")
    private val LABELED_OTP_OR_PIN = Regex(
        """(?i)(?:verification(?:\s+code)?|one[- ]?time(?:\s+(?:password|code))?|otp|pin|passcode|验证码|校验码|动态码|安全码)[^\d\r\n]{0,24}(?<!\d)[0-9]{4,8}(?!\d)""",
    )
}
