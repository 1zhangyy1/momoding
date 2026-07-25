package app.momoding.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.momoding.wire.ReliabilityProtocol
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

enum class HostBindingStatus {
    PAIRING,
    ACTIVE,
    UNPAIRING,
    CREDENTIAL_LOST,
}

data class HostClientProfile(
    val hostAlias: String,
    val provider: String,
    val model: String,
    val thinking: String,
    val mutable: Boolean,
    val configRevision: Long,
)

data class HostBindingSnapshot(
    val status: HostBindingStatus,
    val endpoint: String,
    val spkiPin: String,
    val clientInstanceId: String,
    val deviceId: String,
    val deviceName: String,
    val hostId: String? = null,
    val credentialId: String? = null,
    val profile: HostClientProfile? = null,
    val updatedAtMillis: Long,
) {
    fun headerOrNull(): VaultHeader? {
        val host = hostId ?: return null
        val credential = credentialId ?: return null
        return VaultHeader(endpoint, spkiPin, host, credential)
    }

    fun validate(): HostBindingSnapshot = apply {
        require(updatedAtMillis >= 0) { "Binding timestamp is invalid" }
        val provisionalHeader = VaultHeader(
            endpoint = endpoint,
            spkiPin = spkiPin,
            hostId = hostId ?: PLACEHOLDER_UUID,
            credentialId = credentialId ?: PLACEHOLDER_UUID,
        )
        VaultEnvelopeCodec.validateHeader(provisionalHeader)
        require(CANONICAL_UUID.matches(clientInstanceId)) { "clientInstanceId is invalid" }
        require(CANONICAL_UUID.matches(deviceId)) { "deviceId is invalid" }
        DeviceNamePolicy.requireCanonical(deviceName)
        if (status == HostBindingStatus.PAIRING) {
            require(hostId == null && credentialId == null && profile == null) {
                "PAIRING state must not contain an active binding"
            }
        } else {
            require(hostId != null && credentialId != null) { "$status state requires an active binding" }
            headerOrNull()?.let(VaultEnvelopeCodec::validateHeader)
        }
        profile?.validate()
    }

    private fun HostClientProfile.validate() {
        require(hostAlias.isNotBlank() && hostAlias.length <= 128) { "Host alias is invalid" }
        require(provider.isNotBlank() && provider.length <= 128) { "Provider label is invalid" }
        require(model.isNotBlank() && model.length <= 256) { "Model label is invalid" }
        require(thinking == "default" && !mutable) { "Client profile is not immutable" }
        require(configRevision in 1..ReliabilityProtocol.MAX_SAFE_INTEGER) { "Config revision is invalid" }
    }

    companion object {
        private const val PLACEHOLDER_UUID = "00000000-0000-4000-8000-000000000000"
        private val CANONICAL_UUID = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
    }
}

interface HostStateStore {
    suspend fun read(): HostBindingSnapshot?
    suspend fun write(snapshot: HostBindingSnapshot)
    suspend fun clear()
}

class PreferencesHostStateStore private constructor(
    private val dataStore: DataStore<Preferences>,
) : HostStateStore {
    override suspend fun read(): HostBindingSnapshot? = decode(dataStore.data.first())

    override suspend fun write(snapshot: HostBindingSnapshot) {
        snapshot.validate()
        dataStore.updateData { encode(snapshot) }
    }

    override suspend fun clear() {
        dataStore.updateData { emptyPreferences() }
    }

    private fun encode(snapshot: HostBindingSnapshot): Preferences =
        emptyPreferences().toMutablePreferences().apply {
            this[SCHEMA_VERSION] = 1
            this[STATUS] = snapshot.status.name
            this[ENDPOINT] = snapshot.endpoint
            this[SPKI_PIN] = snapshot.spkiPin
            this[CLIENT_INSTANCE_ID] = snapshot.clientInstanceId
            this[DEVICE_ID] = snapshot.deviceId
            this[DEVICE_NAME] = snapshot.deviceName
            this[UPDATED_AT_MILLIS] = snapshot.updatedAtMillis
            snapshot.hostId?.let { this[HOST_ID] = it }
            snapshot.credentialId?.let { this[CREDENTIAL_ID] = it }
            snapshot.profile?.let { profile ->
                this[HOST_ALIAS] = profile.hostAlias
                this[PROVIDER] = profile.provider
                this[MODEL] = profile.model
                this[THINKING] = profile.thinking
                this[MUTABLE] = if (profile.mutable) 1 else 0
                this[CONFIG_REVISION] = profile.configRevision
            }
        }

    private fun decode(preferences: Preferences): HostBindingSnapshot? {
        if (preferences.asMap().isEmpty()) return null
        require(preferences[SCHEMA_VERSION] == 1) { "Host state schema is unsupported" }
        val knownNames = KNOWN_KEYS.mapTo(hashSetOf()) { it.name }
        require(preferences.asMap().keys.all { it.name in knownNames }) { "Host state has unknown fields" }
        val profileValues = listOf(
            preferences[HOST_ALIAS],
            preferences[PROVIDER],
            preferences[MODEL],
            preferences[THINKING],
            preferences[MUTABLE],
            preferences[CONFIG_REVISION],
        )
        require(profileValues.all { it == null } || profileValues.all { it != null }) {
            "Host profile is incomplete"
        }
        return HostBindingSnapshot(
            status = HostBindingStatus.valueOf(requireNotNull(preferences[STATUS])),
            endpoint = requireNotNull(preferences[ENDPOINT]),
            spkiPin = requireNotNull(preferences[SPKI_PIN]),
            clientInstanceId = requireNotNull(preferences[CLIENT_INSTANCE_ID]),
            deviceId = requireNotNull(preferences[DEVICE_ID]),
            deviceName = requireNotNull(preferences[DEVICE_NAME]),
            hostId = preferences[HOST_ID],
            credentialId = preferences[CREDENTIAL_ID],
            profile = if (profileValues[0] == null) {
                null
            } else {
                require(preferences[MUTABLE] in setOf(0, 1)) { "Host profile mutable flag is invalid" }
                HostClientProfile(
                    hostAlias = requireNotNull(preferences[HOST_ALIAS]),
                    provider = requireNotNull(preferences[PROVIDER]),
                    model = requireNotNull(preferences[MODEL]),
                    thinking = requireNotNull(preferences[THINKING]),
                    mutable = requireNotNull(preferences[MUTABLE]) == 1,
                    configRevision = requireNotNull(preferences[CONFIG_REVISION]),
                )
            },
            updatedAtMillis = requireNotNull(preferences[UPDATED_AT_MILLIS]),
        ).validate()
    }

    companion object {
        private val SCHEMA_VERSION = intPreferencesKey("schema_version")
        private val STATUS = stringPreferencesKey("status")
        private val ENDPOINT = stringPreferencesKey("endpoint")
        private val SPKI_PIN = stringPreferencesKey("spki_pin")
        private val CLIENT_INSTANCE_ID = stringPreferencesKey("client_instance_id")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val HOST_ID = stringPreferencesKey("host_id")
        private val CREDENTIAL_ID = stringPreferencesKey("credential_id")
        private val HOST_ALIAS = stringPreferencesKey("host_alias")
        private val PROVIDER = stringPreferencesKey("provider_label")
        private val MODEL = stringPreferencesKey("model_label")
        private val THINKING = stringPreferencesKey("thinking")
        private val MUTABLE = intPreferencesKey("profile_mutable")
        private val CONFIG_REVISION = longPreferencesKey("config_revision")
        private val UPDATED_AT_MILLIS = longPreferencesKey("updated_at_millis")
        private val KNOWN_KEYS = setOf(
            SCHEMA_VERSION,
            STATUS,
            ENDPOINT,
            SPKI_PIN,
            CLIENT_INSTANCE_ID,
            DEVICE_ID,
            DEVICE_NAME,
            HOST_ID,
            CREDENTIAL_ID,
            HOST_ALIAS,
            PROVIDER,
            MODEL,
            THINKING,
            MUTABLE,
            CONFIG_REVISION,
            UPDATED_AT_MILLIS,
        )

        @Volatile
        private var singleton: PreferencesHostStateStore? = null

        fun get(context: Context, scope: CoroutineScope): PreferencesHostStateStore =
            singleton ?: synchronized(this) {
                singleton ?: PreferencesHostStateStore(
                    PreferenceDataStoreFactory.create(scope = scope) {
                        context.applicationContext.preferencesDataStoreFile("host-binding-state")
                    },
                ).also { singleton = it }
            }

        fun newIdentity(): String = UUID.randomUUID().toString()
    }
}
