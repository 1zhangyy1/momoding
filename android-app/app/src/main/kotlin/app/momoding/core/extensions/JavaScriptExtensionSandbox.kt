package app.momoding.core.extensions

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class JavaScriptSandboxExecution(
    val text: String? = null,
    val hostToolName: String? = null,
    val hostArguments: JsonObject? = null,
    val stateJson: Map<String, String>,
    val stateChanged: Boolean,
)

class JavaScriptExtensionSandbox(
    private val dispatcher: CoroutineDispatcher,
    private val onExecutionStarted: () -> Unit = {},
) {
    suspend fun execute(
        invocation: JavaScriptExtensionInvocation,
        arguments: JsonObject,
    ): JavaScriptSandboxExecution {
        val extensionPackage = invocation.extensionPackage
        val entrypoint = extensionPackage.entrypoint
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_ENTRYPOINT_MISSING")
        val modules = invocation.files
            .filter { it.relativePath.startsWith("dist/") && it.relativePath.endsWith(".js") }
            .associate { it.relativePath to strictUtf8(it.content) }
        val entrySource = modules[entrypoint]
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_ENTRYPOINT_MISSING")
        val resources = invocation.files.associateBy(ExtensionPackageFile::relativePath)
        val state = invocation.stateJson.toMutableMap()
        var stateChanged = false
        val quickJs = QuickJs.create(dispatcher).apply {
            memoryLimit = SANDBOX_MEMORY_LIMIT_BYTES
            maxStackSize = SANDBOX_STACK_LIMIT_BYTES
            evaluationTimeoutMillis = SANDBOX_LOAD_TIMEOUT_MILLIS
        }
        try {
            quickJs.define("__momodingHost") {
                function("executionStarted") { _ ->
                    onExecutionStarted()
                    Unit
                }
                function("readResource") { args ->
                    val path = requireSingleString(args, "EXTENSION_PACKAGE_RESOURCE_PATH_INVALID")
                    requireValidExtensionPackagePath(path)
                    val file = resources[path]
                        ?: throw ExtensionPackageException("EXTENSION_PACKAGE_RESOURCE_NOT_FOUND")
                    if (path == EXTENSION_MANIFEST_PATH || path.startsWith("dist/")) {
                        throw ExtensionPackageException("EXTENSION_PACKAGE_RESOURCE_NOT_READABLE")
                    }
                    if (file.content.size > MAX_SANDBOX_RESOURCE_READ_BYTES) {
                        throw ExtensionPackageException("EXTENSION_PACKAGE_RESOURCE_TOO_LARGE")
                    }
                    strictUtf8(file.content)
                }
                function("getStateJson") { args ->
                    val key = requireStateKey(requireSingleString(args, "EXTENSION_PACKAGE_STATE_KEY_INVALID"))
                    state[key] ?: "null"
                }
                function("setStateJson") { args ->
                    val key = requireStateKey(args.getOrNull(0) as? String)
                    val valueJson = args.getOrNull(1) as? String
                        ?: throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_VALUE_INVALID")
                    val next = state.toMutableMap().apply { put(key, valueJson) }
                    requireSandboxState(next)
                    state.clear()
                    state.putAll(next)
                    stateChanged = true
                    Unit
                }
                function("deleteState") { args ->
                    val key = requireStateKey(requireSingleString(args, "EXTENSION_PACKAGE_STATE_KEY_INVALID"))
                    if (state.remove(key) != null) stateChanged = true
                    Unit
                }
            }
            quickJs.evaluate<Any?>(
                sandboxPrelude(
                    javascriptToolNames = extensionPackage.tools
                        .filter { it.type == ExtensionToolType.JAVASCRIPT_TOOL.wireValue }
                        .map(ExtensionToolSnapshot::name),
                    hostToolNames = extensionPackage.tools
                        .filter { it.type in HOST_CALL_TOOL_TYPES }
                        .map(ExtensionToolSnapshot::name),
                ),
                "momoding-sandbox-bootstrap.js",
            )
            quickJs.addModule(MOBILE_SDK_MODULE, MOBILE_SDK_SOURCE)
            modules.filterKeys { it != entrypoint }.forEach { (name, source) ->
                quickJs.addModule(name, source)
            }
            withTimeout(SANDBOX_LOAD_TIMEOUT_MILLIS) {
                quickJs.evaluate<Any?>(entrySource, entrypoint, asModule = true)
                val registration = quickJs.evaluate<String>(
                    "globalThis.__momodingExtension.registrationJson()",
                    "momoding-sandbox-registration.js",
                )
                val expected = extensionPackage.tools
                    .filter { it.type == ExtensionToolType.JAVASCRIPT_TOOL.wireValue }
                    .map(ExtensionToolSnapshot::name)
                    .sorted()
                val actual = json.decodeFromString<List<String>>(registration)
                if (actual != expected) {
                    throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_REGISTRATION_MISMATCH")
                }
            }
            quickJs.evaluationTimeoutMillis = SANDBOX_EXECUTION_TIMEOUT_MILLIS
            val resultJson = withTimeout(SANDBOX_EXECUTION_TIMEOUT_MILLIS) {
                quickJs.evaluate<String>(
                    "globalThis.__momodingExtension.executeJson(" +
                        "${JsonPrimitive(invocation.tool.name)},${JsonPrimitive(arguments.toString())})",
                    "momoding-sandbox-execute.js",
                )
            }
            val result = try {
                json.parseToJsonElement(resultJson).jsonObject
            } catch (_: Exception) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_RESULT_INVALID")
            }
            return when (result.string("kind")) {
                "result" -> JavaScriptSandboxExecution(
                    text = result.string("text").also(::requireBoundedOutput),
                    stateJson = state.toMap(),
                    stateChanged = stateChanged,
                )
                "host-call" -> {
                    if (stateChanged) {
                        throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_WITH_HOST_CALL_UNSUPPORTED")
                    }
                    JavaScriptSandboxExecution(
                        hostToolName = result.string("toolName"),
                        hostArguments = result["arguments"] as? JsonObject
                            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_HOST_CALL_INVALID"),
                        stateJson = state.toMap(),
                        stateChanged = false,
                    ).also { execution ->
                        if (execution.hostToolName !in extensionPackage.tools
                                .filter { it.type in HOST_CALL_TOOL_TYPES }
                                .map(ExtensionToolSnapshot::name)
                        ) {
                            throw ExtensionPackageException("EXTENSION_PACKAGE_HOST_CALL_NOT_DECLARED")
                        }
                        if (execution.hostArguments.toString().toByteArray(Charsets.UTF_8).size >
                            MAX_SANDBOX_HOST_ARGUMENT_BYTES
                        ) {
                            throw ExtensionPackageException("EXTENSION_PACKAGE_HOST_CALL_TOO_LARGE")
                        }
                    }
                }
                else -> throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_RESULT_INVALID")
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_TIMEOUT")
        } catch (error: QuickJsInterruptedException) {
            currentCoroutineContext().ensureActive()
            throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_TIMEOUT")
        } catch (error: QuickJsException) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_FAILED")
        } finally {
            quickJs.close()
        }
    }

    private fun strictUtf8(content: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(content))
            .toString()
    } catch (_: Exception) {
        throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_UTF8_INVALID")
    }

    private fun requireSingleString(args: Array<out Any?>, code: String): String =
        (args.singleOrNull() as? String)?.takeIf { it.isNotBlank() && '\u0000' !in it }
            ?: throw ExtensionPackageException(code)

    private fun requireStateKey(value: String?): String = value
        ?.takeIf { STATE_KEY.matches(it) }
        ?: throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_KEY_INVALID")

    private fun requireSandboxState(values: Map<String, String>) {
        if (values.size > MAX_EXTENSION_STATE_ENTRIES) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
        }
        var totalBytes = 0
        values.forEach { (_, valueJson) ->
            val bytes = valueJson.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_EXTENSION_STATE_VALUE_BYTES) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_VALUE_TOO_LARGE")
            }
            totalBytes += bytes
            if (totalBytes > MAX_EXTENSION_STATE_TOTAL_BYTES) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_LIMIT_EXCEEDED")
            }
            try {
                json.parseToJsonElement(valueJson)
            } catch (_: Exception) {
                throw ExtensionPackageException("EXTENSION_PACKAGE_STATE_VALUE_INVALID")
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_RESULT_INVALID")

    private fun requireBoundedOutput(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SANDBOX_OUTPUT_BYTES) {
            throw ExtensionPackageException("EXTENSION_PACKAGE_JAVASCRIPT_OUTPUT_TOO_LARGE")
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
        val STATE_KEY = Regex("^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$")
        val HOST_CALL_TOOL_TYPES = setOf(
            ExtensionToolType.ANDROID_TOOL_ALIAS.wireValue,
            ExtensionToolType.CONNECTOR_PROXY.wireValue,
        )
        const val SANDBOX_MEMORY_LIMIT_BYTES = 16L * 1024L * 1024L
        const val SANDBOX_STACK_LIMIT_BYTES = 1024L * 1024L
        const val SANDBOX_LOAD_TIMEOUT_MILLIS = 750L
        const val SANDBOX_EXECUTION_TIMEOUT_MILLIS = 1_500L
        const val MAX_SANDBOX_OUTPUT_BYTES = 32 * 1024
        const val MAX_SANDBOX_HOST_ARGUMENT_BYTES = 16 * 1024
        const val MAX_SANDBOX_RESOURCE_READ_BYTES = 64 * 1024
        const val MOBILE_SDK_MODULE = "@momoding/sdk"
        val MOBILE_SDK_SOURCE = """
            const runtime = globalThis.__momodingExtension;
            export const registerTool = runtime.registerTool;
            export const readResource = runtime.readResource;
            export const getState = runtime.getState;
            export const setState = runtime.setState;
            export const deleteState = runtime.deleteState;
            export const callTool = runtime.callTool;
        """.trimIndent()
    }
}

private fun sandboxPrelude(
    javascriptToolNames: List<String>,
    hostToolNames: List<String>,
): String = """
    (() => {
      "use strict";
      const declaredTools = new Set(${javascriptToolNamesJson(javascriptToolNames)});
      const hostTools = new Set(${javascriptToolNamesJson(hostToolNames)});
      const handlers = new Map();
      const hostCallBrand = Symbol("momoding.host-call");
      let registrationOpen = true;
      const exactKeys = (value, expected) => {
        if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
        const keys = Object.keys(value).sort();
        return keys.length === expected.length && keys.every((key, index) => key === expected[index]);
      };
      const registerTool = (definition) => {
        if (!registrationOpen || !exactKeys(definition, ["execute", "name"])) {
          throw new Error("MOMODING_EXTENSION_REGISTRATION_INVALID");
        }
        if (!declaredTools.has(definition.name) || typeof definition.execute !== "function" ||
            handlers.has(definition.name)) {
          throw new Error("MOMODING_EXTENSION_REGISTRATION_INVALID");
        }
        handlers.set(definition.name, definition.execute);
      };
      const host = globalThis.__momodingHost;
      const readResource = (path) => host.readResource(path);
      const getState = (key) => JSON.parse(host.getStateJson(key));
      const setState = (key, value) => {
        const encoded = JSON.stringify(value);
        if (encoded === undefined) throw new Error("MOMODING_EXTENSION_STATE_INVALID");
        host.setStateJson(key, encoded);
      };
      const deleteState = (key) => host.deleteState(key);
      const callTool = (toolName, args = {}) => {
        if (!hostTools.has(toolName) || args === null || typeof args !== "object" || Array.isArray(args)) {
          throw new Error("MOMODING_EXTENSION_HOST_CALL_INVALID");
        }
        return { [hostCallBrand]: true, toolName, arguments: args };
      };
      const registrationJson = () => {
        registrationOpen = false;
        return JSON.stringify([...handlers.keys()].sort());
      };
      const executeJson = (toolName, argumentsJson) => {
        registrationOpen = false;
        const handler = handlers.get(toolName);
        if (handler === undefined) throw new Error("MOMODING_EXTENSION_TOOL_MISSING");
        const args = JSON.parse(argumentsJson);
        host.executionStarted();
        const value = handler(args);
        if (value !== null && typeof value === "object" && typeof value.then === "function") {
          throw new Error("MOMODING_EXTENSION_ASYNC_UNSUPPORTED");
        }
        if (value !== null && typeof value === "object" && value[hostCallBrand] === true) {
          return JSON.stringify({ kind: "host-call", toolName: value.toolName, arguments: value.arguments });
        }
        if (typeof value === "string") return JSON.stringify({ kind: "result", text: value });
        if (exactKeys(value, ["text"]) && typeof value.text === "string") {
          return JSON.stringify({ kind: "result", text: value.text });
        }
        throw new Error("MOMODING_EXTENSION_RESULT_INVALID");
      };
      Object.defineProperty(globalThis, "__momodingExtension", {
        value: Object.freeze({
          registerTool, readResource, getState, setState, deleteState, callTool,
          registrationJson, executeJson,
        }),
        writable: false,
        configurable: false,
        enumerable: false,
      });
    })();
""".trimIndent()

private fun javascriptToolNamesJson(names: List<String>): String =
    names.sorted().joinToString(prefix = "[", postfix = "]") { JsonPrimitive(it).toString() }
