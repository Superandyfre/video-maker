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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.InfoCard
import com.example.videomaker.ui.components.SoftSecondaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.util.DownloadUtils
import com.example.videomaker.viewmodel.GenerateViewModel
import com.example.videomaker.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ResultScreen(
    generateViewModel: GenerateViewModel,
    settingsViewModel: SettingsViewModel,
    onBackHome: () -> Unit
) {
    val generateState by generateViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val videoUrl = generateState.videoFullUrl
    val promptText = generateState.prompt?.takeIf { it.isNotBlank() } ?: "未保存 prompt"
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    fun startDownload() {
        val url = videoUrl ?: return
        if (isDownloading) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                onBack = onBackHome,
                title = "生成响应"
            )
            if (videoUrl.isNullOrBlank()) {
                ErrorCard("没有可播放的视频地址")
            } else {
                SoftSurfaceCard(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    VideoPlayer(url = videoUrl, apiToken = settingsState.apiToken)
                }
                SoftSurfaceCard(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                ) {
                    StatusPill(text = "已完成")
                    Text(
                        text = promptText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "模板：${generateState.template ?: "智能模板"} · 素材：${generateState.mediaCount.coerceAtLeast(0)} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ResultActionButton(
                        text = if (isDownloading) "下载中" else "下载",
                        icon = Icons.Rounded.Download,
                        primary = true,
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                startDownload()
                            }
                        }
                    )
                    ResultActionButton(
                        text = "分享",
                        icon = Icons.Rounded.Share,
                        primary = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                if (savedUri != null) {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } else {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, videoUrl)
                                }
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享视频"))
                        }
                    )
                }
            }
            message?.let { InfoCard(it) }
            error?.let { ErrorCard(it) }
        }
    }
}

@Composable
private fun ResultActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 54.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(icon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 54.dp),
            shape = RoundedCornerShape(22.dp)
        ) {
            Icon(icon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text)
        }
    }
}

@Composable
private fun VideoPlayer(url: String, apiToken: String) {
    val context = LocalContext.current
    val player = remember(url, apiToken) {
        val headers = if (apiToken.isNotBlank()) {
            mapOf("Authorization" to "Bearer $apiToken")
        } else {
            emptyMap()
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
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
