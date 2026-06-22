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

object GenerationUploadScheduler {
    private const val LOCAL_JOB_ID_KEY = "local_job_id"

    fun schedule(context: Context, localJobId: String) {
        if (localJobId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<GenerationUploadWorker>()
            .setInputData(workDataOf(LOCAL_JOB_ID_KEY to localJobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(localJobId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, localJobId: String?) {
        val value = localJobId?.trim().orEmpty()
        if (value.isBlank() || !value.startsWith("local-")) return
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(value))
    }

    internal fun localJobIdKey(): String = LOCAL_JOB_ID_KEY

    private fun uniqueWorkName(localJobId: String): String = "video-maker-upload-$localJobId"
}
