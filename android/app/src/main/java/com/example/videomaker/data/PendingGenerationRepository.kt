package com.example.videomaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pendingGenerationDataStore by preferencesDataStore(name = "video_maker_pending_generation")

data class PendingUploadMedia(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val uploadedFileId: String? = null
)

data class PendingGenerationRequest(
    val localJobId: String,
    val prompt: String,
    val template: String,
    val voice: String,
    val resolution: String,
    val autoBgm: Boolean,
    val media: List<PendingUploadMedia>,
    val createdAtMillis: Long
)

class PendingGenerationRepository(private val context: Context) {
    private val pendingRequestKey = stringPreferencesKey("pending_request")
    private val gson = Gson()

    val pendingRequestFlow: Flow<PendingGenerationRequest?> = context.pendingGenerationDataStore.data.map { preferences ->
        preferences[pendingRequestKey]?.let { json ->
            runCatching { gson.fromJson(json, PendingGenerationRequest::class.java) }.getOrNull()
        }
    }

    suspend fun save(request: PendingGenerationRequest) {
        context.pendingGenerationDataStore.edit { preferences ->
            preferences[pendingRequestKey] = gson.toJson(request)
        }
    }

    suspend fun markUploaded(localJobId: String, mediaIndex: Int, fileId: String): PendingGenerationRequest? {
        var updated: PendingGenerationRequest? = null
        context.pendingGenerationDataStore.edit { preferences ->
            val current = preferences[pendingRequestKey]?.let { json ->
                runCatching { gson.fromJson(json, PendingGenerationRequest::class.java) }.getOrNull()
            }
            if (current != null && current.localJobId == localJobId && mediaIndex in current.media.indices) {
                val nextMedia = current.media.mapIndexed { index, media ->
                    if (index == mediaIndex) media.copy(uploadedFileId = fileId) else media
                }
                val next = current.copy(media = nextMedia)
                preferences[pendingRequestKey] = gson.toJson(next)
                updated = next
            }
        }
        return updated
    }

    suspend fun clear(localJobId: String? = null) {
        context.pendingGenerationDataStore.edit { preferences ->
            if (localJobId == null) {
                preferences.remove(pendingRequestKey)
                return@edit
            }
            val current = preferences[pendingRequestKey]?.let { json ->
                runCatching { gson.fromJson(json, PendingGenerationRequest::class.java) }.getOrNull()
            }
            if (current?.localJobId == localJobId) {
                preferences.remove(pendingRequestKey)
            }
        }
    }
}
