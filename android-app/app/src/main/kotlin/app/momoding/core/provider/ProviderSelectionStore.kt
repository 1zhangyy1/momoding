package app.momoding.core.provider

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ProviderSelection(
    val accountId: String,
    val providerKind: ProviderKind,
    val chatModelId: String,
    val imageModelId: String?,
    val webSearchEnabled: Boolean,
    val imageGenerationEnabled: Boolean,
) {
    companion object {
        fun defaults(profile: ProviderProfile): ProviderSelection {
            ProviderProfilePolicy.validate(profile)
            return ProviderSelection(
                accountId = profile.id,
                providerKind = profile.kind,
                chatModelId = profile.modelId,
                imageModelId = null,
                webSearchEnabled = true,
                imageGenerationEnabled = false,
            )
        }
    }
}

object ProviderSelectionPolicy {
    fun validate(selection: ProviderSelection) {
        require(CANONICAL_UUID.matches(selection.accountId)) {
            "Provider selection account id is invalid"
        }
        require(selection.providerKind == ProviderKind.OPENROUTER) {
            "Provider selection kind is unsupported"
        }
        require(MODEL_ID.matches(selection.chatModelId)) {
            "Provider selection chat model is invalid"
        }
        selection.imageModelId?.let { imageModelId ->
            require(MODEL_ID.matches(imageModelId)) {
                "Provider selection image model is invalid"
            }
        }
        require(!selection.imageGenerationEnabled || selection.imageModelId != null) {
            "Image generation needs an image model"
        }
    }

    fun reconcile(
        saved: ProviderSelection?,
        profile: ProviderProfile,
    ): ProviderSelection {
        ProviderProfilePolicy.validate(profile)
        val selection = if (
            saved != null &&
            saved.accountId == profile.id &&
            saved.providerKind == profile.kind
        ) {
            saved.copy(chatModelId = profile.modelId)
        } else {
            ProviderSelection.defaults(profile)
        }
        validate(selection)
        return selection
    }

    private val CANONICAL_UUID = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
    private val MODEL_ID = Regex(
        "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    )
}

class ProviderSelectionStore internal constructor(
    private val readPayload: () -> String?,
    private val writePayload: (String) -> Unit,
    private val deletePayload: () -> Unit,
) {
    @Synchronized
    fun reconcile(profile: ProviderProfile): ProviderSelection {
        val saved = readPayload()?.let { payload ->
            runCatching { ProviderSelectionCodec.decode(payload) }.getOrNull()
        }
        return ProviderSelectionPolicy.reconcile(saved, profile).also(::store)
    }

    @Synchronized
    fun load(profile: ProviderProfile): ProviderSelection =
        ProviderSelectionPolicy.reconcile(
            saved = readPayload()?.let { payload ->
                runCatching { ProviderSelectionCodec.decode(payload) }.getOrNull()
            },
            profile = profile,
        )

    @Synchronized
    fun store(selection: ProviderSelection) {
        ProviderSelectionPolicy.validate(selection)
        writePayload(ProviderSelectionCodec.encode(selection))
    }

    @Synchronized
    fun delete() {
        deletePayload()
    }

    companion object {
        private const val PREFERENCES_NAME = "provider-selection-v1"
        private const val PAYLOAD_KEY = "selection"

        fun create(context: Context): ProviderSelectionStore {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            return ProviderSelectionStore(
                readPayload = { preferences.getString(PAYLOAD_KEY, null) },
                writePayload = { payload ->
                    preferences.edit(commit = true) { putString(PAYLOAD_KEY, payload) }
                },
                deletePayload = { preferences.edit(commit = true) { remove(PAYLOAD_KEY) } },
            )
        }
    }
}

internal object ProviderSelectionCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encode(selection: ProviderSelection): String {
        ProviderSelectionPolicy.validate(selection)
        return buildJsonObject {
            put("version", VERSION)
            put("account_id", selection.accountId)
            put("provider_kind", selection.providerKind.wireValue)
            put("chat_model_id", selection.chatModelId)
            selection.imageModelId?.let { put("image_model_id", it) }
            put("web_search_enabled", selection.webSearchEnabled)
            put("image_generation_enabled", selection.imageGenerationEnabled)
        }.toString()
    }

    fun decode(payload: String): ProviderSelection {
        require(payload.toByteArray().size <= MAX_PAYLOAD_BYTES) {
            "Provider selection payload is too large"
        }
        val root = json.parseToJsonElement(payload).jsonObject
        require(root.keys.all(ALLOWED_FIELDS::contains)) {
            "Provider selection payload has unknown fields"
        }
        require(root.getValue("version").jsonPrimitive.int == VERSION) {
            "Provider selection version is unsupported"
        }
        return ProviderSelection(
            accountId = root.getValue("account_id").jsonPrimitive.content,
            providerKind = ProviderKind.fromWireValue(
                root.getValue("provider_kind").jsonPrimitive.content,
            ),
            chatModelId = root.getValue("chat_model_id").jsonPrimitive.content,
            imageModelId = root["image_model_id"]?.jsonPrimitive?.contentOrNull,
            webSearchEnabled = root.getValue("web_search_enabled").jsonPrimitive.boolean,
            imageGenerationEnabled = root.getValue("image_generation_enabled")
                .jsonPrimitive.boolean,
        ).also(ProviderSelectionPolicy::validate)
    }

    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 8 * 1024
    private val ALLOWED_FIELDS = setOf(
        "version",
        "account_id",
        "provider_kind",
        "chat_model_id",
        "image_model_id",
        "web_search_enabled",
        "image_generation_enabled",
    )
}
