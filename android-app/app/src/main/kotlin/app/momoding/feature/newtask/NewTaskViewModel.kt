package app.momoding.feature.newtask

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.wire.WireErrorCode
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.attachments.AttachmentImportBatchResult
import app.momoding.core.attachments.AttachmentRecord
import app.momoding.core.attachments.DraftAttachmentGateway
import app.momoding.core.data.DraftRepository
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.feature.tasks.PhoneLocalTaskCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NewTaskOneShot {
    data class OpenTask(val taskId: String) : NewTaskOneShot
    data class LaunchCamera(val captureId: String, val outputUri: Uri) : NewTaskOneShot
    data object OpenFullAccessSetup : NewTaskOneShot
    data object Back : NewTaskOneShot
}

class NewTaskViewModel internal constructor(
    private val draftId: String,
    private val drafts: DraftRepository,
    private val transportStatus: StateFlow<app.momoding.core.transport.SecureTransportUiStatus>,
    private val retryConnectionAction: suspend () -> Unit,
    private val refreshProfileAction: suspend () -> Unit,
    private val coordinator: TaskCreationCoordinator? = null,
    private val phoneLocalCoordinator: PhoneLocalTaskCoordinator? = null,
    private val authorizedFolders: AuthorizedFoldersRepository? = null,
    private val attachments: DraftAttachmentGateway? = null,
    private val photoAttachmentInputEnabled: Boolean = false,
    private val textFileAttachmentInputEnabled: Boolean = false,
    private val cameraAttachmentInputEnabled: Boolean = false,
    private val imageAttachmentRuntimeReady: Boolean = false,
    private val textFileAttachmentRuntimeReady: Boolean = false,
    initialState: NewTaskUiState = NewTaskUiState(draftId = draftId),
    private val approvalModePersistence: suspend (TaskApprovalMode) -> Unit = { mode ->
        drafts.selectApprovalMode(draftId, mode)
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        initialState.copy(
            photoAttachmentInputEnabled = photoAttachmentInputEnabled,
            textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
            cameraAttachmentInputEnabled = cameraAttachmentInputEnabled,
            imageAttachmentRuntimeReady = imageAttachmentRuntimeReady,
            textFileAttachmentRuntimeReady = textFileAttachmentRuntimeReady,
        ),
    )
    val state: StateFlow<NewTaskUiState> = mutableState.asStateFlow()
    private val mutableOneShots = MutableSharedFlow<NewTaskOneShot>(extraBufferCapacity = 4)
    val oneShots: SharedFlow<NewTaskOneShot> = mutableOneShots.asSharedFlow()
    private var saveJob: Job? = null
    private var sendPersistence: CompletableDeferred<Boolean>? = null
    private var initialized = initialState.draftInitialized
    private val draftInitialization = CompletableDeferred<Unit>().apply {
        if (initialized) complete(Unit)
    }
    private var navigatedTaskId: String? = null

    constructor(
        draftId: String,
        drafts: DraftRepository,
        runtime: AndroidSecureTransportRuntime,
        coordinator: TaskCreationCoordinator,
        authorizedFolders: AuthorizedFoldersRepository,
        attachments: DraftAttachmentGateway? = null,
        photoAttachmentInputEnabled: Boolean = false,
        textFileAttachmentInputEnabled: Boolean = false,
        cameraAttachmentInputEnabled: Boolean = false,
        imageAttachmentRuntimeReady: Boolean = false,
        textFileAttachmentRuntimeReady: Boolean = false,
    ) : this(
        draftId = draftId,
        drafts = drafts,
        transportStatus = runtime.uiStatus,
        retryConnectionAction = runtime::retryConnection,
        refreshProfileAction = runtime::refreshProfile,
        coordinator = coordinator,
        authorizedFolders = authorizedFolders,
        attachments = attachments,
        photoAttachmentInputEnabled = photoAttachmentInputEnabled,
        textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
        cameraAttachmentInputEnabled = cameraAttachmentInputEnabled,
        imageAttachmentRuntimeReady = imageAttachmentRuntimeReady,
        textFileAttachmentRuntimeReady = textFileAttachmentRuntimeReady,
    )

    constructor(
        draftId: String,
        drafts: DraftRepository,
        coordinator: PhoneLocalTaskCoordinator,
        modelId: String,
        authorizedFolders: AuthorizedFoldersRepository,
        attachments: DraftAttachmentGateway? = null,
        photoAttachmentInputEnabled: Boolean = false,
        textFileAttachmentInputEnabled: Boolean = false,
        cameraAttachmentInputEnabled: Boolean = false,
        imageAttachmentRuntimeReady: Boolean = false,
        textFileAttachmentRuntimeReady: Boolean = false,
    ) : this(
        draftId = draftId,
        drafts = drafts,
        transportStatus = MutableStateFlow(
            SecureTransportUiStatus(
                phase = SecureTransportUiPhase.READY,
                hostAlias = "On this phone",
                profile = HostClientProfile(
                    hostAlias = "On this phone",
                    provider = "OpenRouter",
                    model = modelId,
                    thinking = "default",
                    mutable = false,
                    configRevision = 1,
                ),
            ),
        ),
        retryConnectionAction = {},
        refreshProfileAction = {},
        phoneLocalCoordinator = coordinator,
        authorizedFolders = authorizedFolders,
        attachments = attachments,
        photoAttachmentInputEnabled = photoAttachmentInputEnabled,
        textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
        cameraAttachmentInputEnabled = cameraAttachmentInputEnabled,
        imageAttachmentRuntimeReady = imageAttachmentRuntimeReady,
        textFileAttachmentRuntimeReady = textFileAttachmentRuntimeReady,
        initialState = NewTaskUiState(
            draftId = draftId,
            phoneLocal = true,
            photoAttachmentInputEnabled = photoAttachmentInputEnabled,
            textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
            cameraAttachmentInputEnabled = cameraAttachmentInputEnabled,
            imageAttachmentRuntimeReady = imageAttachmentRuntimeReady,
            textFileAttachmentRuntimeReady = textFileAttachmentRuntimeReady,
        ),
    )

    init {
        check((coordinator == null) != (phoneLocalCoordinator == null)) {
            "New Task requires exactly one task coordinator"
        }
        viewModelScope.launch {
            val durable = drafts.createDraft(draftId)
            if (!initialized) initializeDraft(durable)
            drafts.observeDraft(draftId).collect { record ->
                if (record == null) return@collect
                if (!initialized) initializeDraft(record)
            }
        }
        viewModelScope.launch {
            transportStatus.collect { status ->
                mutableState.value = mutableState.value.copy(
                    connection = when (status.phase) {
                        SecureTransportUiPhase.READY -> NewTaskConnectionState.READY
                        SecureTransportUiPhase.OFFLINE -> NewTaskConnectionState.OFFLINE
                        else -> NewTaskConnectionState.UNAVAILABLE
                    },
                    profile = status.profile?.let { profile ->
                        NewTaskProfileState.Ready(
                            hostAlias = profile.hostAlias,
                            provider = profile.provider,
                            model = profile.model,
                            thinking = profile.thinking,
                        )
                    } ?: if (status.phase == SecureTransportUiPhase.READY) {
                        NewTaskProfileState.Error("The Host did not provide a usable model profile.")
                    } else {
                        NewTaskProfileState.Loading
                    },
                )
            }
        }
        viewModelScope.launch {
            val progress = coordinator?.observe(draftId)
                ?: requireNotNull(phoneLocalCoordinator).observeCreation(draftId)
            progress.collect(::applyProgress)
        }
        if (photoAttachmentInputEnabled || textFileAttachmentInputEnabled || cameraAttachmentInputEnabled) {
            val gateway = requireNotNull(attachments) {
                "Attachment input requires a durable attachment gateway"
            }
            viewModelScope.launch {
                gateway.observeDraftAttachments(draftId).collect { records ->
                    val uiModels = records.map { record -> attachmentUiModel(gateway, record) }
                    mutableState.value = mutableState.value.copy(attachments = uiModels)
                }
            }
        }
        authorizedFolders?.let { repository ->
            viewModelScope.launch {
                mutableState.value = mutableState.value.copy(mobileFoldersLoading = true)
                val folders = runCatching { repository.folders() }.getOrElse {
                    mutableState.value = mutableState.value.copy(
                        mobileFoldersLoading = false,
                        notice = "Authorized mobile folders could not be loaded.",
                    )
                    return@launch
                }
                val options = folders
                    .filter { it.canRead }
                    .map {
                        NewTaskFolderOption(
                            grantId = it.grantId,
                            displayName = it.displayName,
                            status = it.status,
                            canRead = it.canRead,
                        )
                    }
                val current = mutableState.value
                val selected = current.selectedGrantId?.takeIf { grantId ->
                    options.any { it.grantId == grantId }
                }
                mutableState.value = current.copy(
                    mobileFoldersLoading = false,
                    mobileFolders = options,
                    selectedGrantId = selected,
                )
                if (current.selectedGrantId != null && selected == null) {
                    runCatching { drafts.selectAuthorizedFolder(draftId, null) }
                }
            }
        }
    }

    private fun initializeDraft(record: app.momoding.core.data.DraftRecord) {
        if (initialized) return
        initialized = true
        mutableState.value = restoreDraftState(mutableState.value, record)
        draftInitialization.complete(Unit)
    }

    fun dispatch(action: NewTaskAction) {
        when (action) {
            is NewTaskAction.EditDraft -> editDraft(action.value)
            NewTaskAction.ClearDraft -> clearDraft()
            NewTaskAction.Send -> send()
            NewTaskAction.RetrySend -> if (mutableState.value.sendRetryable) retryTaskCreation()
            NewTaskAction.RetryConnection -> viewModelScope.launch { retryConnectionAction() }
            NewTaskAction.RetryModelLoad -> viewModelScope.launch { refreshProfileAction() }
            NewTaskAction.OpenFolderChooser -> if (
                mutableState.value.draftInitialized && !mutableState.value.sending
            ) {
                mutableState.value = mutableState.value.copy(folderChooserOpen = true)
            }
            NewTaskAction.DismissFolderChooser -> mutableState.value =
                mutableState.value.copy(folderChooserOpen = false)
            is NewTaskAction.SelectFolder -> selectFolder(action.grantId)
            NewTaskAction.TogglePlanMode -> togglePlanMode()
            is NewTaskAction.SelectApprovalMode -> selectApprovalMode(action.mode)
            NewTaskAction.OpenAttachmentMenu -> openAttachmentMenu()
            NewTaskAction.DismissAttachmentMenu -> mutableState.value =
                mutableState.value.copy(attachmentMenuOpen = false)
            is NewTaskAction.ImportPhotos -> importPhotos(action.uris)
            is NewTaskAction.ImportFile -> importFile(action.uri)
            NewTaskAction.RequestCameraCapture -> requestCameraCapture()
            is NewTaskAction.CameraCaptureFinished -> finishCameraCapture(action)
            is NewTaskAction.RemoveAttachment -> removeAttachment(action.attachmentId)
            NewTaskAction.DismissIme -> Unit
            NewTaskAction.Back -> back()
            NewTaskAction.RestorationAnnouncementConsumed -> mutableState.value = mutableState.value.copy(
                restored = false,
                notice = null,
            )
        }
    }

    private fun editDraft(value: TextFieldValue) {
        if (!mutableState.value.draftInitialized || mutableState.value.sending) return
        mutableState.value = mutableState.value.copy(
            draft = value,
            restored = false,
            notice = null,
            sendError = null,
            sendRetryable = false,
            sendState = NewTaskSendState.IDLE,
        )
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MILLIS)
            flushOrPublishLocalFailure(value)
        }
    }

    private fun clearDraft() {
        if (!mutableState.value.draftInitialized || mutableState.value.sending) return
        val empty = TextFieldValue()
        mutableState.value = mutableState.value.copy(
            draft = empty,
            restored = false,
            notice = null,
            sendError = null,
            sendRetryable = false,
            sendState = NewTaskSendState.IDLE,
        )
        saveJob?.cancel()
        saveJob = viewModelScope.launch { flushOrPublishLocalFailure(empty) }
    }

    private fun selectFolder(grantId: String?) {
        val current = mutableState.value
        if (!current.draftInitialized || current.sending || current.folderSelectionSaving) return
        if (grantId != null && current.mobileFolders.none { it.grantId == grantId && it.canRead }) {
            return
        }
        mutableState.value = current.copy(
            selectedGrantId = grantId,
            folderChooserOpen = false,
            folderSelectionSaving = true,
            sendError = null,
        )
        viewModelScope.launch {
            try {
                drafts.selectAuthorizedFolder(draftId, grantId)
                mutableState.value = mutableState.value.copy(folderSelectionSaving = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    selectedGrantId = current.selectedGrantId,
                    folderSelectionSaving = false,
                    notice = "The mobile folder selection could not be saved.",
                )
            }
        }
    }

    private fun togglePlanMode() {
        val current = mutableState.value
        if (!current.draftInitialized || !current.phoneLocal || current.sending || current.modeSelectionSaving) return
        val enabled = !current.planMode
        mutableState.value = current.copy(
            planMode = enabled,
            modeSelectionSaving = true,
            sendError = null,
        )
        viewModelScope.launch {
            try {
                drafts.selectMode(draftId, "PLAN".takeIf { enabled })
                mutableState.value = mutableState.value.copy(modeSelectionSaving = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    planMode = current.planMode,
                    modeSelectionSaving = false,
                    notice = "The task mode could not be saved.",
                )
            }
        }
    }

    private fun selectApprovalMode(mode: TaskApprovalMode) {
        val current = mutableState.value
        if (!current.draftInitialized || current.sending || current.approvalModeSaving ||
            current.approvalMode == mode
        ) return
        mutableState.value = current.copy(
            approvalMode = mode,
            approvalModeSaving = true,
            sendError = null,
            notice = null,
        )
        viewModelScope.launch {
            try {
                approvalModePersistence(mode)
                mutableState.value = mutableState.value.copy(approvalModeSaving = false)
                if (mode == TaskApprovalMode.FULL_ACCESS) {
                    mutableOneShots.emit(NewTaskOneShot.OpenFullAccessSetup)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    approvalMode = current.approvalMode,
                    approvalModeSaving = false,
                    notice = "The approval mode could not be saved.",
                )
            }
        }
    }

    private fun openAttachmentMenu() {
        val current = mutableState.value
        if (!current.draftInitialized || (!current.attachmentInputEnabled && !current.phoneLocal) || current.sending ||
            current.attachmentImporting || current.cameraCaptureActive
        ) return
        mutableState.value = current.copy(attachmentMenuOpen = true, attachmentError = null)
    }

    private fun importPhotos(uris: List<android.net.Uri>) {
        if (!mutableState.value.draftInitialized || !mutableState.value.photoAttachmentInputEnabled) return
        if (uris.isEmpty()) {
            mutableState.value = mutableState.value.copy(attachmentMenuOpen = false)
            return
        }
        importAttachments { gateway -> gateway.importPhotoPickerSelection(draftId, uris) }
    }

    private fun importFile(uri: android.net.Uri) {
        if (!mutableState.value.draftInitialized || !mutableState.value.textFileAttachmentInputEnabled) return
        importAttachments { gateway -> gateway.importOpenDocument(draftId, uri) }
    }

    private fun requestCameraCapture() {
        val current = mutableState.value
        if (!current.draftInitialized || !current.cameraAttachmentInputEnabled || current.sending ||
            current.attachmentImporting || current.cameraCaptureActive ||
            current.attachments.size >= app.momoding.core.attachments.MAX_DRAFT_ATTACHMENTS
        ) return
        val gateway = attachments ?: return
        mutableState.value = current.copy(
            attachmentMenuOpen = false,
            cameraCaptureActive = true,
            attachmentError = null,
        )
        viewModelScope.launch {
            try {
                val request = gateway.prepareCameraCapture(draftId)
                mutableOneShots.emit(NewTaskOneShot.LaunchCamera(request.captureId, request.outputUri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    cameraCaptureActive = false,
                    attachmentError = "The system camera could not be opened.",
                )
            }
        }
    }

    private fun finishCameraCapture(action: NewTaskAction.CameraCaptureFinished) {
        val gateway = attachments ?: return
        mutableState.value = mutableState.value.copy(
            cameraCaptureActive = false,
            attachmentImporting = action.captured,
            attachmentError = if (action.launchFailed) "The system camera could not be opened." else null,
        )
        viewModelScope.launch {
            try {
                draftInitialization.await()
                val result = gateway.completeCameraCapture(
                    draftId = draftId,
                    captureId = action.captureId,
                    captured = action.captured,
                )
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = result.failures.firstOrNull()?.safeMessage
                        ?: mutableState.value.attachmentError,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = if (action.captured) {
                        "The captured photo could not be saved on this phone."
                    } else {
                        mutableState.value.attachmentError
                    },
                )
            }
        }
    }

    private fun importAttachments(
        import: suspend (DraftAttachmentGateway) -> AttachmentImportBatchResult,
    ) {
        val current = mutableState.value
        if (!current.draftInitialized || !current.attachmentInputEnabled || current.sending ||
            current.attachmentImporting
        ) return
        val gateway = attachments ?: return
        mutableState.value = current.copy(
            attachmentMenuOpen = false,
            attachmentImporting = true,
            attachmentError = null,
        )
        viewModelScope.launch {
            try {
                val result = import(gateway)
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = result.failures.firstOrNull()?.safeMessage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = "The selected attachment could not be saved on this phone.",
                )
            }
        }
    }

    private fun removeAttachment(attachmentId: String) {
        val current = mutableState.value
        if (!current.draftInitialized || !current.attachmentInputEnabled || current.sending ||
            current.attachmentImporting
        ) return
        val gateway = attachments ?: return
        viewModelScope.launch {
            try {
                if (!gateway.removeDraftAttachment(draftId, attachmentId)) {
                    mutableState.value = mutableState.value.copy(
                        attachmentError = "The attachment is no longer available.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    attachmentError = "The attachment could not be removed from this draft.",
                )
            }
        }
    }

    private fun send() {
        val current = mutableState.value
        if (!current.canSend) return
        saveJob?.cancel()
        mutableState.value = current.copy(sendState = NewTaskSendState.PERSISTING, sendError = null)
        val persistence = CompletableDeferred<Boolean>()
        sendPersistence = persistence
        viewModelScope.launch {
            val saved = flushOrPublishLocalFailure(current.draft)
            if (saved) startTaskCreation()
            persistence.complete(saved)
        }
    }

    private fun back() {
        val current = mutableState.value
        saveJob?.cancel()
        if (!current.draftInitialized) {
            saveJob = viewModelScope.launch {
                draftInitialization.await()
                mutableOneShots.emit(NewTaskOneShot.Back)
            }
            return
        }
        if (current.sendState == NewTaskSendState.PERSISTING) {
            saveJob = viewModelScope.launch {
                val saved = sendPersistence?.await() ?: flushOrPublishLocalFailure(current.draft)
                if (saved) mutableOneShots.emit(NewTaskOneShot.Back)
            }
            return
        }
        if (current.sending) {
            saveJob = viewModelScope.launch { mutableOneShots.emit(NewTaskOneShot.Back) }
            return
        }
        saveJob = viewModelScope.launch {
            drafts.createDraft(draftId)
            if (flushOrPublishLocalFailure(current.draft)) mutableOneShots.emit(NewTaskOneShot.Back)
        }
    }

    private suspend fun flushOrPublishLocalFailure(value: TextFieldValue): Boolean = try {
        flush(value)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(
            sendState = NewTaskSendState.ERROR,
            sendError = "The draft could not be saved on this phone.",
            sendRetryable = false,
        )
        false
    }

    private suspend fun flush(value: TextFieldValue) {
        drafts.flushTextAndSelection(
            draftId = draftId,
            text = value.text,
            selectionStart = value.selection.start,
            selectionEnd = value.selection.end,
        )
    }

    private fun applyProgress(progress: TaskCreationProgress) {
        mutableState.value = when (progress) {
            TaskCreationProgress.Idle -> mutableState.value
            is TaskCreationProgress.Working -> mutableState.value.copy(
                sendState = when (progress.stage) {
                    TaskCreationStage.CREATE -> if (progress.recovering) NewTaskSendState.RECOVERING else NewTaskSendState.CREATING
                    TaskCreationStage.PROMPT -> if (progress.recovering) NewTaskSendState.RECOVERING else NewTaskSendState.PROMPTING
                },
                sendError = null,
                sendRetryable = false,
            )
            is TaskCreationProgress.Failed -> mutableState.value.copy(
                sendState = NewTaskSendState.ERROR,
                sendError = progress.safeMessage
                    ?: progress.code.toUserMessage(progress.stage, mutableState.value.phoneLocal),
                sendRetryable = progress.retryable,
            )
            is TaskCreationProgress.Started -> {
                if (navigatedTaskId != progress.taskId) {
                    navigatedTaskId = progress.taskId
                    mutableOneShots.tryEmit(NewTaskOneShot.OpenTask(progress.taskId))
                }
                mutableState.value.copy(sendState = NewTaskSendState.IDLE)
            }
        }
    }

    private fun startTaskCreation() {
        coordinator?.start(draftId) ?: requireNotNull(phoneLocalCoordinator).start(draftId)
    }

    private fun retryTaskCreation() {
        coordinator?.retry(draftId) ?: requireNotNull(phoneLocalCoordinator).retry(draftId)
    }

    class Factory(
        private val draftId: String,
        private val drafts: DraftRepository,
        private val runtime: AndroidSecureTransportRuntime,
        private val coordinator: TaskCreationCoordinator,
        private val authorizedFolders: AuthorizedFoldersRepository,
        private val attachments: DraftAttachmentGateway? = null,
        private val photoAttachmentInputEnabled: Boolean = false,
        private val textFileAttachmentInputEnabled: Boolean = false,
        private val cameraAttachmentInputEnabled: Boolean = false,
        private val imageAttachmentRuntimeReady: Boolean = false,
        private val textFileAttachmentRuntimeReady: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NewTaskViewModel::class.java))
            return NewTaskViewModel(
                draftId,
                drafts,
                runtime,
                coordinator,
                authorizedFolders,
                attachments,
                photoAttachmentInputEnabled,
                textFileAttachmentInputEnabled,
                cameraAttachmentInputEnabled,
                imageAttachmentRuntimeReady,
                textFileAttachmentRuntimeReady,
            ) as T
        }
    }

    class PhoneLocalFactory(
        private val draftId: String,
        private val drafts: DraftRepository,
        private val coordinator: PhoneLocalTaskCoordinator,
        private val modelId: String,
        private val authorizedFolders: AuthorizedFoldersRepository,
        private val attachments: DraftAttachmentGateway? = null,
        private val photoAttachmentInputEnabled: Boolean = false,
        private val textFileAttachmentInputEnabled: Boolean = false,
        private val cameraAttachmentInputEnabled: Boolean = false,
        private val imageAttachmentRuntimeReady: Boolean = false,
        private val textFileAttachmentRuntimeReady: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NewTaskViewModel::class.java))
            return NewTaskViewModel(
                draftId = draftId,
                drafts = drafts,
                coordinator = coordinator,
                modelId = modelId,
                authorizedFolders = authorizedFolders,
                attachments = attachments,
                photoAttachmentInputEnabled = photoAttachmentInputEnabled,
                textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
                cameraAttachmentInputEnabled = cameraAttachmentInputEnabled,
                imageAttachmentRuntimeReady = imageAttachmentRuntimeReady,
                textFileAttachmentRuntimeReady = textFileAttachmentRuntimeReady,
            ) as T
        }
    }

    private companion object {
        const val DRAFT_DEBOUNCE_MILLIS = 250L
    }
}

private suspend fun attachmentUiModel(
    gateway: DraftAttachmentGateway,
    record: AttachmentRecord,
): NewTaskAttachmentUiModel {
    val thumbnail = if (record.hasThumbnail) gateway.thumbnailPng(record.attachmentId) else null
    return NewTaskAttachmentUiModel(
        attachmentId = record.attachmentId,
        kind = record.kind,
        displayName = record.displayName,
        mimeType = record.mimeType,
        byteSize = record.byteSize,
        thumbnailPng = thumbnail,
        width = record.width,
        height = record.height,
    )
}

internal fun restoreDraftState(current: NewTaskUiState, record: app.momoding.core.data.DraftRecord): NewTaskUiState =
    current.copy(
        draftInitialized = true,
        draft = TextFieldValue(
            record.text,
            TextRange(record.selectionStart, record.selectionEnd),
        ),
        restored = record.text.isNotEmpty(),
        notice = if (record.text.isNotEmpty()) DRAFT_RESTORED_NOTICE else null,
        selectedGrantId = record.selectedGrantId,
        planMode = record.selectedMode == "PLAN",
        approvalMode = record.approvalMode,
    )

private fun WireErrorCode.toUserMessage(
    stage: TaskCreationStage,
    phoneLocal: Boolean,
): String = if (phoneLocal) {
    when (this) {
        WireErrorCode.SESSION_BUSY -> "Another on-device task is still running."
        WireErrorCode.ABORTED -> "The on-device task was stopped before it started."
        else -> "Momoding could not safely start this task."
    }
} else when (this) {
    WireErrorCode.SESSION_BUSY -> "The Host is already running this task."
    WireErrorCode.TASK_NOT_FOUND -> "The Host could not find the created task."
    WireErrorCode.UNAUTHORIZED -> "This phone is no longer authorized by the Host."
    WireErrorCode.BAD_REQUEST -> "The Host rejected the ${stage.name.lowercase()} request."
    WireErrorCode.ABORTED -> "The Host stopped the request before it started."
    else -> "The Host could not safely continue (${name.lowercase()})."
}
