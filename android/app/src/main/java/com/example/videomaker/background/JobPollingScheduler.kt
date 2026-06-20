package com.example.videomaker.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object JobPollingScheduler {
    private const val JOB_ID_KEY = "job_id"

    fun schedule(context: Context, jobId: String, delaySeconds: Long = 10L) {
        if (jobId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<JobStatusPollWorker>()
            .setInputData(workDataOf(JOB_ID_KEY to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(jobId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, jobId: String?) {
        val value = jobId?.trim().orEmpty()
        if (value.isBlank()) return
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(value))
    }

    internal fun jobIdKey(): String = JOB_ID_KEY

    private fun uniqueWorkName(jobId: String): String = "video-maker-job-poll-$jobId"
}
