package app.momoding.core.connector

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class ConnectorExposureMode {
    @SerialName("direct")
    DIRECT,

    @SerialName("proxy")
    PROXY,
}

@Serializable
data class ConnectorToolDefinition(
    val remoteName: String,
    val exposedName: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val risk: String,
) {
    companion object {
        const val READ_RISK = "read"
    }
}

@Serializable
data class ConnectorToolSnapshot(
    val version: Int,
    val connectorId: String,
    val connectionId: String,
    val sourceLabel: String,
    val mode: ConnectorExposureMode,
    val schemaDigest: String,
    val tools: List<ConnectorToolDefinition>,
)

internal object ConnectorToolSnapshotPolicy {
    const val MAX_TOOLS = 16
    const val MAX_SNAPSHOT_CHARS = 64 * 1024
    const val MAX_SCHEMA_CHARS = 16 * 1024
    const val MAX_SCHEMA_DEPTH = 12
    const val MAX_ARGUMENT_CHARS = 16 * 1024
    const val MAX_RESULT_CHARS = 24 * 1024
    const val MAX_RESULT_BLOCKS = 12

    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val remoteToolPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val exposedToolPattern = Regex("^[a-z][a-z0-9_]{0,79}$")
    private val digestPattern = Regex("^[a-f0-9]{64}$")

    fun requireValid(snapshot: ConnectorToolSnapshot): ConnectorToolSnapshot {
        require(snapshot.version == 1) { "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID" }
        require(idPattern.matches(snapshot.connectorId)) { "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID" }
        require(idPattern.matches(snapshot.connectionId)) { "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID" }
        require(snapshot.sourceLabel.length in 1..80 && '\u0000' !in snapshot.sourceLabel) {
            "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID"
        }
        require(snapshot.tools.size in 1..MAX_TOOLS) { "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID" }
        require(snapshot.schemaDigest.matches(digestPattern)) {
            "PI_MOBILE_CONNECTOR_SNAPSHOT_INVALID"
        }
        snapshot.tools.forEach { tool ->
            require(remoteToolPattern.matches(tool.remoteName)) {
                "PI_MOBILE_CONNECTOR_TOOL_INVALID"
            }
            require(exposedToolPattern.matches(tool.exposedName)) {
                "PI_MOBILE_CONNECTOR_TOOL_INVALID"
            }
            require(tool.title.length in 1..80 && '\u0000' !in tool.title) {
                "PI_MOBILE_CONNECTOR_TOOL_INVALID"
            }
            require(tool.description.length in 1..512 && '\u0000' !in tool.description) {
                "PI_MOBILE_CONNECTOR_TOOL_INVALID"
            }
            require(tool.risk == ConnectorToolDefinition.READ_RISK) {
                "PI_MOBILE_CONNECTOR_TOOL_RISK_UNSUPPORTED"
            }
            require(tool.inputSchema["type"] == JsonPrimitive("object")) {
                "PI_MOBILE_CONNECTOR_SCHEMA_INVALID"
            }
            require(tool.inputSchema.toString().length <= MAX_SCHEMA_CHARS) {
                "PI_MOBILE_CONNECTOR_SCHEMA_LIMIT"
            }
            require(tool.inputSchema.depth() <= MAX_SCHEMA_DEPTH) {
                "PI_MOBILE_CONNECTOR_SCHEMA_LIMIT"
            }
            ConnectorJsonSchemaSubset.requireSupported(tool.inputSchema)
        }
        require(snapshot.tools.map { it.remoteName }.distinct().size == snapshot.tools.size) {
            "PI_MOBILE_CONNECTOR_REMOTE_TOOL_DUPLICATE"
        }
        require(snapshot.tools.map { it.exposedName }.distinct().size == snapshot.tools.size) {
            "PI_MOBILE_CONNECTOR_EXPOSED_TOOL_DUPLICATE"
        }
        require(snapshot.mode != ConnectorExposureMode.DIRECT ||
            snapshot.tools.none { it.exposedName == "connector" }) {
            "PI_MOBILE_CONNECTOR_TOOL_NAME_RESERVED"
        }
        require(snapshot.canonicalDigestPayload().length <= MAX_SNAPSHOT_CHARS) {
            "PI_MOBILE_CONNECTOR_SNAPSHOT_LIMIT"
        }
        require(snapshot.schemaDigest == digest(snapshot)) {
            "PI_MOBILE_CONNECTOR_SNAPSHOT_DIGEST_MISMATCH"
        }
        return snapshot
    }

    fun withComputedDigest(snapshot: ConnectorToolSnapshot): ConnectorToolSnapshot =
        snapshot.copy(schemaDigest = digest(snapshot))

    fun digest(snapshot: ConnectorToolSnapshot): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(snapshot.canonicalDigestPayload().encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun ConnectorToolSnapshot.canonicalDigestPayload(): String = canonicalJson(
        buildJsonObject {
            put("version", version)
            put("connectorId", connectorId)
            put("connectionId", connectionId)
            put("sourceLabel", sourceLabel)
            put("mode", mode.name.lowercase())
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("remoteName", tool.remoteName)
                        put("exposedName", tool.exposedName)
                        put("title", tool.title)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        put("risk", tool.risk)
                    })
                }
            })
        },
    )

    fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key).joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (key, item) -> "${JsonPrimitive(key)}:${canonicalJson(item)}" }
        is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") {
            canonicalJson(it)
        }
        else -> value.toString()
    }

    private fun JsonElement.depth(): Int = when (this) {
        is JsonObject -> 1 + (values.maxOfOrNull { it.depth() } ?: 0)
        is JsonArray -> 1 + (maxOfOrNull { it.depth() } ?: 0)
        else -> 1
    }
}
