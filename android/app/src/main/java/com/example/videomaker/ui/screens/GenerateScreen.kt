package com.example.videomaker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videomaker.ui.components.AppScreenScaffold
import com.example.videomaker.ui.components.BackTopBar
import com.example.videomaker.ui.components.ErrorCard
import com.example.videomaker.ui.components.GenerationLiveStatus
import com.example.videomaker.ui.components.SoftPrimaryButton
import com.example.videomaker.ui.components.SoftSurfaceCard
import com.example.videomaker.ui.components.StatusPill
import com.example.videomaker.viewmodel.GenerateViewModel

@Composable
fun GenerateScreen(
    viewModel: GenerateViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.videoFullUrl) {
        if (!state.videoFullUrl.isNullOrBlank()) {
            onDone()
        }
    }

    AppScreenScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            BackTopBar(
                onBack = onBack,
                title = if (state.status == "failed") "生成遇到问题" else "正在生成视频"
            )
            GenerationLiveStatus(
                status = state.status,
                phase = state.phase,
                progress = state.progress,
                message = state.message,
                visualProgressStartedAtMillis = state.visualProgressStartedAtMillis,
                visualProgressKey = state.jobId
            )
            if (!state.prompt.isNullOrBlank()) {
                SoftSurfaceCard(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
                ) {
                    StatusPill(text = "创作请求")
                    Text(
                        text = state.prompt.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "模板：${state.template ?: "默认"} · 素材：${state.mediaCount.coerceAtLeast(0)} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            state.error?.let {
                ErrorCard(it)
                SoftPrimaryButton(
                    text = "重试",
                    onClick = viewModel::retry,
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
