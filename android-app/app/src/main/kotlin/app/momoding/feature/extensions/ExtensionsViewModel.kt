package app.momoding.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillCatalogService
import app.momoding.core.skills.SkillRecord
import app.momoding.core.skills.SkillRepository
import app.momoding.core.extensions.ExtensionPackageCatalogService
import app.momoding.core.extensions.ExtensionPackageRecord
import app.momoding.core.extensions.ExtensionPackageRepository
import app.momoding.core.extensions.ExtensionPackageImportResult
import app.momoding.core.extensions.PiExtensionCredentialBinding
import app.momoding.core.extensions.PiExtensionCredentialStore
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtensionsViewModel(
    private val repository: SkillRepository,
    private val catalog: SkillCatalogService,
    private val extensionRepository: ExtensionPackageRepository,
    private val extensionCatalog: ExtensionPackageCatalogService,
    private val credentialVault: PiExtensionCredentialStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExtensionsUiState())
    val state: StateFlow<ExtensionsUiState> = mutableState.asStateFlow()

    private val mutableOneShots = MutableSharedFlow<ExtensionsOneShot>(extraBufferCapacity = 1)
    val oneShots: SharedFlow<ExtensionsOneShot> = mutableOneShots.asSharedFlow()

    init {
        observeSkills()
        observeExtensionPackages()
        seedBundledSkills()
    }

    fun dispatch(action: ExtensionsAction) {
        when (action) {
            ExtensionsAction.Back -> mutableOneShots.tryEmit(ExtensionsOneShot.Back)
            ExtensionsAction.ImportSkill -> {
                if (!isBusy()) {
                    clearTransientFeedback()
                    mutableOneShots.tryEmit(ExtensionsOneShot.LaunchSkillPicker)
                }
            }
            ExtensionsAction.ImportSkillPackage -> {
                if (!isBusy()) {
                    clearTransientFeedback()
                    mutableOneShots.tryEmit(ExtensionsOneShot.LaunchSkillPackagePicker)
                }
            }
            ExtensionsAction.ImportExtensionPackage -> {
                if (!isBusy()) {
                    clearTransientFeedback()
                    mutableOneShots.tryEmit(ExtensionsOneShot.LaunchExtensionPackagePicker)
                }
            }
            is ExtensionsAction.ImportFinished -> importSkill(action)
            is ExtensionsAction.PackageImportFinished -> importSkillPackage(action)
            is ExtensionsAction.ExtensionPackageImportFinished -> importExtensionPackage(action)
            is ExtensionsAction.SetSkillEnabled -> setSkillEnabled(action)
            is ExtensionsAction.RequestDeleteSkill -> requestDelete(action.name)
            ExtensionsAction.CancelDeleteSkill -> mutableState.value =
                mutableState.value.copy(pendingDeleteSkillName = null)
            ExtensionsAction.ConfirmDeleteSkill -> confirmDelete()
            is ExtensionsAction.ToggleExtensionPackageDetails -> toggleExtensionDetails(action.packageId)
            is ExtensionsAction.SetExtensionPackageEnabled -> setExtensionPackageEnabled(action)
            is ExtensionsAction.RequestDeleteExtensionPackage -> requestDeleteExtension(action.packageId)
            ExtensionsAction.CancelDeleteExtensionPackage -> mutableState.value =
                mutableState.value.copy(pendingDeleteExtensionPackageId = null)
            ExtensionsAction.ConfirmDeleteExtensionPackage -> confirmDeleteExtension()
            is ExtensionsAction.RequestBindCredential -> requestBindCredential(action)
            is ExtensionsAction.BindCredential -> bindCredential(action.bearerToken)
            ExtensionsAction.CancelBindCredential -> mutableState.value =
                mutableState.value.copy(pendingCredentialBinding = null)
            is ExtensionsAction.RemoveCredential -> removeCredential(action)
            ExtensionsAction.UseNoticeRecovery -> useNoticeRecovery()
            ExtensionsAction.ClearNotice -> mutableState.value =
                mutableState.value.copy(
                    notice = null,
                    noticeRecovery = null,
                    compatibilityDiagnostic = null,
                )
        }
    }

    private fun observeExtensionPackages() {
        viewModelScope.launch {
            extensionRepository.observePackages()
                .catch {
                    mutableState.value = mutableState.value.copy(
                        packagesLoading = false,
                        notice = "Extension packages could not be loaded.",
                        noticeRecovery = null,
                    )
                }
                .collect { records ->
                    val credentialMetadata = try {
                        withContext(ioDispatcher) { credentialVault.metadata() }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        mutableState.value = mutableState.value.copy(
                            notice = "Extension credential metadata could not be loaded.",
                            noticeRecovery = null,
                        )
                        emptyList()
                    }
                    val expandedIds = mutableState.value.extensionPackages
                        .filter(ExtensionPackageUiState::expanded)
                        .map(ExtensionPackageUiState::id)
                        .toSet()
                    mutableState.value = mutableState.value.copy(
                        packagesLoading = false,
                        extensionPackages = records.map {
                            it.toUiState(
                                expanded = it.manifest.snapshot.id in expandedIds,
                                credentialMetadata = credentialMetadata,
                            )
                        },
                    )
                }
        }
    }

    private fun observeSkills() {
        viewModelScope.launch {
            repository.observeSkills()
                .catch {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        notice = "Skills could not be loaded.",
                        noticeRecovery = null,
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
                    noticeRecovery = null,
                )
            }
        }
    }

    private fun importSkill(action: ExtensionsAction.ImportFinished) {
        val uri = action.uri
        if (uri == null) {
            mutableState.value = mutableState.value.copy(
                notice = "Skill import cancelled.",
                noticeRecovery = null,
            )
            return
        }
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                importing = true,
                notice = null,
                noticeRecovery = null,
            )
            try {
                val imported = catalog.importSkill(uri)
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = buildSkillImportNotice(imported, importedFromFolder = false),
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = publicFailure(error, "This Skill could not be imported."),
                    noticeRecovery = ExtensionsNoticeRecovery(
                        ExtensionsNoticeRecoveryKind.ADD_SINGLE_SKILL,
                    ),
                )
            }
        }
    }

    private fun importSkillPackage(action: ExtensionsAction.PackageImportFinished) {
        val uri = action.uri
        if (uri == null) {
            mutableState.value = mutableState.value.copy(
                notice = "Skill package import cancelled.",
                noticeRecovery = null,
            )
            return
        }
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                importing = true,
                notice = null,
                noticeRecovery = null,
            )
            try {
                val installed = catalog.importSkillPackage(uri)
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = buildSkillImportNotice(installed.record, importedFromFolder = true),
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    importing = false,
                    notice = publicFailure(
                        error,
                        "This Skill package could not be imported. Choose a readable folder with a valid UTF-8 SKILL.md.",
                    ),
                    noticeRecovery = ExtensionsNoticeRecovery(
                        ExtensionsNoticeRecoveryKind.ADD_SKILL_FOLDER,
                    ),
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
                noticeRecovery = null,
            )
            try {
                repository.setEnabled(action.name, action.enabled)
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = if (action.enabled) {
                        "${action.name} is on. Momoding can use it in future messages."
                    } else {
                        "${action.name} is off. Momoding will not use it in future messages."
                    },
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = publicFailure(error, "${action.name} could not be updated."),
                    noticeRecovery = null,
                )
            }
        }
    }

    private fun importExtensionPackage(action: ExtensionsAction.ExtensionPackageImportFinished) {
        val uri = action.uri
        if (uri == null) {
            mutableState.value = mutableState.value.copy(
                notice = "Extension import cancelled.",
                noticeRecovery = null,
            )
            return
        }
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                importingExtensionPackage = true,
                notice = null,
                noticeRecovery = null,
                compatibilityDiagnostic = null,
            )
            val beforeDigests = runCatching { extensionRepository.packageRecords() }
                .getOrNull()
                ?.associate { it.manifest.snapshot.id to it.manifest.snapshot.packageDigest }
            try {
                when (val imported = extensionCatalog.importPackage(uri)) {
                    is ExtensionPackageImportResult.Diagnosed -> {
                        mutableState.value = mutableState.value.copy(
                            importingExtensionPackage = false,
                            compatibilityDiagnostic = imported.diagnostic,
                            notice = null,
                            noticeRecovery = null,
                        )
                    }
                    is ExtensionPackageImportResult.Installed -> {
                        val installed = imported.result.record
                        val summary = when {
                            imported.result.unchanged && installed.enabled ->
                                "${installed.manifest.snapshot.name} ${installed.manifest.snapshot.version} is unchanged and remains enabled."
                            imported.result.unchanged ->
                                "${installed.manifest.snapshot.name} ${installed.manifest.snapshot.version} is already installed disabled."
                            imported.result.wasUpdate ->
                                buildUpdateNotice(imported.result)
                            else ->
                                "${installed.manifest.snapshot.name} ${installed.manifest.snapshot.version} " +
                                    "was installed and is off. Review what it can do before turning it on."
                        }
                        mutableState.value = mutableState.value.copy(
                            importingExtensionPackage = false,
                            compatibilityDiagnostic = null,
                            notice = summary,
                            noticeRecovery = null,
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val afterRecords = beforeDigests?.let {
                    try {
                        Result.success(extensionRepository.packageRecords())
                    } catch (readError: Throwable) {
                        if (readError is CancellationException) throw readError
                        Result.failure(readError)
                    }
                }
                when (val outcome = extensionImportFailureOutcome(beforeDigests, afterRecords)) {
                    ExtensionImportFailureOutcome.Unknown -> {
                        mutableState.value = mutableState.value.copy(
                            importingExtensionPackage = false,
                            notice = "Momoding could not confirm whether this import finished. Check the " +
                                "installed list below. If the new version appears, use that state and do " +
                                "not import it again.",
                            noticeRecovery = null,
                        )
                    }
                    is ExtensionImportFailureOutcome.Committed -> {
                        val snapshot = outcome.record.manifest.snapshot
                        mutableState.value = mutableState.value.copy(
                            importingExtensionPackage = false,
                            notice = "${snapshot.name} ${snapshot.version} is installed and off, but old " +
                                "credential cleanup did not finish. Use the installed state below; do not " +
                                "import it again. Retry cleanup before adding new credentials.",
                            noticeRecovery = ExtensionsNoticeRecovery(
                                kind = ExtensionsNoticeRecoveryKind.CLEAN_PACKAGE_CREDENTIALS,
                                packageId = snapshot.id,
                            ),
                        )
                    }
                    ExtensionImportFailureOutcome.NotCommitted -> {
                        mutableState.value = mutableState.value.copy(
                            importingExtensionPackage = false,
                            notice = publicExtensionFailure(error),
                            noticeRecovery = ExtensionsNoticeRecovery(
                                ExtensionsNoticeRecoveryKind.ADD_EXTENSION_FOLDER,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun toggleExtensionDetails(packageId: String) {
        mutableState.value = mutableState.value.copy(
            extensionPackages = mutableState.value.extensionPackages.map { item ->
                if (item.id == packageId) item.copy(expanded = !item.expanded) else item
            },
        )
    }

    private fun setExtensionPackageEnabled(action: ExtensionsAction.SetExtensionPackageEnabled) {
        if (isBusy() || mutableState.value.extensionPackages.none { it.id == action.packageId }) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                busyExtensionPackageId = action.packageId,
                notice = null,
                noticeRecovery = null,
            )
            try {
                val record = extensionRepository.setEnabled(action.packageId, action.enabled)
                mutableState.value = mutableState.value.copy(
                    busyExtensionPackageId = null,
                    notice = if (action.enabled) {
                        "${record.manifest.snapshot.name} is on for future messages. " +
                            "Android will still ask when a tool first needs phone access."
                    } else {
                        "${record.manifest.snapshot.name} is off. It cannot start new tool calls."
                    },
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyExtensionPackageId = null,
                    notice = publicExtensionFailure(error),
                    noticeRecovery = ExtensionsNoticeRecovery(
                        kind = ExtensionsNoticeRecoveryKind.REVIEW_EXTENSION,
                        packageId = action.packageId,
                    ),
                )
            }
        }
    }

    private fun requestDeleteExtension(packageId: String) {
        if (!isBusy() && mutableState.value.extensionPackages.any { it.id == packageId }) {
            mutableState.value = mutableState.value.copy(
                pendingDeleteExtensionPackageId = packageId,
                notice = null,
                noticeRecovery = null,
            )
        }
    }

    private fun confirmDeleteExtension() {
        val packageId = mutableState.value.pendingDeleteExtensionPackageId ?: return
        if (isBusy()) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                pendingDeleteExtensionPackageId = null,
                busyExtensionPackageId = packageId,
                notice = null,
                noticeRecovery = null,
            )
            try {
                check(extensionRepository.delete(packageId)) { "EXTENSION_PACKAGE_NOT_FOUND" }
                mutableState.value = mutableState.value.copy(
                    busyExtensionPackageId = null,
                    notice = "Extension removed from this phone. New Tool calls are blocked.",
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val stillInstalled = runCatching { extensionRepository.packageRecords() }
                    .getOrNull()
                    ?.any { it.manifest.snapshot.id == packageId }
                if (stillInstalled == true) {
                    mutableState.value = mutableState.value.copy(
                        busyExtensionPackageId = null,
                        notice = "This Extension is still installed. Try removing it again.",
                        noticeRecovery = ExtensionsNoticeRecovery(
                            kind = ExtensionsNoticeRecoveryKind.REMOVE_EXTENSION,
                            packageId = packageId,
                        ),
                    )
                } else if (stillInstalled == false) {
                    mutableState.value = mutableState.value.copy(
                        busyExtensionPackageId = null,
                        notice = "The Extension was removed and cannot run, but credential cleanup " +
                            "did not finish. Retry cleanup; do not add the package again first.",
                        noticeRecovery = ExtensionsNoticeRecovery(
                            kind = ExtensionsNoticeRecoveryKind.CLEAN_PACKAGE_CREDENTIALS,
                            packageId = packageId,
                        ),
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        busyExtensionPackageId = null,
                        notice = "Momoding could not confirm whether removal finished. Check the list " +
                            "below. If the Extension remains, turn it off and try removing it again.",
                        noticeRecovery = ExtensionsNoticeRecovery(
                            kind = ExtensionsNoticeRecoveryKind.REMOVE_EXTENSION,
                            packageId = packageId,
                        ),
                    )
                }
            }
        }
    }

    private fun requestBindCredential(action: ExtensionsAction.RequestBindCredential) {
        if (isBusy()) return
        val item = mutableState.value.extensionPackages.singleOrNull { it.id == action.packageId }
            ?: return
        val binding = item.credentialBindings.singleOrNull {
            it.slot == action.slot && it.origin == action.origin
        } ?: return
        mutableState.value = mutableState.value.copy(
            pendingCredentialBinding = binding,
            notice = null,
            noticeRecovery = null,
        )
    }

    private fun bindCredential(bearerToken: String) {
        val binding = mutableState.value.pendingCredentialBinding ?: return
        if (isBusy() || bearerToken.isEmpty() || bearerToken.length > 4_096) return
        viewModelScope.launch {
            val key = binding.key()
            mutableState.value = mutableState.value.copy(
                pendingCredentialBinding = null,
                busyCredentialBindingKey = key,
                notice = null,
                noticeRecovery = null,
            )
            val secret = bearerToken.toCharArray()
            try {
                withContext(ioDispatcher) {
                    credentialVault.bind(binding.toVaultBinding(), secret)
                }
                refreshCredentialBindings()
                mutableState.value = mutableState.value.copy(
                    busyCredentialBindingKey = null,
                    notice = "${binding.slot} credential saved in Android Vault. Its value will not be shown again.",
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyCredentialBindingKey = null,
                    notice = "This credential could not be saved in Android Vault.",
                    noticeRecovery = binding.toRecovery(ExtensionsNoticeRecoveryKind.ADD_CREDENTIAL),
                )
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private fun removeCredential(action: ExtensionsAction.RemoveCredential) {
        if (isBusy()) return
        val binding = mutableState.value.extensionPackages
            .singleOrNull { it.id == action.packageId }
            ?.credentialBindings
            ?.singleOrNull { it.slot == action.slot && it.origin == action.origin && it.bound }
            ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                busyCredentialBindingKey = binding.key(),
                notice = null,
                noticeRecovery = null,
            )
            try {
                withContext(ioDispatcher) { credentialVault.remove(binding.toVaultBinding()) }
                refreshCredentialBindings()
                mutableState.value = mutableState.value.copy(
                    busyCredentialBindingKey = null,
                    notice = "${binding.slot} credential removed from Android Vault.",
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyCredentialBindingKey = null,
                    notice = "This credential could not be removed from Android Vault.",
                    noticeRecovery = binding.toRecovery(ExtensionsNoticeRecoveryKind.REMOVE_CREDENTIAL),
                )
            }
        }
    }

    private suspend fun refreshCredentialBindings() {
        val metadata = withContext(ioDispatcher) { credentialVault.metadata() }
        mutableState.value = mutableState.value.copy(
            extensionPackages = mutableState.value.extensionPackages.map { item ->
                item.copy(credentialBindings = item.credentialBindings.map { binding ->
                    binding.copy(bound = metadata.any {
                        it.packageId == binding.packageId &&
                            it.packageDigest == binding.packageDigest &&
                            it.slot == binding.slot && it.origin == binding.origin
                    })
                })
            },
        )
    }

    private fun useNoticeRecovery() {
        if (isBusy()) return
        val recovery = mutableState.value.noticeRecovery ?: return
        clearTransientFeedback()
        when (recovery.kind) {
            ExtensionsNoticeRecoveryKind.ADD_SINGLE_SKILL ->
                mutableOneShots.tryEmit(ExtensionsOneShot.LaunchSkillPicker)
            ExtensionsNoticeRecoveryKind.ADD_SKILL_FOLDER ->
                mutableOneShots.tryEmit(ExtensionsOneShot.LaunchSkillPackagePicker)
            ExtensionsNoticeRecoveryKind.ADD_EXTENSION_FOLDER ->
                mutableOneShots.tryEmit(ExtensionsOneShot.LaunchExtensionPackagePicker)
            ExtensionsNoticeRecoveryKind.REVIEW_EXTENSION -> {
                val packageId = recovery.packageId ?: return
                mutableState.value = mutableState.value.copy(
                    extensionPackages = mutableState.value.extensionPackages.map { item ->
                        if (item.id == packageId) item.copy(expanded = true) else item
                    },
                )
            }
            ExtensionsNoticeRecoveryKind.ADD_CREDENTIAL -> requestBindCredential(
                ExtensionsAction.RequestBindCredential(
                    packageId = recovery.packageId ?: return,
                    slot = recovery.slot ?: return,
                    origin = recovery.origin ?: return,
                ),
            )
            ExtensionsNoticeRecoveryKind.REMOVE_CREDENTIAL -> removeCredential(
                ExtensionsAction.RemoveCredential(
                    packageId = recovery.packageId ?: return,
                    slot = recovery.slot ?: return,
                    origin = recovery.origin ?: return,
                ),
            )
            ExtensionsNoticeRecoveryKind.CLEAN_PACKAGE_CREDENTIALS ->
                retryPackageCredentialCleanup(recovery.packageId ?: return)
            ExtensionsNoticeRecoveryKind.REMOVE_EXTENSION ->
                requestDeleteExtension(recovery.packageId ?: return)
        }
    }

    private fun retryPackageCredentialCleanup(packageId: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                busyExtensionPackageId = packageId,
                notice = null,
                noticeRecovery = null,
            )
            try {
                withContext(ioDispatcher) { credentialVault.removePackage(packageId) }
                val stillInstalled = mutableState.value.extensionPackages.any { it.id == packageId }
                mutableState.value = mutableState.value.copy(
                    busyExtensionPackageId = null,
                    extensionPackages = mutableState.value.extensionPackages.map { item ->
                        if (item.id == packageId) {
                            item.copy(
                                expanded = true,
                                credentialBindings = item.credentialBindings.map { binding ->
                                    binding.copy(bound = false)
                                },
                            )
                        } else item
                    },
                    notice = if (stillInstalled) {
                        "Old credentials were removed. Review the installed Extension before turning it on."
                    } else {
                        "Old credentials were removed. Extension removal is complete."
                    },
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyExtensionPackageId = null,
                    notice = "Credential cleanup still could not finish. The Extension remains unable " +
                        "to use old credentials; try cleanup again later.",
                    noticeRecovery = ExtensionsNoticeRecovery(
                        ExtensionsNoticeRecoveryKind.CLEAN_PACKAGE_CREDENTIALS,
                        packageId = packageId,
                    ),
                )
            }
        }
    }

    private fun clearTransientFeedback() {
        mutableState.value = mutableState.value.copy(
            notice = null,
            noticeRecovery = null,
            compatibilityDiagnostic = null,
        )
    }

    private fun requestDelete(name: String) {
        val imported = mutableState.value.skills.firstOrNull { it.name == name }
            ?.takeIf { it.source == app.momoding.core.skills.SkillSource.IMPORTED }
            ?: return
        if (!isBusy()) {
            mutableState.value = mutableState.value.copy(
                pendingDeleteSkillName = imported.name,
                notice = null,
                noticeRecovery = null,
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
                noticeRecovery = null,
            )
            try {
                check(repository.deleteImported(name)) { "SKILL_NOT_FOUND" }
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = "$name was removed from Momoding. Original files were not changed.",
                    noticeRecovery = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busySkillName = null,
                    notice = publicFailure(error, "$name could not be removed."),
                    noticeRecovery = null,
                )
            }
        }
    }

    private fun isBusy(): Boolean = mutableState.value.importing ||
        mutableState.value.importingExtensionPackage ||
        mutableState.value.busySkillName != null ||
        mutableState.value.busyExtensionPackageId != null ||
        mutableState.value.busyCredentialBindingKey != null

    private fun publicExtensionFailure(error: Throwable): String = when (error) {
        is SecurityException -> "Android did not grant one-shot access to this folder."
        is FileNotFoundException -> "The selected Extension folder is no longer available."
        is IllegalArgumentException -> when (error.message) {
            "EXTENSION_PACKAGE_DIGEST_MISMATCH" -> "The Extension package digest does not match its files."
            "EXTENSION_PACKAGE_MANIFEST_MISSING" -> "The folder does not contain momoding-extension.json."
            "EXTENSION_PACKAGE_NATIVE_PI_UNSUPPORTED" ->
                "This is a native desktop Pi package. Momoding cannot run its ExtensionAPI code yet."
            "EXTENSION_PACKAGE_RUNTIME_UNSUPPORTED" -> "This package needs a runtime Momoding does not support yet."
            "EXTENSION_PACKAGE_CAPABILITY_UNDECLARED" -> "A Tool uses Android access not declared by the package."
            "EXTENSION_PACKAGE_ALIAS_TARGET_UNSUPPORTED" -> "A Tool targets an unsupported Android capability."
            "EXTENSION_PACKAGE_LIMIT_EXCEEDED" -> "The 32-Extension device limit has been reached."
            "EXTENSION_PACKAGE_NOT_FOUND" -> "This Extension is no longer installed."
            else -> "This Extension package failed strict validation."
        }
        else -> "This Extension package could not be installed or updated."
    }

    private fun publicFailure(error: Throwable, fallback: String): String = when (error) {
        is SecurityException -> "$fallback Android did not grant access to this document."
        is FileNotFoundException -> "$fallback The selected document is no longer available."
        is IllegalArgumentException -> when (error.message) {
            "SKILL_NAME_DUPLICATED" -> "A Skill with this name is already installed."
            "SKILL_RESOURCE_LIMIT_EXCEEDED" -> "The 64-Skill device limit has been reached."
            "SKILL_UNAVAILABLE" -> "This Skill is unavailable and cannot be enabled."
            "SKILL_PACKAGE_DOCUMENT_MISSING" -> "The selected folder does not contain SKILL.md."
            "SKILL_PACKAGE_FILE_LIMIT_EXCEEDED" -> "This Skill package contains too many files."
            "SKILL_PACKAGE_FILE_TOO_LARGE", "SKILL_PACKAGE_TOO_LARGE" ->
                "This Skill package is larger than the on-device import limit."
            "SKILL_PACKAGE_TREE_URI_INVALID" -> "Choose one folder containing SKILL.md."
            else -> fallback
        }
        else -> fallback
    }

    class Factory(
        private val repository: SkillRepository,
        private val catalog: SkillCatalogService,
        private val extensionRepository: ExtensionPackageRepository,
        private val extensionCatalog: ExtensionPackageCatalogService,
        private val credentialVault: PiExtensionCredentialStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExtensionsViewModel::class.java))
            return ExtensionsViewModel(
                repository,
                catalog,
                extensionRepository,
                extensionCatalog,
                credentialVault,
            ) as T
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
    packageFileCount = resource.packageFileCount,
    diagnosticCode = diagnosticCode,
)

internal fun buildSkillImportNotice(
    record: SkillRecord,
    importedFromFolder: Boolean,
): String = when (record.availability) {
    SkillAvailability.AVAILABLE -> if (importedFromFolder) {
        val fileLabel = if (record.resource.packageFileCount == 1) "1 file" else
            "${record.resource.packageFileCount} files"
        "${record.resource.name} was added with $fileLabel. It is off until you turn it on."
    } else {
        "${record.resource.name} was added from one SKILL.md. It is off until you turn it on."
    }
    SkillAvailability.UNAVAILABLE ->
        "${record.resource.name} was copied, but it needs its full Skill folder before it can be used. " +
            "Remove it, then add the Skill folder."
    SkillAvailability.ERROR -> "${record.resource.name} could not be added safely."
}

private fun ExtensionPackageRecord.toUiState(
    expanded: Boolean,
    credentialMetadata: List<PiExtensionCredentialBinding>,
): ExtensionPackageUiState =
    ExtensionPackageUiState(
        id = manifest.snapshot.id,
        name = manifest.snapshot.name,
        version = manifest.snapshot.version,
        description = manifest.snapshot.description,
        runtime = manifest.snapshot.runtime,
        entrypoint = manifest.snapshot.entrypoint,
        enabled = enabled,
        fileCount = fileCount,
        tools = manifest.snapshot.tools,
        requiredCapabilities = manifest.snapshot.requiredCapabilities,
        optionalCapabilities = manifest.snapshot.optionalCapabilities,
        networkOrigins = manifest.snapshot.networkOrigins,
        hostTools = manifest.snapshot.hostTools,
        httpPolicy = manifest.snapshot.httpPolicy,
        packageDigest = manifest.snapshot.packageDigest,
        activationAvailable = true,
        credentialBindings = manifest.snapshot.httpPolicy.credentialSlots.map { slot ->
            ExtensionCredentialUiState(
                packageId = manifest.snapshot.id,
                packageDigest = manifest.snapshot.packageDigest,
                slot = slot.slot,
                origin = slot.origin,
                placement = slot.placement,
                bound = credentialMetadata.any {
                    it.packageId == manifest.snapshot.id &&
                        it.packageDigest == manifest.snapshot.packageDigest &&
                        it.slot == slot.slot && it.origin == slot.origin
                },
            )
        },
        expanded = expanded,
    )

private fun ExtensionCredentialUiState.key(): String =
    "$packageId\u0000$packageDigest\u0000$slot\u0000$origin"

private fun ExtensionCredentialUiState.toVaultBinding() = PiExtensionCredentialBinding(
    packageId = packageId,
    packageDigest = packageDigest,
    slot = slot,
    origin = origin,
)

private fun ExtensionCredentialUiState.toRecovery(
    kind: ExtensionsNoticeRecoveryKind,
) = ExtensionsNoticeRecovery(
    kind = kind,
    packageId = packageId,
    slot = slot,
    origin = origin,
)

internal fun buildUpdateNotice(
    result: app.momoding.core.extensions.ExtensionPackageInstallResult,
): String {
    val snapshot = result.record.manifest.snapshot
    val changes = buildList {
        if (result.accessDiff.runtimeChanged) add("how it runs changed")
        if (result.accessDiff.addedRequiredCapabilities.isNotEmpty()) {
            add("now requires ${result.accessDiff.addedRequiredCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        if (result.accessDiff.removedRequiredCapabilities.isNotEmpty()) {
            add("no longer requires ${result.accessDiff.removedRequiredCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        if (result.accessDiff.addedOptionalCapabilities.isNotEmpty()) {
            add("may now use ${result.accessDiff.addedOptionalCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        if (result.accessDiff.removedOptionalCapabilities.isNotEmpty()) {
            add("no longer optionally uses ${result.accessDiff.removedOptionalCapabilities.joinToString { extensionAccessLabel(it) }}")
        }
        if (result.accessDiff.addedHostTools.isNotEmpty()) {
            add("added phone tools: ${result.accessDiff.addedHostTools.joinToString()}")
        }
        if (result.accessDiff.removedHostTools.isNotEmpty()) {
            add("removed phone tools: ${result.accessDiff.removedHostTools.joinToString()}")
        }
        if (result.accessDiff.addedOrigins.isNotEmpty()) {
            add("can now connect to ${result.accessDiff.addedOrigins.joinToString()}")
        }
        if (result.accessDiff.removedOrigins.isNotEmpty()) {
            add("no longer connects to ${result.accessDiff.removedOrigins.joinToString()}")
        }
        if (result.accessDiff.addedHttpMethods.isNotEmpty()) {
            add("added web methods: ${result.accessDiff.addedHttpMethods.joinToString()}")
        }
        if (result.accessDiff.removedHttpMethods.isNotEmpty()) {
            add("removed web methods: ${result.accessDiff.removedHttpMethods.joinToString()}")
        }
        if (result.accessDiff.addedCredentialBindings.isNotEmpty()) {
            add("new credential setup: ${result.accessDiff.addedCredentialBindings.joinToString { it.substringBefore(" (") }}")
        }
        if (result.accessDiff.removedCredentialBindings.isNotEmpty()) {
            add("removed credential setup: ${result.accessDiff.removedCredentialBindings.joinToString { it.substringBefore(" (") }}")
        }
    }
    val diff = changes.takeIf(List<String>::isNotEmpty)?.joinToString("; ")
        ?: "Its declared access did not change"
    return "${snapshot.name} was updated to ${snapshot.version} and turned off for review. " +
        "Its saved Extension state was cleared. Changes: $diff."
}

internal sealed interface ExtensionImportFailureOutcome {
    data object Unknown : ExtensionImportFailureOutcome
    data object NotCommitted : ExtensionImportFailureOutcome
    data class Committed(val record: ExtensionPackageRecord) : ExtensionImportFailureOutcome
}

internal fun extensionImportFailureOutcome(
    beforeDigests: Map<String, String>?,
    afterRecords: Result<List<ExtensionPackageRecord>>?,
): ExtensionImportFailureOutcome {
    if (beforeDigests == null || afterRecords == null || afterRecords.isFailure) {
        return ExtensionImportFailureOutcome.Unknown
    }
    val committed = committedExtensionAfterFailure(beforeDigests, afterRecords.getOrThrow())
    return committed?.let(ExtensionImportFailureOutcome::Committed)
        ?: ExtensionImportFailureOutcome.NotCommitted
}

private fun committedExtensionAfterFailure(
    beforeDigests: Map<String, String>,
    after: List<ExtensionPackageRecord>,
): ExtensionPackageRecord? = after.firstOrNull { record ->
    beforeDigests[record.manifest.snapshot.id] != record.manifest.snapshot.packageDigest
}
