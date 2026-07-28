package app.momoding.core.transport

import app.momoding.wire.ReceivedP1bServerFrame
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred

data class PendingWireRequest(
    val requestId: String,
    val kind: String,
    val canonicalPayload: String,
    val mutating: Boolean,
    val commandId: String?,
    val taskId: String?,
    var deadlineAtMillis: Long,
    val completion: CompletableDeferred<ReceivedP1bServerFrame>,
    var generation: Long,
)

sealed interface ResponseLookup {
    data class Pending(val request: PendingWireRequest) : ResponseLookup
    data object Tombstoned : ResponseLookup
    data object Unknown : ResponseLookup
    data object StaleGeneration : ResponseLookup
}

class RequestTable(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val pending = linkedMapOf<String, PendingWireRequest>()
    private val tombstones = LinkedHashMap<String, RequestTombstone>()

    fun register(request: PendingWireRequest) {
        pruneTombstones()
        require(pending.size < MAX_PENDING) { "Too many pending Wire requests" }
        require(request.requestId !in pending && request.requestId !in tombstones) {
            "requestId is already in use"
        }
        require(request.deadlineAtMillis > nowMillis()) { "Request deadline has already elapsed" }
        pending[request.requestId] = request
    }

    /**
     * Re-opens an exact, durably validated reconcile command after its own response tombstone
     * was installed. Callers must first prove the immutable Room intent; generic requests must
     * continue to use [register] so a requestId cannot be rebound across kinds.
     */
    fun registerDurableReconcileReplay(request: PendingWireRequest) {
        pruneTombstones()
        requireDurableReconcileReplay(request)
        require(request.requestId !in pending) { "Durable replay is already pending" }
        require(pending.size < MAX_PENDING) { "Too many pending Wire requests" }
        require(request.deadlineAtMillis > nowMillis()) { "Request deadline has already elapsed" }
        tombstones.remove(request.requestId)
        pending[request.requestId] = request
    }

    fun hasCompatibleReconcileProvenance(requestId: String): Boolean {
        pruneTombstones()
        pending[requestId]?.let { request ->
            return request.mutating && request.kind == RECONCILE_RESULT_KIND
        }
        tombstones[requestId]?.let { tombstone ->
            return tombstone.responseCompleted &&
                tombstone.mutating &&
                tombstone.kind == RECONCILE_RESULT_KIND
        }
        return true
    }

    fun requireDurableReconcileReplay(request: PendingWireRequest) {
        pruneTombstones()
        require(request.mutating && request.kind == RECONCILE_RESULT_KIND) {
            "Only an exact durable reconcile result can replace a response tombstone"
        }
        val tombstone = requireNotNull(tombstones[request.requestId]) {
            "Durable reconcile replay has no response tombstone"
        }
        require(tombstone.responseCompleted &&
            tombstone.mutating &&
            tombstone.kind == request.kind &&
            tombstone.commandId == request.commandId &&
            tombstone.taskId == request.taskId &&
            tombstone.canonicalPayloadSha256 == sha256(request.canonicalPayload)
        ) { "Durable reconcile replay differs from tombstone provenance" }
    }

    fun lookup(requestId: String, generation: Long): ResponseLookup {
        pruneTombstones()
        val request = pending[requestId]
        if (request != null) {
            return if (request.generation == generation) {
                ResponseLookup.Pending(request)
            } else {
                ResponseLookup.StaleGeneration
            }
        }
        return if (requestId in tombstones) ResponseLookup.Tombstoned else ResponseLookup.Unknown
    }

    fun complete(requestId: String): PendingWireRequest {
        val request = requireNotNull(pending.remove(requestId)) { "Cannot complete an unknown request" }
        tombstone(request, responseCompleted = true)
        return request
    }

    fun expireForCaller(requestId: String): PendingWireRequest? {
        val request = pending[requestId] ?: return null
        if (request.mutating) return request
        pending.remove(requestId)
        tombstone(request, responseCompleted = false)
        return request
    }

    fun updateGenerationAndPendingPayloads(generation: Long): List<PendingWireRequest> =
        pending.values.onEach { it.generation = generation }.toList()

    fun allPending(): List<PendingWireRequest> = pending.values.toList()

    fun failAll(error: Throwable) {
        pending.values.forEach { request ->
            tombstone(request, responseCompleted = false)
            request.completion.completeExceptionally(error)
        }
        pending.clear()
    }

    private fun tombstone(request: PendingWireRequest, responseCompleted: Boolean) {
        tombstones.remove(request.requestId)
        tombstones[request.requestId] = RequestTombstone(
            expiresAtMillis = nowMillis() + TOMBSTONE_TTL_MILLIS,
            kind = request.kind,
            mutating = request.mutating,
            commandId = request.commandId,
            taskId = request.taskId,
            canonicalPayloadSha256 = sha256(request.canonicalPayload),
            responseCompleted = responseCompleted,
        )
        while (tombstones.size > MAX_TOMBSTONES) {
            tombstones.remove(tombstones.entries.first().key)
        }
    }

    private fun pruneTombstones() {
        val now = nowMillis()
        val iterator = tombstones.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMillis <= now) iterator.remove()
        }
    }

    private data class RequestTombstone(
        val expiresAtMillis: Long,
        val kind: String,
        val mutating: Boolean,
        val commandId: String?,
        val taskId: String?,
        val canonicalPayloadSha256: String,
        val responseCompleted: Boolean,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val RECONCILE_RESULT_KIND = "device.tool.reconcile.result"
        const val MAX_PENDING = 256
        const val MAX_TOMBSTONES = 1_024
        const val TOMBSTONE_TTL_MILLIS = 5 * 60 * 1_000L
    }
}
