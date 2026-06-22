package com.example.videomaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.activeJobDataStore by preferencesDataStore(name = "video_maker_active_job")

data class PersistedActiveJob(
    val jobId: String,
    val status: String,
    val phase: String?,
    val progress: Int,
    val message: String,
    val error: String?,
    val videoUrl: String?,
    val videoFullUrl: String?,
    val prompt: String? = null,
    val template: String? = null,
    val mediaCount: Int = 0,
    val visualProgressStartedAtMillis: Long = 0L
)

class ActiveJobRepository(private val context: Context) {
    private val jobIdKey = stringPreferencesKey("job_id")
    private val statusKey = stringPreferencesKey("status")
    private val phaseKey = stringPreferencesKey("phase")
    private val progressKey = intPreferencesKey("progress")
    private val messageKey = stringPreferencesKey("message")
    private val errorKey = stringPreferencesKey("error")
    private val videoUrlKey = stringPreferencesKey("video_url")
    private val videoFullUrlKey = stringPreferencesKey("video_full_url")
    private val promptKey = stringPreferencesKey("prompt")
    private val templateKey = stringPreferencesKey("template")
    private val mediaCountKey = intPreferencesKey("media_count")
    private val visualProgressStartedAtMillisKey = longPreferencesKey("visual_progress_started_at_millis")

    val activeJobFlow: Flow<PersistedActiveJob?> = context.activeJobDataStore.data.map { preferences ->
        val jobId = preferences[jobIdKey].orEmpty().trim()
        if (jobId.isBlank()) {
            null
        } else {
            PersistedActiveJob(
                jobId = jobId,
                status = preferences[statusKey].orEmpty().ifBlank { "queued" },
                phase = preferences[phaseKey],
                progress = preferences[progressKey] ?: 0,
                message = preferences[messageKey].orEmpty(),
                error = preferences[errorKey],
                videoUrl = preferences[videoUrlKey],
                videoFullUrl = preferences[videoFullUrlKey],
                prompt = preferences[promptKey],
                template = preferences[templateKey],
                mediaCount = preferences[mediaCountKey] ?: 0,
                visualProgressStartedAtMillis = preferences[visualProgressStartedAtMillisKey] ?: 0L
            )
        }
    }

    suspend fun save(job: PersistedActiveJob) {
        context.activeJobDataStore.edit { preferences ->
            preferences[jobIdKey] = job.jobId
            preferences[statusKey] = job.status
            job.phase?.let { preferences[phaseKey] = it } ?: preferences.remove(phaseKey)
            preferences[progressKey] = job.progress
            preferences[messageKey] = job.message
            job.error?.let { preferences[errorKey] = it } ?: preferences.remove(errorKey)
            job.videoUrl?.let { preferences[videoUrlKey] = it } ?: preferences.remove(videoUrlKey)
            job.videoFullUrl?.let { preferences[videoFullUrlKey] = it } ?: preferences.remove(videoFullUrlKey)
            job.prompt?.let { preferences[promptKey] = it }
            job.template?.let { preferences[templateKey] = it }
            if (job.mediaCount > 0) {
                preferences[mediaCountKey] = job.mediaCount
            }
            if (job.visualProgressStartedAtMillis > 0L) {
                preferences[visualProgressStartedAtMillisKey] = job.visualProgressStartedAtMillis
            } else {
                preferences.remove(visualProgressStartedAtMillisKey)
            }
        }
    }

    suspend fun clear() {
        context.activeJobDataStore.edit { preferences -> preferences.clear() }
    }
}
