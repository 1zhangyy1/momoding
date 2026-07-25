package app.momoding.feature.settings

import app.momoding.core.appearance.AppearanceMode
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.diagnostics.DiagnosticsArchive
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {
    @Test
    fun invalidPairFormFailsLocallyWithoutEffect() {
        val reduction = SettingsReducer.reduce(
            SettingsUiState(
                transport = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED),
                pairForm = PairHostForm("http://host", "wrong", "short", ""),
            ),
            SettingsAction.SubmitPair,
        )

        assertNull(reduction.effect)
        assertTrue(reduction.state.pairForm.endpointError != null)
        assertTrue(reduction.state.pairForm.spkiPinError != null)
        assertTrue(reduction.state.pairForm.pairingCodeError != null)
        assertTrue(reduction.state.pairForm.deviceNameError != null)
    }

    @Test
    fun validPairFormProducesOneTypedEffect() {
        val form = PairHostForm(
            endpoint = "https://host.example:8443",
            spkiPin = "sha256/BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
            pairingCode = "ABCDEFGHJKLMNPQRSTUV",
            deviceName = "Android phone",
        )
        val reduction = SettingsReducer.reduce(SettingsUiState(pairForm = form), SettingsAction.SubmitPair)

        assertEquals(SettingsEffect.Pair(form), reduction.effect)
        assertEquals("pair", reduction.state.busyAction)
    }

    @Test
    fun transportProjectionKeepsProfileReadOnlyAndDistinguishesRecoveryStates() {
        val profile = HostClientProfile("Mac Studio", "OpenRouter", "model", "default", false, 9)
        val connected = SettingsReducer.reduce(
            SettingsUiState(),
            SettingsAction.TransportObserved(
                SecureTransportUiStatus(SecureTransportUiPhase.READY, "Mac Studio", profile, 9),
            ),
        ).state
        assertEquals(AgentProfileState.Ready(profile), connected.agentProfile)
        assertFalse(connected.showHostGate)

        listOf(
            SecureTransportUiPhase.UNPAIRED,
            SecureTransportUiPhase.PAIRING,
            SecureTransportUiPhase.HOST_REVOKED,
            SecureTransportUiPhase.CREDENTIAL_LOST,
        ).forEach { phase ->
            val state = SettingsReducer.reduce(
                connected,
                SettingsAction.TransportObserved(SecureTransportUiStatus(phase)),
            ).state
            assertTrue("$phase must use HostGate", state.showHostGate)
        }
        assertFalse(
            SettingsReducer.reduce(
                connected,
                SettingsAction.TransportObserved(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE)),
            ).state.showHostGate,
        )
    }

    @Test
    fun appearanceAndDiagnosticsHaveExplicitEffectsAndTerminalStates() {
        val appearance = SettingsReducer.reduce(
            SettingsUiState(),
            SettingsAction.SelectAppearance(AppearanceMode.DARK),
        )
        assertEquals(AppearanceMode.DARK, appearance.state.appearance)
        assertEquals(SettingsEffect.SaveAppearance(AppearanceMode.DARK), appearance.effect)

        val exporting = SettingsReducer.reduce(appearance.state, SettingsAction.ExportDiagnostics)
        assertEquals(DiagnosticsState.Exporting, exporting.state.diagnostics)
        assertEquals(SettingsEffect.ExportDiagnostics, exporting.effect)

        val cancel = SettingsReducer.reduce(exporting.state, SettingsAction.CancelDiagnosticsExport)
        assertEquals(SettingsEffect.CancelDiagnosticsExport, cancel.effect)
        val cancelled = SettingsReducer.reduce(cancel.state, SettingsAction.DiagnosticsCancelled)
        assertEquals(DiagnosticsState.Idle, cancelled.state.diagnostics)

        val archive = DiagnosticsArchive("diagnostics-00000000-0000-4000-8000-000000000000.zip", 20)
        val ready = SettingsReducer.reduce(cancelled.state, SettingsAction.DiagnosticsReady(archive)).state
        assertEquals(SettingsEffect.ShareDiagnostics(archive), SettingsReducer.reduce(ready, SettingsAction.ShareDiagnostics).effect)
        assertEquals(SettingsEffect.DeleteDiagnostics(archive), SettingsReducer.reduce(ready, SettingsAction.DeleteDiagnostics).effect)
    }

    @Test
    fun failedPairClearsCodeButRetainsNonSecretFields() {
        val before = SettingsUiState(
            pairForm = PairHostForm(
                endpoint = "https://host.example:8443",
                spkiPin = "sha256/BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
                pairingCode = "ABCDEFGHJKLMNPQRSTUV",
                deviceName = "Android phone",
            ),
            busyAction = "pair",
        )
        val after = SettingsReducer.reduce(
            before,
            SettingsAction.OperationFailed("pair", "Could not reach the Host."),
        ).state

        assertEquals("", after.pairForm.pairingCode)
        assertEquals(before.pairForm.endpoint, after.pairForm.endpoint)
        assertEquals(before.pairForm.spkiPin, after.pairForm.spkiPin)
        assertEquals(before.pairForm.deviceName, after.pairForm.deviceName)
        assertNull(after.busyAction)
    }

}
