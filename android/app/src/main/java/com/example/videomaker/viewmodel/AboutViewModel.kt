package com.example.videomaker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videomaker.BuildConfig
import com.example.videomaker.data.ApiClient
import com.example.videomaker.data.AppUpdateResponse
import com.example.videomaker.data.SettingsRepository
import com.example.videomaker.util.UpdateInstallUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AboutUiState(
    val baseUrl: String = "",
    val apiToken: String = "",
    val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    val currentVersionName: String = BuildConfig.VERSION_NAME,
    val isChecking: Boolean = false,
    val latest: AppUpdateResponse? = null,
    val resolvedApkUrl: String? = null,
    val updateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int? = null,
    val downloadedApkPath: String? = null,
    val installPermissionRequired: Boolean = false,
    val installEventId: Long = 0L,
    val message: String? = null,
    val error: String? = null
)

class AboutViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private var hasAutoChecked = false
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { settings ->
                _uiState.update {
                    it.copy(baseUrl = settings.baseUrl, apiToken = settings.apiToken)
                }
                if (!hasAutoChecked && settings.baseUrl.isNotBlank()) {
                    hasAutoChecked = true
                    checkForUpdates(settings.baseUrl, settings.apiToken)
                }
            }
        }
    }

    fun checkForUpdates() {
        val state = _uiState.value
        checkForUpdates(state.baseUrl, state.apiToken)
    }

    fun startDownload() {
        val state = _uiState.value
        val latest = state.latest
        val apkUrl = state.resolvedApkUrl
        if (latest == null || !state.updateAvailable) {
            _uiState.update { it.copy(error = "当前没有可下载的新版本。", message = null) }
            return
        }
        if (apkUrl.isNullOrBlank()) {
            _uiState.update { it.copy(error = "版本清单缺少 APK 下载地址。", message = null) }
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>().applicationContext
                val enqueued = UpdateInstallUtils.enqueueApkDownload(
                    context = context,
                    apkUrl = apkUrl,
                    versionName = latest.versionName,
                    apiToken = state.apiToken
                )
                _uiState.update {
                    it.copy(
                        isDownloading = true,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        installPermissionRequired = false,
                        message = "开始下载安装包...",
                        error = null
                    )
                }

                while (isActive) {
                    val snapshot = UpdateInstallUtils.queryApkDownload(
                        context = context,
                        downloadId = enqueued.downloadId,
                        filePath = enqueued.filePath
                    )
                    if (snapshot.isFinished) {
                        if (snapshot.isSuccessful) {
                            val canInstall = UpdateInstallUtils.canRequestPackageInstalls(context)
                            _uiState.update {
                                it.copy(
                                    isDownloading = false,
                                    downloadProgress = 100,
                                    downloadedApkPath = enqueued.filePath,
                                    installPermissionRequired = !canInstall,
                                    installEventId = if (canInstall) System.currentTimeMillis() else it.installEventId,
                                    message = if (canInstall) {
                                        "安装包已下载，正在打开安装确认。"
                                    } else {
                                        "安装包已下载，请先授权安装未知应用。"
                                    },
                                    error = null
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isDownloading = false,
                                    downloadProgress = snapshot.progressPercent,
                                    error = snapshot.message,
                                    message = null
                                )
                            }
                        }
                        break
                    }

                    _uiState.update {
                        it.copy(
                            isDownloading = true,
                            downloadProgress = snapshot.progressPercent,
                            message = snapshot.message,
                            error = null
                        )
                    }
                    delay(800)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        error = ApiClient.toUserMessage(error),
                        message = null
                    )
                }
            }
        }
    }

    fun requestInstall() {
        val path = _uiState.value.downloadedApkPath
        if (path.isNullOrBlank()) {
            _uiState.update { it.copy(error = "请先下载安装包。", message = null) }
            return
        }
        val context = getApplication<Application>().applicationContext
        val canInstall = UpdateInstallUtils.canRequestPackageInstalls(context)
        _uiState.update {
            it.copy(
                installPermissionRequired = !canInstall,
                installEventId = if (canInstall) System.currentTimeMillis() else it.installEventId,
                message = if (canInstall) "正在打开安装确认。" else "请先授权安装未知应用。",
                error = null
            )
        }
    }

    fun onInstallPermissionResult() {
        val context = getApplication<Application>().applicationContext
        if (UpdateInstallUtils.canRequestPackageInstalls(context)) {
            requestInstall()
        } else {
            _uiState.update {
                it.copy(
                    installPermissionRequired = true,
                    message = null,
                    error = "未获得安装权限，无法继续安装 APK。"
                )
            }
        }
    }

    fun consumeInstallEvent() {
        _uiState.update { it.copy(installEventId = 0L) }
    }

    fun onInstallLaunchFailed(message: String) {
        _uiState.update { it.copy(error = message, message = null) }
    }

    private fun checkForUpdates(baseUrl: String, apiToken: String) {
        if (baseUrl.isBlank()) {
            _uiState.update {
                it.copy(
                    isChecking = false,
                    latest = null,
                    resolvedApkUrl = null,
                    updateAvailable = false,
                    message = null,
                    error = "请先在设置中填写后端 Base URL。"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isChecking = true,
                    message = null,
                    error = null,
                    latest = null,
                    resolvedApkUrl = null,
                    updateAvailable = false,
                    downloadProgress = null,
                    downloadedApkPath = null,
                    installPermissionRequired = false
                )
            }
            runCatching {
                ApiClient.create(baseUrl, apiToken).latestAndroidVersion()
            }.onSuccess { latest ->
                val updateAvailable = latest.versionCode > BuildConfig.VERSION_CODE
                val resolvedApkUrl = latest.apkUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ApiClient.buildAbsoluteUrl(baseUrl, it) }
                val missingApkUrl = updateAvailable && resolvedApkUrl.isNullOrBlank()
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        latest = latest,
                        resolvedApkUrl = resolvedApkUrl,
                        updateAvailable = updateAvailable && !missingApkUrl,
                        message = when {
                            missingApkUrl -> null
                            updateAvailable -> "发现新版本 ${latest.versionName.orEmpty().ifBlank { latest.versionCode.toString() }}"
                            else -> "已是最新版本。"
                        },
                        error = if (missingApkUrl) "版本清单缺少 APK 下载地址。" else null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        latest = null,
                        resolvedApkUrl = null,
                        updateAvailable = false,
                        message = null,
                        error = ApiClient.toUserMessage(error)
                    )
                }
            }
        }
    }
}
