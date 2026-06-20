package com.example.videomaker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.videomaker.data.AppUpdateResponse
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.IconActionButton
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.SectionHeader
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.util.UpdateInstallUtils
import com.example.videomaker.viewmodel.AboutUiState
import com.example.videomaker.viewmodel.AboutViewModel

@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val installPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onInstallPermissionResult()
    }

    LaunchedEffect(state.installEventId) {
        if (state.installEventId == 0L) return@LaunchedEffect
        val apkPath = state.downloadedApkPath
        if (!apkPath.isNullOrBlank()) {
            runCatching {
                UpdateInstallUtils.launchInstaller(context, apkPath)
            }.onFailure { error ->
                viewModel.onInstallLaunchFailed(error.message ?: "无法打开系统安装器。")
            }
        }
        viewModel.consumeInstallEvent()
    }

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconActionButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
                IconActionButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "重新检查",
                    onClick = viewModel::checkForUpdates,
                    enabled = !state.isChecking && !state.isDownloading
                )
            }

            SectionHeader(
                title = "关于与更新"
            )

            CurrentVersionCard(state = state)
            UpdateStatusCard(
                state = state,
                onCheck = viewModel::checkForUpdates,
                onDownload = viewModel::startDownload,
                onInstall = viewModel::requestInstall,
                onRequestInstallPermission = {
                    installPermissionLauncher.launch(UpdateInstallUtils.installPermissionIntent(context))
                }
            )

            state.message?.let { InfoCard(it) }
            state.error?.let { ErrorCard(it) }
        }
    }
}

@Composable
private fun CurrentVersionCard(state: AboutUiState) {
    SoftSurfaceCard {
        StatusPill(text = "当前应用")
        Text(
            text = "营销视频生成器",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = "当前版本 ${state.currentVersionName} (${state.currentVersionCode})",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = if (state.baseUrl.isBlank()) {
                "更新源未配置"
            } else {
                "更新源 ${state.baseUrl.trimEnd('/')}/app/android/latest.json"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun UpdateStatusCard(
    state: AboutUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRequestInstallPermission: () -> Unit
) {
    SoftSurfaceCard {
        StatusPill(
            text = statusLabel(state),
            containerColor = if (state.updateAvailable) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.86f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.76f)
            },
            contentColor = if (state.updateAvailable) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
        )
        Text(
            text = latestVersionText(state.latest),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp)
        )
        state.latest?.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        state.resolvedApkUrl?.takeIf { state.updateAvailable }?.let { apkUrl ->
            Text(
                text = "安装包 $apkUrl",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        state.latest?.sha256?.takeIf { it.isNotBlank() }?.let { sha256 ->
            Text(
                text = "SHA-256 $sha256",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (state.isDownloading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
            Text(
                text = state.downloadProgress?.let { "下载进度 $it%" } ?: "正在下载...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SoftSecondaryButton(
            text = if (state.isChecking) "检查中..." else "重新检查",
            onClick = onCheck,
            enabled = !state.isChecking && !state.isDownloading,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.installPermissionRequired) {
            SoftPrimaryButton(
                text = "授权安装权限",
                onClick = onRequestInstallPermission,
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        } else if (!state.downloadedApkPath.isNullOrBlank()) {
            SoftPrimaryButton(
                text = "安装更新",
                onClick = onInstall,
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        } else if (state.updateAvailable) {
            SoftPrimaryButton(
                text = "下载并安装",
                onClick = onDownload,
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }
}

private fun statusLabel(state: AboutUiState): String {
    return when {
        state.isChecking -> "检查中"
        state.isDownloading -> "下载中"
        state.installPermissionRequired -> "需要授权"
        !state.downloadedApkPath.isNullOrBlank() -> "已下载"
        state.updateAvailable -> "发现新版本"
        state.latest != null -> "已是最新"
        else -> "等待检查"
    }
}

@Composable
private fun latestVersionText(latest: AppUpdateResponse?): String {
    if (latest == null) return "尚未获取最新版本信息"
    val versionName = latest.versionName?.takeIf { it.isNotBlank() } ?: "未命名版本"
    return "最新版本 $versionName (${latest.versionCode})"
}
