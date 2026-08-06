package app.momoding.feature.providersetup

import app.momoding.core.provider.ChatProviderKind
import app.momoding.core.provider.OpenRouterImageModelSummary
import app.momoding.core.provider.OpenRouterModelSummary
import app.momoding.core.provider.ProviderProfile

enum class ProviderSetupLoadState {
    LOADING,
    MISSING,
    CONFIGURED,
    ERROR,
}

enum class ProviderSetupOperation {
    IDLE,
    TESTING,
    SAVING,
    DELETING,
}

enum class ProviderHealth {
    UNKNOWN,
    MISSING,
    SAVED,
    TESTING,
    READY,
    INVALID,
    RATE_LIMITED,
    UNAVAILABLE,
}

enum class ProviderModelCatalogState { IDLE, LOADING, READY, ERROR }

enum class CodexSignInState {
    DISCONNECTED,
    STARTING,
    WAITING_FOR_USER,
    CONNECTED,
    ERROR,
}

data class ProviderSetupUiState(
    val loadState: ProviderSetupLoadState = ProviderSetupLoadState.LOADING,
    val operation: ProviderSetupOperation = ProviderSetupOperation.IDLE,
    val health: ProviderHealth = ProviderHealth.UNKNOWN,
    val savedProfile: ProviderProfile? = null,
    val modelId: String = DEFAULT_OPENROUTER_MODEL,
    val apiKeyInput: String = "",
    val revealApiKey: Boolean = false,
    val hasSavedApiKey: Boolean = false,
    val modelError: String? = null,
    val apiKeyError: String? = null,
    val notice: String? = null,
    val deleteConfirmationVisible: Boolean = false,
    val testedChangesNeedSave: Boolean = false,
    val modelCatalogVisible: Boolean = false,
    val modelCatalogState: ProviderModelCatalogState = ProviderModelCatalogState.IDLE,
    val modelCatalog: List<OpenRouterModelSummary> = emptyList(),
    val modelSearch: String = "",
    val modelCatalogError: String? = null,
    val webSearchEnabled: Boolean = true,
    val imageGenerationEnabled: Boolean = false,
    val imageModelId: String? = null,
    val imageModelCatalogVisible: Boolean = false,
    val imageModelCatalogState: ProviderModelCatalogState = ProviderModelCatalogState.IDLE,
    val imageModelCatalog: List<OpenRouterImageModelSummary> = emptyList(),
    val imageModelSearch: String = "",
    val imageModelCatalogError: String? = null,
    val capabilityChangesNeedSave: Boolean = false,
    val activeChatProvider: ChatProviderKind = ChatProviderKind.OPENROUTER,
    val codexModelId: String = DEFAULT_CODEX_MODEL,
    val codexConnected: Boolean = false,
    val codexSignInState: CodexSignInState = CodexSignInState.DISCONNECTED,
    val codexVerificationUri: String? = null,
    val codexUserCode: String? = null,
    val codexPollSeconds: Long? = null,
    val codexNotice: String? = null,
) {
    val configured: Boolean
        get() = when (activeChatProvider) {
            ChatProviderKind.OPENROUTER -> savedProfile != null
            ChatProviderKind.CODEX -> codexConnected
        }

    val busy: Boolean
        get() = operation != ProviderSetupOperation.IDLE ||
            codexSignInState == CodexSignInState.STARTING ||
            codexSignInState == CodexSignInState.WAITING_FOR_USER

    val activeModelId: String
        get() = when (activeChatProvider) {
            ChatProviderKind.OPENROUTER -> savedProfile?.modelId ?: modelId
            ChatProviderKind.CODEX -> codexModelId
        }

    val activeProviderName: String
        get() = when (activeChatProvider) {
            ChatProviderKind.OPENROUTER -> "OpenRouter"
            ChatProviderKind.CODEX -> "Codex"
        }

    val canReturnToTask: Boolean
        get() = configured && !busy && when (activeChatProvider) {
            ChatProviderKind.OPENROUTER ->
                health == ProviderHealth.READY &&
                    !testedChangesNeedSave &&
                    !capabilityChangesNeedSave
            ChatProviderKind.CODEX -> true
        }

    val visibleModels: List<OpenRouterModelSummary>
        get() {
            val query = modelSearch.trim()
            return (if (query.isEmpty()) {
                modelCatalog
            } else {
                modelCatalog.filter { model ->
                    model.id.contains(query, ignoreCase = true) ||
                        model.name.contains(query, ignoreCase = true)
                }
            }).take(20)
        }

    val visibleImageModels: List<OpenRouterImageModelSummary>
        get() {
            val query = imageModelSearch.trim()
            return (if (query.isEmpty()) {
                imageModelCatalog
            } else {
                imageModelCatalog.filter { model ->
                    model.id.contains(query, ignoreCase = true) ||
                        model.name.contains(query, ignoreCase = true)
                }
            }).take(20)
        }
}

sealed interface ProviderSetupAction {
    data object SelectOpenRouter : ProviderSetupAction
    data object SelectCodex : ProviderSetupAction
    data class EditModel(val value: String) : ProviderSetupAction
    data class EditApiKey(val value: String) : ProviderSetupAction
    data object ToggleApiKeyVisibility : ProviderSetupAction
    data object TestConnection : ProviderSetupAction
    data object Save : ProviderSetupAction
    data object RequestDelete : ProviderSetupAction
    data object CancelDelete : ProviderSetupAction
    data object ConfirmDelete : ProviderSetupAction
    data object RetryLoad : ProviderSetupAction
    data object ClearNotice : ProviderSetupAction
    data object ToggleModelCatalog : ProviderSetupAction
    data class EditModelSearch(val value: String) : ProviderSetupAction
    data class SelectModel(val modelId: String) : ProviderSetupAction
    data object RefreshModels : ProviderSetupAction
    data object ToggleWebSearch : ProviderSetupAction
    data object ToggleImageGeneration : ProviderSetupAction
    data object ToggleImageModelCatalog : ProviderSetupAction
    data class EditImageModelSearch(val value: String) : ProviderSetupAction
    data class SelectImageModel(val modelId: String) : ProviderSetupAction
    data object RefreshImageModels : ProviderSetupAction
    data object StartCodexSignIn : ProviderSetupAction
    data object CancelCodexSignIn : ProviderSetupAction
    data object DisconnectCodex : ProviderSetupAction
}

const val DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v4-pro"
const val DEFAULT_CODEX_MODEL = "gpt-5.4"
