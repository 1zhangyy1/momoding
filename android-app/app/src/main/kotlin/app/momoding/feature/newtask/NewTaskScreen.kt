package app.momoding.feature.newtask

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.momoding.ui.components.MomodingBannerTone
import app.momoding.core.attachments.AttachmentKind
import app.momoding.core.attachments.MAX_DRAFT_ATTACHMENTS
import app.momoding.ui.components.MomodingStatusBanner
import app.momoding.ui.components.ComposerDock
import app.momoding.ui.components.ComposerMenuItem
import app.momoding.ui.components.ApprovalModeSelector
import app.momoding.ui.components.ListGroup
import app.momoding.ui.components.ListRow
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.NavigationDrawerButton
import app.momoding.ui.components.ProductIconTone
import app.momoding.ui.components.ProductTopBar
import app.momoding.ui.theme.LocalMomodingStatusColors
import app.momoding.ui.theme.LocalMomodingBrandColors
import app.momoding.feature.settings.structuralAction
import app.momoding.ui.icons.MomodingFilledIcons
import app.momoding.ui.icons.MomodingIcons

@Composable
fun NewTaskScreen(
    state: NewTaskUiState,
    onAction: (NewTaskAction) -> Unit,
    interactionPolicy: NewTaskInteractionPolicy = NewTaskInteractionPolicy.All,
    importNotice: String? = null,
    onOpenNavigation: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_DRAFT_ATTACHMENTS),
    ) { uris ->
        if (uris.isNotEmpty()) onAction(NewTaskAction.ImportPhotos(uris))
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(NewTaskAction.ImportFile(uri))
    }
    LaunchedEffect(state.restored, state.notice) {
        if (state.restored && state.notice == DRAFT_RESTORED_NOTICE) {
            withFrameNanos { }
            onAction(NewTaskAction.RestorationAnnouncementConsumed)
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ProductTopBar(
                title = "New task",
                navigation = onOpenNavigation?.let { open ->
                    { NavigationDrawerButton(onClick = open) }
                },
                onBack = if (
                    onOpenNavigation == null &&
                    interactionPolicy.allows(NewTaskInteraction.BACK)
                ) {
                    { onAction(NewTaskAction.Back) }
                } else null,
                backModifier = Modifier.structuralAction("NavigateBack"),
            )
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("new-task-content"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 13.dp, end = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.sendError != null) {
                    item {
                        SendErrorBanner(state.sendRetryable, onAction, interactionPolicy)
                    }
                }
                if (state.sendError == null && state.connection != NewTaskConnectionState.READY) {
                    item { HostConnectionBanner(state.connection, onAction, interactionPolicy) }
                }
                when (val profile = state.profile) {
                    NewTaskProfileState.Loading -> item {
                        ModelLoadingBanner(onAction, interactionPolicy)
                    }
                    is NewTaskProfileState.Error -> item {
                        ModelErrorBanner(profile.message, onAction, interactionPolicy)
                    }
                    is NewTaskProfileState.Ready -> Unit
                }
                importNotice?.let { notice ->
                    item {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("android-share-notice"),
                        )
                    }
                }
                state.notice?.let { notice ->
                    item {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                if (state.restored) liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                }
                item {
                    NewTaskIntro()
                }
                if (state.draft.text.isBlank() && state.attachments.isEmpty()) {
                    item { SuggestionList(state, onAction, interactionPolicy) }
                }
            }
            NewTaskComposerDock(state, focusManager, onAction, interactionPolicy)
        }
    }
    if (state.folderChooserOpen) {
        MobileFolderChooser(
            state = state,
            onAction = onAction,
            policy = interactionPolicy,
        )
    }
    if (state.attachmentMenuOpen && (state.attachmentInputEnabled || state.phoneLocal)) {
        AttachmentPickerSheet(
            onDismiss = { onAction(NewTaskAction.DismissAttachmentMenu) },
            onCamera = { onAction(NewTaskAction.RequestCameraCapture) },
            onPhotos = {
                onAction(NewTaskAction.DismissAttachmentMenu)
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onFile = {
                onAction(NewTaskAction.DismissAttachmentMenu)
                filePicker.launch(
                    arrayOf(
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/javascript",
                    ),
                )
            },
            onTogglePlanMode = {
                onAction(NewTaskAction.DismissAttachmentMenu)
                onAction(NewTaskAction.TogglePlanMode)
            },
            photosEnabled = state.photoAttachmentInputEnabled,
            textFilesEnabled = state.textFileAttachmentInputEnabled,
            cameraEnabled = state.cameraAttachmentInputEnabled,
            planAvailable = state.phoneLocal,
            planEnabled = state.planMode,
            planSaving = state.modeSelectionSaving,
            attachmentCapacityAvailable = state.attachments.size < MAX_DRAFT_ATTACHMENTS,
            policy = interactionPolicy,
        )
    }
}

@Composable
private fun NewTaskIntro() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MomodingMark(size = 54.dp)
        Text(
            "What should Momoding do?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp)
                .semantics { heading() },
        )
        Text(
            "Describe the outcome you want and the context that matters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        )
    }
}

@Composable
private fun SuggestionList(
    state: NewTaskUiState,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    ListGroup {
        SuggestionRow(
            icon = MomodingIcons.Tasks,
            label = "Draft a checklist for my next project",
            enabled = state.draftInitialized && !state.sending && policy.allows(NewTaskInteraction.EDIT_DRAFT),
            policy = policy,
            showDivider = true,
            onClick = { onAction(NewTaskAction.EditDraft(textValue("Draft a checklist for my next project"))) },
        )
        SuggestionRow(
            icon = MomodingIcons.Add,
            label = "Help me break a task into clear steps",
            enabled = state.draftInitialized && !state.sending && policy.allows(NewTaskInteraction.EDIT_DRAFT),
            policy = policy,
            showDivider = true,
            onClick = { onAction(NewTaskAction.EditDraft(textValue("Help me break a task into clear steps"))) },
        )
        SuggestionRow(
            icon = MomodingIcons.Code,
            label = "Review text that I paste here",
            enabled = state.draftInitialized && !state.sending && policy.allows(NewTaskInteraction.EDIT_DRAFT),
            policy = policy,
            showDivider = false,
            onClick = { onAction(NewTaskAction.EditDraft(textValue("Review this text and suggest precise improvements:\n\n"))) },
        )
    }
}

@Composable
private fun SuggestionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    policy: NewTaskInteractionPolicy,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    ListRow(
        title = label,
        titleMaxLines = 2,
        icon = icon,
        iconTone = ProductIconTone.NEUTRAL,
        onClick = if (enabled) onClick else null,
        showDivider = showDivider,
        modifier = Modifier
            .testTag("action-EditDraft")
            .newTaskContractAction(policy, NewTaskInteraction.EDIT_DRAFT),
    )
}

@Composable
private fun NewTaskComposerDock(
    state: NewTaskUiState,
    focusManager: FocusManager,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .imePadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Composer(state, focusManager, onAction, policy)
    }
}

@Composable
private fun Composer(
    state: NewTaskUiState,
    focusManager: FocusManager,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    val brand = LocalMomodingBrandColors.current
    ComposerDock(
        meta = {
            MobileFolderContext(
                state = state,
                enabled = state.draftInitialized && !state.sending &&
                    !state.folderSelectionSaving &&
                    policy.allows(NewTaskInteraction.OPEN_FOLDER_CHOOSER),
                onClick = { onAction(NewTaskAction.OpenFolderChooser) },
                modifier = Modifier
                    .testTag("action-OpenFolderChooser")
                    .newTaskContractAction(
                        policy,
                        NewTaskInteraction.OPEN_FOLDER_CHOOSER,
                    ),
            )
        },
        editor = {
            if (state.attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = state.attachments,
                    removalEnabled = state.draftInitialized && !state.sending && !state.attachmentImporting &&
                        !state.cameraCaptureActive &&
                        policy.allows(NewTaskInteraction.REMOVE_ATTACHMENT),
                    onRemove = { onAction(NewTaskAction.RemoveAttachment(it)) },
                    policy = policy,
                )
                Spacer(Modifier.size(8.dp))
            }
            state.attachmentError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
            }
            BasicTextField(
                value = state.draft,
                onValueChange = { onAction(NewTaskAction.EditDraft(it)) },
                enabled = state.draftInitialized && !state.sending &&
                    policy.allows(NewTaskInteraction.EDIT_DRAFT),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (policy.allows(NewTaskInteraction.DISMISS_IME)) {
                            focusManager.clearFocus()
                            onAction(NewTaskAction.DismissIme)
                        }
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .testTag("action-EditDraft")
                    .newTaskContractAction(policy, NewTaskInteraction.EDIT_DRAFT),
                decorationBox = { inner ->
                    Box {
                        if (state.draft.text.isEmpty()) {
                            Text(
                                if (state.planMode) {
                                    "Describe what the plan should cover"
                                } else {
                                    "Describe a task for Momoding"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        },
        footer = {
            if (state.attachmentInputEnabled || state.phoneLocal) {
                IconButton(
                    onClick = { onAction(NewTaskAction.OpenAttachmentMenu) },
                    enabled = state.draftInitialized && !state.sending &&
                        !state.attachmentImporting &&
                        !state.cameraCaptureActive &&
                        policy.allows(NewTaskInteraction.OPEN_ATTACHMENT_MENU),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("action-OpenAttachmentMenu")
                        .newTaskContractAction(policy, NewTaskInteraction.OPEN_ATTACHMENT_MENU),
                ) {
                    if (state.attachmentImporting || state.cameraCaptureActive) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(MomodingIcons.Add, contentDescription = "Add")
                    }
                }
            }
            ApprovalModeSelector(
                mode = state.approvalMode,
                saving = state.approvalModeSaving,
                enabled = state.draftInitialized && !state.sending &&
                    policy.allows(NewTaskInteraction.SELECT_APPROVAL_MODE),
                onSelect = { onAction(NewTaskAction.SelectApprovalMode(it)) },
                modifier = Modifier.newTaskContractAction(
                    policy,
                    NewTaskInteraction.SELECT_APPROVAL_MODE,
                ),
            )
            Spacer(Modifier.weight(1f))
            if (
                state.draftInitialized &&
                state.draft.text.isNotEmpty() &&
                !state.sending &&
                policy.allows(NewTaskInteraction.CLEAR_DRAFT)
            ) {
                IconButton(
                    onClick = { onAction(NewTaskAction.ClearDraft) },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("action-ClearDraft")
                        .newTaskContractAction(policy, NewTaskInteraction.CLEAR_DRAFT),
                ) {
                    Icon(
                        MomodingIcons.Close,
                        contentDescription = "Clear draft",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            val sendAllowed = state.canSend && policy.allows(NewTaskInteraction.SEND)
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    onAction(NewTaskAction.Send)
                },
                enabled = sendAllowed,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("action-Send")
                    .newTaskContractAction(policy, NewTaskInteraction.SEND)
                    .semantics { state.sendDisabledReason?.let { stateDescription = it } },
            ) {
                Box(
                    modifier = Modifier.size(36.dp).background(
                        if (sendAllowed) brand.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(10.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(
                            MomodingFilledIcons.Send,
                            contentDescription = if (sendAllowed) "Send task" else state.sendDisabledReason,
                            modifier = Modifier.size(18.dp),
                            tint = if (sendAllowed) brand.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MobileFolderContext(
    state: NewTaskUiState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedFolder = state.mobileFolders.firstOrNull { it.grantId == state.selectedGrantId }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.folderSelectionSaving || state.mobileFoldersLoading) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    MomodingIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    state.folderSelectionSaving -> "Saving mobile folder"
                    state.mobileFoldersLoading -> "Loading mobile folders"
                    selectedFolder != null -> selectedFolder.displayName
                    else -> "Mobile folder · Optional"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (selectedFolder == null) "Choose" else "Change",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<NewTaskAttachmentUiModel>,
    removalEnabled: Boolean,
    onRemove: (String) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.testTag("attachment-${attachment.attachmentId}"),
            ) {
                Row(
                    modifier = Modifier.padding(start = 7.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AttachmentPreview(attachment)
                    Column(modifier = Modifier.size(width = 112.dp, height = 42.dp)) {
                        Text(
                            attachment.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            attachmentLabel(attachment),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    IconButton(
                        onClick = { onRemove(attachment.attachmentId) },
                        enabled = removalEnabled,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("action-RemoveAttachment")
                            .newTaskContractAction(policy, NewTaskInteraction.REMOVE_ATTACHMENT),
                    ) {
                        Icon(
                            MomodingIcons.Close,
                            contentDescription = "Remove ${attachment.displayName}",
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(attachment: NewTaskAttachmentUiModel) {
    val bitmap = remember(attachment.attachmentId, attachment.thumbnailPng) {
        attachment.thumbnailPng?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(42.dp).background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(9.dp),
            ),
        )
    } else {
        Box(
            modifier = Modifier.size(42.dp).background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(9.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (attachment.kind) {
                    AttachmentKind.IMAGE -> Icons.Outlined.Image
                    AttachmentKind.TEXT_FILE -> Icons.Outlined.AttachFile
                    AttachmentKind.VIDEO -> Icons.Outlined.Videocam
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun attachmentLabel(attachment: NewTaskAttachmentUiModel): String {
    val kib = (attachment.byteSize + 1023L) / 1024L
    return when (attachment.kind) {
        AttachmentKind.IMAGE -> "Image · $kib KB"
        AttachmentKind.TEXT_FILE -> "Text file · $kib KB"
        AttachmentKind.VIDEO -> "Video · Agent cannot read · $kib KB"
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onTogglePlanMode: () -> Unit,
    photosEnabled: Boolean,
    textFilesEnabled: Boolean,
    cameraEnabled: Boolean,
    planAvailable: Boolean,
    planEnabled: Boolean,
    planSaving: Boolean,
    attachmentCapacityAvailable: Boolean,
    policy: NewTaskInteractionPolicy,
) {
    val hasAttachmentOptions = cameraEnabled || photosEnabled || textFilesEnabled
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add", style = MaterialTheme.typography.titleLarge)
            Text(
                if (hasAttachmentOptions) {
                    "Add context or choose how Momoding should approach this task."
                } else {
                    "Choose how Momoding should approach this task."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (cameraEnabled) {
                ComposerMenuItem(
                    icon = Icons.Outlined.CameraAlt,
                    title = "Camera",
                    detail = "Take one photo with the system camera",
                    enabled = attachmentCapacityAvailable &&
                        policy.allows(NewTaskInteraction.IMPORT_CAMERA),
                    modifier = Modifier
                        .testTag("action-ImportCamera")
                        .newTaskContractAction(policy, NewTaskInteraction.IMPORT_CAMERA),
                    onClick = onCamera,
                )
            }
            if (photosEnabled) {
                ComposerMenuItem(
                    icon = Icons.Outlined.Image,
                    title = "Photos",
                    detail = "Choose up to five images with Android Photo Picker",
                    enabled = attachmentCapacityAvailable &&
                        policy.allows(NewTaskInteraction.IMPORT_PHOTOS),
                    modifier = Modifier
                        .testTag("action-ImportPhotos")
                        .newTaskContractAction(policy, NewTaskInteraction.IMPORT_PHOTOS),
                    onClick = onPhotos,
                )
            }
            if (textFilesEnabled) {
                ComposerMenuItem(
                    icon = Icons.Outlined.AttachFile,
                    title = "Text file",
                    detail = "Choose one text, code, Markdown, JSON, or XML file",
                    enabled = attachmentCapacityAvailable &&
                        policy.allows(NewTaskInteraction.IMPORT_FILE),
                    modifier = Modifier
                        .testTag("action-ImportFile")
                        .newTaskContractAction(policy, NewTaskInteraction.IMPORT_FILE),
                    onClick = onFile,
                )
            }
            if (planAvailable) {
                ComposerMenuItem(
                    icon = MomodingIcons.Tasks,
                    title = "Plan mode",
                    detail = if (planEnabled) {
                        "On · Momoding will plan before making changes"
                    } else {
                        "Ask Momoding to make a plan before acting"
                    },
                    enabled = policy.allows(NewTaskInteraction.TOGGLE_PLAN_MODE),
                    selected = planEnabled,
                    loading = planSaving,
                    modifier = Modifier
                        .testTag("action-TogglePlanMode")
                        .newTaskContractAction(policy, NewTaskInteraction.TOGGLE_PLAN_MODE),
                    onClick = onTogglePlanMode,
                )
            }
            if (hasAttachmentOptions) {
                Text(
                    "Only files you choose are copied into this app and sent with this task.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MobileFolderChooser(
    state: NewTaskUiState,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    AlertDialog(
        onDismissRequest = { onAction(NewTaskAction.DismissFolderChooser) },
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = { Text("Choose mobile folder") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (state.phoneLocal) {
                        "Selecting a folder copies its readable project files into this task’s private workspace on your phone. Sensitive and generated files are excluded; every write to the real folder still shows a diff and asks for confirmation."
                    } else {
                        "Momoding can see names and metadata in the folder selected for this task. Reading a file asks separately; every write shows a diff and asks for confirmation. The Android folder address stays on this phone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                MobileFolderChoice(
                    label = "No mobile folder",
                    detail = "Run this task without mobile file access",
                    selected = state.selectedGrantId == null,
                    enabled = policy.allows(NewTaskInteraction.SELECT_FOLDER),
                    onClick = { onAction(NewTaskAction.SelectFolder(null)) },
                )
                state.mobileFolders.forEach { folder ->
                    MobileFolderChoice(
                        label = folder.displayName,
                        detail = if (state.phoneLocal) {
                            "Private task workspace · Writes need confirmation"
                        } else {
                            "Only this task · File contents need your approval"
                        },
                        selected = state.selectedGrantId == folder.grantId,
                        enabled = folder.canRead &&
                            policy.allows(NewTaskInteraction.SELECT_FOLDER),
                        onClick = { onAction(NewTaskAction.SelectFolder(folder.grantId)) },
                    )
                }
                if (!state.mobileFoldersLoading && state.mobileFolders.isEmpty()) {
                    Text(
                        "No readable folder is authorized. Add one in Settings → Phone access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(NewTaskAction.DismissFolderChooser) }) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun MobileFolderChoice(
    label: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("action-SelectFolder")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SendErrorBanner(
    retryable: Boolean,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    val colors = LocalMomodingStatusColors.current
    val canRetry = retryable && policy.allows(NewTaskInteraction.RETRY_SEND)
    MomodingStatusBanner(
        title = "Couldn’t start the task",
        body = "Your draft is saved on this phone.",
        icon = Icons.Outlined.WarningAmber,
        iconColor = colors.danger,
        containerColor = colors.dangerContainer,
        tone = MomodingBannerTone.DANGER,
        actionLabel = if (canRetry) "Retry" else null,
        onAction = if (canRetry) ({ onAction(NewTaskAction.RetrySend) }) else null,
        actionModifier = Modifier
            .testTag("action-RetrySend")
            .newTaskContractAction(policy, NewTaskInteraction.RETRY_SEND),
    )
}

@Composable
private fun ModelErrorBanner(message: String, onAction: (NewTaskAction) -> Unit, policy: NewTaskInteractionPolicy) {
    val colors = LocalMomodingStatusColors.current
    val canRetry = policy.allows(NewTaskInteraction.RETRY_MODEL_LOAD)
    MomodingStatusBanner(
        title = "Host profile unavailable",
        body = message,
        icon = Icons.Outlined.WarningAmber,
        iconColor = colors.warning,
        containerColor = colors.warningContainer,
        tone = MomodingBannerTone.WARNING,
        actionLabel = if (canRetry) "Retry" else null,
        onAction = if (canRetry) ({ onAction(NewTaskAction.RetryModelLoad) }) else null,
        actionModifier = Modifier
            .testTag("action-RetryModelLoad")
            .newTaskContractAction(policy, NewTaskInteraction.RETRY_MODEL_LOAD),
    )
}

@Composable
private fun HostConnectionBanner(
    connection: NewTaskConnectionState,
    onAction: (NewTaskAction) -> Unit,
    policy: NewTaskInteractionPolicy,
) {
    val colors = LocalMomodingStatusColors.current
    val canRetry = policy.allows(NewTaskInteraction.RETRY_CONNECTION)
    MomodingStatusBanner(
        title = if (connection == NewTaskConnectionState.OFFLINE) "Host offline" else "Host unavailable",
        body = "Your draft stays on this phone until the secure Host connection is ready.",
        icon = Icons.Outlined.WarningAmber,
        iconColor = colors.warning,
        containerColor = colors.warningContainer,
        tone = MomodingBannerTone.WARNING,
        actionLabel = if (canRetry) "Retry" else null,
        onAction = if (canRetry) ({ onAction(NewTaskAction.RetryConnection) }) else null,
        actionModifier = Modifier
            .testTag("action-RetryConnection")
            .newTaskContractAction(policy, NewTaskInteraction.RETRY_CONNECTION),
    )
}

@Composable
private fun ModelLoadingBanner(onAction: (NewTaskAction) -> Unit, policy: NewTaskInteractionPolicy) {
    val colors = LocalMomodingStatusColors.current
    val canRetry = policy.allows(NewTaskInteraction.RETRY_MODEL_LOAD)
    MomodingStatusBanner(
        title = "Loading Host configuration",
        body = "Model and thinking settings are read-only on this phone.",
        icon = Icons.Outlined.Refresh,
        iconColor = colors.info,
        containerColor = colors.infoContainer,
        actionLabel = if (canRetry) "Retry" else null,
        onAction = if (canRetry) ({ onAction(NewTaskAction.RetryModelLoad) }) else null,
        actionModifier = Modifier
            .testTag("action-RetryModelLoad")
            .newTaskContractAction(policy, NewTaskInteraction.RETRY_MODEL_LOAD),
    )
}

private fun textValue(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))
