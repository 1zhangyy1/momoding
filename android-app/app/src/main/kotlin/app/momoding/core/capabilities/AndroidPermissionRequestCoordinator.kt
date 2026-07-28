package app.momoding.core.capabilities

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AndroidPermissionRequestResult {
    GRANTED,
    DENIED,
    TIMEOUT,
}

data class AndroidPermissionRequest(
    val requestId: String,
    val permissions: List<String>,
)

/**
 * Bridges a background device-tool request to the foreground Android permission UI.
 *
 * Only one request is exposed at a time. The requesting tool suspends until the
 * Activity returns the user's decision or the request expires.
 */
class AndroidPermissionRequestCoordinator(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val requestMutex = Mutex()
    private val mutablePending = MutableStateFlow<AndroidPermissionRequest?>(null)
    @Volatile
    private var pendingResult: CompletableDeferred<AndroidPermissionRequestResult>? = null

    val pending: StateFlow<AndroidPermissionRequest?> = mutablePending.asStateFlow()

    suspend fun request(permissions: List<String>): AndroidPermissionRequestResult {
        val normalized = permissions.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return AndroidPermissionRequestResult.DENIED
        return requestMutex.withLock {
            val request = AndroidPermissionRequest(
                requestId = requestIdFactory(),
                permissions = normalized,
            )
            val result = CompletableDeferred<AndroidPermissionRequestResult>()
            pendingResult = result
            mutablePending.value = request
            try {
                withTimeoutOrNull(timeoutMillis) { result.await() }
                    ?: AndroidPermissionRequestResult.TIMEOUT
            } finally {
                if (mutablePending.value?.requestId == request.requestId) {
                    mutablePending.value = null
                }
                if (pendingResult === result) pendingResult = null
            }
        }
    }

    fun respond(requestId: String, result: AndroidPermissionRequestResult) {
        if (mutablePending.value?.requestId != requestId) return
        pendingResult?.complete(result)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
