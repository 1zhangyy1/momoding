package app.momoding.core.transport

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object StrictJsonDocument {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun parseObject(bytes: ByteArray, maxBytes: Int): JsonObject {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "JSON body size is invalid" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        DuplicateKeyScanner(text, json).validate()
        return json.parseToJsonElement(text).jsonObject
    }
}

private class DuplicateKeyScanner(
    private val text: String,
    private val json: Json,
) {
    private var index: Int = 0

    fun validate() {
        skipWhitespace()
        readValue()
        skipWhitespace()
        require(index == text.length) { "JSON has trailing data" }
    }

    private fun readValue() {
        require(index < text.length) { "JSON value is truncated" }
        when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readStringToken()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> error("JSON value is invalid")
        }
    }

    private fun readObject() {
        index += 1
        skipWhitespace()
        if (consume('}')) return
        val keys = hashSetOf<String>()
        while (true) {
            require(index < text.length && text[index] == '"') { "JSON object key is invalid" }
            val token = readStringToken()
            val key = json.parseToJsonElement(token).toStringValue()
            require(keys.add(key)) { "JSON object contains duplicate keys" }
            skipWhitespace()
            require(consume(':')) { "JSON object separator is missing" }
            skipWhitespace()
            readValue()
            skipWhitespace()
            if (consume('}')) return
            require(consume(',')) { "JSON object delimiter is missing" }
            skipWhitespace()
        }
    }

    private fun readArray() {
        index += 1
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            readValue()
            skipWhitespace()
            if (consume(']')) return
            require(consume(',')) { "JSON array delimiter is missing" }
            skipWhitespace()
        }
    }

    private fun readStringToken(): String {
        val start = index
        require(consume('"')) { "JSON string is invalid" }
        while (index < text.length) {
            when (val char = text[index++]) {
                '"' -> return text.substring(start, index)
                '\\' -> {
                    require(index < text.length) { "JSON escape is truncated" }
                    val escape = text[index++]
                    if (escape == 'u') {
                        require(index + 4 <= text.length) { "JSON unicode escape is truncated" }
                        repeat(4) {
                            require(text[index++] in "0123456789abcdefABCDEF") {
                                "JSON unicode escape is invalid"
                            }
                        }
                    } else {
                        require(escape in "\"\\/bfnrt") { "JSON escape is invalid" }
                    }
                }
                else -> require(char.code >= 0x20) { "JSON string contains a control character" }
            }
        }
        error("JSON string is truncated")
    }

    private fun readNumber() {
        val start = index
        consume('-')
        require(index < text.length) { "JSON number is truncated" }
        if (consume('0')) {
            require(index >= text.length || text[index] !in '0'..'9') { "JSON number has a leading zero" }
        } else {
            require(text[index] in '1'..'9') { "JSON number is invalid" }
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        if (consume('.')) {
            require(index < text.length && text[index] in '0'..'9') { "JSON fraction is invalid" }
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        if (index < text.length && text[index] in "eE") {
            index += 1
            if (index < text.length && text[index] in "+-") index += 1
            require(index < text.length && text[index] in '0'..'9') { "JSON exponent is invalid" }
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        require(index > start) { "JSON number is invalid" }
    }

    private fun readLiteral(value: String) {
        require(text.regionMatches(index, value, 0, value.length)) { "JSON literal is invalid" }
        index += value.length
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \t\r\n") index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index >= text.length || text[index] != expected) return false
        index += 1
        return true
    }

    private fun kotlinx.serialization.json.JsonElement.toStringValue(): String =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: error("JSON key is not a string")
}
