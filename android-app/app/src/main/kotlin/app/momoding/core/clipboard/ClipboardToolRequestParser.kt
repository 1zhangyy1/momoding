package app.momoding.core.clipboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object ClipboardToolRequestParser {
    fun parse(arguments: JsonObject): ClipboardToolRequest {
        val action = (arguments["action"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.let(ClipboardToolAction::fromWireValue)
            ?: throw ClipboardToolArgumentsException()
        val purpose = (arguments["purpose"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (purpose.isEmpty() || purpose.length > MAX_PURPOSE_UTF16) {
            throw ClipboardToolArgumentsException()
        }
        return when (action) {
            ClipboardToolAction.GET -> {
                if (arguments.keys != GET_KEYS) throw ClipboardToolArgumentsException()
                ClipboardToolRequest.Get(purpose)
            }
            ClipboardToolAction.SET -> {
                if (arguments.keys != SET_KEYS) throw ClipboardToolArgumentsException()
                val text = (arguments["text"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?: throw ClipboardToolArgumentsException()
                if (text.isEmpty() || text.length > MAX_SET_TEXT_UTF16) {
                    throw ClipboardToolArgumentsException()
                }
                ClipboardToolRequest.Set(text, purpose)
            }
            ClipboardToolAction.CLEAR -> {
                if (arguments.keys != CLEAR_KEYS) throw ClipboardToolArgumentsException()
                ClipboardToolRequest.Clear(purpose)
            }
        }
    }

    private const val MAX_PURPOSE_UTF16 = 160
    private const val MAX_SET_TEXT_UTF16 = 4_096
    private val GET_KEYS = setOf("action", "purpose")
    private val SET_KEYS = setOf("action", "text", "purpose")
    private val CLEAR_KEYS = setOf("action", "purpose")
}
