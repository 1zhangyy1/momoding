package app.momoding.core.runtime.local

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityRequirement
import app.momoding.core.capabilities.AndroidCapabilityRequestOutcome
import app.momoding.core.capabilities.AndroidCapabilityRequestResult
import app.momoding.core.capabilities.AndroidCapabilityRequester
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CalendarCapabilityAccess
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.capabilities.ContactsCapabilityAccess
import app.momoding.core.capabilities.DEVICE_CAPABILITY_REQUEST_TOOL
import app.momoding.core.capabilities.LocationCapabilityAccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface TaskFolderGrantBinder {
    suspend fun bind(taskId: String, grantId: String): Boolean
}

fun interface PhoneLocalCapabilityRequestToolHandler {
    suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult

    fun handles(toolName: String): Boolean = toolName == DEVICE_CAPABILITY_REQUEST_TOOL
}

class PhoneLocalCapabilityRequestToolExecutor(
    private val requester: AndroidCapabilityRequester,
    private val registry: AndroidCapabilityRegistry,
    private val folderGrantBinder: TaskFolderGrantBinder,
) : PhoneLocalCapabilityRequestToolHandler {
    override suspend fun execute(
        taskId: String,
        request: PiNativeToolRequest,
    ): PiNativeAndroidToolResult {
        require(handles(request.toolName)) { "PI_MOBILE_NATIVE_TOOL_NOT_ALLOWED" }
        val capabilityValue = request.arguments["capability"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            .orEmpty()
        val capability = CAPABILITIES_BY_WIRE[capabilityValue]
            ?: throw IllegalArgumentException("PI_MOBILE_CAPABILITY_REQUEST_ARGUMENTS_INVALID")
        val requirement = if (
            capability == AndroidCapabilityId.CALENDAR ||
            capability == AndroidCapabilityId.CONTACTS ||
            capability == AndroidCapabilityId.LOCATION
        ) {
            require(request.arguments.keys == TYPED_ACCESS_ARGUMENT_KEYS) {
                "PI_MOBILE_CAPABILITY_REQUEST_ARGUMENTS_INVALID"
            }
            val access = request.arguments["requiredAccess"]?.jsonPrimitive?.contentOrNull
            when (capability) {
                AndroidCapabilityId.CALENDAR -> AndroidCapabilityRequirement.Calendar(
                    access?.let(CalendarCapabilityAccess::fromWireValue)
                        ?: invalidArguments(),
                )
                AndroidCapabilityId.CONTACTS -> AndroidCapabilityRequirement.Contacts(
                    access?.let(ContactsCapabilityAccess::fromWireValue)
                        ?: invalidArguments(),
                )
                AndroidCapabilityId.LOCATION -> AndroidCapabilityRequirement.Location(
                    access?.let(LocationCapabilityAccess::fromWireValue)
                        ?: invalidArguments(),
                )
                else -> error("Typed capability branch changed")
            }
        } else {
            require(request.arguments.keys == DEFAULT_ARGUMENT_KEYS) {
                "PI_MOBILE_CAPABILITY_REQUEST_ARGUMENTS_INVALID"
            }
            AndroidCapabilityRequirement.Default
        }
        val purpose = request.arguments["purpose"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            .orEmpty()
        require(purpose.isNotEmpty() && purpose.length <= MAX_PURPOSE_UTF16) {
            "PI_MOBILE_CAPABILITY_REQUEST_ARGUMENTS_INVALID"
        }

        val before = registry.refreshNow().first { it.id == capability }
        if (
            capability != AndroidCapabilityId.SAF_FOLDERS &&
            before.isReadyForUse(requirement)
        ) {
            return ready(capability, requirement, before, requested = false)
        }
        if (before.availability == CapabilityAvailability.UNSUPPORTED) {
            return failed(
                capability,
                "DEVICE_CAPABILITY_UNAVAILABLE",
                before.safeMessage,
            )
        }

        val outcome = try {
            requester.request(taskId, capability, requirement, purpose)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AndroidCapabilityRequestOutcome(AndroidCapabilityRequestResult.UNAVAILABLE)
        }
        if (
            outcome.result == AndroidCapabilityRequestResult.READY &&
            capability == AndroidCapabilityId.SAF_FOLDERS
        ) {
            val grantId = outcome.grantId
            if (grantId == null || !folderGrantBinder.bind(taskId, grantId)) {
                return failed(
                    capability,
                    "TASK_FOLDER_BIND_FAILED",
                    "The selected Android folder could not be attached to this task.",
                )
            }
        }
        val after = registry.refreshNow().first { it.id == capability }
        return when (outcome.result) {
            AndroidCapabilityRequestResult.READY -> {
                if (after.isReadyForUse(requirement)) {
                    ready(
                        capability,
                        requirement,
                        after,
                        requested = true,
                        grantId = outcome.grantId,
                    )
                } else {
                    failed(
                        capability,
                        "DEVICE_CAPABILITY_NOT_READY",
                        after.safeMessage,
                    )
                }
            }
            AndroidCapabilityRequestResult.DENIED -> failed(
                capability,
                "DEVICE_CAPABILITY_DENIED",
                "The user did not enable ${capability.wireValue()}.",
            )
            AndroidCapabilityRequestResult.UNAVAILABLE -> failed(
                capability,
                "DEVICE_CAPABILITY_UNAVAILABLE",
                after.safeMessage,
            )
            AndroidCapabilityRequestResult.TIMEOUT -> failed(
                capability,
                "DEVICE_CAPABILITY_TIMEOUT",
                "Android access setup timed out.",
            )
        }
    }

    private fun ready(
        capability: AndroidCapabilityId,
        requirement: AndroidCapabilityRequirement,
        state: AndroidCapabilityState,
        requested: Boolean,
        grantId: String? = null,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("capability", capability.wireValue())
            requirement.wireValueOrNull()?.let { put("requiredAccess", it) }
            put("availability", state.availability.name.lowercase())
            put("ready", true)
            put("requested", requested)
            grantId?.let { put("grantId", it) }
            put("message", state.safeMessage)
        },
    )

    private fun failed(
        capability: AndroidCapabilityId,
        code: String,
        message: String,
    ) = PiNativeAndroidToolResult(
        contentPayload = buildJsonObject {
            put("capability", capability.wireValue())
            put("code", code)
            put("message", message)
        },
        isError = true,
    )

    private fun AndroidCapabilityState.isReadyForUse(
        requirement: AndroidCapabilityRequirement,
    ): Boolean = when {
        availability == CapabilityAvailability.READY -> true
        id == AndroidCapabilityId.PHOTO_LIBRARY &&
            availability == CapabilityAvailability.PARTIAL -> true
        id == AndroidCapabilityId.CALENDAR &&
            availability == CapabilityAvailability.PARTIAL &&
            requirement == AndroidCapabilityRequirement.Calendar(
                CalendarCapabilityAccess.READ,
            ) -> true
        id == AndroidCapabilityId.CONTACTS &&
            availability == CapabilityAvailability.PARTIAL &&
            requirement == AndroidCapabilityRequirement.Contacts(
                ContactsCapabilityAccess.READ,
            ) -> true
        id == AndroidCapabilityId.LOCATION &&
            availability == CapabilityAvailability.PARTIAL &&
            requirement == AndroidCapabilityRequirement.Location(
                LocationCapabilityAccess.APPROXIMATE,
            ) -> true
        else -> false
    }

    private fun AndroidCapabilityRequirement.wireValueOrNull(): String? = when (this) {
        AndroidCapabilityRequirement.Default -> null
        is AndroidCapabilityRequirement.Calendar -> access.wireValue
        is AndroidCapabilityRequirement.Contacts -> access.wireValue
        is AndroidCapabilityRequirement.Location -> access.wireValue
    }

    private fun invalidArguments(): Nothing = throw IllegalArgumentException(
        "PI_MOBILE_CAPABILITY_REQUEST_ARGUMENTS_INVALID",
    )

    private fun AndroidCapabilityId.wireValue(): String = name.lowercase()

    private companion object {
        const val MAX_PURPOSE_UTF16 = 512
        val DEFAULT_ARGUMENT_KEYS = setOf("capability", "purpose")
        val TYPED_ACCESS_ARGUMENT_KEYS = setOf("capability", "requiredAccess", "purpose")
        val CAPABILITIES_BY_WIRE = AndroidCapabilityId.entries.associateBy { it.name.lowercase() }
    }
}
