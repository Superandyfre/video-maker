package com.example.videomaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.background.JobPollingScheduler
import com.example.videomaker.data.ActiveJobRepository
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.PersistedActiveJob
import com.example.videomaker.data.SettingsRepository
import com.example.videomaker.data.SmartJobRequest
import com.example.videomaker.data.VoiceConfig
import com.example.videomaker.util.FileUtils
import com.example.videomaker.util.SelectedMedia
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GenerationInput(
    val prompt: String,
    val media: List<SelectedMedia>,
    val template: String,
    val voice: String,
    val resolution: String,
    val autoBgm: Boolean = true
)

data class GenerateUiState(
    val isRunning: Boolean = false,
    val jobId: String? = null,
    val status: String = "idle",
    val phase: String? = null,
    val progress: Int = 0,
    val message: String = "",
    val error: String? = null,
    val videoUrl: String? = null,
    val videoFullUrl: String? = null,
    val resumeRoute: String? = null,
    val prompt: String? = null,
    val template: String? = null,
    val mediaCount: Int = 0
)

class GenerateViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = SettingsRepository(appContext)
    private val activeJobRepository = ActiveJobRepository(appContext)
    private val _uiState = MutableStateFlow(GenerateUiState())
    val uiState: StateFlow<GenerateUiState> = _uiState.asStateFlow()
    private var lastInput: GenerationInput? = null
    private var activeJob: Job? = null

    init {
        viewModelScope.launch {
            restorePersistedJob()
        }
    }

    fun start(input: GenerationInput) {
        lastInput = input
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            activeJobRepository.clear()
            _uiState.value = GenerateUiState(
                isRunning = true,
                status = "uploading",
                phase = "uploading",
                progress = 0,
                message = "准备上传素材",
                prompt = input.prompt,
                template = input.template,
                mediaCount = input.media.size
            )
            runCatching {
                val settings = repository.settingsFlow.first()
                val api = ApiClient.create(settings.baseUrl, settings.apiToken)
                val fileIds = mutableListOf<String>()
                input.media.forEachIndexed { index, media ->
                    _uiState.update {
                        it.copy(
                            progress = ((index.toFloat() / input.media.size) * 20).toInt(),
                            message = "上传素材 ${index + 1}/${input.media.size}"
                        )
                    }
                    persistState(_uiState.value)
                    val part = FileUtils.toMultipart(appContext, media)
                    fileIds += api.upload(part).fileId
                }

                _uiState.update { it.copy(progress = 25, status = "queued", phase = "queued", message = "创建生成任务") }
                val request = SmartJobRequest(
                    template = input.template,
                    prompt = input.prompt,
                    assets = fileIds,
                    voice = VoiceConfig(speaker = input.voice),
                    resolution = input.resolution,
                    autoBgm = input.autoBgm
                )
                val job = api.createSmartJob(request)
                val queuedState = _uiState.updateAndGet {
                    it.copy(jobId = job.jobId, status = job.status, phase = "queued", progress = 30, message = "任务已创建")
                }
                persistState(queuedState)
                JobPollingScheduler.schedule(appContext, job.jobId, delaySeconds = 5)

                observeExistingJob(job.jobId, immediate = false)
            }.onFailure { error ->
                val failedState = _uiState.updateAndGet {
                    it.copy(
                        isRunning = false,
                        status = "failed",
                        phase = "failed",
                        error = ApiClient.toUserMessage(error),
                        message = "生成失败"
                    )
                }
                persistState(failedState)
            }
        }
    }

    fun retry() {
        lastInput?.let { start(it) }
    }

    fun consumeResumeRoute() {
        _uiState.update { it.copy(resumeRoute = null) }
    }

    fun clearPersistedState(resetUi: Boolean = true) {
        val existingJobId = _uiState.value.jobId
        activeJob?.cancel()
        activeJob = null
        viewModelScope.launch {
            activeJobRepository.clear()
        }
        JobPollingScheduler.cancel(appContext, existingJobId)
        if (resetUi) {
            _uiState.value = GenerateUiState()
        }
    }

    private suspend fun restorePersistedJob() {
        val persisted = activeJobRepository.activeJobFlow.first() ?: return
        val initialState = GenerateUiState(
            isRunning = persisted.status == "queued" || persisted.status == "running",
            jobId = persisted.jobId,
            status = persisted.status,
            phase = persisted.phase,
            progress = persisted.progress,
            message = persisted.message.ifBlank { "恢复任务状态" },
            error = persisted.error,
            videoUrl = persisted.videoUrl,
            videoFullUrl = persisted.videoFullUrl,
            resumeRoute = if (persisted.videoFullUrl.isNullOrBlank()) "generate" else "result",
            prompt = persisted.prompt,
            template = persisted.template,
            mediaCount = persisted.mediaCount
        )
        _uiState.value = initialState
        if (persisted.videoFullUrl.isNullOrBlank()) {
            JobPollingScheduler.schedule(appContext, persisted.jobId)
            activeJob?.cancel()
            activeJob = viewModelScope.launch {
                runCatching {
                    observeExistingJob(persisted.jobId, immediate = true)
                }.onFailure { error ->
                    val failedState = _uiState.updateAndGet {
                        it.copy(
                            isRunning = false,
                            status = if (it.status == "idle") "failed" else it.status,
                            phase = "failed",
                            error = ApiClient.toUserMessage(error),
                            message = "恢复任务失败"
                        )
                    }
                    persistState(failedState)
                }
            }
        }
    }

    private suspend fun observeExistingJob(jobId: String, immediate: Boolean) {
        val settings = repository.settingsFlow.first()
        val api = ApiClient.create(settings.baseUrl, settings.apiToken)
        if (immediate) {
            val terminal = fetchJobStatus(api, settings.baseUrl, jobId)
            if (terminal) {
                return
            }
        }
        while (true) {
            delay(2_000)
            val terminal = fetchJobStatus(api, settings.baseUrl, jobId)
            if (terminal) {
                return
            }
        }
    }

    private suspend fun fetchJobStatus(
        api: com.example.videomaker.data.ApiService,
        baseUrl: String,
        jobId: String
    ): Boolean {
        val status = api.jobStatus(jobId)
        val current = _uiState.value
        val nextState = when (status.status) {
            "done" -> {
                val relativeUrl = requireNotNull(status.videoUrl) { "任务完成但没有返回视频地址" }
                val fullUrl = ApiClient.buildAbsoluteUrl(baseUrl, relativeUrl)
                GenerateUiState(
                    isRunning = false,
                    jobId = status.jobId,
                    status = status.status,
                    phase = status.phase ?: "done",
                    progress = 100,
                    message = "视频生成成功",
                    error = status.error,
                    videoUrl = relativeUrl,
                    videoFullUrl = fullUrl,
                    prompt = current.prompt,
                    template = current.template,
                    mediaCount = current.mediaCount
                )
            }
            "failed" -> GenerateUiState(
                isRunning = false,
                jobId = status.jobId,
                status = status.status,
                phase = status.phase ?: "failed",
                progress = status.progress,
                message = "生成失败",
                error = status.error ?: "任务生成失败",
                prompt = current.prompt,
                template = current.template,
                mediaCount = current.mediaCount
            )
            else -> GenerateUiState(
                isRunning = true,
                jobId = status.jobId,
                status = status.status,
                phase = status.phase,
                progress = status.progress,
                message = status.message,
                error = status.error,
                prompt = current.prompt,
                template = current.template,
                mediaCount = current.mediaCount
            )
        }
        _uiState.value = nextState
        persistState(nextState)
        if (status.status == "done" || status.status == "failed") {
            JobPollingScheduler.cancel(appContext, status.jobId)
        } else {
            JobPollingScheduler.schedule(appContext, status.jobId)
        }
        return status.status == "done" || status.status == "failed"
    }

    private suspend fun persistState(state: GenerateUiState) {
        val jobId = state.jobId ?: return
        activeJobRepository.save(
            PersistedActiveJob(
                jobId = jobId,
                status = state.status,
                phase = state.phase,
                progress = state.progress,
                message = state.message,
                error = state.error,
                videoUrl = state.videoUrl,
                videoFullUrl = state.videoFullUrl,
                prompt = state.prompt,
                template = state.template,
                mediaCount = state.mediaCount
            )
        )
    }

    private inline fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
        val next = transform(value)
        value = next
        return next
    }
}
