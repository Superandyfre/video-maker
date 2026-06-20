package com.example.videomaker.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadUtils {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    fun downloadVideo(context: Context, url: String, apiToken: String): Uri {
        val requestBuilder = Request.Builder().url(url)
        if (apiToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiToken")
        }
        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("下载失败：HTTP ${response.code}")
        }
        val body = response.body ?: throw IllegalStateException("下载失败：响应为空")
        val fileName = "video-maker-${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, fileName, body.byteStream())
        } else {
            saveLegacy(context, fileName, body.byteStream())
        }
    }

    private fun saveWithMediaStore(context: Context, fileName: String, input: java.io.InputStream): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoMaker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建媒体文件")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "无法写入媒体文件" }
            input.use { source -> source.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveLegacy(context: Context, fileName: String, input: java.io.InputStream): Uri {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val appDir = File(moviesDir, "VideoMaker").apply { mkdirs() }
        val outputFile = File(appDir, fileName)
        FileOutputStream(outputFile).use { output ->
            input.use { source -> source.copyTo(output) }
        }
        return Uri.fromFile(outputFile)
    }
}

