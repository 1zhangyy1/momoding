package app.momoding.core.provider

enum class ProviderCapabilityAvailability {
    AVAILABLE,
    UNSUPPORTED,
    UNKNOWN,
    NOT_IMPLEMENTED,
}

data class ProviderCapabilitySnapshot(
    val providerKind: ProviderKind,
    val chatModelId: String,
    val chatStream: ProviderCapabilityAvailability,
    val functionTools: ProviderCapabilityAvailability,
    val imageInput: ProviderCapabilityAvailability,
    val serverWebSearch: ProviderCapabilityAvailability,
    val imageGeneration: ProviderCapabilityAvailability,
    val source: String,
    val checkedAtEpochMillis: Long,
)

data class ProviderRuntimeConfiguration(
    val selection: ProviderSelection,
    val capabilities: ProviderCapabilitySnapshot,
)

class OpenRouterCapabilityProbe(
    private val client: OpenRouterNativeClient,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private var cachedProfileId: String? = null
    private var cachedModelId: String? = null
    private var cachedSnapshot: ProviderCapabilitySnapshot? = null

    suspend fun resolve(credential: ProviderCredential): ProviderCapabilitySnapshot {
        ProviderProfilePolicy.validate(credential)
        cachedSnapshot?.takeIf {
            cachedProfileId == credential.profile.id &&
                cachedModelId == credential.profile.modelId
        }?.let { return it }

        val model = client.listModels(credential.apiKey)
            .singleOrNull { it.id == credential.profile.modelId }
        val snapshot = if (model == null) {
            ProviderCapabilitySnapshot(
                providerKind = credential.profile.kind,
                chatModelId = credential.profile.modelId,
                chatStream = ProviderCapabilityAvailability.UNKNOWN,
                functionTools = ProviderCapabilityAvailability.UNKNOWN,
                imageInput = ProviderCapabilityAvailability.UNKNOWN,
                serverWebSearch = ProviderCapabilityAvailability.AVAILABLE,
                imageGeneration = ProviderCapabilityAvailability.AVAILABLE,
                source = OPENROUTER_MODELS_SOURCE,
                checkedAtEpochMillis = nowEpochMillis(),
            )
        } else {
            ProviderCapabilitySnapshot(
                providerKind = credential.profile.kind,
                chatModelId = model.id,
                chatStream = when {
                    model.outputModalities.isEmpty() -> ProviderCapabilityAvailability.UNKNOWN
                    model.supportsOutputModality("text") -> {
                        ProviderCapabilityAvailability.AVAILABLE
                    }
                    else -> ProviderCapabilityAvailability.UNSUPPORTED
                },
                functionTools = model.availabilityForParameter("tools"),
                imageInput = if (model.supportsInputModality("image")) {
                    ProviderCapabilityAvailability.AVAILABLE
                } else {
                    ProviderCapabilityAvailability.UNSUPPORTED
                },
                serverWebSearch = ProviderCapabilityAvailability.AVAILABLE,
                imageGeneration = ProviderCapabilityAvailability.AVAILABLE,
                source = OPENROUTER_MODELS_SOURCE,
                checkedAtEpochMillis = nowEpochMillis(),
            )
        }
        cachedProfileId = credential.profile.id
        cachedModelId = credential.profile.modelId
        cachedSnapshot = snapshot
        return snapshot
    }

    fun invalidate() {
        cachedProfileId = null
        cachedModelId = null
        cachedSnapshot = null
    }

    private companion object {
        const val OPENROUTER_MODELS_SOURCE = "openrouter_models_api"
    }
}

val OpenRouterModelSummary.supportsFunctionTools: Boolean
    get() = supportedParameters.any { it.equals("tools", ignoreCase = true) }

val OpenRouterModelSummary.supportsImageInput: Boolean
    get() = supportsInputModality("image")

private fun OpenRouterModelSummary.supportsInputModality(modality: String): Boolean =
    inputModalities.any { it.equals(modality, ignoreCase = true) }

private fun OpenRouterModelSummary.supportsOutputModality(modality: String): Boolean =
    outputModalities.any { it.equals(modality, ignoreCase = true) }

private fun OpenRouterModelSummary.availabilityForParameter(
    parameter: String,
): ProviderCapabilityAvailability = if (
    supportedParameters.any { it.equals(parameter, ignoreCase = true) }
) {
    ProviderCapabilityAvailability.AVAILABLE
} else {
    ProviderCapabilityAvailability.UNSUPPORTED
}
