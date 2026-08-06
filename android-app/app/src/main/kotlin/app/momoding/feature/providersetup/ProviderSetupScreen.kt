package app.momoding.feature.providersetup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.momoding.core.provider.ChatProviderKind
import app.momoding.core.provider.supportsFunctionTools
import app.momoding.core.provider.supportsImageInput
import app.momoding.ui.components.DangerAction
import app.momoding.ui.components.ListGroup
import app.momoding.ui.components.ListRow
import app.momoding.ui.components.MomodingMark
import app.momoding.ui.components.PrimaryAction
import app.momoding.ui.components.ProductIconTone
import app.momoding.ui.components.ProductTopBar
import app.momoding.ui.components.SecondaryAction
import app.momoding.ui.components.StatusLine
import app.momoding.ui.components.StatusLineTone
import app.momoding.ui.icons.MomodingIcons

@Composable
fun ProviderSetupScreen(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
    onBack: (() -> Unit)? = null,
    onReturnToTask: (() -> Unit)? = null,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (onBack != null) {
                ProductTopBar(
                    title = "Provider",
                    subtitle = "Secure model access",
                    onBack = onBack,
                )
            }
            when (state.loadState) {
                ProviderSetupLoadState.LOADING -> LoadingProvider()
                ProviderSetupLoadState.ERROR -> ProviderLoadError(state, onAction)
                ProviderSetupLoadState.MISSING,
                ProviderSetupLoadState.CONFIGURED,
                -> ProviderForm(state, onAction, onReturnToTask)
            }
        }
    }
    if (state.deleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { onAction(ProviderSetupAction.CancelDelete) },
            title = { Text("Remove OpenRouter?") },
            text = {
                Text(
                    "This deletes the encrypted API key from this phone. Existing local task data is kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(ProviderSetupAction.ConfirmDelete) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(ProviderSetupAction.CancelDelete) }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ProviderForm(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
    onReturnToTask: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MomodingMark(size = 52.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (state.configured) "Momoding on this phone" else "Set up Momoding",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Runs on this Android phone and connects directly to your model provider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ProviderPicker(state, onAction)

        if (state.activeChatProvider == ChatProviderKind.OPENROUTER) {
            ProviderHealthCard(state)

        OutlinedTextField(
            value = state.modelId,
            onValueChange = { onAction(ProviderSetupAction.EditModel(it)) },
            enabled = !state.busy,
            label = { Text("Model ID") },
            placeholder = { Text("provider/model") },
            supportingText = state.modelError?.let { message -> { Text(message) } },
            isError = state.modelError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("provider-model"),
        )

        SecondaryAction(
            label = if (state.modelCatalogVisible) "Hide model browser" else "Browse OpenRouter models",
            onClick = { onAction(ProviderSetupAction.ToggleModelCatalog) },
            enabled = !state.busy,
            icon = MomodingIcons.Search,
            modifier = Modifier.testTag("browse-models"),
        )

        if (state.modelCatalogVisible) {
            ModelCatalog(state, onAction)
        }

        ListGroup {
            ListRow(
                icon = MomodingIcons.Search,
                iconTone = ProductIconTone.BRAND,
                title = "Web access",
                detail = "Let the model search current information and read web pages or PDFs when needed.",
                meta = if (state.webSearchEnabled) "Enabled" else "Off",
                showDivider = false,
                trailing = {
                    Switch(
                        checked = state.webSearchEnabled,
                        onCheckedChange = { onAction(ProviderSetupAction.ToggleWebSearch) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("provider-web-search"),
                    )
                },
            )
        }

        ListGroup {
            ListRow(
                icon = Icons.Outlined.AutoAwesome,
                iconTone = ProductIconTone.BRAND,
                title = "Image generation",
                detail = state.imageModelId?.let { "Generate task images with $it." }
                    ?: "Choose a separate OpenRouter image model.",
                meta = if (state.imageGenerationEnabled) "Enabled" else "Off",
                showDivider = false,
                trailing = {
                    Switch(
                        checked = state.imageGenerationEnabled,
                        onCheckedChange = { onAction(ProviderSetupAction.ToggleImageGeneration) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("provider-image-generation"),
                    )
                },
            )
        }

        SecondaryAction(
            label = if (state.imageModelCatalogVisible) {
                "Hide image models"
            } else if (state.imageModelId == null) {
                "Choose image model"
            } else {
                "Change image model"
            },
            onClick = { onAction(ProviderSetupAction.ToggleImageModelCatalog) },
            enabled = !state.busy,
            icon = Icons.Outlined.AutoAwesome,
            modifier = Modifier.testTag("browse-image-models"),
        )

        if (state.imageModelCatalogVisible) {
            ImageModelCatalog(state, onAction)
        }

        OutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = { onAction(ProviderSetupAction.EditApiKey(it)) },
            enabled = !state.busy,
            label = { Text("OpenRouter API key") },
            placeholder = {
                Text(if (state.hasSavedApiKey) "Leave blank to keep saved key" else "Paste API key")
            },
            supportingText = {
                Text(
                    state.apiKeyError
                        ?: if (state.hasSavedApiKey) {
                            "A key is already encrypted with Android Keystore."
                        } else {
                            "Stored encrypted on this phone. It is never sent into Pi JavaScript."
                        },
                )
            },
            isError = state.apiKeyError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (state.revealApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(
                    onClick = { onAction(ProviderSetupAction.ToggleApiKeyVisibility) },
                    enabled = !state.busy,
                ) {
                    Icon(
                        if (state.revealApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.revealApiKey) "Hide API key" else "Show API key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("provider-api-key"),
        )

        state.notice?.let { notice ->
            val successful = notice.startsWith("Connection successful") || notice.startsWith("OpenRouter saved")
            StatusLine(
                title = if (successful) "Connection verified" else "Provider update",
                detail = notice,
                icon = if (successful) MomodingIcons.Permission else MomodingIcons.Retry,
                tone = if (successful) StatusLineTone.SUCCESS else StatusLineTone.INFO,
            )
        }

        SecondaryAction(
            label = if (state.operation == ProviderSetupOperation.TESTING) "Testing…" else "Test connection",
            onClick = { onAction(ProviderSetupAction.TestConnection) },
            enabled = !state.busy,
            leading = if (state.operation == ProviderSetupOperation.TESTING) {
                {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else null,
            modifier = Modifier.testTag("provider-test"),
        )
        val saveLabel = when {
            state.operation == ProviderSetupOperation.SAVING -> "Saving…"
            state.configured -> "Save changes"
            else -> "Save and continue"
        }
        val saveLeading: (@Composable () -> Unit)? = if (state.operation == ProviderSetupOperation.SAVING) {
            {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null
        val returnIsPrimary = onReturnToTask != null && state.canReturnToTask
        if (returnIsPrimary) {
            SecondaryAction(
                label = saveLabel,
                onClick = { onAction(ProviderSetupAction.Save) },
                enabled = !state.busy,
                leading = saveLeading,
                modifier = Modifier.testTag("provider-save"),
            )
        } else {
            PrimaryAction(
                label = saveLabel,
                onClick = { onAction(ProviderSetupAction.Save) },
                enabled = !state.busy,
                leading = saveLeading,
                modifier = Modifier.testTag("provider-save"),
            )
        }

        onReturnToTask?.let { returnToTask ->
            val label = when {
                state.canReturnToTask -> "Return to task"
                state.testedChangesNeedSave -> "Save verified changes to return"
                state.capabilityChangesNeedSave -> "Save capability changes to return"
                else -> "Test connection to return"
            }
            if (returnIsPrimary) {
                PrimaryAction(
                    label = label,
                    onClick = returnToTask,
                    enabled = true,
                    modifier = Modifier.testTag("provider-return-to-task"),
                )
            } else {
                SecondaryAction(
                    label = label,
                    onClick = returnToTask,
                    enabled = false,
                    modifier = Modifier.testTag("provider-return-to-task"),
                )
            }
        }

        if (state.configured) {
            DangerAction(
                label = "Remove Provider",
                onClick = { onAction(ProviderSetupAction.RequestDelete) },
                enabled = !state.busy,
                modifier = Modifier.testTag("provider-delete"),
            )
        }
        Text(
            "Your API key goes only to OpenRouter through Android’s native network layer. Tasks and file permissions stay on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        } else {
            CodexProviderForm(state, onAction, onReturnToTask)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderPicker(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
) {
    ListGroup {
        ListRow(
            icon = MomodingIcons.Permission,
            iconTone = ProductIconTone.BRAND,
            title = "OpenRouter",
            detail = "Use your own API key · search and image generation",
            meta = if (state.savedProfile != null) "Saved" else null,
            onClick = { onAction(ProviderSetupAction.SelectOpenRouter) },
            trailing = {
                RadioButton(
                    selected = state.activeChatProvider == ChatProviderKind.OPENROUTER,
                    onClick = { onAction(ProviderSetupAction.SelectOpenRouter) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("provider-choice-openrouter"),
                )
            },
        )
        ListRow(
            icon = MomodingIcons.Permission,
            iconTone = ProductIconTone.BRAND,
            title = "Codex",
            detail = "Sign in with ChatGPT · Codex chat and Android tools",
            meta = if (state.codexConnected) "Connected" else null,
            onClick = { onAction(ProviderSetupAction.SelectCodex) },
            showDivider = false,
            trailing = {
                RadioButton(
                    selected = state.activeChatProvider == ChatProviderKind.CODEX,
                    onClick = { onAction(ProviderSetupAction.SelectCodex) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("provider-choice-codex"),
                )
            },
        )
    }
}

@Composable
private fun CodexProviderForm(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
    onReturnToTask: (() -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val (title, detail, tone) = when (state.codexSignInState) {
        CodexSignInState.DISCONNECTED -> Triple(
            "Codex not connected",
            "Sign in with ChatGPT to use Codex directly from this phone.",
            StatusLineTone.INFO,
        )
        CodexSignInState.STARTING -> Triple(
            "Starting sign-in",
            "Requesting a one-time code from ChatGPT.",
            StatusLineTone.INFO,
        )
        CodexSignInState.WAITING_FOR_USER -> Triple(
            "Waiting for ChatGPT",
            "Complete authorization in your browser, then return to Momoding.",
            StatusLineTone.INFO,
        )
        CodexSignInState.CONNECTED -> Triple(
            "Codex connected",
            "New tasks use your ChatGPT account through the on-device Pi Agent.",
            StatusLineTone.SUCCESS,
        )
        CodexSignInState.ERROR -> Triple(
            "Sign-in needs attention",
            state.codexNotice ?: "Codex sign-in failed.",
            StatusLineTone.INFO,
        )
    }
    StatusLine(
        title = title,
        detail = detail,
        icon = if (state.codexConnected) MomodingIcons.Permission else MomodingIcons.Retry,
        tone = tone,
    )

    ListGroup {
        ListRow(
            icon = MomodingIcons.Permission,
            iconTone = ProductIconTone.BRAND,
            title = "Codex model",
            detail = state.codexModelId,
            meta = "Chat",
            showDivider = false,
        )
    }

    if (state.codexSignInState == CodexSignInState.WAITING_FOR_USER) {
        ListGroup {
            ListRow(
                title = "One-time code",
                detail = requireNotNull(state.codexUserCode),
                meta = state.codexPollSeconds?.let { "Checking in ${it}s" },
                showDivider = false,
                modifier = Modifier.testTag("codex-user-code"),
            )
        }
        PrimaryAction(
            label = "Open ChatGPT",
            onClick = {
                state.codexVerificationUri?.let(uriHandler::openUri)
            },
            enabled = state.codexVerificationUri != null,
            modifier = Modifier.testTag("codex-open-browser"),
        )
        SecondaryAction(
            label = "Cancel sign-in",
            onClick = { onAction(ProviderSetupAction.CancelCodexSignIn) },
            modifier = Modifier.testTag("codex-cancel-sign-in"),
        )
    } else if (!state.codexConnected) {
        PrimaryAction(
            label = if (state.codexSignInState == CodexSignInState.STARTING) {
                "Starting…"
            } else {
                "Connect Codex"
            },
            onClick = { onAction(ProviderSetupAction.StartCodexSignIn) },
            enabled = !state.busy,
            leading = if (state.codexSignInState == CodexSignInState.STARTING) {
                { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            } else null,
            modifier = Modifier.testTag("codex-connect"),
        )
    } else {
        onReturnToTask?.let { returnToTask ->
            PrimaryAction(
                label = "Return to task",
                onClick = returnToTask,
                enabled = state.canReturnToTask,
                modifier = Modifier.testTag("provider-return-to-task"),
            )
        }
        DangerAction(
            label = "Disconnect Codex",
            onClick = { onAction(ProviderSetupAction.DisconnectCodex) },
            enabled = !state.busy,
            modifier = Modifier.testTag("codex-disconnect"),
        )
    }

    state.codexNotice?.takeIf { state.codexSignInState != CodexSignInState.ERROR }?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "Codex currently enables chat and Android tools. Web search, image input, and image generation remain off until separately verified.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImageModelCatalog(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Image models", style = MaterialTheme.typography.titleMedium)
                Text(
                    "OpenRouter models verified to return images.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onAction(ProviderSetupAction.RefreshImageModels) },
                enabled = state.imageModelCatalogState != ProviderModelCatalogState.LOADING,
            ) { Icon(MomodingIcons.Retry, contentDescription = "Refresh image models") }
        }
        OutlinedTextField(
            value = state.imageModelSearch,
            onValueChange = { onAction(ProviderSetupAction.EditImageModelSearch(it)) },
            label = { Text("Search image models") },
            leadingIcon = { Icon(MomodingIcons.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("image-model-search"),
        )
        when (state.imageModelCatalogState) {
            ProviderModelCatalogState.IDLE,
            ProviderModelCatalogState.LOADING,
            -> Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Loading image models…", style = MaterialTheme.typography.bodySmall)
            }
            ProviderModelCatalogState.ERROR -> Column {
                Text(
                    state.imageModelCatalogError ?: "Image models are unavailable.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onAction(ProviderSetupAction.RefreshImageModels) }) {
                    Text("Try again")
                }
            }
            ProviderModelCatalogState.READY -> if (state.visibleImageModels.isEmpty()) {
                Text(
                    "No matching image models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.visibleImageModels.forEach { model ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(ProviderSetupAction.SelectImageModel(model.id)) }
                            .testTag("image-model-${model.id}")
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(model.id)
                                if ("image" in model.inputModalities) append(" · Image input")
                                if (model.supportsStreaming) append(" · Streaming")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCatalog(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Models", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose a model or keep typing a manual model ID above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onAction(ProviderSetupAction.RefreshModels) },
                enabled = state.modelCatalogState != ProviderModelCatalogState.LOADING,
            ) { Icon(MomodingIcons.Retry, contentDescription = "Refresh models") }
        }
        OutlinedTextField(
            value = state.modelSearch,
            onValueChange = { onAction(ProviderSetupAction.EditModelSearch(it)) },
            label = { Text("Search models") },
            leadingIcon = { Icon(MomodingIcons.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("model-search"),
        )
        when (state.modelCatalogState) {
            ProviderModelCatalogState.IDLE,
            ProviderModelCatalogState.LOADING,
            -> Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Loading models…", style = MaterialTheme.typography.bodySmall)
            }
            ProviderModelCatalogState.ERROR -> Column {
                Text(
                    state.modelCatalogError ?: "Models are unavailable.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onAction(ProviderSetupAction.RefreshModels) }) {
                    Text("Try again")
                }
            }
            ProviderModelCatalogState.READY -> if (state.visibleModels.isEmpty()) {
                Text(
                    "No matching models. You can still use a valid manual model ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.visibleModels.forEach { model ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAction(ProviderSetupAction.SelectModel(model.id)) }
                            .testTag("model-${model.id}")
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(model.id)
                                model.contextLength?.let { append(" · ").append(it).append(" context") }
                                if (model.supportsFunctionTools) append(" · Tools")
                                if (model.supportsImageInput) append(" · Image input")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHealthCard(state: ProviderSetupUiState) {
    val (title, detail) = when (state.health) {
        ProviderHealth.UNKNOWN -> "Checking Provider" to "Opening the saved configuration."
        ProviderHealth.MISSING -> "Provider not saved" to "Enter a model and API key to continue."
        ProviderHealth.SAVED -> "Provider saved" to "Test the connection before retrying a failed task."
        ProviderHealth.TESTING -> "Testing connection" to "Checking this model through OpenRouter."
        ProviderHealth.READY -> if (state.testedChangesNeedSave) {
            "Connection verified" to "Save these verified changes before returning to the task."
        } else {
            "Provider ready" to "This saved model and API key passed the connection test."
        }
        ProviderHealth.INVALID -> "Provider needs changes" to "Check the API key, model, permissions, or credits."
        ProviderHealth.RATE_LIMITED -> "OpenRouter is rate limited" to "Wait briefly, then test the connection again."
        ProviderHealth.UNAVAILABLE -> "Provider unavailable" to "Check the network or OpenRouter status, then try again."
    }
    val tone = when (state.health) {
        ProviderHealth.UNKNOWN, ProviderHealth.SAVED -> StatusLineTone.NEUTRAL
        ProviderHealth.MISSING, ProviderHealth.RATE_LIMITED -> StatusLineTone.WARNING
        ProviderHealth.TESTING -> StatusLineTone.INFO
        ProviderHealth.READY -> StatusLineTone.SUCCESS
        ProviderHealth.INVALID, ProviderHealth.UNAVAILABLE -> StatusLineTone.DANGER
    }
    val icon = when (state.health) {
        ProviderHealth.UNKNOWN, ProviderHealth.TESTING, ProviderHealth.SAVED -> MomodingIcons.Retry
        ProviderHealth.READY -> MomodingIcons.Permission
        ProviderHealth.MISSING,
        ProviderHealth.INVALID,
        ProviderHealth.RATE_LIMITED,
        ProviderHealth.UNAVAILABLE,
        -> MomodingIcons.Warning
    }
    StatusLine(
        title = title,
        detail = detail,
        icon = icon,
        tone = tone,
        modifier = Modifier.testTag("provider-health-${state.health.name.lowercase()}"),
    )
}

@Composable
private fun LoadingProvider() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text("Opening secure Provider settings…", modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ProviderLoadError(
    state: ProviderSetupUiState,
    onAction: (ProviderSetupAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MomodingMark(size = 52.dp)
        Text(
            "Provider unavailable",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 14.dp).semantics { heading() },
        )
        Text(
            state.notice ?: "The saved Provider could not be opened safely.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        PrimaryAction(
            label = "Try again",
            onClick = { onAction(ProviderSetupAction.RetryLoad) },
            modifier = Modifier.padding(top = 20.dp),
        )
        DangerAction(
            label = "Reset saved Provider",
            onClick = { onAction(ProviderSetupAction.RequestDelete) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
