package com.example.videomaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "video_maker_settings")

class SettingsRepository(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("base_url")
    private val apiTokenKey = stringPreferencesKey("api_token")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            baseUrl = preferences[baseUrlKey].orEmpty(),
            apiToken = preferences[apiTokenKey].orEmpty(),
            themeMode = preferences[themeModeKey] ?: "system"
        )
    }

    suspend fun save(baseUrl: String, apiToken: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[baseUrlKey] = baseUrl.trim()
            preferences[apiTokenKey] = apiToken.trim()
        }
    }

    suspend fun saveThemeMode(mode: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[themeModeKey] = mode
        }
    }
}
