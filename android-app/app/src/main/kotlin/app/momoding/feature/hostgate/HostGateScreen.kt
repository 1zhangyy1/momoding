package app.momoding.feature.hostgate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.appearance.AppearanceMode
import app.momoding.feature.settings.SettingsAction
import app.momoding.feature.settings.SettingsInteraction
import app.momoding.feature.settings.SettingsInteractionPolicy
import app.momoding.feature.settings.SettingsUiState
import app.momoding.feature.settings.contractAction
import app.momoding.feature.settings.structuralAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostGateScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    interactionPolicy: SettingsInteractionPolicy = SettingsInteractionPolicy.All,
    onOpenMobileFiles: (() -> Unit)? = null,
) {
    val pairingInProgress = state.transport.phase == SecureTransportUiPhase.PAIRING
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (state.transport.phase) {
                SecureTransportUiPhase.HOST_REVOKED -> "Host access revoked"
                SecureTransportUiPhase.CREDENTIAL_LOST -> "Host credential unavailable"
                else -> "Connect your Pi Host"
            },
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                state.transport.phase == SecureTransportUiPhase.STARTING -> "Checking this phone’s saved Host binding…"
                state.transport.phase == SecureTransportUiPhase.HOST_REVOKED -> "The Host rejected this device credential. Clear the binding, then pair again."
                state.transport.phase == SecureTransportUiPhase.CREDENTIAL_LOST -> "The local secure credential is missing. Clear the binding, then pair again."
                state.transport.pairingRecovery -> "A previous request may have reached the Host. Generate a new code and retry with the same endpoint and pin."
                else -> "Run the self-hosted Host, then paste its exact endpoint, leaf pin, and one-time pairing code."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        state.notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.transport.phase == SecureTransportUiPhase.STARTING) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        if (state.transport.phase in setOf(SecureTransportUiPhase.HOST_REVOKED, SecureTransportUiPhase.CREDENTIAL_LOST)) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onAction(SettingsAction.UnpairHost) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("action-PairHost")
                    .contractAction(interactionPolicy, SettingsInteraction.PAIR_HOST),
                enabled = state.busyAction == null && interactionPolicy.allows(SettingsInteraction.PAIR_HOST),
            ) { Text("Clear binding and pair again") }
            HostGateUtilities(
                state = state,
                onAction = onAction,
                interactionPolicy = interactionPolicy,
                allowTheme = false,
                allowExport = true,
                onOpenMobileFiles = onOpenMobileFiles,
            )
            return@Column
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = state.pairForm.endpoint,
            onValueChange = { onAction(SettingsAction.EditEndpoint(it)) },
            label = { Text("Host endpoint") },
            supportingText = state.pairForm.endpointError?.let { { Text(it) } },
            isError = state.pairForm.endpointError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().structuralAction("PairHostEndpointField"),
            enabled = state.busyAction == null && !pairingInProgress,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.pairForm.spkiPin,
            onValueChange = { onAction(SettingsAction.EditSpkiPin(it)) },
            label = { Text("Leaf SPKI pin") },
            supportingText = state.pairForm.spkiPinError?.let { { Text(it) } },
            isError = state.pairForm.spkiPinError != null,
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().structuralAction("PairHostPinField"),
            enabled = state.busyAction == null && !pairingInProgress,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.pairForm.pairingCode,
            onValueChange = { onAction(SettingsAction.EditPairingCode(it.uppercase())) },
            label = { Text("Pairing code") },
            supportingText = state.pairForm.pairingCodeError?.let { { Text(it) } },
            isError = state.pairForm.pairingCodeError != null,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Pairing code"
                    password()
                }
                .structuralAction("PairHostCodeField"),
            enabled = state.busyAction == null && !pairingInProgress,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.pairForm.deviceName,
            onValueChange = { onAction(SettingsAction.EditDeviceName(it)) },
            label = { Text("Device name") },
            supportingText = state.pairForm.deviceNameError?.let { { Text(it) } },
            isError = state.pairForm.deviceNameError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().structuralAction("PairHostDeviceNameField"),
            enabled = state.busyAction == null && !pairingInProgress,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busyAction == "pair" || state.transport.phase == SecureTransportUiPhase.PAIRING) {
                OutlinedButton(
                    onClick = { onAction(SettingsAction.CancelPairing) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("action-CancelPairing")
                        .contractAction(interactionPolicy, SettingsInteraction.CANCEL_PAIRING),
                    enabled = interactionPolicy.allows(SettingsInteraction.CANCEL_PAIRING),
                ) { Text(if (state.transport.pairingRecovery) "Stop waiting" else "Cancel") }
            }
            if (interactionPolicy.allows(SettingsInteraction.PAIR_HOST)) {
                Button(
                    onClick = { onAction(SettingsAction.SubmitPair) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("action-PairHost")
                        .contractAction(interactionPolicy, SettingsInteraction.PAIR_HOST),
                    enabled = state.busyAction == null,
                ) { Text(if (state.transport.pairingRecovery) "Retry with new code" else "Pair Host") }
            }
        }
        HostGateUtilities(
            state,
            onAction,
            interactionPolicy,
            allowTheme = true,
            allowExport = state.transport.phase != SecureTransportUiPhase.PAIRING,
            onOpenMobileFiles = onOpenMobileFiles,
        )
    }
}

@Composable
private fun HostGateUtilities(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    interactionPolicy: SettingsInteractionPolicy,
    allowTheme: Boolean,
    allowExport: Boolean,
    onOpenMobileFiles: (() -> Unit)?,
) {
    val canChangeTheme = allowTheme && interactionPolicy.allows(SettingsInteraction.CHANGE_THEME)
    val canExport = allowExport && interactionPolicy.allows(SettingsInteraction.EXPORT_DIAGNOSTICS)
    val canManageFiles = onOpenMobileFiles != null &&
        interactionPolicy.allows(SettingsInteraction.OPEN_MOBILE_FILES)
    if (!canChangeTheme && !canExport && !canManageFiles) return
    Spacer(Modifier.height(20.dp))
    if (canChangeTheme) {
        val next = when (state.appearance) {
            AppearanceMode.SYSTEM -> AppearanceMode.LIGHT
            AppearanceMode.LIGHT -> AppearanceMode.DARK
            AppearanceMode.DARK -> AppearanceMode.SYSTEM
        }
        OutlinedButton(
            onClick = { onAction(SettingsAction.SelectAppearance(next)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("action-ChangeTheme")
            .contractAction(interactionPolicy, SettingsInteraction.CHANGE_THEME),
        ) { Text("Appearance: ${state.appearance.name.lowercase().replaceFirstChar(Char::uppercase)}") }
    }
    if (canManageFiles) {
        if (canChangeTheme) Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = requireNotNull(onOpenMobileFiles),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("action-OpenMobileFiles")
                .contractAction(interactionPolicy, SettingsInteraction.OPEN_MOBILE_FILES),
        ) { Text("Manage mobile files") }
    }
    if (canExport) {
        if (canChangeTheme || canManageFiles) Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onAction(SettingsAction.ExportDiagnostics) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("action-ExportDiagnostics")
                .contractAction(interactionPolicy, SettingsInteraction.EXPORT_DIAGNOSTICS),
        ) { Text("Export diagnostics") }
    }
}
