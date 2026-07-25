package app.momoding.core.transport

import app.momoding.core.auth.HostBindingSnapshot
import app.momoding.core.auth.HostBindingStatus
import app.momoding.core.auth.HostClientProfile
import app.momoding.core.auth.PairingIdentity
import app.momoding.core.auth.PairingSuccess
import app.momoding.core.auth.ReconciledBinding
import app.momoding.core.auth.VaultHeader
import app.momoding.core.auth.VaultSecret
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingAttemptOwnerTest {
    @Test
    fun cancelWhilePrepareIsInProgressPreventsDispatchAndClearsPreparedIdentity() = runBlocking {
        val prepareEntered = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val registered = CompletableDeferred<Unit>()
        val clearCount = AtomicInteger()
        val call = RecordingCall()
        val owner = owner(
            prepare = { _, _, _ ->
                prepareEntered.complete(Unit)
                releasePrepare.await()
                identity(1)
            },
            clear = { clearCount.incrementAndGet(); true },
            callFactory = { _, _ -> call },
        )

        val result = async {
            owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME, onRegistered = { registered.complete(Unit) })
        }
        withTimeout(5_000) { registered.await() }
        withTimeout(5_000) { prepareEntered.await() }
        assertEquals(PairingAttemptPhase.PREPARING, owner.currentPhase())
        assertEquals(PairingCancelResult.CANCEL_WON_BEFORE_SEND, owner.cancel())
        releasePrepare.complete(Unit)

        assertEquals(PairingAttemptResult.Cancelled(false), withTimeout(5_000) { result.await() })
        assertEquals(1, clearCount.get())
        assertFalse(call.executed.get())
    }

    @Test
    fun cancelBeforeDispatchClearsOnlyPreparedIdentity() = runBlocking {
        val factoryEntered = CompletableDeferred<Unit>()
        val releaseFactory = CountDownLatch(1)
        val clearCount = AtomicInteger()
        val call = RecordingCall()
        val owner = owner(
            clear = { clearCount.incrementAndGet(); true },
            callFactory = { _, _ ->
                factoryEntered.complete(Unit)
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                call
            },
        )

        val result = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { factoryEntered.await() }
        assertEquals(PairingCancelResult.CANCEL_WON_BEFORE_SEND, owner.cancel())
        assertEquals(PairingCancelResult.CANCEL_WON_BEFORE_SEND, owner.cancel())
        releaseFactory.countDown()

        assertEquals(PairingAttemptResult.Cancelled(false), withTimeout(5_000) { result.await() })
        assertEquals(1, clearCount.get())
        assertFalse(call.executed.get())
    }

    @Test
    fun sameIdentityRetryCannotStartUntilOldBeforeSendClearCompletes() = runBlocking {
        val firstFactoryEntered = CompletableDeferred<Unit>()
        val releaseFirstFactory = CountDownLatch(1)
        val clearEntered = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        val prepareCount = AtomicInteger()
        val factoryCount = AtomicInteger()
        val owner = owner(
            prepare = { _, _, _ ->
                prepareCount.incrementAndGet()
                identity(1)
            },
            clear = {
                clearEntered.complete(Unit)
                releaseClear.await()
                true
            },
            callFactory = { _, _ ->
                if (factoryCount.getAndIncrement() == 0) {
                    firstFactoryEntered.complete(Unit)
                    check(releaseFirstFactory.await(5, TimeUnit.SECONDS))
                    RecordingCall()
                } else {
                    RecordingCall(executeBlock = { success() })
                }
            },
            beforeCommit = {},
        )

        val first = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { firstFactoryEntered.await() }
        assertEquals(PairingCancelResult.CANCEL_WON_BEFORE_SEND, owner.cancel())
        releaseFirstFactory.countDown()
        withTimeout(5_000) { clearEntered.await() }

        val retry = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        kotlinx.coroutines.yield()
        assertEquals(1, prepareCount.get())
        assertFalse(retry.isCompleted)

        releaseClear.complete(Unit)
        assertEquals(PairingAttemptResult.Cancelled(false), withTimeout(5_000) { first.await() })
        assertTrue(withTimeout(5_000) { retry.await() } is PairingAttemptResult.Active)
        assertEquals(2, prepareCount.get())
    }

    @Test
    fun cancelInFlightCancelsExactCallAndKeepsRecoveryIdentity() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val call = RecordingCall(
            executeBlock = {
                started.complete(Unit)
                runBlocking { release.await() }
                throw IOException("cancelled")
            },
            cancelBlock = { release.complete(Unit) },
        )
        val clearCount = AtomicInteger()
        val owner = owner(
            clear = { clearCount.incrementAndGet(); true },
            callFactory = { _, _ -> call },
        )

        val result = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { started.await() }
        assertEquals(PairingCancelResult.CANCEL_WON_AFTER_SEND, owner.cancel())
        assertEquals(PairingCancelResult.CANCEL_WON_AFTER_SEND, owner.cancel())

        assertEquals(PairingAttemptResult.Cancelled(true), withTimeout(5_000) { result.await() })
        assertEquals(0, clearCount.get())
        assertTrue(call.cancelled.get())
    }

    @Test
    fun cancelAfterResponseBeforeCommitRejectsSecret() = runBlocking {
        val atCommitBoundary = CompletableDeferred<Unit>()
        val continueCommit = CompletableDeferred<Unit>()
        val commitCount = AtomicInteger()
        val owner = owner(
            commit = { identity, success ->
                commitCount.incrementAndGet()
                active(identity, success)
            },
            callFactory = { _, _ -> RecordingCall(executeBlock = { success() }) },
            beforeCommit = {
                atCommitBoundary.complete(Unit)
                continueCommit.await()
            },
        )

        val result = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { atCommitBoundary.await() }
        assertEquals(PairingCancelResult.CANCEL_WON_AFTER_SEND, owner.cancel())
        continueCommit.complete(Unit)

        assertEquals(PairingAttemptResult.Cancelled(true), withTimeout(5_000) { result.await() })
        assertEquals(0, commitCount.get())
    }

    @Test
    fun commitTransitionWinsConcurrentCancel() = runBlocking {
        val committing = CompletableDeferred<Unit>()
        val continueCommit = CompletableDeferred<Unit>()
        val owner = owner(
            commit = { identity, success ->
                committing.complete(Unit)
                continueCommit.await()
                active(identity, success)
            },
            callFactory = { _, _ -> RecordingCall(executeBlock = { success() }) },
            beforeCommit = {},
        )

        val result = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { committing.await() }
        assertEquals(PairingCancelResult.COMMIT_WON, owner.cancel())
        continueCommit.complete(Unit)

        assertTrue(withTimeout(5_000) { result.await() } is PairingAttemptResult.Active)
        assertEquals(PairingCancelResult.COMMIT_WON, owner.cancel())
    }

    @Test
    fun oldGenerationResponseCannotCommitOrClearNewGeneration() = runBlocking {
        val calls = AtomicInteger()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val commits = mutableListOf<String>()
        val owner = owner(
            prepare = { _, _, _ -> identity(calls.get() + 1) },
            commit = { identity, success ->
                commits += identity.deviceId
                active(identity, success)
            },
            callFactory = { _, _ ->
                if (calls.getAndIncrement() == 0) {
                    RecordingCall(executeBlock = {
                        oldStarted.complete(Unit)
                        runBlocking { releaseOld.await() }
                        success()
                    })
                } else {
                    RecordingCall(executeBlock = { success() })
                }
            },
            beforeCommit = {},
        )

        val old = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        withTimeout(5_000) { oldStarted.await() }
        assertEquals(PairingCancelResult.CANCEL_WON_AFTER_SEND, owner.cancel())
        val newer = async { owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME) }
        assertTrue(withTimeout(5_000) { newer.await() } is PairingAttemptResult.Active)
        releaseOld.complete(Unit)
        assertEquals(PairingAttemptResult.Cancelled(true), withTimeout(5_000) { old.await() })
        assertEquals(listOf(identity(2).deviceId), commits)
    }

    @Test
    fun callConstructionFailureClearsPreparedIdentity() = runBlocking {
        val clearCount = AtomicInteger()
        val owner = owner(
            clear = { clearCount.incrementAndGet(); true },
            callFactory = { _, _ -> throw IllegalArgumentException("invalid request") },
        )

        try {
            owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME)
            fail("pair should fail")
        } catch (error: PairingAttemptFailedException) {
            assertFalse(error.responseMayHaveReachedHost)
            assertTrue(error.cause is IllegalArgumentException)
        }
        assertEquals(1, clearCount.get())
        assertEquals(PairingCancelResult.NO_ATTEMPT, owner.cancel())
    }

    @Test
    fun executionFailureKeepsRecoveryIdentityAndReportsResponseAmbiguity() = runBlocking {
        val clearCount = AtomicInteger()
        val owner = owner(
            clear = { clearCount.incrementAndGet(); true },
            callFactory = { _, _ -> RecordingCall(executeBlock = { throw IOException("response lost") }) },
        )

        try {
            owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME)
            fail("pair should fail")
        } catch (error: PairingAttemptFailedException) {
            assertTrue(error.responseMayHaveReachedHost)
            assertTrue(error.cause is IOException)
        }
        assertEquals(PairingAttemptPhase.FAILED, owner.currentPhase())
        assertEquals(0, clearCount.get())
        assertEquals(PairingCancelResult.NO_ATTEMPT, owner.cancel())
    }

    @Test
    fun commitFailureBecomesTerminalRecoveryInsteadOfWedgingCommitting() = runBlocking {
        val owner = owner(
            commit = { _, _ -> throw IOException("vault commit failed") },
            callFactory = { _, _ -> RecordingCall(executeBlock = { success() }) },
        )

        try {
            owner.pair(ENDPOINT, PIN, CODE, DEVICE_NAME)
            fail("pair should fail")
        } catch (error: PairingAttemptFailedException) {
            assertTrue(error.responseMayHaveReachedHost)
        }
        assertEquals(PairingAttemptPhase.FAILED, owner.currentPhase())
        assertEquals(PairingCancelResult.NO_ATTEMPT, owner.cancel())
    }

    private fun owner(
        prepare: suspend (String, String, String) -> PairingIdentity = { _, _, _ -> identity(1) },
        commit: suspend (PairingIdentity, PairingSuccess) -> ReconciledBinding.Active = ::active,
        clear: suspend (PairingIdentity) -> Boolean = { true },
        callFactory: (String, PairingIdentity) -> PairCall,
        beforeCommit: suspend () -> Unit = {},
    ) = PairingAttemptOwner(prepare, commit, clear, callFactory, beforeCommit)

    private class RecordingCall(
        private val executeBlock: () -> PairingSuccess = { success() },
        private val cancelBlock: () -> Unit = {},
    ) : PairCall {
        val executed = AtomicBoolean()
        val cancelled = AtomicBoolean()

        override fun execute(): PairingSuccess {
            executed.set(true)
            return executeBlock()
        }

        override fun cancel() {
            cancelled.set(true)
            cancelBlock()
        }
    }

    private companion object {
        const val ENDPOINT = "https://example.test:8443"
        const val PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val CODE = "ABCDEFGHJKLMNPQRSTUV"
        const val DEVICE_NAME = "Android phone"

        fun identity(index: Int) = PairingIdentity(
            endpoint = ENDPOINT,
            spkiPin = PIN,
            clientInstanceId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
            deviceId = "00000000-0000-4000-9000-${index.toString().padStart(12, '0')}",
            deviceName = DEVICE_NAME,
        )

        fun success() = PairingSuccess(
            hostId = "00000000-0000-4000-a000-000000000001",
            credentialId = "00000000-0000-4000-b000-000000000001",
            deviceCredential = "credential-value",
            profile = HostClientProfile("Mac Studio", "OpenRouter", "deepseek/deepseek-v4-pro", "default", false, 1),
        )

        fun active(identity: PairingIdentity, success: PairingSuccess): ReconciledBinding.Active {
            val snapshot = HostBindingSnapshot(
                status = HostBindingStatus.ACTIVE,
                endpoint = identity.endpoint,
                spkiPin = identity.spkiPin,
                clientInstanceId = identity.clientInstanceId,
                deviceId = identity.deviceId,
                deviceName = identity.deviceName,
                hostId = success.hostId,
                credentialId = success.credentialId,
                profile = success.profile,
                updatedAtMillis = 1,
            )
            val secret = VaultSecret(
                VaultHeader(identity.endpoint, identity.spkiPin, success.hostId, success.credentialId),
                success.deviceCredential,
                identity.clientInstanceId,
                identity.deviceId,
                identity.deviceName,
            )
            return ReconciledBinding.Active(snapshot, secret)
        }
    }
}
