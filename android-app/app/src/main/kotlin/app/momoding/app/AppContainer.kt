package app.momoding.app

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import app.momoding.core.appearance.AppearanceStore
import app.momoding.core.accessibility.MomodingAccessibilityRuntime
import app.momoding.core.accessibility.ScreenCaptureCoordinator
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityRequestCoordinator
import app.momoding.core.capabilities.AndroidPermissionRequestCoordinator
import app.momoding.core.calendar.PhoneLocalCalendarToolExecutor
import app.momoding.core.clipboard.PhoneLocalClipboardToolExecutor
import app.momoding.core.contacts.PhoneLocalContactsToolExecutor
import app.momoding.core.location.PhoneLocalLocationToolExecutor
import app.momoding.core.notification.PhoneLocalNotificationToolExecutor
import app.momoding.core.diagnostics.DiagnosticsExporter
import app.momoding.core.files.AuthorizedContentReadPolicy
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.DeviceContentReadExecutor
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.files.DeviceMetadataToolExecutor
import app.momoding.core.files.SharedStorageRepository
import app.momoding.core.media.DeviceMediaListExecutor
import app.momoding.core.media.AndroidMediaConsentCoordinator
import app.momoding.core.media.AndroidMediaGateway
import app.momoding.core.media.MediaHandleRegistry
import app.momoding.core.media.PhoneLocalMediaToolExecutor
import app.momoding.core.media.PhotoLibraryScopeProvider
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import app.momoding.core.runtime.local.PhoneLocalPiOpenRouterRuntime
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PhoneLocalAttachmentToolExecutor
import app.momoding.core.runtime.local.PhoneLocalCapabilityRequestToolExecutor
import app.momoding.core.runtime.local.PhoneLocalLinuxRuntime
import app.momoding.core.runtime.local.PhoneLocalProjectToolExecutor
import app.momoding.core.runtime.local.PhoneLocalProjectWorkspace
import app.momoding.core.runtime.local.TaskFolderGrantBinder
import app.momoding.core.runtime.local.PhoneLocalScreenCaptureToolExecutor
import app.momoding.core.runtime.local.PhoneLocalShizukuToolExecutor
import app.momoding.core.runtime.local.PhoneLocalUiToolExecutor
import app.momoding.core.shizuku.ShizukuController
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
import rikka.shizuku.ShizukuProvider

class MomodingApplication : Application() {
    override fun attachBaseContext(base: Context) {
        ShizukuProvider.disableAutomaticSuiInitialization()
        super.attachBaseContext(base)
    }

    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mediaHandles = MediaHandleRegistry()
    val database = MomodingDatabase.open(application)
    val commandJournal = RoomCommandDraftJournal(database)
    val taskRepository = TaskRepository(
        database = database,
        onTaskDeleted = mediaHandles::clearTask,
    )
    val taskDetailRepository = TaskDetailRepository(database)
    val authorizedFoldersRepository = AuthorizedFoldersRepository(application, database)
    val sharedStorageRepository = SharedStorageRepository()
    val shizukuController = ShizukuController(application)
    val androidPermissionRequestCoordinator = AndroidPermissionRequestCoordinator()
    val androidCapabilityRequestCoordinator = AndroidCapabilityRequestCoordinator()
    val androidMediaConsentCoordinator = AndroidMediaConsentCoordinator(application)
    val androidCapabilityRegistry = AndroidCapabilityRegistry.create(
        context = application,
        folders = authorizedFoldersRepository,
        shizuku = shizukuController,
        scope = applicationScope,
    ).also { registry ->
        MomodingAccessibilityRuntime.setCapabilityChangedListener(registry::refresh)
        shizukuController.setCapabilityChangedListener(registry::refresh)
        registry.refresh()
    }
    val attentionRepository = AttentionRepository(
        database = database,
        contentMetadataLoader = { grantId ->
            if (sharedStorageRepository.isSharedGrant(grantId)) {
                sharedStorageRepository.metadata(
                    grantId = grantId,
                    maxDepth = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_DEPTH,
                    maxItems = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_ITEMS,
                )
            } else {
                authorizedFoldersRepository.metadata(
                    grantId = grantId,
                    maxDepth = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_DEPTH,
                    maxItems = AuthorizedContentReadPolicy.MAX_CONTENT_SCAN_ITEMS,
                )
            }
        },
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
        database = database,
        folders = authorizedFoldersRepository,
        sharedStorage = sharedStorageRepository,
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
    private val phoneLocalScreenCaptureToolExecutor = PhoneLocalScreenCaptureToolExecutor(
        ScreenCaptureCoordinator(application),
    )
    private val phoneLocalUiToolExecutor = PhoneLocalUiToolExecutor(
        controller = MomodingAccessibilityRuntime.controller,
        isDeviceLocked = {
            application.getSystemService(Context.KEYGUARD_SERVICE)
                .let { it as KeyguardManager }
                .isDeviceLocked
        },
    )
    private val phoneLocalShizukuToolExecutor =
        PhoneLocalShizukuToolExecutor(shizukuController)
    private val phoneLocalCapabilityRequestToolExecutor =
        PhoneLocalCapabilityRequestToolExecutor(
            requester = androidCapabilityRequestCoordinator,
            registry = androidCapabilityRegistry,
            folderGrantBinder = TaskFolderGrantBinder { taskId, grantId ->
                val folder = authorizedFoldersRepository.folders().firstOrNull {
                    it.grantId == grantId && it.canRead
                } ?: return@TaskFolderGrantBinder false
                runCatching {
                    phoneLocalProjectWorkspace.discardImportedTask(taskId)
                }.getOrElse {
                    return@TaskFolderGrantBinder false
                }
                runCatching {
                    commandJournal.rebindTaskGrantFromExplicitSafSelection(
                        taskId = taskId,
                        grantId = folder.grantId,
                    )
                }.isSuccess
            },
        )
    private val mediaListToolExecutor = DeviceMediaListExecutor.create(
        application,
        androidPermissionRequestCoordinator,
        mediaHandles,
    )
    private val mediaMutationToolExecutor = PhoneLocalMediaToolExecutor(
        scopeProvider = PhotoLibraryScopeProvider(mediaListToolExecutor::currentScope),
        gateway = AndroidMediaGateway(application.contentResolver),
        handles = mediaHandles,
        consentRequester = androidMediaConsentCoordinator,
    )
    val phoneLocalAttentionBridge = PhoneLocalAttentionBridge(
        ledger = RoomAttentionLedger(database),
        metadataTools = DeviceMetadataToolExecutor(
            database = database,
            folders = authorizedFoldersRepository,
            sharedStorage = sharedStorageRepository,
            capabilityRegistry = androidCapabilityRegistry,
        ),
        contentReadHandler = DeviceContentReadExecutor(
            database = database,
            folders = authorizedFoldersRepository,
            sharedStorage = sharedStorageRepository,
        ),
        fileChangeHandler = phoneLocalFileChangeExecutor,
        projectTools = phoneLocalProjectToolExecutor,
        attachmentTools = phoneLocalAttachmentToolExecutor,
        mediaTools = mediaListToolExecutor,
        mediaMutationTools = mediaMutationToolExecutor,
        calendarTools = PhoneLocalCalendarToolExecutor.create(
            application,
            androidCapabilityRegistry,
        ),
        contactsTools = PhoneLocalContactsToolExecutor.create(
            application,
            androidCapabilityRegistry,
        ),
        clipboardTools = PhoneLocalClipboardToolExecutor.create(application),
        locationTools = PhoneLocalLocationToolExecutor.create(
            application,
            androidCapabilityRegistry,
        ),
        notificationTools = PhoneLocalNotificationToolExecutor.create(application),
        screenCaptureTools = phoneLocalScreenCaptureToolExecutor,
        uiTools = phoneLocalUiToolExecutor,
        packageTools = phoneLocalShizukuToolExecutor,
        capabilityRequestTools = phoneLocalCapabilityRequestToolExecutor,
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
