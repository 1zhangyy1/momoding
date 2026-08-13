package app.momoding.core.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private const val MAX_SCHEMA_BYTES = 8 * 1024
private const val MAX_SCHEMA_PROPERTIES = 16
private const val MAX_SCHEMA_ENUM_VALUES = 16

internal fun requireValidExtensionToolSchema(schema: JsonObject): JsonObject {
    if (schema.toString().toByteArray(Charsets.UTF_8).size > MAX_SCHEMA_BYTES) {
        failSchema()
    }
    requireExactKeys(
        schema,
        setOf("type", "properties", "required", "additionalProperties"),
    )
    if (schema.string("type") != "object" || schema.boolean("additionalProperties") != false) {
        failSchema()
    }
    val properties = schema["properties"] as? JsonObject ?: failSchema()
    if (properties.size > MAX_SCHEMA_PROPERTIES) failSchema()
    properties.forEach { (name, value) ->
        if (!PROPERTY_NAME.matches(name)) failSchema()
        validateProperty(value as? JsonObject ?: failSchema())
    }
    val required = (schema["required"] as? JsonArray)?.map { element ->
        val primitive = element as? JsonPrimitive ?: failSchema()
        if (!primitive.isString) failSchema()
        primitive.content
    } ?: failSchema()
    if (required.distinct().size != required.size || required.any { it !in properties }) failSchema()
    return schema
}

/** Mobile Profile v1 schema subset produced by the deterministic TypeBox AST packer. */
internal fun requireValidPiRegisterToolSchema(schema: JsonObject): JsonObject {
    if (schema.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) failSchema()
    if (schema.string("type") != "object" || schema.boolean("additionalProperties") != false) {
        failSchema()
    }
    validatePiSchemaNode(schema, depth = 1, root = true)
    return schema
}

private fun validatePiSchemaNode(
    schema: JsonObject,
    depth: Int,
    root: Boolean = false,
) {
    if (depth > 8) failSchema()
    val type = (schema["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    when {
        "const" in schema -> {
            val value = schema["const"] as? JsonPrimitive ?: failSchema()
            if (schema.keys != setOf("const") ||
                !value.isString && value.booleanOrNull == null && value.finiteDoubleOrNull() == null
            ) failSchema()
        }
        "anyOf" in schema -> {
            if (schema.keys != setOf("anyOf")) failSchema()
            val variants = schema["anyOf"] as? JsonArray ?: failSchema()
            if (variants.size !in 1..16) failSchema()
            variants.forEach { validatePiSchemaNode(it as? JsonObject ?: failSchema(), depth + 1) }
        }
        type == "object" -> {
            val allowed = setOf("type", "properties", "required", "additionalProperties", "description")
            if (schema.keys.any { it !in allowed } || schema.boolean("additionalProperties") != false) {
                failSchema()
            }
            val properties = schema["properties"] as? JsonObject ?: failSchema()
            if (properties.size > MAX_SCHEMA_PROPERTIES) failSchema()
            properties.forEach { (name, child) ->
                if (!PROPERTY_NAME.matches(name)) failSchema()
                validatePiSchemaNode(child as? JsonObject ?: failSchema(), depth + 1)
            }
            val required = (schema["required"] as? JsonArray)?.map { element ->
                val primitive = element as? JsonPrimitive ?: failSchema()
                if (!primitive.isString) failSchema()
                primitive.content
            }.orEmpty()
            if (required.distinct().size != required.size || required.any { it !in properties }) failSchema()
            if (root && type != "object") failSchema()
        }
        type == "array" -> {
            val allowed = setOf("type", "items", "description", "minItems", "maxItems")
            if (schema.keys.any { it !in allowed }) failSchema()
            validatePiSchemaNode(schema["items"] as? JsonObject ?: failSchema(), depth + 1)
            val minimum = schema.optionalInt("minItems")
            val maximum = schema.optionalInt("maxItems")
            if (minimum != null && minimum !in 0..64 || maximum != null && maximum !in 0..64 ||
                minimum != null && maximum != null && minimum > maximum
            ) failSchema()
        }
        type in setOf("string", "number", "integer", "boolean") -> validateProperty(schema)
        else -> failSchema()
    }
    schema["description"]?.let { description ->
        val primitive = description as? JsonPrimitive ?: failSchema()
        if (!primitive.isString || primitive.content.length !in 1..256) failSchema()
    }
}

internal fun requireValidExtensionToolArguments(schema: JsonObject, arguments: JsonObject) {
    requireValidExtensionToolSchema(schema)
    val properties = schema.getValue("properties") as JsonObject
    val required = (schema.getValue("required") as JsonArray).map { (it as JsonPrimitive).content }
    if (arguments.keys.any { it !in properties } || required.any { it !in arguments }) {
        throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_ARGUMENTS_INVALID")
    }
    arguments.forEach { (name, value) -> validateValue(properties.getValue(name) as JsonObject, value) }
}

private fun validateProperty(schema: JsonObject) {
    val allowed = setOf(
        "type", "description", "enum", "minLength", "maxLength", "minimum", "maximum",
    )
    if (schema.keys.any { it !in allowed }) failSchema()
    val type = schema.string("type")
    if (type !in setOf("string", "number", "integer", "boolean")) failSchema()
    schema["description"]?.let { value ->
        val description = value as? JsonPrimitive ?: failSchema()
        if (!description.isString || description.content.length !in 1..256) failSchema()
    }
    val minLength = schema.optionalInt("minLength")
    val maxLength = schema.optionalInt("maxLength")
    if (type == "string") {
        if (minLength != null && minLength !in 0..4_096) failSchema()
        if (maxLength != null && maxLength !in 0..4_096) failSchema()
        if (minLength != null && maxLength != null && minLength > maxLength) failSchema()
    } else if (minLength != null || maxLength != null) {
        failSchema()
    }
    val minimum = schema.optionalDouble("minimum")
    val maximum = schema.optionalDouble("maximum")
    if (type == "number" || type == "integer") {
        if (minimum != null && maximum != null && minimum > maximum) failSchema()
    } else if (minimum != null || maximum != null) {
        failSchema()
    }
    val values = schema["enum"]?.let { it as? JsonArray ?: failSchema() }
    if (values != null) {
        if (values.isEmpty() || values.size > MAX_SCHEMA_ENUM_VALUES) failSchema()
        values.forEach { validatePrimitiveType(type, it, ::failSchema) }
        if (values.map { enumSemanticKey(type, it) }.distinct().size != values.size) failSchema()
    }
}

private fun validateValue(schema: JsonObject, value: JsonElement) {
    val type = schema.string("type")
    validatePrimitiveType(type, value, ::invalidArguments)
    val primitive = value as JsonPrimitive
    if (type == "string") {
        val length = primitive.content.length
        if (schema.optionalInt("minLength")?.let { length < it } == true) invalidArguments()
        if (schema.optionalInt("maxLength")?.let { length > it } == true) invalidArguments()
    } else if (type == "number" || type == "integer") {
        val number = primitive.doubleOrNull ?: invalidArguments()
        if (schema.optionalDouble("minimum")?.let { number < it } == true) invalidArguments()
        if (schema.optionalDouble("maximum")?.let { number > it } == true) invalidArguments()
    }
    (schema["enum"] as? JsonArray)?.let { values ->
        if (values.none { enumValuesEqual(type, it, value) }) invalidArguments()
    }
}

private inline fun validatePrimitiveType(
    type: String,
    value: JsonElement,
    invalid: () -> Nothing,
) {
    val primitive = value as? JsonPrimitive ?: invalid()
    when (type) {
        "string" -> if (!primitive.isString) invalid()
        "boolean" -> if (primitive.isString || primitive.booleanOrNull == null) invalid()
        "integer" -> if (primitive.isString || primitive.safeIntegerOrNull() == null) invalid()
        "number" -> if (primitive.isString || primitive.finiteDoubleOrNull() == null) invalid()
        else -> failSchema()
    }
}

private fun enumSemanticKey(type: String, value: JsonElement): Any {
    val primitive = value as JsonPrimitive
    return when (type) {
        "number" -> primitive.finiteDoubleOrNull()?.let { if (it == 0.0) 0.0 else it }
            ?: failSchema()
        "integer" -> primitive.safeIntegerOrNull() ?: failSchema()
        else -> value
    }
}

private fun enumValuesEqual(type: String, expected: JsonElement, actual: JsonElement): Boolean =
    when (type) {
        "number" -> (expected as? JsonPrimitive)?.finiteDoubleOrNull() ==
            (actual as? JsonPrimitive)?.finiteDoubleOrNull()
        "integer" -> (expected as? JsonPrimitive)?.safeIntegerOrNull() ==
            (actual as? JsonPrimitive)?.safeIntegerOrNull()
        else -> expected == actual
    }

private fun JsonPrimitive.finiteDoubleOrNull(): Double? =
    doubleOrNull?.takeIf(Double::isFinite)

private fun JsonPrimitive.safeIntegerOrNull(): Long? {
    val value = finiteDoubleOrNull() ?: return null
    if (value % 1.0 != 0.0 || value < -MAX_JS_SAFE_INTEGER || value > MAX_JS_SAFE_INTEGER) return null
    return value.toLong()
}

private fun JsonObject.string(key: String): String {
    val value = this[key] as? JsonPrimitive ?: failSchema()
    if (!value.isString) failSchema()
    return value.content
}

private fun JsonObject.boolean(key: String): Boolean {
    val value = this[key] as? JsonPrimitive ?: failSchema()
    if (value.isString) failSchema()
    return value.booleanOrNull ?: failSchema()
}

private fun JsonObject.optionalInt(key: String): Int? = this[key]?.let { value ->
    val primitive = value as? JsonPrimitive ?: failSchema()
    if (primitive.isString) failSchema()
    primitive.content.toIntOrNull() ?: failSchema()
}

private fun JsonObject.optionalDouble(key: String): Double? = this[key]?.let { value ->
    val primitive = value as? JsonPrimitive ?: failSchema()
    if (primitive.isString) failSchema()
    primitive.doubleOrNull?.takeIf(Double::isFinite) ?: failSchema()
}

private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
    if (value.keys != expected) failSchema()
}

private fun failSchema(): Nothing =
    throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID")

private fun invalidArguments(): Nothing =
    throw ExtensionPackageException("EXTENSION_PACKAGE_TOOL_ARGUMENTS_INVALID")

private val PROPERTY_NAME = Regex("^[a-z][a-zA-Z0-9_]{0,63}$")
private const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991.0
