package com.example.videomaker.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.CapabilitiesResponse
import com.example.videomaker.data.SettingsRepository
import com.example.videomaker.data.TemplateInfo
import com.example.videomaker.data.VoiceInfo
import com.example.videomaker.util.FileUtils
import com.example.videomaker.util.SelectedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CreateVideoUiState(
    val templates: List<TemplateInfo> = emptyList(),
    val voices: List<VoiceInfo> = emptyList(),
    val selectedTemplate: String = "product_basic",
    val selectedVoice: String = "zh-CN-XiaoxiaoNeural",
    val prompt: String = "",
    val title: String = "",
    val scriptLines: List<String> = listOf(""),
    val resolution: String = "1080x1920",
    val autoBgm: Boolean = true,
    val bgmEnabled: Boolean = false,
    val bgmFilename: String = "test_bgm.wav",
    val selectedMedia: List<SelectedMedia> = emptyList(),
    val capabilities: CapabilitiesResponse = CapabilitiesResponse(),
    val isLoadingOptions: Boolean = false,
    val error: String? = null
)

class CreateVideoViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = SettingsRepository(appContext)
    private val _uiState = MutableStateFlow(CreateVideoUiState())
    val uiState: StateFlow<CreateVideoUiState> = _uiState.asStateFlow()

    fun loadOptions() {
        if (_uiState.value.isLoadingOptions) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOptions = true, error = null) }
            runCatching {
                val settings = repository.settingsFlow.first()
                val api = ApiClient.create(settings.baseUrl, settings.apiToken)
                Triple(api.templates().templates, api.voices(), api.capabilities())
            }.onSuccess { (templates, voices, capabilities) ->
                _uiState.update { state ->
                    state.copy(
                        templates = templates,
                        voices = voices,
                        capabilities = capabilities,
                        selectedTemplate = templates.firstOrNull()?.name ?: state.selectedTemplate,
                        selectedVoice = voices.firstOrNull()?.id ?: state.selectedVoice,
                        resolution = capabilities.supportedResolutions.firstOrNull { it == state.resolution }
                            ?: capabilities.defaultResolution,
                        isLoadingOptions = false,
                        error = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoadingOptions = false, error = ApiClient.toUserMessage(error))
                }
            }
        }
    }

    fun updateTitle(value: String) {
        _uiState.update { it.copy(title = value) }
    }

    fun updatePrompt(value: String) {
        _uiState.update { it.copy(prompt = value) }
    }

    fun updateTemplate(value: String) {
        _uiState.update { it.copy(selectedTemplate = value) }
    }

    fun updateVoice(value: String) {
        _uiState.update { it.copy(selectedVoice = value) }
    }

    fun updateResolution(value: String) {
        _uiState.update { it.copy(resolution = value) }
    }

    fun updateBgmEnabled(value: Boolean) {
        _uiState.update { it.copy(bgmEnabled = value) }
    }

    fun updateAutoBgm(value: Boolean) {
        _uiState.update { it.copy(autoBgm = value) }
    }

    fun updateBgmFilename(value: String) {
        _uiState.update { it.copy(bgmFilename = value) }
    }

    fun updateScriptLine(index: Int, value: String) {
        _uiState.update { state ->
            state.copy(scriptLines = state.scriptLines.mapIndexed { i, line -> if (i == index) value else line })
        }
    }

    fun addScriptLine() {
        _uiState.update { state ->
            if (state.scriptLines.size >= 30) state else state.copy(scriptLines = state.scriptLines + "")
        }
    }

    fun removeScriptLine(index: Int) {
        _uiState.update { state ->
            val next = state.scriptLines.filterIndexed { i, _ -> i != index }.ifEmpty { listOf("") }
            state.copy(scriptLines = next)
        }
    }

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val current = _uiState.value.selectedMedia
            val capabilities = _uiState.value.capabilities
            val maxItems = capabilities.maxJobAssets.coerceAtLeast(1)
            if (current.size >= maxItems) {
                _uiState.update { it.copy(error = "最多只能选择 $maxItems 个素材。") }
                return@launch
            }
            val accepted = mutableListOf<SelectedMedia>()
            var rejectedMessage: String? = null
            for (uri in uris) {
                if (current.size + accepted.size >= maxItems) {
                    rejectedMessage = "最多只能选择 $maxItems 个素材，已忽略多余文件。"
                    break
                }
                val described = runCatching {
                    withContext(Dispatchers.IO) { FileUtils.describe(appContext, uri) }
                }.getOrElse {
                    rejectedMessage = "无法读取部分素材，已忽略。"
                    null
                } ?: continue
                val isSupportedType = described.mimeType in capabilities.supportedImageMimeTypes ||
                    described.mimeType in capabilities.supportedVideoMimeTypes
                if (!isSupportedType) {
                    rejectedMessage = "不支持的素材类型：${described.mimeType}"
                    continue
                }
                if (described.sizeBytes != null && described.sizeBytes > capabilities.maxUploadSizeBytes) {
                    rejectedMessage = "素材超过 ${capabilities.maxUploadSizeMb}MB，已忽略。"
                    continue
                }
                val staged = runCatching {
                    withContext(Dispatchers.IO) { FileUtils.stageForUpload(appContext, described) }
                }.getOrElse {
                    rejectedMessage = "无法暂存部分素材，已忽略。"
                    null
                } ?: continue
                if (staged.sizeBytes != null && staged.sizeBytes > capabilities.maxUploadSizeBytes) {
                    FileUtils.deleteIfStaged(appContext, staged)
                    rejectedMessage = "素材超过 ${capabilities.maxUploadSizeMb}MB，已忽略。"
                    continue
                }
                accepted += staged
            }
            _uiState.update {
                it.copy(
                    selectedMedia = current + accepted,
                    error = rejectedMessage
                )
            }
        }
    }

    fun removeMedia(index: Int) {
        _uiState.value.selectedMedia.getOrNull(index)?.let { media ->
            FileUtils.deleteIfStaged(appContext, media)
        }
        _uiState.update { state ->
            state.copy(selectedMedia = state.selectedMedia.filterIndexed { i, _ -> i != index })
        }
    }

    fun buildGenerationInput(): GenerationInput? {
        val state = _uiState.value
        val prompt = state.prompt.trim()
        return if (prompt.length < 5 || state.selectedMedia.isEmpty() || state.selectedMedia.size > state.capabilities.maxJobAssets) {
            null
        } else {
            GenerationInput(
                prompt = prompt,
                media = state.selectedMedia,
                template = state.selectedTemplate,
                voice = state.selectedVoice,
                resolution = state.resolution,
                autoBgm = state.autoBgm
            )
        }
    }
}
