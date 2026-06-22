package com.example.videomaker.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun GenerationLiveStatus(
    status: String,
    phase: String?,
    progress: Int,
    message: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val displayedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0, 100).toFloat(),
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "displayed-progress"
    )
    val transition = rememberInfiniteTransition(label = "live-status")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1180),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.outline.copy(alpha = 0.14f), RoundedCornerShape(34.dp)),
        shape = RoundedCornerShape(34.dp),
        color = colors.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp
    ) {
        BoxWithConstraints {
            val showStatusPill = maxWidth >= 600.dp

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (showStatusPill) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.primaryContainer.copy(alpha = 0.66f + pulse * 0.12f))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WaveBars(wave = wave)
                        Text(
                            text = phaseLabel(status = status, phase = phase),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = "${displayedProgress.roundToInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.onSurface
                )
                LinearProgressIndicator(
                    progress = { displayedProgress.coerceIn(0f, 100f) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = colors.primary,
                    trackColor = colors.primaryContainer.copy(alpha = 0.42f)
                )
                Text(
                    text = message.ifBlank { "正在准备生成任务" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WaveBars(wave: Float) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) { index ->
            val height = (8f + 12f * ((wave + index * 0.22f) % 1f)).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .alpha(0.72f + index * 0.06f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary)
            )
        }
    }
    Spacer(modifier = Modifier.size(2.dp))
}

private fun phaseLabel(status: String, phase: String?): String {
    return when ((phase ?: status).lowercase()) {
        "uploading" -> "上传素材"
        "queued" -> "排队中"
        "starting" -> "启动渲染"
        "rendering_segments" -> "生成画面"
        "rendering" -> "渲染中"
        "done" -> "已完成"
        "failed" -> "生成失败"
        else -> phase ?: status.ifBlank { "生成中" }
    }
}
