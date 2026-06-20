package com.example.videomaker.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

data class EnqueuedApkDownload(
    val downloadId: Long,
    val filePath: String
)

data class ApkDownloadSnapshot(
    val isFinished: Boolean,
    val isSuccessful: Boolean,
    val progressPercent: Int?,
    val message: String
)

object UpdateInstallUtils {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun enqueueApkDownload(
        context: Context,
        apkUrl: String,
        versionName: String?,
        apiToken: String
    ): EnqueuedApkDownload {
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

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Video Maker $safeVersion")
            .setDescription("正在下载更新安装包")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        if (apiToken.isNotBlank()) {
            request.addRequestHeader("Authorization", "Bearer $apiToken")
        }

        val manager = downloadManager(context)
        return EnqueuedApkDownload(
            downloadId = manager.enqueue(request),
            filePath = destination.absolutePath
        )
    }

    fun queryApkDownload(context: Context, downloadId: Long, filePath: String): ApkDownloadSnapshot {
        val manager = downloadManager(context)
        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) {
                return ApkDownloadSnapshot(
                    isFinished = true,
                    isSuccessful = false,
                    progressPercent = null,
                    message = "找不到下载任务"
                )
            }

            val status = cursor.intColumn(DownloadManager.COLUMN_STATUS)
            val downloaded = cursor.longColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = cursor.longColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null

            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = File(filePath)
                    ApkDownloadSnapshot(
                        isFinished = true,
                        isSuccessful = file.exists() && file.length() > 0L,
                        progressPercent = 100,
                        message = if (file.exists() && file.length() > 0L) {
                            "安装包已下载"
                        } else {
                            "安装包下载完成但文件不可用"
                        }
                    )
                }

                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.intColumn(DownloadManager.COLUMN_REASON)
                    ApkDownloadSnapshot(
                        isFinished = true,
                        isSuccessful = false,
                        progressPercent = progress,
                        message = "下载失败：${failureReason(reason)}"
                    )
                }

                DownloadManager.STATUS_PAUSED -> {
                    val reason = cursor.intColumn(DownloadManager.COLUMN_REASON)
                    ApkDownloadSnapshot(
                        isFinished = false,
                        isSuccessful = false,
                        progressPercent = progress,
                        message = "下载暂停：${pauseReason(reason)}"
                    )
                }

                DownloadManager.STATUS_PENDING -> ApkDownloadSnapshot(
                    isFinished = false,
                    isSuccessful = false,
                    progressPercent = progress,
                    message = "等待下载..."
                )

                else -> ApkDownloadSnapshot(
                    isFinished = false,
                    isSuccessful = false,
                    progressPercent = progress,
                    message = if (progress != null) "正在下载 $progress%" else "正在下载..."
                )
            }
        }
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

    private fun downloadManager(context: Context): DownloadManager {
        return context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private fun Cursor.intColumn(name: String): Int = getInt(getColumnIndexOrThrow(name))

    private fun Cursor.longColumn(name: String): Long = getLong(getColumnIndexOrThrow(name))

    private fun failureReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "无法继续下载"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "找不到存储设备"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据异常"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常状态"
            DownloadManager.ERROR_UNKNOWN -> "未知错误"
            else -> "错误码 $reason"
        }
    }

    private fun pauseReason(reason: Int): String {
        return when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "等待重试"
            DownloadManager.PAUSED_UNKNOWN -> "未知原因"
            else -> "状态码 $reason"
        }
    }
}
