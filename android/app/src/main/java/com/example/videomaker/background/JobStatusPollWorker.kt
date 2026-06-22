package com.example.videomaker.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.videomaker.data.ActiveJobRepository
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.PersistedActiveJob
import com.example.videomaker.data.SettingsRepository
import kotlinx.coroutines.flow.first

class JobStatusPollWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(JobPollingScheduler.jobIdKey()).orEmpty().trim()
        if (jobId.isBlank()) return Result.failure()

        val settings = SettingsRepository(applicationContext).settingsFlow.first()
        if (settings.baseUrl.isBlank()) return Result.retry()

        val activeJobRepository = ActiveJobRepository(applicationContext)
        return runCatching {
            val api = ApiClient.create(settings.baseUrl, settings.apiToken)
            val status = api.jobStatus(jobId)
            val existing = activeJobRepository.activeJobFlow.first()
            val videoFullUrl = status.videoUrl?.let { ApiClient.buildAbsoluteUrl(settings.baseUrl, it) }
            val stableProgress = if (status.status == "done") {
                100
            } else {
                nonDecreasingProgress(existing?.progress ?: 0, status.progress)
            }
            val visualProgressStartedAtMillis = visualProgressStartForProgress(existing, stableProgress)
            activeJobRepository.save(
                PersistedActiveJob(
                    jobId = status.jobId,
                    status = status.status,
                    phase = status.phase,
                    progress = stableProgress,
                    message = if (status.status == "done") "视频生成成功" else status.message,
                    error = status.error,
                    videoUrl = status.videoUrl,
                    videoFullUrl = videoFullUrl,
                    prompt = existing?.prompt,
                    template = existing?.template,
                    mediaCount = existing?.mediaCount ?: 0,
                    visualProgressStartedAtMillis = visualProgressStartedAtMillis
                )
            )
            if (status.status != "done" && status.status != "failed") {
                JobPollingScheduler.schedule(applicationContext, status.jobId)
            }
            Result.success()
        }.getOrElse { error ->
            val existing = activeJobRepository.activeJobFlow.first()
            if (existing != null) {
                activeJobRepository.save(
                    existing.copy(
                        message = "后台同步失败",
                        error = ApiClient.toUserMessage(error)
                    )
                )
            }
            Result.retry()
        }
    }

    private fun nonDecreasingProgress(currentProgress: Int, reportedProgress: Int): Int {
        return maxOf(currentProgress, reportedProgress).coerceIn(0, 100)
    }

    private fun visualProgressStartForProgress(existing: PersistedActiveJob?, nextProgress: Int): Long {
        val existingStart = existing?.visualProgressStartedAtMillis ?: 0L
        return if (existing == null || existingStart <= 0L || nextProgress > existing.progress) {
            System.currentTimeMillis()
        } else {
            existingStart
        }
    }
}
