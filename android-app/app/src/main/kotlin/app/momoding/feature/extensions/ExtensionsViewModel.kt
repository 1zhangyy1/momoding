package app.momoding.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillCatalogService
import app.momoding.core.skills.SkillRecord
import app.momoding.core.skills.SkillRepository
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class ExtensionsViewModel(
    private val repository: SkillRepository,
    private val catalog: SkillCatalogService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = mutableState.asStateFlow()

    private val mutableOneShots = MutableSharedFlow<ExtensionsOneShot>(extraBufferCapacity = 1)
    val oneShots: SharedFlow<ExtensionsOneShot> = mutableOneShots.asSharedFlow()

    init {
        observeSkills()
        seedBundledSkills()
    }

    fun dispatch(action: ExtensionsAction) {
        when (action) {
            ExtensionsAction.Back -> mutableOneShots.tryEmit(ExtensionsOneShot.Back)
            ExtensionsAction.ImportSkill -> {
                if (!isBusy()) mutableOneShots.tryEmit(ExtensionsOneShot.LaunchSkillPicker)
            }
            is ExtensionsAction.ImportFinished -> importSkill(action)
            is ExtensionsAction.SetSkillEnabled -> setSkillEnabled(action)
            is ExtensionsAction.RequestDeleteSkill -> requestDelete(action.name)
            ExtensionsAction.CancelDeleteSkill -> mutableState.value =
                mutableState.value.copy(pendingDeleteSkillName = null)
            ExtensionsAction.ConfirmDeleteSkill -> confirmDelete()
            ExtensionsAction.ClearNotice -> mutableState.value =
                mutableState.value.copy(notice = null)
        }
    }

    private fun observeSkills() {
        viewModelScope.launch {
            repository.observeSkills()
                .catch {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        notice = "Extensions could not be loaded.",
                    )
                }
                .collect { records ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        skills = records.map(SkillRecord::toUiState),
                    )
                }
        }
    }

    private fun seedBundledSkills() {
        viewModelScope.launch {
            try {
                catalog.seedBundledSkills()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    notice = publicFailure(error, "Bundled Skills could not be loaded."),
                )
            }
        }
    }

    private fun importSkill(action: ExtensionsAction.ImportFinished) {
        val uri = action.uri
        if (uri == null) {
            mutableState.value = mutableState.value.copy(notice = "Skill import cancelled.")
            return
        }
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(importing = true, notice = null)
            try {
                val imported = catalog.importSkill(uri)
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = "${imported.resource.name} imported. Enable it when you are ready to use it.",
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = publicFailure(error, "This Skill could not be imported."),
                )
            }
        }
    }

    private fun setSkillEnabled(action: ExtensionsAction.SetSkillEnabled) {
        val skill = mutableState.value.skills.firstOrNull { it.name == action.name } ?: return
        if (isBusy() || skill.availability != SkillAvailability.AVAILABLE) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                busySkillName = action.name,
                notice = null,
            )
            try {
                repository.setEnabled(action.name, action.enabled)
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = if (action.enabled) {
                        "${action.name} enabled for new phone-local turns."
                    } else {
                        "${action.name} disabled."
                    },
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = publicFailure(error, "${action.name} could not be updated."),
                )
            }
        }
    }

    private fun requestDelete(name: String) {
        val imported = mutableState.value.skills.firstOrNull { it.name == name }
            ?.takeIf { it.source == app.momoding.core.skills.SkillSource.IMPORTED }
            ?: return
        if (!isBusy()) {
            mutableState.value = mutableState.value.copy(
                pendingDeleteSkillName = imported.name,
                notice = null,
            )
        }
    }

    private fun confirmDelete() {
        val name = mutableState.value.pendingDeleteSkillName ?: return
        if (mutableState.value.busySkillName != null || mutableState.value.importing) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                pendingDeleteSkillName = null,
                busySkillName = name,
                notice = null,
            )
            try {
                check(repository.deleteImported(name)) { "SKILL_NOT_FOUND" }
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = "$name removed from this phone.",
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = publicFailure(error, "$name could not be removed."),
                )
            }
        }
    }

    private fun isBusy(): Boolean = mutableState.value.importing ||
        mutableState.value.busySkillName != null

    private fun publicFailure(error: Throwable, fallback: String): String = when (error) {
        is SecurityException -> "$fallback Android did not grant access to this document."
        is FileNotFoundException -> "$fallback The selected document is no longer available."
        is IllegalArgumentException -> when (error.message) {
            "SKILL_NAME_DUPLICATED" -> "A Skill with this name is already installed."
            "SKILL_RESOURCE_LIMIT_EXCEEDED" -> "The 64-Skill device limit has been reached."
            "SKILL_UNAVAILABLE" -> "This Skill is unavailable and cannot be enabled."
            else -> "$fallback Choose one valid SKILL.md file (UTF-8, up to 64 KiB)."
        }
        else -> fallback
    }

    class Factory(
        private val repository: SkillRepository,
        private val catalog: SkillCatalogService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExtensionsViewModel::class.java))
            return ExtensionsViewModel(repository, catalog) as T
        }
    }
}

private fun SkillRecord.toUiState(): ExtensionSkillUiState = ExtensionSkillUiState(
    name = resource.name,
    description = resource.description,
    source = source,
    enabled = enabled,
    availability = availability,
    diagnosticMessage = diagnosticMessage,
)
