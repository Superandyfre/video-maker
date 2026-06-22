package com.example.videomaker.background

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.videomaker.data.ActiveJobRepository
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.PendingGenerationRepository
import com.example.videomaker.data.PendingGenerationRequest
import com.example.videomaker.data.PersistedActiveJob
import com.example.videomaker.data.SettingsRepository
import com.example.videomaker.data.SmartJobRequest
import com.example.videomaker.data.VoiceConfig
import com.example.videomaker.util.FileUtils
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.roundToInt

class GenerationUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val localJobId = inputData.getString(GenerationUploadScheduler.localJobIdKey()).orEmpty().trim()
        if (localJobId.isBlank()) return Result.failure()

        val pendingRepository = PendingGenerationRepository(applicationContext)
        val activeJobRepository = ActiveJobRepository(applicationContext)
        val pending = pendingRepository.pendingRequestFlow.first()
            ?.takeIf { it.localJobId == localJobId }
            ?: return Result.failure()

        saveActiveJob(
            repository = activeJobRepository,
            pending = pending,
            jobId = localJobId,
            status = "uploading",
            phase = "uploading",
            progress = pending.uploadedProgress(),
            message = "准备上传素材",
            error = null
        )

        val settings = SettingsRepository(applicationContext).settingsFlow.first()
        if (settings.baseUrl.isBlank()) {
            saveActiveJob(
                repository = activeJobRepository,
                pending = pending,
                jobId = localJobId,
                status = "failed",
                phase = "failed",
                progress = pending.uploadedProgress(),
                message = "上传失败",
                error = "请先在设置中填写后端 Base URL"
            )
            return Result.failure()
        }

        return runCatching {
            val api = ApiClient.create(settings.baseUrl, settings.apiToken)
            val fileIds = pending.media.mapIndexed { index, media ->
                media.uploadedFileId?.takeIf { it.isNotBlank() } ?: run {
                    saveActiveJob(
                        repository = activeJobRepository,
                        pending = pending,
                        jobId = localJobId,
                        status = "uploading",
                        phase = "uploading",
                        progress = uploadProgress(index, pending.media.size),
                        message = "上传素材 ${index + 1}/${pending.media.size}",
                        error = null
                    )
                    val response = api.upload(
                        FileUtils.toMultipart(
                            context = applicationContext,
                            uri = Uri.parse(media.uri),
                            displayName = media.displayName,
                            mimeType = media.mimeType,
                            sizeBytes = media.sizeBytes
                        )
                    )
                    pendingRepository.markUploaded(localJobId, index, response.fileId)
                    saveActiveJob(
                        repository = activeJobRepository,
                        pending = pending,
                        jobId = localJobId,
                        status = "uploading",
                        phase = "uploading",
                        progress = uploadProgress(index + 1, pending.media.size),
                        message = "上传素材 ${index + 1}/${pending.media.size}",
                        error = null
                    )
                    response.fileId
                }
            }

            saveActiveJob(
                repository = activeJobRepository,
                pending = pending,
                jobId = localJobId,
                status = "queued",
                phase = "queued",
                progress = 25,
                message = "创建生成任务",
                error = null
            )
            val job = api.createSmartJob(
                SmartJobRequest(
                    template = pending.template,
                    prompt = pending.prompt,
                    assets = fileIds,
                    voice = VoiceConfig(speaker = pending.voice),
                    resolution = pending.resolution,
                    autoBgm = pending.autoBgm
                )
            )
            saveActiveJob(
                repository = activeJobRepository,
                pending = pending,
                jobId = job.jobId,
                status = job.status,
                phase = "queued",
                progress = 30,
                message = "任务已创建",
                error = null
            )
            pending.media.forEach { media -> FileUtils.deleteStagedUri(applicationContext, media.uri) }
            pendingRepository.clear(localJobId)
            JobPollingScheduler.schedule(applicationContext, job.jobId, delaySeconds = 2)
            Result.success()
        }.getOrElse { error ->
            val userMessage = ApiClient.toUserMessage(error)
            if (shouldRetry(error)) {
                saveActiveJob(
                    repository = activeJobRepository,
                    pending = pending,
                    jobId = localJobId,
                    status = "uploading",
                    phase = "uploading",
                    progress = pending.uploadedProgress(),
                    message = "上传中断，等待网络恢复",
                    error = userMessage
                )
                Result.retry()
            } else {
                saveActiveJob(
                    repository = activeJobRepository,
                    pending = pending,
                    jobId = localJobId,
                    status = "failed",
                    phase = "failed",
                    progress = pending.uploadedProgress(),
                    message = "上传失败",
                    error = userMessage
                )
                Result.failure()
            }
        }
    }

    private suspend fun saveActiveJob(
        repository: ActiveJobRepository,
        pending: PendingGenerationRequest,
        jobId: String,
        status: String,
        phase: String?,
        progress: Int,
        message: String,
        error: String?
    ) {
        val existing = repository.activeJobFlow.first()
        val stableProgress = if (existing?.jobId == jobId) {
            maxOf(existing.progress, progress).coerceIn(0, 100)
        } else {
            progress.coerceIn(0, 100)
        }
        repository.save(
            PersistedActiveJob(
                jobId = jobId,
                status = status,
                phase = phase,
                progress = stableProgress,
                message = message,
                error = error,
                videoUrl = null,
                videoFullUrl = null,
                prompt = pending.prompt,
                template = pending.template,
                mediaCount = pending.media.size,
                visualProgressStartedAtMillis = visualProgressStartForProgress(existing, jobId, stableProgress)
            )
        )
    }

    private fun uploadProgress(uploadedCount: Int, totalCount: Int): Int {
        if (totalCount <= 0) return 0
        return ((uploadedCount.toFloat() / totalCount.toFloat()) * 20f).roundToInt().coerceIn(0, 20)
    }

    private fun PendingGenerationRequest.uploadedProgress(): Int {
        return uploadProgress(media.count { !it.uploadedFileId.isNullOrBlank() }, media.size)
    }

    private fun visualProgressStartForProgress(
        existing: PersistedActiveJob?,
        jobId: String,
        nextProgress: Int
    ): Long {
        val existingStart = existing?.visualProgressStartedAtMillis ?: 0L
        return if (existing == null || existing.jobId != jobId || existingStart <= 0L || nextProgress > existing.progress) {
            System.currentTimeMillis()
        } else {
            existingStart
        }
    }

    private fun shouldRetry(error: Throwable): Boolean {
        return when (error) {
            is IOException -> true
            is HttpException -> error.code() in setOf(408, 429, 500, 502, 503, 504)
            else -> false
        }
    }
}
