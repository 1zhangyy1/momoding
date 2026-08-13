package app.momoding.core.connector

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.GzipSink
import okio.buffer

internal class DeterministicMcpDispatcher : Dispatcher() {
    data class ObservedRequest(
        val method: String,
        val protocolVersion: String?,
        val authorization: String?,
    )

    val observedRequests = CopyOnWriteArrayList<ObservedRequest>()
    val terminatedSessions = AtomicInteger()
    val terminatedAuthorizations = CopyOnWriteArrayList<String?>()

    var statusForMethod: Pair<String, Int>? = null
    var malformedMethod: String? = null
    var oversizedSseMethod: String? = null
    var delayedMethod: String? = null
    var chunkedMethod: String? = null
    var gzipMethod: String? = null
    var delayMillis: Long = 2_000
    var inputSchema: JsonObject = defaultInputSchema()
    var callText: String = "one deterministic result"

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.method == "GET") return MockResponse().setResponseCode(405)
        if (request.method == "DELETE") {
            terminatedSessions.incrementAndGet()
            terminatedAuthorizations += request.getHeader("Authorization")
            return MockResponse().setResponseCode(200)
        }

        val body = request.body.readUtf8()
        val message = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return MockResponse().setResponseCode(400)
        val method = message["method"]?.jsonPrimitive?.content.orEmpty()
        observedRequests += ObservedRequest(
            method = method,
            protocolVersion = request.getHeader("mcp-protocol-version"),
            authorization = request.getHeader("Authorization"),
        )

        statusForMethod?.takeIf { it.first == method }?.let { (_, status) ->
            return MockResponse().setResponseCode(status).setBody("private upstream detail")
        }
        if (malformedMethod == method) {
            return MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{not-json")
        }
        if (oversizedSseMethod == method) {
            return MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: ${"x".repeat(4_096)}")
        }

        if (method == "notifications/initialized" || method == "notifications/cancelled") {
            return MockResponse().setResponseCode(202)
        }
        val id = requireNotNull(message["id"])
        val result = when (method) {
            "initialize" -> buildJsonObject {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", "deterministic-mcp")
                        put("version", "1.0.0")
                    })
                }

            "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "search_records")
                            put("description", "Search deterministic records")
                            put("inputSchema", inputSchema)
                        })
                    })
                }

            "tools/call" -> buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", callText)
                        })
                    })
                    put("structuredContent", buildJsonObject { put("count", 1) })
                    put("isError", false)
                }

            else -> return MockResponse().setResponseCode(400)
        }
        val response = jsonResponse(id, result)
        if (method == "initialize") response.setHeader("mcp-session-id", "session-fixture")
        return if (gzipMethod == method) {
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzip(jsonBody(id, result)))
        } else if (chunkedMethod == method) {
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setChunkedBody(jsonBody(id, result), 64)
        } else if (delayedMethod == method) {
            response.setBodyDelay(delayMillis, TimeUnit.MILLISECONDS)
        } else {
            response
        }
    }

    private fun jsonResponse(id: JsonElement, result: JsonObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(jsonBody(id, result))

    private fun jsonBody(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun gzip(body: String): Buffer = Buffer().also { target ->
        GzipSink(target).buffer().use { sink -> sink.writeUtf8(body) }
    }

    companion object {
        fun defaultInputSchema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            })
        }

        fun nestedInputSchema(depth: Int): JsonObject {
            var node = buildJsonObject { put("type", "string") }
            repeat(depth) {
                val child = node
                node = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { put("next", child) })
                }
            }
            return node
        }
    }
}
