package com.example.videomaker.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.util.DownloadUtils
import com.example.videomaker.viewmodel.HistoryViewModel
import com.example.videomaker.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryDetailScreen(
    viewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    jobId: String,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val record = state.records.firstOrNull { it.jobId == jobId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    LaunchedEffect(jobId) {
        if (record == null) {
            viewModel.loadHistory(force = !state.hasLoaded)
        }
    }

    fun startDownload() {
        val url = record?.videoFullUrl ?: return
        if (isDownloading) return
        scope.launch {
            error = null
            message = "正在下载..."
            isDownloading = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    DownloadUtils.downloadVideo(context, url, settingsState.apiToken)
                }
            }
            isDownloading = false
            result.onSuccess { uri ->
                savedUri = uri
                message = "已保存到系统 Movies/VideoMaker"
            }.onFailure { throwable ->
                error = throwable.message ?: "下载失败"
                message = null
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) startDownload() else error = "没有写入权限，无法保存视频"
        }
    )

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BackTopBar(
                onBack = onBack,
                title = "历史视频",
                subtitle = "可在此播放、下载或分享视频。"
            )
            when {
                record == null && state.isLoading -> InfoCard("正在加载历史视频...")
                record == null -> ErrorCard(state.error ?: "没有找到这条历史记录")
                else -> {
                    SoftSurfaceCard {
                        Text(record.prompt, style = MaterialTheme.typography.titleMedium)
                    }
                    InfoCard("生成时间：${formatTimestamp(record.createdAt)}")
                    SoftSurfaceCard(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                    ) {
                        HistoryVideoPlayer(url = record.videoFullUrl)
                    }
                    SoftPrimaryButton(
                        text = if (isDownloading) "正在下载..." else "下载视频",
                        onClick = {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                startDownload()
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SoftSecondaryButton(
                        text = "分享",
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                if (savedUri != null) {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } else {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, record.videoFullUrl)
                                }
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享视频"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            message?.let { InfoCard(it) }
            error?.let { ErrorCard(it) }
        }
    }
}

@Composable
private fun HistoryVideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(24.dp))
    )
}

private fun formatTimestamp(value: String): String {
    return value.substringBefore(".").replace("T", " ")
}
