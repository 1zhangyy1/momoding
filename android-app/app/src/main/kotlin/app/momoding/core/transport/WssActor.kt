package app.momoding.core.transport

import app.momoding.BuildConfig
import app.momoding.wire.AckReady
import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.DeviceClientFrameEncoder
import app.momoding.wire.DeviceToolCancelFrame
import app.momoding.wire.DeviceToolReconcileRequestFrame
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.FrameReady
import app.momoding.wire.HelloAcceptedFrame
import app.momoding.wire.P1aProtocol
import app.momoding.wire.ProjectionCommitted
import app.momoding.wire.PiEventAckEncoder
import app.momoding.wire.ReceivedP1bServerFrame
import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.ReceiverAction
import app.momoding.wire.ReceiverFailure
import app.momoding.wire.ReceiverFailureCode
import app.momoding.wire.ReliabilityReceiver
import app.momoding.wire.WireErrorCode
import app.momoding.wire.WireErrorFrame
import app.momoding.core.auth.HostConnectionStopper
import app.momoding.core.auth.VaultEnvelopeCodec
import app.momoding.core.auth.VaultSecret
import app.momoding.core.data.CommandDurabilityJournal
import app.momoding.core.files.DeviceMetadataToolHandler
import app.momoding.core.files.DeviceFileChangeExecutor
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class WssConnectionPhase {
    STOPPED,
    CONNECTING,
    HELLO_PENDING,
    CAPABILITY_SYNCING,
    REPLAYING,
    ONLINE,
    BACKING_OFF,
    OFFLINE,
    FATAL,
}

enum class WssTerminalCause {
    CREDENTIAL_REVOKED,
    PROTOCOL_MISMATCH,
    TLS_VALIDATION,
    INTERNAL,
}

data class WssReceiverFailureDiagnostic(
    val code: ReceiverFailureCode,
    val messageSha256: String,
    val failureGeneration: Long,
    val failureOrdinal: Long,
)

data class WssConnectionStatus(
    val phase: WssConnectionPhase,
    val reconnectAttempt: Int = 0,
    val nextDelayMillis: Long? = null,
    val reason: String? = null,
    val terminalCause: WssTerminalCause? = null,
    val replayProgress: Map<String, TaskReplayProgress> = emptyMap(),
    val connectionGeneration: Long = 0,
    val lastReceiverFailure: WssReceiverFailureDiagnostic? = null,
)

data class TaskReplayProgress(
    val taskId: String,
    val replayedEventCount: Long,
    val throughSequence: Long,
)

internal data class AttentionWakeIdentity(
    val generation: Long,
    val deadlineAtMillis: Long,
) {
    fun matches(generation: Long, deadlineAtMillis: Long): Boolean =
        this.generation == generation && this.deadlineAtMillis == deadlineAtMillis
}

data class OutboundWireRequest(
    val requestId: String,
    val kind: String,
    val canonicalPayload: String,
    val mutating: Boolean,
    val commandId: String? = null,
    val taskId: String? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}

fun interface ResumeCursorSource {
    fun loadDurableCursors(): List<DurableResumeCursor>
}

fun interface ReliabilityReceiverFactory {
    fun create(): ReliabilityReceiver
}

interface WssActorObserver {
    fun onReceiverAction(action: ReceiverAction) = Unit
    fun onProtocolFailure(reason: String) = Unit
}

interface SecureTransportActor : TaskListWirePort, HostConnectionStopper {
    val status: StateFlow<WssConnectionStatus>
    suspend fun start(secret: VaultSecret)
    suspend fun retryNow()
    suspend fun requestExact(request: OutboundWireRequest): ReceivedP1bServerFrame =
        throw UnsupportedOperationException("Exact Wire requests are unavailable")
    suspend fun submitAttentionDecision(decision: AttentionUserDecision): Unit {
        throw UnsupportedOperationException("Attention decisions are unavailable")
    }
}

interface WssSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
}

interface WssTransportListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(byteCount: Long)
    fun onClosed(code: Int)
    fun onFailure(fatalTls: Boolean)
}

fun interface WssConnector {
    fun connect(url: String, listener: WssTransportListener): WssSocket
}

fun interface WssConnectorFactory {
    fun create(secret: VaultSecret): WssConnector
}

class ExactPinnedWssConnectorFactory : WssConnectorFactory {
    override fun create(secret: VaultSecret): WssConnector {
        val endpoint = PinnedHostEndpoint.parse(secret.header.endpoint)
        val pin = SpkiPin.parse(secret.header.spkiPin)
        return OkHttpWssConnector(ExactPinnedHttpClientFactory.create(endpoint, pin))
    }
}

class OkHttpWssConnector(
    private val client: OkHttpClient,
) : WssConnector {
    override fun connect(url: String, listener: WssTransportListener): WssSocket {
        val delegate = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    listener.onBinary(bytes.size.toLong())

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, "")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosed(code)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onFailure(t.hasTlsCause())
            },
        )
        return object : WssSocket {
            override fun send(text: String): Boolean = delegate.send(text)
            override fun close(code: Int, reason: String): Boolean = delegate.close(code, reason)
            override fun cancel() = delegate.cancel()
        }
    }

    private fun Throwable.hasTlsCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SSLException || current is java.security.cert.CertificateException) return true
            current = current.cause
        }
        return false
    }
}

class WssActor(
    private val scope: CoroutineScope,
    private val connectorFactory: WssConnectorFactory,
    private val receiverFactory: ReliabilityReceiverFactory,
    private val resumeCursorSource: ResumeCursorSource,
    private val commandJournal: CommandDurabilityJournal? = null,
    private val attentionCoordinator: AttentionApplicationCoordinator? = null,
    private val metadataToolExecutor: DeviceMetadataToolHandler? = null,
    private val fileChangeExecutor: DeviceFileChangeExecutor? = null,
    private val observer: WssActorObserver = object : WssActorObserver {},
    private val reconnectPolicy: FullJitterReconnectPolicy = FullJitterReconnectPolicy(),
    private val receiverReceive: (ReliabilityReceiver, ByteArray) -> List<ReceiverAction> =
        ReliabilityReceiver::receive,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val clientVersion: String = BuildConfig.VERSION_NAME,
) : SecureTransportActor {
    private val mailbox = Channel<ActorMessage>(MAILBOX_CAPACITY)
    private val callerLifecycleMutex = Mutex()
    private var acceptingCallerMessages = true
    private val requestTable = RequestTable(nowMillis)
    private val mutableStatus = MutableStateFlow(WssConnectionStatus(WssConnectionPhase.STOPPED))
    override val status: StateFlow<WssConnectionStatus> = mutableStatus.asStateFlow()
    private val actorJob = scope.launch {
        try {
            actorLoop()
        } finally {
            mailbox.close()
        }
    }

    override suspend fun start(secret: VaultSecret) {
        val completion = CompletableDeferred<Unit>()
        sendCallerMessage(ActorMessage.Start(secret, completion))
        completion.await()
    }

    override suspend fun requestExact(request: OutboundWireRequest): ReceivedP1bServerFrame {
        val completion = CompletableDeferred<ReceivedP1bServerFrame>()
        sendCallerMessage(ActorMessage.Submit(request, completion))
        return completion.await()
    }

    suspend fun request(request: OutboundWireRequest): ReceivedP1bServerFrame = requestExact(request)

    override suspend fun requestPage(cursor: String?, limit: Int): ReceivedP1bServerFrame {
        val requestId = ClientWireCodec.newRequestId()
        return requestExact(
            OutboundWireRequest(
                requestId = requestId,
                kind = "task.list",
                canonicalPayload = ClientWireCodec.encodeTaskList(requestId, cursor, limit),
                mutating = false,
            ),
        )
    }

    override suspend fun retryNow() {
        sendCallerMessage(ActorMessage.Retry)
    }

    suspend fun signalConnectivityRestored() = retryNow()

    suspend fun signalForegrounded() = retryNow()

    override suspend fun submitAttentionDecision(decision: AttentionUserDecision) {
        val completion = CompletableDeferred<Unit>()
        sendCallerMessage(ActorMessage.AttentionDecision(decision, completion))
        completion.await()
    }

    override suspend fun stopAndJoin() {
        callerLifecycleMutex.withLock {
            if (!acceptingCallerMessages) return@withLock
            acceptingCallerMessages = false
            try {
                if (!actorJob.isCompleted) mailbox.send(ActorMessage.Stop)
            } catch (_: ClosedSendChannelException) {
                // Another concurrent stop completed first.
            }
        }
        actorJob.join()
    }

    private suspend fun sendCallerMessage(message: ActorMessage) {
        callerLifecycleMutex.withLock {
            check(acceptingCallerMessages && !actorJob.isCompleted) {
                "WSS actor is stopping or stopped"
            }
            try {
                mailbox.send(message)
            } catch (error: ClosedSendChannelException) {
                throw IllegalStateException("WSS actor is stopping or stopped", error)
            }
        }
    }

    private suspend fun actorLoop() {
        var secret: VaultSecret? = null
        var connector: WssConnector? = null
        var socket: WssSocket? = null
        var receiver: ReliabilityReceiver? = null
        var generation = 0L
        var helloRequestId: String? = null
        var startCompletion: CompletableDeferred<Unit>? = null
        var helloTimer: Job? = null
        var attentionTimer: Job? = null
        var attentionWakeIdentity: AttentionWakeIdentity? = null
        var reconnectTimer: Job? = null
        var reconnectAttempt = 0
        var retryToken = 0L
        var receiverFailureOrdinal = 0L
        var lastReceiverFailure: WssReceiverFailureDiagnostic? = null
        var online = false
        var attentionReady = attentionCoordinator == null
        var preparedResumeCursors = emptyList<DurableResumeCursor>()
        val replayStartByTask = mutableMapOf<String, Long>()
        val replayProgressByTask = mutableMapOf<String, TaskReplayProgress>()
        var running = true

        fun writerSend(text: String): Boolean {
            return socket?.send(text) == true
        }

        fun publishStatus(status: WssConnectionStatus) {
            mutableStatus.value = status.copy(
                connectionGeneration = generation,
                lastReceiverFailure = lastReceiverFailure,
            )
        }

        fun closeTransport() {
            helloTimer?.cancel()
            helloTimer = null
            attentionTimer?.cancel()
            attentionTimer = null
            attentionWakeIdentity = null
            socket?.close(PROTOCOL_CLOSE_CODE, "")
            socket?.cancel()
            socket = null
            receiver?.let { runCatching { it.close() } }
            receiver = null
            online = false
            attentionReady = attentionCoordinator == null
            preparedResumeCursors = emptyList()
            replayStartByTask.clear()
            replayProgressByTask.clear()
        }

        fun enterFatal(
            reason: String,
            error: Throwable = IOException(reason),
            terminalCause: WssTerminalCause = WssTerminalCause.INTERNAL,
        ) {
            closeTransport()
            reconnectTimer?.cancel()
            reconnectTimer = null
            publishStatus(WssConnectionStatus(
                WssConnectionPhase.FATAL,
                reason = reason,
                terminalCause = terminalCause,
            ))
            startCompletion?.completeExceptionally(error)
            startCompletion = null
            requestTable.failAll(error)
            runCatching { observer.onProtocolFailure(reason) }
        }

        fun scheduleReconnect(reason: String) {
            closeTransport()
            if (mutableStatus.value.phase == WssConnectionPhase.FATAL) return
            if (reconnectAttempt >= FullJitterReconnectPolicy.MAX_AUTOMATIC_ATTEMPTS) {
                publishStatus(WssConnectionStatus(
                    WssConnectionPhase.OFFLINE,
                    reconnectAttempt = reconnectAttempt,
                    reason = reason,
                ))
                startCompletion?.completeExceptionally(IOException("Host is offline"))
                startCompletion = null
                return
            }
            reconnectAttempt += 1
            val delayMillis = reconnectPolicy.delayMillis(reconnectAttempt)
            val token = ++retryToken
            publishStatus(WssConnectionStatus(
                WssConnectionPhase.BACKING_OFF,
                reconnectAttempt = reconnectAttempt,
                nextDelayMillis = delayMillis,
                reason = reason,
            ))
            reconnectTimer?.cancel()
            reconnectTimer = scope.launch {
                delay(delayMillis)
                try {
                    mailbox.send(ActorMessage.Reconnect(token))
                } catch (_: ClosedSendChannelException) {
                    // The actor was stopped while the timer was pending.
                }
            }
        }

        fun scheduleProtocolRecovery(reason: String) {
            runCatching { observer.onProtocolFailure(reason) }
            scheduleReconnect(reason)
        }

        fun publishReadyPhase() {
            publishStatus(WssConnectionStatus(
                if (replayStartByTask.isEmpty()) {
                    WssConnectionPhase.ONLINE
                } else {
                    WssConnectionPhase.REPLAYING
                },
                replayProgress = replayProgressByTask.toMap(),
            ))
        }

        fun publishSynchronizingPhase() {
            publishStatus(WssConnectionStatus(
                WssConnectionPhase.CAPABILITY_SYNCING,
                replayProgress = replayProgressByTask.toMap(),
            ))
        }

        fun scheduleRequestExpiry(
            requestId: String,
            deadlineAtMillis: Long,
            generationScoped: Boolean = false,
        ) {
            val timerGeneration = generation
            scope.launch {
                delay((deadlineAtMillis - nowMillis()).coerceAtLeast(0))
                try {
                    mailbox.send(ActorMessage.Expire(
                        requestId,
                        timerGeneration,
                        deadlineAtMillis,
                        generationScoped,
                    ))
                } catch (_: ClosedSendChannelException) {
                    // The actor was stopped while the timer was pending.
                }
            }
        }

        fun scheduleAttentionWake(deadlineAtMillis: Long?) {
            attentionTimer?.cancel()
            attentionTimer = null
            attentionWakeIdentity = null
            if (deadlineAtMillis == null || attentionCoordinator == null || !online) return
            val timerGeneration = generation
            attentionWakeIdentity = AttentionWakeIdentity(timerGeneration, deadlineAtMillis)
            attentionTimer = scope.launch {
                delay((deadlineAtMillis - nowMillis()).coerceAtLeast(0))
                try {
                    mailbox.send(ActorMessage.AttentionWake(timerGeneration, deadlineAtMillis))
                } catch (_: ClosedSendChannelException) {
                    // The actor was stopped while the timer was pending.
                }
            }
        }

        fun registerAttentionCommand(command: AttentionCoordinatorCommand): PendingWireRequest {
            val request = command.request
            require(request.kind in ATTENTION_COMMAND_KINDS) {
                "Coordinator returned a non-attention command"
            }
            require(request.mutating) { "Attention commands must be durable" }
            val existing = requestTable.allPending().singleOrNull {
                it.requestId == request.requestId
            }
            val pendingCandidate = if (existing == null) {
                PendingWireRequest(
                    requestId = request.requestId,
                    kind = request.kind,
                    canonicalPayload = request.canonicalPayload,
                    mutating = true,
                    commandId = request.commandId,
                    taskId = request.taskId,
                    deadlineAtMillis = nowMillis() + request.timeoutMillis,
                    completion = CompletableDeferred(),
                    generation = generation,
                )
            } else {
                null
            }
            val reopensReconcileTombstone = pendingCandidate != null &&
                request.kind == RECONCILE_RESULT_KIND &&
                requestTable.lookup(request.requestId, generation) == ResponseLookup.Tombstoned
            if (reopensReconcileTombstone) {
                requestTable.requireDurableReconcileReplay(requireNotNull(pendingCandidate))
            }
            val journal = requireNotNull(commandJournal) {
                "Attention command journal is unavailable"
            }
            journal.persistAccepted(
                request.requestId,
                request.commandId,
                request.kind,
                request.taskId,
                request.canonicalPayload,
            )
            if (existing != null) {
                require(existing.kind == request.kind &&
                    existing.commandId == request.commandId &&
                    existing.taskId == request.taskId &&
                    existing.canonicalPayload == request.canonicalPayload
                ) { "Registered attention command binding changed" }
                existing.generation = generation
                existing.deadlineAtMillis = nowMillis() + request.timeoutMillis
                return existing
            }
            require(command.register) {
                "Coordinator expected an existing registered attention command"
            }
            val pending = requireNotNull(pendingCandidate)
            if (reopensReconcileTombstone) {
                requestTable.registerDurableReconcileReplay(pending)
            } else {
                requestTable.register(pending)
            }
            return pending
        }

        fun sendAttentionCommand(command: AttentionCoordinatorCommand): Boolean {
            val pending = registerAttentionCommand(command)
            scheduleRequestExpiry(
                pending.requestId,
                pending.deadlineAtMillis,
                generationScoped = true,
            )
            if (!writerSend(pending.canonicalPayload)) {
                scheduleReconnect("Attention command send failed")
                return false
            }
            return true
        }

        fun submitSnapshotRequest(taskId: String): Boolean {
            val requestId = ClientWireCodec.newRequestId()
            val payload = ClientWireCodec.encodeTaskSnapshotRequest(requestId, taskId)
            val deadlineAt = nowMillis() + OutboundWireRequest.DEFAULT_TIMEOUT_MILLIS
            val pending = PendingWireRequest(
                requestId = requestId,
                kind = SNAPSHOT_REQUEST_KIND,
                canonicalPayload = payload,
                mutating = false,
                commandId = null,
                taskId = taskId,
                deadlineAtMillis = deadlineAt,
                completion = CompletableDeferred(),
                generation = generation,
            )
            requestTable.register(pending)
            scheduleRequestExpiry(requestId, deadlineAt)
            if (!writerSend(payload)) {
                scheduleReconnect("Attention snapshot request send failed")
                return false
            }
            return true
        }

        fun processAttentionPlan(plan: AttentionCoordinatorPlan): Boolean {
            plan.protocolRecoveryReason?.let { reason ->
                scheduleProtocolRecovery(reason)
                return false
            }
            if (plan.capabilityReady == false) attentionReady = false
            if (plan.capabilityReady == true && !attentionReady) {
                attentionReady = true
                publishReadyPhase()
                startCompletion?.complete(Unit)
                startCompletion = null
                requestTable.allPending()
                    .filter { it.kind !in ATTENTION_COMMAND_KINDS }
                    .forEach { pending ->
                        if (!writerSend(pending.canonicalPayload)) {
                            scheduleReconnect("Pending request resend failed")
                            return false
                        }
                    }
            }
            plan.commands.forEach { command ->
                if (!sendAttentionCommand(command)) return false
            }
            val snapshotTasks = linkedSetOf<String>()
            plan.oneWayFrames.forEach { frame ->
                val sent = writerSend(frame.canonicalPayload)
                if (frame.discardLiveContentAfterAttempt) {
                    try {
                        requireNotNull(frame.taskId)
                        requireNotNull(frame.callId)
                        requireNotNull(attentionCoordinator).discardLiveContentRead(
                            frame.taskId,
                            frame.callId,
                        )
                    } catch (error: Throwable) {
                        scheduleProtocolRecovery("Live content delivery disposal failed")
                        return false
                    }
                    if (sent) snapshotTasks += frame.taskId
                }
                if (!sent) {
                    scheduleReconnect(
                        if (frame.discardLiveContentAfterAttempt) {
                            "Live content terminal send failed"
                        } else {
                            "Attention progress send failed"
                        },
                    )
                    return false
                }
            }
            plan.terminalCandidates.forEach { operation ->
                val terminal = requireNotNull(operation.terminalFrameCanonicalJson) {
                    "Attention terminal candidate has no exact frame"
                }
                if (!writerSend(terminal)) {
                    scheduleReconnect("Attention terminal send failed")
                    return false
                }
                try {
                    requireNotNull(attentionCoordinator).markTerminalSent(operation.callId)
                } catch (error: Throwable) {
                    scheduleProtocolRecovery("Attention terminal delivery commit failed")
                    return false
                }
                snapshotTasks += operation.taskId
            }
            snapshotTasks.forEach { taskId ->
                if (!submitSnapshotRequest(taskId)) return false
            }
            scheduleAttentionWake(plan.nextWakeAtMillis)
            return true
        }

        fun applyAttentionPlan(plan: AttentionCoordinatorPlan, failureReason: String): Boolean =
            try {
                processAttentionPlan(plan)
            } catch (error: Throwable) {
                enterFatal(failureReason, error)
                false
            }

        fun connectCurrent() {
            val activeSecret = secret ?: return
            closeTransport()
            generation += 1
            val currentGeneration = generation
            receiver = try {
                receiverFactory.create()
            } catch (error: Throwable) {
                enterFatal("Reliability receiver initialization failed", error)
                return
            }
            publishStatus(WssConnectionStatus(
                WssConnectionPhase.CONNECTING,
                reconnectAttempt = reconnectAttempt,
            ))
            val socketReference = AtomicReference<WssSocket?>()
            val overflowReported = AtomicBoolean(false)
            val listener = object : WssTransportListener {
                private fun enqueue(message: ActorMessage) {
                    if (mailbox.trySend(message).isSuccess) return
                    socketReference.get()?.cancel()
                    if (overflowReported.compareAndSet(false, true)) {
                        scope.launch {
                            try {
                                mailbox.send(ActorMessage.MailboxOverflow(currentGeneration))
                            } catch (_: ClosedSendChannelException) {
                                // The actor was stopped after the transport was cancelled.
                            }
                        }
                    }
                }

                override fun onOpen() = enqueue(ActorMessage.Opened(currentGeneration))

                override fun onText(text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > P1aProtocol.MAX_FRAME_BYTES) {
                        enqueue(ActorMessage.Oversized(currentGeneration))
                    } else {
                        enqueue(ActorMessage.Text(currentGeneration, bytes.copyOf()))
                    }
                }

                override fun onBinary(byteCount: Long) =
                    enqueue(ActorMessage.Binary(currentGeneration, byteCount))

                override fun onClosed(code: Int) = enqueue(ActorMessage.Closed(currentGeneration, code))

                override fun onFailure(fatalTls: Boolean) =
                    enqueue(ActorMessage.TransportFailure(currentGeneration, fatalTls))
            }
            try {
                val nextSocket = requireNotNull(connector).connect(
                    PinnedHostEndpoint.parse(activeSecret.header.endpoint).webSocketUrl,
                    listener,
                )
                socketReference.set(nextSocket)
                socket = nextSocket
            } catch (error: Throwable) {
                if (error.hasTlsCause()) {
                    enterFatal("TLS validation failed", error, WssTerminalCause.TLS_VALIDATION)
                }
                else scheduleReconnect("Connection setup failed")
            }
        }

        fun handleResponse(received: ReceivedP1bServerFrame, currentGeneration: Long) {
            val frame = received.frame as CommandResponseFrame
            when (val lookup = requestTable.lookup(frame.requestId, currentGeneration)) {
                is ResponseLookup.Pending -> {
                    val pending = lookup.request
                    try {
                        if (pending.mutating) {
                            requireNotNull(commandJournal) { "Mutating command journal is unavailable" }
                                .markTerminal(frame.requestId, received.rawBytes.decodeToString())
                        }
                        val attentionPlan = if (
                            attentionCoordinator != null && pending.kind in ATTENTION_COMMAND_KINDS
                        ) {
                            attentionCoordinator.onCommandResponse(
                                pending,
                                received,
                                requestTable.allPending().mapTo(linkedSetOf()) { it.requestId },
                            )
                        } else {
                            null
                        }
                        requestTable.complete(frame.requestId)
                        pending.completion.complete(received)
                        attentionPlan?.let(::processAttentionPlan)
                    } catch (error: Throwable) {
                        enterFatal("Terminal response durability failed", error)
                    }
                }
                ResponseLookup.Tombstoned,
                ResponseLookup.StaleGeneration,
                -> Unit
                ResponseLookup.Unknown -> scheduleReconnect("Unknown response requestId")
            }
        }

        suspend fun handleActions(actions: List<ReceiverAction>, currentGeneration: Long) {
            fun handleWireError(frame: WireErrorFrame) {
                when (frame.error.code) {
                    WireErrorCode.UNAUTHORIZED -> enterFatal(
                        "Device credential was revoked",
                        terminalCause = WssTerminalCause.CREDENTIAL_REVOKED,
                    )
                    WireErrorCode.PROTOCOL_MISMATCH -> enterFatal(
                        "Wire version mismatch",
                        terminalCause = WssTerminalCause.PROTOCOL_MISMATCH,
                    )
                    else -> scheduleProtocolRecovery("Wire transport error")
                }
            }

            for (action in actions) {
                when (action) {
                    is ReceiverFailure -> {
                        receiverFailureOrdinal += 1
                        lastReceiverFailure = WssReceiverFailureDiagnostic(
                            code = action.code,
                            messageSha256 = action.message.sha256Utf8(),
                            failureGeneration = currentGeneration,
                            failureOrdinal = receiverFailureOrdinal,
                        )
                        scheduleReconnect("Reliability receiver failure")
                        return
                    }
                    is AckReady -> if (!writerSend(PiEventAckEncoder.encode(action.frame).decodeToString())) {
                        scheduleReconnect("ACK send failed")
                        return
                    }
                    is FrameReady -> {
                        val frame = action.received.frame
                        if (!online) {
                            if (frame is HelloAcceptedFrame && frame.requestId == helloRequestId) {
                                helloTimer?.cancel()
                                helloTimer = null
                                online = true
                                reconnectAttempt = 0
                                replayStartByTask.clear()
                                preparedResumeCursors.forEach { cursor ->
                                    replayStartByTask[cursor.taskId] = cursor.lastAckedSequence
                                }
                                replayProgressByTask.clear()
                                preparedResumeCursors.forEach { cursor ->
                                    replayProgressByTask[cursor.taskId] = TaskReplayProgress(
                                        taskId = cursor.taskId,
                                        replayedEventCount = 0,
                                        throughSequence = cursor.lastAckedSequence,
                                    )
                                }
                                val pending = requestTable.updateGenerationAndPendingPayloads(
                                    currentGeneration,
                                )
                                if (attentionCoordinator == null) {
                                    attentionReady = true
                                    publishReadyPhase()
                                    startCompletion?.complete(Unit)
                                    startCompletion = null
                                    pending.forEach { request ->
                                        if (!writerSend(request.canonicalPayload)) {
                                            scheduleReconnect("Pending request resend failed")
                                            return
                                        }
                                    }
                                } else {
                                    attentionReady = false
                                    publishSynchronizingPhase()
                                    val plan = try {
                                        attentionCoordinator.beginGeneration(
                                            currentGeneration,
                                            requireNotNull(secret).deviceId,
                                            pending.mapTo(linkedSetOf()) { it.requestId },
                                        )
                                    } catch (_: Throwable) {
                                        scheduleProtocolRecovery(
                                            "Attention generation initialization failed",
                                        )
                                        return
                                    }
                                    if (!applyAttentionPlan(
                                            plan,
                                            "Attention generation plan application failed",
                                        )) return
                                }
                            } else if (frame is WireErrorFrame) {
                                handleWireError(frame)
                                return
                            } else {
                                scheduleProtocolRecovery("Unexpected frame before hello acceptance")
                                return
                            }
                        } else {
                            when (frame) {
                                is CommandResponseFrame -> handleResponse(action.received, currentGeneration)
                                is WireErrorFrame -> handleWireError(frame)
                                is DeviceToolRequestFrame -> {
                                    if (!attentionReady || attentionCoordinator == null) {
                                        scheduleProtocolRecovery(
                                            "Device request arrived before capability readiness",
                                        )
                                        return
                                    }
                                    if (fileChangeExecutor?.handlesPrepare(frame.toolName) == true) {
                                        val result = try {
                                            fileChangeExecutor.prepare(frame)
                                        } catch (_: Throwable) {
                                            scheduleProtocolRecovery(
                                                "Device file prepare request application failed",
                                            )
                                            return
                                        }
                                        if (!writerSend(
                                                DeviceClientFrameEncoder.encode(result)
                                                    .decodeToString(),
                                            )) {
                                            scheduleReconnect("Device file prepare result send failed")
                                            return
                                        }
                                        continue
                                    }
                                    if (metadataToolExecutor?.handles(frame.toolName) == true) {
                                        val result = try {
                                            metadataToolExecutor.execute(frame)
                                        } catch (_: Throwable) {
                                            scheduleProtocolRecovery(
                                                "Device metadata request application failed",
                                            )
                                            return
                                        }
                                        if (!writerSend(
                                                DeviceClientFrameEncoder.encode(result)
                                                    .decodeToString(),
                                            )) {
                                            scheduleReconnect("Device metadata result send failed")
                                            return
                                        }
                                        continue
                                    }
                                    val plan = try {
                                        attentionCoordinator.handleDeviceRequest(frame)
                                    } catch (_: Throwable) {
                                        scheduleProtocolRecovery("Device request application failed")
                                        return
                                    }
                                    if (!applyAttentionPlan(
                                            plan,
                                            "Device request plan application failed",
                                        )) return
                                }
                                is DeviceToolCancelFrame -> {
                                    if (!attentionReady || attentionCoordinator == null) {
                                        scheduleProtocolRecovery(
                                            "Device cancel arrived before capability readiness",
                                        )
                                        return
                                    }
                                    val plan = try {
                                        attentionCoordinator.handleDeviceCancel(frame)
                                    } catch (_: Throwable) {
                                        scheduleProtocolRecovery("Device cancel application failed")
                                        return
                                    }
                                    if (!applyAttentionPlan(
                                            plan,
                                            "Device cancel plan application failed",
                                        )) return
                                }
                                is DeviceToolReconcileRequestFrame -> {
                                    if (!attentionReady || attentionCoordinator == null) {
                                        scheduleProtocolRecovery(
                                            "Device reconcile arrived before capability readiness",
                                        )
                                        return
                                    }
                                    if (!requestTable.hasCompatibleReconcileProvenance(
                                            frame.requestId,
                                        )) {
                                        scheduleProtocolRecovery(
                                            "Reconcile requestId reuses another request binding",
                                        )
                                        return
                                    }
                                    val plan = try {
                                        attentionCoordinator.handleReconcileRequest(action.received)
                                    } catch (_: Throwable) {
                                        scheduleProtocolRecovery("Device reconcile application failed")
                                        return
                                    }
                                    if (!applyAttentionPlan(
                                            plan,
                                            "Device reconcile plan application failed",
                                        )) return
                                }
                                else -> observer.onReceiverAction(action)
                            }
                        }
                    }
                    is ProjectionCommitted -> {
                        val taskId = action.state.taskId
                        val replayStart = replayStartByTask[taskId]
                        if (replayStart != null) {
                            if (
                                action.kind == "pi.replay.complete" ||
                                (action.kind.startsWith("task.snapshot") && action.state.pendingResync == null)
                            ) {
                                replayStartByTask.remove(taskId)
                                replayProgressByTask.remove(taskId)
                                if (replayStartByTask.isEmpty()) {
                                    if (attentionReady) publishReadyPhase()
                                    else publishSynchronizingPhase()
                                } else {
                                    if (attentionReady) publishReadyPhase()
                                    else publishSynchronizingPhase()
                                }
                            } else {
                                replayProgressByTask[taskId] = TaskReplayProgress(
                                    taskId = taskId,
                                    replayedEventCount = (action.state.throughSequence - replayStart).coerceAtLeast(0),
                                    throughSequence = action.state.throughSequence,
                                )
                                if (attentionReady) publishReadyPhase()
                                else publishSynchronizingPhase()
                            }
                        }
                        if (attentionCoordinator != null) {
                            val plan = try {
                                attentionCoordinator.onProjectionCommitted(taskId)
                            } catch (_: Throwable) {
                                scheduleProtocolRecovery(
                                    "Attention projection proof application failed",
                                )
                                return
                            }
                            if (!applyAttentionPlan(
                                    plan,
                                    "Attention projection plan application failed",
                                )) return
                        }
                        observer.onReceiverAction(action)
                    }
                    else -> observer.onReceiverAction(action)
                }
            }
        }

        while (running) {
            when (val message = mailbox.receive()) {
                is ActorMessage.Start -> {
                    if (secret != null || mutableStatus.value.phase != WssConnectionPhase.STOPPED) {
                        message.completion.completeExceptionally(IllegalStateException("WSS actor is already started"))
                    } else {
                        try {
                            VaultEnvelopeCodec.validateSecret(message.secret)
                            secret = message.secret
                            connector = connectorFactory.create(message.secret)
                            startCompletion = message.completion
                            connectCurrent()
                        } catch (error: Throwable) {
                            enterFatal("WSS initialization failed", error)
                            message.completion.completeExceptionally(error)
                        }
                    }
                }
                is ActorMessage.Opened -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase == WssConnectionPhase.CONNECTING
                ) {
                    val hello = try {
                        val activeSecret = requireNotNull(secret)
                        helloRequestId = ClientWireCodec.newRequestId()
                        preparedResumeCursors = resumeCursorSource.loadDurableCursors()
                        ClientWireCodec.encodeHello(
                            ClientHelloPayload(
                                requestId = requireNotNull(helloRequestId),
                                clientVersion = clientVersion,
                                secret = activeSecret,
                                resume = preparedResumeCursors,
                            ),
                        )
                    } catch (error: Throwable) {
                        enterFatal("Hello preparation failed", error)
                        continue
                    }
                    publishStatus(WssConnectionStatus(
                        WssConnectionPhase.HELLO_PENDING,
                        reconnectAttempt = reconnectAttempt,
                    ))
                    if (!writerSend(hello)) {
                        scheduleReconnect("Hello send failed")
                    } else {
                        val timerGeneration = generation
                        helloTimer?.cancel()
                        helloTimer = scope.launch {
                            delay(HELLO_TIMEOUT_MILLIS)
                            try {
                                mailbox.send(ActorMessage.HelloTimeout(timerGeneration))
                            } catch (_: ClosedSendChannelException) {
                                // The actor was stopped while the timer was pending.
                            }
                        }
                    }
                }
                is ActorMessage.Text -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase in ACTIVE_TRANSPORT_PHASES
                ) {
                    try {
                        StrictJsonDocument.parseObject(message.bytes, P1aProtocol.MAX_FRAME_BYTES)
                    } catch (_: Throwable) {
                        scheduleProtocolRecovery("Inbound JSON was not strict")
                        continue
                    }
                    val actions = if (!online) {
                        try {
                            listOf(FrameReady(ReliabilityContractDecoder.decode(message.bytes)))
                        } catch (_: Exception) {
                            scheduleProtocolRecovery("Pre-hello frame decode failed")
                            continue
                        }
                    } else {
                        try {
                            receiver?.let { activeReceiver ->
                                receiverReceive(activeReceiver, message.bytes)
                            }
                                ?: listOf(ReceiverFailure(
                                    app.momoding.wire.ReceiverFailureCode.RECEIVER_CLOSED,
                                    "Receiver unavailable",
                                    reconnectRequired = true,
                                ))
                        } catch (_: Exception) {
                            scheduleReconnect("Reliability receiver threw")
                            continue
                        }
                    }
                    handleActions(actions, generation)
                }
                is ActorMessage.Binary -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase in ACTIVE_TRANSPORT_PHASES
                ) {
                    scheduleProtocolRecovery("Binary WebSocket frames are forbidden")
                }
                is ActorMessage.Oversized -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase in ACTIVE_TRANSPORT_PHASES
                ) {
                    scheduleProtocolRecovery("WebSocket frame exceeded the physical limit")
                }
                is ActorMessage.MailboxOverflow -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase in ACTIVE_TRANSPORT_PHASES
                ) {
                    scheduleProtocolRecovery("WebSocket mailbox overflowed")
                }
                is ActorMessage.TransportFailure -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase !in setOf(
                        WssConnectionPhase.BACKING_OFF,
                        WssConnectionPhase.OFFLINE,
                        WssConnectionPhase.FATAL,
                        WssConnectionPhase.STOPPED,
                    )
                ) {
                    if (message.fatalTls) {
                        enterFatal(
                            "TLS validation failed",
                            terminalCause = WssTerminalCause.TLS_VALIDATION,
                        )
                    }
                    else scheduleReconnect("WebSocket transport failed")
                }
                is ActorMessage.Closed -> if (message.generation == generation &&
                    mutableStatus.value.phase in setOf(
                        WssConnectionPhase.CONNECTING,
                        WssConnectionPhase.HELLO_PENDING,
                        WssConnectionPhase.CAPABILITY_SYNCING,
                        WssConnectionPhase.REPLAYING,
                        WssConnectionPhase.ONLINE,
                    )
                ) {
                    scheduleReconnect("WebSocket closed")
                }
                is ActorMessage.HelloTimeout -> if (
                    message.generation == generation &&
                    mutableStatus.value.phase == WssConnectionPhase.HELLO_PENDING
                ) {
                    scheduleReconnect("Hello timeout")
                }
                is ActorMessage.Submit -> {
                    val request = message.request
                    try {
                        require(secret != null) { "WSS actor is not started" }
                        require(mutableStatus.value.phase !in setOf(
                            WssConnectionPhase.FATAL,
                            WssConnectionPhase.OFFLINE,
                            WssConnectionPhase.STOPPED,
                        )) { "WSS actor cannot accept requests" }
                        require(request.timeoutMillis in 1..MAX_REQUEST_TIMEOUT_MILLIS) {
                            "Request timeout is invalid"
                        }
                        val payloadBytes = request.canonicalPayload.toByteArray(Charsets.UTF_8)
                        StrictJsonDocument.parseObject(payloadBytes, P1aProtocol.MAX_FRAME_BYTES).also { objectValue ->
                            require(objectValue["requestId"]?.toString()?.trim('"') == request.requestId) {
                                "Wire requestId differs from payload"
                            }
                            require(objectValue["kind"]?.toString()?.trim('"') == request.kind) {
                                "Wire kind differs from payload"
                            }
                        }
                        if (request.mutating) {
                            requireNotNull(commandJournal) { "Mutating command journal is unavailable" }
                                .persistAccepted(
                                    request.requestId,
                                    request.commandId,
                                    request.kind,
                                    request.taskId,
                                    request.canonicalPayload,
                                )
                        }
                        val pending = PendingWireRequest(
                            requestId = request.requestId,
                            kind = request.kind,
                            canonicalPayload = request.canonicalPayload,
                            mutating = request.mutating,
                            commandId = request.commandId,
                            taskId = request.taskId,
                            deadlineAtMillis = nowMillis() + request.timeoutMillis,
                            completion = message.completion,
                            generation = generation,
                        )
                        requestTable.register(pending)
                        scheduleRequestExpiry(request.requestId, pending.deadlineAtMillis)
                        if (online && attentionReady && !writerSend(request.canonicalPayload)) {
                            scheduleReconnect("Request send failed")
                        }
                    } catch (error: Throwable) {
                        message.completion.completeExceptionally(error)
                    }
                }
                is ActorMessage.Expire -> {
                    val lookup = requestTable.allPending().singleOrNull {
                        it.requestId == message.requestId
                    }
                    if (message.generationScoped &&
                        (message.generation != generation ||
                            lookup?.generation != generation ||
                            lookup.deadlineAtMillis != message.deadlineAtMillis)
                    ) {
                        continue
                    }
                    if (lookup != null && lookup.kind in ATTENTION_COMMAND_KINDS &&
                        lookup.generation == generation
                    ) {
                        scheduleReconnect("Attention command timed out")
                    } else {
                        requestTable.expireForCaller(message.requestId)?.let { request ->
                            request.completion.completeExceptionally(
                                TimeoutException("Wire request timed out"),
                            )
                        }
                    }
                }
                is ActorMessage.AttentionWake -> if (
                    message.generation == generation &&
                    attentionWakeIdentity?.matches(
                        message.generation,
                        message.deadlineAtMillis,
                    ) == true &&
                    online &&
                    attentionCoordinator != null
                ) {
                    attentionTimer = null
                    attentionWakeIdentity = null
                    val plan = try {
                        attentionCoordinator.onWake()
                    } catch (_: Throwable) {
                        scheduleProtocolRecovery("Attention timer application failed")
                        continue
                    }
                    applyAttentionPlan(plan, "Attention timer plan application failed")
                }
                is ActorMessage.AttentionDecision -> {
                    try {
                        require(online && attentionReady && attentionCoordinator != null) {
                            "Attention capability is not ready"
                        }
                        val plan = when (message.decision) {
                            is AttentionUserDecision.AllowContentRead,
                            is AttentionUserDecision.DenyContentRead,
                            -> attentionCoordinator.submitContentReadDecision(message.decision)
                            is AttentionUserDecision.ApproveFileChanges,
                            is AttentionUserDecision.RejectFileChanges,
                            -> attentionCoordinator.submitFileChangeDecision(message.decision)
                            else -> attentionCoordinator.submitDecision(message.decision)
                        }
                        if (applyAttentionPlan(
                                plan,
                                "Attention decision plan application failed",
                            )) {
                            message.completion.complete(Unit)
                        } else {
                            message.completion.completeExceptionally(
                                IOException("Attention decision triggered recovery"),
                            )
                        }
                    } catch (error: Throwable) {
                        message.completion.completeExceptionally(error)
                    }
                }
                is ActorMessage.Reconnect -> if (message.token == retryToken &&
                    mutableStatus.value.phase == WssConnectionPhase.BACKING_OFF
                ) {
                    connectCurrent()
                }
                ActorMessage.Retry -> if (secret != null &&
                    mutableStatus.value.phase in setOf(WssConnectionPhase.BACKING_OFF, WssConnectionPhase.OFFLINE)
                ) {
                    reconnectTimer?.cancel()
                    reconnectAttempt = 0
                    retryToken += 1
                    connectCurrent()
                }
                ActorMessage.Stop -> {
                    reconnectTimer?.cancel()
                    helloTimer?.cancel()
                    attentionTimer?.cancel()
                    closeTransport()
                    requestTable.failAll(IOException("WSS actor stopped"))
                    startCompletion?.completeExceptionally(IOException("WSS actor stopped"))
                    lastReceiverFailure = null
                    publishStatus(WssConnectionStatus(WssConnectionPhase.STOPPED))
                    running = false
                }
            }
        }
    }

    private fun Throwable.hasTlsCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SSLException || current is java.security.cert.CertificateException) return true
            current = current.cause
        }
        return false
    }

    private fun String.sha256Utf8(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private sealed interface ActorMessage {
        data class Start(val secret: VaultSecret, val completion: CompletableDeferred<Unit>) : ActorMessage
        data class Opened(val generation: Long) : ActorMessage
        data class Text(val generation: Long, val bytes: ByteArray) : ActorMessage
        data class Binary(val generation: Long, val byteCount: Long) : ActorMessage
        data class Oversized(val generation: Long) : ActorMessage
        data class MailboxOverflow(val generation: Long) : ActorMessage
        data class TransportFailure(val generation: Long, val fatalTls: Boolean) : ActorMessage
        data class Closed(val generation: Long, val code: Int) : ActorMessage
        data class HelloTimeout(val generation: Long) : ActorMessage
        data class AttentionWake(val generation: Long, val deadlineAtMillis: Long) : ActorMessage
        data class AttentionDecision(
            val decision: AttentionUserDecision,
            val completion: CompletableDeferred<Unit>,
        ) : ActorMessage
        data class Submit(
            val request: OutboundWireRequest,
            val completion: CompletableDeferred<ReceivedP1bServerFrame>,
        ) : ActorMessage
        data class Expire(
            val requestId: String,
            val generation: Long,
            val deadlineAtMillis: Long,
            val generationScoped: Boolean,
        ) : ActorMessage
        data class Reconnect(val token: Long) : ActorMessage
        data object Retry : ActorMessage
        data object Stop : ActorMessage
    }

    companion object {
        const val MAILBOX_CAPACITY = 64
        const val HELLO_TIMEOUT_MILLIS = 5_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val PROTOCOL_CLOSE_CODE = 1002
        private const val SNAPSHOT_REQUEST_KIND = "task.snapshot.request"
        private const val RECONCILE_RESULT_KIND = "device.tool.reconcile.result"
        private val ATTENTION_COMMAND_KINDS = setOf(
            "device.capabilities.report",
            "device.tool.reconcile.result",
        )
        private val ACTIVE_TRANSPORT_PHASES = setOf(
            WssConnectionPhase.CONNECTING,
            WssConnectionPhase.HELLO_PENDING,
            WssConnectionPhase.CAPABILITY_SYNCING,
            WssConnectionPhase.REPLAYING,
            WssConnectionPhase.ONLINE,
        )
    }
}
