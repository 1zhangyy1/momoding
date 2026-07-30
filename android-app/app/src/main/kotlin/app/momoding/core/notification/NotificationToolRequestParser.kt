package app.momoding.core.notification

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal object NotificationToolRequestParser {
    fun parse(arguments: JsonElement): NotificationToolRequest {
        val objectValue = arguments as? JsonObject
            ?: throw NotificationToolArgumentsException()
        val actionValue = (objectValue["action"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?: throw NotificationToolArgumentsException()
        return when (NotificationToolAction.fromWireValue(actionValue)) {
            NotificationToolAction.STATUS -> {
                objectValue.requireExactKeys("action")
                NotificationToolRequest.Status
            }
            NotificationToolAction.POST -> {
                objectValue.requireExactKeys("action", "title", "message")
                NotificationToolRequest.Post(
                    title = objectValue.requiredText(
                        "title",
                        MAX_NOTIFICATION_TITLE_LENGTH,
                    ),
                    message = objectValue.requiredText(
                        "message",
                        MAX_NOTIFICATION_MESSAGE_LENGTH,
                    ),
                )
            }
            NotificationToolAction.LIST_ACTIVE -> {
                objectValue.requireExactKeys("action", optional = setOf("limit"))
                val limit = objectValue["limit"]?.let { element ->
                    (element as? JsonPrimitive)
                        ?.takeUnless(JsonPrimitive::isString)
                        ?.intOrNull
                        ?: throw NotificationToolArgumentsException()
                } ?: DEFAULT_NOTIFICATION_LIST_LIMIT
                if (limit !in 1..MAX_NOTIFICATION_LIST_LIMIT) {
                    throw NotificationToolArgumentsException()
                }
                NotificationToolRequest.ListActive(limit)
            }
            NotificationToolAction.UPDATE -> {
                objectValue.requireExactKeys(
                    "action",
                    "notificationHandle",
                    "title",
                    "message",
                )
                NotificationToolRequest.Update(
                    notificationHandle = objectValue.requiredHandle(),
                    title = objectValue.requiredText(
                        "title",
                        MAX_NOTIFICATION_TITLE_LENGTH,
                    ),
                    message = objectValue.requiredText(
                        "message",
                        MAX_NOTIFICATION_MESSAGE_LENGTH,
                    ),
                )
            }
            NotificationToolAction.CANCEL -> {
                objectValue.requireExactKeys("action", "notificationHandle")
                NotificationToolRequest.Cancel(objectValue.requiredHandle())
            }
            NotificationToolAction.OPEN_SETTINGS -> {
                objectValue.requireExactKeys("action")
                NotificationToolRequest.OpenSettings
            }
            null -> throw NotificationToolArgumentsException()
        }
    }

    private fun JsonObject.requiredHandle(): String =
        requiredText("notificationHandle", 45).also {
            if (!NOTIFICATION_HANDLE_PATTERN.matches(it)) {
                throw NotificationToolArgumentsException()
            }
        }

    private fun JsonObject.requiredText(name: String, maximum: Int): String {
        val value = (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?: throw NotificationToolArgumentsException()
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.length > maximum) {
            throw NotificationToolArgumentsException()
        }
        return normalized
    }

    private fun JsonObject.requireExactKeys(
        vararg required: String,
        optional: Set<String> = emptySet(),
    ) {
        val requiredKeys = required.toSet()
        if (!keys.containsAll(requiredKeys) || !keys.all { it in requiredKeys || it in optional }) {
            throw NotificationToolArgumentsException()
        }
    }

    private val NOTIFICATION_HANDLE_PATTERN = Regex("^notification-[0-9a-f]{32}$")
}

internal class NotificationToolArgumentsException : IllegalArgumentException()
