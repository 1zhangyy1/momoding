package app.momoding.core.connector

import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun interface ConnectorToolSnapshotResolver {
    suspend fun resolve(taskId: String): ConnectorToolSnapshot?
}

internal fun interface RemoteMcpClientFactory {
    fun create(connectionId: String): RemoteMcpClient
}

internal fun interface ConnectorCredentialRefresher {
    suspend fun refreshOnce(connectionId: String): Boolean
}

internal interface PhoneLocalConnectorToolHandler {
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult

    suspend fun closeTask(taskId: String) = Unit
}

/**
 * Android-owned execution boundary for task-scoped Connector tools.
 *
 * QuickJS receives only the approved tool catalog. Endpoints, credentials, refresh, HTTP, result
 * limits and cancellation stay on Android behind [RemoteMcpClient].
 */
internal class PhoneLocalConnectorToolExecutor(
    private val snapshots: ConnectorToolSnapshotResolver,
    private val clients: RemoteMcpClientFactory,
    private val credentialRefresher: ConnectorCredentialRefresher =
        ConnectorCredentialRefresher { false },
    private val beforeProjection: suspend () -> Unit = {},
) : PhoneLocalConnectorToolHandler {
    private val clientsByTask = ConcurrentHashMap<String, ClientBinding>()

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        require(request.kind == NATIVE_KIND) { "PI_MOBILE_CONNECTOR_KIND_INVALID" }
        val invocation = requireInvocation(request.arguments)
        val snapshot = ConnectorToolSnapshotPolicy.requireValid(
            requireNotNull(snapshots.resolve(taskId)) {
                "PI_MOBILE_CONNECTOR_TASK_SNAPSHOT_MISSING"
            },
        )
        require(invocation.connectorId == snapshot.connectorId &&
            invocation.connectionId == snapshot.connectionId &&
            invocation.schemaDigest == snapshot.schemaDigest) {
            "PI_MOBILE_CONNECTOR_INVOCATION_BINDING_MISMATCH"
        }
        val tool = requireNotNull(snapshot.tools.singleOrNull { candidate ->
            candidate.exposedName == invocation.exposedToolName &&
                candidate.remoteName == invocation.remoteToolName
        }) { "PI_MOBILE_CONNECTOR_INVOCATION_TOOL_UNKNOWN" }
        require(request.toolName == tool.exposedName || request.toolName == PROXY_TOOL_NAME) {
            "PI_MOBILE_CONNECTOR_INVOCATION_TOOL_MISMATCH"
        }
        require(invocation.arguments.toString().length <= ConnectorToolSnapshotPolicy.MAX_ARGUMENT_CHARS &&
            ConnectorJsonSchemaSubset.matches(invocation.arguments, tool.inputSchema)) {
            "PI_MOBILE_CONNECTOR_ARGUMENTS_INVALID"
        }

        return try {
            val remoteResult = callWithOneRefresh(taskId, snapshot, tool, invocation.arguments)
            beforeProjection()
            project(snapshot, tool, remoteResult)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteMcpException) {
            failureResult(snapshot, tool, failure.failureCode.toConnectorErrorCode())
        }
    }

    override suspend fun closeTask(taskId: String) {
        clientsByTask.remove(taskId)?.client?.close()
    }

    private suspend fun callWithOneRefresh(
        taskId: String,
        snapshot: ConnectorToolSnapshot,
        tool: ConnectorToolDefinition,
        arguments: JsonObject,
    ): RemoteMcpResult {
        val first = clientFor(taskId, snapshot.connectionId)
        try {
            return first.callTool(tool.remoteName, arguments)
        } catch (failure: RemoteMcpException) {
            if (failure.failureCode != RemoteMcpFailureCode.UNAUTHORIZED) throw failure
            invalidate(taskId, first)
            if (!credentialRefresher.refreshOnce(snapshot.connectionId)) throw failure
        }
        return clientFor(taskId, snapshot.connectionId).callTool(tool.remoteName, arguments)
    }

    private fun clientFor(taskId: String, connectionId: String): RemoteMcpClient {
        return synchronized(clientsByTask) {
            val existing = clientsByTask[taskId]
            if (existing != null) {
                require(existing.connectionId == connectionId) {
                    "PI_MOBILE_CONNECTOR_TASK_CONNECTION_CHANGED"
                }
                return@synchronized existing.client
            }
            ClientBinding(connectionId, clients.create(connectionId)).also { created ->
                clientsByTask[taskId] = created
            }.client
        }
    }

    private suspend fun invalidate(taskId: String, expected: RemoteMcpClient) {
        val current = clientsByTask[taskId]
        if (current?.client !== expected) return
        if (clientsByTask.remove(taskId, current)) current.client.close()
    }

    private fun project(
        snapshot: ConnectorToolSnapshot,
        tool: ConnectorToolDefinition,
        result: RemoteMcpResult,
    ): PiNativeAndroidToolResult {
        if (result.content.size > ConnectorToolSnapshotPolicy.MAX_RESULT_BLOCKS) {
            return failureResult(snapshot, tool, "CONNECTOR_RESULT_TOO_LARGE")
        }
        val data = buildJsonObject {
            put("content", JsonArray(result.content))
            result.structuredContent?.let { put("structuredContent", it) }
        }
        val payload = resultPayload(
            snapshot = snapshot,
            tool = tool,
            ok = !result.isError,
            data = data,
            errorCode = if (result.isError) "CONNECTOR_REMOTE_TOOL_ERROR" else null,
        )
        if (payload.toString().length > ConnectorToolSnapshotPolicy.MAX_RESULT_CHARS) {
            return failureResult(snapshot, tool, "CONNECTOR_RESULT_TOO_LARGE")
        }
        return PiNativeAndroidToolResult(
            contentPayload = payload,
            details = payload,
            isError = result.isError,
        )
    }

    private fun failureResult(
        snapshot: ConnectorToolSnapshot,
        tool: ConnectorToolDefinition,
        errorCode: String,
    ): PiNativeAndroidToolResult {
        val payload = resultPayload(
            snapshot = snapshot,
            tool = tool,
            ok = false,
            data = null,
            errorCode = errorCode,
        )
        return PiNativeAndroidToolResult(payload, details = payload, isError = true)
    }

    private fun resultPayload(
        snapshot: ConnectorToolSnapshot,
        tool: ConnectorToolDefinition,
        ok: Boolean,
        data: JsonObject?,
        errorCode: String?,
    ): JsonObject = buildJsonObject {
        put("ok", ok)
        put("kind", NATIVE_KIND)
        put("dataClassification", "untrusted_data")
        put("provenance", buildJsonObject {
            put("connectorId", snapshot.connectorId)
            put("sourceLabel", snapshot.sourceLabel)
            put("tool", tool.exposedName)
        })
        if (data != null) put("data", data)
        if (errorCode != null) put("errorCode", errorCode)
    }

    private fun requireInvocation(arguments: JsonObject): Invocation {
        require(arguments.keys == setOf(
            "operation",
            "connectorId",
            "connectionId",
            "schemaDigest",
            "exposedToolName",
            "remoteToolName",
            "arguments",
        )) { "PI_MOBILE_CONNECTOR_INVOCATION_INVALID" }
        require(arguments["operation"]?.jsonPrimitive?.contentOrNull == "call") {
            "PI_MOBILE_CONNECTOR_INVOCATION_INVALID"
        }
        return Invocation(
            connectorId = arguments.requiredString("connectorId"),
            connectionId = arguments.requiredString("connectionId"),
            schemaDigest = arguments.requiredString("schemaDigest"),
            exposedToolName = arguments.requiredString("exposedToolName"),
            remoteToolName = arguments.requiredString("remoteToolName"),
            arguments = arguments["arguments"]?.jsonObject
                ?: error("PI_MOBILE_CONNECTOR_INVOCATION_INVALID"),
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("PI_MOBILE_CONNECTOR_INVOCATION_INVALID")

    private data class ClientBinding(
        val connectionId: String,
        val client: RemoteMcpClient,
    )

    private data class Invocation(
        val connectorId: String,
        val connectionId: String,
        val schemaDigest: String,
        val exposedToolName: String,
        val remoteToolName: String,
        val arguments: JsonObject,
    )

    companion object {
        const val NATIVE_KIND = "connector_tool"
        const val PROXY_TOOL_NAME = "connector"
    }
}

internal object ConnectorJsonSchemaSubset {
    private val allowedKeywords = setOf(
        "type",
        "properties",
        "required",
        "additionalProperties",
        "items",
        "enum",
        "minLength",
        "maxLength",
        "minItems",
        "maxItems",
        "minimum",
        "maximum",
        "title",
        "description",
        "default",
    )

    fun requireSupported(schema: JsonObject, depth: Int = 0) {
        require(depth <= ConnectorToolSnapshotPolicy.MAX_SCHEMA_DEPTH) {
            "PI_MOBILE_CONNECTOR_SCHEMA_LIMIT"
        }
        require(schema.keys.all { it in allowedKeywords }) {
            "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED"
        }
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        require(type in setOf("object", "array", "string", "integer", "number", "boolean")) {
            "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED"
        }
        if (type == "object") {
            val properties = schema["properties"]
            require(properties == null || properties is JsonObject) {
                "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED"
            }
            val additional = schema["additionalProperties"]
            require(additional == null || additional == JsonPrimitive(true) ||
                additional == JsonPrimitive(false)) {
                "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED"
            }
            val required = schema["required"]
            require(required == null || required is JsonArray &&
                required.all { it is JsonPrimitive && it.isString }) {
                "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED"
            }
            (properties as? JsonObject)?.values?.forEach { child ->
                require(child is JsonObject) { "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED" }
                requireSupported(child, depth + 1)
            }
        }
        if (type == "array") {
            schema["items"]?.let { items ->
                require(items is JsonObject) { "PI_MOBILE_CONNECTOR_SCHEMA_UNSUPPORTED" }
                requireSupported(items, depth + 1)
            }
        }
    }

    fun matches(value: JsonElement, schema: JsonObject, depth: Int = 0): Boolean {
        if (depth > ConnectorToolSnapshotPolicy.MAX_SCHEMA_DEPTH) return false
        val enum = schema["enum"] as? JsonArray
        if (enum != null && enum.none { candidate -> candidate == value }) return false
        return when (schema["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> {
                val candidate = value as? JsonObject ?: return false
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (required.any { it !in candidate }) return false
                if (schema["additionalProperties"] == JsonPrimitive(false) &&
                    candidate.keys.any { it !in properties }) return false
                candidate.all { (key, child) ->
                    val childSchema = properties[key] as? JsonObject
                    childSchema == null || matches(child, childSchema, depth + 1)
                }
            }
            "array" -> {
                val candidate = value as? JsonArray ?: return false
                val min = schema["minItems"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val max = schema["maxItems"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (min != null && candidate.size < min) return false
                if (max != null && candidate.size > max) return false
                val itemSchema = schema["items"] as? JsonObject
                itemSchema == null || candidate.all { matches(it, itemSchema, depth + 1) }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive ?: return false
                if (!primitive.isString) return false
                val text = primitive.content
                val min = schema["minLength"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val max = schema["maxLength"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                (min == null || text.length >= min) &&
                    (max == null || text.length <= max)
            }
            "integer" -> value.jsonNumber()?.let { number ->
                number % 1.0 == 0.0 && inRange(number, schema)
            } ?: false
            "number" -> value.jsonNumber()?.let { inRange(it, schema) } ?: false
            "boolean" -> value is JsonPrimitive && !value.isString &&
                (value.content == "true" || value.content == "false")
            null -> value !is JsonNull
            else -> false
        }
    }

    private fun JsonElement.jsonNumber(): Double? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toDoubleOrNull()

    private fun inRange(number: Double, schema: JsonObject): Boolean {
        val minimum = schema["minimum"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val maximum = schema["maximum"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        return number.isFinite() && (minimum == null || number >= minimum) &&
            (maximum == null || number <= maximum)
    }
}

private fun RemoteMcpFailureCode.toConnectorErrorCode(): String = when (this) {
    RemoteMcpFailureCode.CLOSED -> "CONNECTOR_CLOSED"
    RemoteMcpFailureCode.UNAUTHORIZED -> "CONNECTOR_UNAUTHORIZED"
    RemoteMcpFailureCode.FORBIDDEN -> "CONNECTOR_FORBIDDEN"
    RemoteMcpFailureCode.NOT_FOUND -> "CONNECTOR_NOT_FOUND"
    RemoteMcpFailureCode.RATE_LIMITED -> "CONNECTOR_RATE_LIMITED"
    RemoteMcpFailureCode.SERVER_ERROR -> "CONNECTOR_SERVER_ERROR"
    RemoteMcpFailureCode.NETWORK -> "CONNECTOR_NETWORK_ERROR"
    RemoteMcpFailureCode.TIMEOUT -> "CONNECTOR_TIMEOUT"
    RemoteMcpFailureCode.INVALID_RESPONSE -> "CONNECTOR_INVALID_RESPONSE"
    RemoteMcpFailureCode.LIMIT_EXCEEDED -> "CONNECTOR_RESULT_TOO_LARGE"
    RemoteMcpFailureCode.PROTOCOL_ERROR -> "CONNECTOR_PROTOCOL_ERROR"
}
