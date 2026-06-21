package com.example.videomaker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ApkDownloadProgress(
    val percent: Int?,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val message: String
)

data class ApkDownloadResult(
    val filePath: String,
    val isSuccessful: Boolean,
    val message: String
)

object UpdateInstallUtils {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        versionName: String?,
        expectedSha256: String?,
        apiToken: String,
        onProgress: (ApkDownloadProgress) -> Unit
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val safeVersion = versionName
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "latest"
        val fileName = "video-maker-update-$safeVersion-${System.currentTimeMillis()}.apk"
        val downloadDir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "无法访问应用下载目录"
        }.apply { mkdirs() }
        val destination = File(downloadDir, fileName)
        if (destination.exists()) destination.delete()
        val normalizedExpectedSha256 = normalizeExpectedSha256(expectedSha256)
            ?: return@withContext ApkDownloadResult(
                filePath = destination.absolutePath,
                isSuccessful = false,
                message = "版本清单缺少有效的 SHA-256 校验值"
            )

        val requestBuilder = Request.Builder().url(apkUrl)
        if (apiToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiToken")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext ApkDownloadResult(
                    filePath = destination.absolutePath,
                    isSuccessful = false,
                    message = "下载失败：HTTP ${response.code}"
                )
            }
            val body = response.body ?: run {
                response.close()
                return@withContext ApkDownloadResult(
                    filePath = destination.absolutePath,
                    isSuccessful = false,
                    message = "下载失败：响应为空"
                )
            }
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportedPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(
                                    ApkDownloadProgress(
                                        percent = percent,
                                        bytesDownloaded = totalRead,
                                        totalBytes = totalBytes,
                                        message = "正在下载 $percent%"
                                    )
                                )
                            }
                        } else if (totalRead % (512 * 1024) < buffer.size) {
                            onProgress(
                                ApkDownloadProgress(
                                    percent = null,
                                    bytesDownloaded = totalRead,
                                    totalBytes = totalBytes,
                                    message = "正在下载 ${formatBytes(totalRead)}"
                                )
                            )
                        }
                    }
                }
            }

            response.close()
            val finalSize = destination.length()
            if (finalSize <= 0L) {
                return@withContext ApkDownloadResult(
                    filePath = destination.absolutePath,
                    isSuccessful = false,
                    message = "下载完成但文件为空"
                )
            }
            val actualSha256 = digest.digest().toHex()
            if (actualSha256 != normalizedExpectedSha256) {
                runCatching { destination.delete() }
                return@withContext ApkDownloadResult(
                    filePath = destination.absolutePath,
                    isSuccessful = false,
                    message = "安装包校验失败，已拒绝安装"
                )
            }

            ApkDownloadResult(
                filePath = destination.absolutePath,
                isSuccessful = true,
                message = "安装包已下载"
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            runCatching { if (destination.exists()) destination.delete() }
            throw cancellation
        } catch (e: Exception) {
            runCatching { if (destination.exists()) destination.delete() }
            ApkDownloadResult(
                filePath = destination.absolutePath,
                isSuccessful = false,
                message = "下载失败：${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun normalizeExpectedSha256(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace(Regex("\\s"), "")
            ?: return null
        return cleaned.takeIf { Regex("^[a-f0-9]{64}$").matches(it) }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun installPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun launchInstaller(context: Context, filePath: String) {
        val file = File(filePath)
        require(file.exists() && file.length() > 0L) { "安装包文件不存在或为空" }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var idx = 0
        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0
            idx++
        }
        return String.format("%.1f %s", value, units[idx])
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
