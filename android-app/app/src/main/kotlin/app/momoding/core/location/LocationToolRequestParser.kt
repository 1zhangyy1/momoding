package app.momoding.core.location

import app.momoding.core.capabilities.LocationCapabilityAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object LocationToolRequestParser {
    fun parse(arguments: JsonObject): LocationToolRequest {
        if (arguments.keys != REQUIRED_KEYS) throw LocationToolArgumentsException()
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull
            ?.let(LocationToolAction::fromWireValue)
            ?: throw LocationToolArgumentsException()
        val precision = arguments["precision"]?.jsonPrimitive?.contentOrNull
            ?.let(LocationCapabilityAccess::fromWireValue)
            ?: throw LocationToolArgumentsException()
        val purpose = arguments["purpose"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            .orEmpty()
        if (purpose.isEmpty() || purpose.length > MAX_PURPOSE_UTF16) {
            throw LocationToolArgumentsException()
        }
        return LocationToolRequest(action, precision, purpose)
    }

    private const val MAX_PURPOSE_UTF16 = 160
    private val REQUIRED_KEYS = setOf("action", "precision", "purpose")
}
