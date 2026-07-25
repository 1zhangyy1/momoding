package app.momoding.feature.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

enum class SettingsInteraction(
    val controlId: String,
    val defaultContractAction: String,
) {
    PAIR_HOST("PAIR_HOST", "PairHost"),
    CANCEL_PAIRING("CANCEL_PAIRING", "CancelPairing"),
    CHANGE_THEME("CHANGE_THEME", "ChangeTheme"),
    RETRY_CONNECTION("RETRY_CONNECTION", "RetryConnection"),
    UNPAIR_HOST("UNPAIR_HOST", "UnpairHost"),
    RETRY_AGENT_DEFAULTS("RETRY_AGENT_DEFAULTS", "RetryAgentDefaults"),
    SELECT_SYSTEM("SELECT_SYSTEM", "SelectSystem"),
    SELECT_LIGHT("SELECT_LIGHT", "SelectLight"),
    SELECT_DARK("SELECT_DARK", "SelectDark"),
    OPEN_SYSTEM_ACCESSIBILITY("OPEN_SYSTEM_ACCESSIBILITY", "OpenSystemAccessibility"),
    EXPORT_DIAGNOSTICS("EXPORT_DIAGNOSTICS", "ExportDiagnostics"),
    CANCEL_DIAGNOSTICS_EXPORT("CANCEL_DIAGNOSTICS_EXPORT", "CancelDiagnosticsExport"),
    SHARE_DIAGNOSTICS("SHARE_DIAGNOSTICS", "ShareDiagnostics"),
    DELETE_DIAGNOSTICS_ARCHIVE("DELETE_DIAGNOSTICS_ARCHIVE", "DeleteDiagnosticsArchive"),
    OPEN_VERSION_DETAILS("OPEN_VERSION_DETAILS", "OpenVersionDetails"),
    OPEN_MOBILE_FILES("OPEN_MOBILE_FILES", "OpenMobileFiles"),
    OPEN_DEVICE_CAPABILITIES("OPEN_DEVICE_CAPABILITIES", "OpenDeviceCapabilities"),
    OPEN_EXTENSIONS("OPEN_EXTENSIONS", "OpenExtensions"),
}

class SettingsInteractionPolicy private constructor(
    private val bindings: Map<SettingsInteraction, String>,
) {
    fun contractAction(interaction: SettingsInteraction): String? = bindings[interaction]

    fun allows(interaction: SettingsInteraction): Boolean = interaction in bindings

    companion object {
        val All = SettingsInteractionPolicy(
            SettingsInteraction.entries.associateWith(SettingsInteraction::defaultContractAction),
        )

        fun fromControlBindings(controlBindings: Map<String, String>): SettingsInteractionPolicy {
            val interactionsById = SettingsInteraction.entries.associateBy(SettingsInteraction::controlId)
            require(controlBindings.keys.all(interactionsById::containsKey)) {
                "interaction policy contains an unknown control"
            }
            require(controlBindings.values.all(String::isNotBlank)) {
                "interaction policy contains a blank contract action"
            }
            return SettingsInteractionPolicy(
                controlBindings.mapKeys { (controlId, _) -> interactionsById.getValue(controlId) },
            )
        }
    }
}

val ContractActionKey = SemanticsPropertyKey<String>("ContractAction")
var SemanticsPropertyReceiver.contractAction by ContractActionKey
val StructuralActionKey = SemanticsPropertyKey<String>("StructuralAction")
var SemanticsPropertyReceiver.structuralAction by StructuralActionKey

fun Modifier.contractAction(
    policy: SettingsInteractionPolicy,
    interaction: SettingsInteraction,
): Modifier = policy.contractAction(interaction)?.let { action ->
    semantics { contractAction = action }
} ?: this

fun Modifier.structuralAction(action: String): Modifier = semantics {
    structuralAction = action
}
