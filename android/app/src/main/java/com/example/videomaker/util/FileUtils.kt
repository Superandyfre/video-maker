package com.example.videomaker.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SelectedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?
)

object FileUtils {
    private const val StagedUploadDirName = "upload_staging"
    private const val StagedFileRetentionMillis = 7L * 24L * 60L * 60L * 1000L

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

    fun stageForUpload(context: Context, media: SelectedMedia): SelectedMedia {
        cleanupOldStagedFiles(context)
        val targetDir = stagedUploadDir(context).apply { mkdirs() }
        val target = File(
            targetDir,
            "${System.currentTimeMillis()}-${UUID.randomUUID()}-${sanitizeFileName(media.displayName)}"
        )
        context.contentResolver.openInputStream(media.uri).use { input ->
            requireNotNull(input) { "无法读取所选素材" }
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        return media.copy(
            uri = Uri.fromFile(target),
            sizeBytes = target.length()
        )
    }

    fun toMultipart(context: Context, media: SelectedMedia): MultipartBody.Part {
        return toMultipart(
            context = context,
            uri = media.uri,
            displayName = media.displayName,
            mimeType = media.mimeType,
            sizeBytes = media.sizeBytes
        )
    }

    fun toMultipart(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long?
    ): MultipartBody.Part {
        val body = ContentUriRequestBody(context, uri, mimeType, sizeBytes)
        return MultipartBody.Part.createFormData("file", displayName, body)
    }

    fun deleteIfStaged(context: Context, media: SelectedMedia) {
        deleteStagedUri(context, media.uri.toString())
    }

    fun deleteStagedUri(context: Context, uriValue: String): Boolean {
        val uri = Uri.parse(uriValue)
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val candidate = File(path).canonicalFile
        val stagingDir = stagedUploadDir(context).canonicalFile
        if (!candidate.toPath().startsWith(stagingDir.toPath())) return false
        return candidate.delete()
    }

    private fun cleanupOldStagedFiles(context: Context) {
        val cutoff = System.currentTimeMillis() - StagedFileRetentionMillis
        stagedUploadDir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
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

    private fun stagedUploadDir(context: Context): File {
        return File(context.filesDir, StagedUploadDirName)
    }

    private fun sanitizeFileName(value: String): String {
        val sanitized = value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return sanitized.ifBlank { "asset" }
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
