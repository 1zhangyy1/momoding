package app.momoding.feature.extensions

import android.net.Uri
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillSource

data class ExtensionSkillUiState(
    val name: String,
    val description: String,
    val source: SkillSource,
    val enabled: Boolean,
    val availability: SkillAvailability,
    val diagnosticMessage: String?,
)

data class ExtensionsUiState(
    val loading: Boolean = true,
    val skills: List<ExtensionSkillUiState> = emptyList(),
    val busySkillName: String? = null,
    val importing: Boolean = false,
    val pendingDeleteSkillName: String? = null,
    val notice: String? = null,
)

sealed interface ExtensionsAction {
    data object Back : ExtensionsAction
    data object ImportSkill : ExtensionsAction
    data class ImportFinished(val uri: Uri?) : ExtensionsAction
    data class SetSkillEnabled(val name: String, val enabled: Boolean) : ExtensionsAction
    data class RequestDeleteSkill(val name: String) : ExtensionsAction
    data object CancelDeleteSkill : ExtensionsAction
    data object ConfirmDeleteSkill : ExtensionsAction
    data object ClearNotice : ExtensionsAction
}

sealed interface ExtensionsOneShot {
    data object Back : ExtensionsOneShot
    data object LaunchSkillPicker : ExtensionsOneShot
}
