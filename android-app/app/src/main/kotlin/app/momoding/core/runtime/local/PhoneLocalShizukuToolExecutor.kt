package app.momoding.core.runtime.local

import app.momoding.core.shizuku.ANDROID_SHELL_UID
import app.momoding.core.shizuku.ShizukuController
import app.momoding.core.shizuku.ShizukuLifecycleStage
import app.momoding.core.shizuku.ShizukuPackageDetails
import app.momoding.core.shizuku.ShizukuPackageSummary
import app.momoding.core.shizuku.ShizukuQueryResult
import app.momoding.core.shizuku.ShizukuSnapshot
import app.momoding.core.shizuku.validShizukuPackageName
import app.momoding.core.shizuku.validShizukuPackagePage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

interface PhoneLocalPackageToolHandler {
    fun handles(toolName: String): Boolean
    suspend fun execute(taskId: String, request: PiNativeToolRequest): PiNativeAndroidToolResult
}

internal interface PhoneLocalShizukuClient {
    fun snapshot(): ShizukuSnapshot

    suspend fun listPackages(
        includeSystem: Boolean,
        offset: Int,
        limit: Int,
    ): ShizukuQueryResult<List<ShizukuPackageSummary>>

    suspend fun inspectPackage(packageName: String): ShizukuQueryResult<ShizukuPackageDetails>
}

private class ControllerShizukuClient(
    private val controller: ShizukuController,
) : PhoneLocalShizukuClient {
    override fun snapshot(): ShizukuSnapshot = controller.state.value

    override suspend fun listPackages(
        includeSystem: Boolean,
        offset: Int,
        limit: Int,
    ) = controller.listPackages(includeSystem, offset, limit)

    override suspend fun inspectPackage(packageName: String) =
        controller.inspectPackage(packageName)
}

class PhoneLocalShizukuToolExecutor internal constructor(
    private val client: PhoneLocalShizukuClient,
) : PhoneLocalPackageToolHandler {
    constructor(controller: ShizukuController) : this(ControllerShizukuClient(controller))

    override fun handles(toolName: String): Boolean = toolName in SUPPORTED_TOOLS

    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        if (taskId.isBlank() || !handles(request.toolName)) {
            return failure(
                code = "INVALID_DEVICE_TOOL_ARGUMENTS",
                message = "Android package query arguments are invalid.",
            )
        }
        return try {
            when (request.toolName) {
                LIST_TOOL -> {
                    val arguments = parseListArguments(request.arguments)
                    val identity = readyIdentity()
                        ?: return capabilityNotReady(client.snapshot())
                    listPackages(arguments, identity)
                }
                INSPECT_TOOL -> {
                    val arguments = parseInspectArguments(request.arguments)
                    val identity = readyIdentity()
                        ?: return capabilityNotReady(client.snapshot())
                    inspectPackage(arguments, identity)
                }
                else -> error("Unsupported package tool reached executor")
            }
        } catch (_: InvalidPackageToolArguments) {
            failure(
                code = "INVALID_DEVICE_TOOL_ARGUMENTS",
                message = "Android package query arguments are invalid.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure(
                code = "PACKAGE_QUERY_FAILED",
                message = "Android package facts could not be read.",
            )
        }
    }

    private suspend fun listPackages(
        arguments: PackageListArguments,
        identity: ShizukuSnapshot,
    ): PiNativeAndroidToolResult {
        return when (
            val result = client.listPackages(
                arguments.includeSystem,
                arguments.offset,
                arguments.limit,
            )
        ) {
            is ShizukuQueryResult.Failed -> queryFailure(result)
            is ShizukuQueryResult.Ready -> {
                val currentIdentity = readyIdentity()
                    ?: return capabilityNotReady(client.snapshot())
                if (currentIdentity.servicePid != identity.servicePid) {
                    return failure(
                        code = "PACKAGE_QUERY_SESSION_CHANGED",
                        message = "The Android package session changed. Try again.",
                    )
                }
                success(
                    buildJsonObject {
                        putIdentity(currentIdentity)
                        put("includeSystem", arguments.includeSystem)
                        put("offset", arguments.offset)
                        put("limit", arguments.limit)
                        put("count", result.value.size)
                        put(
                            "packages",
                            buildJsonArray {
                                result.value.forEach { item -> add(item.toJson()) }
                            },
                        )
                    },
                )
            }
        }
    }

    private suspend fun inspectPackage(
        arguments: PackageInspectArguments,
        identity: ShizukuSnapshot,
    ): PiNativeAndroidToolResult {
        return when (val result = client.inspectPackage(arguments.packageName)) {
            is ShizukuQueryResult.Failed -> queryFailure(result)
            is ShizukuQueryResult.Ready -> {
                val currentIdentity = readyIdentity()
                    ?: return capabilityNotReady(client.snapshot())
                if (currentIdentity.servicePid != identity.servicePid) {
                    return failure(
                        code = "PACKAGE_QUERY_SESSION_CHANGED",
                        message = "The Android package session changed. Try again.",
                    )
                }
                success(
                    buildJsonObject {
                        putIdentity(currentIdentity)
                        put("package", result.value.toJson())
                    },
                )
            }
        }
    }

    private fun readyIdentity(): ShizukuSnapshot? = client.snapshot().takeIf {
        it.stage == ShizukuLifecycleStage.READY &&
            it.serverUid == ANDROID_SHELL_UID &&
            it.serviceUid == ANDROID_SHELL_UID &&
            it.servicePid != null
    }

    private fun queryFailure(
        result: ShizukuQueryResult.Failed,
    ): PiNativeAndroidToolResult = if (result.code == "SHIZUKU_NOT_READY") {
        capabilityNotReady(client.snapshot())
    } else {
        failure(result.code, result.safeMessage)
    }

    private fun capabilityNotReady(snapshot: ShizukuSnapshot) = failure(
        code = "CAPABILITY_NOT_READY",
        message = snapshot.safeMessage,
        capabilityState = snapshot.stage.name.lowercase(),
    )

    private fun success(payload: JsonObject) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", true)
            payload.forEach { (key, value) -> put(key, value) }
        },
        isError = false,
    )

    private fun failure(
        code: String,
        message: String,
        capabilityState: String? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("ok", false)
            put("errorCode", code)
            put("errorMessage", message)
            capabilityState?.let { put("capabilityState", it) }
        },
        isError = true,
    )

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIdentity(
        snapshot: ShizukuSnapshot,
    ) {
        put("capabilityId", "shizuku_shell_uid")
        put("serverUid", requireNotNull(snapshot.serverUid))
        put("serviceUid", requireNotNull(snapshot.serviceUid))
        put("servicePid", requireNotNull(snapshot.servicePid))
    }

    private fun ShizukuPackageSummary.toJson() = buildJsonObject {
        put("packageName", packageName)
        put("label", label)
        put("system", system)
        put("enabled", enabled)
    }

    private fun ShizukuPackageDetails.toJson() = buildJsonObject {
        put("packageName", packageName)
        put("label", label)
        put("system", system)
        put("enabled", enabled)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("minSdk", minSdk)
        put("targetSdk", targetSdk)
    }

    private fun parseListArguments(arguments: JsonObject): PackageListArguments {
        requireExactKeys(
            arguments,
            allowed = setOf("purpose", "includeSystem", "offset", "limit"),
            required = setOf("purpose"),
        )
        requirePurpose(arguments)
        val includeSystem = arguments.optionalBoolean("includeSystem", false)
        val offset = arguments.optionalInt("offset", 0)
        val limit = arguments.optionalInt("limit", DEFAULT_PACKAGE_LIMIT)
        if (!validShizukuPackagePage(offset, limit)) {
            throw InvalidPackageToolArguments()
        }
        return PackageListArguments(includeSystem, offset, limit)
    }

    private fun parseInspectArguments(arguments: JsonObject): PackageInspectArguments {
        requireExactKeys(
            arguments,
            allowed = setOf("purpose", "packageName"),
            required = setOf("purpose", "packageName"),
        )
        requirePurpose(arguments)
        val packageName = arguments.string("packageName")
            .takeIf(::validShizukuPackageName)
            ?: throw InvalidPackageToolArguments()
        return PackageInspectArguments(packageName)
    }

    private fun requirePurpose(arguments: JsonObject) {
        val purpose = arguments.string("purpose")
        if (purpose.isBlank() || purpose.length > MAX_PURPOSE_LENGTH) {
            throw InvalidPackageToolArguments()
        }
    }

    private fun JsonObject.string(key: String): String {
        val value = this[key] as? JsonPrimitive ?: throw InvalidPackageToolArguments()
        if (!value.isString) throw InvalidPackageToolArguments()
        return value.contentOrNull ?: throw InvalidPackageToolArguments()
    }

    private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean {
        val value = this[key] ?: return default
        val primitive = value as? JsonPrimitive ?: throw InvalidPackageToolArguments()
        if (primitive.isString) throw InvalidPackageToolArguments()
        return primitive.booleanOrNull ?: throw InvalidPackageToolArguments()
    }

    private fun JsonObject.optionalInt(key: String, default: Int): Int {
        val value = this[key] ?: return default
        val primitive = value as? JsonPrimitive ?: throw InvalidPackageToolArguments()
        if (primitive.isString) throw InvalidPackageToolArguments()
        return primitive.intOrNull ?: throw InvalidPackageToolArguments()
    }

    private fun requireExactKeys(
        arguments: JsonObject,
        allowed: Set<String>,
        required: Set<String>,
    ) {
        if (!arguments.keys.all(allowed::contains) || !arguments.keys.containsAll(required)) {
            throw InvalidPackageToolArguments()
        }
    }

    private class InvalidPackageToolArguments : IllegalArgumentException()
    private data class PackageListArguments(
        val includeSystem: Boolean,
        val offset: Int,
        val limit: Int,
    )
    private data class PackageInspectArguments(val packageName: String)

    companion object {
        const val LIST_TOOL = "device_packages_list"
        const val INSPECT_TOOL = "device_package_inspect"
        val SUPPORTED_TOOLS = setOf(LIST_TOOL, INSPECT_TOOL)
        private const val MAX_PURPOSE_LENGTH = 512
        private const val DEFAULT_PACKAGE_LIMIT = 50
    }
}
