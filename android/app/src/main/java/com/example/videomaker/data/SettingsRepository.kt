package com.example.videomaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "video_maker_settings")

class SettingsRepository(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val apiTokenKey = stringPreferencesKey("api_token")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val secureRevisionKey = intPreferencesKey("secure_revision")
    private val secureTokenStore = SecureTokenStore(context)

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        val secureToken = secureTokenStore.readApiToken()
        AppSettings(
            baseUrl = preferences[baseUrlKey].orEmpty(),
            apiToken = secureToken.ifBlank { preferences[apiTokenKey].orEmpty() },
            themeMode = preferences[themeModeKey] ?: "system"
        )
    }

    suspend fun migrateLegacyTokenIfNeeded() {
        context.settingsDataStore.edit { preferences ->
            val legacyToken = preferences[apiTokenKey].orEmpty().trim()
            if (legacyToken.isNotBlank() && secureTokenStore.readApiToken().isBlank()) {
                secureTokenStore.writeApiToken(legacyToken)
            }
            preferences.remove(apiTokenKey)
            preferences[secureRevisionKey] = (preferences[secureRevisionKey] ?: 0) + 1
        }
    }

    suspend fun save(baseUrl: String, apiToken: String) {
        secureTokenStore.writeApiToken(apiToken)
        context.settingsDataStore.edit { preferences ->
            preferences[baseUrlKey] = baseUrl.trim()
            preferences.remove(apiTokenKey)
            preferences[secureRevisionKey] = (preferences[secureRevisionKey] ?: 0) + 1
        }
    }

    suspend fun saveThemeMode(mode: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[themeModeKey] = mode
        }
    }
}
