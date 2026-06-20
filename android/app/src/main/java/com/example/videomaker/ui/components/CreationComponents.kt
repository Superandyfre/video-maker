package com.example.videomaker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videomaker.util.SelectedMedia
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun PromptComposer(
    prompt: String,
    selectedMedia: List<SelectedMedia>,
    canGenerate: Boolean,
    isBusy: Boolean,
    onPromptChange: (String) -> Unit,
    onAddMedia: () -> Unit,
    onRemoveMedia: (Int) -> Unit,
    onOpenTools: () -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.outline.copy(alpha = 0.14f), RoundedCornerShape(34.dp)),
        shape = RoundedCornerShape(34.dp),
        color = colors.surface.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 168.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (prompt.isBlank()) {
                            Text(
                                text = "描述你想生成的营销视频...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                        innerTextField()
                    }
                }
            )
            AnimatedVisibility(
                visible = selectedMedia.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 }
            ) {
                MediaAttachmentStrip(
                    media = selectedMedia,
                    onRemoveMedia = onRemoveMedia
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconActionButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "添加图片或视频素材",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAddMedia()
                    },
                    containerColor = colors.primaryContainer.copy(alpha = 0.76f),
                    contentColor = colors.onPrimaryContainer
                )
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGenerate()
                    },
                    enabled = canGenerate && !isBusy,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        disabledContainerColor = colors.primary.copy(alpha = 0.32f),
                        disabledContentColor = colors.onPrimary.copy(alpha = 0.72f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ToolChip(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.54f))
            .border(1.dp, colors.outline.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface
        )
    }
}

@Composable
fun MediaAttachmentStrip(
    media: List<SelectedMedia>,
    onRemoveMedia: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(media, key = { index, item -> "${item.uri}-$index" }) { index, item ->
            MediaAttachmentChip(
                media = item,
                onRemove = { onRemoveMedia(index) }
            )
        }
    }
}

@Composable
private fun MediaAttachmentChip(
    media: SelectedMedia,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isVideo = media.mimeType.startsWith("video/")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.58f))
            .border(1.dp, colors.outline.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
            .padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (isVideo) Icons.Rounded.VideoLibrary else Icons.Rounded.Image,
            contentDescription = null,
            tint = if (isVideo) colors.secondary else colors.tertiary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.width(128.dp)) {
            Text(
                text = media.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface
            )
            Text(
                text = media.sizeBytes?.let { formatFileSize(it) } ?: "大小未知",
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface.copy(alpha = 0.74f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "移除素材",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val units = listOf("KB", "MB", "GB", "TB")
    val digitGroup = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size)
    val value = bytes / 1024.0.pow(digitGroup.toDouble())
    return String.format("%.1f %s", value, units[digitGroup - 1])
}
