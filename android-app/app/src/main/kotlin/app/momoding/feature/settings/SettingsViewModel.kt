package app.momoding.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.appearance.AppearanceStore
import app.momoding.core.diagnostics.DiagnosticsArchive
import app.momoding.core.diagnostics.DiagnosticsExporter
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.PairingCancelResult
import app.momoding.core.transport.PairingCancelledException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SettingsOneShot {
    data class ShareDiagnostics(val archive: DiagnosticsArchive) : SettingsOneShot
    data object OpenSystemAccessibility : SettingsOneShot
}

class SettingsViewModel(
    private val runtime: AndroidSecureTransportRuntime,
    private val appearanceStore: AppearanceStore,
    private val diagnosticsExporter: DiagnosticsExporter,
    enableRemoteHost: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val mutableOneShots = MutableSharedFlow<SettingsOneShot>(extraBufferCapacity = 1)
    val oneShots: SharedFlow<SettingsOneShot> = mutableOneShots.asSharedFlow()
    private var diagnosticsJob: Job? = null
    private var remoteHostEnabled = false

    init {
        viewModelScope.launch { appearanceStore.mode.collect { dispatch(SettingsAction.AppearanceObserved(it)) } }
        if (enableRemoteHost) enableRemoteHost()
    }

    fun dispatch(action: SettingsAction) {
        val reduction = SettingsReducer.reduce(mutableState.value, action)
        mutableState.value = reduction.state
        reduction.effect?.let(::execute)
    }

    private fun execute(effect: SettingsEffect) {
        if (effect == SettingsEffect.EnableRemoteHost) {
            enableRemoteHost()
            return
        }
        if (effect == SettingsEffect.CancelDiagnosticsExport) {
            diagnosticsJob?.cancel()
            diagnosticsJob = null
            dispatch(SettingsAction.DiagnosticsCancelled)
            return
        }
        if (effect == SettingsEffect.ExportDiagnostics) {
            diagnosticsJob?.cancel()
            diagnosticsJob = viewModelScope.launch {
                try {
                    dispatch(SettingsAction.DiagnosticsReady(diagnosticsExporter.export(runtime.uiStatus.value)))
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    dispatch(SettingsAction.OperationFailed("diagnostics", "Could not create diagnostics."))
                } finally {
                    diagnosticsJob = null
                }
            }
            return
        }
        viewModelScope.launch {
            when (effect) {
                is SettingsEffect.Pair -> runCatching {
                    runtime.pair(
                        effect.form.endpoint,
                        effect.form.spkiPin,
                        effect.form.pairingCode,
                        effect.form.deviceName,
                    )
                }.fold(
                    onSuccess = { dispatch(SettingsAction.PairFinished(null)) },
                    onFailure = { error ->
                        val notice = if (error is PairingCancelledException && error.responseMayHaveReachedHost) {
                            "Stopped waiting. Generate a new Host code to recover the same device identity."
                        } else {
                            sanitized(error)
                        }
                        dispatch(SettingsAction.OperationFailed("pair", notice))
                    },
                )
                SettingsEffect.CancelPairing -> runCatching { runtime.cancelPairing() }.fold(
                    onSuccess = { result ->
                        val notice = when (result) {
                            PairingCancelResult.COMMIT_WON -> "Pairing already committed; use Unpair Host if needed."
                            PairingCancelResult.CANCEL_WON_BEFORE_SEND -> "Pairing cancelled before contacting the Host."
                            PairingCancelResult.CANCEL_WON_AFTER_SEND -> "Stopped waiting. Generate a new Host code to recover this device identity."
                            PairingCancelResult.NO_ATTEMPT -> null
                        }
                        dispatch(SettingsAction.PairFinished(notice))
                    },
                    onFailure = { dispatch(SettingsAction.OperationFailed("cancel-pairing", sanitized(it))) },
                )
                SettingsEffect.RetryConnection -> runCatching { runtime.retryConnection() }.fold(
                    onSuccess = { dispatch(SettingsAction.PairFinished(null)) },
                    onFailure = { dispatch(SettingsAction.OperationFailed("retry", sanitized(it))) },
                )
                SettingsEffect.RefreshProfile -> runCatching { runtime.refreshProfile() }.fold(
                    onSuccess = { dispatch(SettingsAction.PairFinished(null)) },
                    onFailure = { dispatch(SettingsAction.OperationFailed("profile", sanitized(it))) },
                )
                SettingsEffect.Unpair -> runCatching { runtime.unpair() }.fold(
                    onSuccess = { dispatch(SettingsAction.PairFinished("Host removed from this phone.")) },
                    onFailure = { dispatch(SettingsAction.OperationFailed("unpair", sanitized(it))) },
                )
                is SettingsEffect.SaveAppearance -> runCatching { appearanceStore.set(effect.mode) }
                    .onFailure { dispatch(SettingsAction.OperationFailed("appearance", "Could not save appearance.")) }
                SettingsEffect.ExportDiagnostics -> Unit
                SettingsEffect.CancelDiagnosticsExport -> Unit
                is SettingsEffect.ShareDiagnostics -> mutableOneShots.emit(SettingsOneShot.ShareDiagnostics(effect.archive))
                is SettingsEffect.DeleteDiagnostics -> runCatching { diagnosticsExporter.delete(effect.archive) }.fold(
                    onSuccess = { dispatch(SettingsAction.DiagnosticsDeleted) },
                    onFailure = { dispatch(SettingsAction.OperationFailed("diagnostics", "Could not delete diagnostics.")) },
                )
                SettingsEffect.OpenSystemAccessibility -> mutableOneShots.emit(SettingsOneShot.OpenSystemAccessibility)
                SettingsEffect.EnableRemoteHost -> Unit
            }
        }
    }

    private fun enableRemoteHost() {
        if (remoteHostEnabled) return
        remoteHostEnabled = true
        viewModelScope.launch {
            runtime.uiStatus.collect { dispatch(SettingsAction.TransportObserved(it)) }
        }
        viewModelScope.launch {
            runCatching { runtime.reconcileStartup() }
                .onFailure { dispatch(SettingsAction.OperationFailed("startup", sanitized(it))) }
        }
    }

    private fun sanitized(error: Throwable): String = when (error) {
        is IllegalArgumentException -> "Check the visible Host details and try again."
        is IOException -> "Could not reach the Host. Your local details are retained."
        else -> "The operation could not be completed safely."
    }

    class Factory(
        private val runtime: AndroidSecureTransportRuntime,
        private val appearanceStore: AppearanceStore,
        private val diagnosticsExporter: DiagnosticsExporter,
        private val enableRemoteHost: Boolean = true,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                runtime,
                appearanceStore,
                diagnosticsExporter,
                enableRemoteHost,
            ) as T
    }
}
