package app.momoding.core.transport

import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.ReceivedReliabilityServerFrame
import app.momoding.core.auth.HostBindingSnapshot
import app.momoding.core.auth.HostBindingStatus
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomCommandDraftJournal
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSecureTransportRuntimeUiStatusTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun preHelloMismatchAndRevocationRemainTypedAfterStartFails() = runTest {
        suspend fun assertTerminal(cause: WssTerminalCause, expected: SecureTransportUiPhase, suffix: String) {
            val actor = ScriptedActor()
            val database = MomodingDatabase.open(context, "runtime-status-$suffix.db")
            val runtime = AndroidSecureTransportRuntime(context, this, database, connectionActorFactory = { actor })
            try {
                runtime.startActorForStatusTest(secret(), snapshot())
                actor.startEntered.await()
                actor.publish(WssConnectionStatus(WssConnectionPhase.FATAL, terminalCause = cause))
                actor.failStart.complete(IOException("synthetic terminal"))
                advanceUntilIdle()

                assertEquals(expected, runtime.uiStatus.value.phase)
                assertEquals(0, actor.stopCount.get())
            } finally {
                runtime.closeConnection()
                database.close()
                context.deleteDatabase("runtime-status-$suffix.db")
            }
        }

        assertTerminal(WssTerminalCause.PROTOCOL_MISMATCH, SecureTransportUiPhase.VERSION_MISMATCH, "mismatch")
        assertTerminal(WssTerminalCause.CREDENTIAL_REVOKED, SecureTransportUiPhase.HOST_REVOKED, "revoked")
    }

    @Test
    fun offlineActorRemainsOwnedAndExplicitRetryReconnects() = runTest {
        val actor = ScriptedActor()
        val databaseName = "runtime-status-offline.db"
        val database = MomodingDatabase.open(context, databaseName)
        val runtime = AndroidSecureTransportRuntime(context, this, database, connectionActorFactory = { actor })
        try {
            runtime.startActorForStatusTest(secret(), snapshot())
            actor.startEntered.await()
            actor.publish(WssConnectionStatus(WssConnectionPhase.BACKING_OFF, reconnectAttempt = 7))
            runCurrent()
            assertEquals(SecureTransportUiPhase.RECONNECTING, runtime.uiStatus.value.phase)
            actor.publish(WssConnectionStatus(WssConnectionPhase.OFFLINE, reconnectAttempt = 8))
            actor.failStart.complete(IOException("synthetic offline"))
            advanceUntilIdle()

            assertEquals(SecureTransportUiPhase.OFFLINE, runtime.uiStatus.value.phase)
            assertEquals(0, actor.stopCount.get())

            runtime.retryConnection()
            runCurrent()
            assertEquals(1, actor.retryCount.get())
            assertEquals(SecureTransportUiPhase.CONNECTING, runtime.uiStatus.value.phase)
        } finally {
            runtime.closeConnection()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun replayPhaseAndTaskProgressRemainTypedUntilActorPublishesOnline() = runTest {
        val actor = ScriptedActor()
        val databaseName = "runtime-status-replay.db"
        val database = MomodingDatabase.open(context, databaseName)
        val runtime = AndroidSecureTransportRuntime(context, this, database, connectionActorFactory = { actor })
        try {
            runtime.startActorForStatusTest(secret(), snapshot())
            actor.startEntered.await()
            actor.publish(WssConnectionStatus(WssConnectionPhase.CAPABILITY_SYNCING))
            runCurrent()
            assertEquals(SecureTransportUiPhase.SYNCHRONIZING, runtime.uiStatus.value.phase)
            actor.publish(
                WssConnectionStatus(
                    WssConnectionPhase.REPLAYING,
                    replayProgress = mapOf("task" to TaskReplayProgress("task", 4, 9)),
                ),
            )
            runCurrent()

            assertEquals(SecureTransportUiPhase.SYNCHRONIZING, runtime.uiStatus.value.phase)
            assertEquals(4L, runtime.taskReplayProgress.value.getValue("task").replayedEventCount)

            actor.publish(WssConnectionStatus(WssConnectionPhase.ONLINE))
            runCurrent()
            assertEquals(SecureTransportUiPhase.READY, runtime.uiStatus.value.phase)
            assertEquals(emptyMap<String, TaskReplayProgress>(), runtime.taskReplayProgress.value)
        } finally {
            runtime.closeConnection()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun oneTaskCanLeaveReplayWhileAnotherKeepsTheTransportSynchronizing() = runTest {
        val actor = ScriptedActor()
        val databaseName = "runtime-status-multi-replay.db"
        val database = MomodingDatabase.open(context, databaseName)
        val runtime = AndroidSecureTransportRuntime(context, this, database, connectionActorFactory = { actor })
        try {
            runtime.startActorForStatusTest(secret(), snapshot())
            actor.startEntered.await()
            val taskA = TaskReplayProgress("task-a", 3, 8)
            val taskB = TaskReplayProgress("task-b", 1, 4)
            actor.publish(
                WssConnectionStatus(
                    WssConnectionPhase.REPLAYING,
                    replayProgress = mapOf(taskA.taskId to taskA, taskB.taskId to taskB),
                ),
            )
            runCurrent()
            actor.publish(
                WssConnectionStatus(
                    WssConnectionPhase.REPLAYING,
                    replayProgress = mapOf(taskB.taskId to taskB),
                ),
            )
            runCurrent()

            assertEquals(SecureTransportUiPhase.SYNCHRONIZING, runtime.uiStatus.value.phase)
            assertEquals(setOf("task-b"), runtime.taskReplayProgress.value.keys)
        } finally {
            runtime.closeConnection()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun defaultProductionCompositionSharesOneRoomJournalWithOneAttentionCoordinator() = runTest {
        val databaseName = "runtime-attention-composition.db"
        val database = MomodingDatabase.open(context, databaseName)
        val runtime = AndroidSecureTransportRuntime(context, this, database)
        var actor: WssActor? = null
        try {
            val createActor = AndroidSecureTransportRuntime::class.java
                .getDeclaredMethod("createActor")
                .also { it.isAccessible = true }
            actor = createActor.invoke(runtime) as WssActor
            val exactActor = requireNotNull(actor)
            val actorJournal = WssActor::class.java.getDeclaredField("commandJournal")
                .also { it.isAccessible = true }
                .get(exactActor)
            val coordinator = WssActor::class.java.getDeclaredField("attentionCoordinator")
                .also { it.isAccessible = true }
                .get(exactActor) as AttentionApplicationCoordinator
            val coordinatorJournal = AttentionApplicationCoordinator::class.java
                .getDeclaredField("journal")
                .also { it.isAccessible = true }
                .get(coordinator)

            assertTrue(actorJournal is RoomCommandDraftJournal)
            assertSame(actorJournal, coordinatorJournal)
        } finally {
            actor?.stopAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun attentionDecisionAndRuntimeCloseAlwaysCompleteAcrossTheStopBoundary() = runTest {
        val actor = ScriptedActor()
        val databaseName = "runtime-attention-unpair.db"
        val database = MomodingDatabase.open(context, databaseName)
        val runtime = AndroidSecureTransportRuntime(
            context,
            this,
            database,
            connectionActorFactory = { actor },
        )
        try {
            runtime.startActorForStatusTest(secret(), snapshot())
            actor.startEntered.await()
            val decision = async {
                runCatching {
                    runtime.submitAttentionDecision(AttentionUserDecision.Skip(
                        "00000000-0000-4000-8000-000000000099",
                    ))
                }
            }
            actor.decisionEntered.await()

            runtime.closeConnection()
            val outcome = decision.await()

            assertTrue(outcome.isFailure)
            assertEquals(1, actor.stopCount.get())
        } finally {
            runtime.closeConnection()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class ScriptedActor : SecureTransportActor {
        private val mutableStatus = MutableStateFlow(WssConnectionStatus(WssConnectionPhase.STOPPED))
        override val status: StateFlow<WssConnectionStatus> = mutableStatus
        val startEntered = CompletableDeferred<Unit>()
        val failStart = CompletableDeferred<Throwable>()
        val retryCount = AtomicInteger()
        val stopCount = AtomicInteger()
        val decisionEntered = CompletableDeferred<Unit>()
        val decisionResult = CompletableDeferred<Unit>()

        override suspend fun start(secret: VaultSecret) {
            startEntered.complete(Unit)
            throw failStart.await()
        }

        override suspend fun retryNow() {
            retryCount.incrementAndGet()
            mutableStatus.value = WssConnectionStatus(WssConnectionPhase.CONNECTING)
        }

        override suspend fun requestPage(cursor: String?, limit: Int): ReceivedReliabilityServerFrame =
            error("task.list is outside this lifecycle test")

        override suspend fun submitAttentionDecision(decision: AttentionUserDecision) {
            decisionEntered.complete(Unit)
            decisionResult.await()
        }

        override suspend fun stopAndJoin() {
            stopCount.incrementAndGet()
            mutableStatus.value = WssConnectionStatus(WssConnectionPhase.STOPPED)
            decisionResult.completeExceptionally(IOException("stopped"))
            failStart.complete(IOException("stopped"))
        }

        fun publish(status: WssConnectionStatus) {
            mutableStatus.value = status
        }
    }

    private companion object {
        fun snapshot() = HostBindingSnapshot(
            status = HostBindingStatus.ACTIVE,
            endpoint = "https://example.test:8443",
            spkiPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            clientInstanceId = "00000000-0000-4000-8000-000000000001",
            deviceId = "00000000-0000-4000-9000-000000000001",
            deviceName = "Android phone",
            hostId = "00000000-0000-4000-a000-000000000001",
            credentialId = "00000000-0000-4000-b000-000000000001",
            updatedAtMillis = 1,
        )

        fun secret() = VaultSecret(
            header = VaultHeader(
                endpoint = "https://example.test:8443",
                spkiPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                hostId = "00000000-0000-4000-a000-000000000001",
                credentialId = "00000000-0000-4000-b000-000000000001",
            ),
            deviceCredential = "synthetic-credential",
            clientInstanceId = "00000000-0000-4000-8000-000000000001",
            deviceId = "00000000-0000-4000-9000-000000000001",
            deviceName = "Android phone",
        )
    }
}
