package com.example.videomaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.background.GenerationUploadScheduler
import com.example.videomaker.background.JobPollingScheduler
import com.example.videomaker.data.ActiveJobRepository
import com.example.videomaker.data.PendingGenerationRepository
import com.example.videomaker.data.PendingGenerationRequest
import com.example.videomaker.data.PendingUploadMedia
import com.example.videomaker.data.PersistedActiveJob
import com.example.videomaker.util.FileUtils
import com.example.videomaker.util.SelectedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    val mediaCount: Int = 0,
    val visualProgressStartedAtMillis: Long = 0L
)

class GenerateViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val activeJobRepository = ActiveJobRepository(appContext)
    private val pendingGenerationRepository = PendingGenerationRepository(appContext)
    private val _uiState = MutableStateFlow(GenerateUiState())
    val uiState: StateFlow<GenerateUiState> = _uiState.asStateFlow()
    private var lastInput: GenerationInput? = null
    private var activeJobFlowInitialized = false

    init {
        viewModelScope.launch {
            activeJobRepository.activeJobFlow.collect { persisted ->
                val shouldResume = !activeJobFlowInitialized && persisted != null
                activeJobFlowInitialized = true
                if (shouldResume && persisted.status.isActiveStatus()) {
                    if (persisted.jobId.startsWith("local-")) {
                        GenerationUploadScheduler.schedule(appContext, persisted.jobId)
                    } else {
                        JobPollingScheduler.schedule(appContext, persisted.jobId, delaySeconds = 2)
                    }
                }
                _uiState.value = persisted?.toUiState(shouldResume) ?: GenerateUiState()
            }
        }
    }

    override fun onCleared() {
        val state = _uiState.value
        val jobId = state.jobId.orEmpty()
        if (state.isRunning && jobId.isNotBlank() && !jobId.startsWith("local-")) {
            JobPollingScheduler.schedule(appContext, jobId, delaySeconds = 10)
        }
        super.onCleared()
    }

    fun start(input: GenerationInput) {
        lastInput = input
        viewModelScope.launch {
            val localJobId = "local-${UUID.randomUUID()}"
            val request = input.toPendingGenerationRequest(localJobId)
            activeJobRepository.clear()
            pendingGenerationRepository.save(request)
            val initialState = GenerateUiState(
                isRunning = true,
                jobId = localJobId,
                status = "uploading",
                phase = "uploading",
                progress = 0,
                message = "准备上传素材",
                prompt = input.prompt,
                template = input.template,
                mediaCount = input.media.size,
                visualProgressStartedAtMillis = System.currentTimeMillis()
            )
            _uiState.value = initialState
            persistState(initialState)
            GenerationUploadScheduler.schedule(appContext, localJobId)
        }
    }

    fun retry() {
        viewModelScope.launch {
            val pending = pendingGenerationRepository.pendingRequestFlow.first()
            if (pending != null) {
                val state = GenerateUiState(
                    isRunning = true,
                    jobId = pending.localJobId,
                    status = "uploading",
                    phase = "uploading",
                    progress = pending.uploadedProgress(),
                    message = "准备重新上传素材",
                    prompt = pending.prompt,
                    template = pending.template,
                    mediaCount = pending.media.size,
                    visualProgressStartedAtMillis = System.currentTimeMillis()
                )
                _uiState.value = state
                persistState(state)
                GenerationUploadScheduler.schedule(appContext, pending.localJobId)
                return@launch
            }
            lastInput?.let { start(it) }
        }
    }

    fun consumeResumeRoute() {
        _uiState.update { it.copy(resumeRoute = null) }
    }

    fun clearPersistedState(resetUi: Boolean = true) {
        val existingJobId = _uiState.value.jobId
        GenerationUploadScheduler.cancel(appContext, existingJobId)
        JobPollingScheduler.cancel(appContext, existingJobId)
        viewModelScope.launch {
            val pending = pendingGenerationRepository.pendingRequestFlow.first()
            if (pending != null && (existingJobId.isNullOrBlank() || pending.localJobId == existingJobId)) {
                pending.media.forEach { media -> FileUtils.deleteStagedUri(appContext, media.uri) }
                pendingGenerationRepository.clear(pending.localJobId)
            }
            activeJobRepository.clear()
        }
        if (resetUi) {
            _uiState.value = GenerateUiState()
        }
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
                mediaCount = state.mediaCount,
                visualProgressStartedAtMillis = state.visualProgressStartedAtMillis.ifPositiveOrNow()
            )
        )
    }

    private fun GenerationInput.toPendingGenerationRequest(localJobId: String): PendingGenerationRequest {
        return PendingGenerationRequest(
            localJobId = localJobId,
            prompt = prompt,
            template = template,
            voice = voice,
            resolution = resolution,
            autoBgm = autoBgm,
            media = media.map {
                PendingUploadMedia(
                    uri = it.uri.toString(),
                    displayName = it.displayName,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes
                )
            },
            createdAtMillis = System.currentTimeMillis()
        )
    }

    private fun PersistedActiveJob.toUiState(includeResumeRoute: Boolean): GenerateUiState {
        return GenerateUiState(
            isRunning = status.isActiveStatus(),
            jobId = jobId,
            status = status,
            phase = phase,
            progress = progress,
            message = message.ifBlank { "恢复任务状态" },
            error = error,
            videoUrl = videoUrl,
            videoFullUrl = videoFullUrl,
            resumeRoute = if (includeResumeRoute) {
                if (videoFullUrl.isNullOrBlank()) "generate" else "result"
            } else {
                null
            },
            prompt = prompt,
            template = template,
            mediaCount = mediaCount,
            visualProgressStartedAtMillis = visualProgressStartedAtMillis.ifPositiveOrNow()
        )
    }

    private fun String.isActiveStatus(): Boolean {
        return this != "idle" && this != "done" && this != "failed"
    }

    private fun PendingGenerationRequest.uploadedProgress(): Int {
        if (media.isEmpty()) return 0
        return ((media.count { !it.uploadedFileId.isNullOrBlank() }.toFloat() / media.size.toFloat()) * 20f)
            .toInt()
            .coerceIn(0, 20)
    }

    private fun Long.ifPositiveOrNow(): Long {
        return if (this > 0L) this else System.currentTimeMillis()
    }
}
