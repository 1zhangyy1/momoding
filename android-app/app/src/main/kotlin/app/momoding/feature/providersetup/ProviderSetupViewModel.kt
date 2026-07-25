package app.momoding.feature.providersetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.provider.OpenRouterChatRequest
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.OpenRouterModelSummary
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderSetupViewModel internal constructor(
    private val loadCredential: suspend () -> ProviderCredential?,
    private val storeCredential: suspend (ProviderCredential) -> Unit,
    private val deleteCredential: suspend () -> Unit,
    private val testCredential: suspend (ProviderCredential) -> Unit,
    private val loadModels: suspend (String?) -> List<OpenRouterModelSummary> = { emptyList() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSetupUiState())
    val state: StateFlow<ProviderSetupUiState> = mutableState.asStateFlow()
    private var savedCredential: ProviderCredential? = null
    private var lastTestedCredential: ProviderCredential? = null
    private var draftProfileId: String = UUID.randomUUID().toString()

    init {
        load()
    }

    fun dispatch(action: ProviderSetupAction) {
        when (action) {
            is ProviderSetupAction.EditModel -> {
                lastTestedCredential = null
                mutableState.value = mutableState.value.copy(
                    modelId = action.value,
                    modelError = null,
                    notice = null,
                    health = editedHealth(),
                    testedChangesNeedSave = false,
                )
            }
            is ProviderSetupAction.EditApiKey -> {
                lastTestedCredential = null
                mutableState.value = mutableState.value.copy(
                    apiKeyInput = action.value,
                    apiKeyError = null,
                    notice = null,
                    health = editedHealth(),
                    testedChangesNeedSave = false,
                )
            }
            ProviderSetupAction.ToggleApiKeyVisibility -> mutableState.value =
                mutableState.value.copy(revealApiKey = !mutableState.value.revealApiKey)
            ProviderSetupAction.TestConnection -> testConnection()
            ProviderSetupAction.Save -> save()
            ProviderSetupAction.RequestDelete -> if (!mutableState.value.busy) {
                mutableState.value = mutableState.value.copy(deleteConfirmationVisible = true)
            }
            ProviderSetupAction.CancelDelete -> mutableState.value =
                mutableState.value.copy(deleteConfirmationVisible = false)
            ProviderSetupAction.ConfirmDelete -> delete()
            ProviderSetupAction.RetryLoad -> load()
            ProviderSetupAction.ClearNotice -> mutableState.value =
                mutableState.value.copy(notice = null)
            ProviderSetupAction.ToggleModelCatalog -> {
                val opening = !mutableState.value.modelCatalogVisible
                mutableState.value = mutableState.value.copy(modelCatalogVisible = opening)
                if (opening && mutableState.value.modelCatalogState == ProviderModelCatalogState.IDLE) {
                    loadModelCatalog()
                }
            }
            is ProviderSetupAction.EditModelSearch -> mutableState.value =
                mutableState.value.copy(modelSearch = action.value)
            is ProviderSetupAction.SelectModel -> selectModel(action.modelId)
            ProviderSetupAction.RefreshModels -> loadModelCatalog()
        }
    }

    private fun selectModel(modelId: String) {
        lastTestedCredential = null
        mutableState.value = mutableState.value.copy(
            modelId = modelId,
            modelError = null,
            notice = null,
            health = editedHealth(),
            testedChangesNeedSave = false,
            modelCatalogVisible = false,
            modelSearch = "",
        )
    }

    private fun loadModelCatalog() {
        if (mutableState.value.modelCatalogState == ProviderModelCatalogState.LOADING) return
        mutableState.value = mutableState.value.copy(
            modelCatalogState = ProviderModelCatalogState.LOADING,
            modelCatalogError = null,
        )
        val key = mutableState.value.apiKeyInput.ifBlank { savedCredential?.apiKey.orEmpty() }
            .takeIf(String::isNotBlank)
        viewModelScope.launch {
            try {
                val models = loadModels(key)
                mutableState.value = mutableState.value.copy(
                    modelCatalogState = ProviderModelCatalogState.READY,
                    modelCatalog = models,
                    modelCatalogError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    modelCatalogState = ProviderModelCatalogState.ERROR,
                    modelCatalogError = "Couldn’t load the OpenRouter model list.",
                )
            }
        }
    }

    private fun load() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(
            loadState = ProviderSetupLoadState.LOADING,
            notice = null,
        )
        viewModelScope.launch {
            try {
                val credential = loadCredential()
                savedCredential = credential
                mutableState.value = if (credential == null) {
                    ProviderSetupUiState(
                        loadState = ProviderSetupLoadState.MISSING,
                        health = ProviderHealth.MISSING,
                    )
                } else {
                    ProviderSetupUiState(
                        loadState = ProviderSetupLoadState.CONFIGURED,
                        health = ProviderHealth.SAVED,
                        savedProfile = credential.profile,
                        modelId = credential.profile.modelId,
                        hasSavedApiKey = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                savedCredential = null
                lastTestedCredential = null
                mutableState.value = ProviderSetupUiState(
                    loadState = ProviderSetupLoadState.ERROR,
                    health = ProviderHealth.UNAVAILABLE,
                    notice = "The saved Provider could not be opened safely.",
                )
            }
        }
    }

    private fun testConnection() {
        val current = mutableState.value
        if (current.busy) return
        val candidate = credentialFrom(current) ?: return
        lastTestedCredential = null
        mutableState.value = current.copy(
            operation = ProviderSetupOperation.TESTING,
            health = ProviderHealth.TESTING,
            notice = null,
            testedChangesNeedSave = false,
        )
        viewModelScope.launch {
            try {
                testCredential(candidate)
                lastTestedCredential = candidate
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    health = ProviderHealth.READY,
                    notice = "Connection successful. Pi can use this model from your phone.",
                    testedChangesNeedSave = candidate != savedCredential,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastTestedCredential = null
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    health = providerHealth(error),
                    notice = providerErrorMessage(error),
                    testedChangesNeedSave = false,
                )
            }
        }
    }

    private fun save() {
        val current = mutableState.value
        if (current.busy) return
        val candidate = credentialFrom(current) ?: return
        mutableState.value = current.copy(
            operation = ProviderSetupOperation.SAVING,
            notice = null,
        )
        viewModelScope.launch {
            try {
                storeCredential(candidate)
                savedCredential = candidate
                val remainsVerified = candidate == lastTestedCredential
                mutableState.value = mutableState.value.copy(
                    loadState = ProviderSetupLoadState.CONFIGURED,
                    operation = ProviderSetupOperation.IDLE,
                    health = if (remainsVerified) ProviderHealth.READY else ProviderHealth.SAVED,
                    savedProfile = candidate.profile,
                    hasSavedApiKey = true,
                    apiKeyInput = "",
                    revealApiKey = false,
                    notice = if (remainsVerified) {
                        "OpenRouter saved and connection verified."
                    } else {
                        "OpenRouter saved securely on this phone. Test the connection when you are ready."
                    },
                    testedChangesNeedSave = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    health = ProviderHealth.UNAVAILABLE,
                    notice = "OpenRouter could not be saved safely.",
                )
            }
        }
    }

    private fun credentialFrom(state: ProviderSetupUiState): ProviderCredential? {
        val modelId = state.modelId.trim()
        val profile = ProviderProfile(
            id = savedCredential?.profile?.id ?: draftProfileId,
            kind = ProviderKind.OPENROUTER,
            baseUrl = ProviderProfilePolicy.OPENROUTER_BASE_URL,
            modelId = modelId,
            displayName = "OpenRouter",
        )
        val modelError = runCatching { ProviderProfilePolicy.validate(profile) }
            .exceptionOrNull()
            ?.let { "Use an OpenRouter model ID like provider/model." }
        val key = state.apiKeyInput.ifBlank { savedCredential?.apiKey.orEmpty() }
        val apiKeyError = if (key.isBlank()) {
            "Enter your OpenRouter API key."
        } else {
            runCatching { ProviderProfilePolicy.validate(ProviderCredential(profile, key)) }
                .exceptionOrNull()
                ?.let { "The API key format is invalid." }
        }
        if (modelError != null || apiKeyError != null) {
            mutableState.value = state.copy(
                modelId = modelId,
                modelError = modelError,
                apiKeyError = apiKeyError,
            )
            return null
        }
        return ProviderCredential(profile, key)
    }

    private fun delete() {
        val current = mutableState.value
        if (current.busy) return
        mutableState.value = current.copy(
            operation = ProviderSetupOperation.DELETING,
            deleteConfirmationVisible = false,
            notice = null,
        )
        viewModelScope.launch {
            try {
                deleteCredential()
                savedCredential = null
                lastTestedCredential = null
                draftProfileId = UUID.randomUUID().toString()
                mutableState.value = ProviderSetupUiState(
                    loadState = ProviderSetupLoadState.MISSING,
                    health = ProviderHealth.MISSING,
                    notice = "OpenRouter was removed from this phone.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    notice = "OpenRouter could not be removed safely.",
                )
            }
        }
    }

    private fun editedHealth(): ProviderHealth = if (savedCredential == null) {
        ProviderHealth.MISSING
    } else {
        ProviderHealth.SAVED
    }

    class Factory(
        vault: ProviderCredentialVault,
        private val client: OpenRouterNativeClient,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModelProvider.Factory {
        private val loadCredential: suspend () -> ProviderCredential? = {
            withContext(ioDispatcher) { vault.load() }
        }
        private val storeCredential: suspend (ProviderCredential) -> Unit = { credential ->
            withContext(ioDispatcher) { vault.store(credential) }
        }
        private val deleteCredential: suspend () -> Unit = {
            withContext(ioDispatcher) {
                vault.deleteFile()
                vault.deleteKey()
            }
        }
        private val testCredential: suspend (ProviderCredential) -> Unit = { credential ->
            client.stream(
                credential = credential,
                request = OpenRouterChatRequest(
                    modelId = credential.profile.modelId,
                    messages = buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", "Reply with exactly OK.")
                            },
                        )
                    },
                    maxTokens = 8,
                ),
                onChunk = {},
            )
            Unit
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProviderSetupViewModel::class.java))
            return ProviderSetupViewModel(
                loadCredential = loadCredential,
                storeCredential = storeCredential,
                deleteCredential = deleteCredential,
                testCredential = testCredential,
                loadModels = client::listModels,
            ) as T
        }
    }
}

private fun providerErrorMessage(error: Throwable): String = when (error) {
    is OpenRouterRequestException -> error.message ?: "OpenRouter request failed."
    is IOException -> "Could not reach OpenRouter. Check this phone’s network and try again."
    is IllegalArgumentException -> "Check the visible Provider fields and try again."
    else -> "The Provider operation could not be completed safely."
}

private fun providerHealth(error: Throwable): ProviderHealth = when (error) {
    is OpenRouterRequestException -> when (error.statusCode) {
        400, 401, 402, 403, 404 -> ProviderHealth.INVALID
        429 -> ProviderHealth.RATE_LIMITED
        else -> ProviderHealth.UNAVAILABLE
    }
    is IllegalArgumentException -> ProviderHealth.INVALID
    else -> ProviderHealth.UNAVAILABLE
}
