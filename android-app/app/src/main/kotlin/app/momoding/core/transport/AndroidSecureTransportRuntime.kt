package app.momoding.core.transport

import android.content.Context
import app.momoding.wire.ReliabilityReceiver
import app.momoding.core.auth.AndroidKeystoreAead
import app.momoding.core.auth.AndroidNoBackupVaultFileStore
import app.momoding.core.auth.CredentialVault
import app.momoding.core.auth.HostBindingCoordinator
import app.momoding.core.auth.HostBindingStatus
import app.momoding.core.auth.HostConnectionStopper
import app.momoding.core.auth.HostRoomEraser
import app.momoding.core.auth.HostStateStore
import app.momoding.core.auth.PreferencesHostStateStore
import app.momoding.core.auth.ReconciledBinding
import app.momoding.core.auth.VaultSecret
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.files.AuthorizedFoldersRepository
import app.momoding.core.files.DeviceMetadataToolExecutor
import app.momoding.core.files.DeviceContentReadExecutor
import app.momoding.core.files.DeviceFileChangeExecutor
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.RoomProjectionTransactionStore
import app.momoding.core.data.RoomTaskListMerger
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Secure transport composition. It intentionally exposes no product or UI behavior. */
class AndroidSecureTransportRuntime(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val database: MomodingDatabase = MomodingDatabase.open(context),
    private val observer: WssActorObserver = object : WssActorObserver {},
    private val connectionActorFactory: (() -> SecureTransportActor)? = null,
) {
    private val appContext = context.applicationContext
    private val stateStore: HostStateStore = PreferencesHostStateStore.get(appContext, applicationScope)
    private val vault = CredentialVault(
        AndroidKeystoreAead(),
        AndroidNoBackupVaultFileStore(appContext),
    )
    private val actorMutex = Mutex()
    private val bindingMutex = Mutex()
    private val taskListSyncMutex = Mutex()

    @Volatile
    private var activeActor: SecureTransportActor? = null
    @Volatile
    private var activeSnapshot: app.momoding.core.auth.HostBindingSnapshot? = null
    private var actorStatusJob: Job? = null
    private val mutableUiStatus = MutableStateFlow(SecureTransportUiStatus())
    val uiStatus: StateFlow<SecureTransportUiStatus> = mutableUiStatus.asStateFlow()
    private val mutableTaskReplayProgress = MutableStateFlow<Map<String, TaskReplayProgress>>(emptyMap())
    val taskReplayProgress: StateFlow<Map<String, TaskReplayProgress>> = mutableTaskReplayProgress.asStateFlow()

    private val coordinator = HostBindingCoordinator(
        stateStore = stateStore,
        vault = vault,
        roomEraser = HostRoomEraser { database.momodingDao().eraseHostScopedData() },
        connectionStopper = HostConnectionStopper { stopActiveActor() },
    )
    private val pairingAttemptOwner = PairingAttemptOwner(
        prepare = coordinator::preparePair,
        commit = coordinator::commitPair,
        clearPrepared = coordinator::cancelPreparedPair,
    )

    suspend fun reconcileStartup(): ReconciledBinding = withContext(Dispatchers.IO) {
        bindingMutex.withLock {
            coordinator.reconcileStartup().also { binding ->
                when (binding) {
                    ReconciledBinding.Unpaired -> mutableUiStatus.value = SecureTransportUiStatus(
                        SecureTransportUiPhase.UNPAIRED,
                    )
                    is ReconciledBinding.Pairing -> mutableUiStatus.value = SecureTransportUiStatus(
                        phase = SecureTransportUiPhase.PAIRING,
                        pairingRecovery = true,
                    )
                    is ReconciledBinding.CredentialLost -> mutableUiStatus.value = SecureTransportUiStatus(
                        phase = SecureTransportUiPhase.CREDENTIAL_LOST,
                        hostAlias = binding.snapshot.profile?.hostAlias,
                        profile = binding.snapshot.profile,
                        configRevision = binding.snapshot.profile?.configRevision,
                    )
                    is ReconciledBinding.Active -> {
                        publishActive(binding.snapshot, SecureTransportUiPhase.CONNECTING)
                        startActorInBackground(binding.secret, binding.snapshot)
                    }
                }
            }
        }
    }

    suspend fun pair(
        endpoint: String,
        spkiPin: String,
        pairingCode: String,
        deviceName: String,
    ): ReconciledBinding.Active = withContext(Dispatchers.IO) {
        try {
            when (val result = pairingAttemptOwner.pair(
                endpoint,
                spkiPin,
                pairingCode,
                deviceName,
                onRegistered = {
                    mutableUiStatus.value = SecureTransportUiStatus(SecureTransportUiPhase.PAIRING)
                },
            )) {
                is PairingAttemptResult.Active -> result.binding.also { active ->
                    publishActive(active.snapshot, SecureTransportUiPhase.CONNECTING)
                    startActorInBackground(active.secret, active.snapshot)
                }
                is PairingAttemptResult.Cancelled -> {
                    publishPairingRecovery(result.responseMayHaveReachedHost)
                    throw PairingCancelledException(result.responseMayHaveReachedHost)
                }
            }
        } catch (error: PairingAttemptFailedException) {
            publishPairingRecovery(error.responseMayHaveReachedHost)
            throw error
        }
    }

    suspend fun cancelPairing(): PairingCancelResult = withContext(Dispatchers.IO) {
        pairingAttemptOwner.cancel().also { result ->
            if (result in setOf(
                    PairingCancelResult.CANCEL_WON_BEFORE_SEND,
                    PairingCancelResult.CANCEL_WON_AFTER_SEND,
                )
            ) {
                val recovery = result == PairingCancelResult.CANCEL_WON_AFTER_SEND
                mutableUiStatus.value = SecureTransportUiStatus(
                    phase = if (recovery) SecureTransportUiPhase.PAIRING else SecureTransportUiPhase.UNPAIRED,
                    pairingRecovery = recovery,
                )
            }
        }
    }

    suspend fun refreshProfile() = withContext(Dispatchers.IO) {
        bindingMutex.withLock {
            val current = requireNotNull(stateStore.read()) { "Host binding is missing" }
            require(current.status == HostBindingStatus.ACTIVE) { "Host binding is not active" }
            val secret = requireNotNull(vault.load()) { "Credential vault is missing" }
            val profile = PairingClient().refreshProfile(secret)
            stateStore.write(
                current.copy(
                    profile = profile,
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            mutableUiStatus.value = mutableUiStatus.value.copy(
                hostAlias = profile.hostAlias,
                profile = profile,
                configRevision = profile.configRevision,
            )
            activeSnapshot = current.copy(
                profile = profile,
                updatedAtMillis = System.currentTimeMillis(),
            )
            profile
        }
    }

    suspend fun synchronizeTaskList(): TaskListSyncResult = withContext(Dispatchers.IO) {
        taskListSyncMutex.withLock {
            val actor = actorMutex.withLock {
                requireNotNull(activeActor) { "Host connection is not started" }
            }
            val publisher = BindingScopedTaskListPublisher(
                bindingMutex = bindingMutex,
                stateStore = stateStore,
                leaseIsCurrent = { activeActor === actor },
                merger = RoomTaskListMerger(database),
            )
            TaskListSynchronizer(actor, publisher).synchronize()
        }
    }

    suspend fun submitExact(request: OutboundWireRequest): app.momoding.wire.ReceivedReliabilityServerFrame =
        withContext(Dispatchers.IO) {
            val actor = actorMutex.withLock {
                requireNotNull(activeActor) { "Host connection is not started" }
            }
            actor.requestExact(request)
        }

    suspend fun submitAttentionDecision(decision: AttentionUserDecision): Unit =
        withContext(Dispatchers.IO) {
            val actor = actorMutex.withLock {
                requireNotNull(activeActor) { "Host connection is not started" }
            }
            actor.submitAttentionDecision(decision)
        }

    suspend fun openTask(taskId: String): ExactWireOutcome<TaskOpenSuccess> {
        val requestId = ClientWireCodec.newRequestId()
        val response = submitExact(
            OutboundWireRequest(
                requestId = requestId,
                kind = "task.open",
                canonicalPayload = ClientWireCodec.encodeTaskOpen(requestId, taskId),
                mutating = false,
                taskId = taskId,
            ),
        )
        return TaskCreationResponseDecoder.decodeTaskOpen(requestId, response)
    }

    suspend fun unpair() = withContext(Dispatchers.IO) {
        bindingMutex.withLock {
            coordinator.unpair()
            activeSnapshot = null
            mutableUiStatus.value = SecureTransportUiStatus(SecureTransportUiPhase.UNPAIRED)
        }
    }

    suspend fun retryConnection() = withContext(Dispatchers.IO) {
        actorMutex.withLock { activeActor }?.retryNow()
    }

    suspend fun closeConnection() {
        stopActiveActor()
    }

    private suspend fun startActorInBackground(secret: VaultSecret, snapshot: app.momoding.core.auth.HostBindingSnapshot) {
        val actor = actorMutex.withLock {
            if (activeActor != null) null else createActor().also {
                activeActor = it
                activeSnapshot = snapshot
            }
        } ?: return
        actorStatusJob?.cancel()
        actorStatusJob = applicationScope.launch {
            actor.status.collect { status ->
                if (activeActor === actor) publishActorStatus(status)
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                actor.start(secret)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (actor.status.value.phase !in setOf(WssConnectionPhase.OFFLINE, WssConnectionPhase.FATAL)) {
                    val detached = actorMutex.withLock {
                        if (activeActor === actor) {
                            activeActor = null
                            true
                        } else {
                            false
                        }
                    }
                    if (detached) {
                        actorStatusJob?.cancel()
                        actorStatusJob = null
                        actor.stopAndJoin()
                        activeSnapshot?.let { publishActive(it, SecureTransportUiPhase.ERROR) }
                    }
                }
            }
        }
    }

    internal suspend fun startActorForStatusTest(
        secret: VaultSecret,
        snapshot: app.momoding.core.auth.HostBindingSnapshot,
    ) {
        publishActive(snapshot, SecureTransportUiPhase.CONNECTING)
        startActorInBackground(secret, snapshot)
    }

    private suspend fun stopActiveActor() {
        val actor = actorMutex.withLock {
            activeActor.also { activeActor = null }
        }
        actor?.stopAndJoin()
        actorStatusJob?.cancel()
        actorStatusJob = null
        mutableTaskReplayProgress.value = emptyMap()
    }

    private fun publishActive(
        snapshot: app.momoding.core.auth.HostBindingSnapshot,
        phase: SecureTransportUiPhase,
    ) {
        mutableUiStatus.value = SecureTransportUiStatus(
            phase = phase,
            hostAlias = snapshot.profile?.hostAlias,
            profile = snapshot.profile,
            configRevision = snapshot.profile?.configRevision,
        )
    }

    private fun publishPairingRecovery(responseMayHaveReachedHost: Boolean) {
        mutableUiStatus.value = SecureTransportUiStatus(
            phase = if (responseMayHaveReachedHost) {
                SecureTransportUiPhase.PAIRING
            } else {
                SecureTransportUiPhase.UNPAIRED
            },
            pairingRecovery = responseMayHaveReachedHost,
        )
    }

    private fun publishActorStatus(status: WssConnectionStatus) {
        if (status.phase == WssConnectionPhase.STOPPED) return
        mutableTaskReplayProgress.value = status.replayProgress
        val phase = when (status.phase) {
            WssConnectionPhase.STOPPED -> return
            WssConnectionPhase.CONNECTING -> SecureTransportUiPhase.CONNECTING
            WssConnectionPhase.HELLO_PENDING -> SecureTransportUiPhase.AUTHENTICATING
            WssConnectionPhase.CAPABILITY_SYNCING -> SecureTransportUiPhase.SYNCHRONIZING
            WssConnectionPhase.REPLAYING -> SecureTransportUiPhase.SYNCHRONIZING
            WssConnectionPhase.ONLINE -> SecureTransportUiPhase.READY
            WssConnectionPhase.BACKING_OFF -> SecureTransportUiPhase.RECONNECTING
            WssConnectionPhase.OFFLINE -> SecureTransportUiPhase.OFFLINE
            WssConnectionPhase.FATAL -> when (status.terminalCause) {
                WssTerminalCause.PROTOCOL_MISMATCH -> SecureTransportUiPhase.VERSION_MISMATCH
                WssTerminalCause.CREDENTIAL_REVOKED -> SecureTransportUiPhase.HOST_REVOKED
                else -> SecureTransportUiPhase.ERROR
            }
        }
        activeSnapshot?.let { publishActive(it, phase) }
    }

    private fun createActor(): SecureTransportActor {
        connectionActorFactory?.let { return it() }
        val transferRoot = appContext.cacheDir.toPath().resolve("wire-transfers")
        Files.createDirectories(transferRoot)
        val commandJournal = RoomCommandDraftJournal(database)
        val folders = AuthorizedFoldersRepository(appContext, database)
        val fileChanges = DeviceFileChangeExecutor(database, folders)
        return WssActor(
            scope = applicationScope,
            connectorFactory = ExactPinnedWssConnectorFactory(),
            receiverFactory = ReliabilityReceiverFactory {
                val connectionRoot = transferRoot.resolve(UUID.randomUUID().toString())
                Files.createDirectories(connectionRoot)
                ReliabilityReceiver(
                    connectionRoot,
                    RoomProjectionTransactionStore(database),
                )
            },
            resumeCursorSource = ResumeCursorSource {
                database.momodingDao().allTasks()
                    .asSequence()
                    .filter { it.streamId != null }
                    .sortedByDescending { it.updatedAtMillis }
                    .take(MAX_RESUME_CURSORS)
                    .map { task ->
                        DurableResumeCursor(
                            taskId = task.taskId,
                            streamId = requireNotNull(task.streamId),
                            lastAckedSequence = task.throughSequence,
                        )
                    }
                    .toList()
            },
            commandJournal = commandJournal,
            attentionCoordinator = AttentionApplicationCoordinator(
                RoomAttentionLedger(database),
                commandJournal,
                contentReadHandler = DeviceContentReadExecutor(
                    database,
                    folders,
                ),
                fileChangeHandler = fileChanges,
            ),
            metadataToolExecutor = DeviceMetadataToolExecutor(
                database,
                folders,
            ),
            fileChangeExecutor = fileChanges,
            observer = observer,
        )
    }

    private companion object {
        const val MAX_RESUME_CURSORS = 128
    }
}

class PairingCancelledException(
    val responseMayHaveReachedHost: Boolean,
) : Exception("Pairing wait was cancelled")
