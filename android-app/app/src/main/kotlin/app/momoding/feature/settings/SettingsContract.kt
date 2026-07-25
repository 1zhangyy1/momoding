package app.momoding.feature.settings

import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.auth.DeviceNamePolicy
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.diagnostics.DiagnosticsArchive
import app.momoding.core.transport.PinnedHostEndpoint
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.core.transport.SpkiPin

data class PairHostForm(
    val endpoint: String = "",
    val spkiPin: String = "",
    val pairingCode: String = "",
    val deviceName: String = "Android phone",
    val endpointError: String? = null,
    val spkiPinError: String? = null,
    val pairingCodeError: String? = null,
    val deviceNameError: String? = null,
)

sealed interface AgentProfileState {
    data object Loading : AgentProfileState
    data class Ready(val profile: HostClientProfile) : AgentProfileState
    data class Error(val message: String) : AgentProfileState
}

sealed interface DiagnosticsState {
    data object Idle : DiagnosticsState
    data object Exporting : DiagnosticsState
    data class Ready(val archive: DiagnosticsArchive) : DiagnosticsState
    data class Error(val message: String) : DiagnosticsState
}

data class SettingsUiState(
    val transport: SecureTransportUiStatus = SecureTransportUiStatus(),
    val agentProfile: AgentProfileState = AgentProfileState.Loading,
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val diagnostics: DiagnosticsState = DiagnosticsState.Idle,
    val pairForm: PairHostForm = PairHostForm(),
    val busyAction: String? = null,
    val notice: String? = null,
) {
    val showHostGate: Boolean
        get() = transport.phase in setOf(
            SecureTransportUiPhase.UNPAIRED,
            SecureTransportUiPhase.PAIRING,
            SecureTransportUiPhase.HOST_REVOKED,
            SecureTransportUiPhase.CREDENTIAL_LOST,
        )
}

sealed interface SettingsAction {
    data class TransportObserved(val status: SecureTransportUiStatus) : SettingsAction
    data class AppearanceObserved(val mode: AppearanceMode) : SettingsAction
    data class EditEndpoint(val value: String) : SettingsAction
    data class EditSpkiPin(val value: String) : SettingsAction
    data class EditPairingCode(val value: String) : SettingsAction
    data class EditDeviceName(val value: String) : SettingsAction
    data object SubmitPair : SettingsAction
    data object CancelPairing : SettingsAction
    data object RetryConnection : SettingsAction
    data object RetryAgentProfile : SettingsAction
    data object EnableRemoteHost : SettingsAction
    data object UnpairHost : SettingsAction
    data class SelectAppearance(val mode: AppearanceMode) : SettingsAction
    data object ExportDiagnostics : SettingsAction
    data object CancelDiagnosticsExport : SettingsAction
    data object ShareDiagnostics : SettingsAction
    data object DeleteDiagnostics : SettingsAction
    data object OpenSystemAccessibility : SettingsAction
    data class PairFinished(val notice: String?) : SettingsAction
    data class OperationFailed(val operation: String, val message: String) : SettingsAction
    data class DiagnosticsReady(val archive: DiagnosticsArchive) : SettingsAction
    data object DiagnosticsDeleted : SettingsAction
    data object DiagnosticsCancelled : SettingsAction
    data object ClearNotice : SettingsAction
}

sealed interface SettingsEffect {
    data class Pair(val form: PairHostForm) : SettingsEffect
    data object CancelPairing : SettingsEffect
    data object RetryConnection : SettingsEffect
    data object RefreshProfile : SettingsEffect
    data object EnableRemoteHost : SettingsEffect
    data object Unpair : SettingsEffect
    data class SaveAppearance(val mode: AppearanceMode) : SettingsEffect
    data object ExportDiagnostics : SettingsEffect
    data object CancelDiagnosticsExport : SettingsEffect
    data class ShareDiagnostics(val archive: DiagnosticsArchive) : SettingsEffect
    data class DeleteDiagnostics(val archive: DiagnosticsArchive) : SettingsEffect
    data object OpenSystemAccessibility : SettingsEffect
}

data class SettingsReduction(
    val state: SettingsUiState,
    val effect: SettingsEffect? = null,
)

object SettingsReducer {
    fun reduce(state: SettingsUiState, action: SettingsAction): SettingsReduction = when (action) {
        is SettingsAction.TransportObserved -> SettingsReduction(
            state.copy(
                transport = action.status,
                agentProfile = action.status.profile?.let(AgentProfileState::Ready)
                    ?: if (action.status.phase == SecureTransportUiPhase.READY) {
                        AgentProfileState.Error("Host profile is unavailable")
                    } else {
                        AgentProfileState.Loading
                    },
            ),
        )
        is SettingsAction.AppearanceObserved -> SettingsReduction(state.copy(appearance = action.mode))
        is SettingsAction.EditEndpoint -> SettingsReduction(state.copy(pairForm = state.pairForm.copy(endpoint = action.value, endpointError = null)))
        is SettingsAction.EditSpkiPin -> SettingsReduction(state.copy(pairForm = state.pairForm.copy(spkiPin = action.value, spkiPinError = null)))
        is SettingsAction.EditPairingCode -> SettingsReduction(state.copy(pairForm = state.pairForm.copy(pairingCode = action.value, pairingCodeError = null)))
        is SettingsAction.EditDeviceName -> SettingsReduction(state.copy(pairForm = state.pairForm.copy(deviceName = action.value, deviceNameError = null)))
        SettingsAction.SubmitPair -> validatePairForm(state)
        SettingsAction.CancelPairing -> SettingsReduction(state.copy(busyAction = "cancel-pairing"), SettingsEffect.CancelPairing)
        SettingsAction.RetryConnection -> SettingsReduction(state.copy(busyAction = "retry-connection"), SettingsEffect.RetryConnection)
        SettingsAction.RetryAgentProfile -> SettingsReduction(state.copy(agentProfile = AgentProfileState.Loading), SettingsEffect.RefreshProfile)
        SettingsAction.EnableRemoteHost -> SettingsReduction(state, SettingsEffect.EnableRemoteHost)
        SettingsAction.UnpairHost -> SettingsReduction(state.copy(busyAction = "unpair"), SettingsEffect.Unpair)
        is SettingsAction.SelectAppearance -> SettingsReduction(state.copy(appearance = action.mode), SettingsEffect.SaveAppearance(action.mode))
        SettingsAction.ExportDiagnostics -> SettingsReduction(state.copy(diagnostics = DiagnosticsState.Exporting), SettingsEffect.ExportDiagnostics)
        SettingsAction.CancelDiagnosticsExport -> SettingsReduction(state, SettingsEffect.CancelDiagnosticsExport)
        SettingsAction.ShareDiagnostics -> {
            val ready = state.diagnostics as? DiagnosticsState.Ready
            SettingsReduction(state, ready?.let { SettingsEffect.ShareDiagnostics(it.archive) })
        }
        SettingsAction.DeleteDiagnostics -> {
            val ready = state.diagnostics as? DiagnosticsState.Ready
            SettingsReduction(state, ready?.let { SettingsEffect.DeleteDiagnostics(it.archive) })
        }
        SettingsAction.OpenSystemAccessibility -> SettingsReduction(state, SettingsEffect.OpenSystemAccessibility)
        is SettingsAction.PairFinished -> SettingsReduction(
            state.copy(
                busyAction = null,
                notice = action.notice,
                pairForm = state.pairForm.copy(pairingCode = ""),
            ),
        )
        is SettingsAction.OperationFailed -> SettingsReduction(
            state.copy(
                busyAction = null,
                notice = action.message,
                pairForm = if (action.operation == "pair") state.pairForm.copy(pairingCode = "") else state.pairForm,
                diagnostics = if (action.operation == "diagnostics") DiagnosticsState.Error(action.message) else state.diagnostics,
            ),
        )
        is SettingsAction.DiagnosticsReady -> SettingsReduction(state.copy(diagnostics = DiagnosticsState.Ready(action.archive)))
        SettingsAction.DiagnosticsDeleted -> SettingsReduction(state.copy(diagnostics = DiagnosticsState.Idle))
        SettingsAction.DiagnosticsCancelled -> SettingsReduction(state.copy(diagnostics = DiagnosticsState.Idle))
        SettingsAction.ClearNotice -> SettingsReduction(state.copy(notice = null))
    }

    private fun validatePairForm(state: SettingsUiState): SettingsReduction {
        val form = state.pairForm
        val endpointError = runCatching { PinnedHostEndpoint.parse(form.endpoint) }.exceptionOrNull()?.let { "Use a canonical https://host:port endpoint" }
        val pinError = runCatching { SpkiPin.parse(form.spkiPin) }.exceptionOrNull()?.let { "Paste the exact sha256/… Host pin" }
        val codeError = if (PAIRING_CODE.matches(form.pairingCode)) null else "Use the 20–64 character Host pairing code"
        val nameError = runCatching { DeviceNamePolicy.canonicalize(form.deviceName) }.exceptionOrNull()?.let { "Enter a valid device name" }
        val checked = form.copy(
            endpointError = endpointError,
            spkiPinError = pinError,
            pairingCodeError = codeError,
            deviceNameError = nameError,
        )
        return if (listOf(endpointError, pinError, codeError, nameError).all { it == null }) {
            SettingsReduction(state.copy(pairForm = checked, busyAction = "pair"), SettingsEffect.Pair(checked))
        } else {
            SettingsReduction(state.copy(pairForm = checked))
        }
    }

    private val PAIRING_CODE = Regex("^[A-Z2-9]{20,64}$")
}
