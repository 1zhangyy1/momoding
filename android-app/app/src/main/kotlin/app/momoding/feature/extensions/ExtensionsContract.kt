package app.momoding.feature.extensions

import android.net.Uri
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillSource
import app.momoding.core.extensions.ExtensionToolSnapshot
import app.momoding.core.extensions.ExtensionHostToolSnapshot
import app.momoding.core.extensions.ExtensionPackageCompatibilityDiagnostic
import app.momoding.core.extensions.PiRegisterToolHttpPolicy

data class ExtensionPackageUiState(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val runtime: String,
    val entrypoint: String?,
    val enabled: Boolean,
    val fileCount: Int,
    val tools: List<ExtensionToolSnapshot>,
    val requiredCapabilities: List<String>,
    val optionalCapabilities: List<String>,
    val networkOrigins: List<String>,
    val hostTools: List<ExtensionHostToolSnapshot>,
    val httpPolicy: PiRegisterToolHttpPolicy,
    val packageDigest: String,
    val activationAvailable: Boolean,
    val credentialBindings: List<ExtensionCredentialUiState> = emptyList(),
    val expanded: Boolean = false,
)

data class ExtensionCredentialUiState(
    val packageId: String,
    val packageDigest: String,
    val slot: String,
    val origin: String,
    val placement: String,
    val bound: Boolean,
)

data class ExtensionSkillUiState(
    val name: String,
    val description: String,
    val source: SkillSource,
    val enabled: Boolean,
    val availability: SkillAvailability,
    val diagnosticMessage: String?,
    val packageFileCount: Int = 1,
    val diagnosticCode: String? = null,
)

data class ExtensionsUiState(
    val loading: Boolean = true,
    val skills: List<ExtensionSkillUiState> = emptyList(),
    val busySkillName: String? = null,
    val importing: Boolean = false,
    val pendingDeleteSkillName: String? = null,
    val extensionPackages: List<ExtensionPackageUiState> = emptyList(),
    val packagesLoading: Boolean = true,
    val importingExtensionPackage: Boolean = false,
    val busyExtensionPackageId: String? = null,
    val pendingDeleteExtensionPackageId: String? = null,
    val pendingCredentialBinding: ExtensionCredentialUiState? = null,
    val busyCredentialBindingKey: String? = null,
    val notice: String? = null,
    val noticeRecovery: ExtensionsNoticeRecovery? = null,
    val compatibilityDiagnostic: ExtensionPackageCompatibilityDiagnostic? = null,
)

enum class ExtensionsNoticeRecoveryKind {
    ADD_SINGLE_SKILL,
    ADD_SKILL_FOLDER,
    ADD_EXTENSION_FOLDER,
    REVIEW_EXTENSION,
    ADD_CREDENTIAL,
    REMOVE_CREDENTIAL,
    CLEAN_PACKAGE_CREDENTIALS,
    REMOVE_EXTENSION,
}

data class ExtensionsNoticeRecovery(
    val kind: ExtensionsNoticeRecoveryKind,
    val packageId: String? = null,
    val slot: String? = null,
    val origin: String? = null,
)

sealed interface ExtensionsAction {
    data object Back : ExtensionsAction
    data object ImportSkill : ExtensionsAction
    data object ImportSkillPackage : ExtensionsAction
    data object ImportExtensionPackage : ExtensionsAction
    data class ImportFinished(val uri: Uri?) : ExtensionsAction
    data class PackageImportFinished(val uri: Uri?) : ExtensionsAction
    data class ExtensionPackageImportFinished(val uri: Uri?) : ExtensionsAction
    data class SetSkillEnabled(val name: String, val enabled: Boolean) : ExtensionsAction
    data class RequestDeleteSkill(val name: String) : ExtensionsAction
    data object CancelDeleteSkill : ExtensionsAction
    data object ConfirmDeleteSkill : ExtensionsAction
    data class ToggleExtensionPackageDetails(val packageId: String) : ExtensionsAction
    data class SetExtensionPackageEnabled(val packageId: String, val enabled: Boolean) : ExtensionsAction
    data class RequestDeleteExtensionPackage(val packageId: String) : ExtensionsAction
    data object CancelDeleteExtensionPackage : ExtensionsAction
    data object ConfirmDeleteExtensionPackage : ExtensionsAction
    data class RequestBindCredential(
        val packageId: String,
        val slot: String,
        val origin: String,
    ) : ExtensionsAction
    data class BindCredential(val bearerToken: String) : ExtensionsAction
    data object CancelBindCredential : ExtensionsAction
    data class RemoveCredential(
        val packageId: String,
        val slot: String,
        val origin: String,
    ) : ExtensionsAction
    data object UseNoticeRecovery : ExtensionsAction
    data object ClearNotice : ExtensionsAction
}

sealed interface ExtensionsOneShot {
    data object Back : ExtensionsOneShot
    data object LaunchSkillPicker : ExtensionsOneShot
    data object LaunchSkillPackagePicker : ExtensionsOneShot
    data object LaunchExtensionPackagePicker : ExtensionsOneShot
}
