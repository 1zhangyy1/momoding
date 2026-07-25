package app.momoding.core.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

class AppearanceStore(
    private val dataStore: DataStore<Preferences>,
) {
    val mode: Flow<AppearanceMode> = dataStore.data
        .map { preferences ->
            preferences[MODE]?.let { saved ->
                AppearanceMode.entries.firstOrNull { it.name == saved }
            } ?: AppearanceMode.SYSTEM
        }
        .catch { emit(AppearanceMode.SYSTEM) }

    suspend fun set(mode: AppearanceMode) {
        dataStore.edit { it[MODE] = mode.name }
    }

    companion object {
        private val MODE = stringPreferencesKey("appearance_mode")

        fun create(context: Context, scope: CoroutineScope): AppearanceStore = AppearanceStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                context.applicationContext.preferencesDataStoreFile("appearance")
            },
        )
    }
}
