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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.momoding.core.provider.ProviderProfilePolicy
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

        ListGroup {
            ListRow(
                icon = MomodingIcons.Permission,
                iconTone = ProductIconTone.BRAND,
                title = "OpenRouter",
                detail = ProviderProfilePolicy.OPENROUTER_BASE_URL,
                showDivider = false,
            )
        }

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
        Spacer(Modifier.height(8.dp))
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
                                if ("image" in model.inputModalities) append(" · Images")
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
