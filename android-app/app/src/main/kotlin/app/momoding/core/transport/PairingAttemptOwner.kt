package app.momoding.core.transport

import app.momoding.core.auth.PairingIdentity
import app.momoding.core.auth.ReconciledBinding
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

enum class PairingAttemptPhase {
    PREPARING,
    PREPARED,
    IN_FLIGHT,
    RESPONSE_RECEIVED,
    CANCEL_WON,
    COMMITTING,
    COMMITTED,
    FAILED,
}

enum class PairingCancelResult {
    NO_ATTEMPT,
    CANCEL_WON_BEFORE_SEND,
    CANCEL_WON_AFTER_SEND,
    COMMIT_WON,
}

sealed interface PairingAttemptResult {
    data class Active(val binding: ReconciledBinding.Active) : PairingAttemptResult
    data class Cancelled(val responseMayHaveReachedHost: Boolean) : PairingAttemptResult
}

class PairingAttemptOwner(
    private val prepare: suspend (String, String, String) -> PairingIdentity,
    private val commit: suspend (PairingIdentity, app.momoding.core.auth.PairingSuccess) -> ReconciledBinding.Active,
    private val clearPrepared: suspend (PairingIdentity) -> Boolean,
    private val callFactory: (String, PairingIdentity) -> PairCall = { code, identity ->
        PairingClient().createPairCall(code, identity)
    },
    private val beforeCommit: suspend () -> Unit = { yield() },
) {
    private data class Attempt(
        val generation: Long,
        var phase: PairingAttemptPhase,
        var identity: PairingIdentity? = null,
        var call: PairCall? = null,
        var dispatched: Boolean = false,
    )

    private sealed interface StartResult {
        data class Started(
            val attempt: Attempt,
            val identity: PairingIdentity,
            val call: PairCall,
        ) : StartResult

        data object Cancelled : StartResult
    }

    private val mutex = Mutex()
    private val startMutex = Mutex()
    private var generation = 0L
    private var current: Attempt? = null

    suspend fun pair(
        endpoint: String,
        spkiPin: String,
        pairingCode: String,
        deviceName: String,
        onRegistered: suspend () -> Unit = {},
    ): PairingAttemptResult = withContext(Dispatchers.IO) {
        PairingClient.requireValidPairingCode(pairingCode)
        val startResult = startMutex.withLock {
            val attempt = mutex.withLock {
                require(current == null || current?.phase in TERMINAL_PHASES) { "Pairing is already running" }
                Attempt(++generation, PairingAttemptPhase.PREPARING).also { current = it }
            }
            onRegistered()
            if (mutex.withLock { attempt.phase == PairingAttemptPhase.CANCEL_WON }) {
                return@withLock StartResult.Cancelled
            }

            val identity = try {
                prepare(endpoint, spkiPin, deviceName)
            } catch (error: Throwable) {
                mutex.withLock {
                    if (attempt.isCurrent() && attempt.phase != PairingAttemptPhase.CANCEL_WON) {
                        attempt.phase = PairingAttemptPhase.FAILED
                    }
                }
                if (error is CancellationException) throw error
                throw PairingAttemptFailedException(responseMayHaveReachedHost = false, cause = error)
            }
            val cancelledAfterPrepare = mutex.withLock {
                attempt.identity = identity
                if (attempt.phase == PairingAttemptPhase.CANCEL_WON) {
                    true
                } else {
                    attempt.phase = PairingAttemptPhase.PREPARED
                    false
                }
            }
            if (cancelledAfterPrepare) {
                clearPrepared(identity)
                return@withLock StartResult.Cancelled
            }

            val call = try {
                callFactory(pairingCode, identity)
            } catch (error: Throwable) {
                val cancelled = mutex.withLock {
                    val cancelWon = attempt.isCurrent() && attempt.phase == PairingAttemptPhase.CANCEL_WON
                    if (!cancelWon && attempt.isCurrent()) attempt.phase = PairingAttemptPhase.FAILED
                    cancelWon
                }
                clearPrepared(identity)
                if (cancelled) return@withLock StartResult.Cancelled
                if (error is CancellationException) throw error
                throw PairingAttemptFailedException(responseMayHaveReachedHost = false, cause = error)
            }
            val mayDispatch = mutex.withLock {
                if (!attempt.isCurrent() || attempt.phase == PairingAttemptPhase.CANCEL_WON) {
                    false
                } else {
                    attempt.call = call
                    attempt.dispatched = true
                    attempt.phase = PairingAttemptPhase.IN_FLIGHT
                    true
                }
            }
            if (!mayDispatch) {
                clearPrepared(identity)
                StartResult.Cancelled
            } else {
                StartResult.Started(attempt, identity, call)
            }
        }
        if (startResult == StartResult.Cancelled) {
            return@withContext PairingAttemptResult.Cancelled(responseMayHaveReachedHost = false)
        }
        val (attempt, identity, call) = startResult as StartResult.Started

        val success = try {
            call.execute()
        } catch (error: Throwable) {
            val cancelled = mutex.withLock {
                val won = attempt.isCurrent() && attempt.phase == PairingAttemptPhase.CANCEL_WON
                if (!won && attempt.isCurrent()) attempt.phase = PairingAttemptPhase.FAILED
                won
            }
            if (cancelled) {
                return@withContext PairingAttemptResult.Cancelled(responseMayHaveReachedHost = true)
            }
            if (error is CancellationException) throw error
            throw PairingAttemptFailedException(responseMayHaveReachedHost = true, cause = error)
        }

        val responseAccepted = mutex.withLock {
            if (!attempt.isCurrent() || attempt.phase == PairingAttemptPhase.CANCEL_WON) {
                false
            } else {
                attempt.phase = PairingAttemptPhase.RESPONSE_RECEIVED
                true
            }
        }
        if (!responseAccepted) {
            return@withContext PairingAttemptResult.Cancelled(responseMayHaveReachedHost = true)
        }
        beforeCommit()
        val commitWon = mutex.withLock {
            if (!attempt.isCurrent() || attempt.phase == PairingAttemptPhase.CANCEL_WON) {
                false
            } else {
                attempt.phase = PairingAttemptPhase.COMMITTING
                true
            }
        }
        if (!commitWon) {
            return@withContext PairingAttemptResult.Cancelled(responseMayHaveReachedHost = true)
        }
        val active = try {
            commit(identity, success)
        } catch (error: Throwable) {
            mutex.withLock {
                if (attempt.isCurrent()) attempt.phase = PairingAttemptPhase.FAILED
            }
            if (error is CancellationException) throw error
            throw PairingAttemptFailedException(responseMayHaveReachedHost = true, cause = error)
        }
        mutex.withLock {
            if (attempt.isCurrent()) attempt.phase = PairingAttemptPhase.COMMITTED
        }
        PairingAttemptResult.Active(active)
    }

    suspend fun cancel(): PairingCancelResult {
        var cancelCall: PairCall? = null
        val result = mutex.withLock {
            val attempt = current ?: return@withLock PairingCancelResult.NO_ATTEMPT
            when (attempt.phase) {
                PairingAttemptPhase.PREPARING,
                PairingAttemptPhase.PREPARED -> {
                    attempt.phase = PairingAttemptPhase.CANCEL_WON
                    PairingCancelResult.CANCEL_WON_BEFORE_SEND
                }
                PairingAttemptPhase.IN_FLIGHT,
                PairingAttemptPhase.RESPONSE_RECEIVED,
                -> {
                    attempt.phase = PairingAttemptPhase.CANCEL_WON
                    cancelCall = attempt.call
                    PairingCancelResult.CANCEL_WON_AFTER_SEND
                }
                PairingAttemptPhase.COMMITTING,
                PairingAttemptPhase.COMMITTED,
                -> PairingCancelResult.COMMIT_WON
                PairingAttemptPhase.CANCEL_WON -> if (attempt.dispatched) {
                    PairingCancelResult.CANCEL_WON_AFTER_SEND
                } else {
                    PairingCancelResult.CANCEL_WON_BEFORE_SEND
                }
                PairingAttemptPhase.FAILED -> PairingCancelResult.NO_ATTEMPT
            }
        }
        cancelCall?.cancel()
        return result
    }

    suspend fun currentPhase(): PairingAttemptPhase? = mutex.withLock { current?.phase }

    private fun Attempt.isCurrent(): Boolean = current?.generation == generation && current === this

    private companion object {
        val TERMINAL_PHASES = setOf(
            PairingAttemptPhase.CANCEL_WON,
            PairingAttemptPhase.COMMITTED,
            PairingAttemptPhase.FAILED,
        )
    }
}

class PairingAttemptFailedException(
    val responseMayHaveReachedHost: Boolean,
    cause: Throwable,
) : IOException("Pairing attempt failed", cause)
