package com.example.videomaker.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink

data class SelectedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?
)

object FileUtils {
    fun describe(context: Context, uri: Uri): SelectedMedia {
        val resolver = context.contentResolver
        var name = "asset-${System.currentTimeMillis()}"
        var size: Long? = null
        resolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val normalizedName = ensureExtension(name, mimeType)
        return SelectedMedia(uri, normalizedName, mimeType, size)
    }

    fun toMultipart(context: Context, media: SelectedMedia): MultipartBody.Part {
        val body = ContentUriRequestBody(context, media.uri, media.mimeType, media.sizeBytes)
        return MultipartBody.Part.createFormData("file", media.displayName, body)
    }

    private fun ensureExtension(name: String, mimeType: String): String {
        if (name.contains(".")) return name
        val extension = when (mimeType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            else -> ""
        }
        return name + extension
    }
}

private class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mimeType: String,
    private val sizeBytes: Long?
) : RequestBody() {
    override fun contentType() = mimeType.toMediaTypeOrNull()

    override fun contentLength(): Long = sizeBytes ?: -1L

    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选素材" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
            }
        }
    }
}

