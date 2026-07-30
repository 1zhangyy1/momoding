package app.momoding.core.capabilities

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

const val DEVICE_CAPABILITY_REQUEST_TOOL = "device_capability_request"

enum class CalendarCapabilityAccess(val wireValue: String) {
    READ("read"),
    WRITE("write"),
    ;

    companion object {
        fun fromWireValue(value: String): CalendarCapabilityAccess? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class ContactsCapabilityAccess(val wireValue: String) {
    READ("read"),
    WRITE("write"),
    ;

    companion object {
        fun fromWireValue(value: String): ContactsCapabilityAccess? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class LocationCapabilityAccess(val wireValue: String) {
    APPROXIMATE("approximate"),
    PRECISE("precise"),
    ;

    companion object {
        fun fromWireValue(value: String): LocationCapabilityAccess? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface AndroidCapabilityRequirement {
    data object Default : AndroidCapabilityRequirement
    data class Calendar(
        val access: CalendarCapabilityAccess,
    ) : AndroidCapabilityRequirement
    data class Contacts(
        val access: ContactsCapabilityAccess,
    ) : AndroidCapabilityRequirement
    data class Location(
        val access: LocationCapabilityAccess,
    ) : AndroidCapabilityRequirement
}

enum class AndroidCapabilityRequestResult {
    READY,
    DENIED,
    UNAVAILABLE,
    TIMEOUT,
}

data class AndroidCapabilityRequestOutcome(
    val result: AndroidCapabilityRequestResult,
    val grantId: String? = null,
)

data class AndroidCapabilityRequest(
    val requestId: String,
    val taskId: String,
    val capability: AndroidCapabilityId,
    val requirement: AndroidCapabilityRequirement,
    val purpose: String,
)

fun interface AndroidCapabilityRequester {
    suspend fun request(
        taskId: String,
        capability: AndroidCapabilityId,
        requirement: AndroidCapabilityRequirement,
        purpose: String,
    ): AndroidCapabilityRequestOutcome
}

/**
 * Suspends one Pi capability request while the foreground UI runs Android's
 * native permission, special-access, settings, or SAF flow.
 */
class AndroidCapabilityRequestCoordinator(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AndroidCapabilityRequester {
    private val requestMutex = Mutex()
    private val mutablePending = MutableStateFlow<AndroidCapabilityRequest?>(null)
    @Volatile
    private var pendingResult: CompletableDeferred<AndroidCapabilityRequestOutcome>? = null

    val pending: StateFlow<AndroidCapabilityRequest?> = mutablePending.asStateFlow()

    override suspend fun request(
        taskId: String,
        capability: AndroidCapabilityId,
        requirement: AndroidCapabilityRequirement,
        purpose: String,
    ): AndroidCapabilityRequestOutcome {
        require(taskId.isNotBlank()) { "Capability request task is blank" }
        require(purpose.isNotBlank()) { "Capability request purpose is blank" }
        require(
            (capability == AndroidCapabilityId.CALENDAR &&
                requirement is AndroidCapabilityRequirement.Calendar) ||
                (capability == AndroidCapabilityId.CONTACTS &&
                    requirement is AndroidCapabilityRequirement.Contacts) ||
                (capability == AndroidCapabilityId.LOCATION &&
                    requirement is AndroidCapabilityRequirement.Location) ||
                (capability !in setOf(
                    AndroidCapabilityId.CALENDAR,
                    AndroidCapabilityId.CONTACTS,
                    AndroidCapabilityId.LOCATION,
                ) &&
                    requirement == AndroidCapabilityRequirement.Default),
        ) { "Capability requirement does not match capability" }
        return requestMutex.withLock {
            val request = AndroidCapabilityRequest(
                requestId = requestIdFactory(),
                taskId = taskId,
                capability = capability,
                requirement = requirement,
                purpose = purpose,
            )
            val result = CompletableDeferred<AndroidCapabilityRequestOutcome>()
            pendingResult = result
            mutablePending.value = request
            try {
                withTimeoutOrNull(timeoutMillis) { result.await() }
                    ?: AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.TIMEOUT)
            } finally {
                if (mutablePending.value?.requestId == request.requestId) {
                    mutablePending.value = null
                }
                if (pendingResult === result) pendingResult = null
            }
        }
    }

    fun respond(
        requestId: String,
        result: AndroidCapabilityRequestResult,
        grantId: String? = null,
    ): Boolean {
        if (mutablePending.value?.requestId != requestId) return false
        return pendingResult?.complete(AndroidCapabilityRequestOutcome(result, grantId)) == true
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60_000L
    }
}
