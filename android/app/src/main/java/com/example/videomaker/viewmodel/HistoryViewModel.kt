package com.example.videomaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.GenerationHistoryItem
import com.example.videomaker.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryRecordUi(
    val jobId: String,
    val prompt: String,
    val videoUrl: String,
    val videoFullUrl: String,
    val createdAt: String,
    val updatedAt: String
)

data class HistoryUiState(
    val isLoading: Boolean = false,
    val records: List<HistoryRecordUi> = emptyList(),
    val error: String? = null,
    val hasLoaded: Boolean = false
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    fun loadHistory(force: Boolean = false) {
        val current = _uiState.value
        if (current.isLoading || (!force && current.hasLoaded)) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val settings = repository.settingsFlow.first()
                val api = ApiClient.create(settings.baseUrl, settings.apiToken)
                api.history(limit = 30).items.map { item ->
                    item.toUi(baseUrl = settings.baseUrl)
                }
            }.onSuccess { records ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        records = records,
                        error = null,
                        hasLoaded = true
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = ApiClient.toUserMessage(error),
                        hasLoaded = true
                    )
                }
            }
        }
    }

    fun findRecord(jobId: String): HistoryRecordUi? {
        return _uiState.value.records.firstOrNull { it.jobId == jobId }
    }

    private fun GenerationHistoryItem.toUi(baseUrl: String): HistoryRecordUi {
        return HistoryRecordUi(
            jobId = jobId,
            prompt = prompt,
            videoUrl = videoUrl,
            videoFullUrl = ApiClient.buildAbsoluteUrl(baseUrl, videoUrl),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
