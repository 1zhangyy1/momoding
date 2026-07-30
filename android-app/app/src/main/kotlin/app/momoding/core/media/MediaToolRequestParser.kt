package app.momoding.core.media

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal object MediaToolRequestParser {
    fun parse(arguments: JsonElement): MediaToolRequest {
        val objectValue = arguments as? JsonObject ?: throw MediaToolArgumentsException()
        val action = (objectValue["action"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.let(MediaToolAction::fromWireValue)
            ?: throw MediaToolArgumentsException()
        val handle = (objectValue["mediaHandle"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf { it.length == MEDIA_HANDLE_LENGTH && MEDIA_HANDLE_PATTERN.matches(it) }
            ?: throw MediaToolArgumentsException()
        return when (action) {
            MediaToolAction.SET_FAVORITE -> {
                objectValue.requireExactKeys("action", "mediaHandle", "favorite")
                MediaToolRequest.SetFavorite(
                    handle,
                    objectValue.requiredBoolean("favorite"),
                )
            }
            MediaToolAction.SET_TRASHED -> {
                objectValue.requireExactKeys("action", "mediaHandle", "trashed")
                MediaToolRequest.SetTrashed(
                    handle,
                    objectValue.requiredBoolean("trashed"),
                )
            }
            MediaToolAction.DELETE -> {
                objectValue.requireExactKeys("action", "mediaHandle")
                MediaToolRequest.Delete(handle)
            }
        }
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.booleanOrNull
            ?: throw MediaToolArgumentsException()

    private fun JsonObject.requireExactKeys(vararg required: String) {
        val expected = required.toSet()
        if (keys != expected) throw MediaToolArgumentsException()
    }
}

internal class MediaToolArgumentsException : IllegalArgumentException()
