package com.example.videomaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.HealthResponse
import com.example.videomaker.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val apiToken: String = "",
    val themeMode: String = "system",
    val isTesting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val health: HealthResponse? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.migrateLegacyTokenIfNeeded()
            repository.settingsFlow.collectLatest { settings ->
                _uiState.update {
                    it.copy(baseUrl = settings.baseUrl, apiToken = settings.apiToken, themeMode = settings.themeMode)
                }
            }
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value, message = null, error = null) }
    }

    fun updateApiToken(value: String) {
        _uiState.update { it.copy(apiToken = value, message = null, error = null) }
    }

    fun updateThemeMode(mode: String) {
        if (mode !in setOf("system", "light", "dark")) return
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            repository.saveThemeMode(mode)
        }
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            repository.save(state.baseUrl, state.apiToken)
            _uiState.update { it.copy(message = "设置已保存", error = null) }
        }
    }

    fun testConnection() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, message = null, error = null, health = null) }
            runCatching {
                ApiClient.create(state.baseUrl, state.apiToken).health()
            }.onSuccess { health ->
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        message = "连接成功，版本 ${health.version}",
                        health = health,
                        error = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        error = ApiClient.toUserMessage(error),
                        message = null,
                        health = null
                    )
                }
            }
        }
    }
}
