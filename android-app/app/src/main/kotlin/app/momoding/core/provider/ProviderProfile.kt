package app.momoding.core.provider

enum class ProviderKind(
    val wireValue: String,
) {
    OPENROUTER("openrouter");

    companion object {
        fun fromWireValue(value: String): ProviderKind =
            entries.singleOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Provider kind is unsupported")
    }
}

data class ProviderProfile(
    val id: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val modelId: String,
    val displayName: String,
)

data class ProviderCredential(
    val profile: ProviderProfile,
    val apiKey: String,
) {
    override fun toString(): String =
        "ProviderCredential(profile=$profile, apiKey=[REDACTED])"
}

object ProviderProfilePolicy {
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    fun validate(profile: ProviderProfile) {
        require(CANONICAL_UUID.matches(profile.id)) { "Provider id is invalid" }
        require(profile.kind == ProviderKind.OPENROUTER) { "Provider kind is unsupported" }
        require(profile.baseUrl == OPENROUTER_BASE_URL) {
            "OpenRouter base URL must use the fixed official endpoint"
        }
        require(MODEL_ID.matches(profile.modelId)) { "OpenRouter model id is invalid" }
        require(profile.displayName == profile.displayName.trim()) {
            "Provider display name must be trimmed"
        }
        require(profile.displayName.length in 1..MAX_DISPLAY_NAME_CHARS) {
            "Provider display name length is invalid"
        }
        require(profile.displayName.none(Char::isISOControl)) {
            "Provider display name contains control characters"
        }
    }

    fun validate(credential: ProviderCredential) {
        validate(credential.profile)
        require(credential.apiKey.length in MIN_API_KEY_CHARS..MAX_API_KEY_CHARS) {
            "Provider API key length is invalid"
        }
        require(credential.apiKey.all { it.code in VISIBLE_ASCII }) {
            "Provider API key contains invalid characters"
        }
    }

    private const val MIN_API_KEY_CHARS = 20
    private const val MAX_API_KEY_CHARS = 512
    private const val MAX_DISPLAY_NAME_CHARS = 80
    private val VISIBLE_ASCII = 0x21..0x7e
    private val CANONICAL_UUID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val MODEL_ID = Regex(
        "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    )
}
