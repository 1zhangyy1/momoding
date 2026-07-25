package app.momoding.feature.providersetup

import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.OpenRouterModelSummary

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
) {
    val configured: Boolean
        get() = loadState == ProviderSetupLoadState.CONFIGURED && savedProfile != null

    val busy: Boolean
        get() = operation != ProviderSetupOperation.IDLE

    val canReturnToTask: Boolean
        get() = configured &&
            health == ProviderHealth.READY &&
            !testedChangesNeedSave &&
            !busy

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
}

sealed interface ProviderSetupAction {
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
}

const val DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v4-pro"
