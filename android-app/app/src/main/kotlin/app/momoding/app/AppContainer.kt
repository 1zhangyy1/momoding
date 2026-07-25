package app.momoding.app

import android.app.Application
import app.momoding.core.appearance.AppearanceStore
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.diagnostics.DiagnosticsExporter
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.DeviceContentReadExecutor
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.DeviceMetadataToolExecutor
import app.momoding.core.media.DeviceMediaListExecutor
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import app.momoding.core.runtime.local.PhoneLocalPiOpenRouterRuntime
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PhoneLocalAttachmentToolExecutor
import app.momoding.core.runtime.local.PhoneLocalLinuxRuntime
import app.momoding.core.runtime.local.PhoneLocalProjectToolExecutor
import app.momoding.core.runtime.local.PhoneLocalProjectWorkspace
import app.momoding.core.skills.SkillCatalogService
import app.momoding.core.skills.SkillImportReader
import app.momoding.core.skills.SkillRepository
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.DraftRepository
import app.momoding.core.data.AttentionRepository
import app.momoding.core.data.FileChangeRepository
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.PhoneLocalGoalRepository
import app.momoding.core.data.PhoneLocalChildAgentRepository
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.TaskRepository
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.feature.newtask.TaskCreationCoordinator
import app.momoding.feature.share.ShareImportCoordinator
import app.momoding.feature.taskdetail.TaskCommandCoordinator
import app.momoding.feature.tasks.PhoneLocalTaskCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MomodingApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = MomodingDatabase.open(application)
    val commandJournal = RoomCommandDraftJournal(database)
    val taskRepository = TaskRepository(database)
    val taskDetailRepository = TaskDetailRepository(database)
    val authorizedFoldersRepository = AuthorizedFoldersRepository(application, database)
    val androidCapabilityRegistry = AndroidCapabilityRegistry.create(
        context = application,
        folders = authorizedFoldersRepository,
        scope = applicationScope,
    ).also(AndroidCapabilityRegistry::refresh)
    val attentionRepository = AttentionRepository(
        database = database,
        folders = authorizedFoldersRepository,
    )
    val fileChangeRepository = FileChangeRepository(database)
    val draftRepository = DraftRepository(database)
    val attachmentRepository = AttachmentRepository(application, database)
    val shareImportCoordinator = ShareImportCoordinator(
        context = application,
        drafts = draftRepository,
        attachments = attachmentRepository,
    )
    init {
        applicationScope.launch {
            runCatching { attachmentRepository.pruneOrphanedPayloads() }
        }
    }
    val runtime = AndroidSecureTransportRuntime(application, applicationScope, database)
    val taskCreationCoordinator = TaskCreationCoordinator(
        transportStatus = runtime.uiStatus,
        submitExact = runtime::submitExact,
        journal = commandJournal,
        applicationScope = applicationScope,
    )
    val taskCommandCoordinator = TaskCommandCoordinator(
        transportStatus = runtime.uiStatus,
        submitExact = runtime::submitExact,
        journal = commandJournal,
        applicationScope = applicationScope,
    )
    val appearanceStore = AppearanceStore.create(application, applicationScope)
    val diagnosticsExporter = DiagnosticsExporter(application)
    val providerCredentialVault = ProviderCredentialVault.create(application)
    val openRouterClient = OpenRouterNativeClient()
    val phoneLocalFileChangeExecutor = DeviceFileChangeExecutor(
        database,
        authorizedFoldersRepository,
    )
    val phoneLocalLinuxRuntime = PhoneLocalLinuxRuntime(application)
    val phoneLocalProjectWorkspace = PhoneLocalProjectWorkspace(
        application,
        database,
        authorizedFoldersRepository,
        phoneLocalLinuxRuntime,
    )
    val phoneLocalProjectToolExecutor = PhoneLocalProjectToolExecutor(
        phoneLocalLinuxRuntime,
        phoneLocalProjectWorkspace,
        phoneLocalFileChangeExecutor,
    )
    val phoneLocalAttachmentToolExecutor = PhoneLocalAttachmentToolExecutor(attachmentRepository)
    val phoneLocalAttentionBridge = PhoneLocalAttentionBridge(
        ledger = RoomAttentionLedger(database),
        metadataTools = DeviceMetadataToolExecutor(database, authorizedFoldersRepository),
        contentReadHandler = DeviceContentReadExecutor(database, authorizedFoldersRepository),
        fileChangeHandler = phoneLocalFileChangeExecutor,
        projectTools = phoneLocalProjectToolExecutor,
        attachmentTools = phoneLocalAttachmentToolExecutor,
        mediaTools = DeviceMediaListExecutor.create(application),
        approvalModeForTask = { taskId ->
            database.momodingDao().task(taskId)?.approvalMode ?: TaskApprovalMode.REQUEST_APPROVAL
        },
    )
    val phoneLocalChildAgentRepository = PhoneLocalChildAgentRepository(database)
    val skillRepository = SkillRepository(database)
    val phoneLocalPiRuntime = PhoneLocalPiOpenRouterRuntime(
        application,
        phoneLocalAttentionBridge,
    ) { events, snapshots ->
        phoneLocalChildAgentRepository.persistRuntimeUpdate(events, snapshots)
    }
    val skillCatalogService = SkillCatalogService(
        assets = application.assets,
        importReader = SkillImportReader(application.contentResolver),
        parser = phoneLocalPiRuntime,
        repository = skillRepository,
    )
    val phoneLocalGoalRepository = PhoneLocalGoalRepository(database)
    val phoneLocalTaskCoordinator = PhoneLocalTaskCoordinator(
        runtime = phoneLocalPiRuntime,
        projector = PhoneLocalPiEventProjector(database),
        journal = commandJournal,
        applicationScope = applicationScope,
        attentionBridge = phoneLocalAttentionBridge,
        goalRepository = phoneLocalGoalRepository,
        childAgentRepository = phoneLocalChildAgentRepository,
        skillRepository = skillRepository,
        attachmentRepository = attachmentRepository,
    )
}
