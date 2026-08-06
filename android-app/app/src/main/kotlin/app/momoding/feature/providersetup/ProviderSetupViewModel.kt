package app.momoding.feature.providersetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.provider.ActiveChatProviderSelection
import app.momoding.core.provider.ActiveChatProviderStore
import app.momoding.core.provider.ChatProviderKind
import app.momoding.core.provider.CodexDeviceAuthorization
import app.momoding.core.provider.CodexDeviceAuthorizationCoordinator
import app.momoding.core.provider.CodexOAuthCredential
import app.momoding.core.provider.CodexOAuthCredentialManager
import app.momoding.core.provider.CodexOAuthGateway
import app.momoding.core.provider.CodexOAuthProtocolException
import app.momoding.core.provider.OpenRouterChatRequest
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.OpenRouterImageGateway
import app.momoding.core.provider.OpenRouterImageModelSummary
import app.momoding.core.provider.OpenRouterModelSummary
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderKind
import app.momoding.core.provider.ProviderProfile
import app.momoding.core.provider.ProviderProfilePolicy
import app.momoding.core.provider.ProviderSelection
import app.momoding.core.provider.ProviderSelectionStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val loadImageModels: suspend (String) -> List<OpenRouterImageModelSummary> = { emptyList() },
    private val loadSelection: suspend (ProviderProfile) -> ProviderSelection =
        { ProviderSelection.defaults(it) },
    private val storeSelection: suspend (ProviderSelection) -> Unit = {},
    private val deleteSelection: suspend () -> Unit = {},
    private val loadActiveChatProvider: suspend (String) -> ActiveChatProviderSelection = {
        ActiveChatProviderSelection(ChatProviderKind.OPENROUTER, it)
    },
    private val storeActiveChatProvider: suspend (ActiveChatProviderSelection) -> Unit = {},
    private val hasCodexCredential: suspend () -> Boolean = { false },
    private val startCodexAuthorization: suspend () -> CodexDeviceAuthorization = {
        error("CODEX_OAUTH_NOT_AVAILABLE")
    },
    private val awaitCodexCredential: suspend (
        CodexDeviceAuthorization,
        (Long) -> Unit,
    ) -> CodexOAuthCredential = { _, _ -> error("CODEX_OAUTH_NOT_AVAILABLE") },
    private val storeCodexCredential: suspend (CodexOAuthCredential) -> Unit = {},
    private val logoutCodex: suspend () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSetupUiState())
    val state: StateFlow<ProviderSetupUiState> = mutableState.asStateFlow()
    private var savedCredential: ProviderCredential? = null
    private var lastTestedCredential: ProviderCredential? = null
    private var draftProfileId: String = UUID.randomUUID().toString()
    private var savedSelection: ProviderSelection? = null
    private var codexSignInJob: Job? = null

    init {
        load()
    }

    fun dispatch(action: ProviderSetupAction) {
        when (action) {
            ProviderSetupAction.SelectOpenRouter -> selectChatProvider(ChatProviderKind.OPENROUTER)
            ProviderSetupAction.SelectCodex -> selectChatProvider(ChatProviderKind.CODEX)
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
            ProviderSetupAction.ToggleWebSearch -> if (!mutableState.value.busy) {
                mutableState.value = mutableState.value.copy(
                    webSearchEnabled = !mutableState.value.webSearchEnabled,
                    capabilityChangesNeedSave = true,
                    notice = null,
                )
            }
            ProviderSetupAction.ToggleImageGeneration -> toggleImageGeneration()
            ProviderSetupAction.ToggleImageModelCatalog -> {
                val opening = !mutableState.value.imageModelCatalogVisible
                mutableState.value = mutableState.value.copy(imageModelCatalogVisible = opening)
                if (opening && mutableState.value.imageModelCatalogState == ProviderModelCatalogState.IDLE) {
                    loadImageModelCatalog()
                }
            }
            is ProviderSetupAction.EditImageModelSearch -> mutableState.value =
                mutableState.value.copy(imageModelSearch = action.value)
            is ProviderSetupAction.SelectImageModel -> selectImageModel(action.modelId)
            ProviderSetupAction.RefreshImageModels -> loadImageModelCatalog()
            ProviderSetupAction.StartCodexSignIn -> startCodexSignIn()
            ProviderSetupAction.CancelCodexSignIn -> cancelCodexSignIn()
            ProviderSetupAction.DisconnectCodex -> disconnectCodex()
        }
    }

    private fun selectChatProvider(kind: ChatProviderKind) {
        val current = mutableState.value
        if (current.busy || current.activeChatProvider == kind) return
        val selection = ActiveChatProviderSelection(
            kind = kind,
            modelId = when (kind) {
                ChatProviderKind.OPENROUTER -> current.savedProfile?.modelId ?: current.modelId
                ChatProviderKind.CODEX -> current.codexModelId
            },
        )
        mutableState.value = current.copy(operation = ProviderSetupOperation.SAVING)
        viewModelScope.launch {
            try {
                storeActiveChatProvider(selection)
                val configured = when (kind) {
                    ChatProviderKind.OPENROUTER -> savedCredential != null
                    ChatProviderKind.CODEX -> mutableState.value.codexConnected
                }
                mutableState.value = mutableState.value.copy(
                    activeChatProvider = kind,
                    loadState = if (configured) {
                        ProviderSetupLoadState.CONFIGURED
                    } else {
                        ProviderSetupLoadState.MISSING
                    },
                    operation = ProviderSetupOperation.IDLE,
                    health = when (kind) {
                        ChatProviderKind.OPENROUTER -> if (savedCredential == null) {
                            ProviderHealth.MISSING
                        } else {
                            ProviderHealth.SAVED
                        }
                        ChatProviderKind.CODEX -> if (mutableState.value.codexConnected) {
                            ProviderHealth.READY
                        } else {
                            ProviderHealth.MISSING
                        }
                    },
                    notice = null,
                    codexNotice = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    notice = "The active Provider could not be changed safely.",
                )
            }
        }
    }

    private fun startCodexSignIn() {
        val current = mutableState.value
        if (
            current.activeChatProvider != ChatProviderKind.CODEX ||
            current.codexConnected ||
            codexSignInJob?.isActive == true ||
            current.busy
        ) return
        mutableState.value = current.copy(
            codexSignInState = CodexSignInState.STARTING,
            codexVerificationUri = null,
            codexUserCode = null,
            codexPollSeconds = null,
            codexNotice = null,
        )
        codexSignInJob = viewModelScope.launch {
            try {
                val authorization = startCodexAuthorization()
                mutableState.value = mutableState.value.copy(
                    codexSignInState = CodexSignInState.WAITING_FOR_USER,
                    codexVerificationUri = authorization.verificationUri,
                    codexUserCode = authorization.userCode,
                    codexPollSeconds = authorization.intervalSeconds,
                    codexNotice = "Open ChatGPT, enter the code, then return here.",
                )
                val credential = awaitCodexCredential(authorization) { sleepMillis ->
                    mutableState.value = mutableState.value.copy(
                        codexPollSeconds = (sleepMillis / 1_000L).coerceAtLeast(1L),
                    )
                }
                storeCodexCredential(credential)
                val selection = ActiveChatProviderSelection(
                    ChatProviderKind.CODEX,
                    mutableState.value.codexModelId,
                )
                storeActiveChatProvider(selection)
                mutableState.value = mutableState.value.copy(
                    loadState = ProviderSetupLoadState.CONFIGURED,
                    health = ProviderHealth.READY,
                    codexConnected = true,
                    codexSignInState = CodexSignInState.CONNECTED,
                    codexVerificationUri = null,
                    codexUserCode = null,
                    codexPollSeconds = null,
                    codexNotice = "Codex is connected. New tasks can use your ChatGPT account.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    codexSignInState = CodexSignInState.ERROR,
                    codexVerificationUri = null,
                    codexUserCode = null,
                    codexPollSeconds = null,
                    codexNotice = codexSignInErrorMessage(error),
                )
            } finally {
                codexSignInJob = null
            }
        }
    }

    private fun cancelCodexSignIn() {
        codexSignInJob?.cancel()
        codexSignInJob = null
        mutableState.value = mutableState.value.copy(
            codexSignInState = if (mutableState.value.codexConnected) {
                CodexSignInState.CONNECTED
            } else {
                CodexSignInState.DISCONNECTED
            },
            codexVerificationUri = null,
            codexUserCode = null,
            codexPollSeconds = null,
            codexNotice = "Codex sign-in was cancelled.",
        )
    }

    private fun disconnectCodex() {
        val current = mutableState.value
        if (!current.codexConnected || current.busy) return
        mutableState.value = current.copy(operation = ProviderSetupOperation.DELETING)
        viewModelScope.launch {
            try {
                logoutCodex()
                mutableState.value = mutableState.value.copy(
                    loadState = if (mutableState.value.activeChatProvider == ChatProviderKind.CODEX) {
                        ProviderSetupLoadState.MISSING
                    } else {
                        mutableState.value.loadState
                    },
                    operation = ProviderSetupOperation.IDLE,
                    health = if (mutableState.value.activeChatProvider == ChatProviderKind.CODEX) {
                        ProviderHealth.MISSING
                    } else {
                        mutableState.value.health
                    },
                    codexConnected = false,
                    codexSignInState = CodexSignInState.DISCONNECTED,
                    codexNotice = "Codex was disconnected from this phone.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    operation = ProviderSetupOperation.IDLE,
                    codexNotice = "Codex could not be disconnected safely.",
                )
            }
        }
    }

    private fun toggleImageGeneration() {
        val current = mutableState.value
        if (current.busy) return
        if (!current.imageGenerationEnabled && current.imageModelId == null) {
            mutableState.value = current.copy(
                imageModelCatalogVisible = true,
                notice = "Choose an image model before enabling image generation.",
            )
            if (current.imageModelCatalogState == ProviderModelCatalogState.IDLE) {
                loadImageModelCatalog()
            }
            return
        }
        mutableState.value = current.copy(
            imageGenerationEnabled = !current.imageGenerationEnabled,
            capabilityChangesNeedSave = true,
            notice = null,
        )
    }

    private fun selectImageModel(modelId: String) {
        if (mutableState.value.imageModelCatalog.none { it.id == modelId }) return
        mutableState.value = mutableState.value.copy(
            imageModelId = modelId,
            imageGenerationEnabled = true,
            imageModelCatalogVisible = false,
            imageModelSearch = "",
            capabilityChangesNeedSave = true,
            notice = null,
        )
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

    private fun loadImageModelCatalog() {
        if (mutableState.value.imageModelCatalogState == ProviderModelCatalogState.LOADING) return
        val key = mutableState.value.apiKeyInput.ifBlank { savedCredential?.apiKey.orEmpty() }
        if (key.isBlank()) {
            mutableState.value = mutableState.value.copy(
                imageModelCatalogState = ProviderModelCatalogState.ERROR,
                imageModelCatalogError = "Enter or save an OpenRouter API key first.",
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            imageModelCatalogState = ProviderModelCatalogState.LOADING,
            imageModelCatalogError = null,
        )
        viewModelScope.launch {
            try {
                val models = loadImageModels(key)
                mutableState.value = mutableState.value.copy(
                    imageModelCatalogState = ProviderModelCatalogState.READY,
                    imageModelCatalog = models,
                    imageModelCatalogError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    imageModelCatalogState = ProviderModelCatalogState.ERROR,
                    imageModelCatalogError = "Couldn’t load the OpenRouter image model list.",
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
                val selection = credential?.let { loadSelection(it.profile) }
                if (selection != null) {
                    savedSelection = selection
                }
                val openRouterModelId = credential?.profile?.modelId ?: DEFAULT_OPENROUTER_MODEL
                val active = loadActiveChatProvider(openRouterModelId)
                val codexConnected = hasCodexCredential()
                val configured = when (active.kind) {
                    ChatProviderKind.OPENROUTER -> credential != null
                    ChatProviderKind.CODEX -> codexConnected
                }
                mutableState.value = ProviderSetupUiState(
                    loadState = if (configured) {
                        ProviderSetupLoadState.CONFIGURED
                    } else {
                        ProviderSetupLoadState.MISSING
                    },
                    health = when (active.kind) {
                        ChatProviderKind.OPENROUTER -> if (credential == null) {
                            ProviderHealth.MISSING
                        } else {
                            ProviderHealth.SAVED
                        }
                        ChatProviderKind.CODEX -> if (codexConnected) {
                            ProviderHealth.READY
                        } else {
                            ProviderHealth.MISSING
                        }
                    },
                    savedProfile = credential?.profile,
                    modelId = openRouterModelId,
                    hasSavedApiKey = credential != null,
                    webSearchEnabled = selection?.webSearchEnabled ?: true,
                    imageGenerationEnabled = selection?.imageGenerationEnabled ?: false,
                    imageModelId = selection?.imageModelId,
                    activeChatProvider = active.kind,
                    codexModelId = if (active.kind == ChatProviderKind.CODEX) {
                        active.modelId
                    } else {
                        DEFAULT_CODEX_MODEL
                    },
                    codexConnected = codexConnected,
                    codexSignInState = if (codexConnected) {
                        CodexSignInState.CONNECTED
                    } else {
                        CodexSignInState.DISCONNECTED
                    },
                )
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
                val selection = ProviderSelection.defaults(candidate.profile).copy(
                    imageModelId = mutableState.value.imageModelId,
                    webSearchEnabled = mutableState.value.webSearchEnabled,
                    imageGenerationEnabled = mutableState.value.imageGenerationEnabled,
                )
                storeSelection(selection)
                if (mutableState.value.activeChatProvider == ChatProviderKind.OPENROUTER) {
                    storeActiveChatProvider(
                        ActiveChatProviderSelection(
                            ChatProviderKind.OPENROUTER,
                            candidate.profile.modelId,
                        ),
                    )
                }
                savedSelection = selection
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
                    capabilityChangesNeedSave = false,
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
                deleteSelection()
                savedCredential = null
                savedSelection = null
                lastTestedCredential = null
                draftProfileId = UUID.randomUUID().toString()
                mutableState.value = ProviderSetupUiState(
                    loadState = ProviderSetupLoadState.MISSING,
                    health = ProviderHealth.MISSING,
                    notice = "OpenRouter was removed from this phone.",
                    activeChatProvider = mutableState.value.activeChatProvider,
                    codexModelId = mutableState.value.codexModelId,
                    codexConnected = mutableState.value.codexConnected,
                    codexSignInState = mutableState.value.codexSignInState,
                    codexNotice = mutableState.value.codexNotice,
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
        selectionStore: ProviderSelectionStore,
        private val activeChatProviderStore: ActiveChatProviderStore,
        private val codexGateway: CodexOAuthGateway,
        private val codexCredentialManager: CodexOAuthCredentialManager,
        private val client: OpenRouterNativeClient,
        private val imageGateway: OpenRouterImageGateway,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModelProvider.Factory {
        private val codexCoordinator = CodexDeviceAuthorizationCoordinator(codexGateway)
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
        private val loadSelection: suspend (ProviderProfile) -> ProviderSelection = { profile ->
            withContext(ioDispatcher) { selectionStore.load(profile) }
        }
        private val storeSelection: suspend (ProviderSelection) -> Unit = { selection ->
            withContext(ioDispatcher) { selectionStore.store(selection) }
        }
        private val deleteSelection: suspend () -> Unit = {
            withContext(ioDispatcher) { selectionStore.delete() }
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
                loadImageModels = imageGateway::listModels,
                loadSelection = loadSelection,
                storeSelection = storeSelection,
                deleteSelection = deleteSelection,
                loadActiveChatProvider = { defaultModelId ->
                    withContext(ioDispatcher) {
                        activeChatProviderStore.load(defaultModelId)
                    }
                },
                storeActiveChatProvider = { selection ->
                    withContext(ioDispatcher) { activeChatProviderStore.store(selection) }
                },
                hasCodexCredential = {
                    withContext(ioDispatcher) { codexCredentialManager.current() != null }
                },
                startCodexAuthorization = codexGateway::startDeviceAuthorization,
                awaitCodexCredential = codexCoordinator::awaitCredential,
                storeCodexCredential = { credential ->
                    withContext(ioDispatcher) { codexCredentialManager.store(credential) }
                },
                logoutCodex = {
                    withContext(ioDispatcher) { codexCredentialManager.logout() }
                },
            ) as T
        }
    }
}

private fun codexSignInErrorMessage(error: Throwable): String = when (error) {
    is CodexOAuthProtocolException -> when (error.errorType) {
        "expired" -> "The Codex sign-in code expired. Start again to get a new code."
        "network", "timeout" -> "Could not reach ChatGPT. Check this phone’s network and try again."
        else -> error.message ?: "Codex sign-in failed."
    }
    else -> "Codex sign-in could not be completed safely."
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
