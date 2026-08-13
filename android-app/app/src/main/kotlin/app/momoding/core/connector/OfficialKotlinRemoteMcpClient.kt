package app.momoding.core.connector

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * PXP-2 compatibility implementation around the official MCP Kotlin SDK.
 *
 * It deliberately owns no OAuth, persistence, Pi registration, or product UI. Callers provide only
 * short-lived request headers; transport/session types remain private to this implementation.
 */
internal class OfficialKotlinRemoteMcpClient private constructor(
    private val endpointUrl: String,
    private val httpClient: HttpClient,
    private val ownsHttpClient: Boolean = false,
    private val requestHeaders: () -> Map<String, String> = { emptyMap() },
    private val limits: Limits = Limits(),
) : RemoteMcpClient {
    data class Limits(
        val requestTimeout: Duration = 20.seconds,
        val maxInlineSseEventChars: Int = 256 * 1024,
        val maxHttpResponseBytes: Long = 512 * 1024,
        val maxTools: Int = 128,
        val maxToolSchemaChars: Int = 32 * 1024,
        val maxSchemaDepth: Int = 16,
        val maxResultContentBlocks: Int = 32,
        val maxResultChars: Int = 64 * 1024,
        val maxToolPages: Int = 8,
    ) {
        init {
            require(requestTimeout.isPositive())
            require(maxInlineSseEventChars > 0)
            require(maxHttpResponseBytes > 0)
            require(maxTools > 0)
            require(maxToolSchemaChars > 0)
            require(maxSchemaDepth > 0)
            require(maxResultContentBlocks > 0)
            require(maxResultChars > 0)
            require(maxToolPages > 0)
        }
    }

    private data class Session(
        val client: Client,
        val transport: StreamableHttpClientTransport,
        val serverInfo: RemoteMcpServerInfo,
    )

    private val stateMutex = Mutex()
    private var session: Session? = null
    private var closed = false

    init {
        require(endpointUrl.isNotBlank())
    }

    override suspend fun discover(): RemoteMcpServerInfo = remoteCall {
        requireSession().serverInfo
    }

    override suspend fun listTools(): List<RemoteMcpTool> = remoteCall {
        val activeClient = requireSession().client
        val tools = mutableListOf<RemoteMcpTool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0

        do {
            if (++pages > limits.maxToolPages) {
                throw limitFailure()
            }
            val response = activeClient.listTools(
                request = ListToolsRequest(cursor?.let(::PaginatedRequestParams)),
                options = RequestOptions(timeout = limits.requestTimeout),
            )
            response.tools.forEach { tool ->
                if (tools.size >= limits.maxTools) {
                    throw limitFailure()
                }
                tools += tool.toRemoteTool()
            }
            cursor = response.nextCursor
            if (cursor != null && !seenCursors.add(cursor)) {
                throw RemoteMcpException(RemoteMcpFailureCode.INVALID_RESPONSE)
            }
        } while (cursor != null)

        tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject): RemoteMcpResult = remoteCall {
        require(name.isNotBlank())
        val response = requireSession().client.callTool(
            request = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            options = RequestOptions(timeout = limits.requestTimeout),
        )
        if (response.content.size > limits.maxResultContentBlocks) {
            throw limitFailure()
        }
        val content = response.content.map { it.toBoundedJsonObject() }
        val structured = response.structuredContent
        val encodedChars = content.sumOf { McpJson.encodeToString(it).length } +
            (structured?.let { McpJson.encodeToString(it).length } ?: 0)
        if (encodedChars > limits.maxResultChars) {
            throw limitFailure()
        }
        RemoteMcpResult(
            content = content,
            structuredContent = structured,
            isError = response.isError == true,
        )
    }

    override suspend fun close() = remoteCall {
        val (shouldClose, activeSession) = stateMutex.withLock {
            if (closed) {
                false to null
            } else {
                closed = true
                true to session.also { session = null }
            }
        }
        if (!shouldClose) return@remoteCall
        try {
            activeSession?.let { active ->
                try {
                    active.transport.terminateSession()
                } finally {
                    active.client.close()
                }
            }
        } finally {
            if (ownsHttpClient) {
                httpClient.close()
            }
        }
    }

    private suspend fun requireSession(): Session = stateMutex.withLock {
        if (closed) throw RemoteMcpException(RemoteMcpFailureCode.CLOSED)
        session?.let { return@withLock it }

        val streamableTransport = StreamableHttpClientTransport(
            client = httpClient,
            url = endpointUrl,
            reconnectionOptions = ReconnectionOptions(
                initialReconnectionDelay = 100.milliseconds,
                maxReconnectionDelay = 500.milliseconds,
                maxRetries = 2,
            ),
            maxInlineSseEventSize = limits.maxInlineSseEventChars,
        )
        val negotiatingTransport = NegotiatingTransport(streamableTransport)
        val sdkClient = Client(Implementation(name = CLIENT_NAME, version = CLIENT_VERSION))
        try {
            withTimeout(limits.requestTimeout) {
                sdkClient.connect(negotiatingTransport)
            }
            val protocolVersion = negotiatingTransport.protocolVersion
                ?: throw RemoteMcpException(RemoteMcpFailureCode.INVALID_RESPONSE)
            val implementation = sdkClient.serverVersion
                ?: throw RemoteMcpException(RemoteMcpFailureCode.INVALID_RESPONSE)
            Session(
                client = sdkClient,
                transport = streamableTransport,
                serverInfo = RemoteMcpServerInfo(
                    name = implementation.name,
                    version = implementation.version,
                    protocolVersion = protocolVersion,
                ),
            ).also { session = it }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            sdkClient.close()
            throw failure
        }
    }

    private fun Tool.toRemoteTool(): RemoteMcpTool {
        require(name.isNotBlank())
        val schema = McpJson.encodeToJsonElement(ToolSchema.serializer(), inputSchema).jsonObject
        if (McpJson.encodeToString(schema).length > limits.maxToolSchemaChars ||
            schema.depth() > limits.maxSchemaDepth
        ) {
            throw limitFailure()
        }
        return RemoteMcpTool(name = name, description = description, inputSchema = schema)
    }

    private fun ContentBlock.toBoundedJsonObject(): JsonObject {
        val encoded = McpJson.encodeToJsonElement(ContentBlock.serializer(), this).jsonObject
        if (McpJson.encodeToString(encoded).length > limits.maxResultChars) {
            throw limitFailure()
        }
        return encoded
    }

    private suspend fun <T> remoteCall(block: suspend () -> T): T = try {
        block()
    } catch (timeout: TimeoutCancellationException) {
        if (currentCoroutineContext().isActive) {
            throw RemoteMcpException(RemoteMcpFailureCode.TIMEOUT)
        }
        throw timeout
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: RemoteMcpException) {
        throw failure
    } catch (failure: Throwable) {
        throw failure.toRemoteMcpException()
    }

    private fun Throwable.toRemoteMcpException(): RemoteMcpException {
        val transportFailure = causeSequence().filterIsInstance<StreamableHttpError>().firstOrNull()
        transportFailure?.code?.let { status ->
            val code = when (status) {
                401 -> RemoteMcpFailureCode.UNAUTHORIZED
                403 -> RemoteMcpFailureCode.FORBIDDEN
                404 -> RemoteMcpFailureCode.NOT_FOUND
                429 -> RemoteMcpFailureCode.RATE_LIMITED
                in 500..599 -> RemoteMcpFailureCode.SERVER_ERROR
                else -> RemoteMcpFailureCode.PROTOCOL_ERROR
            }
            return RemoteMcpException(code, httpStatus = status)
        }
        if (causeSequence().any { it is TooLongFrameException }) {
            return RemoteMcpException(RemoteMcpFailureCode.LIMIT_EXCEEDED)
        }
        if (causeSequence().any { it is McpBodyLimitException }) {
            return RemoteMcpException(RemoteMcpFailureCode.LIMIT_EXCEEDED)
        }
        if (causeSequence().any { it is SerializationException }) {
            return RemoteMcpException(RemoteMcpFailureCode.INVALID_RESPONSE)
        }
        if (causeSequence().any {
                it is HttpRequestTimeoutException ||
                    it is ConnectTimeoutException ||
                    it is SocketTimeoutException
            }
        ) {
            return RemoteMcpException(RemoteMcpFailureCode.TIMEOUT)
        }
        val mcpFailure = causeSequence().filterIsInstance<McpException>().firstOrNull()
        if (mcpFailure?.code == RPCError.ErrorCode.REQUEST_TIMEOUT) {
            return RemoteMcpException(RemoteMcpFailureCode.TIMEOUT)
        }
        return RemoteMcpException(
            failureCode = if (mcpFailure != null) {
                RemoteMcpFailureCode.PROTOCOL_ERROR
            } else {
                RemoteMcpFailureCode.NETWORK
            },
        )
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun JsonElement.depth(): Int = when (this) {
        is JsonObject -> 1 + (values.maxOfOrNull { it.depth() } ?: 0)
        is JsonArray -> 1 + (maxOfOrNull { it.depth() } ?: 0)
        else -> 1
    }

    private fun limitFailure() = RemoteMcpException(RemoteMcpFailureCode.LIMIT_EXCEEDED)

    private class NegotiatingTransport(
        private val delegate: StreamableHttpClientTransport,
    ) : Transport {
        var protocolVersion: String? = null
            private set

        override suspend fun start() = delegate.start()

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) =
            delegate.send(message, options)

        override suspend fun close() = delegate.close()

        override fun onClose(block: () -> Unit) = delegate.onClose(block)

        override fun onError(block: (Throwable) -> Unit) = delegate.onError(block)

        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
            delegate.onMessage { message ->
                val initialize = (message as? JSONRPCResponse)?.result as? InitializeResult
                if (initialize != null) {
                    protocolVersion = initialize.protocolVersion
                    delegate.protocolVersion = initialize.protocolVersion
                }
                block(message)
            }
        }
    }

    companion object {
        private const val CLIENT_NAME = "Momoding"
        private const val CLIENT_VERSION = "0.1.0"

        fun createAndroid(
            endpointUrl: String,
            requestHeaders: () -> Map<String, String> = { emptyMap() },
            limits: Limits = Limits(),
        ): OfficialKotlinRemoteMcpClient {
            val httpClient = HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = limits.requestTimeout.inWholeMilliseconds
                    requestTimeoutMillis = limits.requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = limits.requestTimeout.inWholeMilliseconds
                }
                engine {
                    addInterceptor(RequestHeadersInterceptor(requestHeaders))
                    addInterceptor(BoundedResponseInterceptor(limits.maxHttpResponseBytes))
                }
            }
            return OfficialKotlinRemoteMcpClient(
                endpointUrl = endpointUrl,
                httpClient = httpClient,
                ownsHttpClient = true,
                requestHeaders = requestHeaders,
                limits = limits,
            )
        }
    }
}

private class RequestHeadersInterceptor(
    private val requestHeaders: () -> Map<String, String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder().apply {
            requestHeaders().forEach { (name, value) -> header(name, value) }
        }.build()
        return chain.proceed(request)
    }
}

private class BoundedResponseInterceptor(
    private val maxBytes: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (body.contentLength() > maxBytes) {
            response.close()
            throw McpBodyLimitException()
        }
        return response.newBuilder()
            .body(BoundedResponseBody(body, maxBytes))
            .build()
    }
}

private class BoundedResponseBody(
    private val delegate: ResponseBody,
    private val maxBytes: Long,
) : ResponseBody() {
    private val boundedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            private var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val remaining = maxBytes - bytesRead
                val read = super.read(sink, minOf(byteCount, remaining + 1))
                if (read < 0) return read
                if (read > remaining) throw McpBodyLimitException()
                bytesRead += read
                return read
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = boundedSource
}

private class McpBodyLimitException : IOException("MCP response exceeded the configured byte limit")
