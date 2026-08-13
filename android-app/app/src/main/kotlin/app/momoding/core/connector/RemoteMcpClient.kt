package app.momoding.core.connector

import kotlinx.serialization.json.JsonObject

/** Product-facing MCP boundary. SDK transport and handshake types must not escape this package. */
interface RemoteMcpClient {
    suspend fun discover(): RemoteMcpServerInfo

    suspend fun listTools(): List<RemoteMcpTool>

    suspend fun callTool(name: String, arguments: JsonObject): RemoteMcpResult

    suspend fun close()
}

data class RemoteMcpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)

data class RemoteMcpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
)

data class RemoteMcpResult(
    val content: List<JsonObject>,
    val structuredContent: JsonObject?,
    val isError: Boolean,
)

enum class RemoteMcpFailureCode {
    CLOSED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
    LIMIT_EXCEEDED,
    PROTOCOL_ERROR,
}

class RemoteMcpException(
    val failureCode: RemoteMcpFailureCode,
    val httpStatus: Int? = null,
) : Exception(failureCode.name)
