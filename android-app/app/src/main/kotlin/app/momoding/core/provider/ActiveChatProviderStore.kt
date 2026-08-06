package app.momoding.core.provider

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class ChatProviderKind(val wireValue: String) {
    OPENROUTER("openrouter"),
    CODEX("codex");

    companion object {
        fun fromWireValue(value: String): ChatProviderKind =
            entries.singleOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Chat Provider kind is unsupported")
    }
}

data class ActiveChatProviderSelection(
    val kind: ChatProviderKind,
    val modelId: String,
)

object ActiveChatProviderPolicy {
    fun validate(selection: ActiveChatProviderSelection) {
        val pattern = when (selection.kind) {
            ChatProviderKind.OPENROUTER -> OPENROUTER_MODEL_ID
            ChatProviderKind.CODEX -> CODEX_MODEL_ID
        }
        require(pattern.matches(selection.modelId)) { "Chat Provider model ID is invalid" }
    }

    private val OPENROUTER_MODEL_ID = Regex(
        "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    )
    private val CODEX_MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
}

class ActiveChatProviderStore internal constructor(
    private val readPayload: () -> String?,
    private val writePayload: (String) -> Unit,
) {
    @Synchronized
    fun load(defaultOpenRouterModelId: String): ActiveChatProviderSelection {
        val fallback = ActiveChatProviderSelection(
            kind = ChatProviderKind.OPENROUTER,
            modelId = defaultOpenRouterModelId,
        ).also(ActiveChatProviderPolicy::validate)
        return readPayload()?.let { payload ->
            runCatching { ActiveChatProviderCodec.decode(payload) }.getOrNull()
        } ?: fallback
    }

    @Synchronized
    fun store(selection: ActiveChatProviderSelection) {
        ActiveChatProviderPolicy.validate(selection)
        writePayload(ActiveChatProviderCodec.encode(selection))
    }

    companion object {
        private const val PREFERENCES_NAME = "active-chat-provider-v1"
        private const val PAYLOAD_KEY = "selection"

        fun create(context: Context): ActiveChatProviderStore {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            return ActiveChatProviderStore(
                readPayload = { preferences.getString(PAYLOAD_KEY, null) },
                writePayload = { payload ->
                    preferences.edit(commit = true) { putString(PAYLOAD_KEY, payload) }
                },
            )
        }
    }
}

internal object ActiveChatProviderCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encode(selection: ActiveChatProviderSelection): String {
        ActiveChatProviderPolicy.validate(selection)
        return buildJsonObject {
            put("version", VERSION)
            put("kind", selection.kind.wireValue)
            put("model_id", selection.modelId)
        }.toString()
    }

    fun decode(payload: String): ActiveChatProviderSelection {
        require(payload.toByteArray().size <= MAX_PAYLOAD_BYTES) {
            "Active Chat Provider payload is too large"
        }
        val root = json.parseToJsonElement(payload).jsonObject
        require(root.keys == ALLOWED_FIELDS) {
            "Active Chat Provider payload fields are invalid"
        }
        require(root.getValue("version").jsonPrimitive.int == VERSION) {
            "Active Chat Provider version is unsupported"
        }
        return ActiveChatProviderSelection(
            kind = ChatProviderKind.fromWireValue(root.getValue("kind").jsonPrimitive.content),
            modelId = root.getValue("model_id").jsonPrimitive.content,
        ).also(ActiveChatProviderPolicy::validate)
    }

    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 2 * 1024
    private val ALLOWED_FIELDS = setOf("version", "kind", "model_id")
}
